// Package model 是集控服务的领域数据类型。
//
// 这些结构同时用于三处：HTTP 请求/响应、SQLite 读写、以及前端契约（api/openapi.yaml）。
// 改动字段时三处要一起改 —— 契约是唯一来源，见 control/api/openapi.yaml。
package model

// Buff 是一条 BUFF 补键配置。
//
// 时长单位是**秒**（v0.24.10 起，设置界面从分钟改为秒，默认 280 秒）。
// 旧字段 DurationMin 保留并同时下发：万一线上还是老版本 APK / 老面板，
// 读 durationMin 也不会拿到 0（兼容期过了再删）。
type Buff struct {
	Idx         int  `json:"idx"`
	Enabled     bool `json:"enabled"`
	Key         int  `json:"key"`
	DurationSec int  `json:"durationSec"`
	DurationMin int  `json:"durationMin,omitempty"`
}

// DefaultBuffDurationSec 是 BUFF 时长的默认值（秒）。
const DefaultBuffDurationSec = 280

// Engine 是设备挂机引擎的状态。
type Engine struct {
	Running       bool   `json:"running"`
	State         string `json:"state"` // WAITING / CASTING / IDLE / PAUSED / ERROR
	InputMethod   string `json:"inputMethod"`
	CycleCount    int    `json:"cycleCount"`
	NextDueAt     int64  `json:"nextDueAt"` // 设备本地时钟毫秒
	CyclePeriodMs int    `json:"cyclePeriodMs"`
	LastResult    string `json:"lastResult"`
	LastError     string `json:"lastError"`
	Buffs         []Buff `json:"buffs"`
}

// Heartbeat 是设备健康信息。数字字段用指针，nil 表示设备没上报（区别于真实的 0）。
type Heartbeat struct {
	BatteryPct *int     `json:"batteryPct,omitempty"`
	Charging   *bool    `json:"charging,omitempty"`
	ThermalC   *float64 `json:"thermalC,omitempty"`
	MemFreeMb  *int     `json:"memFreeMb,omitempty"`
	NetRttMs   *int     `json:"netRttMs,omitempty"`
}

// Report 是设备上报体（POST /api/v1/device/report）。
type Report struct {
	DeviceID        string    `json:"deviceId"`
	ProtocolVersion int       `json:"protocolVersion"`
	TS              int64     `json:"ts"`
	UptimeMs        int64     `json:"uptimeMs"`
	VersionCode     int64     `json:"versionCode"`
	VersionName     string    `json:"versionName"`
	Model           string    `json:"model"`
	Android         string    `json:"android"`
	TargetPkg       string    `json:"targetPkg"`
	Foreground      string    `json:"foreground"`
	Armed           bool      `json:"armed"`
	AppliedRevision string    `json:"appliedRevision"`
	Engine          Engine    `json:"engine"`
	Heartbeat       Heartbeat `json:"heartbeat"`
	LogTail         []string  `json:"logTail"`
	AckedCommands   []string  `json:"ackedCommands"`
}

// Config 是下发给设备的配置。Revision 由服务端生成，客户端 PUT 时忽略。
type Config struct {
	Revision    string      `json:"revision,omitempty"`
	TargetPkg   string      `json:"targetPkg"`
	PressMs     int         `json:"pressMs"`
	SkillPoints [][]float64 `json:"skillPoints"`
	InputMethod string      `json:"inputMethod"`
	Buff        []Buff      `json:"buff"`
	Notes       string      `json:"notes"`
	// AutoFreeMarket：每轮补完 BUFF 后自动回自由市场等待（并走到出口待命）。
	// 设计依据见详细设计 6.7「回城模式」。默认关 —— 它涉及地图切换，风险比原地等待高。
	AutoFreeMarket bool `json:"autoFreeMarket"`

	// 原地走动参数（补 BUFF 前左右各走一次）。设备界面与群控台都能改，群控台下发覆盖设备本地值。
	StrollHoldMs     int `json:"strollHoldMs"`     // 每腿持续时长（毫秒），默认 600
	StrollJitterMs   int `json:"strollJitterMs"`   // 时长抖动 ±毫秒，默认 30
	StrollPressGapMs int `json:"strollPressGapMs"` // 连发按键间隔（毫秒），默认 100
}

// 原地走动的默认值与取值范围（设备端 MarketFlow 用同一组默认值）。
const (
	DefaultStrollHoldMs     = 600
	DefaultStrollJitterMs   = 30
	DefaultStrollPressGapMs = 100
)

