# LocalCast viewer e2e (no phone needed)

The phone mock (`mock-server.mjs`) serves `app/src/main/assets/viewer/index.html` and implements `/api/info` and `/ws` per PROTOCOL.md
(PIN `123456`, tokens, lockout after 5 failures), plus a `/phone` page (offerer, video from `canvas.captureStream(60)`).

- Once: `cd tools/e2e && npm ci && npx playwright install chromium`
- Automated: `node --test viewer.spec.mjs` (screenshots in `out/`)
- Manual: `node mock-server.mjs 8099`, open `http://127.0.0.1:8099/phone`, then `http://127.0.0.1:8099/#pin=123456` (add `?lang=en` or `?lang=uk` before `#` to force a language)
- Controls: `POST /mock/drop` (drops the viewer's WS), `/mock/stop` (bye stopped), `/mock/start`, `/mock/reset`; `GET /mock/state`

Languages: the viewer page follows `navigator.languages` (`uk*` → Ukrainian, otherwise English). Most checks run in a `uk-UA` browser context and match Ukrainian texts; the `en-US` tests check the English PIN form, the wrong-PIN error and the stats overlay, and `?lang=` overrides.
