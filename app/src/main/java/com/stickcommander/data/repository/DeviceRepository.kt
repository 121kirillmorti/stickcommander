package com.stickcommander.data.repository

import android.content.Context
import com.stickcommander.data.model.DeviceStats
import com.stickcommander.data.model.DeviceStatus
import com.stickcommander.data.model.LocalScript
import com.stickcommander.data.model.LogEntry
import com.stickcommander.data.model.LogLevel
import com.stickcommander.data.protocol.ButtonAction
import com.stickcommander.data.protocol.CommandProtocol
import com.stickcommander.data.protocol.DeviceCommand
import com.stickcommander.data.protocol.NavDirection
import com.stickcommander.data.protocol.ProtocolEvent
import com.stickcommander.data.protocol.ProtocolLineDecoder
import com.stickcommander.data.protocol.StickButton
import com.stickcommander.data.transport.ConnectionState
import com.stickcommander.data.transport.DeviceTransport
import com.stickcommander.data.transport.TransportType
import com.stickcommander.data.transport.ble.BleConnectionManager
import com.stickcommander.data.transport.isConnected
import com.stickcommander.data.transport.usb.UsbConnectionManager
import com.stickcommander.service.ConnectionKeepAliveService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single source of truth for the active device link, telemetry, logs and display.
 *
 * The repository owns an app-scoped coroutine scope. UI ViewModels only observe
 * StateFlows and enqueue commands, which keeps configuration changes cheap.
 */
