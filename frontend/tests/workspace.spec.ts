import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const password = process.env.ALDER_E2E_PASSWORD;
test.skip(!password, 'Run with scripts/verify_real_integration.py --frontend for isolated real services.');
async function login(page: Page, email: string) {
  await page.goto('/login'); await page.getByLabel('Work email', { exact: true }).fill(email);
  await page.getByLabel('Password', { exact: false }).first().fill(password!);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByRole('heading', { name: /^Good /, level: 1 })).toBeVisible();
}
const nextDay = () => { const day = new Date(); day.setDate(day.getDate() + 24); while ([0, 6].includes(day.getDay())) day.setDate(day.getDate() + 1); return `${day.getFullYear()}-${String(day.getMonth() + 1).padStart(2, '0')}-${String(day.getDate()).padStart(2, '0')}`; };

test('employee requests half a day, manager approves, employee cancels and balance is refunded', async ({ page }) => {
  // The outbox may deliver the approval just after the employee signs back in.
  await page.addLocatorHandler(page.getByRole('dialog', { name: 'Your leave is approved' }), async popup => { await popup.getByRole('button', { name: 'Got it', exact: true }).click(); });
  await login(page, 'employee@example.test');
  await page.getByRole('link', { name: 'Time off', exact: true }).click();
  await page.locator('.page-heading').getByRole('button', { name: 'Request time off', exact: true }).click();
  const dialog = page.getByRole('dialog'); await expect(dialog).toBeVisible();
  const day = nextDay(); await dialog.getByLabel('From', { exact: true }).fill(day); await dialog.getByLabel('Until', { exact: true }).fill(day);
  await dialog.getByLabel('How much of the day?').selectOption('MORNING'); await dialog.getByLabel('A short note').fill('Browser workflow half-day request');
  await expect(dialog.getByText('0.5', { exact: true })).toBeVisible();
  await dialog.getByRole('button', { name: 'Send request' }).click(); await expect(dialog).not.toBeVisible();
  const row = page.getByRole('row').filter({ hasText: 'Morning' }).filter({ hasText: 'Pending' }); await expect(row).toContainText('Pending');
  await page.getByRole('button', { name: 'Sign out', exact: true }).click();
  await login(page, 'manager@example.test'); await page.getByRole('link', { name: 'Time off', exact: true }).click();
  await page.getByRole('button', { name: 'Approvals', exact: true }).click();
  await page.getByRole('row').filter({ hasText: 'Morning' }).getByRole('button').first().click();
  await page.getByRole('dialog').getByRole('button', { name: 'Approve leave', exact: true }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Confirm approval' }).click(); await expect(page.getByRole('dialog')).not.toBeVisible();
  await page.getByRole('button', { name: 'Sign out', exact: true }).click(); await login(page, 'employee@example.test');
  await page.goto('/leave'); await expect(page.locator('.leave-balance-card').first()).toContainText('19.5');
  await page.getByRole('link', { name: 'Time off', exact: true }).click();
  await page.getByRole('row').filter({ hasText: 'Morning' }).filter({ hasText: 'Approved' }).getByRole('button').first().click();
  await page.getByRole('dialog').getByRole('button', { name: 'Cancel this request' }).click();
  await page.getByLabel('Reason for cancellation').fill('Plans changed after browser verification');
  await page.getByRole('dialog').getByRole('button', { name: 'Cancel request', exact: true }).click();
  await expect(page.getByRole('dialog')).not.toBeVisible();
  await page.reload(); await expect(page.locator('.leave-balance-card').first()).toContainText('20');
});

test('admin adds a colleague and reporting manager through the actual forms', async ({ page }) => {
  await login(page, 'admin@example.test'); await page.getByRole('link', { name: 'People', exact: true }).click();
  await page.getByRole('button', { name: 'Add a colleague' }).click(); const dialog = page.getByRole('dialog');
  await dialog.getByLabel('Full name').fill('Aditi Sharma'); await dialog.getByLabel('Work email').fill('aditi@example.test');
  await dialog.getByLabel('Department', { exact: true }).fill('Design'); await dialog.getByLabel('Job title').fill('Product Designer');
  await dialog.getByLabel('Initial password').fill(password!); await dialog.getByRole('button', { name: 'Add colleague', exact: true }).click();
  await expect(dialog).not.toBeVisible(); await page.getByLabel('Search colleagues').fill('Aditi');
  await page.getByRole('button', { name: /Aditi Sharma.*aditi/ }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Employment', exact: true }).click();
  await page.getByLabel('Reporting manager').selectOption({ label: 'Assigned Manager · Manager' });
  await page.getByRole('button', { name: 'Save employment details' }).click(); await expect(page.getByRole('dialog')).not.toBeVisible();
});

test('HR creates payroll; employee views a payslip and cannot access administration', async ({ page }) => {
  await login(page, 'hr@example.test'); await page.getByRole('link', { name: 'Payroll', exact: true }).click();
  await page.getByRole('button', { name: 'Create payroll', exact: true }).first().click(); const dialog = page.getByRole('dialog');
  await dialog.getByRole('combobox', { name: 'Employee', exact: true }).selectOption({ label: 'Aditi Sharma' });
  await dialog.getByLabel('Pay period').fill('2028-03'); await dialog.getByLabel('Basic salary (INR)').fill('50000');
  await dialog.getByLabel('Allowances (INR)').fill('5000'); await dialog.getByLabel('Deductions (INR)').fill('2500');
  await dialog.getByRole('button', { name: 'Calculate pay' }).click(); await expect(dialog.locator('.net strong')).toContainText('52,500');
  await dialog.getByRole('button', { name: 'Create payroll', exact: true }).click(); await expect(dialog).not.toBeVisible();
  await page.getByRole('button', { name: 'Sign out', exact: true }).click(); await login(page, 'aditi@example.test');
  await page.getByRole('link', { name: 'Payroll', exact: true }).click(); await page.getByRole('button', { name: 'View payslip' }).click();
  await expect(page.getByRole('dialog').getByRole('heading', { name: 'March 2028' })).toBeVisible();
  await expect(page.getByRole('dialog').locator('.payslip-total')).toContainText('52,500');
  await page.keyboard.press('Escape'); await expect(page.getByRole('dialog')).not.toBeVisible();
  await page.goto('/admin'); await expect(page.getByRole('heading', { name: 'This space is for administrators.' })).toBeVisible();
});

test('HR publishes a role, a public candidate applies, HR downloads and reviews the resume', async ({ page }) => {
  await login(page, 'hr@example.test'); await page.getByRole('link', { name: 'Hiring', exact: true }).click();
  await page.getByRole('button', { name: 'Publish a role', exact: true }).first().click(); let dialog = page.getByRole('dialog');
  await dialog.getByLabel('Role title').fill('Senior Product Designer'); await dialog.getByLabel('Location', { exact: true }).fill('Bengaluru · Hybrid');
  await dialog.getByLabel('About the opportunity').fill('Help shape thoughtful experiences for people at work. Join a small team of curious designers and engineers.');
  await dialog.getByRole('button', { name: 'Publish role', exact: true }).click(); await expect(dialog).not.toBeVisible();
  await page.goto('/careers'); await page.getByRole('button', { name: /Senior Product Designer/ }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Apply for this role' }).click();
  dialog = page.getByRole('dialog').filter({ has: page.getByRole('heading', { name: 'Apply for this role' }) });
  await dialog.getByLabel('Full name').fill('Maya Iyer'); await dialog.getByLabel('Email address').fill('maya@example.test');
  await dialog.getByLabel('Resume file').setInputFiles({ name: 'maya-resume.txt', mimeType: 'text/plain', buffer: Buffer.from('Maya Iyer · Product designer · Browser verification') });
  await dialog.getByRole('button', { name: 'Send application' }).click(); await expect(page.getByRole('dialog').getByText('Application received.', { exact: true })).toBeVisible();
  await page.goto('/hiring'); await page.getByRole('button', { name: 'Applications', exact: true }).click();
  await page.getByRole('button', { name: /Maya Iyer.*maya/ }).click();
  const download = page.waitForEvent('download'); await page.getByRole('dialog').getByRole('button', { name: 'Download', exact: true }).click();
  const result = await download; const path = await result.path(); expect(readFileSync(path!, 'utf8')).toContain('Maya Iyer');
  await page.getByLabel('Application stage').selectOption('SHORTLISTED'); await expect(page.getByLabel('Application stage')).toHaveValue('SHORTLISTED');
});

test('desktop pages and modal keyboard focus are accessible; screenshots are captured', async ({ page }) => {
  await login(page, 'admin@example.test');
  const errors: string[] = []; page.on('pageerror', error => errors.push(error.message));
  for (const path of ['/', '/people', '/leave', '/payroll', '/hiring', '/calendar', '/insights', '/notifications', '/admin', '/account', '/help']) {
    await page.goto(path); await expect(page).not.toHaveURL(/\/login$/); await expect(page.locator('h1')).toBeVisible(); await expect(page.locator('.skeleton-list:visible')).toHaveCount(0, { timeout: 15000 });
    const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
    expect.soft(results.violations.map(value => ({ id: value.id, nodes: value.nodes.map(node => ({ target: node.target, summary: node.failureSummary })) })), path).toEqual([]);
  }
  await page.goto('/leave'); await page.locator('.page-heading').getByRole('button', { name: 'Request time off', exact: true }).click();
  for (let i = 0; i < 15; i++) { await page.keyboard.press('Tab'); expect(await page.locator('dialog[open]').evaluate(node => node.contains(document.activeElement))).toBe(true); }
  await page.keyboard.press('Escape'); await expect(page.getByRole('dialog')).not.toBeVisible();
  await page.goto('/'); await expect(page.locator('.stat-card').first()).toContainText('Our people');
  await page.screenshot({ path: resolve('../.runtime/alder-desktop.png'), fullPage: true });
  await page.goto('/leave'); await page.screenshot({ path: resolve('../.runtime/alder-time-off.png'), fullPage: true });
  expect(errors).toEqual([]);
});

test('mobile navigation, forms and public careers stay within the viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 }); await login(page, 'employee@example.test');
  for (const path of ['/', '/leave', '/calendar', '/people', '/payroll', '/hiring', '/account']) {
    await page.goto(path); await expect(page.locator('h1')).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), path).toBe(true);
  }
  await page.goto('/'); await page.getByRole('button', { name: 'Open navigation' }).click();
  await page.getByRole('link', { name: 'Time off', exact: true }).click(); await expect(page.getByRole('heading', { name: 'Time off', exact: true })).toBeVisible();
  await page.locator('.page-heading').getByRole('button', { name: 'Request time off', exact: true }).click();
  expect(await page.getByRole('dialog').evaluate(node => node.getBoundingClientRect().width <= innerWidth)).toBe(true);
  await page.keyboard.press('Escape'); await page.goto('/'); await page.screenshot({ path: resolve('../.runtime/alder-mobile.png'), fullPage: true });
  await page.goto('/careers'); expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
});

