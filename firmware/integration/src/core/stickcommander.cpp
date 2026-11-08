#include "stickcommander.h"

#include <FS.h>
#include <LittleFS.h>
#include <cctype>
#include <cstring>
#include <globals.h>

#include "core/sd_functions.h"
#include "core/utils.h"
#include "modules/bjs_interpreter/interpreter.h"
#include "modules/ir/custom_ir.h"

#if !defined(ANALOG_BAT_MULTIPLIER)
#define ANALOG_BAT_MULTIPLIER 2.0f
#endif

namespace {

constexpr const char *SC_FIRMWARE = "Bruce 1.16.1 + StickCommander";
constexpr uint32_t STATUS_INTERVAL_MS = 5000;
constexpr uint32_t CLIENT_TIMEOUT_MS = 60000;
constexpr size_t MAX_INPUT_LINE = 16384;

uint32_t lastClientActivityMs = 0;
uint32_t lastStatusMs = 0;
bool batteryAdcInitialized = false;
bool littleFsReady = false;

File fsUploadFile;
bool fsUploading = false;
String fsUploadPath;
size_t fsUploadWritten = 0;

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
    static const char *table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
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

String base64Encode(const uint8_t *data, size_t length) {
    static const char *table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    String output;
    output.reserve(((length + 2) / 3) * 4);
    size_t i = 0;
    while (i + 3 <= length) {
        const uint32_t value =
            ((uint32_t)data[i] << 16) | ((uint32_t)data[i + 1] << 8) | (uint32_t)data[i + 2];
        output += table[(value >> 18) & 0x3F];
        output += table[(value >> 12) & 0x3F];
        output += table[(value >> 6) & 0x3F];
        output += table[value & 0x3F];
        i += 3;
    }
    if (i < length) {
        uint32_t value = (uint32_t)data[i] << 16;
        if (i + 1 < length) value |= (uint32_t)data[i + 1] << 8;
        output += table[(value >> 18) & 0x3F];
        output += table[(value >> 12) & 0x3F];
        output += (i + 1 < length) ? table[(value >> 6) & 0x3F] : '=';
        output += '=';
    }
    return output;
}

String base64Encode(const String &value) {
    return base64Encode(reinterpret_cast<const uint8_t *>(value.c_str()), value.length());
}

void sendLine(const String &line) {
    if (serialDevice == nullptr) return;
    serialDevice->println(line);
}

void sendLog(const String &tag, const String &message) {
    sendLine("LOG:I:" + tag + ":" + sanitizeLog(message));
}

void sendWarning(const String &tag, const String &message) {
    sendLine("LOG:W:" + tag + ":" + sanitizeLog(message));
}

void sendError(const String &tag, const String &message) {
    sendLine("LOG:E:" + tag + ":" + sanitizeLog(message));
}

void sendDisplay(const String &text) { sendLine("DISPLAY:" + escapeDisplay(text)); }

uint16_t batteryMillivolts() {
#ifdef ANALOG_BAT_PIN
    if (!batteryAdcInitialized) {
        pinMode(ANALOG_BAT_PIN, INPUT);
        batteryAdcInitialized = true;
    }
    const uint32_t adcMillivolts = analogReadMilliVolts(ANALOG_BAT_PIN);
    if (adcMillivolts == 0) return 0;
    return (uint16_t)(adcMillivolts * ANALOG_BAT_MULTIPLIER);
#else
    return 0;
#endif
}

void sendStatus() {
    const uint16_t millivolts = batteryMillivolts();
    int percent = getBattery();
    if (millivolts > 2500 && percent <= 1) {
        percent = (int)((millivolts - 3300.0f) / 850.0f * 100.0f);
        if (percent < 0) percent = 0;
        if (percent > 100) percent = 100;
    }
    if (millivolts > 2500) {
        sendLine("STATUS:BAT:" + String(millivolts) + ":" + String(percent));
    } else {
        sendLine("STATUS:BAT:0:" + String(percent));
    }
    sendLine("STATUS:UPTIME:" + String((unsigned long)(millis() / 1000UL)));
    sendLine("STATUS:FW:" + String(SC_FIRMWARE));
}

void touchClient() {
    if (lastClientActivityMs == 0) {
        sendLog("sc", "StickCommander link active");
        sendDisplay("StickCommander connected");
        lastStatusMs = millis();
    }
    lastClientActivityMs = millis();
}

bool ensureLittleFs() {
    if (littleFsReady) return true;
    if (LittleFS.begin(false)) {
        littleFsReady = true;
        return true;
    }
    // A freshly erased Stick has no filesystem yet; create it once.
    if (LittleFS.begin(true)) {
        littleFsReady = true;
        sendLog("fs", "LittleFS formatted");
        return true;
    }
    sendError("fs", "LittleFS mount failed");
    return false;
}

String normalizeScriptName(String name) {
    name.trim();
    const int slash = name.lastIndexOf('/');
    if (slash >= 0) name = name.substring(slash + 1);
    name.replace("..", "_");
    if (name.length() == 0) name = "script";
    String lower = name;
    lower.toLowerCase();
    if (lower.endsWith(".txt")) name = name.substring(0, name.length() - 4);
    lower = name;
    lower.toLowerCase();
    if (!lower.endsWith(".js") && !lower.endsWith(".bjs")) name += ".js";
    return name;
}

String sanitizeScriptName(String name) {
    name = normalizeScriptName(name);

    String cleaned;
    cleaned.reserve(name.length());
    for (size_t i = 0; i < name.length(); ++i) {
        const char c = name[i];
        if (isalnum((unsigned char)c) || c == '.' || c == '_' || c == '-') {
            cleaned += c;
        } else {
            cleaned += '_';
        }
    }
    if (cleaned.length() == 0) cleaned = "script.js";
    return cleaned;
}

void injectButton(const String &button, const String &action) {
    if (action != "PRESS" && action != "CLICK") return; // release is a no-op

    if (button == "M5") {
        SelPress = true;
    } else if (button == "A") {
        UpPress = true;
    } else if (button == "B") {
        DownPress = true;
    } else if (button == "PWR") {
        EscPress = true; // safe "back" instead of cutting power
    } else {
        sendWarning("sc", "unknown button " + button);
        return;
    }
    AnyKeyPress = true;
    SerialCmdPress = true;
}

void handleButton(const String &arguments) {
    const int separator = arguments.indexOf(':');
    const String button = separator >= 0 ? arguments.substring(0, separator) : arguments;
    const String action = separator >= 0 ? arguments.substring(separator + 1) : "PRESS";
    sendLog("btn", button + ":" + action);
    sendDisplay("BTN " + button + " " + action);
    injectButton(button, action);
}

void injectNav(const String &direction, const String &action) {
    if (action != "PRESS" && action != "CLICK") return;

    if (direction == "UP") {
        UpPress = true;
    } else if (direction == "DOWN") {
        DownPress = true;
    } else if (direction == "LEFT") {
        PrevPress = true;
    } else if (direction == "RIGHT") {
        NextPress = true;
    } else if (direction == "OK") {
        SelPress = true;
    } else {
        sendWarning("sc", "unknown nav " + direction);
        return;
    }
    AnyKeyPress = true;
    SerialCmdPress = true;
}

void handleNav(const String &arguments) {
    const int separator = arguments.indexOf(':');
    const String direction = separator >= 0 ? arguments.substring(0, separator) : arguments;
    const String action = separator >= 0 ? arguments.substring(separator + 1) : "PRESS";
    sendLog("nav", direction + ":" + action);
    sendDisplay("NAV " + direction + " " + action);
    injectNav(direction, action);
}

#if !defined(LITE_VERSION) && !defined(DISABLE_INTERPRETER)
void appendScriptName(String &names, const String &name) {
    if (name.length() == 0) return;
    if (names.length() == 0) {
        names = name;
        return;
    }
    const String wrapped = "|" + names + "|";
    if (wrapped.indexOf("|" + name + "|") < 0) names += "|" + name;
}

void collectScriptFiles(FS &fs, const String &folder, String &names) {
    if (folder.length() == 0) return;
    File directory = fs.open(folder);
    if (!directory || !directory.isDirectory()) return;
    while (true) {
        bool isDirectory = false;
        String fullPath = directory.getNextFileName(&isDirectory);
        if (fullPath.length() == 0) break;
        if (isDirectory) continue;
        String name = fullPath.substring(fullPath.lastIndexOf('/') + 1);
        String lower = name;
        lower.toLowerCase();
        if (!lower.endsWith(".js") && !lower.endsWith(".bjs")) continue;
        appendScriptName(names, name);
    }
    directory.close();
}

void sendScriptList() {
    String names = "status_report";
    if (ensureLittleFs()) {
        collectScriptFiles(LittleFS, "/scripts", names);
        FS *fs = nullptr;
        const String folder = getScriptsFolder(fs);
        if (fs != nullptr) collectScriptFiles(*fs, folder, names);
    }

    sendLine("SCRIPTS:" + names);
    sendLine("SCRIPTS:END");
}

void runNamedScript(const String &rawName) {
    const String requested = rawName;
    if (requested == "status_report" || requested == "status_report.js") {
        sendStatus();
        sendLog("script", "status_report complete");
        sendDisplay("Status report sent");
        return;
    }
    if (requested == "echo_demo" || requested == "echo_demo.js") {
        sendLog("demo", "echo line one");
        sendLog("demo", "echo line two");
        sendDisplay("Echo demo complete");
        return;
    }

    const String name = normalizeScriptName(rawName);

    if (!ensureLittleFs()) {
        sendLine("ERR:filesystem unavailable");
        sendDisplay("Filesystem unavailable");
        return;
    }

    FS *fs = &LittleFS;
    String path = "/scripts/" + name;
    if (!LittleFS.exists(path)) {
        fs = nullptr;
        const String folder = getScriptsFolder(fs);
        if (folder.length() == 0 || fs == nullptr) {
            sendLine("ERR:script not found: " + name);
            sendDisplay("Script not found");
            return;
        }
        path = folder + "/" + name;
        if (!fs->exists(path)) {
            sendLine("ERR:script not found: " + name);
            sendDisplay("Script not found");
            return;
        }
    }

    sendLog("script", "running " + name);
    sendDisplay("Running " + name);
    if (!run_bjs_script_headless(*fs, path)) {
        sendLine("ERR:script failed: " + name);
        sendDisplay("Script failed");
    }
}

void handleScriptLoad(const String &payload) {
    const int separator = payload.indexOf(':');
    if (separator <= 0) {
        sendLine("ERR:bad script payload");
        return;
    }
    const String rawName = base64Decode(payload.substring(0, separator));
    const String source = base64Decode(payload.substring(separator + 1));
    if (source.length() == 0) {
        sendLine("ERR:empty script");
        return;
    }

    if (!ensureLittleFs()) {
        sendLine("ERR:filesystem unavailable");
        sendDisplay("Filesystem unavailable");
        return;
    }

    const String name = sanitizeScriptName(rawName);
    if (!LittleFS.exists("/scripts")) LittleFS.mkdir("/scripts");
    const String path = "/scripts/" + name;
    if (LittleFS.exists(path)) LittleFS.remove(path);
    File file = LittleFS.open(path, FILE_WRITE);
    if (!file) {
        sendLine("ERR:script save failed");
        sendDisplay("Script save failed");
        return;
    }
    const size_t written = file.print(source);
    file.close();
    if (written == 0) {
        sendLine("ERR:script save failed");
        return;
    }

    sendLine(String("OK:script ") + name + " (" + String(written) + " bytes)");
    sendLog("script", "uploaded " + name + " (" + String(written) + " bytes)");
    sendDisplay("Loaded " + name);
    if (!run_bjs_script_headless(LittleFS, path)) {
        sendLine("ERR:script failed: " + name);
        sendDisplay("Script failed");
    }
}
#else
void sendScriptList() { sendLine("SCRIPTS:END"); }
void runNamedScript(const String &) { sendLine("ERR:interpreter disabled"); }
void handleScriptLoad(const String &) { sendLine("ERR:interpreter disabled"); }
#endif

// ---------------------------------------------------------------- live menu

void sendMenuList() {
    if (options.empty()) {
        sendLine("MENU:NONE");
        return;
    }
    const String title = menuOptionLabel.length() > 0 ? menuOptionLabel : String("Menu");
    sendLine("MENU:TITLE:" + base64Encode(title));
    int selected = -1;
    for (size_t i = 0; i < options.size(); ++i) {
        if (options[i].selected) {
            selected = (int)i;
            break;
        }
    }
    sendLine("MENU:SEL:" + String(selected));
    for (size_t i = 0; i < options.size(); ++i) {
        sendLine(
            "MENU:ITEM:" + String((int)i) + ":" + String(options[i].enabled ? 1 : 0) + ":" +
            base64Encode(options[i].label)
        );
    }
    sendLine("MENU:END");
}

void handleMenuCommand(const String &rest) {
    String action = rest;
    action.toUpperCase();
    if (action == "LIST") {
        sendMenuList();
        return;
    }
    if (action.startsWith("OPEN:")) {
        const int index = action.substring(5).toInt();
        if (index >= 0 && index < (int)options.size() && options[index].enabled) {
            forceMenuOption = index;
            AnyKeyPress = true;
            SerialCmdPress = true;
            sendLine("OK:menu:" + String(index));
        } else {
            sendLine("ERR:menu index");
        }
        return;
    }
    if (action == "BACK") {
        EscPress = true;
        AnyKeyPress = true;
        SerialCmdPress = true;
        sendLine("OK:menu:back");
        return;
    }
    if (action == "HOME") {
        returnToMenu = true;
        EscPress = true;
        AnyKeyPress = true;
        SerialCmdPress = true;
        sendLine("OK:menu:home");
        return;
    }
    sendLine("ERR:menu action");
}

// ---------------------------------------------------------------- phone screen

void drawPhoneScreen(const String &text) {
    const int width = tft.width();
    const int height = tft.height();
    tft.fillScreen(bruceConfig.bgColor);
    tft.setTextColor(bruceConfig.priColor, bruceConfig.bgColor);
    tft.setTextSize(2);
    tft.drawCentreString("StickCommander", width / 2, 4, 2);
    tft.drawFastHLine(4, 26, width - 8, bruceConfig.priColor);
    tft.setTextColor(TFT_WHITE, bruceConfig.bgColor);
    tft.setTextSize(1);

    int y = 34;
    String remaining = text;
    while (remaining.length() > 0 && y < height - 18) {
        const int newline = remaining.indexOf('\n');
        String line = newline >= 0 ? remaining.substring(0, newline) : remaining;
        remaining = newline >= 0 ? remaining.substring(newline + 1) : "";
        while (line.length() > 36 && y < height - 18) {
            tft.drawString(line.substring(0, 36), 4, y, 1);
            line = line.substring(36);
            y += 10;
        }
        if (line.length() > 0 && y < height - 18) {
            tft.drawString(line, 4, y, 1);
            y += 10;
        }
    }
    tft.setTextColor(bruceConfig.priColor, bruceConfig.bgColor);
    tft.drawCentreString("from phone", width / 2, height - 12, 1);
}

void handleScreenCommand(const String &rest) {
    if (rest.startsWith("TEXT:")) {
        const String text = base64Decode(rest.substring(5));
        if (text.length() == 0) {
            sendLine("ERR:screen empty");
            return;
        }
        drawPhoneScreen(text);
        sendLine("OK:screen:text");
        return;
    }
    if (rest == "CLEAR") {
        tft.fillScreen(bruceConfig.bgColor);
        tft.setTextColor(bruceConfig.priColor, bruceConfig.bgColor);
        tft.setTextSize(2);
        tft.drawCentreString("StickCommander", tft.width() / 2, tft.height() / 2 - 8, 2);
        sendLine("OK:screen:clear");
        return;
    }
    sendLine("ERR:screen action");
}

// ---------------------------------------------------------------- IR blaster

void handleIrCommand(const String &rest) {
    const int separator = rest.indexOf(':');
    if (separator <= 0) {
        sendLine("ERR:ir payload");
        return;
    }
    String protocol = rest.substring(0, separator);
    protocol.toUpperCase();
    const String args = rest.substring(separator + 1);
    const int second = args.indexOf(':');
    const String address = second >= 0 ? args.substring(0, second) : args;
    String command = second >= 0 ? args.substring(second + 1) : "";
    if (address.length() == 0 || command.length() == 0) {
        sendLine("ERR:ir arguments");
        return;
    }

    if (protocol == "NEC") {
        sendNECCommand(address, command, true);
    } else if (protocol == "NECEXT") {
        sendNECextCommand(address, command, true);
    } else if (protocol == "SAMSUNG") {
        sendSamsungCommand(address, command, true);
    } else if (protocol == "SONY") {
        int bits = 12;
        const int third = command.indexOf(':');
        if (third >= 0) {
            bits = command.substring(third + 1).toInt();
            command = command.substring(0, third);
        }
        sendSonyCommand(address, command, (uint8_t)bits, true);
    } else {
        sendLine("ERR:ir protocol");
        return;
    }

    sendLine("OK:ir:" + protocol);
    sendLog("ir", protocol + " " + address + ":" + command);
    sendDisplay("IR " + protocol);
}

// ---------------------------------------------------------------- filesystem

String normalizeFsPath(String path) {
    path.trim();
    if (path.length() == 0) return "/";
    if (!path.startsWith("/")) path = "/" + path;
    while (path.indexOf("//") >= 0) path.replace("//", "/");
    if (path.indexOf("..") >= 0) return "/";
    if (path.length() > 1 && path.endsWith("/")) path.remove(path.length() - 1);
    return path;
}

bool ensureFsParent(const String &path) {
    const int slash = path.lastIndexOf('/');
    if (slash <= 0) return true;
    const String parent = path.substring(0, slash);
    if (LittleFS.exists(parent)) return true;
    return LittleFS.mkdir(parent);
}

void sendFsError(const String &message) { sendLine("FS:ERR:" + sanitizeLog(message)); }

void sendFsOk(const String &action, const String &path, size_t size) {
    sendLine(
        "FS:OK:" + action + ":" + base64Encode(normalizeFsPath(path)) + ":" +
        String((unsigned long)size)
    );
}

void sendFsList(const String &rawPath) {
    if (!ensureLittleFs()) {
        sendFsError("filesystem unavailable");
        return;
    }
    const String path = normalizeFsPath(rawPath);
    File directory = LittleFS.open(path);
    if (!directory || !directory.isDirectory()) {
        sendFsError("not a directory");
        return;
    }
    sendLine("FS:LIST:" + base64Encode(path));
    while (true) {
        bool isDirectory = false;
        const String fullPath = directory.getNextFileName(&isDirectory);
        if (fullPath.length() == 0) break;
        const String name = fullPath.substring(fullPath.lastIndexOf('/') + 1);
        size_t size = 0;
        if (!isDirectory) {
            File entry = LittleFS.open(fullPath, FILE_READ);
            if (entry) {
                size = entry.size();
                entry.close();
            }
        }
        sendLine(
            "FS:ENTRY:" + String(isDirectory ? "D" : "F") + ":" + String((unsigned long)size) +
            ":" + base64Encode(name)
        );
    }
    directory.close();
    sendLine("FS:LISTEND");
}

void sendFsRead(const String &rawPath) {
    if (!ensureLittleFs()) {
        sendFsError("filesystem unavailable");
        return;
    }
    const String path = normalizeFsPath(rawPath);
    File file = LittleFS.open(path, FILE_READ);
    if (!file || file.isDirectory()) {
        sendFsError("not a file");
        return;
    }
    sendLine("FS:FILE:" + String((unsigned long)file.size()) + ":" + base64Encode(path));
    uint8_t buffer[384];
    while (file.available()) {
        const size_t count = file.read(buffer, sizeof(buffer));
        if (count == 0) break;
        sendLine("FS:DATA:" + base64Encode(buffer, count));
    }
    file.close();
    sendLine("FS:FILEEND");
}

void handleFsCommand(const String &rest) {
    const int separator = rest.indexOf(':');
    String action = separator >= 0 ? rest.substring(0, separator) : rest;
    const String payload = separator >= 0 ? rest.substring(separator + 1) : "";
    action.toUpperCase();

    if (action == "LIST") {
        sendFsList(base64Decode(payload));
        return;
    }
    if (action == "READ") {
        sendFsRead(base64Decode(payload));
        return;
    }
    if (action == "DELETE") {
        if (!ensureLittleFs()) { sendFsError("filesystem unavailable"); return; }
        const String path = normalizeFsPath(base64Decode(payload));
        if (path == "/") { sendFsError("cannot remove root"); return; }
        if (LittleFS.remove(path)) sendFsOk("delete", path, 0);
        else sendFsError("delete failed");
        return;
    }
    if (action == "MKDIR") {
        if (!ensureLittleFs()) { sendFsError("filesystem unavailable"); return; }
        const String path = normalizeFsPath(base64Decode(payload));
        if (path == "/" || LittleFS.exists(path) || LittleFS.mkdir(path)) sendFsOk("mkdir", path, 0);
        else sendFsError("mkdir failed");
        return;
    }
    if (action == "RENAME") {
        if (!ensureLittleFs()) { sendFsError("filesystem unavailable"); return; }
        const int split = payload.indexOf(':');
        if (split <= 0) { sendFsError("bad rename"); return; }
        const String from = normalizeFsPath(base64Decode(payload.substring(0, split)));
        const String to = normalizeFsPath(base64Decode(payload.substring(split + 1)));
        if (LittleFS.rename(from, to)) sendFsOk("rename", to, 0);
        else sendFsError("rename failed");
        return;
    }
    if (action == "BEGIN") {
        if (!ensureLittleFs()) { sendFsError("filesystem unavailable"); return; }
        const int split = payload.indexOf(':');
        if (split <= 0) { sendFsError("bad upload"); return; }
        const String path = normalizeFsPath(base64Decode(payload.substring(0, split)));
        const size_t expected = (size_t)payload.substring(split + 1).toInt();
        if (fsUploading) {
            fsUploadFile.close();
            fsUploading = false;
        }
        if (!ensureFsParent(path)) { sendFsError("parent directory missing"); return; }
        if (LittleFS.exists(path)) LittleFS.remove(path);
        fsUploadFile = LittleFS.open(path, FILE_WRITE);
        if (!fsUploadFile) { sendFsError("cannot open file"); return; }
        fsUploadPath = path;
        fsUploadWritten = 0;
        fsUploading = true;
        sendLine(
            "FS:OK:begin:" + base64Encode(path) + ":" + String((unsigned long)expected)
        );
        return;
    }
    if (action == "CHUNK") {
        if (!fsUploading) { sendFsError("no upload in progress"); return; }
        const String decoded = base64Decode(payload);
        const size_t written = fsUploadFile.write(
            reinterpret_cast<const uint8_t *>(decoded.c_str()), decoded.length()
        );
        fsUploadWritten += written;
        if (written != decoded.length()) {
            fsUploadFile.close();
            fsUploading = false;
            sendFsError("write failed");
        }
        return;
    }
    if (action == "END") {
        if (!fsUploading) { sendFsError("no upload in progress"); return; }
        fsUploadFile.close();
        fsUploading = false;
        sendFsOk("upload", fsUploadPath, fsUploadWritten);
        sendLog(
            "fs", "saved " + fsUploadPath + " (" + String((unsigned long)fsUploadWritten) + " bytes)"
        );
        return;
    }
    if (action == "WRITE") {
        if (!ensureLittleFs()) { sendFsError("filesystem unavailable"); return; }
        const int split = payload.indexOf(':');
        if (split <= 0) { sendFsError("bad write"); return; }
        const String path = normalizeFsPath(base64Decode(payload.substring(0, split)));
        const String data = base64Decode(payload.substring(split + 1));
        if (!ensureFsParent(path)) { sendFsError("parent directory missing"); return; }
        if (LittleFS.exists(path)) LittleFS.remove(path);
        File file = LittleFS.open(path, FILE_WRITE);
        if (!file) { sendFsError("cannot open file"); return; }
        const size_t written = file.write(
            reinterpret_cast<const uint8_t *>(data.c_str()), data.length()
        );
        file.close();
        if (written == data.length()) sendFsOk("write", path, written);
        else sendFsError("write failed");
        return;
    }
    sendFsError("unknown action");
}

}  // namespace

