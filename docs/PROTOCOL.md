# LocalCast — signaling protocol (v1)

The phone is the HTTP/WebSocket server and the WebRTC **offerer** (sends video only). The browser is the answerer (receives only).
Kotlin source of truth: `app/src/main/java/com/localcast/app/server/Protocol.kt`. Keep the two in sync.

## HTTP
| Method | Path | Response |
|---|---|---|
| GET | `/` | `viewer/index.html` (text/html; utf-8, `Cache-Control: no-store`) |
| GET | `/api/info` | `{"name":"LocalCast","pinRequired":true,"streaming":true,"protocol":1}` |
| GET | `/health` | `ok` |
| WS | `/ws` | signaling, one JSON object per text frame, discriminator field `type` |

QR/link: `http://<ip>:<port>/#pin=123456` (exactly 6 digits; anything else shows the PIN form). The PIN is in the fragment (never part of an HTTP request). The page reads it and removes it from the address bar.
Viewer page language: `?lang=en` or `?lang=uk` (e.g. `http://<ip>:<port>/?lang=en#pin=123456`); without it the page follows the browser language. The query string is ignored by the server.

## WebSocket messages

Browser → phone:
```json
{"type":"hello","pin":"123456","userAgent":"..."}      // first join
{"type":"hello","token":"<token>","userAgent":"..."}   // reconnect (no PIN)
{"type":"answer","sdp":"v=0..."}
{"type":"ice","candidate":{"candidate":"candidate:...","sdpMid":"0","sdpMLineIndex":0}}
{"type":"restart"}                                     // ICE failed: a new PeerConnection is needed
{"type":"report","stats":{"fps":59.8,"width":1080,"height":2400,"bitrateKbps":8200,
  "latencyMs":74,"latencyEstimated":false,"rttMs":4,"jitterBufferMs":12,"decodeMs":3,"framesDropped":0,"packetsLost":0,"codec":"H264"}}
{"type":"bye"}
```
All `stats` fields are optional. `report` is sent every ~2 s. `latencyEstimated=true` means latency is estimated from its parts (capture + encode + send queue + RTT/2 + jitter buffer + decode + display) rather than measured.

Phone → browser:
```json
{"type":"welcome","token":"<token>","viewerId":"v3","protocol":1}
{"type":"denied","reason":"pin"}                      // wrong PIN
{"type":"denied","reason":"locked","retryAfterSec":60} // too many attempts from this IP
{"type":"denied","reason":"token"}                    // token unknown/expired → show the PIN form
{"type":"denied","reason":"full"}                     // viewer limit (no limit by default)
{"type":"denied","reason":"busy"}                     // device at capacity (encoders/CPU) → retry in ~10 s
{"type":"denied","reason":"not_streaming"}            // server is up, not casting → wait and retry
{"type":"denied","reason":"bad_request"}
{"type":"offer","sdp":"v=0..."}
{"type":"ice","candidate":{...}}
{"type":"state","streaming":true,"width":1080,"height":2400,"fps":60}   // streaming:false = paused (see below)
{"type":"sender","encoder":"c2.qti.avc.encoder","hardware":true,"encodeMs":3.1,"sendDelayMs":0.8,"fps":59.7} // every ~2 s
{"type":"bye","reason":"stopped"}                     // casting stopped on the phone
```
After `denied` the server closes the socket.

All messages carry machine-readable codes only (`reason`, `type`); the viewer page turns them into text in its own language.

## Sequence
1. Page: `GET /api/info` → if `pinRequired` and there is no PIN (from `#pin=`) and no token (sessionStorage) → PIN form.
2. `WS /ws` → `hello`. The first message must arrive within 10 s.
3. `welcome` → the browser stores `token` in sessionStorage.
4. The phone creates a PeerConnection → `offer` → the browser sends `answer`. Both sides send `ice` (trickle). The browser queues remote candidates that arrive before `setRemoteDescription`.
5. Media: DTLS-SRTP, H.264 (VP8 fallback). `iceServers: []`: local network only.
6. `state` right after `welcome` and on orientation/settings changes and pause/resume.

## Pause
- `state{streaming:false}` means the cast is paused on the phone. The WebSocket and the PeerConnection stay up; the phone keeps
  sending black frames at a low rate (possibly none for a while). The viewer shows a still "Casting is paused" screen instead
  of the video, does not reconnect and does not treat the missing frames as a media stall.
- `state{streaming:true}` resumes: the viewer hides the pause screen and shows the video on the same PeerConnection.
- A viewer that joins during a pause gets `welcome`, then `state{streaming:false}`, and shows the pause screen as soon as the
  PeerConnection is connected.
- Real failures still count during a pause: WS closed, PeerConnection `failed`, or `disconnected` for > 3 s → reconnect.

## Reconnecting (browser)
- WS closed, or PeerConnection `failed` / `disconnected` for > 3 s → new WS with `hello{token}` (or `restart` if the WS is still alive).
- No decoded frame for > 4 s while playing (and not paused) → the media path is considered dead → `restart`.
- Backoff: 0.5 → 1 → 2 → 4 → 5 s (then every 5 s), reset after video plays successfully.
- `not_streaming` → retry every 2 s; `bye{stopped}` → retry every 3 s (casting may be turned back on).
- `token` → drop the token, show the PIN form (if `pinRequired`), otherwise send `hello{}` right away.
- A new `hello` with a token that already has an active connection replaces it (the server closes the old one).

## Hidden tabs (browser)
Every viewer costs the phone a hardware encoder and its own Wi-Fi stream, so background tabs degrade the watched one.
- Page hidden (`visibilityState === 'hidden'`) for 8 s → the viewer sends `bye`, closes the PeerConnection and the WS
  (the phone frees the encoder at once) and stays quiet: no reconnect, no watchdog, no stall recovery while hidden.
  8 s leaves room for a quick app switch and the iOS native-fullscreen transition. `?hide_ms=N` overrides it (tests).
- Not while the video is in picture-in-picture or iPhone native fullscreen (the user is watching); leaving PiP while
  hidden starts the 8 s again.
- Shown again → at once the normal "Connecting" card and a new WS with `hello{token}` (no PIN), new PeerConnection,
  so latency is fresh rather than a resumed stale buffer. A pause (`state{streaming:false}`) is re-sent after `welcome`.
- A shorter hide changes nothing (same WS and PeerConnection).

## Security
- 6-digit PIN; 5 wrong attempts from one IP → 60 s lockout; constant-time comparison.
- 128-bit token, valid until the PIN changes ("New PIN") or the app restarts. The PIN and tokens survive stopping/starting the cast, so viewers come back on their own.
- A WebSocket with a foreign `Origin` (a page from another site) is rejected, which protects against cross-site WebSocket hijacking.
- Turning on "Require PIN" during a cast revokes tokens and disconnects viewers (`bye{pin_changed}`).
- Media is encrypted with DTLS-SRTP (mandatory in WebRTC). Signaling is HTTP on the local network.
