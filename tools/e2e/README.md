# LocalCast viewer e2e (no phone needed)

The phone mock (`mock-server.mjs`) serves `app/src/main/assets/viewer/index.html` and implements `/api/info` and `/ws` per PROTOCOL.md
(PIN `123456`, tokens, lockout after 5 failures), plus a `/phone` page (offerer, video from `canvas.captureStream(60)`).

- Once: `cd tools/e2e && npm ci && npx playwright install chromium webkit`
- Automated: `node --test viewer.spec.mjs` or `npm test` (Chromium; screenshots in `out/`)
- Safari engine: `BROWSER=webkit node --test viewer.spec.mjs` or `npm run test:webkit` — the viewer runs in Playwright WebKit,
  the mock phone stays in Chromium (like the phone's libwebrtc); screenshots get a `-webkit` suffix
- Manual: `node mock-server.mjs 8099`, open `http://127.0.0.1:8099/phone`, then `http://127.0.0.1:8099/#pin=123456` (add `?lang=en` or `?lang=uk` before `#` to force a language)
- Controls: `POST /mock/drop` (drops the viewer's WS), `/mock/stop` (bye stopped), `/mock/start`, `/mock/pause?on=1|0`
  (`state{streaming:false|true}`; the mock phone paints one black frame and then sends nothing until resumed), `/mock/busy?on=1|0`,
  `/mock/reset`; `GET /mock/state` (also `joins`, `byes`, `leaves` — viewer WS closes, `restarts` — `restart` messages,
  `lastHello`: `pin`|`token`)
- Hidden tabs: tests override `document.visibilityState` and fire `visibilitychange`; `?hide_ms=1500` before `#` shortens
  the 8 s release threshold (PROTOCOL.md, "Hidden tabs"). Fullscreen is emulated the same way (`fullscreenElement` +
  `fullscreenchange`); `?keep=1` / `#keep=1` disables the release
- Latency catch-up: `window.__localcast.debugStats({jitterBufferMs, latencyMs, latencyEstimated})` feeds a sample into
  the same decision as the 1 s stats poll (`catchUps`, `catchUpRun` show the result; `shouldCatchUp(history, now, lastAt, cfg)`
  is the pure decision). Samples are fed synchronously so real polls cannot interleave; `?catchup_jb=`, `?catchup_lat=`,
  `?catchup_ms=` override 400 ms / 800 ms / 60 s
- Multipath: the main listener is the device's `wifi` address; `POST /mock/usb?up=1` starts a second listener (another
  port on 127.0.0.1, same app) and lists it as `usb` with its own `port`; `up=0` unplugs it (`mode=close` closes its
  sockets, `mode=blackhole` leaves them open but silent and drops the phone's PeerConnection, like a pulled cable;
  `listed=1` keeps the dead address listed, `announce=0` skips the `addresses` message). A `vpn` entry on a dead port is
  always listed. `GET /mock/state` adds `viewerVia` (`main`|`usb`), `usbPort`, `health` (probe hits per listener).
  `?probe_ms=` / `?switch_hold_ms=` shorten 3 s / 10 s; `window.__localcast.curHost`, `pathHosts`, `pathSwitches`, `probes`
- Jitter buffer: `window.__localcast.debugJitter({rttMs, packetsLost, framesDropped})` feeds the adaptive decision
  (`jitterTarget`, `receiverJitterTarget`; pure `jbDecide(state, sample, now, cfg)`); `?jb_calm_ms=` / `?jb_step_ms=`
  shorten 20 s / 5 s

Languages: the viewer page follows `navigator.languages` (`uk*` → Ukrainian, otherwise English). Most checks run in a `uk-UA` browser context and match Ukrainian texts; the `en-US` tests check the English PIN form, the wrong-PIN error and the stats overlay, and `?lang=` overrides.
