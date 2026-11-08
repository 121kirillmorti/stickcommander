package com.stickcommander.data.protocol

import com.stickcommander.data.model.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandProtocolTest {

    @Test
    fun `button command serializes with newline`() {
        val payload = CommandProtocol.serialize(
            DeviceCommand.Button(StickButton.M5, ButtonAction.PRESS),
        )
        assertEquals("CMD:BTN:M5:PRESS\n", payload)
    }

    @Test
    fun `nav command serializes`() {
        val payload = CommandProtocol.serialize(
            DeviceCommand.Nav(NavDirection.LEFT, ButtonAction.RELEASE),
        )
        assertEquals("CMD:NAV:LEFT:RELEASE\n", payload)
    }

    @Test
    fun `battery status is parsed`() {
        val event = CommandProtocol.parse("STATUS:BAT:4021:87")
        assertTrue(event is ProtocolEvent.Status)
        event as ProtocolEvent.Status
        assertEquals(4021, event.millivolts)
        assertEquals(87, event.batteryPercent)
    }

    @Test
    fun `log levels are mapped`() {
        val warning = CommandProtocol.parse("LOG:W:ble:slow write") as ProtocolEvent.LogLine
        assertEquals(LogLevel.WARNING, warning.level)
        assertEquals("ble", warning.tag)
        assertEquals("slow write", warning.message)

        val beacon = CommandProtocol.parse("LOG:B:scan:aa:bb") as ProtocolEvent.LogLine
        assertEquals(LogLevel.BEACON, beacon.level)
        assertEquals("scan", beacon.tag)
        assertEquals("aa:bb", beacon.message)
    }

    @Test
    fun `control characters survive escape round trip`() {
        val text = "line1\nline2: with colon\\tail"
        val encoded = CommandProtocol.escape(text)
        assertEquals(text, CommandProtocol.unescape(encoded))

        val payload = CommandProtocol.serialize(DeviceCommand.Text(text))
        assertEquals("CMD:TEXT:$encoded\n", payload)
    }

    @Test
    fun `script list is parsed`() {
        val event = CommandProtocol.parse("SCRIPTS:beacon.txt|ir.txt") as ProtocolEvent.ScriptList
        assertEquals(listOf("beacon.txt", "ir.txt"), event.scripts)

        assertTrue(CommandProtocol.parse("SCRIPTS:END") is ProtocolEvent.ScriptListEnd)
        assertTrue(
            CommandProtocol.parse("SCRIPT:beacon.txt") is ProtocolEvent.ScriptEntry,
        )
    }

    @Test
    fun `display frames are unescaped`() {
        val event = CommandProtocol.parse("DISPLAY:hello\\nworld") as ProtocolEvent.Display
        assertEquals("hello\nworld", event.text)
    }

    @Test
    fun `decoder reassembles fragmented frames`() {
        val decoder = ProtocolLineDecoder()

        assertTrue(decoder.feed("PON".toByteArray()).isEmpty())

        val lines = decoder.feed("G\r\nLOG:I:app:ok\n".toByteArray())
        assertEquals(listOf("PONG", "LOG:I:app:ok"), lines)
    }

    @Test
    fun `unknown frames are preserved for the raw console`() {
        val event = CommandProtocol.parse("WEIRD:payload") as ProtocolEvent.Unknown
        assertEquals("WEIRD:payload", event.raw)
    }
}
