// Package auth 负责凭据与会话：argon2id 口令哈希、首次启动引导、登录退避限流。
//
// 与旧 Python 实现的关键差异：
//   - 面板口令**只存 argon2id 哈希**，不再有明文入库（旧版把明文面板密码每 5 秒发给浏览器）。
//   - 首次启动生成的随机口令只打印一次，错过就用 `altaird set-password` 重设。
package auth

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"fmt"
	"log/slog"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.org/x/crypto/argon2"

	"altair/internal/store"
)

// 口令哈希参数（OWASP 推荐量级；单次校验约几十毫秒）。
const (
	argonTime    = 3
	argonMemory  = 64 * 1024
	argonThreads = 2
	argonKeyLen  = 32
	argonSaltLen = 16
)

// HashPassword 生成 PHC 格式的 argon2id 串。
func HashPassword(pw string) (string, error) {
	salt := make([]byte, argonSaltLen)
	if _, err := rand.Read(salt); err != nil {
		return "", err
	}
	key := argon2.IDKey([]byte(pw), salt, argonTime, argonMemory, argonThreads, argonKeyLen)
	return fmt.Sprintf("$argon2id$v=%d$m=%d,t=%d,p=%d$%s$%s",
		argon2.Version, argonMemory, argonTime, argonThreads,
		base64.RawStdEncoding.EncodeToString(salt),
		base64.RawStdEncoding.EncodeToString(key)), nil
}

// VerifyPassword 校验口令，使用常量时间比较。
func VerifyPassword(phc, pw string) bool {
	parts := strings.Split(phc, "$")
	if len(parts) != 6 || parts[1] != "argon2id" {
		return false
	}
	var memory uint32
	var iters uint32
	var threads uint8
	if _, err := fmt.Sscanf(parts[3], "m=%d,t=%d,p=%d", &memory, &iters, &threads); err != nil {
		return false
	}
	salt, err := base64.RawStdEncoding.DecodeString(parts[4])
	if err != nil {
		return false
	}
	want, err := base64.RawStdEncoding.DecodeString(parts[5])
	if err != nil {
		return false
	}
	got := argon2.IDKey([]byte(pw), salt, iters, memory, threads, uint32(len(want)))
	return subtle.ConstantTimeCompare(got, want) == 1
}

// Bootstrap 确保凭据存在；首次运行生成并返回「需要打印给管理员」的明文口令。
func Bootstrap(st *store.Store) (generatedPassword string, err error) {
	if v, _ := st.Setting("panel_user"); v == "" {
		if err := st.SetSetting("panel_user", "admin"); err != nil {
			return "", err
		}
	}
	if v, _ := st.Setting("panel_pass_hash"); v == "" {
		pw := store.RandomSecret(12)
		hash, err := HashPassword(pw)
		if err != nil {
			return "", err
		}
		if err := st.SetSetting("panel_pass_hash", hash); err != nil {
			return "", err
		}
		generatedPassword = pw
	}
	if v, _ := st.Setting("device_token"); v == "" {
		if err := st.SetSetting("device_token", store.RandomSecret(24)); err != nil {
			return "", err
		}
	}
	return generatedPassword, nil
}

// SetPassword 重设面板口令。
func SetPassword(st *store.Store, pw string) error {
	if len(pw) < 6 {
		return errors.New("口令至少 6 位")
	}
	hash, err := HashPassword(pw)
	if err != nil {
		return err
	}
	return st.SetSetting("panel_pass_hash", hash)
}

// SetDeviceToken 更换设备 Token。
func SetDeviceToken(st *store.Store, token string) error {
	if len(token) < 8 {
		return errors.New("设备 Token 至少 8 位")
	}
	return st.SetSetting("device_token", token)
}

// DeviceToken 读设备 Token。
func DeviceToken(st *store.Store) string {
	v, _ := st.Setting("device_token")
	return v
}

// PanelUser 读面板用户名。
func PanelUser(st *store.Store) string {
	v, _ := st.Setting("panel_user")
	if v == "" {
		return "admin"
	}
	return v
}

// CheckLogin 校验用户名口令。
func CheckLogin(st *store.Store, user, pw string) bool {
	if subtle.ConstantTimeCompare([]byte(user), []byte(PanelUser(st))) != 1 {
		return false
	}
	hash, _ := st.Setting("panel_pass_hash")
	if hash == "" {
		return false
	}
	return VerifyPassword(hash, pw)
}

// ---------------------------------------------------------------- 登录退避

// Limiter 是极简的按 key 指数退避（替代旧 Python 的 sleep(1)）。
// 内存态即可：单实例服务，重启后清零不是问题。
type Limiter struct {
	mu       sync.Mutex
	fails    map[string]*failState
	base     time.Duration
	maxDelay time.Duration
}

// freeAttempts 是允许的连续失败次数（不施加延迟）。
const freeAttempts = 3

type failState struct {
	count int
	until time.Time
}

// NewLimiter 建限流器。
func NewLimiter(base, maxDelay time.Duration) *Limiter {
	return &Limiter{fails: make(map[string]*failState), base: base, maxDelay: maxDelay}
}

// Allow 判断当前是否允许尝试（并返回还需等待多久）。
func (l *Limiter) Allow(key string) (bool, time.Duration) {
	l.mu.Lock()
	defer l.mu.Unlock()
	st, ok := l.fails[key]
	if !ok {
		return true, 0
	}
	if wait := time.Until(st.until); wait > 0 {
		return false, wait
	}
	return true, 0
}

// Fail 记一次失败。前 freeAttempts 次不延迟 —— 手滑输错一次就被挡 0.5 秒
// 对正常用户是纯粹的骚扰；超过之后才指数退避。
func (l *Limiter) Fail(key string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	st, ok := l.fails[key]
	if !ok {
		st = &failState{}
		l.fails[key] = st
	}
	st.count++
	if st.count <= freeAttempts {
		st.until = time.Now()
		slog.Warn("认证失败", "key", key, "fails", st.count, "退避", "无（宽容次数内）")
		return
	}
	delay := l.base * time.Duration(1<<min(st.count-1-freeAttempts, 6))
	if delay > l.maxDelay {
		delay = l.maxDelay
	}
	st.until = time.Now().Add(delay)
	slog.Warn("认证失败，退避中", "key", key, "fails", st.count, "delay", delay.String())
}

// Reset 成功后清空退避。
func (l *Limiter) Reset(key string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	delete(l.fails, key)
}

// Sweep 清理过期条目（后台 ticker 调）。
func (l *Limiter) Sweep() {
	l.mu.Lock()
	defer l.mu.Unlock()
	for k, st := range l.fails {
		if time.Since(st.until) > time.Hour {
			delete(l.fails, k)
		}
	}
}

var _ = strconv.Itoa // 保持 strconv 引用稳定（供后续扩展）
