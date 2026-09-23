// TouchScene TouchPuck BLE MVP — ESP32-C3 with Arduino-ESP32 BLE library
// Hardware: 5 V vibration motor -> AO3400 MOSFET drain; GPIO 4 -> gate via 100 ohm.
// Add a 10k gate pulldown, flyback diode across motor, and common ground.
// Optional physical feedback: SW-420 DO -> GPIO 5. Set ENABLE_VIBRATION_SENSOR to 1.

#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

constexpr uint8_t MOTOR_PIN = 4;
constexpr uint32_t COMMAND_TIMEOUT_MS = 600;
#ifndef ENABLE_VIBRATION_SENSOR
#define ENABLE_VIBRATION_SENSOR 0
#endif
#if ENABLE_VIBRATION_SENSOR
constexpr uint8_t VIBRATION_SENSOR_PIN = 5;
constexpr uint32_t SENSOR_ARM_DELAY_MS = 8;
constexpr uint32_t SENSOR_CONFIRM_TIMEOUT_MS = 160;
#endif

constexpr char SERVICE_UUID[] = "7b100001-6c7d-4c7a-9a31-54bf3f010001";
constexpr char COMMAND_UUID[] = "7b100002-6c7d-4c7a-9a31-54bf3f010001";
constexpr char ACK_UUID[] = "7b100003-6c7d-4c7a-9a31-54bf3f010001";

BLECharacteristic *ackCharacteristic = nullptr;
bool clientConnected = false;
uint8_t activeMode = 0;
uint8_t activeIntensity = 0;
uint16_t activeX = 0;
uint16_t activeY = 0;
uint32_t commandReceivedAt = 0;
uint32_t patternStartedAt = 0;

struct PendingPhysicalAck {
  bool active = false;
  uint8_t seq = 0;
  uint8_t mode = 0;
  uint16_t x = 0;
  uint16_t y = 0;
  uint32_t startedAt = 0;
  uint8_t baselineLevel = LOW;
};
PendingPhysicalAck pendingPhysicalAck;
#if ENABLE_VIBRATION_SENSOR
volatile bool sensorTransitionLatched = false;
void IRAM_ATTR onVibrationSensorChange() { sensorTransitionLatched = true; }
#endif

uint8_t xorChecksum(const uint8_t *bytes, size_t length) {
  uint8_t result = 0;
  for (size_t index = 0; index < length; index += 1) result ^= bytes[index];
  return result;
}
void motor(bool enabled) {
  digitalWrite(MOTOR_PIN, enabled ? HIGH : LOW);
}

void updateMotorPattern() {
  if (activeMode == 0 || millis() - commandReceivedAt > COMMAND_TIMEOUT_MS) {
    motor(false);
    if (millis() - commandReceivedAt > COMMAND_TIMEOUT_MS) activeMode = 0;
    return;
  }

  const uint32_t phase = millis() - patternStartedAt;
  if (activeMode == 1) motor((phase % 120) < 28);                 // interior: light pulses
  else if (activeMode == 2) motor((phase % 180) < 48);            // edge: distinct strong pulse
  else if (activeMode == 3) {                                     // key point: double pulse
    const uint32_t local = phase % 360;
    motor(local < 36 || (local >= 76 && local < 112));
  }
}

void sendAck(uint8_t seq, uint8_t mode, uint8_t status, uint16_t x, uint16_t y) {
  if (!clientConnected || ackCharacteristic == nullptr) return;
  uint8_t packet[9] = {
    0xA2, seq, mode, status,
    static_cast<uint8_t>(x & 0xff), static_cast<uint8_t>((x >> 8) & 0xff),
    static_cast<uint8_t>(y & 0xff), static_cast<uint8_t>((y >> 8) & 0xff), 0
  };
  packet[8] = xorChecksum(packet, 8);
  ackCharacteristic->setValue(packet, sizeof(packet));
  ackCharacteristic->notify();
}

void acknowledgeAppliedState(uint8_t seq, uint8_t mode, uint16_t x, uint16_t y, uint8_t sensorBaseline) {
#if ENABLE_VIBRATION_SENSOR
  if (mode != 0) {
    pendingPhysicalAck = {true, seq, mode, x, y, millis(), sensorBaseline};
    return;
  }
#endif
  sendAck(seq, mode, 0, x, y); // status 0: GPIO state applied, physical sensor not used.
}

