package api

import (
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"altair/internal/auth"
	"altair/internal/model"
)

// ---------------------------------------------------------------- 会话

func isHTTPS(r *http.Request) bool {
	if r.TLS != nil {
		return true
	}
	return strings.EqualFold(r.Header.Get("X-Forwarded-Proto"), "https")
}

// handleLogin 校验口令并下发 HttpOnly 会话 Cookie。
func (s *Server) handleLogin(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Username string `json:"username"`
		Password string `json:"password"`
	}
	if err := readJSON(r, &body); err != nil {
		writeErr(w, http.StatusBadRequest, "登录体解析失败: "+err.Error())
		return
	}
	body.Username = strings.TrimSpace(body.Username)
	ip := clientIP(r)
	key := ip + "|" + body.Username

	if ok, wait := s.limiter.Allow(key); !ok {
		writeJSON(w, http.StatusTooManyRequests, map[string]any{
			"ok":    false,
			"error": fmt.Sprintf("尝试过于频繁，请 %.0f 秒后再试", wait.Seconds()+0.5),
		})
		return
	}
	if !auth.CheckLogin(s.st, body.Username, body.Password) {
		s.limiter.Fail(key)
		s.st.AddAudit(body.Username, "login.fail", "", "口令不正确", ip)
		writeErr(w, http.StatusUnauthorized, "用户名或口令不正确")
		return
	}
	s.limiter.Reset(key)
	token, expires, err := s.st.CreateSession(body.Username, sessionTTL, ip, r.UserAgent())
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "建会话失败")
		return
	}
	http.SetCookie(w, &http.Cookie{
		Name:     sessionCookie,
		Value:    token,
		Path:     "/",
		HttpOnly: true,
		Secure:   isHTTPS(r),
		SameSite: http.SameSiteLaxMode,
		Expires:  time.UnixMilli(expires),
	})
	s.st.AddAudit(body.Username, "login.ok", "", "", ip)
	slog.Info("面板登录成功", "user", body.Username, "ip", ip)
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "user": body.Username, "expiresAt": expires})
}

// handleLogout 删除会话并清 Cookie。
func (s *Server) handleLogout(w http.ResponseWriter, r *http.Request) {
	if c, err := r.Cookie(sessionCookie); err == nil {
		s.st.DeleteSession(c.Value)
	}
	http.SetCookie(w, &http.Cookie{
		Name: sessionCookie, Value: "", Path: "/", HttpOnly: true,
		Secure: isHTTPS(r), SameSite: http.SameSiteLaxMode, MaxAge: -1,
	})
	s.st.AddAudit(userOf(r), "logout", "", "", clientIP(r))
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

// handleMe 返回当前会话与设备 Token（替代旧版 HTML 占位符注入）。
func (s *Server) handleMe(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, Me{
		User:        userOf(r),
		DeviceToken: auth.DeviceToken(s.st),
		ExpiresAt:   expiresOf(r),
	})
}

// ---------------------------------------------------------------- 设备

// handleDevices 总览列表 + 统计。**白名单字段，绝不含密钥。**
func (s *Server) handleDevices(w http.ResponseWriter, r *http.Request) {
	now := time.Now().UnixMilli()
	devs, err := s.st.Devices()
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "读设备失败")
		return
	}
	out := make([]DeviceSummary, 0, len(devs))
	var totals Totals
	for _, d := range devs {
		sum := s.summarize(d, now)
		out = append(out, sum)
		totals.Total++
		if sum.Online {
			totals.Online++
		}
		if sum.Engine != nil {
			if sum.Engine.Running {
				totals.Running++
			}
			totals.Cycles += sum.Engine.CycleCount
		}
	}
	writeJSON(w, http.StatusOK, DevicesResponse{
		Devices:               out,
		Totals:                totals,
		DefaultConfigRevision: s.configRevisionFor(""),
	})
}

