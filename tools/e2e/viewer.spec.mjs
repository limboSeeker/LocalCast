// E2E for the viewer page against the mock phone. Run: node --test viewer.spec.mjs
// BROWSER=webkit runs the viewer in Playwright WebKit (Safari engine); the mock phone page always runs in Chromium,
// like the real phone's libwebrtc offerer.
import { test, before, after, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium, webkit } from 'playwright';
import { startMock } from './mock-server.mjs';

const OUT = path.join(path.dirname(fileURLToPath(import.meta.url)), 'out');
fs.mkdirSync(OUT, { recursive: true });
const ENGINE = process.env.BROWSER === 'webkit' ? 'webkit' : 'chromium';
const WEBKIT = ENGINE === 'webkit';
let mock, phoneBrowser, browser, phoneCtx, ctx, phone, errors, extraCtx = [];
const post = (p) => fetch(mock.url + p, { method: 'POST' });
const shot = (page, name) => page.screenshot({ path: path.join(OUT, WEBKIT ? name.replace(/\.png$/, '-webkit.png') : name) });

before(async () => {
  mock = await startMock(0);
  phoneBrowser = await chromium.launch({ args: ['--disable-features=WebRtcHideLocalIpsWithMdns', '--autoplay-policy=no-user-gesture-required'] });
  browser = WEBKIT ? await webkit.launch() : phoneBrowser;
});
after(async () => { if (browser !== phoneBrowser) await browser?.close(); await phoneBrowser?.close(); mock?.close(); });
const VIEWPORT = { width: 390, height: 844 };
beforeEach(async () => {
  await ctx?.close();
  if (phoneCtx !== ctx) await phoneCtx?.close();
  for (const c of extraCtx.splice(0)) await c.close();
  await post('/mock/reset');
  // The page picks its language from navigator.languages: the Ukrainian checks run in a uk-UA browser.
  ctx = await browser.newContext({ viewport: VIEWPORT, locale: 'uk-UA' });
  phoneCtx = WEBKIT ? await phoneBrowser.newContext() : ctx;
  errors = [];
  phone = await phoneCtx.newPage();
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
  // WebKit logs the socket the mock just killed as a console error ("The network connection was lost"): expected here.
  assert.deepEqual(errors.filter((e) => !/WebSocket connection to .* failed/.test(e)), []);
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

const css = (page, sel, prop) => page.evaluate(([s, p]) => getComputedStyle(document.querySelector(s))[p], [sel, prop]);
const isIdle = (page) => page.evaluate(() => document.getElementById('stage').classList.contains('idle'));

test('uk: pause -> calm pause screen, no reconnect even without frames; resume -> video again', async () => {
  const v = await viewer('#pin=123456');
  await playing(v);
  await v.evaluate(() => { window.__oldpc = window.__localcast.pc; });
  const pcs = await phone.evaluate(() => window.pcs);
  await post('/mock/pause?on=1');
  await v.waitForFunction(() => window.__localcast.paused);
  assert.ok(await v.locator('#pause').isVisible());
  assert.equal(await v.locator('#pause h2').innerText(), 'Трансляцію призупинено');
  assert.ok(!(await v.locator('#pill').isVisible()), 'no pill over the pause screen');
  assert.ok(!(await v.locator('#card').isVisible()));
  await v.waitForFunction(() => getComputedStyle(document.getElementById('v')).opacity === '0');
  // The mock phone sends no frames at all while paused: well past the stall limit nothing may be torn down.
  await v.waitForTimeout((await v.evaluate(() => window.__localcast.STALL_MS)) + 2500);
  assert.equal(await state(v), 'playing');
  assert.ok(await v.evaluate(() => window.__localcast.pc === window.__oldpc), 'same PeerConnection');
  assert.equal(await phone.evaluate(() => window.pcs), pcs, 'phone made no new PeerConnection');
  assert.ok(await v.locator('#pause').isVisible());
  await shot(v, 'paused.png');
  const f0 = await v.evaluate(() => window.__localcast.stats.framesDecoded);
  await post('/mock/pause?on=0');
  await v.waitForFunction(() => !window.__localcast.paused);
  assert.ok(!(await v.locator('#pause').isVisible()));
  await v.waitForFunction((n) => window.__localcast.stats.framesDecoded > n + 20, f0, { timeout: 10000 });
  assert.equal(await css(v, '#v', 'opacity'), '1');
  assert.ok(await v.evaluate(() => window.__localcast.pc === window.__oldpc), 'still the same PeerConnection');
  assert.equal(await phone.evaluate(() => window.pcs), pcs);
  assert.deepEqual(errors, []);
});

test('en-US: joining during a pause shows the pause screen', async () => {
  await post('/mock/pause?on=1');
  const v = await viewer('#pin=123456', 'en-US');
  await v.waitForFunction(() => window.__localcast.paused && window.__localcast.state === 'playing', null, { timeout: 20000 });
  assert.ok(await v.locator('#pause').isVisible());
  assert.equal(await v.locator('#pause h2').innerText(), 'Casting is paused');
  assert.doesNotMatch(await v.locator('#pause').innerText(), CYRILLIC);
  await post('/mock/pause?on=0');
  await playing(v, 10);
  assert.ok(!(await v.locator('#pause').isVisible()));
  assert.deepEqual(errors, []);
});

test('projector calm: idle hides cursor, buttons and pill; Esc stays calm; contain letterbox', async () => {
  const v = await viewer('#pin=123456');
  await v.setViewportSize({ width: 1280, height: 720 });
  await playing(v);
  assert.ok(!(await v.locator('#stats').isVisible()), 'stats off by default');
  await v.mouse.move(640, 360);
  assert.equal(await isIdle(v), false);
  assert.equal(await css(v, '#bar', 'opacity'), '1');
  await v.waitForFunction(() => document.getElementById('stage').classList.contains('idle'), null, { timeout: 5000 });
  await v.waitForTimeout(400); // fade-out
  assert.equal(await css(v, '#bar', 'opacity'), '0');
  assert.equal(await css(v, '#v', 'cursor'), 'none');
  assert.equal(await css(v, '#stage', 'cursor'), 'none');
  assert.ok(!(await v.locator('#pill').isVisible()) || (await css(v, '#pill', 'opacity')) === '0');
  // Esc (e.g. a clicker or leaving fullscreen) does not bring the controls back.
  await v.keyboard.press('Escape');
  assert.equal(await isIdle(v), true);
  // Picture: black stage, whole viewport, object-fit contain (16:10 tablet on a 16:9 projector -> pillarbox, no crop).
  assert.equal(await css(v, '#v', 'objectFit'), 'contain');
  assert.equal(await css(v, '#stage', 'backgroundColor'), 'rgb(0, 0, 0)');
  const r = await v.evaluate(() => { const b = document.getElementById('v').getBoundingClientRect(); return [b.left, b.top, b.width, b.height]; });
  assert.deepEqual(r, [0, 0, 1280, 720]);
  await shot(v, 'idle-1280.png');
  // Input brings the controls back; S/Esc toggle the stats.
  await v.mouse.move(100, 100);
  assert.equal(await isIdle(v), false);
  await v.keyboard.press('s');
  assert.ok(await v.locator('#stats').isVisible());
  await v.keyboard.press('Escape');
  assert.ok(!(await v.locator('#stats').isVisible()), 'Esc closes the stats');
  assert.deepEqual(errors, []);
});

test('PIN in the link must be exactly 6 digits', async () => {
  const v = await viewer('#pin=1234');
  await v.waitForFunction(() => window.__localcast.state === 'pin');
  assert.ok(await v.locator('#pinform').isVisible());
  const v2 = await viewer('#pin=1234567');
  await v2.waitForFunction(() => window.__localcast.state === 'pin');
  assert.deepEqual(errors, []);
});

test('uk: drop during a pause -> back to the pause screen; stop during a pause -> waiting card, no pause screen', async () => {
  const v = await viewer('#pin=123456');
  await playing(v);
  await post('/mock/pause?on=1');
  await v.waitForFunction(() => window.__localcast.paused);
  await v.evaluate(() => { window.__oldpc = window.__localcast.pc; });
  await post('/mock/drop');
  await v.waitForFunction(() => window.__localcast.pc && window.__localcast.pc !== window.__oldpc && window.__localcast.state === 'playing' && window.__localcast.paused, null, { timeout: 25000 });
  assert.ok(await v.locator('#pause').isVisible());
  // Resumed while the viewer was away: the fresh state{streaming:true} after welcome clears the stale pause.
  await v.evaluate(() => { window.__oldpc = window.__localcast.pc; });
  await post('/mock/drop');
  await post('/mock/pause?on=0');
  await v.waitForFunction(() => window.__localcast.pc && window.__localcast.pc !== window.__oldpc && window.__localcast.state === 'playing' && !window.__localcast.paused, null, { timeout: 25000 });
  assert.ok(!(await v.locator('#pause').isVisible()));
  await post('/mock/pause?on=1');
  await v.waitForFunction(() => window.__localcast.paused);
  await post('/mock/stop');
  await v.waitForFunction(() => window.__localcast.state === 'waiting');
  assert.ok(await v.locator('#card').isVisible());
  assert.ok(!(await v.locator('#pause').isVisible()), 'bye clears the pause screen');
  assert.deepEqual(errors.filter((e) => !/WebSocket connection to .* failed/.test(e)), []);
});

test('projector calm: a reconnecting pill stays visible (not idle), calm again after recovery', async () => {
  const v = await viewer('#pin=123456');
  await playing(v);
  await v.waitForFunction(() => document.getElementById('stage').classList.contains('idle'), null, { timeout: 5000 });
  await post('/mock/drop');
  await v.waitForFunction(() => {
    const p = document.getElementById('pill'), st = document.getElementById('stage');
    return !p.hidden && p.classList.contains('problem') && !st.classList.contains('idle') && getComputedStyle(p).opacity === '1';
  }, null, { timeout: 5000, polling: 10 });
  await v.waitForFunction(() => window.__localcast.state === 'playing' && document.getElementById('pill').hidden, null, { timeout: 25000 });
  await v.waitForFunction(() => document.getElementById('stage').classList.contains('idle'), null, { timeout: 5000 });
  assert.deepEqual(errors.filter((e) => !/WebSocket connection to .* failed/.test(e)), []);
});

// Emulates a background tab: Playwright pages are always visible, so override document.visibilityState and fire the event.
// Returns the page state right after the event (synchronous handlers have run).
const setVisibility = (page, vis) => page.evaluate((v) => {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => v });
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => v === 'hidden' });
  document.dispatchEvent(new Event('visibilitychange'));
  return window.__localcast.state;
}, vis);
const mockState = async () => (await fetch(mock.url + '/mock/state')).json();

