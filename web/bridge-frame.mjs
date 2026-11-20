// The version 1 datagram envelope shared with touch_bridge.c.
export function pack(address, port, payload) {
  if (!(address instanceof Uint8Array) || ![4, 16].includes(address.length) ||
      !Number.isInteger(port) || port < 1 || port > 65535 ||
      !(payload instanceof Uint8Array) || !payload.length || payload.length > 65507)
    throw new RangeError('invalid bridge datagram');
  const result = new Uint8Array(address.length + 4 + payload.length);
  result[0] = 1;
  result[1] = address.length === 4 ? 4 : 6;
  result.set(address, 2);
  result[2 + address.length] = port >>> 8;
  result[3 + address.length] = port & 255;
  result.set(payload, address.length + 4);
  return result;
}

export function unpack(frame) {
  if (!(frame instanceof Uint8Array) || frame.length < 2 || frame[0] !== 1 || ![4, 6].includes(frame[1]))
    throw new RangeError('invalid bridge frame');
  const n = frame[1] === 4 ? 4 : 16;
  const header = n + 4;
  if (frame.length <= header || frame.length - header > 65507)
    throw new RangeError('invalid bridge payload');
  const port = (frame[n + 2] << 8) | frame[n + 3];
  if (!port) throw new RangeError('invalid bridge port');
  return {address: frame.slice(2, n + 2), port, payload: frame.slice(header)};
}
