package com.stickcommander.data.transport.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import com.stickcommander.data.transport.ConnectionState
import com.stickcommander.data.transport.DeviceTransport
import com.stickcommander.data.transport.TransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.coroutines.resume

/**
 * USB-OTG transport for the M5StickC Plus 2's serial bridge (CH9102/CH340/CP210x/CDC).
 *
 * Responsibilities:
 *  - discover a supported usb-serial driver
 *  - request the Android USB permission with a suspending broadcast round-trip
 *  - open the port at 115200 8N1 and stream bytes through a [SerialInputOutputManager]
 *  - serialize writes with a mutex
 */
class UsbConnectionManager(private val context: Context) : DeviceTransport {

    private val usbManager: UsbManager? =
        context.getSystemService(Context.USB_SERVICE) as? UsbManager

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val incoming: SharedFlow<ByteArray> = _incoming.asSharedFlow()

    override val type: TransportType = TransportType.USB

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()

    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var permissionReceiver: BroadcastReceiver? = null

    @Volatile
    private var closing = false

    val hasAttachedDevice: Boolean
        get() = usbManager?.deviceList?.isNotEmpty() == true

    val attachedDeviceNames: List<String>
        get() = usbManager?.deviceList?.values
            ?.mapNotNull { it.productName ?: it.deviceName }
            .orEmpty()

    override suspend fun connect(deviceId: String?) {
        _state.value = ConnectionState.Connecting
        closing = false
        try {
            val manager = usbManager ?: throw IOException("USB host mode is not available on this device.")
            val driver = findDriver(manager, deviceId)
                ?: throw IOException(
                    "No supported USB serial device found. Connect the M5StickC Plus 2 " +
                        "with an OTG cable and make sure USB serial is enabled in firmware.",
                )

            val usbDevice = driver.device
            if (!manager.hasPermission(usbDevice)) {
                val granted = requestPermission(manager, usbDevice)
                if (!granted) {
                    _state.value = ConnectionState.Error("USB permission was denied. Reconnect the stick and allow access.")
                    return
                }
            }

            val connection = manager.openDevice(usbDevice)
                ?: throw IOException("The USB device could not be opened. Unplug and reconnect it.")

            val serialPort = driver.ports.firstOrNull()
                ?: throw IOException("The USB device does not expose a serial port.")

            serialPort.open(connection)
            serialPort.setParameters(
                BAUD_RATE,
                DATA_BITS,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE,
            )
            // DTR/RTS wake up CDC bridges (CH9102, CP210x) that gate their UART on modem lines.
            runCatching { serialPort.dtr = true }
            runCatching { serialPort.rts = true }

            port = serialPort

            val managerListener = object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) {
                    _incoming.tryEmit(data)
                }

                override fun onRunError(e: Exception) {
                    if (closing) return
                    ioScope.launch {
                        closeInternal()
                        _state.value = ConnectionState.Error(
                            "USB connection lost: ${e.message ?: e.javaClass.simpleName}",
                        )
                    }
                }
            }

            ioManager = SerialInputOutputManager(serialPort, managerListener).also { it.start() }
            _state.value = ConnectionState.Connected(
                TransportType.USB,
                usbDevice.productName ?: usbDevice.deviceName ?: "M5StickC Plus 2",
            )
        } catch (t: Throwable) {
            closeInternal()
            _state.value = ConnectionState.Error(t.message ?: "USB connection failed.")
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) { closeInternal() }
        _state.value = ConnectionState.Disconnected
    }

    override suspend fun write(data: ByteArray) {
        val serialPort = port ?: throw IOException("USB port is not open.")
        writeMutex.withLock {
            // Large frames (script uploads) are split so the Stick's UART RX
            // buffer is drained before the next chunk arrives.
            var offset = 0
            while (offset < data.size) {
                val end = minOf(offset + USB_CHUNK_SIZE, data.size)
                serialPort.write(data.copyOfRange(offset, end), WRITE_TIMEOUT_MS)
                offset = end
                if (offset < data.size) delay(USB_CHUNK_DELAY_MS)
            }
        }
    }

    private fun findDriver(manager: UsbManager, deviceId: String?): UsbSerialDriver? {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        if (drivers.isEmpty()) return null
        if (deviceId == null) return drivers.first()
        return drivers.firstOrNull { driver ->
            driver.device.deviceName == deviceId ||
                driver.device.productName == deviceId
        } ?: drivers.first()
    }

    private suspend fun requestPermission(manager: UsbManager, device: UsbDevice): Boolean =
        withTimeoutOrNull(PERMISSION_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(receiverContext: Context?, intent: Intent?) {
                        if (intent?.action != ACTION_USB_PERMISSION) return
                        val granted = intent.getBooleanExtra(
                            UsbManager.EXTRA_PERMISSION_GRANTED,
                            false,
                        )
                        unregisterPermissionReceiver()
                        if (continuation.isActive) continuation.resume(granted)
                    }
                }

                permissionReceiver = receiver
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    IntentFilter(ACTION_USB_PERMISSION),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )

                val flags = PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                    flags,
                )
                manager.requestPermission(device, pendingIntent)

                continuation.invokeOnCancellation { unregisterPermissionReceiver() }
            }
        } ?: false

    private fun unregisterPermissionReceiver() {
        val receiver = permissionReceiver ?: return
        permissionReceiver = null
        runCatching { context.unregisterReceiver(receiver) }
    }

    private fun closeInternal() {
        closing = true
        runCatching { ioManager?.stop() }
        ioManager = null
        runCatching { port?.close() }
        port = null
        unregisterPermissionReceiver()
    }

    private companion object {
        const val ACTION_USB_PERMISSION = "com.stickcommander.USB_PERMISSION"
        const val BAUD_RATE = 115_200
        const val DATA_BITS = 8
        const val WRITE_TIMEOUT_MS = 2_000
        const val PERMISSION_TIMEOUT_MS = 15_000L
        const val USB_CHUNK_SIZE = 256
        const val USB_CHUNK_DELAY_MS = 5L
    }
}
