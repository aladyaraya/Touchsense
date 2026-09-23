import test from 'node:test';
import assert from 'node:assert/strict';
import { MODES, ACK_STATUS, mapPointerToGrid, encodeCommand, decodeCommand, encodeAck, decodeAck, classifyAck, buildTactileMap, summarizeLatencies } from '../web/core.mjs';

test('touch coordinates map to stable bounded grid cells', () => {
  const rect = { left: 10, top: 20, width: 320, height: 240 };
  const start = mapPointerToGrid(10, 20, rect, 64, 48);
  assert.deepEqual({ x: start.x, y: start.y }, { x: 0, y: 0 });
  const center = mapPointerToGrid(170, 140, rect, 64, 48);
  assert.equal(center.x, 32); assert.equal(center.y, 24);
  const end = mapPointerToGrid(999, 999, rect, 64, 48);
  assert.equal(end.x, 63); assert.equal(end.y, 47);
});

test('command packet round-trips coordinates, mode and duration', () => {
  const packet = encodeCommand({ seq: 254, mode: MODES.EDGE, x: 63, y: 47, intensity: 221, durationMs: 73 });
  assert.equal(packet.length, 10);
  assert.deepEqual(decodeCommand(packet), { seq: 254, mode: 2, x: 63, y: 47, intensity: 221, durationMs: 70 });
});

test('checksum corruption is rejected', () => {
  const packet = encodeCommand({ seq: 1, mode: 1, x: 4, y: 5 });
  packet[5] ^= 0xff;
  assert.throws(() => decodeCommand(packet), /checksum/);
});

test('ack proves the applied state matches the sent state', () => {
  const ack = encodeAck({ seq: 7, mode: MODES.KEYPOINT, status: 0, x: 52, y: 31 });
  assert.deepEqual(decodeAck(new DataView(ack.buffer)), { seq: 7, mode: 3, status: 0, x: 52, y: 31 });
});

test('ack classification separates coordinate integrity from physical actuation', () => {
  const sent = { seq: 8, mode: MODES.EDGE, x: 12, y: 23 };
  assert.deepEqual(classifyAck(sent, { ...sent, status: ACK_STATUS.APPLIED }), {
    mappingMatches: true, accepted: true, physicalConfirmed: false, physicalTimedOut: false,
  });
  assert.deepEqual(classifyAck(sent, { ...sent, status: ACK_STATUS.PHYSICAL_CONFIRMED }), {
    mappingMatches: true, accepted: true, physicalConfirmed: true, physicalTimedOut: false,
  });
  assert.deepEqual(classifyAck(sent, { ...sent, status: ACK_STATUS.PHYSICAL_TIMEOUT }), {
    mappingMatches: true, accepted: false, physicalConfirmed: false, physicalTimedOut: true,
  });
  assert.equal(classifyAck(sent, { ...sent, x: 13, status: ACK_STATUS.PHYSICAL_CONFIRMED }).accepted, false);
});

test('image processor separates dark fill and high-contrast edge', () => {
  const width = 16; const height = 16; const rgba = new Uint8Array(width * height * 4);
  for (let y = 0; y < height; y += 1) for (let x = 0; x < width; x += 1) {
    const dark = x >= 5 && x <= 10 && y >= 5 && y <= 10; const value = dark ? 20 : 240; const i = (y * width + x) * 4;
    rgba[i] = value; rgba[i + 1] = value; rgba[i + 2] = value; rgba[i + 3] = 255;
  }
  const { map } = buildTactileMap(rgba, width, height, 16, 16, { threshold: 120, edgeThreshold: 40 });
  assert.equal(map[8 * 16 + 8], MODES.FILL);
  assert.equal(map[2 * 16 + 2], MODES.BACKGROUND);
  assert.equal(map.some(mode => mode === MODES.EDGE), true);
});

test('latency summary reports average, p95 and max', () => {
  assert.deepEqual(summarizeLatencies([10, 20, 30, 40, 100]), { average: 40, p95: 100, max: 100 });
  assert.deepEqual(summarizeLatencies([]), { average: null, p95: null, max: null });
});
