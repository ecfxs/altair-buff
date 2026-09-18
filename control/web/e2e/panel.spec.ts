import { test, expect, type Page } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';

/**
 * 监控台界面验收（真浏览器）。
 *
 * 分工：`scripts/smoke.sh` 验协议与接口层（curl）；这里验「人能不能用」——
 * 页面能不能渲染、点得动、状态会不会跟着变。
 *
 * 截图落在 `control/data/e2e-shots/`，人眼过一遍就能确认视觉没坏。
 */

const CTRL = join(import.meta.dirname, '..', '..');
const SHOTS = join(CTRL, 'data', 'e2e-shots');
const CREDS = join(CTRL, 'data', 'e2e', 'creds.json');

type Creds = { password: string; token: string; port: number };
const creds: Creds = JSON.parse(readFileSync(CREDS, 'utf8'));

let shotSeq = 0;
/** 存一张带序号的截图，便于按顺序看。 */
async function shot(page: Page, name: string) {
  mkdirSync(SHOTS, { recursive: true });
  await page.screenshot({
    path: join(SHOTS, `${String(++shotSeq).padStart(2, '0')}-${name}.png`),
    fullPage: true,
  });
}

async function login(page: Page) {
  await page.goto('/login');
  await page.getByPlaceholder('面板账号').fill('admin');
  await page.getByPlaceholder('面板口令').fill(creds.password);
  await page.getByRole('button', { name: '登录' }).click();
  await expect(page).toHaveURL(/\/$/, { timeout: 15_000 });
}

test.describe.configure({ mode: 'serial' });

test('1) 未登录会被挡到登录页，且页面真的渲染出来（不是白屏）', async ({ page }) => {
  await page.goto('/');
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole('heading', { name: '阿尔泰挂机 · 监控台' })).toBeVisible();
  await expect(page.getByRole('button', { name: '登录' })).toBeVisible();

  // 白屏兜底：body 要有实际文字，且根节点真的挂了子节点（React 挂载成功）
  const text = (await page.locator('body').innerText()).trim();
  expect(text.length).toBeGreaterThan(20);
  expect(await page.locator('#root > *').count()).toBeGreaterThan(0);

  await shot(page, 'login');
});

test('2) 错误口令给出人话提示', async ({ page }) => {
  await page.goto('/login');
  await page.getByPlaceholder('面板账号').fill('admin');
  await page.getByPlaceholder('面板口令').fill('definitely-wrong');
  await page.getByRole('button', { name: '登录' }).click();
  await expect(page.getByText(/用户名或口令不正确|尝试过频/)).toBeVisible();
  await shot(page, 'login-failed');
});

test('3) 登录后总览渲染出设备卡片与统计条', async ({ page }) => {
  await login(page);

  // 统计条（exact 避免命中卡片里的「挂机中 · 等待」徽章）
  await expect(page.getByText('设备总数', { exact: true })).toBeVisible();
  await expect(page.getByText('挂机中', { exact: true })).toBeVisible();
  await expect(page.getByText('累计轮次', { exact: true })).toBeVisible();

  for (const id of ['fake01', 'fake02', 'fake03']) {
    await expect(page.getByTestId(`device-${id}`)).toBeVisible();
  }

  const card = page.getByTestId('device-fake01');
  await expect(card.getByText('距下次补 BUFF')).toBeVisible();
  // 紧凑卡片：BUFF 用「点点 + 计数」表示，不再是每条一个 chip
  const buffSummary = card.getByTestId('buff-summary');
  await expect(buffSummary).toBeVisible();
  await expect(buffSummary).toContainText('BUFF');
  await expect(buffSummary).toContainText(/\d\/\d/);
  await expect(card.getByText(/前台/)).toBeVisible();
  await expect(card.getByText(/门禁/)).toBeVisible();
  await expect(card.getByText('com.nexon.mod').first()).toBeVisible();

  // 机型 / Android / 版本 / 心跳都应从卡片上移走（只在详情页出现）
  await expect(card.getByText('Android')).toHaveCount(0);
  await expect(card.getByText('心跳')).toHaveCount(0);
  await expect(card.getByText('未知机型')).toHaveCount(0);

  // 实时/轮询指示必须有一个
  await expect(page.getByText(/实时|轮询/).first()).toBeVisible();

  // 样式真的生效：设备 ID 用等宽字体；页面是深色主题（不是白底）
  const font = await card
    .locator('.font-mono')
    .first()
    .evaluate((el) => getComputedStyle(el).fontFamily);
  expect(font.toLowerCase()).toContain('mono');
  const bg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
  expect(bg).not.toBe('rgb(255, 255, 255)');

  await shot(page, 'dashboard');
});

