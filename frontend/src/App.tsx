import { Component, Suspense, lazy } from 'react';
import type { ReactNode } from 'react';
import { BrowserRouter, Link, Navigate, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider, useAuth, useRoles } from './lib/auth';
import { ApiError } from './lib/api';
import { ToastProvider, Loading } from './components/ui';
import { ApprovalPopup } from './components/ApprovalPopup';
import { Layout } from './components/Layout';
import { AuthPage } from './pages/Auth';

const Dashboard = lazy(() => import('./pages/Dashboard').then(module => ({ default: module.Dashboard })));
const Leave = lazy(() => import('./pages/Leave').then(module => ({ default: module.LeavePage })));
const Calendar = lazy(() => import('./pages/Calendar').then(module => ({ default: module.CalendarPage })));
const Insights = lazy(() => import('./pages/Insights').then(module => ({ default: module.InsightsPage })));
const People = lazy(() => import('./pages/People').then(module => ({ default: module.PeoplePage })));
const Account = lazy(() => import('./pages/People').then(module => ({ default: module.AccountPage })));
const Payroll = lazy(() => import('./pages/Payroll').then(module => ({ default: module.PayrollPage })));
const Hiring = lazy(() => import('./pages/Hiring').then(module => ({ default: module.HiringPage })));
const Careers = lazy(() => import('./pages/Hiring').then(module => ({ default: module.CareersPage })));
const MyApplications = lazy(() => import('./pages/Hiring').then(module => ({ default: module.MyApplicationsPage })));
const Notifications = lazy(() => import('./pages/Notifications').then(module => ({ default: module.NotificationsPage })));
const Admin = lazy(() => import('./pages/Admin').then(module => ({ default: module.AdminPage })));
const Help = lazy(() => import('./pages/Admin').then(module => ({ default: module.HelpPage })));
const client = new QueryClient({ defaultOptions: { queries: { staleTime: 20000, refetchOnWindowFocus: false, retry: (count, error) => count < 1 && !(error instanceof ApiError && [400, 401, 403, 404].includes(error.status)) }, mutations: { retry: false } } });

function Protected({ children, admin = false, staff = false }: { children: ReactNode; admin?: boolean; staff?: boolean }) {
  const { session } = useAuth(); const roles = useRoles();
  if (!session) return <Navigate to="/login" replace />;
  if (staff && roles.candidate) return <Navigate to="/" replace />;
  if (admin && !roles.admin) return <div className="not-found"><h1>This space is for administrators.</h1><p>Your workspace is waiting for you.</p><Link className="button primary" to="/">Back to overview</Link></div>;
  return children;
}
class ErrorBoundary extends Component<{ children: ReactNode }, { error: boolean }> {
  state = { error: false };
  static getDerivedStateFromError() { return { error: true }; }
  render() { return this.state.error ? <div className="not-found"><h1>A little pause.</h1><p>Something didn’t load as expected. Please refresh the workspace.</p><button className="button primary" onClick={() => location.reload()}>Refresh workspace</button></div> : this.props.children; }
}
export default function App() {
  return <QueryClientProvider client={client}><AuthProvider><ToastProvider><BrowserRouter><ApprovalPopup /><ErrorBoundary><Suspense fallback={<div className="initial-loading"><img src="/alder.svg" alt="Alder & Co." /><Loading rows={3} /></div>}><Routes><Route path="/login" element={<AuthPage />} /><Route path="/register" element={<AuthPage register />} /><Route path="/careers" element={<Careers />} /><Route element={<Protected><Layout /></Protected>}><Route index element={<Dashboard />} /><Route path="people" element={<Protected staff><People /></Protected>} /><Route path="leave" element={<Protected staff><Leave /></Protected>} /><Route path="calendar" element={<Protected staff><Calendar /></Protected>} /><Route path="insights" element={<Protected staff><Insights /></Protected>} /><Route path="payroll" element={<Protected staff><Payroll /></Protected>} /><Route path="hiring" element={<Hiring />} /><Route path="applications" element={<MyApplications />} /><Route path="notifications" element={<Protected staff><Notifications /></Protected>} /><Route path="account" element={<Account />} /><Route path="help" element={<Help />} /><Route path="admin" element={<Protected admin><Admin /></Protected>} /><Route path="*" element={<div className="not-found"><p className="eyebrow">404 · A LITTLE OFF TRACK</p><h1>Let’s find your way back.</h1><p>This page isn’t part of the workspace.</p><Link className="button primary" to="/">Back to overview</Link></div>} /></Route></Routes></Suspense></ErrorBoundary></BrowserRouter></ToastProvider></AuthProvider></QueryClientProvider>;
}
