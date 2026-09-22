import { pathToFileURL } from 'node:url';
import { readFile } from 'node:fs/promises';

const packageDirectory = process.env.TOUCHPUCK_PLAYWRIGHT_DIR;
const executablePath = process.env.TOUCHPUCK_BROWSER_PATH;
if (!packageDirectory || !executablePath) throw new Error('Set TOUCHPUCK_PLAYWRIGHT_DIR and TOUCHPUCK_BROWSER_PATH');
const { chromium } = await import(pathToFileURL(`${packageDirectory}/index.mjs`).href);
const browser = await chromium.launch({ headless: true, executablePath });

async function collectErrors(page) {
  const errors = [];
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  page.on('pageerror', error => errors.push(String(error)));
  return errors;
}

const desktop = await browser.newPage({ viewport: { width: 1440, height: 1000 }, serviceWorkers: 'block' });
const desktopErrors = await collectErrors(desktop);
const response = await desktop.goto('http://127.0.0.1:8765/touchpuck/', { waitUntil: 'networkidle', timeout: 30000 });
await desktop.locator('[name="transport"][value="ble"]').check();
await desktop.locator('#mockConnect').click();
const box = await desktop.locator('#outputCanvas').boundingBox();
await desktop.mouse.move(box.x + box.width * 0.35, box.y + box.height * 0.48);
await desktop.mouse.down();
await desktop.mouse.move(box.x + box.width * 0.72, box.y + box.height * 0.28, { steps: 7 });
await desktop.waitForTimeout(550);
await desktop.mouse.up();
await desktop.waitForFunction(() => Number(document.querySelector('#ackCount')?.textContent) >= 2);
await desktop.locator('#bleAcceptance').click();
await desktop.waitForFunction(() => document.querySelector('#acceptanceState')?.dataset.result === 'logic-pass', null, { timeout: 15000 });
const downloadPromise = desktop.waitForEvent('download');
await desktop.locator('#exportAcceptance').click();
const reportDownload = await downloadPromise;
const report = JSON.parse(await readFile(await reportDownload.path(), 'utf8'));
await desktop.screenshot({ path: 'touchpuck-desktop-qa.png', fullPage: true });
const desktopResult = await desktop.evaluate(() => ({
  scrollWidth: document.documentElement.scrollWidth,
  innerWidth: window.innerWidth,
  ackCount: Number(document.querySelector('#ackCount').textContent),
  mapping: document.querySelector('#mappingState').textContent,
  feedback: document.querySelector('#feedbackState').textContent,
  rtt: document.querySelector('#rttValue').textContent,
  mode: document.querySelector('#currentMode').textContent,
  acceptance: document.querySelector('#acceptanceState').textContent,
}));

const mobile = await browser.newPage({ viewport: { width: 412, height: 915 }, isMobile: true, hasTouch: true, deviceScaleFactor: 1, serviceWorkers: 'block' });
const mobileErrors = await collectErrors(mobile);
await mobile.goto('http://127.0.0.1:8765/touchpuck/', { waitUntil: 'networkidle', timeout: 30000 });
await mobile.locator('[name="transport"][value="ble"]').check();
await mobile.locator('.wiring-details summary').click();
await mobile.waitForFunction(() => document.querySelector('.wiring-details img')?.naturalWidth === 1200);
await mobile.screenshot({ path: 'touchpuck-mobile-qa.png', fullPage: true });
const mobileResult = await mobile.evaluate(() => ({
  scrollWidth: document.documentElement.scrollWidth,
  innerWidth: window.innerWidth,
  canvasWidth: document.querySelector('#outputCanvas').getBoundingClientRect().width,
  panelWidth: document.querySelector('.touch-panel').getBoundingClientRect().width,
  wiringWidth: document.querySelector('.wiring-details img').naturalWidth,
}));

async function verifyFeedback(status, expected) {
  const page = await browser.newPage({ viewport: { width: 900, height: 700 }, serviceWorkers: 'block' });
  const errors = await collectErrors(page);
  await page.goto(`http://127.0.0.1:8765/touchpuck/?mockFeedback=${status}`, { waitUntil: 'networkidle', timeout: 30000 });
  await page.locator('[name="transport"][value="ble"]').check();
  await page.locator('#mockConnect').click();
  await page.locator('#bleTest').click();
  await page.waitForFunction(value => document.querySelector('#feedbackState')?.textContent === value, expected);
  await page.close();
  return { status, expected, errors };
}

const physicalFeedback = await verifyFeedback('physical', '物理振动已检测');
const timeoutFeedback = await verifyFeedback('timeout', '未检测到振动');

await browser.close();
const result = { status: response?.status(), desktop: desktopResult, mobile: mobileResult, report: { schema: report.schema, completed: report.summary.completed, mappingMatched: report.summary.mappingMatched, commandCount: report.commands.length }, feedbackScenarios: [physicalFeedback, timeoutFeedback], errors: [...desktopErrors, ...mobileErrors, ...physicalFeedback.errors, ...timeoutFeedback.errors] };
console.log(JSON.stringify(result, null, 2));
if (response?.status() !== 200) throw new Error('Demo did not return HTTP 200');
if (desktopErrors.length || mobileErrors.length) throw new Error(`Browser errors: ${result.errors.join('; ')}`);
if (desktopResult.ackCount < 102 || desktopResult.mapping !== '坐标 ACK 一致' || desktopResult.feedback !== 'GPIO 已执行（无传感器）' || !desktopResult.acceptance.startsWith('映射 100/100')) throw new Error('BLE mock loop was not acknowledged');
if (desktopResult.scrollWidth > desktopResult.innerWidth || mobileResult.scrollWidth > mobileResult.innerWidth) throw new Error('Horizontal overflow detected');
if (mobileResult.wiringWidth !== 1200) throw new Error('Wiring diagram did not load');
if (report.schema !== 'touchpuck-acceptance/v1' || report.summary.completed !== 100 || report.summary.mappingMatched !== 100 || report.commands.length !== 100) throw new Error('Acceptance report is incomplete');
