import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import type { DeviceSummary } from '@/api/types';
import { useBatchCommand, useDeviceCommand, useDevices, useLogs } from '@/api/queries';
import { ApiError } from '@/api/client';
import { EMPTY_DEVICES_HINT, isRunning } from '@/lib/status';
import { ageSeconds, ONLINE_SEC } from '@/lib/time';
import { useRefreshCountdown } from '@/lib/useTicker';
import { Button, Drawer, Icon, SectionTitle, StatBox, useToast } from '@/ui';
import { DeviceCard } from './DeviceCard';
import { DeviceEditModal } from './DeviceEditModal';

/** 总览：统计条 + 设备卡片网格 + 多选批量启停。 */
export function DevicesPage() {
  const { data, isPending, isError, error, refetch, isFetching } = useDevices();
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [logDevice, setLogDevice] = useState<string | null>(null);
  const [editDevice, setEditDevice] = useState<string | null>(null);
  const toast = useToast();
  const navigate = useNavigate();

  const cmd = useDeviceCommand();
  const batch = useBatchCommand();

  const left = useRefreshCountdown(5, () => {
    void refetch();
  });

  const devices = useMemo<DeviceSummary[]>(() => {
    const list = data?.devices ?? [];
    return [...list].sort((a, b) => (b.lastSeen ?? 0) - (a.lastSeen ?? 0));
  }, [data]);

  const stats = useMemo(() => {
    const now = Date.now();
    let online = 0;
    let running = 0;
    let cycles = 0;
    for (const d of devices) {
      if (ageSeconds(d.lastSeen, now) < ONLINE_SEC) online += 1;
      if (isRunning(d.engine)) running += 1;
      cycles += d.engine?.cycleCount ?? 0;
    }
    return { total: devices.length, online, running, cycles };
  }, [devices]);

  const visibleSelected = useMemo(
    () => devices.filter((d) => selected.has(d.id)).map((d) => d.id),
    [devices, selected],
  );

  function onCommand(id: string, action: 'start' | 'stop') {
    cmd.mutate(
      { id, action },
      {
        onSuccess: () =>
          toast.push(`已下发「${action === 'start' ? '启动' : '停止'}」，设备下次上报（≤周期）后生效`, 'ok'),
        onError: (e) => toast.push(e instanceof ApiError ? e.message : '下发失败', 'error'),
      },
    );
  }

  function onBatch(action: 'start' | 'stop') {
    if (visibleSelected.length === 0) return;
    batch.mutate(
      { ids: visibleSelected, action },
      {
        onSuccess: (r) => {
          toast.push(`批量${action === 'start' ? '启动' : '停止'}已下发 ${r.applied?.length ?? 0} 台`, 'ok');
          setSelected(new Set());
        },
        onError: (e) => toast.push(e instanceof ApiError ? e.message : '批量下发失败', 'error'),
      },
    );
  }

  const allSelected = devices.length > 0 && visibleSelected.length === devices.length;

  return (
    <div>
      <header className="mb-4 flex flex-wrap items-center gap-[10px]">
        <div>
          <h1>阿尔泰挂机 · 监控台</h1>
          <div className="text-[11px] text-muted-2">
            {data?.defaultConfigRevision ? (
              <>
                默认配置 <code>{data.defaultConfigRevision}</code> · 状态每 5 秒自检一次（SSE 事件即时刷新）
              </>
            ) : (
              '状态每 5 秒自检一次（SSE 事件即时刷新）'
            )}
          </div>
        </div>

        <div className="ml-auto flex flex-wrap gap-[10px]">
          <StatBox value={stats.total} label="设备总数" />
          <StatBox value={stats.online} label="在线 <3min" color="#7fd18b" />
          <StatBox value={stats.running} label="挂机中" color="#7fd18b" />
          <StatBox value={stats.cycles} label="累计轮次" />
          <StatBox
            value={`${Math.max(0, left).toFixed(1)}s`}
            label="距刷新"
            title="每 5 秒重取设备列表；SSE 连通时事件会额外即时刷新"
          />
        </div>
      </header>

      <div className="mb-2 flex flex-wrap items-center gap-2">
        <SectionTitle>设备</SectionTitle>
        <div className="mb-[10px] flex flex-wrap items-center gap-2">
          <Button
            icon="check"
            disabled={devices.length === 0}
            onClick={() => setSelected(allSelected ? new Set() : new Set(devices.map((d) => d.id)))}
          >
            {allSelected ? '取消全选' : '全选'}
          </Button>
          <Button variant="primary" icon="play" disabled={!visibleSelected.length || batch.isPending} onClick={() => onBatch('start')}>
            批量启动{visibleSelected.length ? ` (${visibleSelected.length})` : ''}
          </Button>
          <Button variant="danger" icon="stop" disabled={!visibleSelected.length || batch.isPending} onClick={() => onBatch('stop')}>
            批量停止
          </Button>
          <Button icon="refresh" disabled={isFetching} onClick={() => void refetch()}>
            {isFetching ? '刷新中…' : '立即刷新'}
          </Button>
          {visibleSelected.length ? (
            <span className="text-[11px] text-muted-2">已选 {visibleSelected.length} 台</span>
          ) : null}
        </div>
      </div>

      {isPending ? (
        <div className="empty">加载中…</div>
      ) : isError ? (
        <div className="empty">
          加载失败：{error instanceof ApiError ? error.message : '网络错误'}
          <div className="mt-3">
            <Button icon="refresh" onClick={() => void refetch()}>
              重试
            </Button>
          </div>
        </div>
      ) : devices.length === 0 ? (
        <div className="empty">
          <Icon name="phone" size={22} className="mx-auto mb-2 block text-muted-2" />
          {EMPTY_DEVICES_HINT}
          <div className="mt-2 text-[11px]">
            设备 Token 在「设置」页可复制；服务器地址填本机地址（例如 http://10.0.0.2:8788）。
          </div>
        </div>
      ) : (
        <div className="grid gap-[13px]" style={{ gridTemplateColumns: 'repeat(auto-fill,minmax(320px,1fr))' }}>
          {devices.map((d) => (
            <DeviceCard
              key={d.id}
              device={d}
              selected={selected.has(d.id)}
              busy={cmd.isPending}
              onSelect={(id, next) =>
                setSelected((prev) => {
                  const s = new Set(prev);
                  if (next) s.add(id);
                  else s.delete(id);
                  return s;
                })
              }
              onCommand={onCommand}
              onLogs={setLogDevice}
              onEdit={setEditDevice}
            />
          ))}
        </div>
      )}

      <div className="mt-4 flex flex-wrap items-center gap-3 text-[10.5px] text-muted-2">
        <span>已上报设备 {devices.length} 台 · 数据来源 GET /api/v1/panel/devices</span>
        <Button icon="list" onClick={() => navigate('/audit')}>
          查看审计
        </Button>
      </div>

      <LogDrawer deviceId={logDevice} onClose={() => setLogDevice(null)} />

      <DeviceEditModal
        device={devices.find((d) => d.id === editDevice) ?? null}
        defaultRevision={data?.defaultConfigRevision}
        onClose={() => setEditDevice(null)}
      />
    </div>
  );
}

