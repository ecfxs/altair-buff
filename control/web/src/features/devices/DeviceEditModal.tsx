import { useEffect, useState } from 'react';
import type { Buff, DeviceSummary } from '@/api/types';
import { ApiError } from '@/api/client';
import { useConfig, usePutConfig, useUpdateDevice } from '@/api/queries';
import { Button, Check, Field, Modal, TextInput, useToast } from '@/ui';

type Props = {
  device: DeviceSummary | null;
  /** 默认配置的 revision：用来判断这台设备当前是不是「继承默认配置」 */
  defaultRevision?: string;
  onClose: () => void;
};

/**
 * 卡片上的「编辑」弹窗：改备注 + 拨自动补 BUFF 的开关。
 *
 * 两个字段分属两层，保存时是两次请求，这是有意的：
 *   · 备注 → 设备表（面板侧标注，不下发给设备、不动配置 revision）
 *   · BUFF 开关 → 设备级配置（会让设备热重载一次，这是它该付的代价）
 * 把备注塞进配置的话，改一个字都会触发设备重载配置 —— 那是错的。
 */
export function DeviceEditModal({ device, defaultRevision, onClose }: Props) {
  const id = device?.id ?? '';
  const toast = useToast();
  const cfg = useConfig(id);
  const putConfig = usePutConfig(id);
  const updateDevice = useUpdateDevice(id);

  const [notes, setNotes] = useState('');
  const [buff, setBuff] = useState<Buff[]>([]);
  const [autoFreeMarket, setAutoFreeMarket] = useState(false);
  const [loaded, setLoaded] = useState(false);

  // 设备或配置变了就重建本地草稿（弹窗可以连续切换设备）
  useEffect(() => {
    setNotes(device?.notes ?? '');
    setLoaded(false);
  }, [device?.id, device?.notes]);

  useEffect(() => {
    if (cfg.data?.config) {
      setBuff(cfg.data.config.buff ?? []);
      setAutoFreeMarket(cfg.data.config.autoFreeMarket ?? false);
      setLoaded(true);
    }
  }, [cfg.data]);

  const saving = putConfig.isPending || updateDevice.isPending;
  const inherited = Boolean(cfg.data?.revision && cfg.data.revision === defaultRevision);

  function toggleBuff(idx: number, on: boolean) {
    setBuff((prev) => prev.map((b, i) => (i === idx || b.idx === idx ? { ...b, enabled: on } : b)));
  }

  async function save() {
    if (!device) return;
    try {
      // 备注按设备存；BUFF 开关按设备级配置存（服务端会补全缺省字段）
      if (notes.trim() !== (device.notes ?? '')) {
        await updateDevice.mutateAsync({ notes: notes.trim() });
      }
      if (loaded) {
        const original = cfg.data?.config.buff ?? [];
        const buffChanged =
          original.length !== buff.length ||
          buff.some((b, i) => (original[i]?.enabled ?? false) !== b.enabled);
        const fmChanged = (cfg.data?.config.autoFreeMarket ?? false) !== autoFreeMarket;
        if (buffChanged || fmChanged) {
          await putConfig.mutateAsync({ ...(cfg.data?.config ?? {}), buff, autoFreeMarket });
        }
      }
      toast.push('已保存', 'ok');
      onClose();
    } catch (e) {
      toast.push(e instanceof ApiError ? e.message : '保存失败', 'error');
    }
  }

  return (
    <Modal
      open={Boolean(device)}
      title={`${id} · 编辑`}
      icon="gear"
      onClose={onClose}
      footer={
        <div className="flex flex-wrap items-center gap-2.5">
          <Button variant="primary" icon="check" disabled={saving} onClick={() => void save()}>
            {saving ? '保存中…' : '保存'}
          </Button>
          <Button onClick={onClose}>取消</Button>
          <span className="text-[10.5px] text-muted-2">
            备注只存在面板里；BUFF 开关会下发到设备（下次上报生效）
          </span>
        </div>
      }
    >
      <div className="grid gap-3.5 p-4">
        <Field label="备注（只给运维看，不下发设备）" hint="例如：主教号 / 方案A / 待观察">
          <TextInput
            value={notes}
            maxLength={200}
            placeholder="给这台机器起个记得住的名字"
            onChange={(e) => setNotes(e.target.value)}
          />
        </Field>

        <div>
          <div className="mb-1.5 text-[11px] text-muted">自动补 BUFF（开启的技能）</div>
          {!loaded ? (
            <div className="text-[11.5px] text-muted-2">加载配置中…</div>
          ) : (
            <div className="grid gap-2">
              {buff.map((b, i) => (
                // 注意别在外面再套 <label>：Check 自己就是 label，嵌套 label 是无效 HTML，
                // 点一下会触发两次或干脆不触发。
                <div
                  key={b.idx}
                  className="rounded-ctl border border-line bg-inset px-3 py-2"
                  data-testid={`buff-toggle-${b.idx}`}
                >
                  <Check
                    checked={b.enabled}
                    onChange={(v) => toggleBuff(i, v)}
                    label={
                      <span className="inline-flex items-center gap-2">
                        <span className="text-[12px] text-fg">BUFF{b.idx}</span>
                        <span className="num text-[11px] text-muted-2">
                          键 {b.key} · 持续 {b.durationSec ?? (b.durationMin ?? 0) * 60} 秒
                        </span>
                      </span>
                    }
                  />
                </div>
              ))}
              {buff.some((b) => b.enabled) ? (
                <div className="text-[10.5px] text-muted-2">
                  循环周期 = 启用的 BUFF 里最短时长 × 0.94（改键位/时长请到「配置」页或设备详情）
                </div>
              ) : (
                <div className="text-[10.5px] text-warn">一个都没启用：引擎不会自动补 BUFF</div>
              )}
            </div>
          )}
          <div className="mt-3 rounded-ctl border border-line bg-inset px-3 py-2" data-testid="auto-free-market">
            <Check
              checked={autoFreeMarket}
              onChange={setAutoFreeMarket}
              label={
                <span className="inline-flex items-center gap-2">
                  <span className="text-[12px] text-fg">加完 BUFF 自动进自由市场</span>
                  <span className="text-[10.5px] text-muted-2">回城模式：进市场等待 → 走到出口待命</span>
                </span>
              }
            />
            <div className="mt-1.5 text-[10.5px] text-muted-2">
              流程：点菜单 → 等菜单出现 → 点自由市场 → 等过图黑屏 → 走到出口。
              出口位置用「采点」的第 7 个点（传送点）。
            </div>
          </div>

          {inherited ? (
            <div className="mt-2 text-[10.5px] text-muted-2">
              该设备当前<b className="text-dim">继承默认配置</b>
              ；保存 BUFF 开关会为它建立独立配置（之后不再跟随默认值）。
            </div>
          ) : null}
        </div>
      </div>
    </Modal>
  );
}
