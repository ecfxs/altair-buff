// altaird 是阿尔泰集控服务：设备 API + 面板 API + 内嵌前端，单二进制。
//
// 用法:
//
//	altaird                            启动服务（默认 127.0.0.1:8788）
//	altaird --addr :8788 --db ...      指定监听与数据库
//	altaird show                       查看面板用户名与设备 Token
//	altaird set-password 新口令        重设面板口令
//	altaird set-token 新Token          更换设备 Token
//	altaird fake-device --n 3          起假设备造流量（开发/验收用）
//	altaird calibrate --root .         起本地标定工具服务
//	altaird version
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"altair/internal/api"
	"altair/internal/auth"
	"altair/internal/fake"
	"altair/internal/model"
	"altair/internal/store"
)

var version = "dev"

func main() {
	args := os.Args[1:]
	cmd := "serve"
	if len(args) > 0 && !strings.HasPrefix(args[0], "-") {
		cmd, args = args[0], args[1:]
	}
	var err error
	switch cmd {
	case "serve":
		err = runServe(args)
	case "show":
		err = runShow(args)
	case "set-password":
		err = runSetPassword(args)
	case "set-token":
		err = runSetToken(args)
	case "fake-device":
		err = runFakeDevice(args)
	case "calibrate":
		err = runCalibrate(args)
	case "version":
		fmt.Println("altaird", version)
	case "help", "-h", "--help":
		usage()
	default:
		fmt.Fprintf(os.Stderr, "未知子命令: %s\n\n", cmd)
		usage()
		os.Exit(2)
	}
	if err != nil {
		slog.Error("执行失败", "err", err)
		os.Exit(1)
	}
}

func usage() {
	fmt.Print(`altaird —— 阿尔泰集控服务

  altaird [--addr 127.0.0.1:8788] [--db 路径] [--data 目录] [--legacy-device-api] [--static-dir 目录]
  altaird show                        查看面板用户名与设备 Token
  altaird set-password <新口令>       重设面板口令
  altaird set-token <新Token>         更换设备 Token
  altaird fake-device [--n 3] [--interval 5s] [--server http://127.0.0.1:8788]
  altaird calibrate [--root .] [--addr 127.0.0.1:8787]
  altaird version
`)
}

// ---------------------------------------------------------------- 通用

func openStore(dbPath string) (*store.Store, error) {
	if err := os.MkdirAll(filepath.Dir(dbPath), 0o755); err != nil {
		return nil, fmt.Errorf("建数据目录: %w", err)
	}
	return store.Open(dbPath)
}

func defaultDBPath() string {
	if v := os.Getenv("ALTAIR_DB"); v != "" {
		return v
	}
	return filepath.Join("data", "altair.db")
}

// ---------------------------------------------------------------- serve

