-- 0001_init: 集控服务初始表结构
-- 说明：单账号面板（无 users 表），凭据存 settings。

CREATE TABLE IF NOT EXISTS devices (
  id                 TEXT PRIMARY KEY,
  first_seen         INTEGER NOT NULL,
  last_seen          INTEGER NOT NULL,
  model              TEXT NOT NULL DEFAULT '',
  android            TEXT NOT NULL DEFAULT '',
  version_name       TEXT NOT NULL DEFAULT '',
  protocol_version   INTEGER NOT NULL DEFAULT 1,
  group_name         TEXT NOT NULL DEFAULT '默认',
  tags               TEXT NOT NULL DEFAULT '[]',
  report_interval_ms INTEGER NOT NULL DEFAULT 0,   -- 0 = 用全局默认
  notes              TEXT NOT NULL DEFAULT '',
  created_at         INTEGER NOT NULL,
  updated_at         INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS reports (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id    TEXT NOT NULL,
  ts           INTEGER NOT NULL,          -- 设备时钟（毫秒）
  received_at  INTEGER NOT NULL,          -- 服务器时钟（毫秒）
  foreground   TEXT NOT NULL DEFAULT '',
  armed        INTEGER NOT NULL DEFAULT 0,
  running      INTEGER NOT NULL DEFAULT 0,
  state        TEXT NOT NULL DEFAULT '',
  cycle_count  INTEGER NOT NULL DEFAULT 0,
  battery_pct  INTEGER,
  thermal_c    REAL,
  net_rtt_ms   INTEGER,
  payload      TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS reports_device_ts ON reports(device_id, ts DESC);

CREATE TABLE IF NOT EXISTS logs (
  id        INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id TEXT NOT NULL,
  ts        INTEGER NOT NULL,
  line      TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS logs_device_ts ON logs(device_id, ts DESC);

CREATE TABLE IF NOT EXISTS configs (
  scope      TEXT PRIMARY KEY,
  revision   TEXT NOT NULL,
  json       TEXT NOT NULL,
  updated_at INTEGER NOT NULL,
  updated_by TEXT NOT NULL DEFAULT 'admin'
);

CREATE TABLE IF NOT EXISTS config_revisions (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  scope      TEXT NOT NULL,
  revision   TEXT NOT NULL,
  json       TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  created_by TEXT NOT NULL DEFAULT 'admin'
);
CREATE INDEX IF NOT EXISTS config_revisions_scope ON config_revisions(scope, created_at DESC);

CREATE TABLE IF NOT EXISTS desired (
  device_id TEXT PRIMARY KEY,
  running   INTEGER NOT NULL,
  rev       INTEGER NOT NULL,
  at        INTEGER NOT NULL,
  by        TEXT NOT NULL DEFAULT 'admin'
);

CREATE TABLE IF NOT EXISTS commands (
  id           TEXT PRIMARY KEY,
  device_id    TEXT NOT NULL,
  action       TEXT NOT NULL,
  payload      TEXT NOT NULL DEFAULT '',
  created_at   INTEGER NOT NULL,
  created_by   TEXT NOT NULL DEFAULT 'admin',
  delivered_at INTEGER,
  acked_at     INTEGER,
  result       TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS commands_pending ON commands(device_id, delivered_at);

CREATE TABLE IF NOT EXISTS screenshots (
  id         TEXT PRIMARY KEY,
  device_id  TEXT NOT NULL,
  ts         INTEGER NOT NULL,
  path       TEXT NOT NULL,
  bytes      INTEGER NOT NULL,
  label      TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS screenshots_device_ts ON screenshots(device_id, ts DESC);

CREATE TABLE IF NOT EXISTS sessions (
  token_hash   TEXT PRIMARY KEY,
  user         TEXT NOT NULL,
  created_at   INTEGER NOT NULL,
  expires_at   INTEGER NOT NULL,
  last_seen_at INTEGER NOT NULL,
  ip           TEXT NOT NULL DEFAULT '',
  user_agent   TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS audit (
  id     INTEGER PRIMARY KEY AUTOINCREMENT,
  ts     INTEGER NOT NULL,
  actor  TEXT NOT NULL,
  action TEXT NOT NULL,
  target TEXT NOT NULL DEFAULT '',
  detail TEXT NOT NULL DEFAULT '',
  ip     TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS audit_ts ON audit(ts DESC);

CREATE TABLE IF NOT EXISTS settings (
  key        TEXT PRIMARY KEY,
  value      TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);
