// Package api 装配 HTTP 层：路由、中间件、鉴权分流、SSE。
package api

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"altair/internal/auth"
	"altair/internal/store"
)

const (
	sessionCookie = "altair_session"
	sessionTTL    = 30 * 24 * time.Hour
	maxBodyBytes  = 8 << 20 // 8MB：截图 multipart 上限
)

type ctxKey string

const (
	ctxUser    ctxKey = "user"
	ctxExpires ctxKey = "expires"
)

// Server 持有全部依赖。
type Server struct {
	st              *store.Store
	dataDir         string
	version         string
	legacyDeviceAPI bool
	staticDir       string
	limiter         *auth.Limiter
	hub             *Hub

	mu         sync.Mutex
	lastReport map[string]int64 // 设备上报最小间隔限流
	lastOnline map[string]bool  // 上一次的在线判定，用于上下线事件
}

// New 建服务。staticDir 非空时，前端产物从磁盘读（开发用），否则用 go:embed 的产物。
func New(st *store.Store, dataDir, version string, legacyDeviceAPI bool, staticDir string) *Server {
	return &Server{
		st:              st,
		dataDir:         dataDir,
		version:         version,
		legacyDeviceAPI: legacyDeviceAPI,
		staticDir:       staticDir,
		limiter:         auth.NewLimiter(500*time.Millisecond, 30*time.Second),
		hub:             NewHub(),
		lastReport:      make(map[string]int64),
		lastOnline:      make(map[string]bool),
	}
}

// Handler 返回装配好的路由。
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()

	mux.HandleFunc("GET /healthz", s.handleHealth)

	// ---- 设备 API（Token 鉴权）----
	mux.Handle("POST /api/v1/device/report", s.device(s.handleDeviceReport))
	mux.Handle("GET /api/v1/device/config", s.device(s.handleDeviceConfig))
	mux.Handle("POST /api/v1/device/screenshot", s.device(s.handleDeviceScreenshot))
	mux.Handle("POST /api/v1/device/ack", s.device(s.handleDeviceAck))

	// ---- 旧设备端点（安全网，默认关闭；见 --legacy-device-api）----
	// 用途：硬切换窗口期里，尚未升级到 v1 协议的设备仍能被看见。
	if s.legacyDeviceAPI {
		mux.Handle("POST /api/report", s.device(s.handleDeviceReport))
		mux.Handle("GET /api/config", s.device(s.handleDeviceConfig))
		slog.Warn("已启用旧设备 API 兼容层（/api/report、/api/config）")
	}

	// ---- 面板 API（会话 Cookie 鉴权）----
	mux.HandleFunc("POST /api/v1/panel/login", s.handleLogin)
	mux.Handle("POST /api/v1/panel/logout", s.panel(s.handleLogout))
	mux.Handle("GET /api/v1/panel/me", s.panel(s.handleMe))
	mux.Handle("GET /api/v1/panel/devices", s.panel(s.handleDevices))
	mux.Handle("GET /api/v1/panel/devices/{id}", s.panel(s.handleDeviceDetail))
	mux.Handle("GET /api/v1/panel/devices/{id}/history", s.panel(s.handleDeviceHistory))
	mux.Handle("GET /api/v1/panel/devices/{id}/logs", s.panel(s.handleDeviceLogs))
	mux.Handle("PUT /api/v1/panel/devices/{id}", s.panel(s.handleDeviceMeta))
	mux.Handle("PUT /api/v1/panel/devices/{id}/interval", s.panel(s.handleDeviceInterval))
	mux.Handle("POST /api/v1/panel/devices/{id}/command", s.panel(s.handleDeviceCommand))
	mux.Handle("POST /api/v1/panel/devices/batch-command", s.panel(s.handleBatchCommand))
	mux.Handle("GET /api/v1/panel/configs/{scope}", s.panel(s.handleConfigGet))
	mux.Handle("PUT /api/v1/panel/configs/{scope}", s.panel(s.handleConfigPut))
	mux.Handle("GET /api/v1/panel/configs/{scope}/revisions", s.panel(s.handleConfigRevisions))
	mux.Handle("POST /api/v1/panel/configs/{scope}/rollback", s.panel(s.handleConfigRollback))
	mux.Handle("GET /api/v1/panel/screenshots", s.panel(s.handleScreenshots))
	mux.Handle("GET /api/v1/panel/screenshots/{id}", s.panel(s.handleScreenshotFile))
	mux.Handle("DELETE /api/v1/panel/screenshots/{id}", s.panel(s.handleScreenshotDelete))
	mux.Handle("GET /api/v1/panel/audit", s.panel(s.handleAudit))
	mux.Handle("GET /api/v1/panel/settings", s.panel(s.handleSettingsGet))
	mux.Handle("PUT /api/v1/panel/settings", s.panel(s.handleSettingsPut))
	mux.Handle("GET /api/v1/panel/events", s.panel(s.handleEvents))

	// ---- SPA（go:embed）：其余全部落到静态资源，未知路径回退 index.html ----
	mux.Handle("GET /", s.staticHandler())

	return s.common(mux)
}