func runServe(args []string) error {
	fs := flag.NewFlagSet("serve", flag.ExitOnError)
	addr := fs.String("addr", envOr("ALTAIR_ADDR", "127.0.0.1:8788"), "监听地址")
	dbPath := fs.String("db", defaultDBPath(), "SQLite 数据库路径")
	dataDir := fs.String("data", "", "数据目录（截图落盘位置），默认与数据库同目录")
	staticDir := fs.String("static-dir", os.Getenv("ALTAIR_STATIC_DIR"), "前端产物目录（开发用；留空则用内嵌产物）")
	legacy := fs.Bool("legacy-device-api", false, "启用旧设备端点 /api/report、/api/config（硬切换窗口的安全网）")
	debug := fs.Bool("debug", false, "打印 debug 级日志")
	if err := fs.Parse(args); err != nil {
		return err
	}

	level := slog.LevelInfo
	if *debug {
		level = slog.LevelDebug
	}
	slog.SetDefault(slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: level})))

	st, err := openStore(*dbPath)
	if err != nil {
		return err
	}
	defer func() { _ = st.Close() }()

	// 首次启动引导：生成面板口令与设备 Token
	generated, err := auth.Bootstrap(st)
	if err != nil {
		return fmt.Errorf("初始化凭据: %w", err)
	}

	// 首次启动写入默认配置（只在缺失时，避免每次启动都产生新 revision）
	if cfg, _, err := st.Config("default"); err != nil {
		return err
	} else if cfg == nil {
		dc := model.DefaultConfig()
		if _, err := st.SetConfig("default", dc, "system"); err != nil {
			return fmt.Errorf("写默认配置: %w", err)
		}
		slog.Info("已创建默认配置")
	}

	if *dataDir == "" {
		*dataDir = filepath.Dir(*dbPath)
	}
	if err := os.MkdirAll(filepath.Join(*dataDir, "shots"), 0o755); err != nil {
		return fmt.Errorf("建截图目录: %w", err)
	}

	srv := api.New(st, *dataDir, version, *legacy, *staticDir)
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	srv.StartBackground(ctx)

	httpSrv := &http.Server{
		Addr:              *addr,
		Handler:           srv.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       120 * time.Second,
	}

	banner(st, generated, *addr, *dataDir, *legacy)

	errCh := make(chan error, 1)
	go func() {
		if err := httpSrv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			errCh <- err
		}
	}()

	select {
	case err := <-errCh:
		return err
	case <-ctx.Done():
		slog.Info("收到停止信号，正在关闭 ...")
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
		defer cancel()
		return httpSrv.Shutdown(shutdownCtx)
	}
}

func banner(st *store.Store, generated, addr, dataDir string, legacy bool) {
	user := auth.PanelUser(st)
	fmt.Println(strings.Repeat("=", 68))
	fmt.Println("  阿尔泰集控 altaird", version)
	fmt.Println(strings.Repeat("-", 68))
	fmt.Printf("  面板地址     http://%s/\n", displayAddr(addr))
	fmt.Printf("  面板账号     %s\n", user)
	if generated != "" {
		fmt.Printf("  面板口令     %s     ← 仅此一次显示，请立刻记下\n", generated)
	} else {
		fmt.Println("  面板口令     （已设置，忘记可执行 altaird set-password 新口令）")
	}
	fmt.Printf("  设备 Token   %s\n", auth.DeviceToken(st))
	fmt.Println(strings.Repeat("-", 68))
	fmt.Printf("  数据库       %s\n", filepath.Join(dataDir, "altair.db"))
	fmt.Printf("  截图目录     %s\n", filepath.Join(dataDir, "shots"))
	if legacy {
		fmt.Println("  ⚠ 已启用旧设备 API 兼容层（/api/report、/api/config）")
	}
	fmt.Println(strings.Repeat("=", 68))
}

func displayAddr(addr string) string {
	if strings.HasPrefix(addr, ":") {
		return "127.0.0.1" + addr
	}
	if strings.HasPrefix(addr, "0.0.0.0:") {
		return "127.0.0.1:" + strings.TrimPrefix(addr, "0.0.0.0:")
	}
	return addr
}

// ---------------------------------------------------------------- 运维子命令

func runShow(args []string) error {
	fs := flag.NewFlagSet("show", flag.ExitOnError)
	dbPath := fs.String("db", defaultDBPath(), "SQLite 数据库路径")
	if err := fs.Parse(args); err != nil {
		return err
	}
	st, err := openStore(*dbPath)
	if err != nil {
		return err
	}
	defer func() { _ = st.Close() }()
	if _, err := auth.Bootstrap(st); err != nil {
		return err
	}
	fmt.Printf("面板账号    %s\n", auth.PanelUser(st))
	fmt.Printf("设备 Token  %s\n", auth.DeviceToken(st))
	fmt.Println("面板口令    无法显示（只存 argon2id 哈希）；用 altaird set-password 新口令 重设")
	return nil
}

func runSetPassword(args []string) error {
	pw, dbPath := parseValueAndDB(args)
	if pw == "" {
		return errors.New("用法: altaird set-password <新口令> [--db 路径]")
	}
	st, err := openStore(dbPath)
	if err != nil {
		return err
	}
	defer func() { _ = st.Close() }()
	if err := auth.SetPassword(st, pw); err != nil {
		return err
	}
	st.AddAudit("cli", "panel.set-password", "", "", "")
	fmt.Println("面板口令已更新")
	return nil
}

