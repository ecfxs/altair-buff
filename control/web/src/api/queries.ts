import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { api, UnauthorizedError } from './client';
import type { DeviceConfig, DevicesResponse, Me, Settings } from './types';

/**
 * TanStack Query 封装。
 *
 * queryKey 约定（与 docs/集控重构方案.md 8.2 一致）：
 *   ['me'] / ['devices'] / ['device', id] / ['device', id, 'logs', {limit,q}] /
 *   ['device', id, 'history', {...}] / ['config', scope] / ['config', scope, 'revisions'] /
 *   ['screenshots', {deviceId,limit}] / ['audit', {actor,target,limit}] / ['settings']
 */

export const qk = {
  me: ['me'] as const,
  devices: ['devices'] as const,
  device: (id: string) => ['device', id] as const,
  history: (id: string, step: number) => ['device', id, 'history', { step }] as const,
  logs: (id: string, q: string, limit: number) => ['device', id, 'logs', { q, limit }] as const,
  config: (scope: string) => ['config', scope] as const,
  revisions: (scope: string) => ['config', scope, 'revisions'] as const,
  screenshots: (deviceId: string, limit: number) => ['screenshots', { deviceId, limit }] as const,
  audit: (actor: string, target: string, limit: number) => ['audit', { actor, target, limit }] as const,
  settings: ['settings'] as const,
};

/** 401 交给上层跳 /login，别在 query 层重试。 */
function retry(failureCount: number, error: unknown): boolean {
  if (error instanceof UnauthorizedError) return false;
  return failureCount < 2;
}

/* ---------------------------------------------------------------- 会话 */

export function useMe(opts?: { enabled?: boolean }) {
  return useQuery<Me, Error>({
    queryKey: qk.me,
    queryFn: () => api.me(),
    retry,
    staleTime: 5 * 60_000,
    ...(opts?.enabled === undefined ? {} : { enabled: opts.enabled }),
  });
}

/* ---------------------------------------------------------------- 设备 */

export function useDevices() {
  return useQuery<DevicesResponse, Error>({
    queryKey: qk.devices,
    queryFn: () => api.devices(),
    retry,
    staleTime: 0,
  });
}

export function useDevice(id: string, reports = 50) {
  return useQuery({
    queryKey: [...qk.device(id), { reports }] as const,
    queryFn: () => api.device(id, reports),
    retry,
    enabled: Boolean(id),
  });
}

export function useHistory(id: string, step = 300) {
  return useQuery({
    queryKey: qk.history(id, step),
    queryFn: () => api.history(id, { step }),
    retry,
    enabled: Boolean(id),
  });
}

export function useLogs(id: string, q: string, limit = 200) {
  return useQuery({
    queryKey: qk.logs(id, q, limit),
    queryFn: () => api.logs(id, { limit, q: q || undefined }),
    retry,
    enabled: Boolean(id),
  });
}

export function useScreenshots(deviceId: string, limit = 50) {
  return useQuery({
    queryKey: qk.screenshots(deviceId, limit),
    queryFn: () => api.screenshots({ deviceId: deviceId || undefined, limit }),
    retry,
  });
}

/* ---------------------------------------------------------------- 配置 */

export function useConfig(scope: string) {
  return useQuery({
    queryKey: qk.config(scope),
    queryFn: () => api.getConfig(scope),
    retry,
    enabled: Boolean(scope),
  });
}

export function useRevisions(scope: string) {
  return useQuery({
    queryKey: qk.revisions(scope),
    queryFn: () => api.revisions(scope),
    retry,
    enabled: Boolean(scope),
  });
}

/* ------------------------------------------------------------ 审计/设置 */

export function useAudit(actor: string, target: string, limit = 100) {
  return useQuery({
    queryKey: qk.audit(actor, target, limit),
    queryFn: () => api.audit({ actor: actor || undefined, target: target || undefined, limit }),
    retry,
  });
}

export function useSettings() {
  return useQuery<Settings, Error>({
    queryKey: qk.settings,
    queryFn: () => api.getSettings(),
    retry,
  });
}

/* ----------------------------------------------------------------- 变更 */

export function useDeviceCommand() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, action }: { id: string; action: 'start' | 'stop' }) => api.command(id, action),
    onSuccess: (_d, v) => {
      void qc.invalidateQueries({ queryKey: qk.devices });
      void qc.invalidateQueries({ queryKey: qk.device(v.id) });
    },
  });
}

export function useBatchCommand() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ ids, action }: { ids: string[]; action: 'start' | 'stop' }) =>
      api.batchCommand(ids, action),
    onSuccess: () => void qc.invalidateQueries({ queryKey: qk.devices }),
  });
}

