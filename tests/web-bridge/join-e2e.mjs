// Browser join through the web relay, end to end, with the real web build in
// headless Chromium (CDP) and real native processes. Run by join-e2e.sh.
//
//   page (wasm engine) --WebRTC data channel--> relay (darkplaces-dedicated,
//   bridge_relay 1) --UDP--> master (fake, here) and game server (dedicated)
//   signaling: an MQTT-over-WebSocket broker (mqtt_broker.py, or a public one)
//
// Checks, each printed "ok"/"FAIL":
//   1. the relay announces itself on the broker
//   2. the page finds it, opens the data channel and gets its masters
//   3. net_slist lists the game server (master query + getinfo over the relay)
//   4. connect: the client loads the server's world (CSQC loaded) and draws it
//   5. cmd join + forward: the player's origin on the server moves
//   6. relay off: the page says no relay is online
//
//   node join-e2e.mjs --cdp PORT --url PAGE_URL --broker WS_URL --dedicated BIN
//        --basedir DIR --out DIR [--map stormkeep]
import {spawn, execFileSync} from 'node:child_process';
import dgram from 'node:dgram';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const opt = (n, d) => { const i = args.indexOf('--' + n); return i < 0 ? d : args[i + 1]; };
const cdpPort = Number(opt('cdp'));
const pageUrl = opt('url');
const broker = opt('broker');
const dedicated = opt('dedicated');
const basedir = opt('basedir');
const out = opt('out');
const map = opt('map', 'stormkeep');
// --public: the relay asks the real public masters (its defaults) instead of
// the local fake one, and the browser lists and briefly joins a public server.
const pub = args.includes('--public');
// A topic of its own per run: on a public broker the test relay must not be
// offered to real players, nor real relays to the test.
const topic = opt('topic', `godnotic/test/${process.pid}-${Date.now()}`);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const T0 = Date.now();
const secs = () => ((Date.now() - T0) / 1000).toFixed(1);
fs.mkdirSync(out, {recursive: true});

let failed = 0;
const results = {};
const check = (ok, what) => { console.log(`${ok ? 'ok  ' : 'FAIL'} ${what} [${secs()} s]`); if (!ok) failed = 1; return ok; };
const children = [];
function cleanup() { for (const c of children) { try { c.kill('SIGTERM'); } catch (_) { /* gone */ } } }
process.on('exit', cleanup);

const freeUdpPort = () => new Promise((r) => { const s = dgram.createSocket('udp4'); s.bind(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => r(p)); }); });

// ---- native processes
function startDedicated(name, port, extra) {
  const logPath = path.join(out, name + '.log');
  const log = fs.openSync(logPath, 'w');
  const p = spawn(dedicated, ['-xonotic', '-basedir', basedir, '-userdir', path.join(out, name + '-user'), '-port', String(port),
    '+sv_public', '0', ...extra], {stdio: ['pipe', log, log]});
  children.push(p);
  return {proc: p, logPath, text: () => fs.readFileSync(logPath, 'utf8'), say: (line) => p.stdin.write(line + '\n')};
}
async function waitLog(proc, re, ms) {
  const t = Date.now();
  while (Date.now() - t < ms) {
    const m = re.exec(proc.text());
    if (m) return m;
    if (proc.proc.exitCode !== null) return null;
    await sleep(200);
  }
  return null;
}
async function udpQuery(port, text, ms = 400) {
  const s = dgram.createSocket('udp4');
  try {
    return await new Promise((resolve) => {
      const t = setTimeout(() => resolve(null), ms);
      s.on('message', (m) => { clearTimeout(t); resolve(m); });
      s.send(Buffer.concat([Buffer.alloc(4, 255), Buffer.from(text)]), port, '127.0.0.1');
    });
  } finally { s.close(); }
}

