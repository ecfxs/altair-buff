package api

import (
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"altair/internal/model"
	"altair/internal/store"
)

const (
	maxShotBytes  = 2 << 20 // 单张截图 2MB
	reportMinGap  = 3000    // 同一设备上报最小间隔（毫秒），防误配打爆服务器
	onlineWindow  = 180_000 // 3 分钟内视为在线
)

// handleDeviceReport 处理设备上报。
//
// 关键设计：响应里直接带回 desired 与 configRevision，
// 设备只在 appliedRevision 不一致时才去拉配置 —— 一轮往返完成上报 + 收指令。
func (s *Server) handleDeviceReport(w http.ResponseWriter, r *http.Request) {
	var rep model.Report
	if err := readJSON(r, &rep); err != nil {
		writeErr(w, http.StatusBadRequest, "上报体解析失败: "+err.Error())
		return
	}
	rep.DeviceID = strings.TrimSpace(rep.DeviceID)
	if rep.DeviceID == "" {
		writeErr(w, http.StatusBadRequest, "缺少 deviceId")
		return
	}
	now := time.Now().UnixMilli()
	if rep.ProtocolVersion == 0 {
		rep.ProtocolVersion = 1
	}
	if rep.TS == 0 {
		rep.TS = now
	}

	// 同一设备上报过频直接拒绝（通常意味着设备端周期配错）
	s.mu.Lock()
	if last, ok := s.lastReport[rep.DeviceID]; ok && now-last < reportMinGap {
		s.mu.Unlock()
		writeErr(w, http.StatusTooManyRequests, "上报过频")
		return
	}
	s.lastReport[rep.DeviceID] = now
	s.mu.Unlock()

	prev, err := s.st.Device(rep.DeviceID)
	if err != nil {
		slog.Error("读设备失败", "err", err, "device", rep.DeviceID)
	}
	wasOffline := prev == nil || now-prev.LastSeen >= onlineWindow

	if err := s.st.UpsertReport(&rep, now); err != nil {
		slog.Error("落盘上报失败", "err", err, "device", rep.DeviceID)
		writeErr(w, http.StatusInternalServerError, "落盘失败")
		return
	}

	d, err := s.st.Device(rep.DeviceID)
	if err != nil || d == nil {
		slog.Error("回读设备失败", "err", err, "device", rep.DeviceID)
		writeErr(w, http.StatusInternalServerError, "回读失败")
		return
	}
	desired, _ := s.st.Desired(rep.DeviceID)
	cmds, _ := s.st.PendingCommands(rep.DeviceID)

	s.hub.Publish("report", map[string]any{
		"deviceId": rep.DeviceID,
		"ts":       rep.TS,
		"state":    rep.Engine.State,
		"running":  rep.Engine.Running,
	})
	if wasOffline {
		slog.Info("设备上线", "device", rep.DeviceID, "model", rep.Model)
		s.hub.Publish("device-online", map[string]any{"deviceId": rep.DeviceID})
	}

	// 只在状态变化时打一条服务器日志，避免 60s 一次的噪音
	if prev == nil || prev.Report == nil ||
		prev.Report.Engine.State != rep.Engine.State ||
		prev.Report.Engine.Running != rep.Engine.Running {
		slog.Info("上报", "device", rep.DeviceID, "state", rep.Engine.State,
			"running", rep.Engine.Running, "cycles", rep.Engine.CycleCount, "fg", rep.Foreground)
	}

	writeJSON(w, http.StatusOK, model.ReportResponse{
		OK:             true,
		TS:             now,
		Desired:        desired,
		ConfigRevision: s.configRevisionFor(rep.DeviceID),
		NextReportInMs: s.effectiveInterval(d),
		Commands:       cmds,
	})
}

// handleDeviceConfig 下发配置（设备仅在 revision 不一致时调用）。
func (s *Server) handleDeviceConfig(w http.ResponseWriter, r *http.Request) {
	deviceID := strings.TrimSpace(r.URL.Query().Get("deviceId"))

	cfg, rev, err := s.st.Config(deviceID)
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
		cfg, rev = &c, c.Revision
	}
	cfg.Revision = rev

	desired, err := s.st.Desired(deviceID)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "读期望状态失败")
		return
	}
	slog.Info("下发配置", "device", deviceIDOr(deviceID), "revision", rev, "desired_running", desired.Running)
	writeJSON(w, http.StatusOK, model.ConfigEnvelope{Config: *cfg, Desired: desired})
}