test('4) 点「停止」能下发，且设备状态真的跟着变', async ({ page }) => {
  await login(page);
  const card = page.getByTestId('device-fake01');

  await card.getByRole('button', { name: '停止' }).click();
  await expect(page.getByText(/已下发|下发失败/).first()).toBeVisible({ timeout: 15_000 });
  await shot(page, 'after-stop-command');

  // 假设备在下一个上报周期（5s）执行，随后卡片显示「已停止」
  await expect(card.getByText('已停止')).toBeVisible({ timeout: 30_000 });
  await shot(page, 'device-stopped');

  // 再启动回来，免得影响后续用例
  await card.getByRole('button', { name: '启动' }).click();
  await expect(card.getByText(/挂机中|正在补 BUFF/)).toBeVisible({ timeout: 30_000 });
});

test('5) 日志抽屉能打开并显示设备日志', async ({ page }) => {
  await login(page);
  await page.getByTestId('device-fake01').getByRole('button', { name: '日志' }).click();
  await expect(page.getByText(/· 日志/).first()).toBeVisible();
  await expect(page.getByText(/引擎心跳|前台应用/).first()).toBeVisible();
  await shot(page, 'log-drawer');
});

test('6) 配置下发：改 BUFF2 时长后 revision 变化并写入历史', async ({ page }) => {
  await login(page);
  await page.getByRole('link', { name: '配置' }).click();
  await expect(page.getByRole('heading', { name: '参数下发' })).toBeVisible();

  const pkg = page.getByPlaceholder(/com\.nexon/).first();
  if (await pkg.count()) await pkg.fill('com.nexon.mod');

  // BUFF 是 3 行，每行一个数字输入框（持续时间）
  const durInputs = page.locator('input[type=number]');
  expect(await durInputs.count()).toBeGreaterThanOrEqual(3);
  const before = await durInputs.nth(1).inputValue();
  await durInputs.nth(1).fill(before === '9' ? '11' : '9');
  await shot(page, 'config-before-submit');

  await page.getByRole('button', { name: /下发配置/ }).click();
  await expect(page.getByText(/已下发 revision=/)).toBeVisible({ timeout: 15_000 });
  await shot(page, 'config-after-submit');

  await expect(page.getByText('历史版本', { exact: false }).first()).toBeVisible();
});

test('7) 设置页能拿到设备 Token（替代旧版往 HTML 注入 Token）', async ({ page }) => {
  await login(page);
  await page.getByRole('link', { name: '设置' }).click();
  await expect(page.getByText('设备 Token', { exact: false }).first()).toBeVisible();
  await expect(page.getByText(creds.token.slice(0, 12), { exact: false }).first()).toBeVisible();
  await shot(page, 'settings');
});

test('8) 审计页能看到刚才的下发与启停记录', async ({ page }) => {
  await login(page);
  await page.getByRole('link', { name: '审计' }).click();
  await expect(page.getByText(/device\.(start|stop)|config\.put/).first()).toBeVisible({
    timeout: 15_000,
  });
  await shot(page, 'audit');
});

test('9) 登出后回到登录页，且受保护页面不可达', async ({ page }) => {
  await login(page);
  await page.getByRole('link', { name: '设置' }).click();
  // 顶部导航和设置页里各有一个「登出」，取设置页正文里那个
  await page.getByRole('main').getByRole('button', { name: '登出' }).click();
  await expect(page).toHaveURL(/\/login$/, { timeout: 15_000 });
  await page.goto('/');
  await expect(page).toHaveURL(/\/login$/);
  await shot(page, 'after-logout');
});