const masterPort = await freeUdpPort();
const gamePort = await freeUdpPort();
const relayPort = await freeUdpPort();
// A master that knows one server: 127.0.0.1:gamePort.
const master = dgram.createSocket('udp4');
let masterQueries = 0;
master.on('message', (m, rinfo) => {
  const s = m.subarray(4).toString('latin1');
  if (!s.startsWith('getservers')) return;
  masterQueries++;
  const ext = s.startsWith('getserversExt');
  const entry = Buffer.from([127, 0, 0, 1, gamePort >> 8, gamePort & 255]);
  master.send(Buffer.concat([Buffer.alloc(4, 255), Buffer.from(ext ? 'getserversExtResponse\\' : 'getserversResponse\\', 'latin1'),
    entry, Buffer.from('\\EOT\0\0\0', 'latin1')]), rinfo.port, rinfo.address);
});
await new Promise((r) => master.bind(masterPort, '127.0.0.1', r));

const game = pub ? null : startDedicated('game', gamePort, ['+hostname', 'Godnotic bridge test', '+bot_number', '0',
  '+rcon_secure', '0', '+rcon_password', 'bridgetest', '+map', map]);
let gameUp = pub;
for (let i = 0; i < 150 && !gameUp; i++) { gameUp = !!(await udpQuery(gamePort, 'getinfo up')); if (!gameUp) await sleep(200); }
if (!pub) check(gameUp, `the game server answers on 127.0.0.1:${gamePort}`);

const relay = startDedicated('relay', relayPort, [...(pub ? [] : ['+bridge_allow_loopback', '1', '+sv_master1', `127.0.0.1:${masterPort}`,
  '+sv_masterextra1', '""', '+sv_masterextra2', '""', '+sv_masterextra3', '""']),
  '+bridge_relay_brokers', broker, '+bridge_relay_topic', topic, '+bridge_relay_stun', '""', '+bridge_relay', '1', '+map', map]);
const online = await waitLog(relay, /web relay ([0-9a-f]{16}) online on/, 60000);
check(!!online, `1. the relay announces itself on ${broker}${online ? ' as ' + online[1] : ''}`);

// ---- the browser (CDP)
let ws;
for (let i = 0; i < 150 && !ws; i++) {
  try {
    const list = await (await fetch(`http://127.0.0.1:${cdpPort}/json/list`)).json();
    const page = list.find((t) => t.type === 'page');
    if (page) ws = new WebSocket(page.webSocketDebuggerUrl);
  } catch (_) { /* not up */ }
  await sleep(100);
}
if (ws && ws.readyState !== 1) await new Promise((r) => { ws.onopen = r; setTimeout(r, 20000); });
if (!check(ws && ws.readyState === 1, 'Chromium devtools connected')) process.exit(1);
let id = 0;
const pending = new Map();
const consoleErrors = [];
ws.onmessage = (ev) => {
  const m = JSON.parse(ev.data);
  if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); return; }
  if (m.method === 'Runtime.exceptionThrown') { const d = m.params.exceptionDetails || {}; consoleErrors.push(((d.exception && d.exception.description) || d.text || '').slice(0, 400)); }
};
const send = (method, params = {}, ms = 60000) => new Promise((resolve) => {
  const i = ++id;
  const t = setTimeout(() => { pending.delete(i); resolve({timeout: true}); }, ms);
  pending.set(i, (m) => { clearTimeout(t); resolve(m); });
  ws.send(JSON.stringify({id: i, method, params}));
});
const evaluate = async (expr) => {
  const r = await send('Runtime.evaluate', {expression: expr, awaitPromise: true, returnByValue: true, userGesture: true});
  return r.result && r.result.result ? r.result.result.value : undefined;
};
const logLines = async () => (await evaluate('window.xt && xt.log ? xt.log() : []')) || [];
const command = (line) => evaluate(`xt.command(${JSON.stringify(line)})`);
let mark = 0;
async function waitConsole(re, ms, since) {
  const t = Date.now();
  while (Date.now() - t < ms) {
    let lines = await logLines();
    if (since) { const i = lines.findLastIndex((l) => l.includes(since)); lines = i < 0 ? [] : lines.slice(i + 1); }
    const l = lines.find((x) => re.test(x));
    if (l) return l;
    await sleep(250);
  }
  return null;
}
async function shot(name) {
  const r = await send('Page.captureScreenshot', {format: 'png'});
  const file = path.join(out, name + '.png');
  if (r.result && r.result.data) fs.writeFileSync(file, Buffer.from(r.result.data, 'base64'));
  try { return JSON.parse(execFileSync('python3', ['-I', path.join(here, 'png_stats.py'), file], {encoding: 'utf8'})); } catch (_) { return null; }
}

