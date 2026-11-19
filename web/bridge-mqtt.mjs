// Minimal MQTT 3.1.1 client (QoS 0) over a WebSocket, for the web relay's
// public signaling (touch_bridge_relay.h has the message formats). Same
// subset as engine/darkplaces/touch_bridge_mqtt.c.
const utf8 = new TextEncoder();

function varint(n) {
  const out = [];
  do { let b = n & 127; n >>>= 7; if (n) b |= 128; out.push(b); } while (n);
  if (out.length > 4) throw new RangeError('MQTT packet too large');
  return out;
}
function str(s) {
  const b = typeof s === 'string' ? utf8.encode(s) : s;
  if (b.length > 65535) throw new RangeError('MQTT string too long');
  return [b.length >> 8, b.length & 255, ...b];
}
function packet(type, body) {
  return Uint8Array.from([type, ...varint(body.length), ...body]);
}

export function encodeConnect(clientId, keepalive = 60) {
  return packet(0x10, [0, 4, 77, 81, 84, 84, 4, 0x02, keepalive >> 8, keepalive & 255, ...str(clientId)]);
}
export function encodePublish(topic, payload, retain = false) {
  const body = typeof payload === 'string' ? utf8.encode(payload) : payload;
  const head = str(topic);
  const all = new Uint8Array(head.length + body.length);
  all.set(head); all.set(body, head.length);
  const len = varint(all.length);
  const out = new Uint8Array(1 + len.length + all.length);
  out[0] = 0x30 | (retain ? 1 : 0); out.set(len, 1); out.set(all, 1 + len.length);
  return out;
}
export function encodeSubscribe(id, filter) {
  return packet(0x82, [id >> 8, id & 255, ...str(filter), 0]);
}
// One packet off the front of buf: null when incomplete, throws when malformed.
export function splitPacket(buf) {
  let len = 0, shift = 0, i = 1;
  for (; i < 5; i++) {
    if (i >= buf.length) return null;
    len |= (buf[i] & 127) << shift; shift += 7;
    if (!(buf[i] & 128)) break;
  }
  if (i === 5 || len > 131072) throw new RangeError('malformed MQTT packet');
  const head = i + 1;
  if (buf.length < head + len) return null;
  return {type: buf[0] >> 4, flags: buf[0] & 15, body: buf.subarray(head, head + len), size: head + len};
}
// topic filter match with + and # wildcards
export function topicMatches(filter, topic) {
  const f = filter.split('/'), t = topic.split('/');
  for (let i = 0; i < f.length; i++) {
    if (f[i] === '#') return true;
    if (i >= t.length || (f[i] !== '+' && f[i] !== t[i])) return false;
  }
  return f.length === t.length;
}

export class MqttClient {
  constructor(url, clientId, {keepalive = 60, WebSocketImpl = globalThis.WebSocket} = {}) {
    this.url = url; this.clientId = clientId; this.keepalive = keepalive;
    this.WebSocketImpl = WebSocketImpl;
    this.onMessage = null; this.onClose = null;
    this.buf = new Uint8Array(0); this.nextId = 1; this.closed = false;
  }
  connect(timeoutMs = 8000) {
    return new Promise((resolve, reject) => {
      let done = false;
      const finish = (err) => { if (done) return; done = true; clearTimeout(timer); err ? (this.close(), reject(err)) : resolve(this); };
      const timer = setTimeout(() => finish(new Error('signaling broker timed out: ' + this.url)), timeoutMs);
      let ws;
      try { ws = new this.WebSocketImpl(this.url, 'mqtt'); } catch (e) { finish(e); return; }
      this.ws = ws;
      ws.binaryType = 'arraybuffer';
      ws.onopen = () => ws.send(encodeConnect(this.clientId, this.keepalive));
      ws.onerror = () => finish(new Error('signaling broker unreachable: ' + this.url));
      ws.onclose = () => { this.closed = true; clearInterval(this.pinger); finish(new Error('signaling broker closed: ' + this.url)); if (this.onClose) this.onClose(); };
      ws.onmessage = (ev) => {
        if (!(ev.data instanceof ArrayBuffer)) return;
        const add = new Uint8Array(ev.data);
        const merged = new Uint8Array(this.buf.length + add.length);
        merged.set(this.buf); merged.set(add, this.buf.length);
        this.buf = merged;
        if (this.buf.length > 262144) { this.close(); return; }
        for (;;) {
          let p;
          try { p = splitPacket(this.buf); } catch (e) { this.close(); return; }
          if (!p) break;
          this.buf = this.buf.slice(p.size);
          if (p.type === 2) {
            if (p.body.length !== 2 || p.body[1] !== 0) { finish(new Error('signaling broker refused the connection')); return; }
            this.pinger = setInterval(() => this.send(Uint8Array.of(0xC0, 0)), this.keepalive * 500);
            finish();
          } else if (p.type === 3) {
            const qos = (p.flags >> 1) & 3;
            const tl = (p.body[0] << 8) | p.body[1];
            const topic = new TextDecoder().decode(p.body.subarray(2, 2 + tl));
            const at = 2 + tl + (qos ? 2 : 0);
            if (qos === 1) this.send(Uint8Array.of(0x40, 2, p.body[2 + tl], p.body[3 + tl]));
            if (this.onMessage) this.onMessage(topic, p.body.slice(at));
          }
        }
      };
    });
  }
  send(bytes) {
    if (this.ws && this.ws.readyState === 1) { this.ws.send(bytes); return true; }
    return false;
  }
  subscribe(filter) {
    const id = this.nextId++; if (this.nextId > 65535) this.nextId = 1;
    return this.send(encodeSubscribe(id, filter));
  }
  publish(topic, payload, retain = false) { return this.send(encodePublish(topic, payload, retain)); }
  close() {
    clearInterval(this.pinger);
    if (this.ws && !this.closed) {
      try { if (this.ws.readyState === 1) this.ws.send(Uint8Array.of(0xE0, 0)); this.ws.close(); } catch (_) { /* gone */ }
    }
    this.closed = true;
  }
}
