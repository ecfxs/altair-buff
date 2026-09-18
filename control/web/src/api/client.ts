import type {
  AuditEntry,
  Buff,
  ConfigEnvelope,
  ConfigRevision,
  DeviceConfig,
  DeviceDetail,
  DeviceSummary,
  DesiredState,
  DevicesResponse,
  HistoryPoint,
  LogLine,
  Me,
  Screenshot,
  SessionInfo,
  Settings,
} from './types';

/**
 * 薄的类型化 API 客户端。
 *
 * - 全部走**相对路径** `/api/v1/panel/...`，不写 host（同源部署，Caddy 反代）。
 * - 鉴权是 HttpOnly 会话 Cookie：fetch 保持默认 `credentials: 'same-origin'`。
 * - 收到 401 → 抛 UnauthorizedError，由 App 层跳 `/login`。
 * - 后端尚不存在，故所有错误都走统一的 ApiError，页面只认 `status` / `message`。
 */

const BASE = '/api/v1/panel';

export class ApiError extends Error {
  readonly status: number;
  readonly body: unknown;

  constructor(status: number, message: string, body?: unknown) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.body = body;
  }
}

export class UnauthorizedError extends ApiError {
  constructor(message = '会话已过期，请重新登录', body?: unknown) {
    super(401, message, body);
    this.name = 'UnauthorizedError';
  }
}

export class RateLimitedError extends ApiError {
  constructor(message = '尝试过频，请稍后再试', body?: unknown) {
    super(429, message, body);
    this.name = 'RateLimitedError';
  }
}

function qs(params: Record<string, string | number | boolean | undefined | null>): string {
  const sp = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === '') continue;
    sp.set(k, String(v));
  }
  const s = sp.toString();
  return s ? `?${s}` : '';
}

async function readBody(res: Response): Promise<unknown> {
  const ct = res.headers.get('content-type') ?? '';
  try {
    if (ct.includes('application/json')) return await res.json();
    return await res.text();
  } catch {
    return undefined;
  }
}

/** 从错误响应里抠出人话（后端契约：{ok:false, error:"..."}）。 */
function errorMessage(status: number, body: unknown): string {
  if (body && typeof body === 'object') {
    const rec = body as Record<string, unknown>;
    for (const k of ['error', 'message', 'detail']) {
      const v = rec[k];
      if (typeof v === 'string' && v) return v;
    }
  }
  if (typeof body === 'string' && body && body.length < 300) return body;
  if (status === 401) return '会话已过期，请重新登录';
  if (status === 429) return '尝试过频，请稍后再试';
  if (status === 404) return '资源不存在';
  if (status === 413) return '文件过大';
  return `请求失败（HTTP ${status}）`;
}

async function raw(path: string, init?: RequestInit): Promise<Response> {
  const res = await fetch(`${BASE}${path}`, {
    credentials: 'same-origin',
    cache: 'no-store',
    ...init,
    headers: {
      Accept: 'application/json, text/event-stream',
      ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
      ...(init?.headers ?? {}),
    },
  });
  if (!res.ok) {
    const body = await readBody(res);
    const msg = errorMessage(res.status, body);
    if (res.status === 401) throw new UnauthorizedError(msg, body);
    if (res.status === 429) throw new RateLimitedError(msg, body);
    throw new ApiError(res.status, msg, body);
  }
  return res;
}

async function req<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await raw(path, init);
  // 204 / 空 body 宽容处理
  const text = await res.text();
  if (!text) return undefined as T;
  try {
    return JSON.parse(text) as T;
  } catch {
    return text as unknown as T;
  }
}

const post = <T>(path: string, body?: unknown): Promise<T> =>
  req<T>(path, { method: 'POST', ...(body === undefined ? {} : { body: JSON.stringify(body) }) });

const put = <T>(path: string, body: unknown): Promise<T> =>
  req<T>(path, { method: 'PUT', body: JSON.stringify(body) });

const del = <T>(path: string): Promise<T> => req<T>(path, { method: 'DELETE' });

/* ------------------------------------------------------------------ 会话 */