func runSetToken(args []string) error {
	token, dbPath := parseValueAndDB(args)
	if token == "" {
		return errors.New("用法: altaird set-token <新Token> [--db 路径]")
	}
	st, err := openStore(dbPath)
	if err != nil {
		return err
	}
	defer func() { _ = st.Close() }()
	if err := auth.SetDeviceToken(st, token); err != nil {
		return err
	}
	st.AddAudit("cli", "device.set-token", "", "", "")
	fmt.Println("设备 Token 已更新（所有云手机都要改成新 Token）")
	return nil
}

// parseValueAndDB 解析「一个位置参数 + 可选 --db」。
//
// 为什么不用 flag 包：Go 的 flag 在遇到**第一个非 flag 参数**时就停止解析，
// 于是 `altaird set-token 新Token --db /path/x.db` 里的 --db 会被静默忽略，
// 改动落到默认数据库上 —— 看起来成功，实际改错了库（这个坑真踩到过）。
// 这里显式扫描，两种写法都支持：
//
//	altaird set-token 新Token --db /path/x.db
//	altaird set-token --db /path/x.db 新Token
func parseValueAndDB(args []string) (value, dbPath string) {
	dbPath = defaultDBPath()
	for i := 0; i < len(args); i++ {
		a := args[i]
		switch {
		case a == "--db":
			if i+1 < len(args) {
				dbPath = args[i+1]
				i++
			}
		case strings.HasPrefix(a, "--db="):
			dbPath = strings.TrimPrefix(a, "--db=")
		case strings.HasPrefix(a, "-"):
			// 未知 flag：忽略，避免把 "-开头" 的口令当成位置参数
		default:
			if value == "" {
				value = a
			}
		}
	}
	return value, dbPath
}

// ---------------------------------------------------------------- fake-device

func runFakeDevice(args []string) error {
	fs := flag.NewFlagSet("fake-device", flag.ExitOnError)
	dbPath := fs.String("db", defaultDBPath(), "SQLite 数据库路径")
	n := fs.Int("n", 3, "假设备台数")
	interval := fs.Duration("interval", 5*time.Second, "上报周期")
	server := fs.String("server", "http://127.0.0.1:8788", "目标服务器")
	token := fs.String("token", "", "设备 Token（默认从库里读）")
	force := fs.Bool("force", false, "允许向非本机地址发送假设备流量（危险，仅限测试环境）")
	if err := fs.Parse(args); err != nil {
		return err
	}
	// ★ 硬护栏：假设备只能打到本机。
	// 假设备是为了开发/验收才存在的，一旦指向生产服务器，面板里就会混进一堆
	// fake01/fake02 把真实设备淹掉（而且它们还会被下发配置、占容量）。
	if !*force && !isLoopbackURL(*server) {
		return fmt.Errorf(
			"拒绝向非本机地址 %s 发送假设备流量。\n"+
				"  假设备仅供开发/验收：打到生产服务器会把真实设备淹掉。\n"+
				"  确实要在测试环境这么做，请显式加 --force", *server)
	}
	tok := *token
	if tok == "" {
		st, err := openStore(*dbPath)
		if err != nil {
			return err
		}
		tok = auth.DeviceToken(st)
		_ = st.Close()
		if tok == "" {
			return errors.New("库里没有设备 Token，请先启动一次 altaird 或加 --token")
		}
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	err := fake.Run(ctx, *server, tok, *n, *interval)
	if errors.Is(err, context.Canceled) {
		return nil
	}
	return err
}

// isLoopbackURL 判断目标是不是本机（127.0.0.0/8、localhost、::1）。
func isLoopbackURL(raw string) bool {
	u, err := url.Parse(raw)
	if err != nil {
		return false
	}
	host := u.Hostname()
	if host == "localhost" || host == "::1" {
		return true
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
