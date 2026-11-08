package com.stickcommander.data.protocol

import com.stickcommander.data.model.LogLevel

/**
 * Commands understood by the StickCommander firmware adapter.
 *
 * The wire protocol is deliberately line-oriented ASCII so it is easy to implement
 * on top of an existing serial CLI (Bruce, M5Stack examples, custom Arduino sketch).
 * Every frame ends with a single '\n'.
 */
sealed interface DeviceCommand {
    data class Button(val button: StickButton, val action: ButtonAction) : DeviceCommand
    data class Nav(val direction: NavDirection, val action: ButtonAction) : DeviceCommand
    data class RunScript(val name: String) : DeviceCommand
    data class LoadScript(val name: String, val source: String) : DeviceCommand
    data class Text(val text: String) : DeviceCommand
    data object StopScript : DeviceCommand
    data object RequestStatus : DeviceCommand
    data object RequestScripts : DeviceCommand
    data object Ping : DeviceCommand

    data object RequestMenuList : DeviceCommand
    data class OpenMenu(val index: Int) : DeviceCommand
    data object MenuBack : DeviceCommand
    data object MenuHome : DeviceCommand

    data class ScreenText(val text: String) : DeviceCommand
    data object ScreenClear : DeviceCommand

    data class IrSend(
        val protocol: String,
        val address: String,
        val command: String,
        val bits: Int = 12,
    ) : DeviceCommand

    data class FsList(val path: String) : DeviceCommand
    data class FsRead(val path: String) : DeviceCommand
    data class FsWrite(val path: String, val data: ByteArray) : DeviceCommand
    data class FsUploadBegin(val path: String, val size: Int) : DeviceCommand
    data class FsUploadChunk(val data: ByteArray) : DeviceCommand
    data object FsUploadEnd : DeviceCommand
    data class FsDelete(val path: String) : DeviceCommand
    data class FsMkdir(val path: String) : DeviceCommand
    data class FsRename(val from: String, val to: String) : DeviceCommand

    /** Escape hatch for the terminal's raw input. */
    data class Raw(val line: String) : DeviceCommand
}

enum class StickButton(val wire: String, val label: String) {
    M5("M5", "M5"),
    ACTION("A", "A"),
    BUTTON_B("B", "B"),
    POWER("PWR", "PWR"),
}

enum class ButtonAction(val wire: String) {
    PRESS("PRESS"),
    RELEASE("RELEASE"),
    CLICK("CLICK"),
}

/** Directions for the virtual D-Pad; the firmware maps these to its menu/UI. */
enum class NavDirection(val wire: String) {
    UP("UP"),
    DOWN("DOWN"),
    LEFT("LEFT"),
    RIGHT("RIGHT"),
    OK("OK"),
}

/** Events decoded from the device-to-phone log stream. */
sealed interface ProtocolEvent {
    data class Status(
        val millivolts: Int? = null,
        val batteryPercent: Int? = null,
        val uptimeSeconds: Long? = null,
        val rssi: Int? = null,
        val firmware: String? = null,
    ) : ProtocolEvent

    data class LogLine(val level: LogLevel, val tag: String, val message: String) : ProtocolEvent
    data class Display(val text: String) : ProtocolEvent
    data class Beacon(val summary: String) : ProtocolEvent
    data class ScriptEntry(val name: String) : ProtocolEvent
    data class ScriptList(val scripts: List<String>) : ProtocolEvent
    data object ScriptListEnd : ProtocolEvent
    data object Pong : ProtocolEvent
    data class Unknown(val raw: String) : ProtocolEvent

    data class MenuTitle(val title: String) : ProtocolEvent
    data class MenuSelected(val index: Int) : ProtocolEvent
    data class MenuItem(val index: Int, val label: String, val enabled: Boolean) : ProtocolEvent
    data object MenuNone : ProtocolEvent
    data object MenuEnd : ProtocolEvent

