package api

import "altair/internal/model"

// 本文件是面板 API 的响应形状（DTO）。
// 与 control/api/openapi.yaml 一一对应；改这里要同步改契约。

// DeviceSummary 是总览卡片所需的全部数据（**不含任何密钥**）。
//
// 对比旧实现：旧版 GET /api/devices 直接把整个存储（含明文面板口令）返回给浏览器，
// 这里改成显式白名单，杜绝凭据外泄。
type DeviceSummary struct {
	ID               string           `json:"id"`
	LastSeen         int64            `json:"lastSeen"`
	FirstSeen        int64            `json:"firstSeen"`
	Online           bool             `json:"online"`
	Model            string           `json:"model"`
	Android          string           `json:"android"`
	VersionName      string           `json:"versionName"`
	ProtocolVersion  int              `json:"protocolVersion"`
	AppliedRevision  string           `json:"appliedRevision"`
	ConfigRevision   string           `json:"configRevision"`
	ConfigInSync     bool             `json:"configInSync"`
	Group            string           `json:"group"`
	Tags             []string         `json:"tags"`
	Notes            string           `json:"notes,omitempty"`
	ReportIntervalMs int              `json:"reportIntervalMs"`
	Desired          model.Desired    `json:"desired"`
	Engine           *model.Engine    `json:"engine,omitempty"`
	Heartbeat        *model.Heartbeat `json:"heartbeat,omitempty"`
	Foreground       string           `json:"foreground"`
	Armed            bool             `json:"armed"`
	LatestShotID     string           `json:"latestScreenshotId,omitempty"`
	ReportTS         int64            `json:"reportTs"`
	Stats            DeviceStats      `json:"stats"`
}

// DeviceStats 是 24 小时统计。
type DeviceStats struct {
	Cycles24h    int     `json:"cycles24h"`
	OnlineRate   float64 `json:"onlineRate24h"`
}

// DevicesResponse 是 GET /devices 的响应。
type DevicesResponse struct {
	Devices               []DeviceSummary `json:"devices"`
	Totals                Totals          `json:"totals"`
	DefaultConfigRevision string          `json:"defaultConfigRevision"`
}

// Totals 是总览统计条。
type Totals struct {
	Total   int `json:"total"`
	Online  int `json:"online"`
	Running int `json:"running"`
	Cycles  int `json:"cycles"`
}

// DeviceDetail 是 GET /devices/{id} 的响应。
type DeviceDetail struct {
	Device  DeviceSummary     `json:"device"`
	Reports []model.ReportRow `json:"reports"`
}

// HistoryPoint 是曲线上的一个点。
type HistoryPoint struct {
	TS         int64    `json:"ts"`
	Running    bool     `json:"running"`
	CycleCount int      `json:"cycleCount"`
	BatteryPct *int     `json:"batteryPct,omitempty"`
	ThermalC   *float64 `json:"thermalC,omitempty"`
	NetRttMs   *int     `json:"netRttMs,omitempty"`
}

// ConfigEnvelope 是面板侧的配置包装（带来源信息）。
type ConfigEnvelope struct {
	Scope     string        `json:"scope"`
	Revision  string        `json:"revision"`
	Config    model.Config  `json:"config"`
	UpdatedAt int64         `json:"updatedAt"`
	UpdatedBy string        `json:"updatedBy"`
}

// Me 是 GET /me 的响应 —— 替代旧版把 Token 注入 HTML 的做法。
type Me struct {
	User        string `json:"user"`
	DeviceToken string `json:"deviceToken"`
	ExpiresAt   int64  `json:"expiresAt"`
}