// handleDeviceDetail 设备详情 + 最近上报。
func (s *Server) handleDeviceDetail(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	d, err := s.st.Device(id)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "读设备失败")
		return
	}
	if d == nil {
		writeErr(w, http.StatusNotFound, "设备不存在")
		return
	}
	limit := parseIntDefault(r.URL.Query().Get("reports"), 50, 1, 500)
	rows, err := s.st.RecentReports(id, limit)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "读上报失败")
		return
	}
	writeJSON(w, http.StatusOK, DeviceDetail{Device: s.summarize(*d, time.Now().UnixMilli()), Reports: rows})
}

// handleDeviceHistory 曲线数据。
func (s *Server) handleDeviceHistory(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	from := parseInt64Default(r.URL.Query().Get("from"), 0)
	to := parseInt64Default(r.URL.Query().Get("to"), 0)
	step := parseIntDefault(r.URL.Query().Get("step"), 300, 5, 86400)
	rows, err := s.st.History(id, from, to, step)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "读历史失败")
		return
	}
	points := make([]HistoryPoint, 0, len(rows))
	for _, row := range rows {
		points = append(points, HistoryPoint{
			TS: row.TS, Running: row.Running, CycleCount: row.CycleCount,
			BatteryPct: row.BatteryPct, ThermalC: row.ThermalC, NetRttMs: row.NetRttMs,
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{"points": points})
}

// handleDeviceLogs 日志检索。
func (s *Server) handleDeviceLogs(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	limit := parseIntDefault(r.URL.Query().Get("limit"), 200, 1, 2000)
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	lines, err := s.st.Logs(id, limit, q)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "读日志失败")
		return
	}
	if lines == nil {
		lines = []model.LogLine{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"lines": lines})
}

// handleDeviceMeta 更新设备的面板侧信息（目前只有备注），返回更新后的总览条目。
func (s *Server) handleDeviceMeta(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	var body struct {
		Notes *string `json:"notes"`
	}
	if err := readJSON(r, &body); err != nil {
		writeErr(w, http.StatusBadRequest, "解析失败: "+err.Error())
		return
	}
	d, err := s.st.Device(id)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "读设备失败")
		return
	}
	if d == nil {
		writeErr(w, http.StatusNotFound, "设备不存在")
		return
	}
	if body.Notes != nil {
		notes := strings.TrimSpace(*body.Notes)
		if runes := []rune(notes); len(runes) > 200 {
			notes = string(runes[:200])
		}
		if err := s.st.SetDeviceNotes(id, notes); err != nil {
			writeErr(w, http.StatusInternalServerError, "保存备注失败")
			return
		}
		s.st.AddAudit(userOf(r), "device.notes", id, notes, clientIP(r))
	}
	updated, err := s.st.Device(id)
	if err != nil || updated == nil {
		writeErr(w, http.StatusInternalServerError, "回读设备失败")
		return
	}
	writeJSON(w, http.StatusOK, s.summarize(*updated, time.Now().UnixMilli()))
}

// handleDeviceInterval 设定单台设备上报周期（0 = 用全局默认）。
func (s *Server) handleDeviceInterval(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	var body struct {
		ReportIntervalMs int `json:"reportIntervalMs"`
	}
	if err := readJSON(r, &body); err != nil {
		writeErr(w, http.StatusBadRequest, "解析失败: "+err.Error())
		return
	}
	ms := body.ReportIntervalMs
	if ms != 0 {
		if ms < 15_000 {
			ms = 15_000
		}
		if ms > 300_000 {
			ms = 300_000
		}
	}
	if err := s.st.SetDeviceInterval(id, ms); err != nil {
		writeErr(w, http.StatusInternalServerError, "保存失败")
		return
	}
	s.st.AddAudit(userOf(r), "device.interval", id, strconv.Itoa(ms), clientIP(r))
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "reportIntervalMs": ms})
}

