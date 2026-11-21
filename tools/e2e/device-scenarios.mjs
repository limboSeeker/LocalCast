// Scenario checks against a live LocalCast (emulator/phone reachable at baseUrl, adb on PATH).
// usage: node device-scenarios.mjs <baseUrl> <pin>
import { chromium } from 'playwright';
import { execFileSync } from 'node:child_process';

const [, , base = 'http://localhost:8080', pin = ''] = process.argv;
const adb = (...a) => execFileSync('adb', a, { encoding: 'utf8' }).trim();
const results = [];
const check = (name, ok, extra = '') => { results.push({ name, ok, extra }); console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`); };

const browser = await chromium.launch({ args: ['--disable-features=WebRtcHideLocalIpsWithMdns', '--autoplay-policy=no-user-gesture-required'] });
// uk-UA: the page follows the browser language and the checks below match Ukrainian texts.
const ctx = () => browser.newContext({ viewport: { width: 1000, height: 700 }, locale: 'uk-UA' });
const st = (p) => p.evaluate(() => window.__localcast && window.__localcast.state);
const stats = (p) => p.evaluate(() => window.__localcast && window.__localcast.stats);
const waitState = (p, s, ms = 20000) => p.waitForFunction((x) => window.__localcast && window.__localcast.state === x, s, { timeout: ms }).then(() => true, () => false);
const text = (p) => p.evaluate(() => document.body.innerText);

// 1. wrong PIN
{
  const c = await ctx(); const p = await c.newPage();
  await p.goto(`${base}/#pin=000000`);
  const ok = await waitState(p, 'denied', 10000);
  check('wrong PIN is rejected', ok && /Невірний PIN/.test(await text(p)));
  await c.close();
}

// 2. correct PIN plays, then a WS drop reconnects without PIN
const c1 = await ctx(); const v1 = await c1.newPage();
await v1.goto(`${base}/#pin=${pin}`);
check('correct PIN plays', await waitState(v1, 'playing'));
await v1.evaluate(() => window.__localcast.ws.close());
await v1.waitForTimeout(300);
const reconnected = await waitState(v1, 'playing', 20000);
const f0 = (await stats(v1))?.framesDecoded ?? 0;
await v1.waitForTimeout(2500);
const f1 = (await stats(v1))?.framesDecoded ?? 0;
check('auto-reconnect after WebSocket drop (token, no PIN)', reconnected && f1 > 0, `framesDecoded ${f0}→${f1}`);

// 3. PeerConnection killed -> page notices, restarts over the live WS, frames flow again
await v1.evaluate(() => window.__localcast.pc && window.__localcast.pc.close());
const noticed = await v1.waitForFunction(() => window.__localcast.state !== 'playing', null, { timeout: 5000 }).then(() => true, () => false);
const recovered = await waitState(v1, 'playing', 20000);
const g0 = (await stats(v1))?.framesDecoded ?? 0;
await v1.waitForTimeout(2500);
const g1 = (await stats(v1))?.framesDecoded ?? 0;
check('auto-recover after PeerConnection loss', noticed && recovered && g1 > g0, `framesDecoded ${g0}→${g1}`);

// 4. rotation changes the stream geometry
const before = await stats(v1);
adb('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0');
adb('shell', 'settings', 'put', 'system', 'user_rotation', '1');
await v1.waitForTimeout(4000);
const rotated = await stats(v1);
adb('shell', 'settings', 'put', 'system', 'user_rotation', '0');
await v1.waitForTimeout(4000);
const back = await stats(v1);
check('rotation → landscape stream, then back', rotated?.width > rotated?.height && back?.height > back?.width,
  `${before?.width}x${before?.height} → ${rotated?.width}x${rotated?.height} → ${back?.width}x${back?.height}`);

// 5. no viewer cap: 4 viewers at once
const extra = [];
for (let i = 0; i < 3; i++) { const c = await ctx(); const p = await c.newPage(); await p.goto(`${base}/#pin=${pin}`); extra.push({ c, p }); }
const allPlay = (await Promise.all(extra.map((e) => waitState(e.p, 'playing')))).every(Boolean);
check('4 viewers play simultaneously (no cap)', allPlay && (await st(v1)) === 'playing');
for (const e of extra) await e.c.close();

await v1.screenshot({ path: new URL('./out/device-scenarios.png', import.meta.url).pathname });
await c1.close();
await browser.close();
const failed = results.filter((r) => !r.ok).length;
console.log(`\n${results.length - failed}/${results.length} passed`);
process.exit(failed ? 1 : 0);
