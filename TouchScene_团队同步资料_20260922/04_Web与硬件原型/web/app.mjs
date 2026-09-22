import { PROTOCOL, MODES, MODE_META, ACK_STATUS, mapPointerToGrid, encodeCommand, decodeAck, encodeAck, classifyAck, buildTactileMap, summarizeLatencies } from './core.mjs';

const $ = (id) => document.getElementById(id);
const query = new URLSearchParams(location.search);
const mockStatus = query.get('mockFeedback') === 'physical' ? ACK_STATUS.PHYSICAL_CONFIRMED
  : query.get('mockFeedback') === 'timeout' ? ACK_STATUS.PHYSICAL_TIMEOUT : ACK_STATUS.APPLIED;
const inputCanvas = $('inputCanvas');
const outputCanvas = $('outputCanvas');
const inputContext = inputCanvas.getContext('2d', { willReadFrequently: true });
const outputContext = outputCanvas.getContext('2d');
const state = {
  cols: 64, rows: 48, tactileMap: new Uint8Array(64 * 48), threshold: 142, edgeThreshold: 92,
  transport: 'phone', active: false, pointer: null, lastMode: MODES.BACKGROUND,
  lastPhonePulse: 0, packetCount: 0, ackCount: 0, latencies: [], eventLog: [], acceptanceReport: null,
};

function log(message, tone = '') {
  state.eventLog.unshift({ message, tone, at: new Date().toLocaleTimeString('zh-CN', { hour12: false }) });
  state.eventLog = state.eventLog.slice(0, 8);
  $('eventLog').innerHTML = state.eventLog.map(item => `<li class="${item.tone}"><time>${item.at}</time><span>${item.message}</span></li>`).join('');
}

function setCapability() {
  const vibration = 'vibrate' in navigator;
  const bluetooth = 'bluetooth' in navigator;
  $('vibrationCap').textContent = vibration ? '可调用' : '不可用';
  $('vibrationCap').className = vibration ? 'ok' : 'bad';
  $('bluetoothCap').textContent = bluetooth ? '可调用' : '不可用';
  $('bluetoothCap').className = bluetooth ? 'ok' : 'bad';
  $('secureCap').textContent = window.isSecureContext ? 'HTTPS' : '需要 HTTPS';
  $('secureCap').className = window.isSecureContext ? 'ok' : 'bad';
}

function drawSample() {
  const { width, height } = inputCanvas;
  const gradient = inputContext.createLinearGradient(0, 0, width, height);
  gradient.addColorStop(0, '#f7fafb'); gradient.addColorStop(1, '#cfd9df');
  inputContext.fillStyle = gradient; inputContext.fillRect(0, 0, width, height);
  inputContext.fillStyle = '#17242d'; inputContext.strokeStyle = '#17242d'; inputContext.lineCap = 'round'; inputContext.lineJoin = 'round';
  inputContext.beginPath(); inputContext.arc(width * 0.35, height * 0.48, height * 0.19, 0, Math.PI * 2); inputContext.fill();
  inputContext.lineWidth = 28; inputContext.beginPath(); inputContext.moveTo(width * 0.52, height * 0.72); inputContext.lineTo(width * 0.7, height * 0.25); inputContext.lineTo(width * 0.87, height * 0.72); inputContext.closePath(); inputContext.stroke();
  inputContext.fillStyle = '#ff725e'; inputContext.beginPath(); inputContext.arc(width * 0.7, height * 0.25, 11, 0, Math.PI * 2); inputContext.fill();
  processImage();
}

function processImage() {
  const pixels = inputContext.getImageData(0, 0, inputCanvas.width, inputCanvas.height);
  const result = buildTactileMap(pixels.data, pixels.width, pixels.height, state.cols, state.rows, { threshold: state.threshold, edgeThreshold: state.edgeThreshold });
  state.tactileMap = result.map;
  const counts = [0, 0, 0, 0]; state.tactileMap.forEach(mode => counts[mode] += 1);
  $('edgeCount').textContent = counts[MODES.EDGE]; $('fillCount').textContent = counts[MODES.FILL];
  renderMap();
}

