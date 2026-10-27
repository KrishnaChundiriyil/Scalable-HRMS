import { createContext, useContext, useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { loadSession, setToken } from './api';
import type { Session } from '../types';

const AuthContext = createContext<{ session: Session | null; login: (value: Session) => void; logout: () => void }>({ session: null, login: () => {}, logout: () => {} });
export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState(loadSession);
  const client = useQueryClient();
  setToken(session?.token || '');
  const logout = () => { sessionStorage.removeItem('alder.session'); setToken(''); setSession(null); client.clear(); };
  const login = (value: Session) => { client.clear(); sessionStorage.setItem('alder.session', JSON.stringify(value)); setToken(value.token); setSession(value); };
  useEffect(() => {
    const expired = () => logout();
    window.addEventListener('alder-session-expired', expired);
    if (!session) return () => window.removeEventListener('alder-session-expired', expired);
    const payload = JSON.parse(atob(session.token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
    const timer = window.setTimeout(expired, Math.max(0, payload.exp * 1000 - Date.now()));
    return () => { window.clearTimeout(timer); window.removeEventListener('alder-session-expired', expired); };
  }, [session]);
  return <AuthContext.Provider value={{ session, login, logout }}>{children}</AuthContext.Provider>;
}
export const useAuth = () => useContext(AuthContext);
export function useRoles() {
  const { session } = useAuth(); const role = session?.employee.role;
  return { hr: role === 'HR' || role === 'ADMIN', reviewer: role === 'MANAGER' || role === 'HR' || role === 'ADMIN', admin: role === 'ADMIN' };
}
