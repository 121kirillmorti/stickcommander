package com.stickcommander.data.transport.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.stickcommander.data.transport.BleDevice
import com.stickcommander.data.transport.ConnectionState
import com.stickcommander.data.transport.DeviceTransport
import com.stickcommander.data.transport.TransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import kotlin.math.min

/**
 * BLE transport implementing the Nordic UART Service (NUS) profile used by most
 * ESP32/M5Stack bridges:
 *
 *   Service 6E400001-B5A3-F393-E0A9-E50E24DCCA9E
 *   RX      6E400002-...  phone -> device (write / write-no-response)
 *   TX      6E400003-...  device -> phone (notify)
 */
@SuppressLint("MissingPermission")
class BleConnectionManager(private val context: Context) : DeviceTransport {

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val adapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val incoming: SharedFlow<ByteArray> = _incoming.asSharedFlow()

    override val type: TransportType = TransportType.BLE

    private val _scanResults = MutableStateFlow<List<BleDevice>>(emptyList())
    val scanResults: StateFlow<List<BleDevice>> = _scanResults.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()

    private var scanTimeoutJob: Job? = null
    private var connectTimeoutJob: Job? = null

    @Volatile
    private var activeGatt: BluetoothGatt? = null

    @Volatile
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    @Volatile
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null

    @Volatile
    private var notifyCharacteristicUuid: UUID? = null

    @Volatile
    private var writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

    @Volatile
    private var mtu = DEFAULT_MTU

    @Volatile
    private var intentionalDisconnect = false

    // ---------------------------------------------------------------- scanning

    fun startScan() {
        val bluetoothAdapter = adapter
        if (bluetoothAdapter == null) {
            _state.value = ConnectionState.Error("Bluetooth is not available on this device.")
            return
        }
        // BLUETOOTH_CONNECT is required even to query isEnabled on Android 12+.
        if (!hasPermissions(context)) {
            _state.value = ConnectionState.Error("Bluetooth scan permission is required.")
            return
        }
        if (!runCatching { bluetoothAdapter.isEnabled }.getOrDefault(false)) {
            _state.value = ConnectionState.Error("Turn Bluetooth on to scan for your Stick.")
            return
        }
        if (_scanning.value) return

        _scanResults.value = emptyList()
        _scanning.value = true
        val scanner = runCatching { bluetoothAdapter.bluetoothLeScanner }.getOrNull()
        if (scanner == null) {
            _scanning.value = false
            _state.value = ConnectionState.Error("BLE scanner is unavailable.")
            return
        }

        // Clear a stale transport error before starting a fresh scan.
        if (_state.value is ConnectionState.Error) {
            _state.value = ConnectionState.Disconnected
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        runCatching { scanner.startScan(null, settings, scanCallback) }
            .onFailure {
                _scanning.value = false
                _state.value = ConnectionState.Error("Could not start BLE scan: ${it.message}")
            }

        scanTimeoutJob?.cancel()
        scanTimeoutJob = scope.launch {
            delay(SCAN_DURATION_MS)
            stopScanInternal()
        }
    }

    fun stopScan() = stopScanInternal()

    private fun stopScanInternal() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        if (!_scanning.value) return
        _scanning.value = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val record = result.scanRecord
            val advertisedNus = record?.serviceUuids?.any { it.uuid == NUS_SERVICE } == true
            val advertisedBruce = record?.serviceUuids?.any { it.uuid == BRUCE_SERVICE } == true
            val name = runCatching { device.name }.getOrNull()
                ?: record?.deviceName
                ?: ""
            val looksLikeStick = advertisedNus ||
                advertisedBruce ||
                name.contains("bruc", ignoreCase = true) ||
                name.contains("m5", ignoreCase = true) ||
                name.contains("stick", ignoreCase = true)
            if (!looksLikeStick) return

            val displayName = name.ifBlank { "Unnamed device" }
            val entry = BleDevice(displayName, device.address, result.rssi)
            _scanResults.update { current ->
                if (current.any { it.address == entry.address }) {
                    current.map { if (it.address == entry.address) entry else it }
                        .sortedByDescending { it.rssi }
                } else {
                    (current + entry).sortedByDescending { it.rssi }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _scanning.value = false
            _state.value = ConnectionState.Error("BLE scan failed (code $errorCode).")
        }
    }

    // -------------------------------------------------------------- connecting

    override suspend fun connect(deviceId: String?) {
        val address = deviceId ?: run {
            _state.value = ConnectionState.Error("Select a BLE device first.")
            return
        }
        val bluetoothAdapter = adapter
        if (bluetoothAdapter == null) {
            _state.value = ConnectionState.Error("Bluetooth is not available on this device.")
            return
        }
        if (!hasPermissions(context)) {
            _state.value = ConnectionState.Error("Bluetooth connect permission is required.")
            return
        }
        if (!runCatching { bluetoothAdapter.isEnabled }.getOrDefault(false)) {
            _state.value = ConnectionState.Error("Turn Bluetooth on and try again.")
            return
        }

        stopScanInternal()
        intentionalDisconnect = false
        closeGatt(activeGatt)
        _state.value = ConnectionState.Connecting

        val device = runCatching { bluetoothAdapter.getRemoteDevice(address) }.getOrElse {
            _state.value = ConnectionState.Error("Invalid BLE device address.")
            return
        }

        withContext(Dispatchers.Main) {
            activeGatt = device.connectGatt(
                context,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE,
            )
        }

        connectTimeoutJob?.cancel()
        connectTimeoutJob = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (_state.value == ConnectionState.Connecting) {
                intentionalDisconnect = true
                closeGatt(activeGatt)
                _state.value = ConnectionState.Error("BLE connection timed out.")
            }
        }
    }

