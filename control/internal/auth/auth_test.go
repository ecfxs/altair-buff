package auth

import (
	"path/filepath"
	"testing"
	"time"

	"altair/internal/store"
)

func TestPasswordRoundTrip(t *testing.T) {
	phc, err := HashPassword("正确的口令 horse")
	if err != nil {
		t.Fatalf("生成哈希: %v", err)
	}
	if phc == "正确的口令 horse" {
		t.Fatal("哈希里不能出现明文")
	}
	if !VerifyPassword(phc, "正确的口令 horse") {
		t.Fatal("正确口令应校验通过")
	}
	if VerifyPassword(phc, "错误的口令") {
		t.Fatal("错误口令不应通过")
	}
	// 同一口令两次哈希必须不同（盐随机）
	phc2, _ := HashPassword("正确的口令 horse")
	if phc == phc2 {
		t.Fatal("两次哈希相同说明盐没随机")
	}
	if !VerifyPassword(phc2, "正确的口令 horse") {
		t.Fatal("第二个哈希也应通过")
	}
	// 损坏的哈希串不能让服务 panic 或误判通过
	for _, bad := range []string{"", "$argon2id$", "garbage", "$argon2id$v=19$m=1,t=1,p=1$!!$!!"} {
		if VerifyPassword(bad, "任意") {
			t.Fatalf("损坏哈希 %q 不应通过", bad)
		}
	}
}

func TestBootstrapIdempotent(t *testing.T) {
	st, err := store.Open(filepath.Join(t.TempDir(), "t.db"))
	if err != nil {
		t.Fatalf("打开库: %v", err)
	}
	defer func() { _ = st.Close() }()

	pw1, err := Bootstrap(st)
	if err != nil {
		t.Fatalf("首次引导: %v", err)
	}
	if pw1 == "" {
		t.Fatal("首次引导应生成口令")
	}
	if !CheckLogin(st, "admin", pw1) {
		t.Fatal("生成的凭据应能登录")
	}
	token1 := DeviceToken(st)
	// 第二次引导不能重置任何东西
	pw2, err := Bootstrap(st)
	if err != nil {
		t.Fatalf("二次引导: %v", err)
	}
	if pw2 != "" {
		t.Fatal("二次引导不应再生成口令")
	}
	if DeviceToken(st) != token1 {
		t.Fatal("二次引导不应更换设备 Token")
	}
	if CheckLogin(st, "admin", "瞎猜的口令") {
		t.Fatal("错误口令不应通过")
	}
	if CheckLogin(st, "root", pw1) {
		t.Fatal("错误用户名不应通过")
	}
}

func TestSetPasswordAndToken(t *testing.T) {
	st, _ := store.Open(filepath.Join(t.TempDir(), "t.db"))
	defer func() { _ = st.Close() }()
	if _, err := Bootstrap(st); err != nil {
		t.Fatalf("引导: %v", err)
	}
	if err := SetPassword(st, "短"); err == nil {
		t.Fatal("过短口令应被拒绝")
	}
	if err := SetPassword(st, "新的口令abc"); err != nil {
		t.Fatalf("重设口令: %v", err)
	}
	if !CheckLogin(st, "admin", "新的口令abc") {
		t.Fatal("重设后应能用新口令登录")
	}
	if err := SetDeviceToken(st, "short"); err == nil {
		t.Fatal("过短 Token 应被拒绝")
	}
	if err := SetDeviceToken(st, "dev-token-1234567890"); err != nil {
		t.Fatalf("换 Token: %v", err)
	}
	if DeviceToken(st) != "dev-token-1234567890" {
		t.Fatal("Token 没生效")
	}
}

// 退避行为：前几次宽容，之后开始挡 —— 这条直接决定"手滑输错会不会把自己锁在门外"。
func TestLimiterFreeAttemptsThenBackoff(t *testing.T) {
	l := NewLimiter(10*time.Millisecond, 100*time.Millisecond)
	key := "1.2.3.4|admin"

	for i := 1; i <= freeAttempts; i++ {
		l.Fail(key)
		if ok, _ := l.Allow(key); !ok {
			t.Fatalf("第 %d 次失败后不应立刻被挡（宽容次数内）", i)
		}
	}
	l.Fail(key) // 第 4 次：进入退避
	if ok, wait := l.Allow(key); ok {
		t.Fatal("超过宽容次数后被挡")
	} else if wait <= 0 {
		t.Fatalf("退避应给出正等待时长，实际 %v", wait)
	}

	// 成功登录后应清空退避
	l.Reset(key)
	if ok, _ := l.Allow(key); !ok {
		t.Fatal("Reset 后应恢复放行")
	}

	// 多次失败后退避有上限，不会无限增长
	for i := 0; i < 40; i++ {
		l.Fail("2.2.2.2|x")
	}
	if ok, wait := l.Allow("2.2.2.2|x"); ok || wait > 200*time.Millisecond {
		t.Fatalf("退避应有上限（<=maxDelay），实际 ok=%v wait=%v", ok, wait)
	}
}
