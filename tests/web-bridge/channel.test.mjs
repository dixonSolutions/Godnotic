import assert from 'node:assert/strict';
import {BridgeChannel} from '../../web/bridge-channel.mjs';
import {pack} from '../../web/bridge-frame.mjs';
class Channel extends EventTarget {
  readyState = 'open'; bufferedAmount = 0;
  send(frame) { this.sent = frame; }
  deliver(frame) { this.dispatchEvent(new MessageEvent('message', {data: frame.buffer})); }
}
const channel = new Channel();
const bridge = new BridgeChannel(channel, {maxPackets: 1, maxBytes: 64});
const ip = Uint8Array.of(1, 2, 3, 4), data = Uint8Array.of(9, 8);
assert.equal(bridge.send(ip, 26000, data), true);
channel.deliver(channel.sent);
channel.deliver(pack(ip, 26000, data));
assert.deepEqual(bridge.receive(), {address: ip, port: 26000, payload: data});
assert.equal(bridge.receive(), null);
channel.deliver(Uint8Array.of(1));
assert.equal(bridge.receive(), null);
channel.bufferedAmount = 64;
assert.equal(bridge.send(ip, 26000, data), false);
channel.readyState = 'closed';
assert.equal(bridge.send(ip, 26000, data), false);
bridge.close();
channel.deliver(pack(ip, 26000, data));
assert.equal(bridge.receive(), null);
console.log('web bridge channel: PASS');
