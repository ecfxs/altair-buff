package store

import (
	"fmt"
	"path/filepath"
	"testing"

	"altair/internal/model"
)

func newTestStore(t *testing.T) *Store {
	t.Helper()
	path := filepath.Join(t.TempDir(), "test.db")
	st, err := Open(path)
	if err != nil {
		t.Fatalf("打开数据库: %v", err)
	}
	t.Cleanup(func() { _ = st.Close() })
	return st
}

// 迁移必须幂等：升级时会在已有库上重跑，炸了就是线上事故。
func TestMigrateIdempotent(t *testing.T) {
	count := func(st *Store) int {
		t.Helper()
		var n int
		if err := st.DB().QueryRow(`SELECT COUNT(*) FROM schema_migrations`).Scan(&n); err != nil {
			t.Fatalf("读迁移记录: %v", err)
		}
		return n
	}

	path := filepath.Join(t.TempDir(), "test.db")
	st1, err := Open(path)
	if err != nil {
		t.Fatalf("第一次打开: %v", err)
	}
	first := count(st1)
	if first < 2 {
		t.Fatalf("应至少应用了 2 个迁移（0001 建表 + 0002 快照表），实际 %d", first)
	}
	if err := st1.Close(); err != nil {
		t.Fatalf("关闭: %v", err)
	}

	st2, err := Open(path)
	if err != nil {
		t.Fatalf("第二次打开（迁移重跑）: %v", err)
	}
	defer func() { _ = st2.Close() }()
	if again := count(st2); again != first {
		t.Fatalf("迁移被重复应用：第一次 %d 条，第二次 %d 条", first, again)
	}
	// 迁移后表可用
	if _, err := st2.Devices(); err != nil {
		t.Fatalf("迁移后查询失败: %v", err)
	}
}

func TestUpsertReportCreatesDevice(t *testing.T) {
	st := newTestStore(t)
	rep := &model.Report{
		DeviceID: "d1", TS: 1000, ProtocolVersion: 1,
		Model: "Pixel", Android: "13", VersionName: "0.24.0",
		Foreground: "com.nexon.mod", Armed: true,
		Engine:    model.Engine{Running: true, State: "WAITING", CycleCount: 3},
		Heartbeat: model.Heartbeat{},
		LogTail:   []string{"第一行", "第二行"},
	}
	if err := st.UpsertReport(rep, 2000); err != nil {
		t.Fatalf("落盘上报: %v", err)
	}
	d, err := st.Device("d1")
	if err != nil || d == nil {
		t.Fatalf("读设备: %v / %v", d, err)
	}
	if d.Model != "Pixel" || d.LastSeen != 2000 {
		t.Fatalf("设备字段不对: %+v", d)
	}
	if d.Report == nil || d.Report.Engine.CycleCount != 3 {
		t.Fatalf("最近上报没回读出来: %+v", d.Report)
	}
	logs, err := st.Logs("d1", 10, "")
	if err != nil || len(logs) != 2 {
		t.Fatalf("日志条数应为 2，实际 %d (%v)", len(logs), err)
	}
	// 设备型号为空的上报不应把已有型号冲掉
	if err := st.UpsertReport(&model.Report{DeviceID: "d1", TS: 3000}, 3000); err != nil {
		t.Fatalf("二次上报: %v", err)
	}
	d2, _ := st.Device("d1")
	if d2.Model != "Pixel" {
		t.Fatalf("空字段覆盖了已有型号: %q", d2.Model)
	}
}

// rev 自增是设备端去重的依据，错了会每轮重复启停。
func TestSetDesiredRevIncrements(t *testing.T) {
	st := newTestStore(t)
	d0, err := st.Desired("d1")
	if err != nil || d0.Rev != 0 || d0.Running {
		t.Fatalf("初始期望状态应为零值: %+v %v", d0, err)
	}
	d1, err := st.SetDesired("d1", true, "admin")
	if err != nil || !d1.Running || d1.Rev != 1 {
		t.Fatalf("第一次下发: %+v %v", d1, err)
	}
	d2, _ := st.SetDesired("d1", true, "admin")
	if d2.Rev != 2 {
		t.Fatalf("rev 应自增到 2，实际 %d", d2.Rev)
	}
	d3, _ := st.SetDesired("d1", false, "admin")
	if d3.Running || d3.Rev != 3 {
		t.Fatalf("第三次下发: %+v", d3)
	}
}

