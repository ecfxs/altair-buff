import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { ApiError } from '@/api/client';
import { useConfig, useDevices, usePutConfig, useRevisions, useRollback } from '@/api/queries';
import { DEFAULT_SCOPE, type Buff, type DeviceConfig } from '@/api/types';
import { CYCLE_HINT } from '@/lib/status';
import { humanDuration, stamp } from '@/lib/time';
import {
  Button,
  Card,
  Check,
  Field,
  FormGrid,
  Icon,
  SectionTitle,
  Select,
  TextInput,
  useToast,
} from '@/ui';

/** BUFF 行兜底：契约固定 3 条，编号 1..3。 */
function normalizeBuffs(buffs: Buff[] | undefined | null): Buff[] {
  const out: Buff[] = [];
  for (let i = 0; i < 3; i += 1) {
    const src = buffs?.[i];
    out.push(
      src
        ? {
            idx: i + 1,
            enabled: Boolean(src.enabled),
            key: src.key >= 1 && src.key <= 4 ? src.key : i + 1,
            durationSec: pickDurationSec(src),
          }
        : { idx: i + 1, enabled: false, key: i + 1, durationSec: DEFAULT_DUR_SEC },
    );
  }
  return out;
}

/** BUFF 时长默认值（秒）——与设备端 Engine.DEFAULT_DUR_SEC 保持一致。 */
const DEFAULT_DUR_SEC = 280;

/**
 * 取 BUFF 时长（秒）。老配置里只有 durationMin（分钟）→ ×60 迁移。
 * 单位串台比报错更难查：同一个字段名换单位会把"5 分钟"读成"5 秒"。
 */
function pickDurationSec(b: { durationSec?: number; durationMin?: number }): number {
  const sec = b.durationSec ?? 0;
  if (sec >= 10 && sec <= 86400) return sec;
  const min = b.durationMin ?? 0;
  if (min >= 1 && min <= 1440) return min * 60;
  return DEFAULT_DUR_SEC;
}

const EMPTY_CONFIG: DeviceConfig = {
  targetPkg: '',
  pressMs: 90,
  skillPoints: [],
  inputMethod: 'keyevent',
  buff: normalizeBuffs(null),
  notes: '',
};

