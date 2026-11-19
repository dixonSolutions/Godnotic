// The browser's UDP: finds an opt-in native relay through public MQTT
// signaling, connects a WebRTC data channel to it, and carries the engine's
// datagrams over it (engine/darkplaces/touch_webnet.c calls send/recv).
// Message formats: engine/darkplaces/touch_bridge_relay.h. docs/WEB.md,
// "Online play in the browser", has the design, privacy and failure modes.
import {MqttClient} from './bridge-mqtt.mjs';
import {BridgeChannel} from './bridge-channel.mjs';

export const DEFAULT_BROKERS = ['wss://broker.emqx.io:8084/mqtt', 'wss://broker.hivemq.com:8884/mqtt'];
export const DEFAULT_PREFIX = 'godnotic/relay/v1';
export const DEFAULT_STUN = 'stun:stun.l.google.com:19302';
export const NO_RELAY = 'No relay online — play locally, or open Godnotic on a device and turn on ' +
  '“Let web players connect through this device”.';

const text = new TextDecoder();

// "KIND 1\nkey value\n...\n\nbody" -> {kind, version, fields: {key: [values]}, body}
export function parseMessage(data) {
  const s = typeof data === 'string' ? data : text.decode(data);
  const split = s.indexOf('\n\n');
  if (split < 0) return null;
  const lines = s.slice(0, split).split('\n');
  const [kind, version] = lines[0].split(' ');
  const fields = {};
  for (const line of lines.slice(1)) {
    const i = line.indexOf(' ');
    const key = i < 0 ? line : line.slice(0, i);
    (fields[key] = fields[key] || []).push(i < 0 ? '' : line.slice(i + 1));
  }
  return {kind, version: Number(version), fields, body: s.slice(split + 2)};
}

function parseIPv6(s) {
  const halves = s.split('::');
  if (halves.length > 2) return null;
  const part = (h) => (h ? h.split(':') : []);
  const head = part(halves[0]), tail = halves.length === 2 ? part(halves[1]) : [];
  const fill = 8 - head.length - tail.length;
  if (fill < 0 || (halves.length === 1 && fill !== 0)) return null;
  const words = [...head, ...Array(halves.length === 2 ? fill : 0).fill('0'), ...tail];
  const out = new Uint8Array(16);
  for (let i = 0; i < 8; i++) {
    if (!/^[0-9a-fA-F]{1,4}$/.test(words[i])) return null;
    const v = parseInt(words[i], 16);
    out[i * 2] = v >> 8; out[i * 2 + 1] = v & 255;
  }
  return out;
}

// "1.2.3.4:26000" or "[2001:db8::1]:26000" (what LHNETADDRESS_ToString writes)
export function parseAddress(s) {
  let m = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3}):(\d{1,5})$/.exec(s);
  if (m) {
    const bytes = m.slice(1, 5).map(Number);
    const port = Number(m[5]);
    if (bytes.some((b) => b > 255) || port < 1 || port > 65535) return null;
    return {address: Uint8Array.from(bytes), port};
  }
  m = /^\[([0-9a-fA-F:]+)\]:(\d{1,5})$/.exec(s);
  if (m) {
    const address = parseIPv6(m[1]), port = Number(m[2]);
    if (!address || port < 1 || port > 65535) return null;
    return {address, port};
  }
  return null;
}

export function randomId(prefix) {
  const b = crypto.getRandomValues(new Uint8Array(8));
  return prefix + Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
}

const delay = (ms) => new Promise((r) => setTimeout(r, ms));

// Connects to every broker, reads the retained relay presences for waitMs.
// Returns relays with room, least loaded first, and the open clients.
export async function findRelays({brokers = DEFAULT_BROKERS, prefix = DEFAULT_PREFIX, waitMs = 2000,
  connectMs = 8000, WebSocketImpl} = {}) {
  const clients = [];
  const relays = new Map();
  await Promise.all(brokers.map(async (url) => {
    const client = new MqttClient(url, randomId('gnw-'), WebSocketImpl ? {WebSocketImpl} : {});
    try { await client.connect(connectMs); } catch (_) { return; }
    clients.push(client);
    client.onMessage = (topic, payload) => {
      const id = topic.slice(prefix.length + 8); // "<prefix>/online/<id>"
      if (!topic.startsWith(prefix + '/online/') || !/^[0-9a-f]{16}$/.test(id)) return;
      const m = payload.length ? parseMessage(payload) : null;
      if (!m || m.kind !== 'GODNOTIC-RELAY' || m.version !== 1) { if (relays.get(id)?.client === client) relays.delete(id); return; }
      const peers = Number(m.fields.peers?.[0]), max = Number(m.fields.max?.[0]);
      if (!(max > 0) || !(peers >= 0)) return;
      if (!relays.has(id) || relays.get(id).client === client) relays.set(id, {id, peers, max, broker: url, client});
    };
    client.subscribe(prefix + '/online/+');
  }));
  if (clients.length) await delay(waitMs);
  const list = [...relays.values()].filter((r) => r.peers < r.max && !r.client.closed)
    .sort((a, b) => a.peers / a.max - b.peers / b.max || Math.random() - 0.5);
  return {relays: list, clients, brokersOnline: clients.length};
}

function waitFor(target, event, check, ms, what) {
  return new Promise((resolve, reject) => {
    if (check()) { resolve(); return; }
    const timer = setTimeout(() => { target.removeEventListener(event, on); reject(new Error(what + ' timed out')); }, ms);
    function on() { if (check()) { clearTimeout(timer); target.removeEventListener(event, on); resolve(); } }
    target.addEventListener(event, on);
  });
}

