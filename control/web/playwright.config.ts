import { defineConfig, devices } from '@playwright/test';
import { join } from 'node:path';

/**
 * E2E 配置：真浏览器跑监控台的关键路径并截图。
 *
 * 与 smoke.sh 的分工：smoke 验协议与接口（curl，快），这里验界面能不能用。
 * 之前用 Chrome 无头手搓截图失败（受限环境里 Chrome 起不来），
 * 改用 Playwright 自带的 headless shell —— 它默认不带 Chrome 那套 sandbox 依赖，能起来。
 */
const CTRL = join(import.meta.dirname, '..');

export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 15_000 },
  // 全部测试共用一个服务实例与一份数据，串行跑，避免互相踩状态
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: `http://127.0.0.1:${process.env.E2E_PORT ?? '8891'}`,
    viewport: { width: 1440, height: 1000 },
    deviceScaleFactor: 2,
    trace: 'off',
    screenshot: 'off', // 截图由测试内部显式控制，便于按语义命名
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'bash scripts/e2e-server.sh',
    cwd: CTRL,
    url: `http://127.0.0.1:${process.env.E2E_PORT ?? '8891'}/healthz`,
    reuseExistingServer: false,
    timeout: 120_000,
    stdout: 'pipe',
    stderr: 'pipe',
  },
});
