/**
 * 时间与数字工具。
 *
 * 契约里有两套时钟，混用会显示错乱，这里集中说明：
 * - 「墙钟秒」：`DeviceSummary.lastSeen` / `firstSeen`、`AuditEntry.ts`、`ReportRow.receivedAt`
 *   —— Go 侧写库用的是 Unix 秒（见 docs/集控重构方案.md 的示例 1699999999）。本模块统一
 *   用 `toMs()` 归一化，若服务端将来改成毫秒也不会算错。
 * - 「设备本地毫秒」：`DeviceReport.ts`(→ `DeviceSummary.reportTs`)、`EngineStatus.nextDueAt`
 *   —— 这是云手机自己的时钟，必须用 `skew = Date.now() - reportTs` 校正后再算倒计时。
 */

/** 数值时间戳归一化到毫秒：< 1e11 视为秒。 */
export function toMs(ts: number | null | undefined): number | null {
  if (ts == null || !Number.isFinite(ts) || ts <= 0) return null;
  return ts < 1e11 ? ts * 1000 : ts;
}

/** 相对时间：「12s 前 / 5m 前 / 3h 前 / 2d 前」。 */
export function ago(ts: number | null | undefined, now = Date.now()): string {
  const ms = toMs(ts);
  if (ms == null) return '—';
  const s = Math.max(0, Math.floor((now - ms) / 1000));
  if (s < 60) return `${s}s 前`;
  if (s < 3600) return `${Math.floor(s / 60)}m 前`;
  if (s < 86400) return `${Math.floor(s / 3600)}h 前`;
  return `${Math.floor(s / 86400)}d 前`;
}

/** 距今秒数（用于在线判定与状态圆点）。 */
export function ageSeconds(ts: number | null | undefined, now = Date.now()): number {
  const ms = toMs(ts);
  if (ms == null) return Number.POSITIVE_INFINITY;
  return Math.max(0, (now - ms) / 1000);
}

export const ONLINE_SEC = 180; // <3min 在线
export const WARM_SEC = 900; // <15min 久未上报

export type Freshness = 'on' | 'warm' | 'off';

export function freshness(ageSec: number): Freshness {
  if (ageSec < ONLINE_SEC) return 'on';
  if (ageSec < WARM_SEC) return 'warm';
  return 'off';
}

export const DOT_COLOR: Record<Freshness, string> = {
  on: '#3bd16f',
  warm: '#ffc53d',
  off: '#ff6b6b',
};

/** mm:ss（倒计时 / 周期）。 */
export function mmss(ms: number): string {
  const t = Math.max(0, Math.floor(ms / 1000));
  return `${Math.floor(t / 60)}:${String(t % 60).padStart(2, '0')}`;
}

/** 「3 分 20 秒」这种中文时长，用于周期展示。 */
export function humanDuration(ms: number | null | undefined): string {
  if (ms == null || !Number.isFinite(ms) || ms <= 0) return '—';
  const s = Math.round(ms / 1000);
  if (s < 60) return `${s} 秒`;
  const m = Math.floor(s / 60);
  const rs = s % 60;
  if (m < 60) return rs ? `${m} 分 ${rs} 秒` : `${m} 分`;
  const h = Math.floor(m / 60);
  const rm = m % 60;
  return rm ? `${h} 小时 ${rm} 分` : `${h} 小时`;
}

/** ISO 本地时间（表格里用）。 */
export function stamp(ts: number | null | undefined): string {
  const ms = toMs(ts);
  if (ms == null) return '—';
  const d = new Date(ms);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(
    d.getMinutes(),
  )}:${p(d.getSeconds())}`;
}

/** 秒级墙钟 + 当前时间，用于倒计时的时钟偏差校正。 */
export function clockSkew(reportTs: number | null | undefined): number {
  return reportTs ? Date.now() - reportTs : 0;
}

export type Countdown = { txt: string; pct: number; leftMs: number };

/**
 * 距下次补 BUFF 的倒计时（旧面板 countdown() 的等价实现）。
 *
 * 关键：nextDueAt 是**设备本地时钟**，直接减 Date.now() 会在云手机与浏览器时钟
 * 不一致时错乱。用设备上报的 reportTs 求出偏差 skew，再把它换算到本地时间轴。
 */
export function countdown(
  engine:
    | { nextDueAt?: number | null; cyclePeriodMs?: number | null; running?: boolean | null }
    | null
    | undefined,
  reportTs: number | null | undefined,
  now = Date.now(),
): Countdown {
  if (!engine || !engine.nextDueAt || !engine.running) return { txt: '—', pct: 0, leftMs: 0 };
  const skew = clockSkew(reportTs);
  const nowDev = now - skew;
  const leftMs = Math.max(0, engine.nextDueAt - nowDev);
  const period = engine.cyclePeriodMs ?? 0;
  return {
    txt: mmss(leftMs),
    pct: period > 0 ? Math.min(100, 100 * (1 - leftMs / period)) : 0,
    leftMs,
  };
}

/** 契约 PATCH：BUFF 周期提示文案里用的系数（旧面板硬编码 0.94）。 */
export const CYCLE_FACTOR = 0.94;

/** 启用的 BUFF 里取最短时长 × 0.94 = 循环周期（旧面板提示语）。 */
export function cyclePeriodFromBuffs(
  buffs: Array<{ enabled: boolean; durationSec?: number; durationMin?: number }> | null | undefined,
): number | null {
  // 优先用秒；老配置只有分钟 → ×60
  const secs = (buffs ?? [])
    .filter((b) => b.enabled)
    .map((b) => b.durationSec ?? (b.durationMin ?? 0) * 60)
    .filter((v) => Number.isFinite(v) && v > 0);
  if (secs.length === 0) return null;
  return Math.round(Math.min(...secs) * 1000 * CYCLE_FACTOR);
}