void updatePhysicalFeedback() {
#if ENABLE_VIBRATION_SENSOR
  if (!pendingPhysicalAck.active) return;
  const uint32_t elapsed = millis() - pendingPhysicalAck.startedAt;
  if (elapsed >= SENSOR_ARM_DELAY_MS && (sensorTransitionLatched || digitalRead(VIBRATION_SENSOR_PIN) != pendingPhysicalAck.baselineLevel)) {
    sendAck(pendingPhysicalAck.seq, pendingPhysicalAck.mode, 3, pendingPhysicalAck.x, pendingPhysicalAck.y);
    Serial.println("ACK_PHYSICAL_CONFIRMED");
    sensorTransitionLatched = false;
    pendingPhysicalAck.active = false;
  } else if (elapsed >= SENSOR_CONFIRM_TIMEOUT_MS) {
    sendAck(pendingPhysicalAck.seq, pendingPhysicalAck.mode, 4, pendingPhysicalAck.x, pendingPhysicalAck.y);
    Serial.println("ACK_PHYSICAL_TIMEOUT");
    sensorTransitionLatched = false;
    pendingPhysicalAck.active = false;
  }
#endif
}

class ServerCallbacks final : public BLEServerCallbacks {
  void onConnect(BLEServer *) override { clientConnected = true; }
  void onDisconnect(BLEServer *server) override {
    clientConnected = false; activeMode = 0; pendingPhysicalAck.active = false; motor(false);
    server->getAdvertising()->start();
  }
};

class CommandCallbacks final : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *characteristic) override {
    auto value = characteristic->getValue();
    if (value.length() != 10) return;
    uint8_t packet[10];
    for (size_t index = 0; index < sizeof(packet); index += 1) packet[index] = static_cast<uint8_t>(value[index]);

    const uint8_t seq = packet[1];
    const uint16_t x = packet[3] | (static_cast<uint16_t>(packet[4]) << 8);
    const uint16_t y = packet[5] | (static_cast<uint16_t>(packet[6]) << 8);
    if (packet[0] != 0xA1 || xorChecksum(packet, 9) != packet[9]) {
      sendAck(seq, 0, 1, x, y); // status 1: invalid packet/checksum
      return;
    }
    if (packet[2] > 3) {
      sendAck(seq, 0, 2, x, y); // status 2: invalid mode
      return;
    }

    activeMode = packet[2]; activeIntensity = packet[7]; activeX = x; activeY = y;
    commandReceivedAt = millis(); patternStartedAt = millis();
    uint8_t sensorBaseline = LOW;
#if ENABLE_VIBRATION_SENSOR
    sensorTransitionLatched = false;
    sensorBaseline = digitalRead(VIBRATION_SENSOR_PIN); // Supports both active-HIGH and active-LOW modules.
#endif
    updateMotorPattern(); // Apply the first motor state before acknowledging.
    acknowledgeAppliedState(seq, activeMode, activeX, activeY, sensorBaseline);
  }
};

void setup() {
  pinMode(MOTOR_PIN, OUTPUT); motor(false);
#if ENABLE_VIBRATION_SENSOR
  pinMode(VIBRATION_SENSOR_PIN, INPUT_PULLUP);
  attachInterrupt(digitalPinToInterrupt(VIBRATION_SENSOR_PIN), onVibrationSensorChange, CHANGE);
#endif
  Serial.begin(115200);
  BLEDevice::init("TouchPuck-Haptic");
  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());
  BLEService *service = server->createService(SERVICE_UUID);

  BLECharacteristic *command = service->createCharacteristic(COMMAND_UUID, BLECharacteristic::PROPERTY_WRITE);
  command->setCallbacks(new CommandCallbacks());
  ackCharacteristic = service->createCharacteristic(ACK_UUID, BLECharacteristic::PROPERTY_NOTIFY | BLECharacteristic::PROPERTY_READ);
  ackCharacteristic->addDescriptor(new BLE2902());

  service->start();
  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(SERVICE_UUID);
  advertising->setScanResponse(true);
  BLEDevice::startAdvertising();
  Serial.println("TOUCHPUCK_BLE_READY");
}

void loop() {
  updateMotorPattern();
  updatePhysicalFeedback();
  delay(2);
}
