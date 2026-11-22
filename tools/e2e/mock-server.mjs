// Mock of the phone side of LocalCast: HTTP + hand-written WebSocket server, no dependencies.
// Run: node tools/e2e/mock-server.mjs [port]   then open /phone first, then /#pin=123456
import http from 'node:http';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const VIEWER = path.resolve(here, '../../app/src/main/assets/viewer/index.html');
const PIN = '123456';
const GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';

/** Minimal RFC 6455 endpoint: text frames, ping/pong, close. */
class Sock {
  constructor(socket) {
    this.socket = socket; this.buf = Buffer.alloc(0); this.frag = []; this.closed = false;
    this.onmessage = () => {}; this.onclose = () => {};
    socket.on('data', (d) => { this.buf = Buffer.concat([this.buf, d]); this.parse(); });
    socket.on('close', () => this.fin());
    socket.on('error', () => this.fin());
  }
  fin() { if (!this.closed) { this.closed = true; this.onclose(); } }
  parse() {
    for (;;) {
      const b = this.buf;
      if (b.length < 2) return;
      const op = b[0] & 0x0f, fin = !!(b[0] & 0x80), masked = !!(b[1] & 0x80);
      let len = b[1] & 0x7f, off = 2;
      if (len === 126) { if (b.length < 4) return; len = b.readUInt16BE(2); off = 4; }
      else if (len === 127) { if (b.length < 10) return; len = Number(b.readBigUInt64BE(2)); off = 10; }
      const mlen = masked ? 4 : 0;
      if (b.length < off + mlen + len) return;
      let p = Buffer.from(b.subarray(off + mlen, off + mlen + len));
      if (masked) { const m = b.subarray(off, off + 4); for (let i = 0; i < p.length; i++) p[i] ^= m[i & 3]; }
      this.buf = b.subarray(off + mlen + len);
      if (op === 8) { this.close(); return; }
      if (op === 9) { this.frame(10, p); continue; }
      if (op === 10) continue;
      if (op === 1 || op === 0) {
        this.frag.push(p);
        if (fin) { const t = Buffer.concat(this.frag).toString('utf8'); this.frag = []; this.onmessage(t); }
      }
    }
  }
  frame(op, payload) {
    if (this.closed) return;
    const n = payload.length;
    const h = n < 126 ? Buffer.from([0x80 | op, n]) : n < 65536 ? Buffer.from([0x80 | op, 126, n >> 8, n & 255])
      : (() => { const x = Buffer.alloc(10); x[0] = 0x80 | op; x[1] = 127; x.writeBigUInt64BE(BigInt(n), 2); return x; })();
    try { this.socket.write(Buffer.concat([h, payload])); } catch { /* ignore */ }
  }
  send(o) { this.frame(1, Buffer.from(typeof o === 'string' ? o : JSON.stringify(o))); }
  close() { this.frame(8, Buffer.alloc(0)); this.socket.end(); setTimeout(() => this.socket.destroy(), 200); }
  kill() { this.socket.destroy(); }
}