// handleDeviceScreenshot 接收截图（multipart：meta + file）。
func (s *Server) handleDeviceScreenshot(w http.ResponseWriter, r *http.Request) {
	if err := r.ParseMultipartForm(maxShotBytes + (1 << 20)); err != nil {
		writeErr(w, http.StatusBadRequest, "multipart 解析失败: "+err.Error())
		return
	}
	var meta struct {
		DeviceID string `json:"deviceId"`
		TS       int64  `json:"ts"`
		Label    string `json:"label"`
	}
	if raw := r.FormValue("meta"); raw != "" {
		if err := json.Unmarshal([]byte(raw), &meta); err != nil {
			writeErr(w, http.StatusBadRequest, "meta 不是合法 JSON: "+err.Error())
			return
		}
	}
	meta.DeviceID = strings.TrimSpace(meta.DeviceID)
	if meta.DeviceID == "" {
		writeErr(w, http.StatusBadRequest, "meta.deviceId 必填")
		return
	}
	if meta.TS == 0 {
		meta.TS = time.Now().UnixMilli()
	}

	file, _, err := r.FormFile("file")
	if err != nil {
		writeErr(w, http.StatusBadRequest, "缺少 file 字段")
		return
	}
	defer func() { _ = file.Close() }()

	head := make([]byte, 512)
	n, err := io.ReadFull(file, head)
	if err != nil && err != io.ErrUnexpectedEOF && err != io.EOF {
		writeErr(w, http.StatusBadRequest, "读文件失败")
		return
	}
	head = head[:n]
	ct := http.DetectContentType(head)
	var ext string
	switch {
	case strings.HasPrefix(ct, "image/jpeg"):
		ext = ".jpg"
	case strings.HasPrefix(ct, "image/png"):
		ext = ".png"
	default:
		writeErr(w, http.StatusBadRequest, "只接受 jpeg/png，实际是 "+ct)
		return
	}

	dir := filepath.Join(s.dataDir, "shots", sanitizeDeviceID(meta.DeviceID))
	if err := os.MkdirAll(dir, 0o755); err != nil {
		writeErr(w, http.StatusInternalServerError, "建目录失败")
		return
	}
	name := fmt.Sprintf("%d_%s%s", meta.TS, store.RandomSecret(6), ext)
	full := filepath.Join(dir, name)
	out, err := os.Create(full)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "建文件失败")
		return
	}
	written, err := io.Copy(out, io.MultiReader(strings.NewReader(string(head)), io.LimitReader(file, maxShotBytes)))
	closeErr := out.Close()
	if err != nil || closeErr != nil {
		_ = os.Remove(full)
		writeErr(w, http.StatusInternalServerError, "写文件失败")
		return
	}
	if written > maxShotBytes {
		_ = os.Remove(full)
		writeErr(w, http.StatusRequestEntityTooLarge, fmt.Sprintf("截图超过 %d 字节", maxShotBytes))
		return
	}

	id, err := s.st.AddScreenshot(meta.DeviceID, full, meta.TS, written, meta.Label)
	if err != nil {
		_ = os.Remove(full)
		writeErr(w, http.StatusInternalServerError, "记录截图失败")
		return
	}
	slog.Info("收到截图", "device", meta.DeviceID, "bytes", written, "id", id)
	s.hub.Publish("screenshot", map[string]any{"deviceId": meta.DeviceID, "id": id, "ts": meta.TS})
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "id": id, "ts": meta.TS})
}

// handleDeviceAck 处理指令回执。
func (s *Server) handleDeviceAck(w http.ResponseWriter, r *http.Request) {
	var body struct {
		DeviceID   string   `json:"deviceId"`
		CommandIDs []string `json:"commandIds"`
		Result     string   `json:"result"`
	}
	if err := readJSON(r, &body); err != nil {
		writeErr(w, http.StatusBadRequest, "解析失败: "+err.Error())
		return
	}
	if body.DeviceID == "" {
		writeErr(w, http.StatusBadRequest, "缺少 deviceId")
		return
	}
	if err := s.st.AckCommands(body.DeviceID, body.CommandIDs, body.Result); err != nil {
		writeErr(w, http.StatusInternalServerError, "标记回执失败")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

// ---------------------------------------------------------------- 辅助

// configRevisionFor 返回该设备实际会拿到的配置版本（设备级优先，回退默认）。
func (s *Server) configRevisionFor(deviceID string) string {
	if deviceID != "" {
		if _, rev, err := s.st.Config(deviceID); err == nil && rev != "" {
			return rev
		}
	}
	_, rev, _ := s.st.Config("default")
	return rev
}

// effectiveInterval 返回该设备下次应隔多久上报。
func (s *Server) effectiveInterval(d *model.Device) int {
	if d != nil && d.ReportIntervalMs > 0 {
		return d.ReportIntervalMs
	}
	if v := s.st.Settings().DefaultReportIntervalMs; v > 0 {
		return v
	}
	return 60_000
}

func deviceIDOr(id string) string {
	if id == "" {
		return "(默认)"
	}
	return id
}

// sanitizeDeviceID 把 deviceId 收敛成安全的目录名。
//
// 为什么必须做：截图落盘路径是 dataDir/shots/<deviceId>/，而 deviceId 完全由设备端提供。
// 不处理的话，一个持有合法 Token 的设备（或伪造上报的中间人）就能用
// `"deviceId": "../../etc"` 把文件写到数据目录之外。这里只保留文件名安全字符。
func sanitizeDeviceID(id string) string {
	var b strings.Builder
	for _, r := range id {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z',
			r >= '0' && r <= '9', r == '-', r == '_', r == '.':
			b.WriteRune(r)
		}
	}
	s := strings.Trim(b.String(), ".")
	if s == "" {
		return "unknown"
	}
	if len(s) > 64 {
		s = s[:64]
	}
	return s
}