test('10) 视觉主题真的生效：设计 token、栅格布局、深浅色都不靠肉眼', async ({ page }) => {
  await login(page);

  // ★ 必须先等卡片渲染出来：裸 evaluate 不等 DOM，
  //   整套一起跑时设备列表可能还没到，querySelector 取到 null，断言就会随机失败（踩过）。
  await expect(page.getByTestId('device-fake01')).toBeVisible();

  // 这套断言是为了替代「人眼看截图」——本环境里没法自动读图，
  // 于是把视觉规范变成可计算的值：颜色直接比对设计 token。
  const theme = await page.evaluate(() => {
    const cs = (el: Element | null) => (el ? getComputedStyle(el) : null);
    const body = cs(document.body);
    const card = document.querySelector('[data-testid^="device-"]');
    const cardCs = cs(card);
    const title = document.querySelector('h1');
    const ids = [...document.querySelectorAll('[data-testid^="device-"]')].map((el) => {
      const r = el.getBoundingClientRect();
      return { top: Math.round(r.top), left: Math.round(r.left), w: Math.round(r.width), h: Math.round(r.height) };
    });
    return {
      bodyBg: body?.backgroundColor,
      bodyColor: body?.color,
      cardBg: cardCs?.backgroundColor,
      cardBorder: cardCs?.borderTopColor,
      titleSize: title ? cs(title)?.fontSize : null,
      cards: ids,
    };
  });

  // 调色板（与详细设计 / 旧面板一致）
  expect(theme.bodyBg).toBe('rgb(13, 16, 20)'); // #0d1014 页面底
  expect(theme.bodyColor).toBe('rgb(216, 221, 227)'); // #d8dde3 主文字
  expect(theme.cardBg).toBe('rgb(20, 25, 32)'); // #141920 卡片底
  // 在线卡片的边框是「正常」色 #2c6b41，而不是默认边框 #262d36
  expect(theme.cardBorder).toBe('rgb(44, 107, 65)');
  expect(theme.titleSize).toBe('16px');

  // 栅格真的生效：至少两张卡片在同一行（top 相同），且卡片有实际尺寸
  expect(theme.cards.length).toBeGreaterThanOrEqual(3);
  for (const c of theme.cards) {
    expect(c.w).toBeGreaterThan(280);
    expect(c.h).toBeGreaterThan(200);
  }
  const sameRow = theme.cards.filter((c) => c.top === theme.cards[0].top).length;
  expect(sameRow).toBeGreaterThanOrEqual(2);

  await shot(page, 'theme-assertions');
});

test('11) 设置页改动真的落库：日志保留期保存后由服务端确认', async ({ page }) => {
  await login(page);
  await page.getByRole('link', { name: '设置' }).click();

  // 注意：locator.count() 不会自动等待 —— 必须先用可断言的等待把页面顶出来，
  // 否则读到 0 个元素就断言失败（这个坑在本次开发里踩了两次）。
  await expect(page.getByText('设备 Token', { exact: false }).first()).toBeVisible();

  const field = page.getByLabel(/日志保留天数/);
  await expect(field).toBeVisible();
  await expect(field).toHaveValue('7'); // 默认 7 天：日志比曲线数据占地方，单独算
  await field.fill('5');

  await page.getByRole('button', { name: /保存设置/ }).click();
  await expect(page.getByText('设置已保存')).toBeVisible({ timeout: 15_000 });

  // 用同一个浏览器会话的请求读回来核对（page.request 共享 Cookie）
  const res = await page.request.get('/api/v1/panel/settings');
  expect(res.ok()).toBeTruthy();
  const body = (await res.json()) as { logRetentionDays: number; defaultReportIntervalMs: number };
  expect(body.logRetentionDays).toBe(5);
  expect(body.defaultReportIntervalMs).toBeGreaterThanOrEqual(15_000);

  // 刷新后仍是 5，说明真落库了，而不是只改了前端 state
  await page.reload();
  await expect(page.getByLabel(/日志保留天数/)).toHaveValue('5');

  await shot(page, 'settings-saved');
});

