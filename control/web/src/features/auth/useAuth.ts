import { useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMe } from '@/api/queries';

/**
 * 会话状态。`/me` 是唯一权威来源（Cookie 是 HttpOnly，前端读不到内容）。
 * 401 由 api client 抛 UnauthorizedError，这里统一转成「未登录」并跳 /login。
 */
export function useAuth() {
  const navigate = useNavigate();
  const me = useMe();

  const toLogin = useCallback(
    (replace = false) => navigate('/login', { replace }),
    [navigate],
  );

  return {
    me,
    /** 会话查询成功 = 已登录。 */
    authed: me.isSuccess,
    /** 首次加载中（避免闪现登录页）。 */
    loading: me.isLoading,
    user: me.data?.user ?? '',
    deviceToken: me.data?.deviceToken ?? '',
    toLogin,
  };
}
