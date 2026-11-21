// E2E for the viewer page against the mock phone. Run: node --test viewer.spec.mjs
import { test, before, after, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';
import { startMock } from './mock-server.mjs';

const OUT = path.join(path.dirname(fileURLToPath(import.meta.url)), 'out');
fs.mkdirSync(OUT, { recursive: true });
let mock, browser, ctx, phone, errors;
const post = (p) => fetch(mock.url + p, { method: 'POST' });
const shot = (page, name) => page.screenshot({ path: path.join(OUT, name) });

before(async () => {
  mock = await startMock(0);
  browser = await chromium.launch({ args: ['--disable-features=WebRtcHideLocalIpsWithMdns', '--autoplay-policy=no-user-gesture-required'] });
});
after(async () => { await browser?.close(); mock?.close(); });
beforeEach(async () => {
  await ctx?.close();
  await post('/mock/reset');
  ctx = await browser.newContext({ viewport: { width: 390, height: 844 } });
  errors = [];
  phone = await ctx.newPage();
  await phone.goto(mock.url + '/phone');
  await phone.waitForFunction(() => window.phoneReady);
});
async function viewer(hash = '') {
  const page = await ctx.newPage();
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error') errors.push('console: ' + m.text()); });
  await page.goto(mock.url + '/' + hash);
  return page;
}
const state = (page) => page.evaluate(() => window.__localcast.state);
const playing = (page, min = 30) => page.waitForFunction(
  (n) => window.__localcast.state === 'playing' && window.__localcast.stats && window.__localcast.stats.framesDecoded > n, min, { timeout: 20000 });

test('PIN из #pin= -> playing, кадры растут, хэш убран, скриншоты', async () => {
  const v = await viewer('#pin=123456');
  await playing(v);
  assert.equal(await v.evaluate(() => location.hash), '');
  const a = await v.evaluate(() => window.__localcast.stats.framesDecoded);
  await v.waitForTimeout(1500);
  const b = await v.evaluate(() => window.__localcast.stats.framesDecoded);
  assert.ok(b > a + 20, `frames grow ${a} -> ${b}`);
  await v.keyboard.press('s');
  await v.waitForTimeout(1500);
  const txt = await v.locator('#stats').innerText();
  assert.match(txt, /FPS/);
  await shot(v, 'playing-mobile.png');
  await v.setViewportSize({ width: 1280, height: 800 });
  await v.waitForTimeout(500);
  await shot(v, 'playing.png');
  console.log('stats:', JSON.stringify(await v.evaluate(() => window.__localcast.stats)));
  await v.waitForTimeout(2200);
  const ms = await (await fetch(mock.url + '/mock/state')).json();
  assert.ok(ms.reports >= 1, 'report received');
  console.log('report:', JSON.stringify(ms.lastReport));
  assert.deepEqual(errors, []);
});

test('форма PIN без #pin и неверный PIN', async () => {
  const v = await viewer('');
  await v.waitForFunction(() => window.__localcast.state === 'pin');
  assert.ok(await v.locator('#pinform').isVisible());
  await v.fill('#pin', '000000');
  await v.click('#go');
  await v.waitForFunction(() => window.__localcast.state === 'denied');
  assert.match(await v.locator('#err').innerText(), /Невірний PIN/);
  assert.ok(await v.locator('#pinform').isVisible());
  await shot(v, 'pin.png');
  await v.fill('#pin', '123456');
  await v.click('#go');
  await playing(v, 10);
  assert.deepEqual(errors, []);
});

test('блокировка после 5 неверных PIN', async () => {
  const v = await viewer('');
  await v.waitForFunction(() => window.__localcast.state === 'pin');
  for (let i = 0; i < 5; i++) {
    await v.fill('#pin', '111111');
    await v.click('#go');
    await v.waitForFunction((i) => i < 4 ? /Невірний/.test(document.getElementById('err').textContent) : /Забагато спроб/.test(document.getElementById('err').textContent), i);
  }
  assert.ok(await v.locator('#go').isDisabled());
});

test('/mock/drop -> снова playing без PIN', async () => {
  const v = await viewer('#pin=123456');
  await playing(v);
  await v.evaluate(() => { window.__oldpc = window.__localcast.pc; });
  await post('/mock/drop');
  await v.waitForFunction(() => window.__localcast.pc && window.__localcast.pc !== window.__oldpc && window.__localcast.state === 'playing' && window.__localcast.stats.framesDecoded > 15, null, { timeout: 25000 });
  assert.ok(!(await v.locator('#pinform').isVisible()));
  assert.deepEqual(errors, []);
});

test('/mock/stop -> ожидание, /mock/start -> playing', async () => {
  const v = await viewer('#pin=123456');
  await playing(v);
  await post('/mock/stop');
  await v.waitForFunction(() => window.__localcast.state === 'waiting');
  assert.match(await v.locator('#sub').innerText(), /зупинено/);
  await post('/mock/start');
  await v.waitForFunction(() => window.__localcast.state === 'playing' && window.__localcast.stats.framesDecoded > 10, null, { timeout: 25000 });
  assert.deepEqual(errors, []);
});

test('не запущена трансляция -> waiting с повтором', async () => {
  await post('/mock/stop');
  const v = await viewer('#pin=123456');
  await v.waitForFunction(() => window.__localcast.state === 'waiting');
  assert.match(await v.locator('#sub').innerText(), /ще не запущено/);
  await post('/mock/start');
  await playing(v, 10);
});