test('12) 卡片「编辑」弹窗：改备注 + 拨自动补 BUFF 开关，且真的落库', async ({ page }) => {
  await login(page);
  const card = page.getByTestId('device-fake01');
  await expect(card).toBeVisible();

  // 卡片应该够紧凑：这是「卡片尽量紧凑」这条需求的可回归断言
  const box = await card.boundingBox();
  expect(box, '卡片应有可见尺寸').toBeTruthy();
  expect(box!.height, '卡片高度应 ≤ 300px（机型/心跳等已移到详情页）').toBeLessThan(300);
  expect(box!.height).toBeGreaterThan(120);

  await card.getByRole('button', { name: '编辑' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();
  await expect(dialog.getByText('自动补 BUFF')).toBeVisible();

  // 备注 + BUFF2 开关一起改
  const note = 'E2E 备注 主教号';
  await dialog.getByPlaceholder('给这台机器起个记得住的名字').fill(note);
  const buff2 = dialog.getByTestId('buff-toggle-2');
  await buff2.locator('input[type=checkbox]').check();
  // 顺便勾上「加完 BUFF 自动进自由市场」（回城模式）
  const fm = dialog.getByTestId('auto-free-market').locator('input[type=checkbox]');
  await expect(fm).not.toBeChecked();
  await fm.check();
  await shot(page, 'edit-modal');

  await dialog.getByRole('button', { name: '保存' }).click();
  await expect(page.getByText('已保存')).toBeVisible({ timeout: 15_000 });

  // 弹窗关掉后，备注应该出现在卡片上
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByTestId('device-fake01').getByText(note)).toBeVisible();

  // 从服务端核对：备注在设备上，BUFF2 在设备级配置里
  const devices = await (await page.request.get('/api/v1/panel/devices')).json();
  const d = devices.devices.find((x: { id: string }) => x.id === 'fake01');
  expect(d?.notes, '备注应存到设备上').toBe(note);

  const cfg = await (await page.request.get('/api/v1/panel/configs/fake01')).json();
  expect(cfg.config.buff[1].enabled, 'BUFF2 应已启用').toBe(true);
  expect(cfg.config.autoFreeMarket, '回城模式开关应已落库').toBe(true);

  // 备注不该影响配置 revision 之外的语义：再打开弹窗应回显
  await page.getByTestId('device-fake01').getByRole('button', { name: '编辑' }).click();
  await expect(page.getByRole('dialog').getByPlaceholder('给这台机器起个记得住的名字')).toHaveValue(note);
  await expect(page.getByTestId('buff-toggle-2').locator('input[type=checkbox]')).toBeChecked();
  await expect(page.getByTestId('auto-free-market').locator('input[type=checkbox]')).toBeChecked();
  await page.getByRole('dialog').getByRole('button', { name: '取消' }).click();
});

test('13) 卡片上移走的信息，在详情页都能看到', async ({ page }) => {
  await login(page);
  await page.getByTestId('device-fake01').getByRole('link').first().click();
  await expect(page).toHaveURL(/\/devices\/fake01$/);

  // 存在性断言，一律 .first()：详情页里同一个词（心跳/机型…）会在多处出现，
  // 不加会把测试变成对页面结构的偶然依赖。
  // 机型 / Android / 版本
  await expect(page.getByText('机型').first()).toBeVisible();
  await expect(page.getByText(/Android/).first()).toBeVisible();
  // 心跳（电量 / 温度）
  await expect(page.getByText('心跳').first()).toBeVisible();
  // 协议版本与配置同步状态
  await expect(page.getByText('协议版本').first()).toBeVisible();
  await expect(page.getByText(/配置已同步|配置未生效/).first()).toBeVisible();
  // 输入方式与上次结果
  await expect(page.getByText('输入方式').first()).toBeVisible();
  await expect(page.getByText('上次结果').first()).toBeVisible();
  // 备注也在详情页可见，并且能就地编辑（复用同一个弹窗）
  await expect(page.getByText('E2E 备注 主教号').first()).toBeVisible();
  await page.getByRole('main').getByRole('button', { name: '编辑' }).first().click();
  await expect(page.getByRole('dialog').getByPlaceholder('给这台机器起个记得住的名字')).toHaveValue(
    'E2E 备注 主教号',
  );
  await page.getByRole('dialog').getByRole('button', { name: '取消' }).click();

  await shot(page, 'detail-after-compact');
});
