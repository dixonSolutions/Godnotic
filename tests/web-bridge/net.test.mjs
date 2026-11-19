// node tests/web-bridge/net.test.mjs: the browser relay client without a browser.
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import path from 'node:path';
import vm from 'node:vm';
import {fileURLToPath} from 'node:url';
import {encodeConnect, encodePublish, encodeSubscribe, splitPacket, topicMatches} from '../../web/bridge-mqtt.mjs';
import {parseAddress, parseMessage, GodnoticNet, NO_RELAY, findRelays} from '../../web/bridge-net.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

// Addresses as LHNETADDRESS_ToString writes them.
assert.deepEqual(parseAddress('127.0.0.1:26000'), {address: Uint8Array.of(127, 0, 0, 1), port: 26000});
assert.deepEqual(parseAddress('[::1]:27950').address, Uint8Array.from({length: 16}, (_, i) => (i === 15 ? 1 : 0)));
assert.deepEqual([...parseAddress('[2001:db8:0:0:0:0:0:ff]:1').address.slice(0, 4)], [0x20, 0x01, 0x0d, 0xb8]);
for (const bad of ['256.0.0.1:1', '1.2.3.4:0', '1.2.3.4:65536', '1.2.3.4', 'host:26000', '[1::2::3]:1', '[::g]:1', '172.29.1.0'])
  assert.equal(parseAddress(bad), null, bad);
console.log('web relay addresses: PASS');

// Signaling messages (touch_bridge_relay.h).
const a = parseMessage('GODNOTIC-ANSWER 1\nrelay 0123456789abcdef\nmaster 1.2.3.4:27950\nmaster 5.6.7.8:27777\n\nv=0\r\nx');
assert.equal(a.kind, 'GODNOTIC-ANSWER');
assert.equal(a.version, 1);
assert.deepEqual(a.fields.master, ['1.2.3.4:27950', '5.6.7.8:27777']);
assert.equal(a.body, 'v=0\r\nx');
assert.equal(parseMessage('no blank line'), null);
console.log('web relay messages: PASS');

// MQTT packets match the native encoder's layout.
assert.deepEqual([...encodeConnect('ab', 60)], [0x10, 14, 0, 4, 77, 81, 84, 84, 4, 2, 0, 60, 0, 2, 97, 98]);
assert.deepEqual([...encodeSubscribe(1, 't/+')], [0x82, 8, 0, 1, 0, 3, 116, 47, 43, 0]);
const pub = encodePublish('t', new Uint8Array(200), true);
assert.deepEqual([...pub.slice(0, 3)], [0x31, 203, 1]);
const split = splitPacket(pub);
assert.equal(split.type, 3);
assert.equal(split.size, pub.length);
assert.equal(splitPacket(pub.slice(0, 50)), null);
assert.throws(() => splitPacket(Uint8Array.of(0x30, 255, 255, 255, 255)), RangeError);
assert.ok(topicMatches('a/+/c', 'a/b/c') && topicMatches('a/#', 'a/b/c') && !topicMatches('a/+', 'a/b/c'));
console.log('web relay MQTT packets: PASS');

// Not connected: datagrams are dropped (0), and the first one starts a search.
let started = 0;
const net = new GodnoticNet({brokers: []});
net.start = () => { started++; net.state = 'searching'; };
assert.equal(net.ready(), false);
assert.equal(net.send('1.2.3.4:26000', Uint8Array.of(1)), 0);
assert.equal(net.send('1.2.3.4:26000', Uint8Array.of(1)), 0);
assert.equal(started, 1);
assert.equal(net.recv(4), null);

// No broker reachable / no relay online: one clear message each.
const states = [];
const none = new GodnoticNet({brokers: [], onState: (s, m) => states.push([s, m])});
await none.start();
assert.equal(none.state, 'none');
assert.match(states.at(-1)[1], /signaling/);
const empty = await findRelays({brokers: []});
assert.deepEqual(empty.relays, []);
assert.match(NO_RELAY, /No relay online/);
assert.match(NO_RELAY, /Let web players connect through this device/);
console.log('web relay offline states: PASS');

// The page bundle defines window.GodnoticBridge as a classic script.
const bundle = execFileSync('python3', ['-I', path.join(root, 'scripts/lib/bridge_bundle.py'), path.join(root, 'web')], {encoding: 'utf8'});
const sandbox = {window: {}, TextEncoder, TextDecoder, crypto, setTimeout, clearTimeout, setInterval, clearInterval};
vm.runInNewContext(bundle, sandbox);
assert.equal(typeof sandbox.window.GodnoticBridge.GodnoticNet, 'function');
assert.deepEqual(sandbox.window.GodnoticBridge.parseAddress('10.0.0.1:5').port, 5);
console.log('web relay page bundle: PASS');