await send('Page.enable');
await send('Runtime.enable');
await send('Emulation.setFocusEmulationEnabled', {enabled: true});
const sep = pageUrl.includes('?') ? '&' : '?';
const url = `${pageUrl}${sep}relaybroker=${encodeURIComponent(broker)}&relaytopic=${encodeURIComponent(topic)}&relaystun=`;
await send('Page.navigate', {url});
let menu = false, clicked = false;
const tStart = Date.now();
while (!menu && Date.now() - tStart < 600000) {
  await sleep(300);
  const s = await evaluate(`(() => { const vis = (i) => { const e = document.getElementById(i); return !!e && !e.hidden; };
    const log = window.xt && xt.log ? xt.log() : [];
    return {download: vis('xt-download'), failed: vis('xt-error'), err: (document.getElementById('xt-error-text') || {}).textContent || '',
      menu: log.some((l) => /menu/i.test(l) && /loaded|program/i.test(l))}; })()`);
  if (!s) continue;
  if (s.failed) { check(false, 'the page starts: ' + s.err); process.exit(1); }
  if (s.download && !clicked) { clicked = true; await evaluate(`document.getElementById('xt-dl-go').click()`); }
  menu = s.menu;
}
if (!check(menu, 'the web game reaches its menu')) process.exit(1);
await sleep(3000);