// handleDeviceCommand 下发启停（写期望状态）。
func (s *Server) handleDeviceCommand(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	action, ok := s.readAction(w, r)
	if !ok {
		return
	}
	desired, err := s.st.SetDesired(id, action == "start", userOf(r))
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "写期望状态失败")
		return
	}
	s.st.AddAudit(userOf(r), "device."+action, id, fmt.Sprintf("rev=%d", desired.Rev), clientIP(r))
	s.hub.Publish("command", map[string]any{"deviceId": id, "action": action, "rev": desired.Rev})
	slog.Info("下发启停", "device", id, "action", action, "rev", desired.Rev, "by", userOf(r))
	writeJSON(w, http.StatusOK, desired)
}

// handleBatchCommand 批量启停。
func (s *Server) handleBatchCommand(w http.ResponseWriter, r *http.Request) {
	var body struct {
		IDs    []string `json:"ids"`
		Action string   `json:"action"`
	}
	if err := readJSON(r, &body); err != nil {
		writeErr(w, http.StatusBadRequest, "解析失败: "+err.Error())
		return
	}
	if body.Action != "start" && body.Action != "stop" {
		writeErr(w, http.StatusBadRequest, "action 只能是 start/stop")
		return
	}
	if len(body.IDs) == 0 {
		writeErr(w, http.StatusBadRequest, "ids 不能为空")
		return
	}
	applied := make([]string, 0, len(body.IDs))
	for _, id := range body.IDs {
		if _, err := s.st.SetDesired(id, body.Action == "start", userOf(r)); err != nil {
			slog.Warn("批量下发失败", "device", id, "err", err)
			continue
		}
		applied = append(applied, id)
	}
	s.st.AddAudit(userOf(r), "device.batch."+body.Action, strings.Join(applied, ","),
		fmt.Sprintf("%d 台", len(applied)), clientIP(r))
	s.hub.Publish("command", map[string]any{"action": body.Action, "ids": applied})
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "applied": applied})
}

// ---------------------------------------------------------------- 配置

// handleConfigGet 读生效配置（设备级优先，回退默认）。
func (s *Server) handleConfigGet(w http.ResponseWriter, r *http.Request) {
	scope := r.PathValue("scope")
	cfg, rev, err := s.st.Config(scope)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "读配置失败")
		return
	}
	if cfg == nil {
		cfg, rev, err = s.st.Config("default")
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "读默认配置失败")
			return
		}
	}
	if cfg == nil {
		c := model.DefaultConfig()
		cfg, rev = &c, ""
	}
	cfg.Revision = rev
	writeJSON(w, http.StatusOK, ConfigEnvelope{
		Scope: scope, Revision: rev, Config: *cfg, UpdatedAt: time.Now().UnixMilli(),
	})
}

// handleConfigPut 写配置：revision 由服务端生成。
func (s *Server) handleConfigPut(w http.ResponseWriter, r *http.Request) {
	scope := r.PathValue("scope")
	var incoming model.Config
	if err := readJSON(r, &incoming); err != nil {
		writeErr(w, http.StatusBadRequest, "配置解析失败: "+err.Error())
		return
	}
	incoming.Revision = "" // 忽略客户端指定的 revision
	incoming = normalizeConfig(incoming)

	rev, err := s.st.SetConfig(scope, incoming, userOf(r))
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "保存配置失败")
		return
	}
	incoming.Revision = rev
	s.st.AddAudit(userOf(r), "config.put", scope, "revision="+rev, clientIP(r))
	s.hub.Publish("config", map[string]any{"scope": scope, "revision": rev})
	slog.Info("配置已更新", "scope", deviceIDOr(scope), "revision", rev, "by", userOf(r))
	writeJSON(w, http.StatusOK, ConfigEnvelope{
		Scope: scope, Revision: rev, Config: incoming, UpdatedAt: time.Now().UnixMilli(), UpdatedBy: userOf(r),
	})
}

// handleConfigRevisions 历史版本。
func (s *Server) handleConfigRevisions(w http.ResponseWriter, r *http.Request) {
	scope := r.PathValue("scope")
	limit := parseIntDefault(r.URL.Query().Get("limit"), 50, 1, 200)
	revs, err := s.st.ConfigRevisions(scope, limit)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "读版本失败")
		return
	}
	if revs == nil {
		revs = []model.ConfigRevision{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"revisions": revs})
}

