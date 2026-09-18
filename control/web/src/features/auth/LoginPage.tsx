import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { ApiError, RateLimitedError, UnauthorizedError, api } from '@/api/client';
import { qk } from '@/api/queries';
import { Button, Icon, TextInput, useToast } from '@/ui';

/** /login —— 独立页，无导航。 */
export function LoginPage() {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const navigate = useNavigate();
  const qc = useQueryClient();
  const toast = useToast();

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError('');
    setBusy(true);
    try {
      const info = await api.login(username.trim(), password);
      await qc.invalidateQueries({ queryKey: qk.me });
      toast.push(`已登录：${info.user}`, 'ok');
      navigate('/', { replace: true });
    } catch (err) {
      if (err instanceof RateLimitedError) {
        setError('尝试过频，已退避。请稍等一会儿再试。');
      } else if (err instanceof UnauthorizedError) {
        setError('用户名或口令不正确');
      } else if (err instanceof ApiError) {
        setError(err.message);
      } else {
        setError('无法连接服务器，请确认地址与网络');
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center p-6">
      <form onSubmit={submit} className="card-shell w-full max-w-[380px] p-6">
        <div className="mb-[18px] flex items-center gap-2">
          <Icon name="phone" size={18} className="text-ok" />
          <h1>阿尔泰挂机 · 监控台</h1>
        </div>

        <div className="grid gap-3">
          <label className="flex flex-col gap-[5px] text-[11px] text-muted">
            <span>用户名</span>
            <TextInput
              value={username}
              autoFocus
              autoComplete="username"
              onChange={(e) => setUsername(e.target.value)}
              placeholder="面板账号"
            />
          </label>

          <label className="flex flex-col gap-[5px] text-[11px] text-muted">
            <span>口令</span>
            <TextInput
              type="password"
              value={password}
              autoComplete="current-password"
              onChange={(e) => setPassword(e.target.value)}
              placeholder="面板口令"
            />
          </label>

          {error ? (
            <div className="rounded-ctl border border-card-off bg-badge-err px-3 py-2 text-[11.5px] text-error">
              {error}
            </div>
          ) : null}

          <Button variant="primary" type="submit" disabled={busy || !username || !password} icon="check">
            {busy ? '登录中…' : '登录'}
          </Button>

          <div className="text-[10.5px] leading-relaxed text-muted-2">
            登录成功后服务端下发 HttpOnly 会话 Cookie（30 天）。连续失败会按 IP + 用户指数退避。
          </div>
        </div>
      </form>
    </div>
  );
}