export const api = {
  login(username: string, password: string): Promise<SessionInfo> {
    return post<SessionInfo>('/login', { username, password });
  },

  logout(): Promise<{ ok: boolean }> {
    return post<{ ok: boolean }>('/logout');
  },

  me(): Promise<Me> {
    return req<Me>('/me');
  },

  /* --------------------------------------------------------------- 设备 */

  devices(): Promise<DevicesResponse> {
    return req<DevicesResponse>('/devices');
  },

  device(id: string, reports = 50): Promise<DeviceDetail> {
    return req<DeviceDetail>(`/devices/${encodeURIComponent(id)}${qs({ reports })}`);
  },

  history(
    id: string,
    opts: { from?: number; to?: number; step?: number } = {},
  ): Promise<{ points: HistoryPoint[] }> {
    return req<{ points: HistoryPoint[] }>(
      `/devices/${encodeURIComponent(id)}/history${qs({
        from: opts.from,
        to: opts.to,
        step: opts.step ?? 300,
      })}`,
    );
  },

  logs(id: string, opts: { limit?: number; q?: string } = {}): Promise<{ lines: LogLine[] }> {
    return req<{ lines: LogLine[] }>(
      `/devices/${encodeURIComponent(id)}/logs${qs({ limit: opts.limit ?? 200, q: opts.q })}`,
    );
  },

  /** 更新设备面板侧信息（备注）。走设备表，不碰配置 revision。 */
  updateDevice(id: string, patch: { notes?: string }): Promise<DeviceSummary> {
    return put<DeviceSummary>(`/devices/${encodeURIComponent(id)}`, patch);
  },

  setInterval(id: string, reportIntervalMs: number): Promise<{ ok: boolean; reportIntervalMs?: number }> {
    return put<{ ok: boolean; reportIntervalMs?: number }>(
      `/devices/${encodeURIComponent(id)}/interval`,
      { reportIntervalMs },
    );
  },

  command(id: string, action: 'start' | 'stop'): Promise<DesiredState> {
    return post<DesiredState>(`/devices/${encodeURIComponent(id)}/command`, { action });
  },

  batchCommand(ids: string[], action: 'start' | 'stop'): Promise<{ ok: boolean; applied: string[] }> {
    return post<{ ok: boolean; applied: string[] }>('/devices/batch-command', { ids, action });
  },

  /* --------------------------------------------------------------- 配置 */

  getConfig(scope: string): Promise<ConfigEnvelope> {
    return req<ConfigEnvelope>(`/configs/${encodeURIComponent(scope)}`);
  },

  putConfig(scope: string, config: DeviceConfig): Promise<ConfigEnvelope> {
    return put<ConfigEnvelope>(`/configs/${encodeURIComponent(scope)}`, config);
  },

  revisions(scope: string): Promise<{ revisions: ConfigRevision[] }> {
    return req<{ revisions: ConfigRevision[] }>(`/configs/${encodeURIComponent(scope)}/revisions`);
  },

  rollback(scope: string, revision: string): Promise<ConfigEnvelope> {
    return post<ConfigEnvelope>(`/configs/${encodeURIComponent(scope)}/rollback`, { revision });
  },

  /* --------------------------------------------------------------- 截图 */

  screenshots(opts: { deviceId?: string; limit?: number } = {}): Promise<{ screenshots: Screenshot[] }> {
    return req<{ screenshots: Screenshot[] }>(
      `/screenshots${qs({ deviceId: opts.deviceId, limit: opts.limit ?? 50 })}`,
    );
  },

  deleteScreenshot(id: string): Promise<{ ok: boolean }> {
    return del<{ ok: boolean }>(`/screenshots/${encodeURIComponent(id)}`);
  },

  /** 原图 URL（同源，会话 Cookie 自动带上，可直接放进 <img src>）。 */
  screenshotUrl(id: string): string {
    return `${BASE}/screenshots/${encodeURIComponent(id)}`;
  },

  /* --------------------------------------------------------- 审计 / 设置 */

  audit(opts: { limit?: number; actor?: string; target?: string } = {}): Promise<{ entries: AuditEntry[] }> {
    return req<{ entries: AuditEntry[] }>(
      `/audit${qs({ limit: opts.limit ?? 100, actor: opts.actor, target: opts.target })}`,
    );
  },

  getSettings(): Promise<Settings> {
    return req<Settings>('/settings');
  },

  putSettings(settings: Settings): Promise<Settings> {
    return put<Settings>('/settings', settings);
  },

  /** SSE 事件流地址（EventSource 用；同源相对路径，Cookie 自动携带）。 */
  eventsUrl(): string {
    return `${BASE}/events`;
  },
};

/** 默认配置的兜底（后端未返回 buff 时前端补齐 3 行，编号 1..3）。 */
export function defaultBuffs(): Buff[] {
  return [
    { idx: 1, enabled: true, key: 1, durationMin: 5 },
    { idx: 2, enabled: false, key: 2, durationMin: 5 },
    { idx: 3, enabled: false, key: 3, durationMin: 5 },
  ];
}