// DefaultConfig 与旧实现保持一致的初始值。
func DefaultConfig() Config {
	return Config{
		TargetPkg:        "com.nexon.mod",
		PressMs:          90,
		StrollHoldMs:     DefaultStrollHoldMs,
		StrollJitterMs:   DefaultStrollJitterMs,
		StrollPressGapMs: DefaultStrollPressGapMs,
		SkillPoints:      [][]float64{},
		InputMethod:    "keyevent",
		AutoFreeMarket: false,
		Buff: []Buff{
			{Idx: 1, Enabled: true, Key: 1, DurationSec: DefaultBuffDurationSec},
			{Idx: 2, Enabled: false, Key: 2, DurationSec: DefaultBuffDurationSec},
			{Idx: 3, Enabled: false, Key: 3, DurationSec: DefaultBuffDurationSec},
		},
		Notes: "初始配置",
	}
}

// Desired 是启停期望状态。设备下次上报时据此执行，rev 用于去重。
type Desired struct {
	Running bool   `json:"running"`
	Rev     int    `json:"rev"`
	At      int64  `json:"at,omitempty"`
	By      string `json:"by,omitempty"`
}

// DeviceCommand 是一次性指令（截图点播等）。
type DeviceCommand struct {
	ID     string `json:"id"`
	Action string `json:"action"`
}

// ReportResponse 是上报响应：合并往返 —— 把 desired 与 configRevision 直接带回。
type ReportResponse struct {
	OK              bool            `json:"ok"`
	TS              int64           `json:"ts"`
	Desired         Desired         `json:"desired"`
	ConfigRevision  string          `json:"configRevision"`
	NextReportInMs  int             `json:"nextReportInMs"`
	Commands        []DeviceCommand `json:"commands,omitempty"`
}

// ConfigEnvelope 是设备拉配置的响应（配置 + 期望状态）。
type ConfigEnvelope struct {
	Config
	Desired Desired `json:"desired"`
}

// Device 是设备主表行 + 最近一次上报。
type Device struct {
	ID               string
	FirstSeen        int64
	LastSeen         int64
	Model            string
	Android          string
	VersionName      string
	ProtocolVersion  int
	Group            string
	Tags             []string
	ReportIntervalMs int
	Notes            string
	Report           *Report
	Desired          Desired
}

// Online 判定：3 分钟内有上报。
func (d Device) Online(now int64) bool { return now-d.LastSeen < 180_000 }

// ReportRow 是 reports 表的一行（用于历史与曲线）。
type ReportRow struct {
	TS         int64   `json:"ts"`
	ReceivedAt int64   `json:"receivedAt"`
	Foreground string  `json:"foreground"`
	Armed      bool    `json:"armed"`
	Running    bool    `json:"running"`
	State      string  `json:"state"`
	CycleCount int     `json:"cycleCount"`
	BatteryPct *int    `json:"batteryPct,omitempty"`
	ThermalC   *float64 `json:"thermalC,omitempty"`
	NetRttMs   *int    `json:"netRttMs,omitempty"`
}

// LogLine 是一条设备日志。
type LogLine struct {
	TS   int64  `json:"ts"`
	Line string `json:"line"`
}

// Screenshot 是一张设备截图。
type Screenshot struct {
	ID       string `json:"id"`
	DeviceID string `json:"deviceId"`
	TS       int64  `json:"ts"`
	Bytes    int64  `json:"bytes"`
	Label    string `json:"label,omitempty"`
	URL      string `json:"url"`
}

// AuditEntry 是一条审计记录。
type AuditEntry struct {
	ID     int64  `json:"id"`
	TS     int64  `json:"ts"`
	Actor  string `json:"actor"`
	Action string `json:"action"`
	Target string `json:"target,omitempty"`
	Detail string `json:"detail,omitempty"`
	IP     string `json:"ip,omitempty"`
}

// ConfigRevision 是一个历史配置版本。
type ConfigRevision struct {
	Revision  string `json:"revision"`
	CreatedAt int64  `json:"createdAt"`
	CreatedBy string `json:"createdBy"`
	Config    Config `json:"config"`
}

// Settings 是全局设置。
type Settings struct {
	DefaultReportIntervalMs int `json:"defaultReportIntervalMs"`
	ReportRetentionDays     int `json:"reportRetentionDays"`
	LogRetentionDays        int `json:"logRetentionDays"`
	ScreenshotRetentionN    int `json:"screenshotRetentionN"`
	ScreenshotRetentionDays int `json:"screenshotRetentionDays"`
}

// DefaultSettings 是默认全局设置。
func DefaultSettings() Settings {
	return Settings{
		DefaultReportIntervalMs: 60_000,
		ReportRetentionDays:     30,
		LogRetentionDays:        7,
		ScreenshotRetentionN:    10,
		ScreenshotRetentionDays: 30,
	}
}
