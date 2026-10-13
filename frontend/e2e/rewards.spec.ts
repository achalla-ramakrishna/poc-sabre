import { expect, test, type Page } from '@playwright/test';
import { FakeApi } from './fakeApi';

async function signIn(page: Page, persona: 'Loyalty analyst' | 'Loyalty approver' | 'Viewer (read only)') {
  await page.goto('/login');
  await page.getByLabel(persona).check();
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: 'Redemption rules' })).toBeVisible();
}

async function fillSydPerRule(page: Page, { maxRedeem = '70', origins = 'SYD' } = {}) {
  await page.getByLabel('Rule name').fill('SYD-PER Economy Choice');
  await page.getByLabel('Origins').fill(origins);
  await page.getByLabel('Destinations').fill('PER');
  await page.getByLabel('Fare brands').fill('CHOICE');
  await page.getByLabel('Points of sale').fill('AU');
  await page.getByLabel('Rate 1 fare brand').fill('CHOICE');
  await page.getByLabel('Rate 1 points per unit').fill('47.8');
  await page.getByLabel('Max redeem percent').fill(maxRedeem);
  await page.getByLabel('Minimum points').fill('5000');
}

test('redirects to sign-in when not signed in', async ({ page }) => {
  await page.goto('/rules');
  await expect(page).toHaveURL(/\/login$/);
});

test('analyst sees field errors from the API next to the fields, fixes them and saves', async ({ page }) => {
  const api = new FakeApi();
  await api.install(page);
  await signIn(page, 'Loyalty analyst');
  await page.getByRole('link', { name: 'New rule' }).click();

  await fillSydPerRule(page, { maxRedeem: '120', origins: 'Sydney' });
  await page.getByRole('button', { name: 'Save draft' }).click();

  await expect(page.getByRole('alert')).toContainText('The rule has invalid fields');
  await expect(page.getByRole('alert')).toContainText('cid-test-123');
  await expect(page.getByText('must be between 0 and 100')).toBeVisible();
  await expect(page.getByText('3-letter IATA airport codes')).toBeVisible();

  await page.getByLabel('Origins').fill('SYD');
  await page.getByLabel('Max redeem percent').fill('70');
  await page.getByRole('button', { name: 'Save draft' }).click();

  await expect(page).toHaveURL(/\/rules\/rul_/);
  await expect(page.getByRole('heading', { name: /SYD-PER Economy Choice/ })).toContainText('Draft');
});

test('full lifecycle: analyst submits, approver approves, rule goes live, preview shows points', async ({ browser }) => {
  const api = new FakeApi();

  // Analyst creates and submits
  const analyst = await (await browser.newContext()).newPage();
  await api.install(analyst);
  await signIn(analyst, 'Loyalty analyst');
  await analyst.getByRole('link', { name: 'New rule' }).click();
  await fillSydPerRule(analyst);
  await analyst.getByRole('button', { name: 'Save draft' }).click();
  await expect(analyst).toHaveURL(/\/rules\/rul_/);
  await analyst.getByRole('button', { name: 'Submit for approval' }).click();
  await expect(analyst.getByRole('status')).toHaveText('Submitted for approval');
  await expect(analyst.getByRole('heading', { name: /SYD-PER/ })).toContainText('Pending approval');
  // Analysts never see Approve
  await expect(analyst.getByRole('button', { name: 'Approve' })).toHaveCount(0);
  const ruleUrl = new URL(analyst.url()).pathname;

  // A different person approves
  const approver = await (await browser.newContext()).newPage();
  await api.install(approver);
  await signIn(approver, 'Loyalty approver');
  await expect(approver.getByTestId('rule-row')).toHaveCount(1);
  await approver.goto(ruleUrl);
  await approver.getByRole('button', { name: 'Approve' }).click();
  await expect(approver.getByRole('status')).toHaveText('Approved and queued for DPE');

  // The page polls until DPE has it
  await expect(approver.getByRole('heading', { name: /SYD-PER/ })).toContainText('Live', { timeout: 10_000 });
  await expect(approver.getByTestId('publications')).toContainText('ACKED');
  await expect(approver.getByTestId('publications')).toContainText('dpe-7001');

  // Preview against live rules
  await approver.getByRole('link', { name: 'Preview' }).click();
  await approver.getByRole('button', { name: 'Preview price' }).click();
  await expect(approver.getByTestId('preview-result')).toContainText('AUD 900.00 or 43,100 points');
  await expect(approver.getByTestId('preview-result')).toContainText('43,100 points');
});

test('approver can reject with a reason, analyst sees it on the draft', async ({ page }) => {
  const api = new FakeApi();
  api.rules.set('rul_9', {
    ruleId: 'rul_9', version: 1, status: 'PENDING_APPROVAL', airline: 'VA', name: 'Peak rule', specificity: 2,
    scope: { origins: ['SYD'], destinations: ['PER'], cabins: [], fareBrands: [], bundles: [], pointsOfSale: [] },
    rates: [{ fareBrand: null, pointsPerUnit: '60', currency: 'AUD', roundingStep: 100 }],
    limits: { maxRedeemPercent: 90, minPoints: 0 }, effectiveFrom: '2026-12-01', createdBy: 'u-analyst',
    submittedBy: 'u-analyst', createdAt: '2026-10-01T00:00:00Z', revision: 1,
  });
  await api.install(page);
  await signIn(page, 'Loyalty approver');
  await page.goto('/rules/rul_9');
  await page.getByRole('button', { name: 'Reject' }).click();
  await page.getByLabel('Rejection reason').fill('Rate too generous for peak dates');
  await page.getByRole('button', { name: 'Confirm reject' }).click();

  await expect(page.getByText('Rejected: Rate too generous for peak dates')).toBeVisible();
  await expect(page.getByRole('heading', { name: /Peak rule/ })).toContainText('Draft');
});

test('viewer can read but not create or edit', async ({ page }) => {
  const api = new FakeApi();
  api.rules.set('rul_1', {
    ruleId: 'rul_1', version: 1, status: 'PUBLISHED', airline: 'VA', name: 'Live rule', specificity: 2,
    scope: { origins: ['SYD'], destinations: ['PER'], cabins: [], fareBrands: [], bundles: [], pointsOfSale: [] },
    rates: [{ fareBrand: null, pointsPerUnit: '50', currency: 'AUD', roundingStep: 100 }],
    limits: { maxRedeemPercent: 70, minPoints: 0 }, effectiveFrom: '2026-01-01', createdBy: 'x',
    createdAt: '2026-01-01T00:00:00Z', revision: 3,
  });
  await api.install(page);
  await signIn(page, 'Viewer (read only)');

  await expect(page.getByRole('link', { name: 'New rule' })).toHaveCount(0);
  await page.getByRole('link', { name: 'Live rule' }).click();
  await expect(page.getByLabel('Rule name')).toBeDisabled();
  await expect(page.getByRole('toolbar').getByRole('button')).toHaveCount(0);
});
