import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { api, ApiError, type Session } from '../api';

interface AuthContextValue {
  session: Session | null;
  notice: string;
  login: (session: Session) => void;
  logout: () => void;
  expire: (token: string) => void;
  clearNotice: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null);
  const [notice, setNotice] = useState('');

  function expire(token: string) {
    setSession(current => current?.accessToken === token ? null : current);
    setNotice('Your session expired. Please sign in again.');
  }

  useEffect(() => {
    if (!session) return;
    const timeout = window.setTimeout(
      () => expire(session.accessToken),
      Math.max(0, Date.parse(session.expiresAt) - Date.now())
    );
    return () => window.clearTimeout(timeout);
  }, [session]);

  function login(value: Session) {
    setSession(value);
    setNotice('');
  }

  function logout() {
    if (session) void api('/api/auth/logout', session.accessToken, { method: 'POST' }).catch(() => undefined);
    setSession(null);
    setNotice('You have signed out.');
  }

  function clearNotice() { setNotice(''); }

  return (
    <AuthContext.Provider value={{ session, notice, login, logout, expire, clearNotice }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
}
