// StickCommander serial adapter for M5StickC Plus 2 (ESP32-PICO-V3-02 + CH9102).
//
// Implements the line protocol consumed by the StickCommander Android app:
//   CMD:BTN / CMD:NAV / CMD:TEXT / CMD:SYS:STATUS / CMD:PING
//   CMD:SCRIPT:RUN / CMD:SCRIPT:LOAD / CMD:SCRIPT:LIST / CMD:SCRIPT:STOP
//
// Device -> phone:
//   STATUS:BAT:mv:pct, STATUS:UPTIME:sec, STATUS:FW:text
//   LOG:I|W|E|B:tag:message, DISPLAY:text, SCRIPTS:..., PONG, OK/ERR
//
// Build: arduino-cli compile --fqbn esp32:esp32:esp32 stickcommander_adapter
// Flash: arduino-cli upload -p COM3 --fqbn esp32:esp32:esp32 stickcommander_adapter

#include <Arduino.h>
#include <Wire.h>
#include <cstring>

namespace {

constexpr const char *FIRMWARE_VERSION = "StickCommander-Adapter 1.0";
constexpr uint8_t AXP192_ADDRESS = 0x34;
constexpr uint8_t AXP192_REG_BAT_VOLTAGE = 0x78;
constexpr uint8_t AXP192_REG_ADC_ENABLE_1 = 0x82;
constexpr int I2C_SDA = 21;
constexpr int I2C_SCL = 22;
constexpr uint32_t TELEMETRY_INTERVAL_MS = 5000;
constexpr size_t MAX_LINE_LENGTH = 24576;
constexpr size_t MAX_UPLOADED_SOURCE = 16384;

String rxLine;
String uploadedName;
String uploadedSource;
uint32_t lastTelemetryMs = 0;
bool batteryAdcEnabled = false;
bool warnedBatteryRead = false;

bool axpRead(uint8_t reg, uint8_t *output, size_t length) {
  Wire.beginTransmission(AXP192_ADDRESS);
  Wire.write(reg);
  if (Wire.endTransmission(false) != 0) {
    return false;
  }
  if (Wire.requestFrom((uint8_t)AXP192_ADDRESS, (uint8_t)length) != length) {
    return false;
  }
  for (size_t i = 0; i < length; ++i) {
    output[i] = Wire.read();
  }
  return true;
}

bool axpWrite(uint8_t reg, uint8_t value) {
  Wire.beginTransmission(AXP192_ADDRESS);
  Wire.write(reg);
  Wire.write(value);
  return Wire.endTransmission() == 0;
}

bool readBatteryMillivolts(uint16_t &millivolts) {
  uint8_t buffer[2];
  if (!axpRead(AXP192_REG_BAT_VOLTAGE, buffer, sizeof(buffer))) {
    return false;
  }
  // AXP192: 13-bit battery voltage, 1.1 mV per LSB.
  const uint16_t raw = ((uint16_t)(buffer[0] & 0x3F) << 8) | buffer[1];
  millivolts = (uint16_t)(raw * 1.1f);
  return millivolts > 2500;
}

int batteryPercent(uint16_t millivolts) {
  if (millivolts >= 4200) return 100;
  if (millivolts <= 3300) return 0;
  return (int)((millivolts - 3300L) * 100L / 900L);
}

String sanitizeLog(String value) {
  value.replace('\n', ' ');
  value.replace('\r', ' ');
  return value;
}

String escapeDisplay(String value) {
  value.replace("\\", "\\\\");
  value.replace("\n", "\\n");
  value.replace("\r", "\\r");
  return value;
}

String unescapeField(const String &value) {
  String output;
  output.reserve(value.length());
  for (size_t i = 0; i < value.length(); ++i) {
    const char c = value[i];
    if (c == '\\' && i + 1 < value.length()) {
      const char next = value[++i];
      switch (next) {
        case 'n': output += '\n'; break;
        case 'r': output += '\r'; break;
        case '\\': output += '\\'; break;
        case ':': output += ':'; break;
        default: output += next; break;
      }
    } else {
      output += c;
    }
  }
  return output;
}

String base64Decode(const String &input) {
  static const char *table =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  String output;
  output.reserve(input.length() * 3 / 4 + 4);
  uint32_t accumulator = 0;
  int bits = 0;
  for (size_t i = 0; i < input.length(); ++i) {
    const char c = input[i];
    if (c == '=') break;
    const char *position = strchr(table, c);
    if (position == nullptr) continue;
    accumulator = (accumulator << 6) | (uint32_t)(position - table);
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      output += (char)((accumulator >> bits) & 0xFF);
    }
  }
  return output;
}

void logLine(const char *level, const char *tag, const String &message) {
  Serial.printf("LOG:%s:%s:%s\n", level, tag, sanitizeLog(message).c_str());
}

void logInfo(const char *tag, const String &message) { logLine("I", tag, message); }
void logWarning(const char *tag, const String &message) { logLine("W", tag, message); }
void logError(const char *tag, const String &message) { logLine("E", tag, message); }

void displayLine(const String &text) {
  Serial.printf("DISPLAY:%s\n", escapeDisplay(text).c_str());
}