/** 卡片「日志」按钮打开的抽屉（走 /devices/{id}/logs）。 */
function LogDrawer({ deviceId, onClose }: { deviceId: string | null; onClose: () => void }) {
  const [q, setQ] = useState('');
  const navigate = useNavigate();
  const id = deviceId ?? '';
  const { data, isPending, isError, error } = useLogs(id, q, 200);

  return (
    <Drawer
      open={Boolean(deviceId)}
      title={`${deviceId ?? ''} · 日志`}
      onClose={onClose}
      footer={
        <div className="flex items-center gap-2">
          <input
            className="w-full rounded-input border border-line-2 bg-inset px-[9px] py-1.5 text-xs text-input-fg outline-none focus:border-brand"
            placeholder="关键词检索（服务端 q 参数）"
            value={q}
            onChange={(e) => setQ(e.target.value)}
          />
          <Button
            icon="gear"
            onClick={() => {
              if (deviceId) {
                onClose();
                navigate(`/devices/${encodeURIComponent(deviceId)}`);
              }
            }}
          >
            详情
          </Button>
        </div>
      }
    >
      {isPending ? (
        <div className="p-4 text-[11.5px] text-muted-2">加载中…</div>
      ) : isError ? (
        <div className="p-4 text-[11.5px] text-error">
          {error instanceof ApiError ? error.message : '加载失败'}
        </div>
      ) : (data?.lines?.length ?? 0) === 0 ? (
        <div className="p-4 text-[11.5px] text-muted-2">（没有匹配的日志）</div>
      ) : (
        <pre className="logs">
          {data?.lines.map((l, i) => (
            <div key={`${l.ts}-${i}`}>
              <span className="text-muted-2">{new Date(l.ts).toLocaleTimeString('zh-CN', { hour12: false })} </span>
              {l.line}
            </div>
          ))}
        </pre>
      )}
    </Drawer>
  );
}
