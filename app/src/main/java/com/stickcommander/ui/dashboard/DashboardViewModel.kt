package com.stickcommander.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.stickcommander.data.model.DeviceStats
import com.stickcommander.data.model.DeviceStatus
import com.stickcommander.data.repository.DeviceRepository
import com.stickcommander.data.transport.BleDevice
import com.stickcommander.data.transport.ConnectionState
import com.stickcommander.data.transport.TransportType
import com.stickcommander.data.transport.isConnected
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

data class DashboardUiState(
    val transport: TransportType = TransportType.USB,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val status: DeviceStatus = DeviceStatus(),
    val stats: DeviceStats = DeviceStats(),
    val bleDevices: List<BleDevice> = emptyList(),
    val isScanning: Boolean = false,
    val uptimeSeconds: Long = 0,
)

class DashboardViewModel(
    private val repository: DeviceRepository,
) : ViewModel() {

    private val selectedTransport = MutableStateFlow(TransportType.USB)

    /** Re-emits once a second so the uptime label stays live without polling the device. */
    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1_000)
        }
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        combine(
            repository.connection,
            repository.status,
            repository.stats,
        ) { connection, status, stats -> Triple(connection, status, stats) },
        repository.bleScanResults,
        repository.bleScanning,
        selectedTransport,
        clock,
    ) { snapshot, devices, scanning, transport, now ->
        val (connection, status, stats) = snapshot
        DashboardUiState(
            transport = transport,
            connection = connection,
            status = status,
            stats = stats,
            bleDevices = devices,
            isScanning = scanning,
            uptimeSeconds = stats.connectedAt?.let { ((now - it) / 1_000).coerceAtLeast(0) } ?: 0,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardUiState(),
    )

    fun selectTransport(type: TransportType) {
        if (!repository.connection.value.isConnected) {
            selectedTransport.value = type
            if (type != TransportType.BLE) repository.stopBleScan()
        }
    }

    fun connect() {
        repository.connect(selectedTransport.value)
    }

    fun connectTo(device: BleDevice) {
        selectedTransport.value = TransportType.BLE
        repository.connect(TransportType.BLE, device.address)
    }

    fun disconnect() = repository.disconnect()

    fun startScan() = repository.startBleScan()

    fun stopScan() = repository.stopBleScan()

    fun refreshStatus() = repository.refreshStatus()

    fun ping() = repository.ping()

    fun requestScripts() = repository.requestDeviceScripts()

    override fun onCleared() {
        repository.stopBleScan()
        super.onCleared()
    }

    companion object {
        fun factory(repository: DeviceRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { DashboardViewModel(repository) }
        }
    }
}