func TestSetConfigGeneratesRevisionAndArchives(t *testing.T) {
	st := newTestStore(t)
	cfg := model.DefaultConfig()
	cfg.Notes = "第一版"
	rev1, err := st.SetConfig("default", cfg, "admin")
	if err != nil || rev1 == "" {
		t.Fatalf("写配置: %q %v", rev1, err)
	}
	got, rev, err := st.Config("default")
	if err != nil || rev != rev1 || got.Notes != "第一版" {
		t.Fatalf("读回配置: %+v %q %v", got, rev, err)
	}
	// 客户端指定的 revision 必须被忽略
	cfg.Revision = "伪造的"
	cfg.Notes = "第二版"
	rev2, _ := st.SetConfig("default", cfg, "admin")
	if rev2 == rev1 {
		t.Fatalf("revision 应变化")
	}
	if got2, _, _ := st.Config("default"); got2.Revision != rev2 {
		t.Fatalf("存进去的 revision 不是服务端生成的: %q", got2.Revision)
	}
	revs, err := st.ConfigRevisions("default", 10)
	if err != nil || len(revs) != 2 {
		t.Fatalf("应有 2 个历史版本，实际 %d (%v)", len(revs), err)
	}
	// 回滚取历史版本
	old, err := st.ConfigByRevision("default", rev1)
	if err != nil || old == nil || old.Notes != "第一版" {
		t.Fatalf("取历史版本失败: %+v %v", old, err)
	}
}

func TestDeviceStatsAndHistory(t *testing.T) {
	st := newTestStore(t)
	now := int64(1_700_000_000_000)
	// 两小时内每 5 分钟一个点，轮次递增
	for i := 0; i < 24; i++ {
		ts := now - int64(23-i)*300_000
		rep := &model.Report{DeviceID: "d1", TS: ts, Engine: model.Engine{CycleCount: i}}
		if err := st.UpsertReport(rep, ts); err != nil {
			t.Fatalf("落盘: %v", err)
		}
	}
	rows, err := st.RecentReports("d1", 50)
	if err != nil {
		t.Fatalf("最近上报: %v", err)
	}
	if len(rows) != 24 {
		t.Fatalf("应有 24 条，实际 %d", len(rows))
	}
	if rows[0].TS <= rows[len(rows)-1].TS {
		t.Fatalf("最近上报应按时间倒序")
	}
	// 采样：step=1h，应显著少于原始点数
	hist, err := st.History("d1", now-24*3600*1000, now+1000, 3600)
	if err != nil {
		t.Fatalf("history: %v", err)
	}
	if len(hist) == 0 || len(hist) >= len(rows) {
		t.Fatalf("采样后点数应少于原始点数，实际 %d vs %d", len(hist), len(rows))
	}
	cycles, rate, err := st.DeviceStats("d1", now+1000)
	if err != nil {
		t.Fatalf("stats: %v", err)
	}
	if cycles != 23 {
		t.Fatalf("24h 轮次增量应为 23，实际 %d", cycles)
	}
	if rate <= 0 || rate > 1 {
		t.Fatalf("在线率应在 (0,1]，实际 %v", rate)
	}
}

