import { defineConfig, devices } from '@playwright/test';

/**
 * E2E tests run the production build against a stateful fake of the backend API (e2e/fakeApi.ts),
 * so they're fast and need no MongoDB. Point E2E_BASE_URL at a running stack (backend + `npm run
 * dev`) to use the real backend instead; the fake is then skipped.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['junit', { outputFile: 'test-results/junit.xml' }], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:4173',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: process.env.E2E_BASE_URL
    ? undefined
    : { command: 'npm run build && npm run preview', url: 'http://localhost:4173', reuseExistingServer: !process.env.CI, timeout: 120_000 },
});
