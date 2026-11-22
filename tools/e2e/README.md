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
  `/mock/reset`; `GET /mock/state` (also `joins`, `byes`, `leaves` — viewer WS closes, `lastHello`: `pin`|`token`)
- Hidden tabs: tests override `document.visibilityState` and fire `visibilitychange`; `?hide_ms=1500` before `#` shortens
  the 8 s release threshold (PROTOCOL.md, "Hidden tabs")

Languages: the viewer page follows `navigator.languages` (`uk*` → Ukrainian, otherwise English). Most checks run in a `uk-UA` browser context and match Ukrainian texts; the `en-US` tests check the English PIN form, the wrong-PIN error and the stats overlay, and `?lang=` overrides.
