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
let mock, browser, ctx, phone, errors, extraCtx = [];
const post = (p) => fetch(mock.url + p, { method: 'POST' });
const shot = (page, name) => page.screenshot({ path: path.join(OUT, name) });

before(async () => {
  mock = await startMock(0);
  browser = await chromium.launch({ args: ['--disable-features=WebRtcHideLocalIpsWithMdns', '--autoplay-policy=no-user-gesture-required'] });
});
after(async () => { await browser?.close(); mock?.close(); });
const VIEWPORT = { width: 390, height: 844 };
beforeEach(async () => {
  await ctx?.close();
  for (const c of extraCtx.splice(0)) await c.close();
  await post('/mock/reset');
  // The page picks its language from navigator.languages: the Ukrainian checks run in a uk-UA browser.
  ctx = await browser.newContext({ viewport: VIEWPORT, locale: 'uk-UA' });
  errors = [];
  phone = await ctx.newPage();
  await phone.goto(mock.url + '/phone');
  await phone.waitForFunction(() => window.phoneReady);
});
// locale: open the viewer in a separate browser context with that language (the phone page stays in ctx).
async function viewer(hash = '', locale) {
  let c = ctx;
  if (locale) { c = await browser.newContext({ viewport: VIEWPORT, locale }); extraCtx.push(c); }
  const page = await c.newPage();
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error') errors.push('console: ' + m.text()); });
  await page.goto(mock.url + '/' + hash);
  return page;
}
const state = (page) => page.evaluate(() => window.__localcast.state);
const CYRILLIC = /[\u0400-\u04FF]/;
const playing = (page, min = 30) => page.waitForFunction(
  (n) => window.__localcast.state === 'playing' && window.__localcast.stats && window.__localcast.stats.framesDecoded > n, min, { timeout: 20000 });

test('PIN from #pin= -> playing, frames grow, hash removed, screenshots', async () => {
  const v = await viewer('#pin=123456');
  await playing(v);
  assert.equal(await v.evaluate(() => location.hash), '');
  assert.equal(await v.evaluate(() => document.documentElement.lang), 'uk');
  const a = await v.evaluate(() => window.__localcast.stats.framesDecoded);
  await v.waitForTimeout(1500);
  const b = await v.evaluate(() => window.__localcast.stats.framesDecoded);
  assert.ok(b > a + 20, `frames grow ${a} -> ${b}`);
  await v.keyboard.press('s');
  await v.waitForTimeout(1500);
  const txt = await v.locator('#stats').innerText();
  assert.match(txt, /FPS/);
  assert.match(txt, /Затримка/);
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

test('uk: PIN form without #pin, wrong PIN', async () => {
  const v = await viewer('');
  await v.waitForFunction(() => window.__localcast.state === 'pin');
  assert.ok(await v.locator('#pinform').isVisible());
  assert.equal(await v.locator('#go').innerText(), 'Підключитися');
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

test('uk: lockout after 5 wrong PINs', async () => {
  const v = await viewer('');
  await v.waitForFunction(() => window.__localcast.state === 'pin');
  for (let i = 0; i < 5; i++) {
    await v.fill('#pin', '111111');
    await v.click('#go');
    await v.waitForFunction((i) => i < 4 ? /Невірний/.test(document.getElementById('err').textContent) : /Забагато спроб/.test(document.getElementById('err').textContent), i);
  }
  assert.ok(await v.locator('#go').isDisabled());
});

test('/mock/drop -> playing again without PIN', async () => {
  const v = await viewer('#pin=123456');
  await playing(v);
  await v.evaluate(() => { window.__oldpc = window.__localcast.pc; });
  await post('/mock/drop');
  await v.waitForFunction(() => window.__localcast.pc && window.__localcast.pc !== window.__oldpc && window.__localcast.state === 'playing' && window.__localcast.stats.framesDecoded > 15, null, { timeout: 25000 });
  assert.ok(!(await v.locator('#pinform').isVisible()));
  assert.deepEqual(errors, []);
});

test('uk: /mock/stop -> waiting, /mock/start -> playing', async () => {
  const v = await viewer('#pin=123456');
  await playing(v);
  await post('/mock/stop');
  await v.waitForFunction(() => window.__localcast.state === 'waiting');
  assert.match(await v.locator('#sub').innerText(), /зупинено/);
  await post('/mock/start');
  await v.waitForFunction(() => window.__localcast.state === 'playing' && window.__localcast.stats.framesDecoded > 10, null, { timeout: 25000 });
  assert.deepEqual(errors, []);
});

test('uk: device at capacity -> waiting, then playing when a slot frees up', async () => {
  await post('/mock/busy?on=1');
  const v = await viewer('#pin=123456');
  await v.waitForFunction(() => window.__localcast.state === 'waiting');
  assert.match(await v.locator('#sub').innerText(), /стільки глядачам, скільки може/);
  await post('/mock/busy?on=0');
  // The page retries 10 s after a busy denial.
  await v.waitForFunction(() => window.__localcast.state === 'playing' && window.__localcast.stats && window.__localcast.stats.framesDecoded > 10, null, { timeout: 25000 });
});

test('uk: not streaming yet -> waiting with retry', async () => {
  await post('/mock/stop');
  const v = await viewer('#pin=123456');
  await v.waitForFunction(() => window.__localcast.state === 'waiting');
  assert.match(await v.locator('#sub').innerText(), /ще не запущено/);
  await post('/mock/start');
  await playing(v, 10);
});

test('en-US: PIN form, wrong PIN and labels in English', async () => {
  const v = await viewer('', 'en-US');
  await v.waitForFunction(() => window.__localcast.state === 'pin');
  assert.equal(await v.evaluate(() => document.documentElement.lang), 'en');
  assert.equal(await v.locator('#title').innerText(), 'Enter PIN');
  assert.equal(await v.locator('#go').innerText(), 'Connect');
  assert.equal(await v.locator('#bi').getAttribute('aria-label'), 'Stats (S)');
  assert.equal(await v.locator('#bf').getAttribute('title'), 'Full screen (F)');
  await v.fill('#pin', '000000');
  await v.click('#go');
  await v.waitForFunction(() => window.__localcast.state === 'denied');
  assert.match(await v.locator('#err').innerText(), /Wrong PIN/);
  assert.doesNotMatch(await v.locator('body').innerText(), CYRILLIC);
  await shot(v, 'pin-en.png');
  await v.fill('#pin', '123456');
  await v.click('#go');
  await playing(v, 10);
  await v.keyboard.press('s');
  await v.waitForTimeout(1500);
  const txt = await v.locator('#stats').innerText();
  assert.match(txt, /Latency/);
  assert.match(txt, /Dropped +\d+ frames?/);
  assert.doesNotMatch(txt, CYRILLIC);
  await shot(v, 'playing-en.png');
  assert.deepEqual(errors, []);
});

test('?lang=uk overrides an English browser, ?lang=en a Ukrainian one', async () => {
  const uk = await viewer('?lang=uk', 'en-US');
  await uk.waitForFunction(() => window.__localcast.state === 'pin');
  assert.equal(await uk.evaluate(() => document.documentElement.lang), 'uk');
  assert.equal(await uk.locator('#title').innerText(), 'Введіть PIN');
  const en = await viewer('?lang=en');
  await en.waitForFunction(() => window.__localcast.state === 'pin');
  assert.equal(await en.evaluate(() => document.documentElement.lang), 'en');
  assert.equal(await en.locator('#title').innerText(), 'Enter PIN');
  assert.deepEqual(errors, []);
});
