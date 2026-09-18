import { useState } from 'react';
import { ApiError } from '@/api/client';
import { useAudit } from '@/api/queries';
import { stamp } from '@/lib/time';
import { Button, Card, Icon, TextInput, useToast } from '@/ui';

/** /audit —— 审计日志表格 + actor/target 过滤。 */
export function AuditPage() {
  const [actor, setActor] = useState('');
  const [target, setTarget] = useState('');
  const [limit, setLimit] = useState(100);
  const [applied, setApplied] = useState({ actor: '', target: '', limit: 100 });
  const toast = useToast();

  const q = useAudit(applied.actor, applied.target, applied.limit);

  function search() {
    if (limit < 1 || limit > 1000) {
      toast.push('limit 需在 1–1000 之间', 'error');
      return;
    }
    setApplied({ actor: actor.trim(), target: target.trim(), limit });
  }

  const entries = q.data?.entries ?? [];

  return (
    <div>
      <div className="mb-2">
        <h1>审计</h1>
        <div className="text-[11px] text-muted-2">
          GET /api/v1/panel/audit · 登录、下发指令、改配置、删截图等都会落审计。
        </div>
      </div>

      <Card className="mb-3">
        <div className="grid gap-3" style={{ gridTemplateColumns: 'repeat(auto-fit,minmax(180px,1fr))' }}>
          <label className="flex flex-col gap-[5px] text-[11px] text-muted">
            <span>actor（操作人）</span>
            <TextInput value={actor} onChange={(e) => setActor(e.target.value)} placeholder="留空 = 全部" />
          </label>
          <label className="flex flex-col gap-[5px] text-[11px] text-muted">
            <span>target（目标）</span>
            <TextInput value={target} onChange={(e) => setTarget(e.target.value)} placeholder="deviceId / scope" />
          </label>
          <label className="flex flex-col gap-[5px] text-[11px] text-muted">
            <span>limit（1–1000）</span>
            <TextInput
              type="number"
              min={1}
              max={1000}
              value={limit}
              onChange={(e) => setLimit(Number.parseInt(e.target.value, 10) || 100)}
            />
          </label>
          <div className="flex items-end gap-2">
            <Button variant="primary" icon="search" onClick={search} disabled={q.isFetching}>
              {q.isFetching ? '查询中…' : '查询'}
            </Button>
            <Button
              icon="refresh"
              onClick={() => {
                setActor('');
                setTarget('');
                setLimit(100);
                setApplied({ actor: '', target: '', limit: 100 });
              }}
            >
              重置
            </Button>
          </div>
        </div>
      </Card>

      <Card className="p-0">
        {q.isPending ? (
          <div className="p-4 text-[11.5px] text-muted-2">加载中…</div>
        ) : q.isError ? (
          <div className="p-4 text-[11.5px] text-error">
            {q.error instanceof ApiError ? q.error.message : '加载失败'}
          </div>
        ) : entries.length === 0 ? (
          <div className="p-4 text-[11.5px] text-muted-2">没有符合条件的审计记录</div>
        ) : (
          <div className="overflow-auto">
            <table className="tbl">
              <thead>
                <tr>
                  <th>#</th>
                  <th>时间</th>
                  <th>actor</th>
                  <th>action</th>
                  <th>target</th>
                  <th>detail</th>
                  <th>ip</th>
                </tr>
              </thead>
              <tbody>
                {entries.map((e) => (
                  <tr key={e.id}>
                    <td className="num">{e.id}</td>
                    <td className="num whitespace-nowrap">{stamp(e.ts)}</td>
                    <td>{e.actor}</td>
                    <td>
                      <ActionTag action={e.action} />
                    </td>
                    <td className="max-w-[200px] truncate">{e.target || '—'}</td>
                    <td className="max-w-[380px] truncate" title={e.detail ?? ''}>
                      {e.detail || '—'}
                    </td>
                    <td className="num">{e.ip || '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <div className="mt-3 flex items-center gap-2 text-[10.5px] text-muted-2">
        <Icon name="shield" />
        共 {entries.length} 条 · 服务端上限 1000 条/次
      </div>
    </div>
  );
}

function ActionTag({ action }: { action: string }) {
  const danger = /(delete|logout|stop|rollback|fail)/i.test(action);
  const ok = /(login|start|put|create|ok)/i.test(action);
  const cls = danger ? 'text-error' : ok ? 'text-ok' : 'text-fg';
  return <code className={cls}>{action}</code>;
}