test('uk: hidden tab past the threshold leaves (bye), stays quiet, rejoins with the token when shown', async () => {
  const v = await viewer('?hide_ms=1500#pin=123456');
  await playing(v);
  assert.equal(await v.evaluate(() => window.__localcast.HIDE_RELEASE_MS), 1500);
  await v.evaluate(() => { window.__oldpc = window.__localcast.pc; });
  const s0 = await mockState();
  assert.equal(s0.joins, 1);
  await setVisibility(v, 'hidden');
  await v.waitForFunction(() => window.__localcast.released && window.__localcast.state === 'released', null, { timeout: 5000 });
  const s1 = await mockState();
  assert.equal(s1.byes, 1, 'viewer said bye');
  assert.equal(s1.viewer, false, 'mock freed the viewer');
  assert.ok(await v.evaluate(() => !window.__localcast.pc && !window.__localcast.ws));
  // Well past the reconnect backoff and the stall limit: no reconnect while hidden.
  const pcs = await phone.evaluate(() => window.pcs);
  await v.waitForTimeout(6000);
  const s2 = await mockState();
  assert.equal(s2.joins, 1, 'no rejoin while hidden');
  assert.equal(s2.viewer, false);
  assert.equal(await phone.evaluate(() => window.pcs), pcs, 'phone made no new PeerConnection');
  assert.equal(await state(v), 'released');
  // Shown again: the normal connecting card at once, then a fresh connection with the stored token (no PIN form).
  assert.equal(await setVisibility(v, 'visible'), 'connecting');
  assert.equal(await v.locator('#title').innerText(), 'Підключення…');
  await v.waitForFunction(() => window.__localcast.pc && window.__localcast.pc !== window.__oldpc && window.__localcast.state === 'playing'
    && window.__localcast.stats && window.__localcast.stats.framesDecoded > 10, null, { timeout: 20000 });
  assert.ok(!(await v.locator('#pinform').isVisible()));
  const s3 = await mockState();
  assert.equal(s3.joins, 2);
  assert.equal(s3.lastHello, 'token');
  assert.equal(await v.evaluate(() => window.__localcast.released), false);
  assert.deepEqual(errors.filter((e) => !/WebSocket connection to .* failed/.test(e)), []);
});