void stickcommanderHandleLine(const String &rawLine) {
    String line = rawLine;
    line.trim();
    if (line.length() == 0 || !line.startsWith("CMD:")) return;
    if (line.length() > MAX_INPUT_LINE) {
        sendLine("ERR:line too long");
        return;
    }

    touchClient();

    if (line == "CMD:PING") {
        sendLine("PONG");
        return;
    }
    if (line == "CMD:SYS:STATUS") {
        sendStatus();
        lastStatusMs = millis();
        return;
    }
    if (line == "CMD:SCRIPT:LIST") {
        sendScriptList();
        return;
    }
    if (line == "CMD:SCRIPT:STOP") {
        interpreter_state = -1;
        sendLine("OK:script stopped");
        sendLog("script", "stopped");
        sendDisplay("Script stopped");
        return;
    }
    if (line.startsWith("CMD:BTN:")) {
        handleButton(line.substring(8));
        return;
    }
    if (line.startsWith("CMD:NAV:")) {
        handleNav(line.substring(8));
        return;
    }
    if (line.startsWith("CMD:TEXT:")) {
        const String text = unescapeField(line.substring(9));
        sendLog("text", text);
        sendDisplay(text);
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
    if (line.startsWith("CMD:MENU:")) {
        handleMenuCommand(line.substring(9));
        return;
    }
    if (line.startsWith("CMD:SCREEN:")) {
        handleScreenCommand(line.substring(11));
        return;
    }
    if (line.startsWith("CMD:IR:")) {
        handleIrCommand(line.substring(7));
        return;
    }
    if (line.startsWith("CMD:FS:")) {
        handleFsCommand(line.substring(7));
        return;
    }

    sendError("sc", "unknown command");
    sendLine("ERR:unknown command");
}

void stickcommanderLoop() {
    if (lastClientActivityMs == 0) return;
    const uint32_t now = millis();
    if (now - lastClientActivityMs > CLIENT_TIMEOUT_MS) {
        lastClientActivityMs = 0;
        return;
    }
    if (now - lastStatusMs >= STATUS_INTERVAL_MS) {
        lastStatusMs = now;
        sendStatus();
    }
}
