// Package store 封装 SQLite 持久化：打开/迁移/全部查询。
//
// 并发策略：MaxOpenConns(1)。本服务规模是「几十台设备 + 个位数面板用户」，
// 串行化写入换来实现上的确定性（彻底避免 SQLITE_BUSY 与写冲突），代价可忽略。
package store

import (
	"database/sql"
	"embed"
	"fmt"
	"log/slog"
	"sort"
	"strconv"
	"strings"
	"time"

	_ "modernc.org/sqlite"

	"altair/internal/model"
)

//go:embed migrations/*.sql
var migrationsFS embed.FS

// Store 是数据库句柄。
type Store struct {
	db *sql.DB
}

// Open 打开（必要时创建）数据库并跑完迁移。
func Open(path string) (*Store, error) {
	dsn := fmt.Sprintf("file:%s?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)&_pragma=foreign_keys(ON)&_pragma=synchronous(NORMAL)", path)
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, fmt.Errorf("打开数据库: %w", err)
	}
	db.SetMaxOpenConns(1)
	if err := db.Ping(); err != nil {
		return nil, fmt.Errorf("连接数据库: %w", err)
	}
	s := &Store{db: db}
	if err := s.migrate(); err != nil {
		return nil, err
	}
	return s, nil
}

// Close 关闭数据库。
func (s *Store) Close() error { return s.db.Close() }

// DB 暴露底层句柄（迁移测试用）。
func (s *Store) DB() *sql.DB { return s.db }

func (s *Store) migrate() error {
	if _, err := s.db.Exec(`CREATE TABLE IF NOT EXISTS schema_migrations (
		version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)`); err != nil {
		return fmt.Errorf("建迁移表: %w", err)
	}
	var current int
	if err := s.db.QueryRow(`SELECT COALESCE(MAX(version),0) FROM schema_migrations`).Scan(&current); err != nil {
		return fmt.Errorf("读迁移版本: %w", err)
	}
	entries, err := migrationsFS.ReadDir("migrations")
	if err != nil {
		return fmt.Errorf("读迁移目录: %w", err)
	}
	names := make([]string, 0, len(entries))
	for _, e := range entries {
		if !e.IsDir() && strings.HasSuffix(e.Name(), ".sql") {
			names = append(names, e.Name())
		}
	}
	sort.Strings(names)
	for _, name := range names {
		ver, err := strconv.Atoi(strings.SplitN(name, "_", 2)[0])
		if err != nil {
			return fmt.Errorf("迁移文件名不合法 %s: %w", name, err)
		}
		if ver <= current {
			continue
		}
		body, err := migrationsFS.ReadFile("migrations/" + name)
		if err != nil {
			return fmt.Errorf("读迁移 %s: %w", name, err)
		}
		tx, err := s.db.Begin()
		if err != nil {
			return err
		}
		if _, err := tx.Exec(string(body)); err != nil {
			_ = tx.Rollback()
			return fmt.Errorf("执行迁移 %s: %w", name, err)
		}
		if _, err := tx.Exec(`INSERT INTO schema_migrations(version, applied_at) VALUES(?,?)`,
			ver, time.Now().UnixMilli()); err != nil {
			_ = tx.Rollback()
			return fmt.Errorf("记录迁移 %s: %w", name, err)
		}
		if err := tx.Commit(); err != nil {
			return fmt.Errorf("提交迁移 %s: %w", name, err)
		}
		slog.Info("迁移已应用", "file", name, "version", ver)
	}
	return nil
}

// ---------------------------------------------------------------- 设备与上报