// ---------------------------------------------------------------- 中间件

type statusWriter struct {
	http.ResponseWriter
	code int
}

func (w *statusWriter) WriteHeader(code int) {
	w.code = code
	w.ResponseWriter.WriteHeader(code)
}

func (w *statusWriter) Write(b []byte) (int, error) {
	if w.code == 0 {
		w.code = http.StatusOK
	}
	return w.ResponseWriter.Write(b)
}

// Flush 让 SSE 能透传（http.Flusher）。
func (w *statusWriter) Flush() {
	if f, ok := w.ResponseWriter.(http.Flusher); ok {
		f.Flush()
	}
}

func (s *Server) common(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		sw := &statusWriter{ResponseWriter: w}
		defer func() {
			if rec := recover(); rec != nil {
				slog.Error("panic 已恢复", "path", r.URL.Path, "panic", rec)
				writeJSON(sw, http.StatusInternalServerError, map[string]any{"ok": false, "error": "服务器内部错误"})
			}
			if !strings.HasPrefix(r.URL.Path, "/api/v1/panel/events") {
				slog.Debug("http", "method", r.Method, "path", r.URL.Path,
					"status", sw.code, "ms", time.Since(start).Milliseconds(), "ip", clientIP(r))
			}
		}()
		// 内网工具，禁掉嗅探与缓存；API 一律 no-store，避免面板读到陈旧的设备状态。
		sw.Header().Set("X-Content-Type-Options", "nosniff")
		if strings.HasPrefix(r.URL.Path, "/api/") {
			sw.Header().Set("Cache-Control", "no-store")
		}
		next.ServeHTTP(sw, r)
	})
}

// ---------------------------------------------------------------- 鉴权

// panel 包装面板端点：要求有效会话 Cookie。
func (s *Server) panel(h http.HandlerFunc) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		c, err := r.Cookie(sessionCookie)
		if err != nil || c.Value == "" {
			writeErr(w, http.StatusUnauthorized, "未登录")
			return
		}
		user, expires, ok := s.st.SessionUser(c.Value)
		if !ok {
			writeErr(w, http.StatusUnauthorized, "会话已失效，请重新登录")
			return
		}
		ctx := context.WithValue(r.Context(), ctxUser, user)
		ctx = context.WithValue(ctx, ctxExpires, expires)
		h(w, r.WithContext(ctx))
	})
}

// device 包装设备端点：校验 X-Altair-Token（也允许 ?token= 便于 curl 排障）。
func (s *Server) device(h http.HandlerFunc) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		tok := r.Header.Get("X-Altair-Token")
		if tok == "" {
			tok = r.URL.Query().Get("token")
		}
		want := auth.DeviceToken(s.st)
		if tok == "" || want == "" || subtleCompare(tok, want) == false {
			time.Sleep(300 * time.Millisecond) // 轻微退避，拖慢暴力尝试
			slog.Warn("设备 Token 无效", "ip", clientIP(r), "path", r.URL.Path)
			writeErr(w, http.StatusUnauthorized, "invalid or missing device token")
			return
		}
		h(w, r)
	})
}

// ---------------------------------------------------------------- 工具

func writeJSON(w http.ResponseWriter, code int, v any) {
	body, err := json.Marshal(v)
	if err != nil {
		slog.Error("序列化响应失败", "err", err)
		http.Error(w, `{"ok":false,"error":"序列化失败"}`, http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	_, _ = w.Write(body)
}

func writeErr(w http.ResponseWriter, code int, msg string) {
	writeJSON(w, code, map[string]any{"ok": false, "error": msg})
}

func readJSON(r *http.Request, v any) error {
	defer func() { _ = r.Body.Close() }()
	dec := json.NewDecoder(http.MaxBytesReader(nil, r.Body, maxBodyBytes))
	return dec.Decode(v)
}

func clientIP(r *http.Request) string {
	if v := r.Header.Get("X-Forwarded-For"); v != "" {
		return strings.TrimSpace(strings.Split(v, ",")[0])
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func userOf(r *http.Request) string {
	if v, ok := r.Context().Value(ctxUser).(string); ok {
		return v
	}
	return "unknown"
}

func expiresOf(r *http.Request) int64 {
	if v, ok := r.Context().Value(ctxExpires).(int64); ok {
		return v
	}
	return 0
}

func subtleCompare(a, b string) bool {
	if len(a) != len(b) {
		return false
	}
	var diff byte
	for i := 0; i < len(a); i++ {
		diff |= a[i] ^ b[i]
	}
	return diff == 0
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "ts": time.Now().UnixMilli(), "version": s.version})
}

var errNotFound = errors.New("not found")
