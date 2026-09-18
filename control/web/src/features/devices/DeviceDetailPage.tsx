import { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ApiError, api } from '@/api/client';
import { useDevice, useDeviceCommand, useHistory, useLogs, useScreenshots, useSetInterval } from '@/api/queries';
import { inputMethodLabel, isRunning, stateBadge } from '@/lib/status';
import {
  DOT_COLOR,
  ago,
  ageSeconds,
  countdown,
  freshness,
  humanDuration,
  stamp,
  toMs,
} from '@/lib/time';
import { useTicker } from '@/lib/useTicker';
import { DeviceEditModal } from './DeviceEditModal';
import {
  Badge,
  Button,
  Card,
  Chip,
  Icon,
  KeyRow,
  Modal,
  NumberInput,
  ProgressBar,
  Section,
  SectionTitle,
  Sparkline,
  StatusDot,
  useToast,
} from '@/ui';

/** /devices/:id —— 完整状态 + 历史 + 曲线 + 日志 + 截图 + 周期设置。 */
export function DeviceDetailPage() {
  const { id = '' } = useParams<{ id: string }>();
  const now = useTicker(500);
  const toast = useToast();

  const detail = useDevice(id, 50);
  const history = useHistory(id, 300);
  const [logQ, setLogQ] = useState('');
  const logs = useLogs(id, logQ, 300);
  const shots = useScreenshots(id, 24);
  const cmd = useDeviceCommand();
  const setIntervalMut = useSetInterval(id);

  const [intervalMs, setIntervalMs] = useState<number>(0);
  const [shot, setShot] = useState<string | null>(null);
  const [editing, setEditing] = useState(false);

  const d = detail.data?.device;
  useEffect(() => {
    if (d?.reportIntervalMs != null) setIntervalMs(d.reportIntervalMs);
  }, [d?.reportIntervalMs]);

  const points = history.data?.points ?? [];
  const series = useMemo(
    () => [
      { label: '轮次', color: '#7fd18b', values: points.map((p) => p.cycleCount ?? null) },
      { label: '电量 %', color: '#2563eb', values: points.map((p) => p.batteryPct ?? null), unit: '%' },
      { label: '温度 ℃', color: '#ffc53d', values: points.map((p) => p.thermalC ?? null), unit: '℃' },
      { label: '延迟 ms', color: '#ff8b8b', values: points.map((p) => p.netRttMs ?? null), unit: 'ms' },
    ],
    [points],
  );

  if (detail.isPending) return <div className="empty">加载中…</div>;
  if (detail.isError || !d) {
    return (
      <div className="empty">
        {detail.error instanceof ApiError ? detail.error.message : '设备不存在或加载失败'}
        <div className="mt-3">
          <Link to="/">
            <Button icon="home">返回总览</Button>
          </Link>
        </div>
      </div>
    );
  }

  const engine = d.engine;
  const age = ageSeconds(d.lastSeen, now);
  const fresh = freshness(age);
  const badge = stateBadge(engine?.state);
  const cd = countdown(engine, d.reportTs, now);
  const hb = d.heartbeat;
  const skew = d.reportTs ? now - d.reportTs : 0;

  return (
    <div>
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <Link to="/" className="no-underline">
          <Button icon="home">总览</Button>
        </Link>
        <Icon name="phone" size={17} className="ml-1 text-ok" />
        <span className="font-mono text-[15px] font-semibold text-ok">{d.id}</span>
        <Badge tone={badge.tone}>{badge.text}</Badge>
        <span className="flex items-center gap-1.5 text-[11px] text-muted">
          <StatusDot color={DOT_COLOR[fresh]} />
          {ago(d.lastSeen, now)}
        </span>
        <span className="ml-auto flex flex-wrap gap-2">
          <Button
            variant="primary"
            icon="play"
            disabled={cmd.isPending || isRunning(engine)}
            onClick={() =>
              cmd.mutate(
                { id, action: 'start' },
                {
                  onSuccess: () => toast.push('已下发启动', 'ok'),
                  onError: (e) => toast.push(e instanceof ApiError ? e.message : '失败', 'error'),
                },
              )
            }
          >
            启动
          </Button>
          <Button
            variant="danger"
            icon="stop"
            disabled={cmd.isPending || !isRunning(engine)}
            onClick={() =>
              cmd.mutate(
                { id, action: 'stop' },
                {
                  onSuccess: () => toast.push('已下发停止', 'ok'),
                  onError: (e) => toast.push(e instanceof ApiError ? e.message : '失败', 'error'),
                },
              )
            }
          >
            停止
          </Button>
          <Link to={`/configs?scope=${encodeURIComponent(id)}`} className="no-underline">
            <Button icon="gear">配置</Button>
          </Link>
        </span>
      </div>

      <div className="grid gap-[13px] lg:grid-cols-[1.1fr_1fr]">
        <Card>
          <SectionTitle>状态</SectionTitle>
          <KeyRow k="备注">
            <span className="inline-flex items-center gap-2">
              <span className={d.notes ? 'text-fg' : 'text-muted-2'}>{d.notes || '—'}</span>
              <Button icon="gear" onClick={() => setEditing(true)}>
                编辑
              </Button>
            </span>
          </KeyRow>
          <KeyRow k="机型">
            {d.model || '未知机型'} · Android {d.android || '?'} · v{d.versionName || '?'}
          </KeyRow>
          <KeyRow k="引擎">
            <Badge tone={badge.tone}>{badge.text}</Badge>
          </KeyRow>
          <ProgressBar pct={cd.pct} />
          <KeyRow k="距下次补 BUFF">
            <span className="num text-[15px]">{cd.txt}</span>
          </KeyRow>
          <KeyRow k="循环周期">{humanDuration(engine?.cyclePeriodMs)}</KeyRow>
          <KeyRow k="已完成">{engine?.cycleCount ?? 0} 轮</KeyRow>
          <KeyRow k="输入方式">{inputMethodLabel(engine?.inputMethod)}</KeyRow>
          <KeyRow k="意图状态">
            desired {d.desired?.running ? '启动' : '停止'} · rev {d.desired?.rev ?? '—'}
            {d.desired?.by ? ` · ${d.desired.by}` : ''}
          </KeyRow>
          <KeyRow k="上次结果">{engine?.lastResult || '—'}</KeyRow>
          {engine?.lastError ? (
            <KeyRow k="最近错误" tone="error">
              {engine.lastError}
            </KeyRow>
          ) : null}
        </Card>

        <Card>
          <SectionTitle>上报细节</SectionTitle>
          <KeyRow k="首次出现">{stamp(d.firstSeen)}</KeyRow>
          <KeyRow k="最后在线">{stamp(d.lastSeen)}</KeyRow>
          <KeyRow k="设备时钟偏差">
            <span className="num" title="skew = 浏览器 now - 设备 reportTs；倒计时按此校正">
              {d.reportTs ? `${skew > 0 ? '+' : ''}${(skew / 1000).toFixed(1)}s` : '—'}
            </span>
          </KeyRow>
          <KeyRow k="前台应用">{d.foreground || '—'}</KeyRow>
          <KeyRow k="门禁">{d.armed ? '已启用' : '已禁用'}</KeyRow>
          <KeyRow k="心跳">
            <span className="num">
              {hb?.batteryPct != null ? `${hb.batteryPct}%${hb.charging ? '·充电' : ''}` : '—'}
              {hb?.thermalC != null ? ` · ${hb.thermalC}℃` : ''}
              {hb?.netRttMs != null ? ` · ${hb.netRttMs}ms` : ''}
              {hb?.memFreeMb != null ? ` · 空闲${hb.memFreeMb}MB` : ''}
            </span>
          </KeyRow>
          <KeyRow k="协议版本">v{d.protocolVersion ?? '?'}</KeyRow>
          <KeyRow k="配置">
            {d.configInSync === false ? (
              <>
                <Chip tone="warn">配置未生效</Chip>{' '}
                <span className="ml-1">
                  applied {d.appliedRevision || '—'} / current {d.configRevision || '—'}
                </span>
              </>
            ) : (
              <Chip active>已同步 {d.appliedRevision || d.configRevision || '—'}</Chip>
            )}
          </KeyRow>
          <KeyRow k="24h 轮次 / 在线率">
            {d.stats?.cycles24h ?? 0} 轮 · {((d.stats?.onlineRate24h ?? 0) * 100).toFixed(1)}%
          </KeyRow>

          <Section>
            <div className="mb-2 text-[11px] text-muted-3">上报周期（0 = 使用全局默认；服务端最小 15s）</div>
            <div className="flex flex-wrap items-center gap-2">
              <NumberInput value={intervalMs} onChange={setIntervalMs} min={0} max={3_600_000} width={110} suffix="ms" />
              <Button
                icon="check"
                disabled={setIntervalMut.isPending}
                onClick={() =>
                  setIntervalMut.mutate(intervalMs, {
                    onSuccess: () => toast.push('上报周期已更新', 'ok'),
                    onError: (e) => toast.push(e instanceof ApiError ? e.message : '失败', 'error'),
                  })
                }
              >
                PUT 周期
              </Button>
              <span className="text-[10.5px] text-muted-2">
                当前生效 {humanDuration(d.reportIntervalMs)}
              </span>
            </div>
          </Section>
        </Card>
      </div>

      <SectionTitle>上报历史曲线（/devices/{id}/history）</SectionTitle>
      <Card>
        {history.isPending ? (
          <div className="text-[11.5px] text-muted-2">加载中…</div>
        ) : history.isError ? (
          <div className="text-[11.5px] text-error">曲线加载失败</div>
        ) : (
          <Sparkline series={series} />
        )}
      </Card>

      <div className="grid gap-[13px] lg:grid-cols-2">
        <div>
          <SectionTitle>最近上报（GET /devices/{id}?reports=50）</SectionTitle>
          <Card className="max-h-[420px] overflow-auto p-0">
            <table className="tbl">
              <thead>
                <tr>
                  <th>设备时间</th>
                  <th>状态</th>
                  <th>轮次</th>
                  <th>电量</th>
                  <th>温度</th>
                  <th>延迟</th>
                  <th>前台</th>
                </tr>
              </thead>
              <tbody>
                {(detail.data?.reports ?? []).map((r, i) => (
                  <tr key={`${r.ts}-${i}`}>
                    <td className="num whitespace-nowrap">{stamp(r.ts)}</td>
                    <td>{r.state || (r.running ? 'WAITING' : 'IDLE')}</td>
                    <td className="num">{r.cycleCount ?? '—'}</td>
                    <td className="num">{r.batteryPct ?? '—'}</td>
                    <td className="num">{r.thermalC ?? '—'}</td>
                    <td className="num">{r.netRttMs ?? '—'}</td>
                    <td className="max-w-[180px] truncate">{r.foreground || '—'}</td>
                  </tr>
                ))}
                {(detail.data?.reports ?? []).length === 0 ? (
                  <tr>
                    <td colSpan={7} className="p-4 text-center text-muted-2">
                      暂无上报记录
                    </td>
                  </tr>
                ) : null}
              </tbody>
            </table>
          </Card>
        </div>

        <div>
          <SectionTitle>日志检索（/devices/{id}/logs）</SectionTitle>
          <Card className="p-0">
            <div className="flex items-center gap-2 border-b border-line-3 p-3">
              <Icon name="search" className="text-muted-2" />
              <input
                className="w-full rounded-input border border-line-2 bg-inset px-[9px] py-1.5 text-xs text-input-fg outline-none focus:border-brand"
                placeholder="关键词（服务端 q 参数）"
                value={logQ}
                onChange={(e) => setLogQ(e.target.value)}
              />
              <span className="whitespace-nowrap text-[10.5px] text-muted-2">
                {logs.data?.lines?.length ?? 0} 行
              </span>
            </div>
            <div className="max-h-[360px] overflow-auto">
              {logs.isPending ? (
                <div className="p-4 text-[11.5px] text-muted-2">加载中…</div>
              ) : logs.isError ? (
                <div className="p-4 text-[11.5px] text-error">日志加载失败</div>
              ) : (logs.data?.lines?.length ?? 0) === 0 ? (
                <div className="p-4 text-[11.5px] text-muted-2">没有匹配的日志</div>
              ) : (
                <pre className="logs">
                  {logs.data?.lines.map((l, i) => (
                    <div key={`${l.ts}-${i}`}>
                      <span className="text-muted-2">
                        {new Date(toMs(l.ts) ?? l.ts).toLocaleTimeString('zh-CN', { hour12: false })}{' '}
                      </span>
                      {l.line}
                    </div>
                  ))}
                </pre>
              )}
            </div>
          </Card>
        </div>
      </div>

      <SectionTitle>截图画廊（/screenshots?deviceId=）</SectionTitle>
      <Card>
        {shots.isPending ? (
          <div className="text-[11.5px] text-muted-2">加载中…</div>
        ) : (shots.data?.screenshots?.length ?? 0) === 0 ? (
          <div className="text-[11.5px] text-muted-2">暂无截图。设备上传截图后会出现在这里。</div>
        ) : (
          <div className="flex flex-wrap gap-3">
            {shots.data?.screenshots.map((s) => (
              <button
                key={s.id}
                type="button"
                className="w-[140px] cursor-pointer overflow-hidden rounded-input border border-line-2 bg-inset p-0 text-left"
                onClick={() => setShot(s.id)}
              >
                <img
                  src={api.screenshotUrl(s.id)}
                  alt={s.label ?? s.id}
                  className="block h-[220px] w-full object-cover"
                  loading="lazy"
                />
                <span className="block px-2 py-1 text-[10.5px] text-muted-2">{stamp(s.ts)}</span>
              </button>
            ))}
          </div>
        )}
      </Card>

      <Modal open={Boolean(shot)} title={`截图 ${shot ?? ''}`} icon="image" onClose={() => setShot(null)} wide>
        {shot ? (
          <div className="p-4">
            <img src={api.screenshotUrl(shot)} alt={shot} className="mx-auto max-h-[70vh] max-w-full" />
            <div className="mt-3 flex justify-center gap-2">
              <a href={api.screenshotUrl(shot)} target="_blank" rel="noreferrer" className="no-underline">
                <Button icon="image">新窗口打开原图</Button>
              </a>
              <Button
                variant="danger"
                icon="close"
                onClick={async () => {
                  try {
                    await api.deleteScreenshot(shot);
                    toast.push('已删除截图', 'ok');
                    setShot(null);
                    void shots.refetch();
                  } catch (e) {
                    toast.push(e instanceof ApiError ? e.message : '删除失败', 'error');
                  }
                }}
              >
                删除
              </Button>
            </div>
          </div>
        ) : null}
      </Modal>

      <div className="mt-5 flex flex-wrap gap-2 text-[10.5px] text-muted-2">
        <span>契约：GET /devices/{'{id}'} · /history · /logs · PUT /devices/{'{id}'}/interval · /screenshots</span>
      </div>

      <DeviceEditModal device={editing ? d : null} onClose={() => setEditing(false)} />
    </div>
  );
}