test('a real SSE approval immediately opens a popup and retains the Inbox item', async ({ page, browser }) => {
  await login(page, 'employee@example.test');
  const stream = page.waitForResponse(response => response.url().includes('/notifications/stream') && response.status() === 200);
  await page.reload(); await stream;
  const day = new Date(); day.setDate(day.getDate() + 80); while ([0, 6].includes(day.getDay())) day.setDate(day.getDate() + 1);
  const date = day.toLocaleDateString('en-CA');
  const leave = await page.evaluate(async ({ date }) => {
    const session = JSON.parse(sessionStorage.getItem('alder.session')!);
    const response = await fetch('/api/leaves', { method: 'POST', headers: { Authorization: 'Bearer ' + session.token, 'Content-Type': 'application/json' }, body: JSON.stringify({ type: 'CASUAL', startDate: date, endDate: date, reason: 'Live approval popup verification' }) });
    if (!response.ok) throw new Error(await response.text()); return response.json();
  }, { date });
  const inboxStream = page.waitForResponse(response => response.url().includes('/notifications/stream') && response.status() === 200);
  await page.goto('/notifications'); await inboxStream;
  const context = await browser.newContext({ baseURL: process.env.ALDER_E2E_BASE_URL || 'http://127.0.0.1:5173', reducedMotion: 'reduce' });
  try {
    const manager = await context.newPage(); await login(manager, 'manager@example.test');
    await manager.goto('/leave?tab=approvals');
    await manager.getByRole('row').filter({ hasText: 'Casual' }).getByRole('button').first().click();
    await manager.getByRole('dialog').getByRole('button', { name: 'Approve leave', exact: true }).click();
    await manager.getByRole('dialog').getByRole('button', { name: 'Confirm approval' }).click();
    const popup = page.getByRole('dialog', { name: 'Your leave is approved' });
    await expect(popup).toBeVisible({ timeout: 12000 });
    expect((await popup.boundingBox())!.height).toBeLessThan(600);
    await page.screenshot({ path: resolve('../.runtime/alder-approval-popup.png'), fullPage: false });
    await expect(popup.getByText('This update is saved in your Inbox, so you can return to it later.')).toBeVisible();
    await popup.getByRole('button', { name: 'Got it' }).click(); await expect(popup).not.toBeVisible();
    await expect(page.locator('.notification-row').filter({ hasText: 'Your leave request was approved' }).first()).toBeVisible();
    await page.evaluate(async (id) => { const session = JSON.parse(sessionStorage.getItem('alder.session')!); await fetch('/api/leaves/me/' + id + '/cancel', { method: 'POST', headers: { Authorization: 'Bearer ' + session.token, 'Content-Type': 'application/json' }, body: JSON.stringify({ reason: 'Live popup test complete; restore balance' }) }); }, leave.id);
  } finally { await context.close(); }
});

