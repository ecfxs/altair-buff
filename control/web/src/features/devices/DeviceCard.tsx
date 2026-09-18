import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import type { DeviceSummary } from '@/api/types';
import { DOT_COLOR, ago, ageSeconds, countdown, freshness } from '@/lib/time';
import { inputMethodLabel, isRunning, stateBadge } from '@/lib/status';
import { Badge, Button, Card, Check, Icon, ProgressBar, StatusDot } from '@/ui';
import { useTicker } from '@/lib/useTicker';

type Props = {
  device: DeviceSummary;
  selected: boolean;
  onSelect: (id: string, next: boolean) => void;
  onCommand: (id: string, action: 'start' | 'stop') => void;
  onLogs: (id: string) => void;
  onEdit: (id: string) => void;
  busy?: boolean;
};

/**
 * 总览里的一张设备卡片（紧凑版）。
 *
 * 设计取舍：卡片只回答「这台机器现在怎么样、我要做点什么」。
 * 机型 / Android / 版本 / 心跳 / 协议版本 / 上次结果 / 上报细节一律移到详情页，
 * 卡片上只保留**随时要看的**和**要立刻动手的**。
 * 倒计时用 reportTs 校正两端时钟偏差（云手机与浏览器时钟不一致时不然会错乱）。
 */
export function DeviceCard({ device: d, selected, onSelect, onCommand, onLogs, onEdit, busy }: Props) {
  const now = useTicker(250);

  const age = ageSeconds(d.lastSeen, now);
  const fresh = freshness(age);
  const engine = d.engine;
  const running = isRunning(engine);
  const cd = useMemo(() => countdown(engine, d.reportTs, now), [engine, d.reportTs, now]);
  const badge = stateBadge(engine?.state);
  const buffs = engine?.buffs ?? [];
  const enabledBuffs = buffs.filter((b) => b.enabled).length;
  const outOfSync = d.configInSync === false;

  return (
    // data-testid 让 E2E 能稳定定位到「这一台设备的卡片」——
    // 靠文本过滤 div 会命中一堆祖先节点，测试会变脆。
    <Card freshness={fresh} className="relative !p-3" data-testid={`device-${d.id}`}>
      {/* 第一行：选择框 + 设备 ID（点 ID 进详情）+ 最后在线 */}
      <div className="flex items-center gap-2">
        <Check
          checked={selected}
          onChange={(v) => onSelect(d.id, v)}
          label={
            <span className="inline-flex items-center gap-1.5">
              <Icon name="phone" size={15} className="text-muted" />
              {/* 导航就该是 <a>：能中键新开、读屏能识别（按钮不算导航语义） */}
              <Link
                to={`/devices/${encodeURIComponent(d.id)}`}
                className="font-mono text-[13.5px] font-semibold text-ok no-underline hover:underline"
                title="打开设备详情"
              >
                {d.id}
              </Link>
            </span>
          }
        />
        <span className="ml-auto flex shrink-0 items-center gap-1.5 text-[11px] text-muted">
          <StatusDot color={DOT_COLOR[fresh]} />
          {ago(d.lastSeen, now)}
        </span>
      </div>

      {/* 备注：有才显示，一行截断 */}
      {d.notes ? (
        <div className="ml-[22px] truncate text-[11px] text-dim" title={d.notes}>
          备注 {d.notes}
        </div>
      ) : null}

      {/* 状态 + 唯一的告警（配置未生效是可立即处理的，必须留在卡片上） */}
      <div className="mt-2 flex flex-wrap items-center gap-1.5">
        <Badge tone={badge.tone}>{badge.text}</Badge>
        {outOfSync ? (
          <span
            className="inline-flex items-center gap-1 rounded-[5px] border border-card-warm bg-chip px-2 py-[2px] text-[10.5px] text-warn"
            title={`设备 appliedRevision=${d.appliedRevision || '空'} 与当前 ${d.configRevision || '空'} 不一致`}
          >
            <Icon name="warn" size={11} />
            配置未生效
          </span>
        ) : null}
        {engine?.lastError ? (
          <span className="truncate text-[10.5px] text-error" title={engine.lastError}>
            {engine.lastError}
          </span>
        ) : null}
      </div>

      {/* 倒计时 + 进度：卡片的信息核心 */}
      <div className="mt-2.5">
        <ProgressBar pct={cd.pct} />
        <div className="flex items-end justify-between">
          <span className="text-[11px] text-muted-2">距下次补 BUFF</span>
          <span className="num text-[19px] leading-tight text-title">{cd.txt}</span>
        </div>
      </div>

      {/* 一行摘要：轮次 · 输入方式 · BUFF 开关概况 */}
      <div className="mt-2 flex flex-wrap items-center gap-x-2.5 gap-y-1 text-[11.5px] text-dim">
        <span>
          <span className="num text-fg">{engine?.cycleCount ?? 0}</span> 轮
        </span>
        <span className="text-line-2">·</span>
        <span>{inputMethodLabel(engine?.inputMethod)}</span>
        {buffs.length ? (
          <>
            <span className="text-line-2">·</span>
            <span
              className="inline-flex items-center gap-1"
              title="自动补 BUFF：实心=已启用"
              data-testid="buff-summary"
            >
              BUFF
              <span className="font-mono tracking-[1px]">
                {buffs.map((b) => (
                  <span key={b.idx} className={b.enabled ? 'text-ok' : 'text-muted-2'}>
                    {b.enabled ? '●' : '○'}
                  </span>
                ))}
              </span>
              <span className="num text-muted-2">
                {enabledBuffs}/{buffs.length}
              </span>
            </span>
          </>
        ) : null}
      </div>

      <div className="mt-1 flex flex-wrap items-center gap-x-2.5 gap-y-1 text-[11px] text-muted">
        <span className="truncate" title={d.foreground || '（未知）'}>
          前台 {d.foreground || '—'}
        </span>
        <span className="text-line-2">·</span>
        <span style={{ color: d.armed ? '#7fd18b' : '#ffc53d' }}>
          门禁 {d.armed ? '已启用' : '已禁用'}
        </span>
      </div>

      <div className="mt-2.5 flex flex-wrap gap-1.5">
        <Button variant="primary" icon="play" disabled={busy || running} onClick={() => onCommand(d.id, 'start')}>
          启动
        </Button>
        <Button variant="danger" icon="stop" disabled={busy || !running} onClick={() => onCommand(d.id, 'stop')}>
          停止
        </Button>
        <Button icon="log" onClick={() => onLogs(d.id)}>
          日志
        </Button>
        <Button icon="gear" onClick={() => onEdit(d.id)} title="改备注 / 自动补 BUFF 开关">
          编辑
        </Button>
      </div>
    </Card>
  );
}