/** /configs —— 配置下发 + 历史版本回滚。 */
export function ConfigsPage() {
  const [params, setParams] = useSearchParams();
  const scope = params.get('scope') ?? DEFAULT_SCOPE;

  const devices = useDevices();
  const cfg = useConfig(scope);
  const revs = useRevisions(scope);
  const put = usePutConfig(scope);
  const rollback = useRollback(scope);
  const toast = useToast();

  const [form, setForm] = useState<DeviceConfig>(EMPTY_CONFIG);
  const [skillText, setSkillText] = useState('');
  const [dirty, setDirty] = useState(false);

  // 载入服务端配置（切 scope / 保存后重新同步）
  useEffect(() => {
    const c = cfg.data?.config;
    if (!c) return;
    setForm({
      targetPkg: c.targetPkg ?? '',
      pressMs: c.pressMs && c.pressMs > 0 ? c.pressMs : 90,
      skillPoints: c.skillPoints ?? [],
      inputMethod: c.inputMethod === 'touch' ? 'touch' : 'keyevent',
      buff: normalizeBuffs(c.buff),
      notes: c.notes ?? '',
    });
    setSkillText((c.skillPoints ?? []).map((p) => `${p[0] ?? 0},${p[1] ?? 0}`).join('\n'));
    setDirty(false);
  }, [cfg.data]);

  const options = useMemo(() => {
    const list = devices.data?.devices ?? [];
    return [...list].sort((a, b) => (b.lastSeen ?? 0) - (a.lastSeen ?? 0));
  }, [devices.data]);

  const cycle = useMemo(() => {
    const secs = form.buff.filter((b) => b.enabled && (b.durationSec ?? 0) > 0).map((b) => b.durationSec ?? 0);
    if (!secs.length) return null;
    return Math.round(Math.min(...secs) * 1000 * 0.94);
  }, [form.buff]);

  function patch<K extends keyof DeviceConfig>(key: K, value: DeviceConfig[K]) {
    setForm((f) => ({ ...f, [key]: value }));
    setDirty(true);
  }

  function patchBuff(idx: number, next: Partial<Buff>) {
    setForm((f) => ({
      ...f,
      buff: f.buff.map((b, i) => (i === idx ? { ...b, ...next, idx: i + 1 } : b)),
    }));
    setDirty(true);
  }

  function submit() {
    // 契约：revision 由服务端生成，PUT 时忽略/不得指定
    const skillPoints = skillText
      .split('\n')
      .map((line) => line.trim())
      .filter(Boolean)
      .map((line) => line.split(/[,，\s]+/).map((n) => Number.parseFloat(n)))
      .filter((pair) => pair.length >= 2 && pair.every((n) => Number.isFinite(n)))
      .map((pair) => [pair[0] as number, pair[1] as number]);

    const payload: DeviceConfig = {
      targetPkg: form.targetPkg.trim(),
      pressMs: form.pressMs,
      skillPoints,
      inputMethod: form.inputMethod === 'touch' ? 'touch' : 'keyevent',
      buff: normalizeBuffs(form.buff),
      notes: form.notes.trim(),
    };

    put.mutate(payload, {
      onSuccess: (r) => {
        toast.push(`已下发 revision=${r.revision}，设备下次轮询后生效`, 'ok');
        setDirty(false);
      },
      onError: (e) => toast.push(e instanceof ApiError ? e.message : '下发失败', 'error'),
    });
  }

  return (
    <div>
      <div className="mb-2">
        <h1>参数下发</h1>
        <div className="text-[11px] text-muted-2">
          scope = <code>default</code> 对未单独配置的设备生效；选具体设备则覆盖单台。
          写入时 revision 由服务端生成，旧版本自动归档。
        </div>
      </div>

      <FormGrid>
        <Field label="目标设备 / scope">
          <Select
            value={scope}
            onChange={(v) => {
              if (dirty) toast.push('已切换 scope，未保存的修改被丢弃', 'info');
              setParams(v === DEFAULT_SCOPE ? {} : { scope: v });
            }}
          >
            <option value={DEFAULT_SCOPE}>（默认配置，对所有未单独配置的设备生效）</option>
            {options.map((d) => (
              <option key={d.id} value={d.id}>
                {d.id}
                {d.model ? ` · ${d.model}` : ''}
              </option>
            ))}
          </Select>
        </Field>

        <Field label="目标游戏包名（前台门禁用）">
          <TextInput
            placeholder="com.nexon.mod"
            value={form.targetPkg ?? ''}
            onChange={(e) => patch('targetPkg', e.target.value)}
          />
        </Field>

        <Field label="技能键输入方式">
          <Select value={form.inputMethod ?? 'keyevent'} onChange={(v) => patch('inputMethod', v as 'keyevent' | 'touch')}>
            <option value="keyevent">键盘按键 input keyevent</option>
            <option value="touch">触摸点击 input swipe（需先采点）</option>
          </Select>
        </Field>

        <Field label="按下时长 pressMs" hint="服务端会把 <=0 或 >5000 兜回 90">
          <TextInput
            type="number"
            min={1}
            max={5000}
            value={form.pressMs ?? 90}
            onChange={(e) => patch('pressMs', Number.parseInt(e.target.value, 10) || 90)}
          />
        </Field>

        <Field label="备注">
          <TextInput
            placeholder="例如：方案A / 主教"
            value={form.notes ?? ''}
            onChange={(e) => patch('notes', e.target.value)}
          />
        </Field>

        <Field label="技能键坐标 skillPoints（每行 x,y，归一化）">
          <textarea
            className="h-[92px] w-full rounded-ctl border border-line-2 bg-inset p-2.5 font-mono text-[11.5px] text-input-fg outline-none focus:border-brand"
            placeholder={'0.5,0.72\n0.62,0.8'}
            value={skillText}
            onChange={(e) => {
              setSkillText(e.target.value);
              setDirty(true);
            }}
          />
        </Field>

        <div className="col-span-full">
          <div className="mb-[5px] text-[11px] text-muted-2">{CYCLE_HINT}</div>
          <div className="grid gap-[3px]">
            {form.buff.map((b, i) => (
              <div key={b.idx} className="flex flex-wrap items-center gap-2 text-[11.5px] text-dim">
                <Check checked={b.enabled} onChange={(v) => patchBuff(i, { enabled: v })} />
                <span className="w-[52px]">BUFF{i + 1}</span>
                <span className="inline-flex items-center gap-1.5">
                  数字键
                  <input
                    type="number"
                    min={1}
                    max={4}
                    className="w-[52px] rounded-input border border-line-2 bg-inset px-[9px] py-1.5 text-xs text-input-fg outline-none focus:border-brand"
                    value={b.key}
                    onChange={(e) => patchBuff(i, { key: Number.parseInt(e.target.value, 10) || i + 1 })}
                  />
                </span>
                <span className="inline-flex items-center gap-1.5">
                  持续
                  <input
                    type="number"
                    min={10}
                    max={86400}
                    className="w-[78px] rounded-input border border-line-2 bg-inset px-[9px] py-1.5 text-xs text-input-fg outline-none focus:border-brand"
                    value={b.durationSec ?? DEFAULT_DUR_SEC}
                    onChange={(e) =>
                      patchBuff(i, { durationSec: Number.parseInt(e.target.value, 10) || DEFAULT_DUR_SEC })
                    }
                  />
                  秒
                </span>
              </div>
            ))}
          </div>
          <div className="mt-2 text-[11px] text-muted-2">
            预计循环周期：
            <b className="num text-fg">{cycle ? humanDuration(cycle) : '—'}</b>
            <span className="ml-2">（取启用中最短时长 × 0.94）</span>
          </div>

          {/* 回城模式：补完 BUFF 自动进自由市场等待。与 BUFF 放一起，
              因为它改变的就是"这一轮补完之后干什么"。 */}
          <div className="mt-3 rounded-ctl border border-line bg-inset px-3 py-2">
            <Check
              checked={form.autoFreeMarket ?? false}
              onChange={(v) => {
                setForm((f) => ({ ...f, autoFreeMarket: v }));
                setDirty(true);
              }}
              label={
                <span className="inline-flex items-center gap-2">
                  <span className="text-[12px] text-fg">加完 BUFF 自动进自由市场</span>
                  <span className="text-[10.5px] text-muted-2">
                    进市场等待 → 走到出口待命；下轮到点按方向键上出市场再补
                  </span>
                </span>
              }
            />
          </div>

          {/* 原地走动：补 BUFF 前左右各走一次（设备界面也能改，这里下发会覆盖设备本地值） */}
          <div className="mt-3 rounded-ctl border border-line bg-inset px-3 py-2">
            <div className="mb-1 text-[12px] text-fg">原地走动（补 BUFF 前左右各走一次）</div>
            <div className="flex flex-wrap items-center gap-3 text-[11.5px] text-muted">
              <span className="inline-flex items-center gap-1.5">
                每腿时长
                <input
                  type="number"
                  min={100}
                  max={10000}
                  className="w-[74px] rounded-input border border-line-2 bg-inset px-[9px] py-1.5 text-xs text-input-fg outline-none focus:border-brand"
                  value={form.strollHoldMs ?? 600}
                  onChange={(e) => {
                    setForm((f) => ({ ...f, strollHoldMs: Number.parseInt(e.target.value, 10) || 600 }));
                    setDirty(true);
                  }}
                />
                毫秒
              </span>
              <span className="inline-flex items-center gap-1.5">
                抖动 ±
                <input
                  type="number"
                  min={0}
                  max={1000}
                  className="w-[64px] rounded-input border border-line-2 bg-inset px-[9px] py-1.5 text-xs text-input-fg outline-none focus:border-brand"
                  value={form.strollJitterMs ?? 30}
                  onChange={(e) => {
                    const v = Number.parseInt(e.target.value, 10);
                    setForm((f) => ({ ...f, strollJitterMs: Number.isFinite(v) ? v : 30 }));
                    setDirty(true);
                  }}
                />
                毫秒
              </span>
              <span className="inline-flex items-center gap-1.5">
                连发间隔
                <input
                  type="number"
                  min={30}
                  max={2000}
                  className="w-[64px] rounded-input border border-line-2 bg-inset px-[9px] py-1.5 text-xs text-input-fg outline-none focus:border-brand"
                  value={form.strollPressGapMs ?? 100}
                  onChange={(e) => {
                    setForm((f) => ({ ...f, strollPressGapMs: Number.parseInt(e.target.value, 10) || 100 }));
                    setDirty(true);
                  }}
                />
                毫秒
              </span>
              <span className="text-[10.5px] text-muted-2">
                走法跟随触发方式：触摸→摇杆，键盘→方向键
              </span>
            </div>
          </div>
        </div>

        <div className="col-span-full flex flex-wrap items-center gap-[9px]">
          <Button variant="primary" icon="check" disabled={put.isPending} onClick={submit}>
            {put.isPending ? '下发中…' : '下发配置'}
          </Button>
          <Button icon="refresh" disabled={cfg.isFetching} onClick={() => void cfg.refetch()}>
            重新载入
          </Button>
          {dirty ? <span className="text-[11px] text-warn">有未下发的修改</span> : null}
          {cfg.data ? (
            <span className="text-[11px] text-muted-2">
              当前 revision <code>{cfg.data.revision}</code>
              {cfg.data.updatedAt ? ` · ${stamp(cfg.data.updatedAt)}` : ''}
              {cfg.data.updatedBy ? ` · ${cfg.data.updatedBy}` : ''}
            </span>
          ) : null}
        </div>
      </FormGrid>

      <SectionTitle>历史版本（{scope}）</SectionTitle>
      <Card className="p-0">
        {revs.isPending ? (
          <div className="p-4 text-[11.5px] text-muted-2">加载中…</div>
        ) : revs.isError ? (
          <div className="p-4 text-[11.5px] text-error">
            {revs.error instanceof ApiError ? revs.error.message : '加载失败'}
          </div>
        ) : (revs.data?.revisions?.length ?? 0) === 0 ? (
          <div className="p-4 text-[11.5px] text-muted-2">还没有历史版本。下发一次配置后就会出现。</div>
        ) : (
          <table className="tbl">
            <thead>
              <tr>
                <th>revision</th>
                <th>时间</th>
                <th>操作人</th>
                <th>摘要</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {revs.data?.revisions.map((r) => (
                <tr key={r.revision}>
                  <td className="num">
                    <code>{r.revision}</code>
                    {r.revision === cfg.data?.revision ? (
                      <span className="ml-2 text-[10.5px] text-ok">（当前）</span>
                    ) : null}
                  </td>
                  <td className="whitespace-nowrap">{stamp(r.createdAt)}</td>
                  <td>{r.createdBy || '—'}</td>
                  <td className="max-w-[420px] truncate">
                    {r.config?.targetPkg || '—'} · {r.config?.inputMethod || '—'} · BUFF{' '}
                    {(r.config?.buff ?? []).filter((b) => b.enabled).map((b) => `键${b.key}/${b.durationSec}秒`).join(' ') ||
                      '全关'}
                    {r.config?.notes ? ` · ${r.config.notes}` : ''}
                  </td>
                  <td className="text-right">
                    <Button
                      icon="refresh"
                      disabled={rollback.isPending}
                      onClick={() =>
                        rollback.mutate(r.revision, {
                          onSuccess: (env) => toast.push(`已回滚，新 revision=${env.revision}`, 'ok'),
                          onError: (e) => toast.push(e instanceof ApiError ? e.message : '回滚失败', 'error'),
                        })
                      }
                    >
                      回滚
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      <div className="mt-3 flex items-center gap-2 text-[10.5px] text-muted-2">
        <Icon name="warn" />
        回滚会用历史版本覆盖当前配置并生成**新的** revision（不删除历史）。
      </div>
    </div>
  );
}
