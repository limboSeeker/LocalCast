// Live check against a running LocalCast (phone or emulator via `adb forward tcp:8080 tcp:8080`).
// usage: node device-check.mjs <baseUrl> <pin> [seconds]
// Prints JSON stats samples and saves screenshots to out/device-*.png
import { chromium } from 'playwright';
import { mkdirSync } from 'node:fs';

const [, , base = 'http://localhost:8080', pin = '', secArg = '10'] = process.argv;
const seconds = Number(secArg);
mkdirSync(new URL('./out/', import.meta.url), { recursive: true });

const browser = await chromium.launch({
  args: [
    '--disable-features=WebRtcHideLocalIpsWithMdns',
    '--autoplay-policy=no-user-gesture-required',
  ],
});
const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
const consoleErrors = [];
page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
page.on('pageerror', (e) => consoleErrors.push(String(e)));

const t0 = Date.now();
await page.goto(`${base}/#pin=${pin}`);
await page.waitForFunction(() => window.__localcast && window.__localcast.state === 'playing', null, { timeout: 30000 });
const tPlaying = Date.now() - t0;
console.log(JSON.stringify({ event: 'playing', msFromNavigation: tPlaying }));

await page.keyboard.press('s'); // stats overlay
const samples = [];
for (let i = 0; i < seconds; i++) {
  await page.waitForTimeout(1000);
  samples.push(await page.evaluate(() => ({ ...window.__localcast.stats, state: window.__localcast.state })));
}
for (const s of samples) console.log(JSON.stringify(s));
await page.screenshot({ path: new URL('./out/device-playing.png', import.meta.url).pathname });

console.log(JSON.stringify({ event: 'summary', consoleErrors }));
await browser.close();