test('a short hide (below the threshold) keeps the same connection', async () => {
  const v = await viewer('?hide_ms=3000#pin=123456');
  await playing(v);
  await v.evaluate(() => { window.__oldpc = window.__localcast.pc; });
  await setVisibility(v, 'hidden');
  await v.waitForTimeout(1500);
  assert.equal(await setVisibility(v, 'visible'), 'playing');
  await v.waitForTimeout(3000); // past the moment the release timer would have fired
  assert.equal(await state(v), 'playing');
  assert.ok(await v.evaluate(() => window.__localcast.pc === window.__oldpc && !window.__localcast.released));
  const s = await mockState();
  assert.equal(s.joins, 1);
  assert.equal(s.byes, 0);
  assert.equal(s.leaves, 0);
  assert.equal(s.viewer, true);
  assert.deepEqual(errors, []);
});

test('hidden while in picture-in-picture keeps playing; leaving PiP in the background releases', async () => {
  const v = await viewer('?hide_ms=1000#pin=123456');
  await playing(v);
  await v.evaluate(() => Object.defineProperty(document, 'pictureInPictureElement', { configurable: true, get: () => document.getElementById('v') }));
  await setVisibility(v, 'hidden');
  await v.waitForTimeout(2500);
  assert.equal(await state(v), 'playing');
  assert.equal((await mockState()).byes, 0);
  await v.evaluate(() => {
    Object.defineProperty(document, 'pictureInPictureElement', { configurable: true, get: () => null });
    document.getElementById('v').dispatchEvent(new Event('leavepictureinpicture'));
  });
  await v.waitForFunction(() => window.__localcast.released, null, { timeout: 5000 });
  assert.equal((await mockState()).byes, 1);
  await setVisibility(v, 'visible');
  await playing(v, 10);
  assert.deepEqual(errors.filter((e) => !/WebSocket connection to .* failed/.test(e)), []);
});
