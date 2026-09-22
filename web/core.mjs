export const PROTOCOL = Object.freeze({
  service: '7b100001-6c7d-4c7a-9a31-54bf3f010001',
  command: '7b100002-6c7d-4c7a-9a31-54bf3f010001',
  ack: '7b100003-6c7d-4c7a-9a31-54bf3f010001',
  commandMagic: 0xa1,
  ackMagic: 0xa2,
});

export const MODES = Object.freeze({ BACKGROUND: 0, FILL: 1, EDGE: 2, KEYPOINT: 3 });
export const ACK_STATUS = Object.freeze({
  APPLIED: 0,
  INVALID_PACKET: 1,
  INVALID_MODE: 2,
  PHYSICAL_CONFIRMED: 3,
  PHYSICAL_TIMEOUT: 4,
});
export const MODE_META = Object.freeze([
  { label: '背景', short: '静默', color: '#15212b' },
  { label: '主体内部', short: '弱振', color: '#5ea3b5' },
  { label: '主体轮廓', short: '强脉冲', color: '#ff725e' },
  { label: '关键点', short: '双脉冲', color: '#ffc857' },
]);

export function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}
export function mapPointerToGrid(clientX, clientY, rect, cols, rows) {
  const nx = clamp((clientX - rect.left) / Math.max(1, rect.width), 0, 0.999999);
  const ny = clamp((clientY - rect.top) / Math.max(1, rect.height), 0, 0.999999);
  return { x: Math.floor(nx * cols), y: Math.floor(ny * rows), nx, ny };
}

export function checksum(bytes, end = bytes.length) {
  let value = 0;
  for (let index = 0; index < end; index += 1) value ^= bytes[index];
  return value;
}

export function encodeCommand({ seq, mode, x, y, intensity = 180, durationMs = 60 }) {
  const packet = new Uint8Array(10);
  packet[0] = PROTOCOL.commandMagic;
  packet[1] = seq & 0xff;
  packet[2] = clamp(mode, 0, 3);
  packet[3] = x & 0xff;
  packet[4] = (x >> 8) & 0xff;
  packet[5] = y & 0xff;
  packet[6] = (y >> 8) & 0xff;
  packet[7] = clamp(intensity, 0, 255);
  packet[8] = clamp(Math.round(durationMs / 10), 1, 255);
  packet[9] = checksum(packet, 9);
  return packet;
}

export function decodeCommand(input) {
  const bytes = input instanceof Uint8Array ? input : new Uint8Array(input.buffer ?? input);
  if (bytes.length !== 10 || bytes[0] !== PROTOCOL.commandMagic) throw new Error('invalid command packet');
  if (checksum(bytes, 9) !== bytes[9]) throw new Error('command checksum mismatch');
  return {
    seq: bytes[1], mode: bytes[2], x: bytes[3] | (bytes[4] << 8),
    y: bytes[5] | (bytes[6] << 8), intensity: bytes[7], durationMs: bytes[8] * 10,
  };
}

export function encodeAck({ seq, mode, status = 0, x, y }) {
  const packet = new Uint8Array(9);
  packet[0] = PROTOCOL.ackMagic;
  packet[1] = seq & 0xff;
  packet[2] = clamp(mode, 0, 3);
  packet[3] = status & 0xff;
  packet[4] = x & 0xff;
  packet[5] = (x >> 8) & 0xff;
  packet[6] = y & 0xff;
  packet[7] = (y >> 8) & 0xff;
  packet[8] = checksum(packet, 8);
  return packet;
}

export function decodeAck(input) {
  const bytes = input instanceof Uint8Array
    ? input
    : new Uint8Array(input.buffer, input.byteOffset ?? 0, input.byteLength ?? input.buffer.byteLength);
  if (bytes.length !== 9 || bytes[0] !== PROTOCOL.ackMagic) throw new Error('invalid ack packet');
  if (checksum(bytes, 8) !== bytes[8]) throw new Error('ack checksum mismatch');
  return { seq: bytes[1], mode: bytes[2], status: bytes[3], x: bytes[4] | (bytes[5] << 8), y: bytes[6] | (bytes[7] << 8) };
}

export function classifyAck(sent, ack) {
  const mappingMatches = sent.seq === ack.seq && sent.mode === ack.mode && sent.x === ack.x && sent.y === ack.y;
  const accepted = mappingMatches && (ack.status === ACK_STATUS.APPLIED || ack.status === ACK_STATUS.PHYSICAL_CONFIRMED);
  return {
    mappingMatches,
    accepted,
    physicalConfirmed: accepted && ack.status === ACK_STATUS.PHYSICAL_CONFIRMED,
    physicalTimedOut: mappingMatches && ack.status === ACK_STATUS.PHYSICAL_TIMEOUT,
  };
}

export function buildTactileMap(rgba, width, height, cols = 64, rows = 48, options = {}) {
  const threshold = options.threshold ?? 142;
  const edgeThreshold = options.edgeThreshold ?? 92;
  const gray = new Float32Array(cols * rows);
  for (let gy = 0; gy < rows; gy += 1) {
    for (let gx = 0; gx < cols; gx += 1) {
      const x0 = Math.floor(gx * width / cols);
      const x1 = Math.max(x0 + 1, Math.floor((gx + 1) * width / cols));
      const y0 = Math.floor(gy * height / rows);
      const y1 = Math.max(y0 + 1, Math.floor((gy + 1) * height / rows));
      let total = 0; let count = 0;
      for (let y = y0; y < y1; y += 1) for (let x = x0; x < x1; x += 1) {
        const index = (y * width + x) * 4;
        total += 0.2126 * rgba[index] + 0.7152 * rgba[index + 1] + 0.0722 * rgba[index + 2];
        count += 1;
      }
      gray[gy * cols + gx] = total / count;
    }
  }

  const map = new Uint8Array(cols * rows);
  for (let y = 1; y < rows - 1; y += 1) for (let x = 1; x < cols - 1; x += 1) {
    const i = y * cols + x;
    const gx = -gray[i - cols - 1] + gray[i - cols + 1] - 2 * gray[i - 1] + 2 * gray[i + 1] - gray[i + cols - 1] + gray[i + cols + 1];
    const gy = -gray[i - cols - 1] - 2 * gray[i - cols] - gray[i - cols + 1] + gray[i + cols - 1] + 2 * gray[i + cols] + gray[i + cols + 1];
    const edge = Math.hypot(gx, gy) / 4;
    map[i] = edge >= edgeThreshold ? MODES.EDGE : (gray[i] < threshold ? MODES.FILL : MODES.BACKGROUND);
  }
  return { map, gray, cols, rows };
}

export function summarizeLatencies(values) {
  if (!values.length) return { average: null, p95: null, max: null };
  const sorted = [...values].sort((a, b) => a - b);
  return {
    average: Math.round(values.reduce((sum, value) => sum + value, 0) / values.length),
    p95: sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * 0.95) - 1)],
    max: sorted.at(-1),
  };
}
