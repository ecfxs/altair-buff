// Package fake 是假设备流量发生器。
//
// 存在的意义：在没有云手机、没有真机的情况下，让前端开发和端到端验收
// 都能跑起来 —— 它按真实设备协议上报，并会真的执行服务器下发的启停期望状态。
// 这正是旧 check-assets.sh 里那套 DOM 桩想解决但解决不了的问题。
package fake

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"math/rand"
	"net/http"
	"time"
)

// Device 是一台假设备。
type Device struct {
	ID       string
	Server   string
	Token    string
	Interval time.Duration

	appliedRev string
	running    bool
	cycles     int
	nextDue    time.Time

	loggedInterval bool
}

// Run 启动 n 台假设备，直到 ctx 结束。
func Run(ctx context.Context, server, token string, n int, interval time.Duration) error {
	if interval < 2*time.Second {
		interval = 5 * time.Second
	}
	var devs []*Device
	for i := 1; i <= n; i++ {
		devs = append(devs, &Device{
			ID:       fmt.Sprintf("fake%02d", i),
			Server:   server,
			Token:    token,
			Interval: interval,
			running:  true,
			cycles:   rand.Intn(30),
			nextDue:  time.Now().Add(time.Duration(rand.Intn(300)) * time.Second),
		})
	}
	slog.Info("假设备已启动", "count", n, "server", server, "interval", interval.String())

	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	next := make([]time.Time, len(devs))
	for i := range devs {
		next[i] = time.Now()
	}
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-ticker.C:
			for i, d := range devs {
				if time.Now().Before(next[i]) {
					continue
				}
				wait, err := d.report(ctx)
				if err != nil {
					slog.Warn("假设备上报失败", "device", d.ID, "err", err)
					next[i] = time.Now().Add(2 * time.Second)
					continue
				}
				next[i] = time.Now().Add(wait)
			}
		}
	}
}

// report 上报一次并处理响应里的 desired / commands。
func (d *Device) report(ctx context.Context) (time.Duration, error) {
	if d.running && time.Now().After(d.nextDue) {
		d.cycles++
		d.nextDue = time.Now().Add(4 * time.Minute)
	}
	state := "IDLE"
	if d.running {
		state = "WAITING"
		if time.Until(d.nextDue) < 30*time.Second {
			state = "CASTING"
		}
	}
	battery := 60 + rand.Intn(40)
	thermal := 33 + rand.Float64()*8

	body := map[string]any{
		"deviceId":        d.ID,
		"protocolVersion": 1,
		"ts":              time.Now().UnixMilli(),
		"uptimeMs":        time.Since(started).Milliseconds(),
		"versionCode":     24,
		"versionName":     "0.24.0-fake",
		"model":           "Fake CloudPhone " + d.ID,
		"android":         "13",
		"targetPkg":       "com.nexon.mod",
		"foreground":      "com.nexon.mod",
		"armed":           d.running,
		"appliedRevision": d.appliedRev,
		"engine": map[string]any{
			"running":       d.running,
			"state":         state,
			"inputMethod":   "keyevent",
			"cycleCount":    d.cycles,
			"nextDueAt":     d.nextDue.UnixMilli(),
			"cyclePeriodMs": 240000,
			"lastResult":    "补 BUFF1 成功",
			"lastError":     "",
			"buffs": []map[string]any{
				{"idx": 1, "enabled": true, "key": 1, "durationMin": 5},
				{"idx": 2, "enabled": false, "key": 2, "durationMin": 5},
				{"idx": 3, "enabled": false, "key": 3, "durationMin": 5},
			},
		},
		"heartbeat": map[string]any{
			"batteryPct": battery,
			"charging":   true,
			"thermalC":   thermal,
			"memFreeMb":  800 + rand.Intn(1200),
			"netRttMs":   20 + rand.Intn(60),
		},
		"logTail": []string{
			fmt.Sprintf("[%s] 引擎心跳 state=%s cycles=%d", time.Now().Format("15:04:05"), state, d.cycles),
			fmt.Sprintf("[%s] 前台应用 com.nexon.mod", time.Now().Format("15:04:05")),
		},
	}
	raw, _ := json.Marshal(body)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, d.Server+"/api/v1/device/report", bytes.NewReader(raw))
	if err != nil {
		return 0, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Altair-Token", d.Token)

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return 0, err
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		return 0, fmt.Errorf("服务器返回 %s", resp.Status)
	}
	var out struct {
		Desired struct {
			Running bool `json:"running"`
			Rev     int  `json:"rev"`
		} `json:"desired"`
		ConfigRevision string `json:"configRevision"`
		NextReportInMs int    `json:"nextReportInMs"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return 0, err
	}

	// 真的执行期望状态（模拟设备端行为）
	if out.Desired.Rev > 0 && out.Desired.Running != d.running {
		slog.Info("假设备执行启停", "device", d.ID, "running", out.Desired.Running, "rev", out.Desired.Rev)
		d.running = out.Desired.Running
		if d.running {
			d.nextDue = time.Now().Add(4 * time.Minute)
		}
	}
	// 配置版本变化时"拉取"一次（模拟热重载）
	if out.ConfigRevision != "" && out.ConfigRevision != d.appliedRev {
		if err := d.pullConfig(ctx, out.ConfigRevision); err != nil {
			slog.Warn("假设备拉配置失败", "device", d.ID, "err", err)
		}
	}

	// 说明：真实设备会跟随服务端下发的 nextReportInMs。
	// 假设备是开发/演示工具，**按自己的 --interval 走**（否则默认 60s 一轮，
	// 前端开发时要盯着一屏不动的数据等一分钟），只把服务端的建议记一条日志。
	wait := d.Interval
	if out.NextReportInMs > 0 && time.Duration(out.NextReportInMs)*time.Millisecond != d.Interval {
		if !d.loggedInterval {
			d.loggedInterval = true
			slog.Info("服务端建议的上报周期与假设备自身周期不同（假设备按自身周期走）",
				"device", d.ID, "服务端建议", (time.Duration(out.NextReportInMs) * time.Millisecond).String(),
				"假设备", d.Interval.String())
		}
	}
	if wait < 2*time.Second {
		wait = 2 * time.Second
	}
	return wait, nil
}

func (d *Device) pullConfig(ctx context.Context, rev string) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet,
		d.Server+"/api/v1/device/config?deviceId="+d.ID, nil)
	if err != nil {
		return err
	}
	req.Header.Set("X-Altair-Token", d.Token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("拉配置返回 %s", resp.Status)
	}
	d.appliedRev = rev
	slog.Info("假设备已应用配置", "device", d.ID, "revision", rev)
	return nil
}

var started = time.Now()
