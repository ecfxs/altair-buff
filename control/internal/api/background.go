package api

import (
	"context"
	"log/slog"
	"os"
	"time"
)

// StartBackground 起后台任务：上下线事件、数据保留清理、会话与限流清扫。
func (s *Server) StartBackground(ctx context.Context) {
	go s.onlineScanner(ctx)
	go s.retentionLoop(ctx)
}

// onlineScanner 定期检查设备是否掉线，并广播 device-online / device-offline。
//
// 为什么需要它：上线事件在 handleDeviceReport 里就地判断，
// 但**掉线是「什么都不发生」**，只能靠轮询发现。
func (s *Server) onlineScanner(ctx context.Context) {
	tick := time.NewTicker(20 * time.Second)
	defer tick.Stop()
	s.scanOnline() // 启动时先建立基线
	for {
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
			s.scanOnline()
		}
	}
}

func (s *Server) scanOnline() {
	now := time.Now().UnixMilli()
	devs, err := s.st.Devices()
	if err != nil {
		slog.Warn("上下线扫描失败", "err", err)
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, d := range devs {
		online := d.Online(now)
		prev, known := s.lastOnline[d.ID]
		s.lastOnline[d.ID] = online
		if known && prev && !online {
			slog.Info("设备掉线", "device", d.ID, "silent_ms", now-d.LastSeen)
			s.hub.Publish("device-offline", map[string]any{"deviceId": d.ID, "lastSeen": d.LastSeen})
		}
	}
}

// retentionLoop 定期清理过期数据。
func (s *Server) retentionLoop(ctx context.Context) {
	// 启动 1 分钟后先跑一次，之后每 6 小时一次
	first := time.NewTimer(time.Minute)
	defer first.Stop()
	select {
	case <-ctx.Done():
		return
	case <-first.C:
		s.runRetention()
	}
	tick := time.NewTicker(6 * time.Hour)
	defer tick.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
			s.runRetention()
		}
	}
}

func (s *Server) runRetention() {
	st := s.st.Settings()
	if err := s.st.Prune(st.ReportRetentionDays, st.LogRetentionDays); err != nil {
		slog.Warn("清理时间序列失败", "err", err)
	}
	paths, err := s.st.PruneScreenshots(st.ScreenshotRetentionN, st.ScreenshotRetentionDays)
	if err != nil {
		slog.Warn("清理截图记录失败", "err", err)
	}
	removed := 0
	for _, p := range paths {
		if err := os.Remove(p); err == nil {
			removed++
		}
	}
	if err := s.st.PruneSessions(); err != nil {
		slog.Warn("清理过期会话失败", "err", err)
	}
	s.limiter.Sweep()
	if removed > 0 || len(paths) > 0 {
		slog.Info("保留策略已执行", "截图删除", removed, "记录删除", len(paths),
			"保留天数", st.ReportRetentionDays)
	}
}