    data class FsListing(val path: String) : ProtocolEvent
    data class FsEntry(val name: String, val isDirectory: Boolean, val size: Long) : ProtocolEvent
    data object FsListEnd : ProtocolEvent
    data class FsFileStart(val path: String, val size: Long) : ProtocolEvent
    data class FsData(val base64: String) : ProtocolEvent
    data object FsFileEnd : ProtocolEvent
    data class FsError(val message: String) : ProtocolEvent
    data class FsOk(val action: String, val path: String, val size: Long) : ProtocolEvent
}

/**
 * Stateless serializer/parser. Keeping this pure makes it unit-testable on the JVM.
 *
 * Device -> phone frames
 *   STATUS:BAT:4021:87          battery millivolts + percentage
 *   STATUS:UPTIME:1234          seconds since boot
 *   STATUS:RSSI:-61             BLE RSSI (optional)
 *   STATUS:FW:Bruce 1.9.0       firmware string
 *   LOG:I:net:connected         level I/W/E/B, tag, message
 *   DISPLAY:Scanning...         mirrored TFT text (use \n for a new line)
 *   BEACON:aa:bb:cc:dd          beacon / BLE packet summary
 *   SCRIPT:beacon.txt           one device-side script
 *   SCRIPTS:a.txt|b.txt         full script list
 *   SCRIPTS:END                 end of script list
 *   PONG                        reply to CMD:PING
 *   OK:... / ERR:...            acknowledgement / failure
 */
object CommandProtocol {
    const val NEWLINE = "\n"

    fun serialize(command: DeviceCommand): String {
        val body = when (command) {
            is DeviceCommand.Button -> "CMD:BTN:${command.button.wire}:${command.action.wire}"
            is DeviceCommand.Nav -> "CMD:NAV:${command.direction.wire}:${command.action.wire}"
            is DeviceCommand.RunScript -> "CMD:SCRIPT:RUN:${escape(command.name)}"
            is DeviceCommand.LoadScript ->
                "CMD:SCRIPT:LOAD:${encode(command.name)}:${encode(command.source)}"
            is DeviceCommand.Text -> "CMD:TEXT:${escape(command.text)}"
            DeviceCommand.StopScript -> "CMD:SCRIPT:STOP"
            DeviceCommand.RequestStatus -> "CMD:SYS:STATUS"
            DeviceCommand.RequestScripts -> "CMD:SCRIPT:LIST"
            DeviceCommand.Ping -> "CMD:PING"
            DeviceCommand.RequestMenuList -> "CMD:MENU:LIST"
            is DeviceCommand.OpenMenu -> "CMD:MENU:OPEN:${command.index}"
            DeviceCommand.MenuBack -> "CMD:MENU:BACK"
            DeviceCommand.MenuHome -> "CMD:MENU:HOME"
            is DeviceCommand.ScreenText -> "CMD:SCREEN:TEXT:${encode(command.text)}"
            DeviceCommand.ScreenClear -> "CMD:SCREEN:CLEAR"
            is DeviceCommand.IrSend -> {
                val base = "CMD:IR:${command.protocol.uppercase()}:${command.address}:${command.command}"
                if (command.protocol.equals("SONY", ignoreCase = true)) {
                    "$base:${command.bits}"
                } else {
                    base
                }
            }
            is DeviceCommand.FsList -> "CMD:FS:LIST:${encode(command.path)}"
            is DeviceCommand.FsRead -> "CMD:FS:READ:${encode(command.path)}"
            is DeviceCommand.FsWrite ->
                "CMD:FS:WRITE:${encode(command.path)}:${encodeBytes(command.data)}"
            is DeviceCommand.FsUploadBegin ->
                "CMD:FS:BEGIN:${encode(command.path)}:${command.size}"
            is DeviceCommand.FsUploadChunk -> "CMD:FS:CHUNK:${encodeBytes(command.data)}"
            DeviceCommand.FsUploadEnd -> "CMD:FS:END"
            is DeviceCommand.FsDelete -> "CMD:FS:DELETE:${encode(command.path)}"
            is DeviceCommand.FsMkdir -> "CMD:FS:MKDIR:${encode(command.path)}"
            is DeviceCommand.FsRename ->
                "CMD:FS:RENAME:${encode(command.from)}:${encode(command.to)}"
            is DeviceCommand.Raw -> command.line.trimEnd('\r', '\n')
        }
        return body + NEWLINE
    }

