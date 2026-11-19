import {pack, unpack} from './bridge-frame.mjs';

// Bounded receive queue: a remote peer cannot grow the browser heap without limit.
export class BridgeChannel {
  constructor(channel, {maxPackets = 256, maxBytes = 1048576} = {}) {
    this.channel = channel;
    this.maxPackets = maxPackets;
    this.maxBytes = maxBytes;
    this.queue = [];
    this.bytes = 0;
    this.onMessage = event => {
      if (!(event.data instanceof ArrayBuffer)) return;
      try {
        const packet = unpack(new Uint8Array(event.data));
        if (this.queue.length >= this.maxPackets || this.bytes + packet.payload.length > this.maxBytes) return;
        this.queue.push(packet);
        this.bytes += packet.payload.length;
      } catch (_) { /* Ignore malformed peer traffic. */ }
    };
    channel.binaryType = 'arraybuffer';
    channel.addEventListener('message', this.onMessage);
  }
  send(address, port, payload) {
    if (this.channel.readyState !== 'open') return false;
    const frame = pack(address, port, payload);
    if (this.channel.bufferedAmount + frame.length > this.maxBytes) return false;
    try { this.channel.send(frame); return true; } catch (_) { return false; }
  }
  receive() {
    const packet = this.queue.shift();
    if (packet) this.bytes -= packet.payload.length;
    return packet || null;
  }
  close() {
    this.channel.removeEventListener('message', this.onMessage);
    this.queue.length = 0;
    this.bytes = 0;
  }
}
