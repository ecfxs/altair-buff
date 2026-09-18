import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { AppShell } from './AppShell';
import { useAuth } from '@/features/auth/useAuth';
import { LoginPage } from '@/features/auth/LoginPage';
import { DevicesPage } from '@/features/devices/DevicesPage';
import { DeviceDetailPage } from '@/features/devices/DeviceDetailPage';
import { ConfigsPage } from '@/features/config/ConfigsPage';
import { AuditPage } from '@/features/audit/AuditPage';
import { SettingsPage } from '@/features/settings/SettingsPage';

/** 未登录访问受保护路由 → /login（带 from 便于回跳）。 */
function RequireAuth({ children }: { children: React.ReactNode }) {
  const { authed, loading } = useAuth();
  const location = useLocation();
  if (loading) return <div className="p-10 text-center text-muted-2">加载中…</div>;
  if (!authed) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  return <>{children}</>;
}

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        element={
          <RequireAuth>
            <AppShell />
          </RequireAuth>
        }
      >
        <Route index element={<DevicesPage />} />
        <Route path="devices/:id" element={<DeviceDetailPage />} />
        <Route path="configs" element={<ConfigsPage />} />
        <Route path="audit" element={<AuditPage />} />
        <Route path="settings" element={<SettingsPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}