    fun parse(rawLine: String): ProtocolEvent {
        val line = rawLine.trim()
        if (line.isEmpty()) return ProtocolEvent.Unknown("")
        return when {
            line.startsWith("STATUS:BAT:") -> parseBattery(line)
            line.startsWith("STATUS:UPTIME:") -> ProtocolEvent.Status(
                uptimeSeconds = line.removePrefix("STATUS:UPTIME:").toLongOrNull(),
            )
            line.startsWith("STATUS:RSSI:") -> ProtocolEvent.Status(
                rssi = line.removePrefix("STATUS:RSSI:").toIntOrNull(),
            )
            line.startsWith("STATUS:FW:") -> ProtocolEvent.Status(
                firmware = line.removePrefix("STATUS:FW:").ifBlank { null },
            )
            line.startsWith("LOG:") -> parseLog(line)
            line.startsWith("BEACON:") || line.startsWith("BLE:") ->
                ProtocolEvent.Beacon(line.substringAfter(':').ifBlank { "BLE packet" })
            line.startsWith("DISPLAY:") -> ProtocolEvent.Display(unescape(line.removePrefix("DISPLAY:")))
            line.startsWith("DISP:") -> ProtocolEvent.Display(unescape(line.removePrefix("DISP:")))
            line == "SCRIPTS:END" -> ProtocolEvent.ScriptListEnd
            line.startsWith("SCRIPTS:") -> ProtocolEvent.ScriptList(
                line.removePrefix("SCRIPTS:")
                    .split('|')
                    .map { unescape(it.trim()) }
                    .filter { it.isNotEmpty() },
            )
            line.startsWith("SCRIPT:") -> ProtocolEvent.ScriptEntry(
                unescape(line.removePrefix("SCRIPT:")),
            )
            line == "PONG" || line.startsWith("PONG:") -> ProtocolEvent.Pong
            line == "MENU:NONE" -> ProtocolEvent.MenuNone
            line == "MENU:END" -> ProtocolEvent.MenuEnd
            line.startsWith("MENU:TITLE:") -> ProtocolEvent.MenuTitle(
                decodeString(line.removePrefix("MENU:TITLE:")),
            )
            line.startsWith("MENU:SEL:") -> ProtocolEvent.MenuSelected(
                line.removePrefix("MENU:SEL:").toIntOrNull() ?: -1,
            )
            line.startsWith("MENU:ITEM:") -> parseMenuItem(line.removePrefix("MENU:ITEM:"))
            line.startsWith("FS:LIST:") -> ProtocolEvent.FsListing(
                decodeString(line.removePrefix("FS:LIST:")),
            )
            line == "FS:LISTEND" -> ProtocolEvent.FsListEnd
            line.startsWith("FS:ENTRY:") -> parseFsEntry(line.removePrefix("FS:ENTRY:"))
            line.startsWith("FS:FILE:") -> parseFsFile(line.removePrefix("FS:FILE:"))
            line.startsWith("FS:DATA:") -> ProtocolEvent.FsData(line.removePrefix("FS:DATA:"))
            line == "FS:FILEEND" -> ProtocolEvent.FsFileEnd
            line.startsWith("FS:ERR:") -> ProtocolEvent.FsError(line.removePrefix("FS:ERR:"))
            line.startsWith("FS:OK:") -> parseFsOk(line.removePrefix("FS:OK:"))
            line.startsWith("ERR:") -> ProtocolEvent.LogLine(
                LogLevel.ERROR,
                "device",
                line.removePrefix("ERR:"),
            )
            line == "OK" -> ProtocolEvent.LogLine(LogLevel.INFO, "device", "Acknowledged")
            line.startsWith("OK:") -> ProtocolEvent.LogLine(
                LogLevel.INFO,
                "device",
                line.removePrefix("OK:"),
            )
            else -> ProtocolEvent.Unknown(line)
        }
    }

