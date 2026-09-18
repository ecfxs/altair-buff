import { useEffect } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { UnauthorizedError } from '@/api/client';
import { useLiveEvents, useLogout, useMe } from '@/api/queries';
import { Button, Icon, type IconName } from '@/ui';

const NAV: Array<{ to: string; label: string; icon: IconName }> = [
  { to: '/', label: '总览', icon: 'home' },
  { to: '/configs', label: '配置', icon: 'gear' },
  { to: '/audit', label: '审计', icon: 'list' },
  { to: '/settings', label: '设置', icon: 'shield' },
];

/** 已登录后的外壳：顶部导航 + 实时状态。 */
export function AppShell() {
  const me = useMe();
  const navigate = useNavigate();
  const location = useLocation();
  const logout = useLogout();
  const live = useLiveEvents(me.isSuccess);

  // 任何请求 401 都回登录页（Cookie 过期/服务端重启）
  useEffect(() => {
    const err = me.error;
    if (err instanceof UnauthorizedError) {
      navigate('/login', { replace: true });
    }
  }, [me.error, navigate]);

  // 首屏会话查询中：不要闪登录页
  if (me.isLoading) {
    return <div className="p-10 text-center text-muted-2">加载中…</div>;
  }
  if (me.isError) {
    return (
      <div className="p-10">
        <div className="empty">
          无法连接集控服务
          <div className="mt-2 text-[11px]">
            请确认 altaird 已在同源后端运行（开发时由 vite proxy 转发到 127.0.0.1:8788）。
          </div>
          <div className="mt-3">
            <Button icon="refresh" onClick={() => void me.refetch()}>
              重试
            </Button>
          </div>
        </div>
      </div>
    );
  }

  const liveBadge =
    live === 'sse' ? (
      <span className="inline-flex items-center gap-1.5 text-[11px] text-ok" title="SSE 已连接，事件即时刷新">
        <span className="relative flex size-2">
          <span className="absolute inline-flex size-full animate-ping rounded-full bg-ok-dot opacity-60" />
          <span className="relative inline-flex size-2 rounded-full bg-ok-dot" />
        </span>
        实时
      </span>
    ) : live === 'polling' ? (
      <span
        className="inline-flex items-center gap-1.5 text-[11px] text-warn"
        title="SSE 不可用或已断开，已降级为 5 秒轮询；重连成功会自动恢复实时"
      >
        <span className="inline-block size-2 rounded-full bg-warn" />
        轮询 · 5s
      </span>
    ) : (
      <span className="inline-flex items-center gap-1.5 text-[11px] text-muted-2" title="正在建立 SSE 连接">
        <span className="inline-block size-2 rounded-full bg-muted-2" />
        连接中
      </span>
    );

  return (
    <div className="min-h-screen">
      <header className="sticky top-0 z-40 border-b border-line bg-canvas/95 backdrop-blur">
        <div className="mx-auto flex max-w-[1600px] flex-wrap items-center gap-x-3 gap-y-2 px-5 py-3">
          <NavLink to="/" className="flex items-center gap-2 no-underline">
            <Icon name="phone" size={17} className="text-ok" />
            <span className="text-[15px] font-semibold tracking-[0.02em] text-title">
              阿尔泰挂机 · 监控台
            </span>
          </NavLink>

          <nav className="flex items-center gap-1">
            {NAV.map((n) => {
              const active = n.to === '/' ? location.pathname === '/' : location.pathname.startsWith(n.to);
              return (
                <NavLink
                  key={n.to}
                  to={n.to}
                  className={`inline-flex items-center gap-1.5 rounded-ctl border px-3 py-[6px] text-xs no-underline transition-colors ${
                    active
                      ? 'border-brand bg-brand text-white'
                      : 'border-line-2 bg-[#242c36] text-fg hover:bg-[#2e3844]'
                  }`}
                >
                  <Icon name={n.icon} />
                  {n.label}
                </NavLink>
              );
            })}
          </nav>

          <div className="ml-auto flex items-center gap-3">
            {liveBadge}
            <span className="text-[11px] text-muted-2">
              账号 <code>{me.data?.user}</code>
            </span>
            <Button
              icon="close"
              disabled={logout.isPending}
              onClick={() => {
                logout.mutate(undefined, {
                  onSettled: () => navigate('/login', { replace: true }),
                });
              }}
            >
              登出
            </Button>
          </div>
        </div>
      </header>

      <main className="mx-auto max-w-[1600px] px-5 pb-14 pt-4">
        <Outlet context={{ live }} />
      </main>
    </div>
  );
}