// UpsertReport 落一次设备上报：更新设备行 + 写时间序列 + 写日志 + 标记指令回执。
func (s *Store) UpsertReport(r *model.Report, now int64) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()

	if _, err := tx.Exec(`
		INSERT INTO devices (id, first_seen, last_seen, model, android, version_name,
		                     protocol_version, created_at, updated_at)
		VALUES (?,?,?,?,?,?,?,?,?)
		ON CONFLICT(id) DO UPDATE SET
		  last_seen   = excluded.last_seen,
		  model       = CASE WHEN excluded.model        <> '' THEN excluded.model        ELSE devices.model        END,
		  android     = CASE WHEN excluded.android      <> '' THEN excluded.android      ELSE devices.android      END,
		  version_name= CASE WHEN excluded.version_name <> '' THEN excluded.version_name ELSE devices.version_name END,
		  protocol_version = excluded.protocol_version,
		  updated_at  = excluded.updated_at`,
		r.DeviceID, now, now, r.Model, r.Android, r.VersionName, r.ProtocolVersion, now, now); err != nil {
		return fmt.Errorf("更新设备: %w", err)
	}

	payload, err := jsonMarshal(r)
	if err != nil {
		return err
	}
	// payload 列不再写内容：时间序列只留标量（见 migrations/0002_device_state.sql）。
	// 保留该列是为了兼容老库的表结构，写入空串零成本。
	if _, err := tx.Exec(`
		INSERT INTO reports (device_id, ts, received_at, foreground, armed, running, state,
		                     cycle_count, battery_pct, thermal_c, net_rtt_ms, payload)
		VALUES (?,?,?,?,?,?,?,?,?,?,?,'')`,
		r.DeviceID, r.TS, now, r.Foreground, boolInt(r.Armed), boolInt(r.Engine.Running), r.Engine.State,
		r.Engine.CycleCount, r.Heartbeat.BatteryPct, r.Heartbeat.ThermalC, r.Heartbeat.NetRttMs); err != nil {
		return fmt.Errorf("写上报: %w", err)
	}

	// 完整快照单独存一份（每台设备一行，覆盖写），面板读它重建最近状态
	if _, err := tx.Exec(`
		INSERT INTO device_state (device_id, payload, updated_at) VALUES (?,?,?)
		ON CONFLICT(device_id) DO UPDATE SET payload=excluded.payload, updated_at=excluded.updated_at`,
		r.DeviceID, payload, now); err != nil {
		return fmt.Errorf("写设备快照: %w", err)
	}

	for _, line := range r.LogTail {
		if strings.TrimSpace(line) == "" {
			continue
		}
		if _, err := tx.Exec(`INSERT INTO logs (device_id, ts, line) VALUES (?,?,?)`,
			r.DeviceID, r.TS, line); err != nil {
			return fmt.Errorf("写日志: %w", err)
		}
	}

	for _, id := range r.AckedCommands {
		if _, err := tx.Exec(`UPDATE commands SET acked_at=? WHERE id=? AND acked_at IS NULL`, now, id); err != nil {
			return fmt.Errorf("标记指令回执: %w", err)
		}
	}
	return tx.Commit()
}

