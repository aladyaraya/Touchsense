// TouchScene TouchPuck MVP — ESP32-C3 / Arduino
// Serial protocol: '0' background, '1' inside, '2' edge, '3' key point.
// Loads MUST use external MOSFETs and flyback diodes. Do not power them from GPIO.

constexpr uint8_t MOTOR_PIN = 4;
constexpr uint8_t SOLENOID_PIN = 5;
constexpr uint32_t BAUD = 115200;
constexpr uint32_t SOLENOID_PULSE_MS = 70;
constexpr uint32_t SOLENOID_COOLDOWN_MS = 250;

char tactileMode = '0';
bool solenoidOn = false;
uint32_t solenoidStartedAt = 0;
uint32_t lastSolenoidAt = 0;
uint32_t lastMotorToggleAt = 0;
bool motorOn = false;
bool secondTapPending = false;
uint32_t secondTapAt = 0;

void setMotor(bool on) {
  motorOn = on;
  digitalWrite(MOTOR_PIN, on ? HIGH : LOW);
}

void requestTap() {
  const uint32_t now = millis();
  if (!solenoidOn && now - lastSolenoidAt >= SOLENOID_COOLDOWN_MS) {
    digitalWrite(SOLENOID_PIN, HIGH);
    solenoidOn = true;
    solenoidStartedAt = now;
    lastSolenoidAt = now;
  }
}

void applyCommand(char command) {
  if (command < '0' || command > '3') return;
  tactileMode = command;
  if (command == '0' || command == '2') setMotor(false);
  if (command == '2' || command == '3') requestTap();
  if (command == '3') {
    secondTapPending = true;
    secondTapAt = millis() + SOLENOID_COOLDOWN_MS;
  }
}

void setup() {
  pinMode(MOTOR_PIN, OUTPUT);
  pinMode(SOLENOID_PIN, OUTPUT);
  digitalWrite(MOTOR_PIN, LOW);
  digitalWrite(SOLENOID_PIN, LOW);
  Serial.begin(BAUD);
  delay(200);
  Serial.println("TOUCHPUCK_READY");
}

void loop() {
  while (Serial.available()) applyCommand(static_cast<char>(Serial.read()));

  const uint32_t now = millis();
  if (solenoidOn && now - solenoidStartedAt >= SOLENOID_PULSE_MS) {
    digitalWrite(SOLENOID_PIN, LOW);
    solenoidOn = false;
  }

  if (secondTapPending && static_cast<int32_t>(now - secondTapAt) >= 0) {
    requestTap();
    secondTapPending = false;
  }

  // Interior: simple pulse pattern. Key point: faster pattern plus double solenoid tap.
  const uint32_t interval = tactileMode == '3' ? 70 : 110;
  if ((tactileMode == '1' || tactileMode == '3') && now - lastMotorToggleAt >= interval) {
    setMotor(!motorOn);
    lastMotorToggleAt = now;
  } else if (tactileMode != '1' && tactileMode != '3' && motorOn) {
    setMotor(false);
  }
}