// handleConfigRollback 回滚到历史版本（生成新 revision）。
func (s *Server) handleConfigRollback(w http.ResponseWriter, r *http.Request) {
	scope := r.PathValue("scope")
	var body struct {
		Revision string `json:"revision"`
	}
	if err := readJSON(r, &body); err != nil {
		writeErr(w, http.StatusBadRequest, "解析失败: "+err.Error())
		return
	}
	old, err := s.st.ConfigByRevision(scope, body.Revision)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "读历史版本失败")
		return
	}
	if old == nil {
		writeErr(w, http.StatusNotFound, "历史版本不存在")
		return
	}
	rev, err := s.st.SetConfig(scope, *old, userOf(r))
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "回滚失败")
		return
	}
	old.Revision = rev
	s.st.AddAudit(userOf(r), "config.rollback", scope,
		fmt.Sprintf("%s -> %s", body.Revision, rev), clientIP(r))
	s.hub.Publish("config", map[string]any{"scope": scope, "revision": rev})
	writeJSON(w, http.StatusOK, ConfigEnvelope{
		Scope: scope, Revision: rev, Config: *old, UpdatedAt: time.Now().UnixMilli(), UpdatedBy: userOf(r),
	})
}

// ---------------------------------------------------------------- 截图

func (s *Server) handleScreenshots(w http.ResponseWriter, r *http.Request) {
	deviceID := r.URL.Query().Get("deviceId")
	limit := parseIntDefault(r.URL.Query().Get("limit"), 50, 1, 500)
	shots, err := s.st.Screenshots(deviceID, limit)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "读截图失败")
		return
	}
	if shots == nil {
		shots = []model.Screenshot{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"screenshots": shots})
}

func (s *Server) handleScreenshotFile(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	path, _, err := s.st.ScreenshotFile(id)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "读截图失败")
		return
	}
	if path == "" {
		writeErr(w, http.StatusNotFound, "截图不存在")
		return
	}
	w.Header().Set("Cache-Control", "private, max-age=300")
	switch strings.ToLower(filepath.Ext(path)) {
	case ".png":
		w.Header().Set("Content-Type", "image/png")
	default:
		w.Header().Set("Content-Type", "image/jpeg")
	}
	http.ServeFile(w, r, path)
}

func (s *Server) handleScreenshotDelete(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	path, err := s.st.DeleteScreenshot(id)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "删除失败")
		return
	}
	if path != "" {
		if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
			slog.Warn("删除截图文件失败", "path", path, "err", err)
		}
	}
	s.st.AddAudit(userOf(r), "screenshot.delete", id, path, clientIP(r))
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

// ---------------------------------------------------------------- 审计与设置

func (s *Server) handleAudit(w http.ResponseWriter, r *http.Request) {
	limit := parseIntDefault(r.URL.Query().Get("limit"), 100, 1, 1000)
	entries, err := s.st.Audits(limit, r.URL.Query().Get("actor"), r.URL.Query().Get("target"))
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "读审计失败")
		return
	}
	if entries == nil {
		entries = []model.AuditEntry{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"entries": entries})
}

func (s *Server) handleSettingsGet(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, s.st.Settings())
}

func (s *Server) handleSettingsPut(w http.ResponseWriter, r *http.Request) {
	var st model.Settings
	if err := readJSON(r, &st); err != nil {
		writeErr(w, http.StatusBadRequest, "解析失败: "+err.Error())
		return
	}
	if st.DefaultReportIntervalMs < 15_000 {
		st.DefaultReportIntervalMs = 15_000
	}
	if st.DefaultReportIntervalMs > 300_000 {
		st.DefaultReportIntervalMs = 300_000
	}
	if st.ReportRetentionDays < 1 {
		st.ReportRetentionDays = 1
	}
	if st.LogRetentionDays < 1 {
		st.LogRetentionDays = 1
	}
	if st.ScreenshotRetentionN < 1 {
		st.ScreenshotRetentionN = 1
	}
	if st.ScreenshotRetentionDays < 1 {
		st.ScreenshotRetentionDays = 1
	}
	if err := s.st.SaveSettings(st); err != nil {
		writeErr(w, http.StatusInternalServerError, "保存失败")
		return
	}
	s.st.AddAudit(userOf(r), "settings.put", "", fmt.Sprintf("%+v", st), clientIP(r))
	writeJSON(w, http.StatusOK, s.st.Settings())
}