function renderMap() {
  const scale = 8; outputCanvas.width = state.cols * scale; outputCanvas.height = state.rows * scale;
  for (let y = 0; y < state.rows; y += 1) for (let x = 0; x < state.cols; x += 1) {
    const mode = state.tactileMap[y * state.cols + x];
    outputContext.fillStyle = MODE_META[mode].color;
    outputContext.fillRect(x * scale, y * scale, scale + 0.5, scale + 0.5);
  }
  if (state.pointer) {
    outputContext.strokeStyle = '#fff'; outputContext.lineWidth = 2.5;
    outputContext.beginPath(); outputContext.arc((state.pointer.x + 0.5) * scale, (state.pointer.y + 0.5) * scale, 7, 0, Math.PI * 2); outputContext.stroke();
  }
}

class PhoneHaptics {
  static pulse(mode, force = false) {
    if (!('vibrate' in navigator)) return false;
    const now = performance.now();
    const minimumGap = mode === MODES.FILL ? 105 : 135;
    if (!force && mode === state.lastMode && now - state.lastPhonePulse < minimumGap) return true;
    state.lastPhonePulse = now;
    const pattern = mode === MODES.FILL ? 22 : mode === MODES.EDGE ? 42 : mode === MODES.KEYPOINT ? [32, 34, 32] : 0;
    return navigator.vibrate(pattern);
  }
  static stop() { if ('vibrate' in navigator) navigator.vibrate(0); }
}

class BleHaptics {
  constructor() { this.device = null; this.command = null; this.ack = null; this.seq = 0; this.pending = new Map(); this.latest = null; this.writing = false; this.awaitingAck = false; this.mock = false; this.mockStatus = ACK_STATUS.APPLIED; this.lastAckResult = null; }
  async connect() {
    if (!navigator.bluetooth) throw new Error('此浏览器没有 Web Bluetooth');
    this.device = await navigator.bluetooth.requestDevice({ filters: [{ namePrefix: 'TouchPuck' }], optionalServices: [PROTOCOL.service] });
    this.device.addEventListener('gattserverdisconnected', () => this.disconnected());
    const server = await this.device.gatt.connect();
    const service = await server.getPrimaryService(PROTOCOL.service);
    this.command = await service.getCharacteristic(PROTOCOL.command);
    this.ack = await service.getCharacteristic(PROTOCOL.ack);
    await this.ack.startNotifications();
    this.ack.addEventListener('characteristicvaluechanged', event => this.handleAck(event.target.value));
    this.mock = false; $('bleState').textContent = 'ESP32 已连接'; $('bleState').className = 'status-chip online';
    log(`已连接 ${this.device.name || 'TouchPuck'}`, 'success');
  }
  useMock(status = ACK_STATUS.APPLIED) { this.mock = true; this.mockStatus = status; $('bleState').textContent = '模拟 ESP32'; $('bleState').className = 'status-chip online'; log('已启用 BLE 回环模拟器', 'success'); }
  disconnected() { this.command = null; this.ack = null; this.awaitingAck = false; this.pending.clear(); $('bleState').textContent = 'ESP32 未连接'; $('bleState').className = 'status-chip'; log('BLE 已断开，触觉输出停止', 'error'); }
  enqueue(data) { this.latest = data; this.flush(); }
  async transact(data, timeoutMs = 700) {
    const idleStart = performance.now();
    while (this.writing || this.awaitingAck) {
      if (performance.now() - idleStart > timeoutMs) throw new Error('等待上一条 ACK 超时');
      await new Promise(resolve => setTimeout(resolve, 8));
    }
    this.lastAckResult = null;
    this.enqueue(data);
    const expectedSeq = this.seq;
    const startedAt = performance.now();
    while (performance.now() - startedAt <= timeoutMs) {
      if (this.lastAckResult?.ack.seq === expectedSeq) return this.lastAckResult;
      await new Promise(resolve => setTimeout(resolve, 8));
    }
    throw new Error(`序号 ${expectedSeq} 验收超时`);
  }
  async flush() {
    if (this.writing || this.awaitingAck || !this.latest) return;
    if (!this.mock && !this.command) return;
    this.writing = true;
    const data = this.latest; this.latest = null;
    const seq = this.seq = (this.seq + 1) & 0xff;
    const packet = encodeCommand({ seq, ...data });
    this.pending.set(seq, { seq, sentAt: performance.now(), ...data });
    this.awaitingAck = true;
    state.packetCount += 1; updateMetrics();
    try {
      if (this.mock) {
        await new Promise(resolve => setTimeout(resolve, 18 + Math.random() * 18));
        const status = data.mode === MODES.BACKGROUND ? ACK_STATUS.APPLIED : this.mockStatus;
        const ack = encodeAck({ seq, mode: data.mode, status, x: data.x, y: data.y });
        this.handleAck(new DataView(ack.buffer));
      } else {
        await this.command.writeValueWithResponse(packet);
      }
    } catch (error) { log(`发送失败：${error.message}`, 'error'); }
    finally { this.writing = false; if (this.latest) this.flush(); }
  }
  handleAck(value) {
    try {
      const ack = decodeAck(value); const pending = this.pending.get(ack.seq);
      if (!pending) return;
      this.pending.delete(ack.seq);
      this.awaitingAck = false;
      const rtt = Math.round(performance.now() - pending.sentAt);
      const result = classifyAck(pending, ack);
      this.lastAckResult = { ack, result, rtt };
      state.ackCount += 1; state.latencies.push(rtt); state.latencies = state.latencies.slice(-60);
      $('mappingState').textContent = result.mappingMatches ? '坐标 ACK 一致' : '映射异常'; $('mappingState').className = result.mappingMatches ? 'ok' : 'bad';
      if (result.physicalConfirmed) { $('feedbackState').textContent = '物理振动已检测'; $('feedbackState').className = 'ok'; }
      else if (result.physicalTimedOut) { $('feedbackState').textContent = '未检测到振动'; $('feedbackState').className = 'bad'; log('坐标正确，但振动传感器在窗口内没有响应', 'error'); }
      else if (result.accepted) { $('feedbackState').textContent = 'GPIO 已执行（无传感器）'; $('feedbackState').className = ''; }
      else { $('feedbackState').textContent = `执行失败 · 状态 ${ack.status}`; $('feedbackState').className = 'bad'; }
      if (!result.mappingMatches) log(`ACK 不一致：发送 ${pending.x},${pending.y}/${pending.mode}，返回 ${ack.x},${ack.y}/${ack.mode}`, 'error');
      updateMetrics();
      if (this.latest) this.flush();
    } catch (error) { log(`ACK 校验失败：${error.message}`, 'error'); }
  }
  watchdog() {
    const now = performance.now();
    for (const [seq, pending] of this.pending) if (now - pending.sentAt > 450) {
      this.pending.delete(seq); $('mappingState').textContent = 'ACK 超时'; $('mappingState').className = 'bad'; log(`序号 ${seq} 超过 450 ms 未确认`, 'error');
      this.awaitingAck = false; $('feedbackState').textContent = '设备未确认'; $('feedbackState').className = 'bad'; if (this.latest) this.flush();
    }
  }
}