    private fun parseBattery(line: String): ProtocolEvent.Status {
        // STATUS:BAT:<millivolts>:<percent>
        val parts = line.split(':')
        return ProtocolEvent.Status(
            millivolts = parts.getOrNull(2)?.toIntOrNull(),
            batteryPercent = parts.getOrNull(3)?.toIntOrNull()?.coerceIn(0, 100),
        )
    }

    private fun parseLog(line: String): ProtocolEvent.LogLine {
        val segments = line.removePrefix("LOG:").split(':', limit = 3)
        val level = when (segments.getOrNull(0)?.trim()?.uppercase()) {
            "E", "ERROR" -> LogLevel.ERROR
            "W", "WARN", "WARNING" -> LogLevel.WARNING
            "B", "BEACON" -> LogLevel.BEACON
            else -> LogLevel.INFO
        }
        val tag = segments.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "device"
        return ProtocolEvent.LogLine(level, tag, segments.getOrNull(2).orEmpty())
    }

    /** Escapes control characters inside a single wire field. */
    fun escape(value: String): String = buildString {
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                ':' -> append("\\:")
                else -> append(ch)
            }
        }
    }

    fun unescape(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            if (ch == '\\' && index + 1 < value.length) {
                when (val next = value[index + 1]) {
                    'n' -> append('\n')
                    'r' -> append('\r')
                    '\\' -> append('\\')
                    ':' -> append(':')
                    else -> {
                        append(ch)
                        append(next)
                    }
                }
                index += 2
            } else {
                append(ch)
                index++
            }
        }
    }

    private fun encode(value: String): String =
        java.util.Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun encodeBytes(value: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(value)

    private fun decodeString(value: String): String =
        runCatching {
            java.util.Base64.getDecoder().decode(value).toString(Charsets.UTF_8)
        }.getOrElse { value }

    private fun parseMenuItem(payload: String): ProtocolEvent {
        val parts = payload.split(':', limit = 3)
        val index = parts.getOrNull(0)?.toIntOrNull() ?: return ProtocolEvent.Unknown(payload)
        val enabled = parts.getOrNull(1) == "1"
        val label = decodeString(parts.getOrNull(2).orEmpty())
        return ProtocolEvent.MenuItem(index, label, enabled)
    }

    private fun parseFsEntry(payload: String): ProtocolEvent {
        val parts = payload.split(':', limit = 3)
        val isDirectory = parts.getOrNull(0) == "D"
        val size = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        val name = decodeString(parts.getOrNull(2).orEmpty())
        return ProtocolEvent.FsEntry(name, isDirectory, size)
    }

    private fun parseFsFile(payload: String): ProtocolEvent {
        val parts = payload.split(':', limit = 2)
        val size = parts.getOrNull(0)?.toLongOrNull() ?: 0L
        val path = decodeString(parts.getOrNull(1).orEmpty())
        return ProtocolEvent.FsFileStart(path, size)
    }

    private fun parseFsOk(payload: String): ProtocolEvent {
        val parts = payload.split(':', limit = 3)
        val action = parts.getOrNull(0).orEmpty()
        val path = decodeString(parts.getOrNull(1).orEmpty())
        val size = parts.getOrNull(2)?.toLongOrNull() ?: 0L
        return ProtocolEvent.FsOk(action, path, size)
    }
}

/**
 * Reassembles arbitrary transport chunks into complete newline-delimited frames.
 * Multibyte UTF-8 payloads are safe because bytes are only decoded once a full
 * line has been accumulated.
 */
class ProtocolLineDecoder(private val maxLineBytes: Int = 16 * 1024) {
    private val buffer = java.io.ByteArrayOutputStream(256)

    fun feed(data: ByteArray): List<String> {
        val lines = ArrayList<String>()
        for (byte in data) {
            if (byte == '\n'.code.toByte()) {
                val line = buffer.toByteArray().toString(Charsets.UTF_8).trimEnd('\r')
                if (line.isNotBlank()) lines += line
                buffer.reset()
            } else if (buffer.size() >= maxLineBytes) {
                // Drop oversized garbage rather than growing without bound.
                buffer.reset()
            } else {
                buffer.write(byte.toInt())
            }
        }
        return lines
    }

    fun reset() = buffer.reset()
}