// 2. The first server list request makes the page look for a relay.
const tFind = Date.now();
await command(`echo XTMARK${++mark}`);
await command('net_slist');
let state = '';
for (let i = 0; i < 120 && state !== 'connected'; i++) {
  await sleep(250);
  state = await evaluate('Module.godnoticNet ? Module.godnoticNet.state : "missing"');
  if (state === 'none' || state === 'failed') break;
}
results.relayConnectMs = Date.now() - tFind;
const masters = await evaluate('xt.cvar("sv_masterextra1")');
check(state === 'connected', `2. the page connects to the relay over WebRTC (state ${state}, ${results.relayConnectMs} ms) and asks its master (sv_masterextra1=${masters})`);
if (pub) {
  check(/^\d+\.\d+\.\d+\.\d+:\d+$/.test(masters || ''), '2b. the relay\'s public master list reaches the engine');
  // 3. The public list over the relay: line 2 of each entry is "^4<address> <game> <map> ...".
  await sleep(500);
  const m3 = `XTMARK${++mark}`;
  const tList = Date.now();
  await command(`echo ${m3}`);
  await command('net_slist');
  // The console keeps 200 lines and the list prints hundreds of "querying"
  // lines: read it every half second for 15 s.
  const entries = [], seen = new Set();
  const plain = (l) => l.replace(/\x1b\[[0-9;]*m/g, '').replace(/\^(\d|x[0-9a-fA-F]{3})/g, '');
  const dump = [];
  for (let t = 0; t < 30; t++) {
    await sleep(500);
    let lines = await logLines();
    const at = lines.findLastIndex((l) => l.includes(m3));
    if (at >= 0) lines = lines.slice(at + 1);
    for (let k = 0; k + 1 < lines.length; k++) {
      const p = /^\s*(\d+)\s+(\d+)\/\s*(\d+)\s(.*)$/.exec(plain(lines[k]));
      const a = /^(\d+\.\d+\.\d+\.\d+:\d+)\s+(\S+)\s+(\S+)\s*(\S*)/.exec(plain(lines[k + 1]));
      if (!p || !a || seen.has(a[1])) continue;
      seen.add(a[1]);
      dump.push(plain(lines[k]), plain(lines[k + 1]));
      entries.push({address: a[1], map: a[4] || a[3], players: Number(p[2]), max: Number(p[3]), name: p[4].trim(), ping: Number(p[1])});
    }
  }
  fs.writeFileSync(path.join(out, 'slist.txt'), dump.join('\n'));
  results.publicServers = entries.length;
  results.serverListMs = Date.now() - tList;
  check(entries.length >= 3, `3. net_slist lists public servers through the relay: ${entries.length} in 12 s (e.g. ${entries.slice(0, 3).map((e) => e.name + ' ' + e.map).join(' | ')})`);
  fs.writeFileSync(path.join(out, 'public-servers.json'), JSON.stringify(entries, null, 1));
  // 4. A short visit to a public server with room, on a map the web data has.
  const stock = new Set(['stormkeep', 'boil', 'dance', 'atelier', 'afterslime', 'bromine', 'catharsis', 'courtfun', 'darkzone', 'erbium',
    'finalrage', 'fuse', 'geoplanetary', 'glowplant', 'implosion', 'leave_em_behind', 'nexballarena', 'oilrig', 'runningman', 'silentsiege',
    'solarium', 'space-elevator', 'stormkeep', 'techassault', 'vorix', 'warfare', 'xoylent', 'drain', 'opium', 'ruiner', 'gasoline']);
  const pick = entries.filter((e) => stock.has(e.map) && e.players < e.max).sort((x, y) => y.players - x.players).slice(0, 3);
  let joined = null;
  for (const e of pick) {
    const m4 = `XTMARK${++mark}`;
    const tJoin = Date.now();
    await command(`echo ${m4}`);
    await command(`connect ${e.address}`);
    const loaded = await waitConsole(/client: program loaded|CSQC.*loaded/i, 90000, m4);
    if (loaded) { joined = {...e, ms: Date.now() - tJoin}; break; }
    await command('disconnect');
    await sleep(1000);
  }
  results.publicJoin = joined;
  check(!!joined, `4. the browser joins a public server through the relay: ${joined ? `${joined.name} (${joined.address}, ${joined.map}) in ${joined.ms} ms` : 'none of ' + pick.length + ' tried'}`);
  if (joined) {
    await command('togglemenu 0');
    await sleep(5000);
    const world = await shot('public-world');
    check(!!world && world.colours > 200 && world.sd > 12, `4c. the public server's world is drawn (${JSON.stringify(world)})`);
  }
} else {
check(masters === `127.0.0.1:${masterPort}`, '2b. the relay\'s master list reaches the engine');
  
  // 3. The server list over the relay.
  await sleep(500);
  const m3 = `XTMARK${++mark}`;
  const tList = Date.now();
  await command(`echo ${m3}`);
  await command('net_slist');
  const listed = await waitConsole(/Godnotic bridge test/, 20000, m3);
  results.serverListMs = Date.now() - tList;
  check(!!listed && masterQueries > 0, `3. net_slist lists the game server through the relay (${results.serverListMs} ms, master queries ${masterQueries}): ${listed || 'nothing'}`);
  
  // 4. Join it.
  const m4 = `XTMARK${++mark}`;
  const tJoin = Date.now();
  await command(`echo ${m4}`);
  await command(`connect 127.0.0.1:${gamePort}`);
  const loaded = await waitConsole(/client: program loaded|CSQC.*loaded/i, 120000, m4);
  results.joinMs = Date.now() - tJoin;
  const entered = await waitLog(game, /connected|entered the game/i, 10000);
  check(!!loaded, `4. connect: the browser loads the server's world (${results.joinMs} ms): ${loaded || 'no CSQC'}`);
  check(!!entered, `4b. the game server sees the browser player: ${entered ? entered[0] : 'nothing'}`);
  await command('togglemenu 0');
  await sleep(4000);
  const world = await shot('joined-world');
  check(!!world && world.colours > 200 && world.sd > 12 && world.dark < 0.9,
    `4c. the world is drawn (screenshot: ${JSON.stringify(world)})`);
  
  // 5. Move.
  await command('cmd join');
  await sleep(2500);
  // The server's welcome dialog is up: press its "Join!" (bottom left) as a player would.
  const vp = await evaluate('[innerWidth, innerHeight]');
  for (const [type, extra] of [['mouseMoved', {}], ['mousePressed', {button: 'left', clickCount: 1}], ['mouseReleased', {button: 'left', clickCount: 1}]]) {
    await send('Input.dispatchMouseEvent', {type, x: Math.round(vp[0] * 0.377), y: Math.round(vp[1] * 0.711), ...extra});
    await sleep(80);
  }
  await sleep(1500);
  await shot('after-join-click');
  // The server's view of player 1, over plain rcon (stdout is buffered).
  async function rcon(line, ms = 600) {
    const sock = dgram.createSocket('udp4');
    let text = '';
    sock.on('message', (m) => { if (m.subarray(4, 5).toString() === 'n') text += m.subarray(5).toString('latin1'); });
    await new Promise((r) => sock.bind(0, '127.0.0.1', r));
    sock.send(Buffer.concat([Buffer.alloc(4, 255), Buffer.from(`rcon bridgetest ${line}`, 'latin1')]), gamePort, '127.0.0.1');
    await sleep(ms);
    sock.close();
    return text;
  }
  async function origin() {
    const mm = /(-?[\d.]+) (-?[\d.]+) (-?[\d.]+)/.exec(await rcon('prvm_edictget server 1 origin'));
    return mm ? mm.slice(1, 4).map(Number) : null;
  }
  const before = await origin();
  await command('+forward');
  await sleep(1500);
  await command('-forward');
  await sleep(500);
  const afterF = await origin();
  await command('+back');
  await sleep(1500);
  await command('-back');
  await sleep(500);
  const afterB = await origin();
  const dist = (a, b) => (a && b ? Math.hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2]) : 0);
  results.moved = [dist(before, afterF), dist(afterF, afterB)].map((d) => Math.round(d));
  check(dist(before, afterF) > 40 || dist(afterF, afterB) > 40,
    `5. the player moves on the server (${JSON.stringify(before)} -> ${JSON.stringify(afterF)} -> ${JSON.stringify(afterB)})`);
  await shot('moved');
  const relayText = relay.text();
  check(/web relay: offer from gnw-/.test(relayText), 'the relay logged the browser\'s offer');
}
// 6. Relay gone: the page tells the player.
await command('disconnect');
relay.say('bridge_relay 0');
await waitLog(relay, /web relay: off/, 10000);
await sleep(1500);
await evaluate('Module.godnoticNet.close(); Module.godnoticNet.retryAt = 0');
await command('net_slist');
let msg = '';
for (let i = 0; i < 160; i++) {
  await sleep(250);
  const s = await evaluate('[Module.godnoticNet.state, Module.godnoticNet.message, (document.getElementById("xt-toast")||{}).textContent || ""]');
  if (s && (s[0] === 'none' || s[0] === 'failed')) { msg = s; break; }
}
check(!!msg && msg[0] === 'none' && /No relay online/.test(msg[1]) && /No relay online/.test(msg[2]),
  `6. no relay online: the page says so (${JSON.stringify(msg)})`);
check(consoleErrors.length === 0, `no page exceptions${consoleErrors.length ? ': ' + consoleErrors.join(' | ') : ''}`);
console.log(JSON.stringify({results, fail: failed}));
master.close();
ws.close();
cleanup();
process.exit(failed);