    override suspend fun disconnect() {
        stopScanInternal()
        intentionalDisconnect = true
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        val gatt = activeGatt
        withContext(Dispatchers.Main) {
            runCatching { gatt?.disconnect() }
            runCatching { gatt?.close() }
        }
        activeGatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
        notifyCharacteristicUuid = null
        _state.value = ConnectionState.Disconnected
    }

    override suspend fun write(data: ByteArray) {
        val gatt = activeGatt ?: throw IOException("BLE link is not connected.")
        val characteristic = writeCharacteristic
            ?: throw IOException("BLE serial characteristic is unavailable.")
        if (data.isEmpty()) return

        val chunkSize = (mtu - 3).coerceAtLeast(MIN_CHUNK_BYTES)
        writeMutex.withLock {
            var offset = 0
            while (offset < data.size) {
                val end = min(offset + chunkSize, data.size)
                val chunk = data.copyOfRange(offset, end)
                val started = writeChunk(gatt, characteristic, chunk, writeType)
                if (!started) throw IOException("BLE write was rejected by the stack.")
                offset = end
                if (offset < data.size) delay(WRITE_CHUNK_DELAY_MS)
            }
        }
    }

    private fun writeChunk(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        chunk: ByteArray,
        writeType: Int,
    ): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        gatt.writeCharacteristic(characteristic, chunk, writeType) == BluetoothGatt.GATT_SUCCESS
    } else {
        @Suppress("DEPRECATION")
        run {
            characteristic.writeType = writeType
            characteristic.value = chunk
            gatt.writeCharacteristic(characteristic)
        }
    }

    // ----------------------------------------------------------- GATT callback

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when {
                status == BluetoothGatt.GATT_SUCCESS &&
                    newState == BluetoothProfile.STATE_CONNECTED -> {
                    activeGatt = gatt
                    runCatching { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                    if (!gatt.discoverServices()) {
                        fail("Service discovery could not be started.")
                    }
                }

                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    val wasIntentional = intentionalDisconnect
                    closeGatt(gatt)
                    connectTimeoutJob?.cancel()
                    connectTimeoutJob = null
                    _state.value = if (wasIntentional) {
                        ConnectionState.Disconnected
                    } else {
                        ConnectionState.Error(
                            "BLE link lost" + if (status != BluetoothGatt.GATT_SUCCESS) " (status $status)." else ".",
                        )
                    }
                    intentionalDisconnect = false
                }

                status != BluetoothGatt.GATT_SUCCESS -> {
                    closeGatt(gatt)
                    _state.value = ConnectionState.Error("BLE connection failed (status $status).")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("BLE service discovery failed (status $status).")
                return
            }
            val nusService = gatt.getService(NUS_SERVICE)
            val nusRx = nusService?.getCharacteristic(NUS_RX)
            val nusTx = nusService?.getCharacteristic(NUS_TX)

            val writeChar: BluetoothGattCharacteristic
            val notifyChar: BluetoothGattCharacteristic
            if (nusRx != null && nusTx != null) {
                writeChar = nusRx
                notifyChar = nusTx
            } else {
                // Bruce 1.16.x exposes its own serial service instead of NUS.
                val bruceService = gatt.getService(BRUCE_SERVICE)
                val bruceChar = bruceService?.getCharacteristic(BRUCE_SERIAL)
                if (bruceChar == null) {
                    fail("The device exposes neither Nordic UART nor Bruce BLE serial.")
                    return
                }
                writeChar = bruceChar
                notifyChar = bruceChar
            }

            writeCharacteristic = writeChar
            notifyCharacteristic = notifyChar
            notifyCharacteristicUuid = notifyChar.uuid
            writeType = if (
                writeChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
            ) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }

            if (!gatt.setCharacteristicNotification(notifyChar, true)) {
                fail("Could not enable BLE serial notifications.")
                return
            }

            val descriptor = notifyChar.getDescriptor(CCCD_UUID)
            if (descriptor == null) {
                finishHandshake(gatt)
                return
            }

            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(
                    descriptor,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                ) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                }
            }
            // Some firmware omits the CCCD write acknowledgement; do not stall the link.
            if (!started) finishHandshake(gatt)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (descriptor.uuid != CCCD_UUID) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                finishHandshake(gatt)
            } else {
                fail("Could not subscribe to NUS notifications (status $status).")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                this@BleConnectionManager.mtu = mtu.coerceAtLeast(DEFAULT_MTU)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == notifyCharacteristicUuid) _incoming.tryEmit(value)
        }

        @Deprecated("Used below API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
                characteristic.uuid == notifyCharacteristicUuid
            ) {
                characteristic.value?.let { _incoming.tryEmit(it) }
            }
        }
    }

    /**
     * Notifications are live at this point. MTU negotiation is requested only after
     * the CCCD write so the GATT operations do not race on strict stacks.
     */
    private fun finishHandshake(gatt: BluetoothGatt) {
        runCatching { gatt.requestMtu(REQUESTED_MTU) }
        markConnected(gatt)
    }

    private fun markConnected(gatt: BluetoothGatt) {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        val name = runCatching { gatt.device.name }.getOrNull() ?: gatt.device.address
        _state.value = ConnectionState.Connected(TransportType.BLE, name)
    }

    private fun fail(message: String) {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        closeGatt(activeGatt)
        _state.value = ConnectionState.Error(message)
    }

    private fun closeGatt(gatt: BluetoothGatt?) {
        if (gatt == null) return
        if (activeGatt === gatt) {
            activeGatt = null
            writeCharacteristic = null
            notifyCharacteristic = null
            notifyCharacteristicUuid = null
        }
        runCatching { gatt.close() }
    }

    companion object {
        val NUS_SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

        /** Bruce's BLE API serial service (NimBLE). */
        val BRUCE_SERVICE: UUID = UUID.fromString("4371EC0B-3D43-49F9-B731-7C72A4A7BB91")
        val BRUCE_SERIAL: UUID = UUID.fromString("D555ED97-BF2A-4F46-B3EB-D1FCDD7325E9")

        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val DEFAULT_MTU = 23
        private const val REQUESTED_MTU = 247
        private const val MIN_CHUNK_BYTES = 20
        private const val WRITE_CHUNK_DELAY_MS = 12L
        private const val SCAN_DURATION_MS = 20_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L

        /** Runtime permissions needed for scanning and connecting. */
        fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        fun hasPermissions(context: Context): Boolean = permissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
        }
    }
}
