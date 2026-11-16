import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { ThemeProvider, CssBaseline } from '@mui/material';
import { theme } from './theme';
import { AuthProvider, useAuth } from './contexts/AuthContext';
import { RequireAuth, RequireGuest } from './components/ProtectedRoute';
import AppShell from './components/AppShell';

import LandingPage from './pages/LandingPage';
import LoginPage from './pages/LoginPage';
import DashboardPage from './pages/DashboardPage';
import ProductsPage from './pages/ProductsPage';
import WaitlistPage from './pages/WaitlistPage';
import NotificationsPage from './pages/NotificationsPage';
import AiAssistantPage from './pages/AiAssistantPage';

// Keep original screen names importable for backward compat test references
export { default as WaitlistScreen } from './WaitlistScreen';
export { default as AiAssistantScreen } from './AiAssistantScreen';

function AppRoutes() {
  const { session, expire } = useAuth();
  function onExpired() { if (session) expire(session.accessToken); }

  return (
    <Routes>
      {/* Public routes */}
      <Route path="/" element={<LandingPage />} />

      {/* Guest-only routes */}
      <Route element={<RequireGuest />}>
        <Route path="/login" element={<LoginPage />} />
      </Route>

      {/* Authenticated merchant app */}
      <Route element={<RequireAuth />}>
        <Route element={<AppShell />}>
          <Route path="/app" element={
            session ? <DashboardPage session={session} onExpired={onExpired} /> : null
          } />
          <Route path="/app/products" element={
            session ? <ProductsPage session={session} onExpired={onExpired} /> : null
          } />
          <Route path="/app/waitlists" element={
            session ? <WaitlistPage session={session} onExpired={onExpired} /> : null
          } />
          <Route path="/app/notifications" element={
            session ? <NotificationsPage session={session} onExpired={onExpired} /> : null
          } />
          <Route path="/app/ai" element={
            session ? <AiAssistantPage session={session} onExpired={onExpired} /> : null
          } />
        </Route>
      </Route>

      {/* Fallback */}
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

export default function App() {
  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <BrowserRouter>
        <AuthProvider>
          <AppRoutes />
        </AuthProvider>
      </BrowserRouter>
    </ThemeProvider>
  );
}
