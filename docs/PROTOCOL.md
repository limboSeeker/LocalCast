# LocalCast — протокол сигналізації (v1)

Телефон — HTTP/WebSocket-сервер і WebRTC **offerer** (тільки відправляє відео). Браузер — answerer (тільки приймає).
Kotlin-джерело істини: `app/src/main/java/com/localcast/app/server/Protocol.kt`. Тримати синхронно.

## HTTP
| Метод | Шлях | Відповідь |
|---|---|---|
| GET | `/` | `viewer/index.html` (text/html; utf-8, `Cache-Control: no-store`) |
| GET | `/api/info` | `{"name":"LocalCast","pinRequired":true,"streaming":true,"protocol":1}` |
| GET | `/health` | `ok` |
| WS | `/ws` | сигналізація, один JSON-об'єкт на текстовий кадр, поле-дискримінатор `type` |

QR/посилання: `http://<ip>:<port>/#pin=123456` — PIN у фрагменті (не потрапляє в HTTP-запит). Сторінка читає його і прибирає з адресного рядка.

## WebSocket: повідомлення

Браузер → телефон:
```json
{"type":"hello","pin":"123456","userAgent":"..."}      // перший вхід
{"type":"hello","token":"<token>","userAgent":"..."}   // перепідключення (без PIN)
{"type":"answer","sdp":"v=0..."}
{"type":"ice","candidate":{"candidate":"candidate:...","sdpMid":"0","sdpMLineIndex":0}}
{"type":"restart"}                                     // ICE впав — потрібен новий PeerConnection
{"type":"report","stats":{"fps":59.8,"width":1080,"height":2400,"bitrateKbps":8200,
  "latencyMs":74,"latencyEstimated":false,"rttMs":4,"jitterBufferMs":12,"decodeMs":3,"framesDropped":0,"packetsLost":0,"codec":"H264"}}
{"type":"bye"}
```
Усі поля `stats` необов'язкові. `report` — кожні ~2 с. `latencyEstimated=true` — затримка оцінена зі складових (захоплення + кодування + черга відправки + RTT/2 + джитер-буфер + декодування + показ), а не виміряна.

Телефон → браузер:
```json
{"type":"welcome","token":"<token>","viewerId":"v3","protocol":1}
{"type":"denied","reason":"pin"}                      // невірний PIN
{"type":"denied","reason":"locked","retryAfterSec":60} // забагато спроб з цієї IP
{"type":"denied","reason":"token"}                    // токен невідомий/прострочений → показати форму PIN
{"type":"denied","reason":"full"}                     // ліміт глядачів (за замовчуванням ліміту немає)
{"type":"denied","reason":"not_streaming"}            // сервер працює, трансляції немає → чекати й повторювати
{"type":"denied","reason":"bad_request"}
{"type":"offer","sdp":"v=0..."}
{"type":"ice","candidate":{...}}
{"type":"state","streaming":true,"width":1080,"height":2400,"fps":60}
{"type":"sender","encoder":"c2.qti.avc.encoder","hardware":true,"encodeMs":3.1,"sendDelayMs":0.8,"fps":59.7} // кожні ~2 с
{"type":"bye","reason":"stopped"}                     // трансляцію зупинено на телефоні
```
Після `denied` сервер закриває сокет.

## Послідовність
1. Сторінка: `GET /api/info` → якщо `pinRequired` і немає PIN (з `#pin=`) і немає токена (sessionStorage) → форма PIN.
2. `WS /ws` → `hello`. Перше повідомлення має прийти за 10 с.
3. `welcome` → браузер зберігає `token` у sessionStorage.
4. Телефон створює PeerConnection → `offer` → браузер `answer`. Обидва боки шлють `ice` (trickle). Віддалені кандидати до `setRemoteDescription` браузер ставить у чергу.
5. Медіа: DTLS-SRTP, H.264 (фолбек VP8). `iceServers: []` — лише локальна мережа.
6. `state` — при зміні орієнтації/налаштувань.

## Перепідключення (браузер)
- WS закрився або PeerConnection `failed` / `disconnected` > 3 с → новий WS з `hello{token}` (або `restart`, якщо WS живий).
- Паузи: 0.5 → 1 → 2 → 4 → 5 с (далі кожні 5 с), скидаються після успішного відео.
- `not_streaming` → повтор кожні 2 с; `bye{stopped}` → повтор кожні 3 с (трансляцію можуть знову увімкнути).
- `token` → видалити токен, показати форму PIN (якщо `pinRequired`), інакше одразу `hello{}`.
- Новий `hello` з токеном, який уже має активне з'єднання, замінює старе (старе закривається сервером).

## Безпека
- PIN 6 цифр; 5 невірних спроб з однієї IP → блок 60 с; порівняння в константний час.
- Токен 128 біт, живе до зміни PIN («Новий PIN») або перезапуску застосунку. PIN і токени переживають зупинку/запуск трансляції — глядачі повертаються самі.
- WebSocket з чужим `Origin` (сторінка іншого сайту) відхиляється — захист від cross-site WebSocket hijacking.
- Увімкнення «Вимагати PIN» під час трансляції відкликає токени й від'єднує глядачів (`bye{pin_changed}`).
- Медіа шифрується DTLS-SRTP (обов'язково у WebRTC). Сигналізація — HTTP у локальній мережі.