test('job seeker gets a public company workspace; senior leader requests own leave by email', async ({ page }) => {
  await login(page, 'applicant@example.test');
  await expect(page.getByRole('heading', { name: 'Good people. Thoughtful work. Room to grow.' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'People', exact: true })).toHaveCount(0);
  await expect(page.getByRole('link', { name: 'Time off', exact: true })).toHaveCount(0);
  await expect(page.getByRole('link', { name: 'Payroll', exact: true })).toHaveCount(0);
  await page.getByRole('link', { name: 'My applications', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'My applications', exact: true })).toBeVisible();
  await expect(page.getByRole('row').filter({ hasText: 'Submitted' })).toHaveCount(1);
  await page.goto('/leave'); await expect(page).toHaveURL(/\/$/);
  await page.getByRole('button', { name: 'Sign out', exact: true }).click();
  await login(page, 'leader@example.test'); await page.getByRole('link', { name: 'Time off', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Leave arrangements for your position' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Request time off', exact: true })).toHaveCount(0);
  await expect(page.getByRole('link', { name: 'Email HR', exact: true }).first()).toHaveAttribute('href', /^mailto:leave@alder.example/);
  await expect(page.locator('.leave-balance-card').first()).toContainText('20');
});

test('employees apply internally and see private, persistent recent activity on Overview', async ({ page }) => {
  await login(page, 'employee@example.test');
  await page.getByRole('link', { name: 'Opportunities', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Internal opportunities', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'View Java Engineer', exact: true }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Apply for this role' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByText(/You’re applying as a current employee/)).toBeVisible();
  await dialog.getByLabel('Resume file').setInputFiles({ name: 'internal-resume.txt', mimeType: 'text/plain', buffer: Buffer.from('Current employee internal mobility application') });
  await dialog.getByRole('button', { name: 'Send application' }).click();
  await expect(dialog.getByText('Application received.', { exact: true })).toBeVisible();
  await dialog.getByRole('button', { name: 'Back to opportunities' }).click();
  await page.getByRole('navigation').getByRole('link', { name: 'My applications', exact: true }).click();
  await expect(page.getByRole('row').filter({ hasText: 'Java Engineer' })).toContainText('Submitted');
  await page.getByRole('link', { name: 'Overview', exact: true }).click();
  const activity = page.getByRole('region', { name: 'Your recent activity', exact: true });
  await expect(activity.getByText('Submitted a job application', { exact: true })).toBeVisible();
  await expect(activity.getByText('Last 48 hours', { exact: true })).toBeVisible();
  await expect(activity.getByText('Published a job opportunity', { exact: true })).toHaveCount(0);
  await page.reload();
  await expect(activity.getByText('Submitted a job application', { exact: true })).toBeVisible();
  await activity.scrollIntoViewIfNeeded();
  await page.screenshot({ path: resolve('../.runtime/alder-recent-activity.png'), fullPage: false });
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
  expect(results.violations.map(value => value.id)).toEqual([]);
  await page.getByRole('button', { name: 'Sign out', exact: true }).click();
  await login(page, 'manager@example.test');
  await expect(activity.getByText('Submitted a job application', { exact: true })).toHaveCount(0);
});