// 锁定存储设计：完整的最近上报只存一份快照，时间序列里不带 payload。
// 这是实测 1454 字节/份 → 362 字节/份 的关键，退回去就是线上磁盘爆炸。
func TestReportSnapshotSeparateFromTimeSeries(t *testing.T) {
	st := newTestStore(t)
	if err := st.UpsertReport(&model.Report{
		DeviceID: "d1", TS: 1000,
		Engine:    model.Engine{State: "WAITING", CycleCount: 7},
		Heartbeat: model.Heartbeat{},
		LogTail:   []string{"第一行日志"},
	}, 1000); err != nil {
		t.Fatalf("落盘: %v", err)
	}

	var timeSeriesPayload string
	if err := st.DB().QueryRow(`SELECT payload FROM reports WHERE device_id='d1'`).Scan(&timeSeriesPayload); err != nil {
		t.Fatalf("读时间序列: %v", err)
	}
	if timeSeriesPayload != "" {
		t.Fatalf("时间序列里不该存 payload，实际 %d 字节", len(timeSeriesPayload))
	}

	var snapshot string
	if err := st.DB().QueryRow(`SELECT payload FROM device_state WHERE device_id='d1'`).Scan(&snapshot); err != nil {
		t.Fatalf("读快照: %v", err)
	}
	if snapshot == "" {
		t.Fatal("快照表里应该有完整上报")
	}

	// 快照必须能重建出完整状态（面板靠它渲染卡片）
	d, err := st.Device("d1")
	if err != nil || d == nil || d.Report == nil {
		t.Fatalf("回读设备: %v %v", d, err)
	}
	if d.Report.Engine.CycleCount != 7 || d.Report.Engine.State != "WAITING" {
		t.Fatalf("快照重建的状态不对: %+v", d.Report.Engine)
	}

	// 覆盖写：第二份上报只更新那一行，不新增
	if err := st.UpsertReport(&model.Report{
		DeviceID: "d1", TS: 2000, Engine: model.Engine{State: "CASTING", CycleCount: 9},
	}, 2000); err != nil {
		t.Fatalf("二次落盘: %v", err)
	}
	var snapshots int
	if err := st.DB().QueryRow(`SELECT COUNT(*) FROM device_state`).Scan(&snapshots); err != nil {
		t.Fatalf("数快照: %v", err)
	}
	if snapshots != 1 {
		t.Fatalf("快照应每台一行，实际 %d 行", snapshots)
	}
	d2, _ := st.Device("d1")
	if d2.Report.Engine.CycleCount != 9 || d2.Report.Engine.State != "CASTING" {
		t.Fatalf("快照没被覆盖: %+v", d2.Report.Engine)
	}
	// 时间序列应该是两条（历史要留）
	rows, _ := st.RecentReports("d1", 10)
	if len(rows) != 2 {
		t.Fatalf("时间序列应有 2 条，实际 %d", len(rows))
	}
	if rows[0].CycleCount != 9 {
		t.Fatalf("时间序列应记录标量：%+v", rows[0])
	}
}

// 连续两次写入必须产生不同 revision —— 设备只在 revision 变化时热重载，
// 撞号 = 第二次改动被设备静默忽略。这条曾经真的挂过（同毫秒撞号）。
func TestRevisionUniqueUnderRapidWrites(t *testing.T) {
	st := newTestStore(t)
	seen := map[string]bool{}
	for i := 0; i < 50; i++ {
		cfg := model.DefaultConfig()
		cfg.Notes = fmt.Sprintf("第 %d 版", i)
		rev, err := st.SetConfig("default", cfg, "admin")
		if err != nil {
			t.Fatalf("第 %d 次写入: %v", i, err)
		}
		if seen[rev] {
			t.Fatalf("第 %d 次写入产生重复 revision=%s", i, rev)
		}
		seen[rev] = true
	}
	revs, err := st.ConfigRevisions("default", 100)
	if err != nil {
		t.Fatalf("读历史: %v", err)
	}
	if len(revs) != 50 {
		t.Fatalf("历史版本应有 50 条，实际 %d", len(revs))
	}
	// 每个 scope 独立计数：另一个 scope 不该受影响
	if _, err := st.SetConfig("other", model.DefaultConfig(), "admin"); err != nil {
		t.Fatalf("另一 scope 写入: %v", err)
	}
}
