# LocalCast viewer e2e (без телефона)

Мок телефона (`mock-server.mjs`) віддає `app/src/main/assets/viewer/index.html`, реалізує `/api/info`, `/ws` за PROTOCOL.md
(PIN `123456`, токени, lock після 5 помилок) і сторінку `/phone` (offerer, відео з `canvas.captureStream(60)`).

- Один раз: `cd tools/e2e && npm i && npx playwright install chromium`
- Автотест: `node --test viewer.spec.mjs` (скріншоти в `out/`)
- Вручну: `node mock-server.mjs 8099`, відкрити `http://127.0.0.1:8099/phone`, потім `http://127.0.0.1:8099/#pin=123456`
- Керування: `POST /mock/drop` (рве WS глядача), `/mock/stop` (bye stopped), `/mock/start`, `/mock/reset`; `GET /mock/state`