/** 更新设备备注（面板侧标注，不下发设备）。 */
export function useUpdateDevice(id: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (patch: { notes?: string }) => api.updateDevice(id, patch),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: qk.devices });
      void qc.invalidateQueries({ queryKey: qk.device(id) });
    },
  });
}

export function useSetInterval(id: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (ms: number) => api.setInterval(id, ms),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: qk.devices });
      void qc.invalidateQueries({ queryKey: qk.device(id) });
    },
  });
}

export function usePutConfig(scope: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (config: DeviceConfig) => api.putConfig(scope, config),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: qk.config(scope) });
      void qc.invalidateQueries({ queryKey: qk.revisions(scope) });
      void qc.invalidateQueries({ queryKey: qk.devices });
    },
  });
}

export function useRollback(scope: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (revision: string) => api.rollback(scope, revision),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: qk.config(scope) });
      void qc.invalidateQueries({ queryKey: qk.revisions(scope) });
      void qc.invalidateQueries({ queryKey: qk.devices });
    },
  });
}

export function usePutSettings() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (s: Settings) => api.putSettings(s),
    onSuccess: () => void qc.invalidateQueries({ queryKey: qk.settings }),
  });
}

export function useLogout() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api.logout(),
    onSuccess: () => qc.clear(),
  });
}

/* ------------------------------------------------------------------ SSE */

export type LiveMode = 'connecting' | 'sse' | 'polling';

/**
 * SSE 实时刷新 + 断线降级。
 *
 * - 收到任何事件就 invalidateQueries（服务端事件体只是提示，前端一律重取，避免丢事件导致不一致）。
 * - 曾经连上过再断线 → 立即打开 5s 轮询；重连成功（`onopen`）→ 停掉轮询。
 * - 从没连上过（例如反代没透传 SSE、或服务端不支持）→ 连续失败 3 次后进入轮询模式，
 *   避免无限重连时统计条一直在「实时/轮询」之间闪烁。
 */
export function useLiveEvents(enabled: boolean, pollMs = 5000): LiveMode {
  const qc = useQueryClient();
  const [mode, setMode] = useState<LiveMode>('connecting');
  const pollRef = useRef<number | null>(null);
  const everOpenRef = useRef(false);

  useEffect(() => {
    if (!enabled) return;

    const invalidateAll = () => {
      void qc.invalidateQueries({ queryKey: qk.devices });
      void qc.invalidateQueries({ queryKey: ['device'] });
      void qc.invalidateQueries({ queryKey: ['screenshots'] });
    };

    const stopPoll = () => {
      if (pollRef.current != null) {
        window.clearInterval(pollRef.current);
        pollRef.current = null;
      }
    };

    const startPoll = () => {
      if (pollRef.current != null) return;
      pollRef.current = window.setInterval(invalidateAll, pollMs);
    };

    const es = new EventSource(api.eventsUrl());
    let failures = 0;

    es.onopen = () => {
      everOpenRef.current = true;
      failures = 0;
      stopPoll();
      setMode('sse');
    };

    es.onerror = () => {
      // EventSource 自己会重连；这段时间用轮询兜底。
      failures += 1;
      if (everOpenRef.current || failures >= 3) {
        startPoll();
        setMode('polling');
      }
    };
    // 兜底：浏览器不给 onerror（例如被中间层静默挂住）时，10s 后也切轮询
    const watchdog = window.setTimeout(() => {
      if (pollRef.current == null) {
        startPoll();
        setMode('polling');
      }
    }, Math.max(pollMs * 2, 10_000));

    // 契约里的事件名；逐个订阅（'message' 也订一份，防服务端发无名事件）
    const names = ['report', 'command', 'screenshot', 'device-online', 'device-offline', 'message'];
    const handlers: Array<[string, EventListener]> = names.map((n) => {
      const h: EventListener = () => invalidateAll();
      es.addEventListener(n, h);
      return [n, h];
    });

    return () => {
      for (const [n, h] of handlers) es.removeEventListener(n, h);
      window.clearTimeout(watchdog);
      es.close();
      stopPoll();
    };
  }, [enabled, pollMs, qc]);

  return mode;
}

/** 让「距刷新」倒计时归零时手动重取一次。 */
export function useManualRefresh(): () => void {
  const qc = useQueryClient();
  return () => {
    void qc.invalidateQueries({ queryKey: qk.devices });
  };
}

/** 全局失效（登出/切换作用域时用）。 */
export function invalidateEverything(qc: QueryClient) {
  void qc.invalidateQueries();
}
