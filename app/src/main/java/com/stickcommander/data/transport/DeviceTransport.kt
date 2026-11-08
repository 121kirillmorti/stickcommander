package com.stickcommander.data.transport

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class TransportType(val label: String, val shortLabel: String) {
    USB("USB-OTG", "USB"),
    BLE("Bluetooth LE", "BLE"),
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(
        val transport: TransportType,
        val deviceName: String?,
    ) : ConnectionState

    data class Error(val message: String) : ConnectionState
}

val ConnectionState.isConnected: Boolean
    get() = this is ConnectionState.Connected

data class BleDevice(
    val name: String,
    val address: String,
    val rssi: Int,
)

/**
 * Common contract for both physical transports. Implementations are app-scoped
 * singletons; UI code only observes [state] and [incoming].
 */
interface DeviceTransport {
    val type: TransportType
    val state: StateFlow<ConnectionState>
    val incoming: SharedFlow<ByteArray>

    suspend fun connect(deviceId: String? = null)
    suspend fun disconnect()
    suspend fun write(data: ByteArray)
}
