import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';

export default defineConfig(({ mode }) => {
  const root = resolve(import.meta.dirname, '..');
  const env = { ...loadEnv(mode, root, ''), ...process.env };
  const runtimePath = env.ALDER_RUNTIME_FILE || resolve(root, '.runtime/services.json');
  const runtime = existsSync(runtimePath) ? JSON.parse(readFileSync(runtimePath, 'utf8')) : {};
  const target = (service: string, port: string) => runtime[service] || `http://127.0.0.1:${port}`;
  const employee = target('employee', env.EMPLOYEE_SERVICE_PORT || '8180');
  const leave = target('leave', env.LEAVE_SERVICE_PORT || '8181');
  const payroll = target('payroll', env.PAYROLL_SERVICE_PORT || '8183');
  const recruitment = target('recruitment', env.RECRUITMENT_SERVICE_PORT || '8184');
  const proxy = Object.fromEntries([
    ['/api/auth', employee], ['/api/employees', employee], ['/api/internal/employees', employee],
    ['/api/leaves', leave], ['/api/payroll', payroll],
    ...['jobs', 'candidates', 'applications', 'shorten', 'crawler', 'notifications', 'storage'].map(route => [`/api/${route}`, recruitment]),
    ['/s/', recruitment], ['/demo-jobs', recruitment],
  ].map(([path, url]) => [path, { target: url, changeOrigin: true }]));
  proxy['/health/leave'] = { target: leave, changeOrigin: true, rewrite: () => '/actuator/health' } as typeof proxy[string];
  proxy['/health/recruitment'] = { target: recruitment, changeOrigin: true, rewrite: () => '/actuator/health' } as typeof proxy[string];
  return { plugins: [react()], server: { port: Number(env.FRONTEND_PORT || 5173), strictPort: true, proxy }, preview: { port: Number(env.FRONTEND_PORT || 5173), strictPort: true, proxy }, build: { sourcemap: false } };
});
