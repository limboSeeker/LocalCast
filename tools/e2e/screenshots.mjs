// README screenshots of the viewer page against the mock phone. Run: npm run screenshots
// Writes docs/images/viewer-pin.png, viewer-stream.png (1280x800) and viewer-mobile.png (iPhone 14).
// The "stream" is the mock phone's test pattern (canvas.captureStream), not a real Android screen.
// If ffmpeg is on PATH the PNGs are re-encoded with a 256-colour palette (typically 3-5x smaller).
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { chromium, devices } from 'playwright';
import { startMock } from './mock-server.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const OUT = path.join(ROOT, 'docs/images');
fs.mkdirSync(OUT, { recursive: true });

const DESKTOP = { viewport: { width: 1280, height: 800 }, deviceScaleFactor: 1, locale: 'en-US' };
const MOBILE = { ...devices['iPhone 14'], locale: 'en-US' };

const mock = await startMock(0);
const browser = await chromium.launch({
  args: ['--disable-features=WebRtcHideLocalIpsWithMdns', '--autoplay-policy=no-user-gesture-required'],
});
const post = (p) => fetch(mock.url + p, { method: 'POST' });
const written = [];

// A fresh mock phone (offerer) for every viewer, as in viewer.spec.mjs.
async function phone() {
  await post('/mock/reset');
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  await page.goto(mock.url + '/phone');
  await page.waitForFunction(() => window.phoneReady);
  return ctx;
}
const playing = (page) => page.waitForFunction(
  () => window.__localcast.state === 'playing' && window.__localcast.stats?.framesDecoded > 60, null, { timeout: 20000 });
async function shot(page, name) {
  const file = path.join(OUT, name);
  await page.screenshot({ path: file });
  written.push(file);
}

try {
  // Desktop: PIN form, then the same page while playing, with the stats overlay (S).
  let phoneCtx = await phone();
  let ctx = await browser.newContext(DESKTOP);
  let v = await ctx.newPage();
  await v.goto(mock.url + '/?lang=en');
  await v.waitForFunction(() => window.__localcast.state === 'pin');
  await v.locator('#pin').focus();
  await shot(v, 'viewer-pin.png');
  await v.fill('#pin', '123456');
  await v.click('#go');
  await playing(v);
  await v.keyboard.press('s');
  await v.waitForTimeout(2500); // let the stats overlay fill in
  await shot(v, 'viewer-stream.png');
  await ctx.close();
  await phoneCtx.close();

  // Mobile: QR-code style link with the PIN in the hash.
  phoneCtx = await phone();
  ctx = await browser.newContext(MOBILE);
  v = await ctx.newPage();
  await v.goto(mock.url + '/?lang=en#pin=123456');
  await playing(v);
  await v.waitForTimeout(1000);
  await shot(v, 'viewer-mobile.png');
  await ctx.close();
  await phoneCtx.close();
} finally {
  await browser.close();
  mock.close();
}

let ffmpeg = true;
try { execFileSync('ffmpeg', ['-version'], { stdio: 'ignore' }); } catch { ffmpeg = false; }
for (const file of written) {
  if (ffmpeg) {
    const tmp = file.replace(/\.png$/, '.q.png');
    execFileSync('ffmpeg', ['-loglevel', 'error', '-y', '-i', file, '-vf',
      'split[a][b];[a]palettegen=max_colors=256:stats_mode=full[p];[b][p]paletteuse=dither=none', tmp]);
    if (fs.statSync(tmp).size < fs.statSync(file).size) fs.renameSync(tmp, file); else fs.unlinkSync(tmp);
  }
  console.log(`${path.relative(ROOT, file)}  ${Math.round(fs.statSync(file).size / 1024)} KB`);
}
if (!ffmpeg) console.log('ffmpeg not found: PNGs left unoptimised');