// Offers a data channel to one relay and waits until it is open.
export async function connectRelay(relay, {prefix = DEFAULT_PREFIX, stun = DEFAULT_STUN, answerMs = 12000,
  openMs = 15000, RTCPeerConnectionImpl = globalThis.RTCPeerConnection} = {}) {
  const pc = new RTCPeerConnectionImpl({iceServers: stun ? [{urls: stun}] : []});
  try {
    const dc = pc.createDataChannel('datagrams', {ordered: false, maxRetransmits: 0});
    await pc.setLocalDescription(await pc.createOffer());
    await waitFor(pc, 'icegatheringstatechange', () => pc.iceGatheringState === 'complete', 5000, 'ICE gathering')
      .catch(() => { /* use the candidates gathered so far */ });
    const client = relay.client;
    const answer = new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('the relay did not answer')), answerMs);
      const previous = client.onMessage;
      client.onMessage = (topic, payload) => {
        if (topic !== `${prefix}/answer/${client.clientId}`) { if (previous) previous(topic, payload); return; }
        const m = parseMessage(payload);
        if (!m || m.kind !== 'GODNOTIC-ANSWER' || m.fields.relay?.[0] !== relay.id) return;
        clearTimeout(timer);
        client.onMessage = previous;
        if (m.fields.error) reject(new Error('relay refused: ' + m.fields.error[0]));
        else resolve(m);
      };
    });
    client.subscribe(`${prefix}/answer/${client.clientId}`);
    await delay(100); // let the subscription land before the answer can
    client.publish(`${prefix}/offer/${relay.id}`, `GODNOTIC-OFFER 1\nfrom ${client.clientId}\n\n${pc.localDescription.sdp}`);
    const m = await answer;
    await pc.setRemoteDescription({type: 'answer', sdp: m.body});
    await waitFor(dc, 'open', () => dc.readyState === 'open', openMs, 'relay data channel');
    return {pc, dc, channel: new BridgeChannel(dc, {maxPackets: 1024, maxBytes: 4194304}),
      masters: (m.fields.master || []).filter((a) => parseAddress(a)), relay: relay.id};
  } catch (e) {
    pc.close();
    throw e;
  }
}

// What touch_webnet.c talks to (Module.godnoticNet).
export class GodnoticNet {
  constructor({brokers = DEFAULT_BROKERS, prefix = DEFAULT_PREFIX, stun = DEFAULT_STUN, waitMs = 2000,
    retryMs = 20000, onState = () => {}, onConnected = () => {}, WebSocketImpl, RTCPeerConnectionImpl} = {}) {
    Object.assign(this, {brokers, prefix, stun, waitMs, retryMs, onState, onConnected, WebSocketImpl, RTCPeerConnectionImpl});
    this.state = 'idle';
    this.retryAt = 0;
    this.conn = null;
    this.queues = {4: [], 6: []};
  }
  setState(state, message = '') {
    this.state = state;
    this.message = message;
    this.onState(state, message);
  }
  ready() { return this.state === 'connected'; }
  request() {
    if (this.state === 'searching' || this.state === 'connected') return;
    if (this.state !== 'idle' && Date.now() < this.retryAt) return;
    this.start();
  }
  async start() {
    this.setState('searching', 'Looking for a web relay…');
    let found;
    try {
      found = await findRelays({brokers: this.brokers, prefix: this.prefix, waitMs: this.waitMs, WebSocketImpl: this.WebSocketImpl});
    } catch (e) {
      found = {relays: [], clients: [], brokersOnline: 0};
    }
    const {relays, clients} = found;
    let conn = null, last = null;
    for (const relay of relays.slice(0, 3)) {
      try {
        conn = await connectRelay(relay, {prefix: this.prefix, stun: this.stun, RTCPeerConnectionImpl: this.RTCPeerConnectionImpl});
        break;
      } catch (e) { last = e; }
    }
    for (const c of clients) c.close(); // signaling is done once the channel is open
    this.retryAt = Date.now() + this.retryMs;
    if (!conn) {
      if (!found.brokersOnline) this.setState('none', 'Cannot reach the relay signaling service — play locally, or try again later.');
      else if (!relays.length) this.setState('none', NO_RELAY);
      else this.setState('failed', 'Could not connect to a web relay' + (last ? ': ' + last.message : '') + '.');
      return;
    }
    this.conn = conn;
    conn.dc.addEventListener('close', () => {
      if (this.conn !== conn) return;
      this.conn = null;
      this.retryAt = 0;
      this.setState('lost', 'The web relay went away.');
    });
    this.setState('connected', 'Connected through web relay ' + conn.relay + '.');
    this.onConnected(conn.masters.join(' '));
  }
  send(address, payload) {
    if (!this.ready()) { this.request(); return 0; }
    const a = parseAddress(address);
    if (!a || !payload.length) return 0;
    return this.conn.channel.send(a.address, a.port, payload) ? payload.length : 0;
  }
  recv(family) {
    if (!this.conn) return null;
    for (let p; (p = this.conn.channel.receive());) {
      const q = this.queues[p.address.length === 4 ? 4 : 6];
      if (q.length < 1024) q.push(p);
    }
    return this.queues[family === 6 ? 6 : 4].shift() || null;
  }
  close() {
    if (this.conn) { this.conn.channel.close(); this.conn.pc.close(); this.conn = null; }
    this.setState('idle');
  }
}
