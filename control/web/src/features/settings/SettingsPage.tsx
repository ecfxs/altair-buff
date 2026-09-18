import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ApiError } from '@/api/client';
import { useLogout, useMe, usePutSettings, useSettings } from '@/api/queries';
import type { Settings } from '@/api/types';
import { humanDuration, stamp } from '@/lib/time';
import { Button, Card, Field, Icon, SectionTitle, TextInput, useToast } from '@/ui';

/** /settings —— 设备 Token、上报周期与保留策略、登出。 */
export function SettingsPage() {
  const me = useMe();
  const settings = useSettings();
  const put = usePutSettings();
  const logout = useLogout();
  const navigate = useNavigate();
  const toast = useToast();

  const [form, setForm] = useState<Settings>({ defaultReportIntervalMs: 60_000 });
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (settings.data) setForm(settings.data);
  }, [settings.data]);

  async function copy(text: string) {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      toast.push('设备 Token 已复制', 'ok');
      window.setTimeout(() => setCopied(false), 2500);
    } catch {
      // 非 HTTPS / 无剪贴板权限时退化：提示手动复制
      toast.push('浏览器拒绝了剪贴板访问，请手动选中复制', 'error');
    }
  }

  const token = me.data?.deviceToken ?? '';

  return (
    <div>
      <div className="mb-2">
        <h1>设置</h1>
        <div className="text-[11px] text-muted-2">GET /me 提供设备 Token（替代旧面板往 HTML 里注入 __TOKEN__）。</div>
      </div>

      <SectionTitle>设备 Token（填到 App 的「集控 Token」）</SectionTitle>
      <Card>
        {me.isPending ? (
          <div className="text-[11.5px] text-muted-2">加载中…</div>
        ) : me.isError ? (
          <div className="text-[11.5px] text-error">
            {me.error instanceof ApiError ? me.error.message : '加载失败'}
          </div>
        ) : (
          <>
            <div className="token-box">{token || '（服务端未返回 Token）'}</div>
            <div className="mt-[6px] text-[11px] text-muted-2">
              面板账号 <code>{me.data?.user}</code> 的口令不在此显示
              {me.data?.expiresAt ? ` · 会话到期 ${stamp(me.data.expiresAt)}` : ''}
            </div>
            <div className="mt-3 flex flex-wrap gap-2">
              <Button variant="primary" icon="copy" disabled={!token} onClick={() => void copy(token)}>
                {copied ? '已复制' : '复制 Token'}
              </Button>
              <Button
                icon="refresh"
                onClick={() => {
                  void me.refetch();
                  toast.push('已重新拉取会话信息', 'info');
                }}
              >
                重新载入
              </Button>
            </div>
            <div className="mt-[10px] text-[10.5px] leading-relaxed text-muted-2">
              设备端以请求头 <code>X-Altair-Token</code> 鉴权；Token 只会出现在 /me，其他接口一律不返回密钥。
            </div>
          </>
        )}
      </Card>

      <SectionTitle>上报周期与保留策略（GET/PUT /settings）</SectionTitle>
      <Card>
        {settings.isPending ? (
          <div className="text-[11.5px] text-muted-2">加载中…</div>
        ) : settings.isError ? (
          <div className="text-[11.5px] text-error">
            {settings.error instanceof ApiError ? settings.error.message : '加载失败'}
          </div>
        ) : (
          <div
            className="grid gap-3"
            style={{ gridTemplateColumns: 'repeat(auto-fit,minmax(220px,1fr))' }}
          >
            <Field
              label="默认上报周期 defaultReportIntervalMs"
              hint={`当前约 ${humanDuration(form.defaultReportIntervalMs)}；单台设备可在详情页覆盖（0 = 用此默认）`}
            >
              <TextInput
                type="number"
                min={15_000}
                step={1000}
                value={form.defaultReportIntervalMs}
                onChange={(e) =>
                  setForm((f) => ({
                    ...f,
                    defaultReportIntervalMs: Number.parseInt(e.target.value, 10) || 60_000,
                  }))
                }
              />
            </Field>

            <Field label="上报保留天数 reportRetentionDays" hint="超过天数的 reports / audit 由服务端每日清理">
              <TextInput
                type="number"
                min={0}
                value={form.reportRetentionDays ?? 0}
                onChange={(e) =>
                  setForm((f) => ({ ...f, reportRetentionDays: Number.parseInt(e.target.value, 10) || 0 }))
                }
              />
            </Field>

            <Field
              label="日志保留天数 logRetentionDays"
              hint="日志单独算：每份上报带若干行，设备一多它比曲线数据还占地方（默认 7 天）"
            >
              <TextInput
                type="number"
                min={0}
                value={form.logRetentionDays ?? 0}
                onChange={(e) =>
                  setForm((f) => ({ ...f, logRetentionDays: Number.parseInt(e.target.value, 10) || 0 }))
                }
              />
            </Field>

            <Field label="截图保留张数 screenshotRetentionN" hint="每台设备最多保留多少张（0 = 不按张数限制）">
              <TextInput
                type="number"
                min={0}
                value={form.screenshotRetentionN ?? 0}
                onChange={(e) =>
                  setForm((f) => ({ ...f, screenshotRetentionN: Number.parseInt(e.target.value, 10) || 0 }))
                }
              />
            </Field>

            <Field label="截图保留天数 screenshotRetentionDays">
              <TextInput
                type="number"
                min={0}
                value={form.screenshotRetentionDays ?? 0}
                onChange={(e) =>
                  setForm((f) => ({ ...f, screenshotRetentionDays: Number.parseInt(e.target.value, 10) || 0 }))
                }
              />
            </Field>

            <div className="col-span-full flex flex-wrap items-center gap-2">
              <Button
                variant="primary"
                icon="check"
                disabled={put.isPending}
                onClick={() =>
                  put.mutate(form, {
                    onSuccess: (s) => {
                      setForm(s);
                      toast.push('设置已保存', 'ok');
                    },
                    onError: (e) => toast.push(e instanceof ApiError ? e.message : '保存失败', 'error'),
                  })
                }
              >
                {put.isPending ? '保存中…' : '保存设置'}
              </Button>
              <Button icon="refresh" onClick={() => void settings.refetch()}>
                重新载入
              </Button>
              <span className="text-[10.5px] text-muted-2">
                服务端对默认周期有下限约束（≥15s），保存后以返回值回填。
              </span>
            </div>
          </div>
        )}
      </Card>

      <SectionTitle>会话</SectionTitle>
      <Card>
        <div className="flex flex-wrap items-center gap-2">
          <Button
            variant="danger"
            icon="close"
            disabled={logout.isPending}
            onClick={() =>
              logout.mutate(undefined, {
                onSettled: () => {
                  toast.push('已登出', 'info');
                  navigate('/login', { replace: true });
                },
              })
            }
          >
            登出
          </Button>
          <span className="flex items-center gap-1.5 text-[11px] text-muted-2">
            <Icon name="warn" />
            登出会调用 POST /logout 删除服务端会话并清空本地查询缓存。
          </span>
        </div>
      </Card>
    </div>
  );
}