const ble = new BleHaptics();

function updateMetrics() {
  const stats = summarizeLatencies(state.latencies);
  $('packetCount').textContent = state.packetCount;
  $('ackCount').textContent = state.ackCount;
  $('rttValue').textContent = stats.average == null ? '—' : `${stats.average} ms`;
  $('p95Value').textContent = stats.p95 == null ? '—' : `${stats.p95} ms`;
}

function output(mode, x, y, force = false) {
  const meta = MODE_META[mode];
  $('currentMode').textContent = `${meta.label} · ${meta.short}`; $('currentMode').style.setProperty('--mode-color', meta.color);
  $('coordValue').textContent = `${x}, ${y}`;
  if (state.transport === 'phone') {
    const accepted = PhoneHaptics.pulse(mode, force);
    $('mappingState').textContent = accepted ? '触发已提交' : '系统拒绝振动'; $('mappingState').className = accepted ? 'ok' : 'bad';
  } else if (state.transport === 'ble') {
    ble.enqueue({ mode, x, y, intensity: mode === MODES.FILL ? 105 : 220, durationMs: mode === MODES.FILL ? 30 : 60 });
  }
  state.lastMode = mode;
}

function explore(event) {
  if (!state.active && event.type !== 'pointerdown') return;
  if (event.type === 'pointerdown') { state.active = true; outputCanvas.setPointerCapture?.(event.pointerId); }
  const point = mapPointerToGrid(event.clientX, event.clientY, outputCanvas.getBoundingClientRect(), state.cols, state.rows);
  const mode = state.tactileMap[point.y * state.cols + point.x];
  state.pointer = point; renderMap(); output(mode, point.x, point.y, event.type === 'pointerdown');
}