// ---------------------------------------------------------------- 辅助

func (s *Server) readAction(w http.ResponseWriter, r *http.Request) (string, bool) {
	var body struct {
		Action string `json:"action"`
	}
	if err := readJSON(r, &body); err != nil {
		writeErr(w, http.StatusBadRequest, "解析失败: "+err.Error())
		return "", false
	}
	if body.Action != "start" && body.Action != "stop" {
		writeErr(w, http.StatusBadRequest, "action 只能是 start/stop")
		return "", false
	}
	return body.Action, true
}

// summarize 把设备行拼成总览卡片所需的形状。
func (s *Server) summarize(d model.Device, now int64) DeviceSummary {
	cfgRev := s.configRevisionFor(d.ID)
	sum := DeviceSummary{
		ID:               d.ID,
		LastSeen:         d.LastSeen,
		FirstSeen:        d.FirstSeen,
		Online:           d.Online(now),
		Model:            d.Model,
		Android:          d.Android,
		VersionName:      d.VersionName,
		ProtocolVersion:  d.ProtocolVersion,
		Group:            d.Group,
		Tags:             d.Tags,
		Notes:            d.Notes,
		ReportIntervalMs: s.effectiveInterval(&d),
		Desired:          d.Desired,
		ConfigRevision:   cfgRev,
		ConfigInSync:     true,
	}
	if d.Report != nil {
		sum.Engine = &d.Report.Engine
		sum.Heartbeat = &d.Report.Heartbeat
		sum.Foreground = d.Report.Foreground
		sum.Armed = d.Report.Armed
		sum.AppliedRevision = d.Report.AppliedRevision
		sum.ReportTS = d.Report.TS
		// 老 APK 不上报 appliedRevision，此时无法判断，按「同步」处理避免误报
		if d.Report.AppliedRevision != "" {
			sum.ConfigInSync = d.Report.AppliedRevision == cfgRev
		}
	}
	if id, err := s.st.LatestScreenshot(d.ID); err == nil {
		sum.LatestShotID = id
	}
	sum.Stats.Cycles24h, sum.Stats.OnlineRate, _ = s.st.DeviceStats(d.ID, now)
	return sum
}

// normalizeConfig 兜住面板传来的空值，保证设备侧拿到结构完整的配置。
func normalizeConfig(c model.Config) model.Config {
	if c.InputMethod != "touch" {
		c.InputMethod = "keyevent"
	}
	if c.PressMs <= 0 || c.PressMs > 5000 {
		c.PressMs = 90
	}
	if c.SkillPoints == nil {
		c.SkillPoints = [][]float64{}
	}
	// BUFF 固定 3 条，缺的用索引补齐（设备端按 idx 读取）
	fixed := make([]model.Buff, 3)
	for i := 0; i < 3; i++ {
		if i < len(c.Buff) {
			fixed[i] = c.Buff[i]
		}
		fixed[i].Idx = i + 1
		if fixed[i].Key < 1 || fixed[i].Key > 4 {
			fixed[i].Key = i + 1
		}
		if fixed[i].DurationMin < 1 || fixed[i].DurationMin > 240 {
			fixed[i].DurationMin = 5
		}
	}
	c.Buff = fixed
	return c
}

func parseIntDefault(s string, def, lo, hi int) int {
	n := def
	if s != "" {
		if v, err := strconv.Atoi(s); err == nil {
			n = v
		}
	}
	if n < lo {
		n = lo
	}
	if n > hi {
		n = hi
	}
	return n
}

func parseInt64Default(s string, def int64) int64 {
	if s == "" {
		return def
	}
	if v, err := strconv.ParseInt(s, 10, 64); err == nil {
		return v
	}
	return def
}
