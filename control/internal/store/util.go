package store

import (
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"strings"

	"altair/internal/model"
)

func jsonMarshal(v any) (string, error) {
	b, err := json.Marshal(v)
	return string(b), err
}

func jsonUnmarshal(s string, v any) error { return json.Unmarshal([]byte(s), v) }

func boolInt(b bool) int {
	if b {
		return 1
	}
	return 0
}

func decodeTags(s string) []string {
	if s == "" {
		return []string{}
	}
	var out []string
	if err := json.Unmarshal([]byte(s), &out); err != nil {
		return []string{}
	}
	if out == nil {
		return []string{}
	}
	return out
}

// scanReportRow 扫描 reports 查询的 10 个标量列（payload 已挪到 device_state，见 0002 迁移）。
func scanReportRow(rows *sql.Rows) (*model.ReportRow, error) {
	var (
		r        model.ReportRow
		armed    int
		running  int
		battery  sql.NullInt64
		thermal  sql.NullFloat64
		rtt      sql.NullInt64
	)
	if err := rows.Scan(&r.TS, &r.ReceivedAt, &r.Foreground, &armed, &running, &r.State,
		&r.CycleCount, &battery, &thermal, &rtt); err != nil {
		return nil, err
	}
	r.Armed = armed != 0
	r.Running = running != 0
	if battery.Valid {
		v := int(battery.Int64)
		r.BatteryPct = &v
	}
	if thermal.Valid {
		v := thermal.Float64
		r.ThermalC = &v
	}
	if rtt.Valid {
		v := int(rtt.Int64)
		r.NetRttMs = &v
	}
	return &r, nil
}

// newToken 生成随机会话 token，返回 (明文, sha256 哈希)。
// 明文只在响应里出现一次，库里只存哈希 —— 库被读走也无法直接冒充会话。
func newToken() (string, string, error) {
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		return "", "", fmt.Errorf("生成随机 token: %w", err)
	}
	token := base64.RawURLEncoding.EncodeToString(buf)
	return token, hashToken(token), nil
}

func hashToken(token string) string {
	sum := sha256.Sum256([]byte(strings.TrimSpace(token)))
	return hex.EncodeToString(sum[:])
}

// RandomSecret 生成 URL 安全的随机密钥（设备 Token 用）。
func RandomSecret(nbytes int) string {
	buf := make([]byte, nbytes)
	if _, err := rand.Read(buf); err != nil {
		// crypto/rand 失败属于不可恢复的环境问题，直接 panic 好过发一个弱密钥。
		panic("crypto/rand 不可用: " + err.Error())
	}
	return base64.RawURLEncoding.EncodeToString(buf)
}