class DeviceRepository(
    private val context: Context,
    private val usb: UsbConnectionManager,
    private val ble: BleConnectionManager,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectionMutex = Mutex()
    private val decoder = ProtocolLineDecoder()
    private val outbound = Channel<DeviceCommand>(capacity = Channel.BUFFERED)

    private var activeTransport: DeviceTransport? = null
    private var stateJob: Job? = null
    private var ingestJob: Job? = null
    private var pollJob: Job? = null
    private var keepAliveStarted = false

    private var nextLogId = 1L
    private val displayBuffer = ArrayDeque<String>()

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _status = MutableStateFlow(DeviceStatus())
    val status: StateFlow<DeviceStatus> = _status.asStateFlow()

    private val _stats = MutableStateFlow(DeviceStats())
    val stats: StateFlow<DeviceStats> = _stats.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _display = MutableStateFlow<List<String>>(emptyList())
    val display: StateFlow<List<String>> = _display.asStateFlow()

    private val _deviceScripts = MutableStateFlow<List<String>>(emptyList())
    val deviceScripts: StateFlow<List<String>> = _deviceScripts.asStateFlow()

    private val _messages = MutableSharedFlow<String>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _events = MutableSharedFlow<ProtocolEvent>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<ProtocolEvent> = _events.asSharedFlow()

    val bleScanResults: StateFlow<List<com.stickcommander.data.transport.BleDevice>> =
        ble.scanResults
    val bleScanning: StateFlow<Boolean> = ble.scanning

    init {
        // A single writer preserves command ordering (important for PRESS/RELEASE pairs)
        // and keeps serialization off the UI thread.
        scope.launch {
            for (command in outbound) {
                val transport = activeTransport ?: continue
                runCatching {
                    val payload = CommandProtocol.serialize(command).toByteArray(Charsets.UTF_8)
                    transport.write(payload)
                    payload.size
                }
                    .onSuccess { byteCount ->
                        _stats.update {
                            it.copy(
                                txBytes = it.txBytes + byteCount,
                                txPackets = it.txPackets + 1,
                            )
                        }
                    }
                    .onFailure { notify("Send failed: ${it.message ?: "unknown error"}") }
            }
        }
    }

    // ------------------------------------------------------------- connection

    fun connect(type: TransportType, deviceId: String? = null) {
        scope.launch {
            connectionMutex.withLock {
                teardownConnection()
                startConnection(type, deviceId)
            }
        }
    }

    fun disconnect() {
        scope.launch {
            connectionMutex.withLock {
                teardownConnection()
                _connection.value = ConnectionState.Disconnected
            }
        }
    }

    private suspend fun startConnection(type: TransportType, deviceId: String?) {
        val transport: DeviceTransport = when (type) {
            TransportType.USB -> usb
            TransportType.BLE -> ble
        }

        activeTransport = transport
        decoder.reset()
        _status.value = DeviceStatus()
        _stats.value = DeviceStats()
        _connection.value = ConnectionState.Connecting

        stateJob = scope.launch {
            transport.state.collect { handleTransportState(it) }
        }
        ingestJob = scope.launch {
            transport.incoming.collect { ingest(it) }
        }

        runCatching { transport.connect(deviceId) }
            .onFailure { error ->
                _connection.value = ConnectionState.Error(
                    error.message ?: "Unable to open the transport.",
                )
                stopPolling()
            }
    }

    private suspend fun teardownConnection() {
        stopPolling()
        stateJob?.cancel()
        stateJob = null
        ingestJob?.cancel()
        ingestJob = null
        val previous = activeTransport
        activeTransport = null
        if (previous != null) runCatching { previous.disconnect() }
        decoder.reset()
        stopKeepAlive()
    }

    private fun handleTransportState(state: ConnectionState) {
        _connection.value = state
        when (state) {
            is ConnectionState.Connected -> {
                if (_stats.value.connectedAt == null) {
                    _stats.update { it.copy(connectedAt = System.currentTimeMillis()) }
                }
                startKeepAlive()
                startPolling()
                scope.launch {
                    delay(500)
                    if (_connection.value.isConnected) send(DeviceCommand.RequestStatus)
                }
            }

            is ConnectionState.Disconnected, is ConnectionState.Error -> {
                stopPolling()
                stopKeepAlive()
            }

            ConnectionState.Connecting -> Unit
        }
    }

    private fun startKeepAlive() {
        if (keepAliveStarted) return
        keepAliveStarted = true
        runCatching { ConnectionKeepAliveService.start(context) }
    }

    private fun stopKeepAlive() {
        if (!keepAliveStarted) return
        keepAliveStarted = false
        runCatching { ConnectionKeepAliveService.stop(context) }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                delay(STATUS_POLL_INTERVAL_MS)
                if (_connection.value.isConnected) send(DeviceCommand.RequestStatus)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    // ---------------------------------------------------------------- commands

    fun send(command: DeviceCommand) {
        if (!_connection.value.isConnected) {
            notify("Connect to your Stick first.")
            return
        }
        if (!outbound.trySend(command).isSuccess) {
            notify("Command queue is full.")
        }
    }

    fun sendButton(button: StickButton, pressed: Boolean) {
        send(
            DeviceCommand.Button(
                button,
                if (pressed) ButtonAction.PRESS else ButtonAction.RELEASE,
            ),
        )
    }

    fun sendNav(direction: NavDirection, pressed: Boolean) {
        send(
            DeviceCommand.Nav(
                direction,
                if (pressed) ButtonAction.PRESS else ButtonAction.RELEASE,
            ),
        )
    }

    fun sendRaw(line: String) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return
        send(DeviceCommand.Raw(trimmed))
    }

    fun ping() = send(DeviceCommand.Ping)

    fun refreshStatus() = send(DeviceCommand.RequestStatus)

    fun stopScript() = send(DeviceCommand.StopScript)

    fun requestDeviceScripts() {
        _deviceScripts.value = emptyList()
        send(DeviceCommand.RequestScripts)
    }

    fun runDeviceScript(name: String) {
        addLog(LogLevel.INFO, "app", "Requested device script: $name")
        send(DeviceCommand.RunScript(name))
    }

    fun runLocalScript(script: LocalScript) {
        addLog(LogLevel.INFO, "app", "Uploading script: ${script.name}")
        send(DeviceCommand.LoadScript(script.name, script.content))
    }

    fun startBleScan() = ble.startScan()

    fun stopBleScan() = ble.stopScan()

    // ---------------------------------------------------------- live menu / tools

    fun requestMenus() = send(DeviceCommand.RequestMenuList)

    fun openMenu(index: Int) = send(DeviceCommand.OpenMenu(index))

    fun menuBack() = send(DeviceCommand.MenuBack)

    fun menuHome() = send(DeviceCommand.MenuHome)

    fun sendScreenText(text: String) = send(DeviceCommand.ScreenText(text.trim()))

    fun clearScreen() = send(DeviceCommand.ScreenClear)

    fun sendIr(
        protocol: String,
        address: String,
        command: String,
        bits: Int = 12,
    ) = send(DeviceCommand.IrSend(protocol, address, command, bits))

    fun fsList(path: String) = send(DeviceCommand.FsList(path))

    fun fsRead(path: String) = send(DeviceCommand.FsRead(path))

    fun fsDelete(path: String) = send(DeviceCommand.FsDelete(path))

    fun fsMkdir(path: String) = send(DeviceCommand.FsMkdir(path))

    fun fsRename(from: String, to: String) = send(DeviceCommand.FsRename(from, to))

    suspend fun fsUploadBegin(path: String, size: Int) =
        sendAwait(DeviceCommand.FsUploadBegin(path, size))

    suspend fun fsUploadChunk(data: ByteArray) =
        sendAwait(DeviceCommand.FsUploadChunk(data))

    suspend fun fsUploadEnd() = sendAwait(DeviceCommand.FsUploadEnd)

    suspend fun sendAwait(command: DeviceCommand) {
        if (!_connection.value.isConnected) {
            notify("Connect to your Stick first.")
            return
        }
        outbound.send(command)
    }

    // ---------------------------------------------------------------- decoding

    private fun ingest(bytes: ByteArray) {
        _stats.update {
            it.copy(
                rxBytes = it.rxBytes + bytes.size,
                rxPackets = it.rxPackets + 1,
            )
        }
        decoder.feed(bytes).forEach(::handleLine)
    }

    private fun handleLine(line: String) {
        val event = CommandProtocol.parse(line)
        when (event) {
            is ProtocolEvent.Status -> _status.update { current ->
                current.copy(
                    batteryMillivolts = event.millivolts ?: current.batteryMillivolts,
                    batteryPercent = event.batteryPercent ?: current.batteryPercent,
                    uptimeSeconds = event.uptimeSeconds ?: current.uptimeSeconds,
                    rssi = event.rssi ?: current.rssi,
                    firmware = event.firmware ?: current.firmware,
                    lastUpdated = System.currentTimeMillis(),
                )
            }

            is ProtocolEvent.LogLine -> addLog(event.level, event.tag, event.message)

            is ProtocolEvent.Display -> appendDisplay(event.text)

            is ProtocolEvent.Beacon -> {
                _stats.update { it.copy(beacons = it.beacons + 1) }
                addLog(LogLevel.BEACON, "beacon", event.summary)
            }

            is ProtocolEvent.ScriptEntry -> _deviceScripts.update { current ->
                (current + event.name).distinct().sorted()
            }

            is ProtocolEvent.ScriptList -> _deviceScripts.value = event.scripts.distinct().sorted()

            ProtocolEvent.ScriptListEnd -> Unit

            ProtocolEvent.Pong -> addLog(LogLevel.INFO, "system", "PONG received")

            is ProtocolEvent.Unknown -> addLog(LogLevel.RAW, "raw", event.raw)

            else -> Unit
        }

        when (event) {
            is ProtocolEvent.MenuTitle,
            is ProtocolEvent.MenuSelected,
            is ProtocolEvent.MenuItem,
            ProtocolEvent.MenuNone,
            ProtocolEvent.MenuEnd,
            is ProtocolEvent.FsListing,
            is ProtocolEvent.FsEntry,
            ProtocolEvent.FsListEnd,
            is ProtocolEvent.FsFileStart,
            is ProtocolEvent.FsData,
            ProtocolEvent.FsFileEnd,
            is ProtocolEvent.FsError,
            is ProtocolEvent.FsOk -> _events.tryEmit(event)

            else -> Unit
        }
    }

    private fun appendDisplay(text: String) {
        text.split('\n').forEach { line ->
            if (displayBuffer.size >= DISPLAY_LINES) displayBuffer.removeFirst()
            displayBuffer.addLast(line)
        }
        _display.value = displayBuffer.toList()
    }

    fun clearDisplay() {
        displayBuffer.clear()
        _display.value = emptyList()
    }

    fun addLog(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(
            id = nextLogId++,
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
        )
        _logs.update { current ->
            if (current.size >= LOG_LIMIT) current.drop(current.size - LOG_LIMIT + 1) + entry
            else current + entry
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
        nextLogId = 1L
    }

    /** Surfaces a user-facing message in the root snackbar. */
    fun postMessage(message: String) {
        notify(message)
    }

    private fun notify(message: String) {
        _messages.tryEmit(message)
    }

    private companion object {
        const val LOG_LIMIT = 1_000
        const val DISPLAY_LINES = 6
        const val STATUS_POLL_INTERVAL_MS = 10_000L
    }
}