function stopExplore() {
  if (!state.active) return;
  state.active = false; PhoneHaptics.stop();
  if (state.transport === 'ble' && state.pointer) ble.enqueue({ mode: MODES.BACKGROUND, x: state.pointer.x, y: state.pointer.y, intensity: 0, durationMs: 20 });
  $('currentMode').textContent = '抬起手指 · 已停止';
}

outputCanvas.addEventListener('pointerdown', explore);
outputCanvas.addEventListener('pointermove', explore);
outputCanvas.addEventListener('pointerup', stopExplore);
outputCanvas.addEventListener('pointercancel', stopExplore);

$('sourceInput').addEventListener('change', event => {
  const file = event.target.files?.[0]; if (!file) return;
  const image = new Image();
  image.onload = () => {
    inputContext.fillStyle = '#f5f7f8'; inputContext.fillRect(0, 0, inputCanvas.width, inputCanvas.height);
    const scale = Math.min(inputCanvas.width / image.width, inputCanvas.height / image.height);
    const width = image.width * scale; const height = image.height * scale;
    inputContext.drawImage(image, (inputCanvas.width - width) / 2, (inputCanvas.height - height) / 2, width, height);
    URL.revokeObjectURL(image.src); processImage(); log('照片已在本机完成触觉化', 'success');
  };
  image.src = URL.createObjectURL(file);
});

$('threshold').addEventListener('input', event => { state.threshold = Number(event.target.value); $('thresholdValue').textContent = state.threshold; processImage(); });
$('edgeThreshold').addEventListener('input', event => { state.edgeThreshold = Number(event.target.value); $('edgeValue').textContent = state.edgeThreshold; processImage(); });
$('sampleButton').addEventListener('click', () => { drawSample(); log('已恢复几何测试图'); });
document.querySelectorAll('[name="transport"]').forEach(input => input.addEventListener('change', event => {
  PhoneHaptics.stop(); state.transport = event.target.value; $('phonePanel').hidden = state.transport !== 'phone'; $('blePanel').hidden = state.transport !== 'ble';
}));
$('phoneTest').addEventListener('click', async () => {
  if (!('vibrate' in navigator)) { log('当前浏览器不支持 Vibration API', 'error'); return; }
  const modes = [MODES.FILL, MODES.EDGE, MODES.KEYPOINT];
  for (const mode of modes) { PhoneHaptics.pulse(mode, true); $('currentMode').textContent = `${MODE_META[mode].label}测试`; await new Promise(resolve => setTimeout(resolve, 420)); }
  PhoneHaptics.stop(); log('手机三段触觉自检完成', 'success');
});
$('bleConnect').addEventListener('click', async () => { try { await ble.connect(); } catch (error) { log(`连接未完成：${error.message}`, 'error'); } });
$('mockConnect').addEventListener('click', () => ble.useMock(mockStatus));
$('bleTest').addEventListener('click', async () => {
  if (!ble.command && !ble.mock) { log('请先连接 ESP32 或启用模拟设备', 'error'); return; }
  const sequence = [MODES.FILL, MODES.EDGE, MODES.KEYPOINT, MODES.BACKGROUND];
  for (let index = 0; index < sequence.length; index += 1) { ble.enqueue({ mode: sequence[index], x: 10 + index, y: 8, intensity: 180, durationMs: 60 }); await new Promise(resolve => setTimeout(resolve, 320)); }
  log('BLE 四状态自检已发送', 'success');
});
$('bleAcceptance').addEventListener('click', async event => {
  if (!ble.command && !ble.mock) { log('请先连接 ESP32 或启用模拟设备', 'error'); return; }
  const button = event.currentTarget; const status = $('acceptanceState');
  button.disabled = true; $('exportAcceptance').disabled = true; state.acceptanceReport = null; status.dataset.result = 'running';
  let mapped = 0; let physical = 0; let completed = 0;
  const records = []; let failure = null;
  try {
    for (let index = 0; index < 100; index += 1) {
      const mode = 1 + (index % 3); const x = (index * 17) % state.cols; const y = (index * 11) % state.rows;
      const receipt = await ble.transact({ mode, x, y, intensity: mode === MODES.FILL ? 105 : 220, durationMs: 60 });
      if (receipt.result.mappingMatches) mapped += 1;
      if (receipt.result.physicalConfirmed) physical += 1;
      records.push({ index: index + 1, seq: receipt.ack.seq, x, y, mode, ackStatus: receipt.ack.status, rttMs: receipt.rtt, mappingMatches: receipt.result.mappingMatches, physicalConfirmed: receipt.result.physicalConfirmed });
      completed += 1; status.textContent = `验收中 ${completed}/100 · 映射 ${mapped} · 物理确认 ${physical}`;
    }
    await ble.transact({ mode: MODES.BACKGROUND, x: 0, y: 0, intensity: 0, durationMs: 20 });
    const mappingPassed = mapped === 100; const physicalPassed = physical >= 99;
    status.dataset.result = mappingPassed ? (physicalPassed ? 'physical-pass' : 'logic-pass') : 'fail';
    status.textContent = `${mappingPassed ? '映射 100/100' : `映射 ${mapped}/100`} · ${physicalPassed ? `物理确认 ${physical}/100，达标` : physical ? `物理确认 ${physical}/100，未达 99%` : '未启用物理传感器'}`;
    status.className = mappingPassed && (!physical || physicalPassed) ? 'ok' : 'bad';
    log(`100 点验收完成：映射 ${mapped}，物理确认 ${physical}`, mappingPassed && (!physical || physicalPassed) ? 'success' : 'error');
  } catch (error) {
    failure = error.message;
    status.dataset.result = 'fail'; status.className = 'bad'; status.textContent = `验收中止：${error.message}`; log(status.textContent, 'error');
  } finally {
    const latency = summarizeLatencies(records.map(record => record.rttMs));
    state.acceptanceReport = {
      schema: 'touchpuck-acceptance/v1', generatedAt: new Date().toISOString(),
      device: { transport: ble.mock ? 'mock' : 'web-bluetooth', name: ble.device?.name || (ble.mock ? 'TouchPuck Mock' : 'TouchPuck-Haptic') },
      configuration: { grid: { columns: state.cols, rows: state.rows }, targetCommands: 100, physicalPassThreshold: 99 },
      summary: { completed, mappingMatched: mapped, physicalConfirmed: physical, averageRttMs: latency.average, p95RttMs: latency.p95, maxRttMs: latency.max, passedMapping: completed === 100 && mapped === 100, passedPhysical: completed === 100 && physical >= 99, failure },
      commands: records,
    };
    $('exportAcceptance').disabled = records.length === 0; button.disabled = false;
  }
});
$('exportAcceptance').addEventListener('click', () => {
  if (!state.acceptanceReport) return;
  const blob = new Blob([`${JSON.stringify(state.acceptanceReport, null, 2)}\n`], { type: 'application/json' });
  const link = document.createElement('a'); const url = URL.createObjectURL(blob);
  link.href = url; link.download = `touchpuck-acceptance-${state.acceptanceReport.generatedAt.replace(/[:.]/g, '-')}.json`;
  document.body.appendChild(link); link.click(); link.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000);
  log('验收报告已导出，可用于现场复盘和提交证据', 'success');
});