void emitStatus() {
  uint16_t millivolts = 0;
  if (readBatteryMillivolts(millivolts)) {
    warnedBatteryRead = false;
    Serial.printf("STATUS:BAT:%u:%d\n", (unsigned)millivolts,
                  batteryPercent(millivolts));
  } else {
    if (!warnedBatteryRead) {
      warnedBatteryRead = true;
      logWarning("axp", "battery voltage read failed");
    }
    Serial.println("STATUS:BAT:0:0");
  }
  Serial.printf("STATUS:UPTIME:%lu\n", (unsigned long)(millis() / 1000UL));
  Serial.printf("STATUS:FW:%s\n", FIRMWARE_VERSION);
}

void emitScriptList() {
  Serial.println("SCRIPTS:status_report.txt|echo_demo.txt");
  Serial.println("SCRIPTS:END");
}

void runUploadedScript() {
  logInfo("script", "running " + uploadedName);
  displayLine("Running " + uploadedName);
  int start = 0;
  int emitted = 0;
  while (start < (int)uploadedSource.length() && emitted < 16) {
    int newline = uploadedSource.indexOf('\n', start);
    String line = newline < 0 ? uploadedSource.substring(start)
                              : uploadedSource.substring(start, newline);
    line.trim();
    if (line.length() > 0) {
      logInfo("js", line);
      ++emitted;
    }
    if (newline < 0) break;
    start = newline + 1;
  }
  Serial.println("OK:script complete");
}

void runNamedScript(const String &name) {
  if (name == "status_report.txt" || name == "status_report") {
    emitStatus();
    logInfo("script", "status_report complete");
    displayLine("Status report sent");
    return;
  }
  if (name == "echo_demo.txt" || name == "echo_demo") {
    logInfo("demo", "echo line one");
    logInfo("demo", "echo line two");
    displayLine("Echo demo complete");
    return;
  }
  if (uploadedName.length() > 0 && name == uploadedName) {
    runUploadedScript();
    return;
  }
  Serial.printf("ERR:script not found: %s\n", sanitizeLog(name).c_str());
  displayLine("Script not found");
}

void handleScriptLoad(const String &payload) {
  const int separator = payload.indexOf(':');
  if (separator <= 0) {
    Serial.println("ERR:bad script payload");
    return;
  }
  const String name = base64Decode(payload.substring(0, separator));
  const String source = base64Decode(payload.substring(separator + 1));
  uploadedName = name;
  uploadedSource = source.length() > MAX_UPLOADED_SOURCE
                       ? source.substring(0, MAX_UPLOADED_SOURCE)
                       : source;
  logInfo("script", "uploaded " + name + " (" + String(source.length()) + " bytes)");
  displayLine("Loaded " + name);
  Serial.printf("OK:script %s\n", sanitizeLog(name).c_str());
}

void processLine(String line) {
  line.trim();
  if (line.length() == 0) return;

  if (line == "CMD:PING") {
    Serial.println("PONG");
    return;
  }
  if (line == "CMD:SYS:STATUS") {
    emitStatus();
    return;
  }
  if (line == "CMD:SCRIPT:LIST") {
    emitScriptList();
    return;
  }
  if (line == "CMD:SCRIPT:STOP") {
    logInfo("script", "stopped");
    displayLine("Script stopped");
    Serial.println("OK:script stopped");
    return;
  }
  if (line.startsWith("CMD:BTN:")) {
    String args = line.substring(8);
    logInfo("btn", args);
    displayLine("BTN " + args);
    return;
  }
  if (line.startsWith("CMD:NAV:")) {
    String args = line.substring(8);
    logInfo("nav", args);
    displayLine("NAV " + args);
    return;
  }
  if (line.startsWith("CMD:TEXT:")) {
    const String text = unescapeField(line.substring(9));
    logInfo("text", text);
    displayLine(text);
    return;
  }
  if (line.startsWith("CMD:SCRIPT:RUN:")) {
    runNamedScript(unescapeField(line.substring(15)));
    return;
  }
  if (line.startsWith("CMD:SCRIPT:LOAD:")) {
    handleScriptLoad(line.substring(16));
    return;
  }

  Serial.printf("ERR:unknown command: %s\n", sanitizeLog(line).c_str());
  displayLine("Unknown command");
}

}  // namespace

void setup() {
  Serial.begin(115200);
  delay(150);
  rxLine.reserve(1024);

  Wire.begin(I2C_SDA, I2C_SCL, 100000);
  uint8_t adcEnable = 0;
  if (axpRead(AXP192_REG_ADC_ENABLE_1, &adcEnable, 1)) {
    if ((adcEnable & 0x80) == 0) {
      batteryAdcEnabled = axpWrite(AXP192_REG_ADC_ENABLE_1, adcEnable | 0x80);
    } else {
      batteryAdcEnabled = true;
    }
  }

  delay(100);
  logInfo("boot", "StickCommander adapter ready, build " __DATE__ " " __TIME__);
  if (!batteryAdcEnabled) {
    logWarning("axp", "could not enable AXP192 battery ADC");
  }
  emitStatus();
  displayLine("StickCommander ready");
  lastTelemetryMs = millis();
}

void loop() {
  while (Serial.available() > 0) {
    const char c = (char)Serial.read();
    if (c == '\n') {
      String line = rxLine;
      rxLine = "";
      processLine(line);
    } else if (c != '\r') {
      if (rxLine.length() < MAX_LINE_LENGTH) {
        rxLine += c;
      } else {
        rxLine = "";
        logError("proto", "line too long, dropped");
      }
    }
  }

  const uint32_t now = millis();
  if (now - lastTelemetryMs >= TELEMETRY_INTERVAL_MS) {
    lastTelemetryMs = now;
    emitStatus();
  }
}
