import { Navigate, Outlet } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';

export function RequireAuth() {
  const { session } = useAuth();
  if (!session) return <Navigate to="/login" replace />;
  return <Outlet />;
}

export function RequireGuest() {
  const { session } = useAuth();
  if (session) return <Navigate to="/app" replace />;
  return <Outlet />;
}