// Devices 返回全部设备（按最后在线倒序），每台带最近一次上报。
func (s *Store) Devices() ([]model.Device, error) {
	rows, err := s.db.Query(`
		SELECT d.id, d.first_seen, d.last_seen, d.model, d.android, d.version_name,
		       d.protocol_version, d.group_name, d.tags, d.report_interval_ms, d.notes
		FROM devices d ORDER BY d.last_seen DESC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []model.Device
	for rows.Next() {
		var d model.Device
		var tags string
		if err := rows.Scan(&d.ID, &d.FirstSeen, &d.LastSeen, &d.Model, &d.Android, &d.VersionName,
			&d.ProtocolVersion, &d.Group, &tags, &d.ReportIntervalMs, &d.Notes); err != nil {
			return nil, err
		}
		d.Tags = decodeTags(tags)
		out = append(out, d)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	// 逐台补最近上报与期望状态（设备数量级小，N+1 可接受且实现最直白）
	for i := range out {
		if out[i].Report, err = s.lastReport(out[i].ID); err != nil {
			return nil, err
		}
		if out[i].Desired, err = s.Desired(out[i].ID); err != nil {
			return nil, err
		}
	}
	return out, nil
}

// Device 返回单台设备。
func (s *Store) Device(id string) (*model.Device, error) {
	var d model.Device
	var tags string
	err := s.db.QueryRow(`
		SELECT id, first_seen, last_seen, model, android, version_name,
		       protocol_version, group_name, tags, report_interval_ms, notes
		FROM devices WHERE id=?`, id).Scan(&d.ID, &d.FirstSeen, &d.LastSeen, &d.Model, &d.Android,
		&d.VersionName, &d.ProtocolVersion, &d.Group, &tags, &d.ReportIntervalMs, &d.Notes)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	d.Tags = decodeTags(tags)
	if d.Report, err = s.lastReport(id); err != nil {
		return nil, err
	}
	if d.Desired, err = s.Desired(id); err != nil {
		return nil, err
	}
	return &d, nil
}

func (s *Store) lastReport(deviceID string) (*model.Report, error) {
	var payload string
	err := s.db.QueryRow(`SELECT payload FROM device_state WHERE device_id=?`,
		deviceID).Scan(&payload)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var r model.Report
	if err := jsonUnmarshal(payload, &r); err != nil {
		return nil, fmt.Errorf("解析上报 payload: %w", err)
	}
	return &r, nil
}

// SetDeviceNotes 更新设备备注。
//
// 备注是**面板侧标注**（"这台是主教号"），不是设备配置：
// 写进 config 会顺带 bump revision，让设备为一个备注白白热重载一次配置。
func (s *Store) SetDeviceNotes(deviceID, notes string) error {
	_, err := s.db.Exec(`UPDATE devices SET notes=?, updated_at=? WHERE id=?`,
		notes, time.Now().UnixMilli(), deviceID)
	return err
}

// SetDeviceInterval 设置单台设备的上报周期（0 = 用全局默认）。
func (s *Store) SetDeviceInterval(deviceID string, ms int) error {
	_, err := s.db.Exec(`UPDATE devices SET report_interval_ms=?, updated_at=? WHERE id=?`,
		ms, time.Now().UnixMilli(), deviceID)
	return err
}

// RecentReports 返回最近 N 条上报。
func (s *Store) RecentReports(deviceID string, limit int) ([]model.ReportRow, error) {
	rows, err := s.db.Query(`SELECT ts, received_at, foreground, armed, running, state,
		cycle_count, battery_pct, thermal_c, net_rtt_ms
		FROM reports WHERE device_id=? ORDER BY ts DESC LIMIT ?`, deviceID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []model.ReportRow
	for rows.Next() {
		r, err := scanReportRow(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, *r)
	}
	return out, rows.Err()
}

// History 返回按 stepSec 采样后的时间序列（每个桶保留最后一个点）。
func (s *Store) History(deviceID string, from, to int64, stepSec int) ([]model.ReportRow, error) {
	if stepSec < 1 {
		stepSec = 300
	}
	if to <= 0 {
		to = time.Now().UnixMilli() + 1000
	}
	if from <= 0 {
		from = to - 24*3600*1000
	}
	rows, err := s.db.Query(`SELECT ts, received_at, foreground, armed, running, state,
		cycle_count, battery_pct, thermal_c, net_rtt_ms
		FROM reports WHERE device_id=? AND ts BETWEEN ? AND ? ORDER BY ts ASC`, deviceID, from, to)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []model.ReportRow
	bucket := int64(-1)
	for rows.Next() {
		r, err := scanReportRow(rows)
		if err != nil {
			return nil, err
		}
		b := r.TS / (int64(stepSec) * 1000)
		if b != bucket {
			out = append(out, *r)
			bucket = b
		} else {
			out[len(out)-1] = *r // 同桶内用更新的点替换
		}
	}
	return out, rows.Err()
}

// Logs 返回日志（可按关键词过滤）。
func (s *Store) Logs(deviceID string, limit int, q string) ([]model.LogLine, error) {
	if limit <= 0 {
		limit = 200
	}
	var (
		rows *sql.Rows
		err  error
	)
	if q == "" {
		rows, err = s.db.Query(`SELECT ts, line FROM logs WHERE device_id=? ORDER BY ts DESC LIMIT ?`,
			deviceID, limit)
	} else {
		rows, err = s.db.Query(`SELECT ts, line FROM logs WHERE device_id=? AND line LIKE ? ORDER BY ts DESC LIMIT ?`,
			deviceID, "%"+q+"%", limit)
	}
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []model.LogLine
	for rows.Next() {
		var l model.LogLine
		if err := rows.Scan(&l.TS, &l.Line); err != nil {
			return nil, err
		}
		out = append(out, l)
	}
	return out, rows.Err()
}

// DeviceStats 返回 24 小时轮次增量与在线率（按 5 分钟桶覆盖比例估算）。
func (s *Store) DeviceStats(deviceID string, now int64) (cycles24h int, onlineRate float64, err error) {
	since := now - 24*3600*1000
	var maxC, minC sql.NullInt64
	if err = s.db.QueryRow(`SELECT MAX(cycle_count), MIN(cycle_count) FROM reports
		WHERE device_id=? AND ts>=?`, deviceID, since).Scan(&maxC, &minC); err != nil {
		return 0, 0, err
	}
	if maxC.Valid && minC.Valid {
		cycles24h = int(maxC.Int64 - minC.Int64)
	}
	const bucketMs = 300_000
	const bucketsPerDay = 24 * 3600 * 1000 / bucketMs
	var seen int
	if err = s.db.QueryRow(`SELECT COUNT(DISTINCT ts/?) FROM reports WHERE device_id=? AND ts>=?`,
		bucketMs, deviceID, since).Scan(&seen); err != nil {
		return 0, 0, err
	}
	onlineRate = float64(seen) / float64(bucketsPerDay)
	if onlineRate > 1 {
		onlineRate = 1
	}
	return cycles24h, onlineRate, nil
}

// ---------------------------------------------------------------- 配置

// Config 读取生效配置。返回 nil 表示该 scope 没有配置。
func (s *Store) Config(scope string) (*model.Config, string, error) {
	var raw, revision string
	err := s.db.QueryRow(`SELECT json, revision FROM configs WHERE scope=?`, scope).Scan(&raw, &revision)
	if err == sql.ErrNoRows {
		return nil, "", nil
	}
	if err != nil {
		return nil, "", err
	}
	var c model.Config
	if err := jsonUnmarshal(raw, &c); err != nil {
		return nil, "", err
	}
	c.Revision = revision
	return &c, revision, nil
}

// newRevision 生成一个**保证与历史不重复**的 revision。
//
// 为什么不能只用当前毫秒：设备只在 revision 变化时才热重载配置，
// 同一毫秒内连续两次写入会产生同一个 revision，第二次改动就被设备静默忽略了。
// （这个 bug 是被单测抓到的 —— 不带 -race 跑得快，两次写入落在同一毫秒。）
// 撞号时补一个后缀，既保持人类可读，又保证唯一且字典序递增。
func (s *Store) newRevision(scope string) string {
	base := "r" + strconv.FormatInt(time.Now().UnixMilli(), 10)
	rev := base
	for i := 2; i < 1000; i++ {
		var n int
		err := s.db.QueryRow(`SELECT COUNT(*) FROM config_revisions WHERE scope=? AND revision=?`,
			scope, rev).Scan(&n)
		if err != nil || n == 0 {
			return rev
		}
		rev = fmt.Sprintf("%s-%d", base, i)
	}
	return fmt.Sprintf("%s-%d", base, time.Now().UnixNano())
}

// SetConfig 写入配置：服务端生成 revision，并归档历史版本。
func (s *Store) SetConfig(scope string, c model.Config, by string) (string, error) {
	revision := s.newRevision(scope)
	c.Revision = revision
	raw, err := jsonMarshal(c)
	if err != nil {
		return "", err
	}
	now := time.Now().UnixMilli()
	tx, err := s.db.Begin()
	if err != nil {
		return "", err
	}
	defer func() { _ = tx.Rollback() }()
	if _, err := tx.Exec(`INSERT INTO configs (scope, revision, json, updated_at, updated_by)
		VALUES (?,?,?,?,?)
		ON CONFLICT(scope) DO UPDATE SET revision=excluded.revision, json=excluded.json,
		  updated_at=excluded.updated_at, updated_by=excluded.updated_by`,
		scope, revision, raw, now, by); err != nil {
		return "", err
	}
	if _, err := tx.Exec(`INSERT INTO config_revisions (scope, revision, json, created_at, created_by)
		VALUES (?,?,?,?,?)`, scope, revision, raw, now, by); err != nil {
		return "", err
	}
	return revision, tx.Commit()
}

// ConfigRevisions 返回历史版本（新到旧）。
func (s *Store) ConfigRevisions(scope string, limit int) ([]model.ConfigRevision, error) {
	if limit <= 0 {
		limit = 50
	}
	rows, err := s.db.Query(`SELECT revision, created_at, created_by, json FROM config_revisions
		WHERE scope=? ORDER BY created_at DESC LIMIT ?`, scope, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []model.ConfigRevision
	for rows.Next() {
		var cr model.ConfigRevision
		var raw string
		if err := rows.Scan(&cr.Revision, &cr.CreatedAt, &cr.CreatedBy, &raw); err != nil {
			return nil, err
		}
		if err := jsonUnmarshal(raw, &cr.Config); err != nil {
			return nil, err
		}
		out = append(out, cr)
	}
	return out, rows.Err()
}

// ConfigByRevision 取某个历史版本。
func (s *Store) ConfigByRevision(scope, revision string) (*model.Config, error) {
	var raw string
	err := s.db.QueryRow(`SELECT json FROM config_revisions WHERE scope=? AND revision=?`,
		scope, revision).Scan(&raw)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var c model.Config
	if err := jsonUnmarshal(raw, &c); err != nil {
		return nil, err
	}
	return &c, nil
}

// ---------------------------------------------------------------- 期望状态与指令

// Desired 读期望状态（没有记录时返回零值 + rev=0）。
func (s *Store) Desired(deviceID string) (model.Desired, error) {
	var d model.Desired
	var running int
	err := s.db.QueryRow(`SELECT running, rev, at, by FROM desired WHERE device_id=?`,
		deviceID).Scan(&running, &d.Rev, &d.At, &d.By)
	if err == sql.ErrNoRows {
		return model.Desired{}, nil
	}
	if err != nil {
		return d, err
	}
	d.Running = running != 0
	return d, nil
}

// SetDesired 写期望状态，rev 自增（设备据此去重，避免每轮重复启停）。
func (s *Store) SetDesired(deviceID string, running bool, by string) (model.Desired, error) {
	at := time.Now().UnixMilli()
	_, err := s.db.Exec(`INSERT INTO desired (device_id, running, rev, at, by)
		VALUES (?,?,1,?,?)
		ON CONFLICT(device_id) DO UPDATE SET running=excluded.running,
		  rev=desired.rev+1, at=excluded.at, by=excluded.by`,
		deviceID, boolInt(running), at, by)
	if err != nil {
		return model.Desired{}, err
	}
	return s.Desired(deviceID)
}

// EnqueueCommand 投递一次性指令，返回指令 id。
func (s *Store) EnqueueCommand(deviceID, action, by string) (string, error) {
	id := "c_" + strconv.FormatInt(time.Now().UnixNano()/1000, 36)
	_, err := s.db.Exec(`INSERT INTO commands (id, device_id, action, created_at, created_by)
		VALUES (?,?,?,?,?)`, id, deviceID, action, time.Now().UnixMilli(), by)
	return id, err
}

// PendingCommands 取出未投递指令并标记为已投递。
func (s *Store) PendingCommands(deviceID string) ([]model.DeviceCommand, error) {
	rows, err := s.db.Query(`SELECT id, action FROM commands
		WHERE device_id=? AND delivered_at IS NULL ORDER BY created_at ASC LIMIT 10`, deviceID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []model.DeviceCommand
	for rows.Next() {
		var c model.DeviceCommand
		if err := rows.Scan(&c.ID, &c.Action); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	for _, c := range out {
		if _, err := s.db.Exec(`UPDATE commands SET delivered_at=? WHERE id=? AND delivered_at IS NULL`,
			time.Now().UnixMilli(), c.ID); err != nil {
			return nil, err
		}
	}
	return out, nil
}

// AckCommands 标记指令回执。
func (s *Store) AckCommands(deviceID string, ids []string, result string) error {
	for _, id := range ids {
		if _, err := s.db.Exec(`UPDATE commands SET acked_at=?, result=?
			WHERE id=? AND device_id=? AND acked_at IS NULL`, time.Now().UnixMilli(), result, id, deviceID); err != nil {
			return err
		}
	}
	return nil
}

// ---------------------------------------------------------------- 截图

// AddScreenshot 记录一张已落盘的截图。
func (s *Store) AddScreenshot(deviceID, path string, ts, nbytes int64, label string) (string, error) {
	id := "s_" + strconv.FormatInt(time.Now().UnixNano()/1000, 36)
	_, err := s.db.Exec(`INSERT INTO screenshots (id, device_id, ts, path, bytes, label, created_at)
		VALUES (?,?,?,?,?,?,?)`, id, deviceID, ts, path, nbytes, label, time.Now().UnixMilli())
	return id, err
}

// Screenshots 列出截图（可按设备过滤，新到旧）。
func (s *Store) Screenshots(deviceID string, limit int) ([]model.Screenshot, error) {
	if limit <= 0 {
		limit = 50
	}
	var (
		rows *sql.Rows
		err  error
	)
	if deviceID == "" {
		rows, err = s.db.Query(`SELECT id, device_id, ts, bytes, label FROM screenshots
			ORDER BY ts DESC LIMIT ?`, limit)
	} else {
		rows, err = s.db.Query(`SELECT id, device_id, ts, bytes, label FROM screenshots
			WHERE device_id=? ORDER BY ts DESC LIMIT ?`, deviceID, limit)
	}
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []model.Screenshot
	for rows.Next() {
		var sc model.Screenshot
		if err := rows.Scan(&sc.ID, &sc.DeviceID, &sc.TS, &sc.Bytes, &sc.Label); err != nil {
			return nil, err
		}
		sc.URL = "/api/v1/panel/screenshots/" + sc.ID
		out = append(out, sc)
	}
	return out, rows.Err()
}

// ScreenshotFile 返回截图的磁盘路径与设备 id。
func (s *Store) ScreenshotFile(id string) (path, deviceID string, err error) {
	err = s.db.QueryRow(`SELECT path, device_id FROM screenshots WHERE id=?`, id).Scan(&path, &deviceID)
	if err == sql.ErrNoRows {
		return "", "", nil
	}
	return path, deviceID, err
}

// DeleteScreenshot 删除截图记录，返回被删文件的路径。
func (s *Store) DeleteScreenshot(id string) (string, error) {
	var path string
	err := s.db.QueryRow(`SELECT path FROM screenshots WHERE id=?`, id).Scan(&path)
	if err == sql.ErrNoRows {
		return "", nil
	}
	if err != nil {
		return "", err
	}
	if _, err := s.db.Exec(`DELETE FROM screenshots WHERE id=?`, id); err != nil {
		return "", err
	}
	return path, nil
}

// LatestScreenshot 返回设备最新截图 id（没有则空串）。
func (s *Store) LatestScreenshot(deviceID string) (string, error) {
	var id string
	err := s.db.QueryRow(`SELECT id FROM screenshots WHERE device_id=? ORDER BY ts DESC LIMIT 1`,
		deviceID).Scan(&id)
	if err == sql.ErrNoRows {
		return "", nil
	}
	return id, err
}

// PruneScreenshots 按「每设备保留 keepN 张、且不超过 maxAgeDays 天」清理，返回待删文件路径。
func (s *Store) PruneScreenshots(keepN, maxAgeDays int) ([]string, error) {
	cutoff := time.Now().Add(-time.Duration(maxAgeDays) * 24 * time.Hour).UnixMilli()
	rows, err := s.db.Query(`
		SELECT id, path FROM screenshots
		WHERE id IN (
		  SELECT id FROM (
		    SELECT id, device_id, ROW_NUMBER() OVER (PARTITION BY device_id ORDER BY ts DESC) AS rn
		    FROM screenshots
		  ) WHERE rn > ?
		) OR ts < ?`, keepN, cutoff)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var paths []string
	var ids []string
	for rows.Next() {
		var id, path string
		if err := rows.Scan(&id, &path); err != nil {
			return nil, err
		}
		ids = append(ids, id)
		paths = append(paths, path)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	for _, id := range ids {
		if _, err := s.db.Exec(`DELETE FROM screenshots WHERE id=?`, id); err != nil {
			return nil, err
		}
	}
	return paths, nil
}

// ---------------------------------------------------------------- 会话

// CreateSession 建会话，返回明文 token（只在此刻存在，入库的是哈希）。
func (s *Store) CreateSession(user string, ttl time.Duration, ip, ua string) (string, int64, error) {
	token, hash, err := newToken()
	if err != nil {
		return "", 0, err
	}
	now := time.Now()
	expires := now.Add(ttl).UnixMilli()
	_, err = s.db.Exec(`INSERT INTO sessions (token_hash, user, created_at, expires_at, last_seen_at, ip, user_agent)
		VALUES (?,?,?,?,?,?,?)`, hash, user, now.UnixMilli(), expires, now.UnixMilli(), ip, ua)
	return token, expires, err
}

// SessionUser 校验会话，返回用户名与过期时间。
func (s *Store) SessionUser(token string) (string, int64, bool) {
	if token == "" {
		return "", 0, false
	}
	var user string
	var expires int64
	err := s.db.QueryRow(`SELECT user, expires_at FROM sessions WHERE token_hash=?`,
		hashToken(token)).Scan(&user, &expires)
	if err != nil {
		return "", 0, false
	}
	if time.Now().UnixMilli() > expires {
		return "", 0, false
	}
	_, _ = s.db.Exec(`UPDATE sessions SET last_seen_at=? WHERE token_hash=?`,
		time.Now().UnixMilli(), hashToken(token))
	return user, expires, true
}

// DeleteSession 登出。
func (s *Store) DeleteSession(token string) {
	_, _ = s.db.Exec(`DELETE FROM sessions WHERE token_hash=?`, hashToken(token))
}

// PruneSessions 清理过期会话。
func (s *Store) PruneSessions() error {
	_, err := s.db.Exec(`DELETE FROM sessions WHERE expires_at < ?`, time.Now().UnixMilli())
	return err
}

// ---------------------------------------------------------------- 设置与审计

// Setting 读一个设置项。
func (s *Store) Setting(key string) (string, error) {
	var v string
	err := s.db.QueryRow(`SELECT value FROM settings WHERE key=?`, key).Scan(&v)
	if err == sql.ErrNoRows {
		return "", nil
	}
	return v, err
}

// SetSetting 写一个设置项。
func (s *Store) SetSetting(key, value string) error {
	_, err := s.db.Exec(`INSERT INTO settings (key, value, updated_at) VALUES (?,?,?)
		ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at`,
		key, value, time.Now().UnixMilli())
	return err
}

// Settings 读全部全局设置（缺项用默认值补齐）。
func (s *Store) Settings() model.Settings {
	out := model.DefaultSettings()
	get := func(key string) string { v, _ := s.Setting(key); return v }
	if v := get("default_report_interval_ms"); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			out.DefaultReportIntervalMs = n
		}
	}
	if v := get("report_retention_days"); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			out.ReportRetentionDays = n
		}
	}
	if v := get("log_retention_days"); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			out.LogRetentionDays = n
		}
	}
	if v := get("screenshot_retention_n"); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			out.ScreenshotRetentionN = n
		}
	}
	if v := get("screenshot_retention_days"); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			out.ScreenshotRetentionDays = n
		}
	}
	return out
}

// SaveSettings 写全局设置。
func (s *Store) SaveSettings(st model.Settings) error {
	pairs := map[string]string{
		"default_report_interval_ms": strconv.Itoa(st.DefaultReportIntervalMs),
		"report_retention_days":      strconv.Itoa(st.ReportRetentionDays),
		"log_retention_days":         strconv.Itoa(st.LogRetentionDays),
		"screenshot_retention_n":     strconv.Itoa(st.ScreenshotRetentionN),
		"screenshot_retention_days":  strconv.Itoa(st.ScreenshotRetentionDays),
	}
	for k, v := range pairs {
		if err := s.SetSetting(k, v); err != nil {
			return err
		}
	}
	return nil
}

// AddAudit 写审计。审计失败不阻断主流程（记日志即可）。
func (s *Store) AddAudit(actor, action, target, detail, ip string) {
	_, err := s.db.Exec(`INSERT INTO audit (ts, actor, action, target, detail, ip) VALUES (?,?,?,?,?,?)`,
		time.Now().UnixMilli(), actor, action, target, detail, ip)
	if err != nil {
		slog.Warn("写审计失败", "err", err, "action", action)
	}
}

// Audits 查审计。
func (s *Store) Audits(limit int, actor, target string) ([]model.AuditEntry, error) {
	if limit <= 0 {
		limit = 100
	}
	q := `SELECT id, ts, actor, action, target, detail, ip FROM audit WHERE 1=1`
	var args []any
	if actor != "" {
		q += ` AND actor=?`
		args = append(args, actor)
	}
	if target != "" {
		q += ` AND target LIKE ?`
		args = append(args, "%"+target+"%")
	}
	q += ` ORDER BY ts DESC LIMIT ?`
	args = append(args, limit)
	rows, err := s.db.Query(q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []model.AuditEntry
	for rows.Next() {
		var a model.AuditEntry
		if err := rows.Scan(&a.ID, &a.TS, &a.Actor, &a.Action, &a.Target, &a.Detail, &a.IP); err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, rows.Err()
}

// Prune 清理过期数据。
//
// 日志单独给一个更短的保留期（默认 7 天）：每份上报带 8 行日志，
// 200 台 60s 周期就是每天 230 万行 —— 它比时间序列本身还占地方。
// 面板看日志是为了排障，留几天足够，不需要跟曲线一样久。
func (s *Store) Prune(reportRetentionDays, logRetentionDays int) error {
	cutoff := time.Now().Add(-time.Duration(reportRetentionDays) * 24 * time.Hour).UnixMilli()
	if _, err := s.db.Exec(`DELETE FROM reports WHERE received_at < ?`, cutoff); err != nil {
		return err
	}
	if _, err := s.db.Exec(`DELETE FROM audit WHERE ts < ?`, cutoff); err != nil {
		return err
	}
	logCutoff := time.Now().Add(-time.Duration(logRetentionDays) * 24 * time.Hour).UnixMilli()
	if _, err := s.db.Exec(`DELETE FROM logs WHERE ts < ?`, logCutoff); err != nil {
		return err
	}
	return nil
}