export function startMock(port = 8099) {
  const st = { streaming: true, paused: false, busy: false, viewer: null, phone: null, tokens: new Set(), fails: 0, lockedUntil: 0, reports: [], log: [], joins: 0, byes: 0, leaves: 0, restarts: 0, lastHello: null };
  const log = (...a) => { st.log.push(a.join(' ')); if (process.env.MOCK_DEBUG) console.log(...a); };

  const toPhone = (o) => st.phone && st.phone.send(o);
  // Like the app: a paused cast keeps the PeerConnection (the app sends black frames) and says streaming:false.
  const stateMsg = () => ({ type: 'state', streaming: !st.paused, width: 640, height: 360, fps: 60 });
  const deny = (ws, reason, extra = {}) => { ws.send({ type: 'denied', reason, ...extra }); ws.close(); };

  function viewerHello(ws, m) {
    if (Date.now() < st.lockedUntil) return deny(ws, 'locked', { retryAfterSec: Math.ceil((st.lockedUntil - Date.now()) / 1000) });
    if (m.token && !st.tokens.has(m.token)) return deny(ws, 'token');
    if (!m.token) {
      if (m.pin !== PIN) {
        if (++st.fails >= 5) { st.lockedUntil = Date.now() + 60000; return deny(ws, 'locked', { retryAfterSec: 60 }); }
        return deny(ws, 'pin');
      }
      st.fails = 0;
    }
    if (!st.streaming) return deny(ws, 'not_streaming');
    if (st.busy) return deny(ws, 'busy');
    if (st.viewer && st.viewer !== ws) { const old = st.viewer; st.viewer = null; old.kill(); }
    st.viewer = ws; st.joins++; st.lastHello = m.token ? 'token' : 'pin';
    const token = m.token || crypto.randomBytes(16).toString('hex');
    st.tokens.add(token);
    ws.send({ type: 'welcome', token, viewerId: 'v1', protocol: 1 });
    ws.send(stateMsg());
    toPhone({ cmd: 'newViewer' });
  }

  function onViewerMsg(ws, text) {
    let m; try { m = JSON.parse(text); } catch { return; }
    if (!ws.hello) {
      if (m.type !== 'hello') return deny(ws, 'bad_request');
      ws.hello = true; return viewerHello(ws, m);
    }
    if (ws !== st.viewer) return;
    if (m.type === 'answer') toPhone({ cmd: 'answer', sdp: m.sdp });
    else if (m.type === 'ice') toPhone({ cmd: 'ice', candidate: m.candidate });
    else if (m.type === 'restart') { st.restarts++; toPhone({ cmd: 'newViewer' }); }
    else if (m.type === 'report') st.reports.push(m.stats);
    else if (m.type === 'bye') { st.byes++; ws.close(); }
  }

  const PHONE_HTML = `<!doctype html><meta charset=utf-8><title>mock phone</title>
<body style="margin:0;background:#222;color:#fff;font:14px monospace"><canvas id=c width=640 height=360></canvas><div id=s>init</div>
<script>
const c=document.getElementById('c'),x=c.getContext('2d'),s=document.getElementById('s');
let paused=false;
function draw(){if(paused)return;const t=performance.now();x.fillStyle='#103060';x.fillRect(0,0,640,360);
 x.fillStyle='#fff';x.font='bold 64px monospace';const d=new Date();
 x.fillText(d.toTimeString().slice(0,8)+'.'+String(d.getMilliseconds()).padStart(3,'0'),40,120);
 x.fillStyle='#ffb020';x.fillRect((t/4)%600,200,40,40);x.fillStyle='#4f8cff';x.beginPath();x.arc(320+Math.cos(t/300)*120,300+Math.sin(t/300)*40,20,0,7);x.fill();}
setInterval(draw,16);(function l(){draw();requestAnimationFrame(l)})();
const stream=c.captureStream(60);let pc=null;
const ws=new WebSocket('ws://'+location.host+'/phone-ws');
const send=o=>ws.send(JSON.stringify(o));
window.phoneReady=false;window.pcs=0;
ws.onopen=()=>{window.phoneReady=true;s.textContent='phone ready'};
ws.onmessage=async e=>{const m=JSON.parse(e.data);
 if(m.cmd==='newViewer'){ if(pc)pc.close(); const p=pc=new RTCPeerConnection({iceServers:[]}); window.pcs++;
  stream.getTracks().forEach(t=>p.addTrack(t,stream));
  p.onicecandidate=ev=>{if(ev.candidate&&p===pc)send({type:'ice',candidate:{candidate:ev.candidate.candidate,sdpMid:ev.candidate.sdpMid,sdpMLineIndex:ev.candidate.sdpMLineIndex}})};
  await p.setLocalDescription(await p.createOffer()); send({type:'offer',sdp:p.localDescription.sdp}); s.textContent='offer sent #'+window.pcs;}
 else if(m.cmd==='answer'&&pc&&pc.signalingState==='have-local-offer') await pc.setRemoteDescription({type:'answer',sdp:m.sdp});
 else if(m.cmd==='ice'&&pc&&pc.remoteDescription) pc.addIceCandidate(m.candidate).catch(()=>{});
 else if(m.cmd==='close'&&pc){pc.close();pc=null;}
 else if(m.cmd==='pause'){paused=m.on;if(paused){x.fillStyle='#000';x.fillRect(0,0,640,360);}}
};
</script>`;

  const server = http.createServer((req, res) => {
    const u = new URL(req.url, 'http://x');
    const j = (o, code = 200) => { res.writeHead(code, { 'content-type': 'application/json', 'cache-control': 'no-store' }); res.end(JSON.stringify(o)); };
    if (req.method === 'GET' && u.pathname === '/') {
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' });
      return res.end(fs.readFileSync(VIEWER));
    }
    if (u.pathname === '/phone') { res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' }); return res.end(PHONE_HTML); }
    if (u.pathname === '/api/info') return j({ name: 'LocalCast', pinRequired: true, streaming: st.streaming, protocol: 1 });
    if (u.pathname === '/health') { res.writeHead(200); return res.end('ok'); }
    if (u.pathname === '/mock/state') return j({ streaming: st.streaming, viewer: !!st.viewer, phone: !!st.phone, reports: st.reports.length, lastReport: st.reports.at(-1) || null,
      joins: st.joins, byes: st.byes, leaves: st.leaves, restarts: st.restarts, lastHello: st.lastHello });
    if (req.method === 'POST' && u.pathname === '/mock/drop') { if (st.viewer) st.viewer.kill(); return j({ ok: true }); }
    if (req.method === 'POST' && u.pathname === '/mock/stop') {
      st.streaming = false; // tokens survive a stop, like the app (PinGuard is process-wide)
      if (st.viewer) { st.viewer.send({ type: 'bye', reason: 'stopped' }); st.viewer.close(); st.viewer = null; }
      toPhone({ cmd: 'close' }); return j({ ok: true });
    }
    if (req.method === 'POST' && u.pathname === '/mock/start') { st.streaming = true; return j({ ok: true }); }
    if (req.method === 'POST' && u.pathname === '/mock/pause') {
      st.paused = u.searchParams.get('on') !== '0';
      // The mock phone paints one black frame and then sends nothing (worst case of the app's low-rate black frames).
      toPhone({ cmd: 'pause', on: st.paused });
      if (st.viewer) st.viewer.send(stateMsg());
      return j({ ok: true });
    }
    if (req.method === 'POST' && u.pathname === '/mock/busy') { st.busy = u.searchParams.get('on') !== '0'; return j({ ok: true }); }
    if (req.method === 'POST' && u.pathname === '/mock/reset') { if (st.paused) toPhone({ cmd: 'pause', on: false }); st.fails = 0; st.lockedUntil = 0; st.streaming = true; st.paused = false; st.busy = false; st.reports = []; st.joins = 0; st.byes = 0; st.leaves = 0; st.restarts = 0; st.lastHello = null; return j({ ok: true }); }
    res.writeHead(404); res.end('not found');
  });

  server.on('upgrade', (req, socket) => {
    const u = new URL(req.url, 'http://x');
    const key = req.headers['sec-websocket-key'];
    if (!key || (u.pathname !== '/ws' && u.pathname !== '/phone-ws')) { socket.destroy(); return; }
    socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ' +
      crypto.createHash('sha1').update(key + GUID).digest('base64') + '\r\n\r\n');
    const ws = new Sock(socket);
    if (u.pathname === '/phone-ws') {
      st.phone = ws;
      ws.onmessage = (t) => { let m; try { m = JSON.parse(t); } catch { return; } if (st.viewer && (m.type === 'offer' || m.type === 'ice')) st.viewer.send(m); };
      ws.onclose = () => { if (st.phone === ws) st.phone = null; };
    } else {
      ws.onmessage = (t) => onViewerMsg(ws, t);
      ws.onclose = () => { if (st.viewer === ws) { st.viewer = null; st.leaves++; toPhone({ cmd: 'close' }); } };
      setTimeout(() => { if (!ws.hello && !ws.closed) ws.close(); }, 10000);
    }
  });

  return new Promise((resolve) => server.listen(port, '127.0.0.1', () => {
    resolve({ port: server.address().port, url: `http://127.0.0.1:${server.address().port}`, state: st, close: () => { server.close(); server.closeAllConnections?.(); } });
  }));
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const port = Number(process.argv[2] || process.env.PORT || 8099);
  const m = await startMock(port);
  console.log(`LocalCast mock: ${m.url}/phone (open first), then ${m.url}/#pin=123456`);
}