setInterval(() => {
  ble.watchdog();
  if (state.active && state.pointer) {
    const mode = state.tactileMap[state.pointer.y * state.cols + state.pointer.x];
    output(mode, state.pointer.x, state.pointer.y);
  }
}, 180);

if ('serviceWorker' in navigator) navigator.serviceWorker.register('./sw.js').catch(() => {});
setCapability(); drawSample(); updateMetrics(); log('Demo 已就绪；先选择手机振动或 BLE 输出', 'success');

if (query.has('selftest')) {
  const bleRadio = document.querySelector('[name="transport"][value="ble"]');
  bleRadio.checked = true; bleRadio.dispatchEvent(new Event('change', { bubbles: true }));
  ble.useMock(mockStatus);
  const rect = outputCanvas.getBoundingClientRect();
  outputCanvas.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, pointerId: 77, clientX: rect.left + rect.width * 0.35, clientY: rect.top + rect.height * 0.48 }));
  setTimeout(() => outputCanvas.dispatchEvent(new PointerEvent('pointermove', { bubbles: true, pointerId: 77, clientX: rect.left + rect.width * 0.7, clientY: rect.top + rect.height * 0.28 })), 280);
  setTimeout(() => outputCanvas.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, pointerId: 77, clientX: rect.left + rect.width * 0.7, clientY: rect.top + rect.height * 0.28 })), 650);
  setTimeout(() => {
    const passed = state.ackCount >= 2 && $('mappingState').textContent === '坐标 ACK 一致';
    document.documentElement.dataset.selftest = passed ? 'pass' : 'fail';
    document.documentElement.dataset.selftestAcks = String(state.ackCount);
  }, 1050);
}
