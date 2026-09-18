-- 0002_device_state: 把「每台设备最近一次完整上报」从 reports 里挪出来。
--
-- 起因（实测数据，不是猜的）：
--   reports.payload 存的是整包上报 JSON，实测 ≈ 820 字节/份；
--   真实 APK 的 logTail 有 8 行、每行最长 200 字符，payload 还会更大。
--   20 台 × 4s 周期实测 1454 字节/份 → 折算 200 台 60s 周期 = 399MB/天、30 天保留 11.7GB。
--   小 VPS 撑不住，而且这些字节里绝大部分是每份都重复的 engine/心跳/日志尾部。
--
-- 改法：面板真正需要的只有「这台设备最近一次上报长什么样」（一张快照）。
--   时间序列（reports）只留标量字段，用于曲线与历史；快照单独一表，每台一行。
--   日志尾部本来就已经单独写进 logs 表了，放在 payload 里是重复存储。
--
-- 效果：时间序列每行从 ~950 字节降到 ~105 字节（约 9 倍），快照表每台一行、可忽略。

CREATE TABLE IF NOT EXISTS device_state (
  device_id  TEXT PRIMARY KEY,
  payload    TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);

-- 迁移既有数据：先把每台设备最新那份 payload 搬过去
INSERT OR IGNORE INTO device_state (device_id, payload, updated_at)
SELECT r.device_id, r.payload, r.received_at
FROM reports r
JOIN (
  SELECT device_id, MAX(ts) AS mts FROM reports GROUP BY device_id
) m ON m.device_id = r.device_id AND m.mts = r.ts
WHERE r.payload <> '';

-- 历史行里的 payload 清空，让 SQLite 复用这些页（不 VACUUM，避免迁移期间长时间锁库）
UPDATE reports SET payload = '';
