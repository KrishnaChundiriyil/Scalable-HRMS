import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './tests', fullyParallel: false, workers: 1, timeout: 60000,
  expect: { timeout: 12000 }, reporter: [['list'], ['html', { open: 'never' }]],
  use: { reducedMotion: 'reduce', baseURL: process.env.ALDER_E2E_BASE_URL || 'http://127.0.0.1:5173', viewport: { width: 1440, height: 1000 }, trace: 'retain-on-failure', screenshot: 'only-on-failure' },
});
