@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.stickcommander.ui.dashboard

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stickcommander.data.model.DeviceStatus
import com.stickcommander.data.transport.BleDevice
import com.stickcommander.data.transport.ConnectionState
import com.stickcommander.data.transport.TransportType
import com.stickcommander.data.transport.ble.BleConnectionManager
import com.stickcommander.data.transport.isConnected
import com.stickcommander.ui.components.ConnectionStatusChip
import com.stickcommander.ui.components.SectionTitle
import com.stickcommander.ui.components.StatCard
import com.stickcommander.ui.util.estimateBatteryPercent
import com.stickcommander.ui.util.formatBytes
import com.stickcommander.ui.util.formatRelativeTime
import com.stickcommander.ui.util.formatUptime
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val connected = state.connection.isConnected

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.all { it }) {
            viewModel.startScan()
        } else {
            Toast.makeText(
                context,
                "Bluetooth permission is required to scan for BLE devices.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        // The link works without the notification; the foreground service is still alive.
    }

    val ensureNotificationPermission: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val requestScan: () -> Unit = {
        if (BleConnectionManager.hasPermissions(context)) {
            viewModel.startScan()
        } else {
            permissionLauncher.launch(BleConnectionManager.permissions())
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ConnectionCard(
                state = state,
                isConnected = connected,
                onSelectTransport = viewModel::selectTransport,
                onConnect = {
                    ensureNotificationPermission()
                    viewModel.connect()
                },
                onConnectDevice = { device ->
                    ensureNotificationPermission()
                    viewModel.connectTo(device)
                },
                onDisconnect = viewModel::disconnect,
                onScan = requestScan,
                onStopScan = viewModel::stopScan,
            )
        }
        item { BatteryCard(status = state.status) }
        item { StatsGrid(state = state) }
        item {
            QuickActionsCard(
                enabled = connected,
                onRefresh = viewModel::refreshStatus,
                onPing = viewModel::ping,
                onRequestScripts = viewModel::requestScripts,
            )
        }
    }
}

@Composable
private fun ConnectionCard(
    state: DashboardUiState,
    isConnected: Boolean,
    onSelectTransport: (TransportType) -> Unit,
    onConnect: () -> Unit,
    onConnectDevice: (BleDevice) -> Unit,
    onDisconnect: () -> Unit,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
) {
    val connecting = state.connection == ConnectionState.Connecting
    val connectedState = state.connection as? ConnectionState.Connected

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(
                    title = "Connection",
                    subtitle = "USB-OTG serial or Bruce/NUS BLE",
                    modifier = Modifier.weight(1f),
                )
                ConnectionStatusChip(state = state.connection)
            }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                TransportType.entries.forEachIndexed { index, type ->
                    SegmentedButton(
                        selected = state.transport == type,
                        onClick = { onSelectTransport(type) },
                        enabled = !isConnected && !connecting,
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = TransportType.entries.size,
                        ),
                        icon = {
                            Icon(
                                imageVector = if (type == TransportType.USB) {
                                    Icons.Rounded.Usb
                                } else {
                                    Icons.Rounded.Bluetooth
                                },
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        label = { Text(text = type.label) },
                    )
                }
            }

            when {
                isConnected -> ConnectedPanel(
                    deviceName = connectedState?.deviceName ?: "Stick connected",
                    transportLabel = connectedState?.transport?.label.orEmpty(),
                    onDisconnect = onDisconnect,
                )

                connecting -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = if (state.transport == TransportType.USB) {
                            "Opening serial link at 115200 baud…"
                        } else {
                            "Connecting to BLE device…"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                state.transport == TransportType.USB -> {
                    Button(
                        onClick = onConnect,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                    ) {

                        Icon(Icons.Rounded.Usb, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Connect via USB-OTG")
                    }
                    Text(
                        text = "8N1 · 115200 baud · DTR/RTS asserted. " +
                            "Android asks for USB access on the first connection.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    Button(
                        onClick = if (state.isScanning) onStopScan else onScan,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                    ) {
                        Icon(Icons.Rounded.Bluetooth, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.isScanning) "Stop scanning" else "Scan for BLE devices")
                    }

                    if (state.isScanning) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    if (state.bleDevices.isEmpty()) {
                        Text(
                            text = if (state.isScanning) {
                                "Looking for Bruce/NUS BLE serial advertisements…"
                            } else {
                                "No devices yet. Put the Stick in BLE serial mode and scan again."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            state.bleDevices.take(8).forEach { device ->
                                BleDeviceRow(device = device, onClick = { onConnectDevice(device) })
                            }
                        }
                    }
                }
            }

            val errorMessage = (state.connection as? ConnectionState.Error)?.message
            if (errorMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectedPanel(
    deviceName: String,
    transportLabel: String,
    onDisconnect: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = deviceName, style = MaterialTheme.typography.titleMedium)
            Text(
                text = transportLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onDisconnect) {
            Text("Disconnect")
        }
    }
}

@Composable
private fun BleDeviceRow(device: BleDevice, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Bluetooth,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = device.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "${device.rssi} dBm",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BatteryCard(status: DeviceStatus) {
    val percent = status.batteryPercent ?: estimateBatteryPercent(status.batteryMillivolts)
    val scheme = MaterialTheme.colorScheme
    val color = when {
        percent == null -> scheme.outline
        percent <= 15 -> scheme.error
        percent <= 35 -> scheme.tertiary
        else -> scheme.primary
    }
    val voltage = status.batteryMillivolts
        ?.let { String.format(Locale.US, "%.2f V", it / 1000f) }
        ?: "—"

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(modifier = Modifier.size(100.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { (percent ?: 0) / 100f },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 10.dp,
                    color = color,
                    trackColor = scheme.surfaceVariant,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = percent?.toString() ?: "—",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "percent",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("AXP192 battery", style = MaterialTheme.typography.titleMedium)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Bolt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = color,
                    )
                    Text(
                        text = voltage,
                        style = MaterialTheme.typography.titleLarge,
                        color = scheme.onSurface,
                    )
                }
                Text(
                    text = if (status.lastUpdated > 0) {
                        "Telemetry ${formatRelativeTime(status.lastUpdated)}"
                    } else {
                        "Waiting for telemetry…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                if (status.firmware != null) {
                    Text(
                        text = status.firmware,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsGrid(state: DashboardUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(title = "System", subtitle = "Live link counters")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(
                label = "Uptime",
                value = formatUptime(state.uptimeSeconds),
                icon = Icons.Rounded.Timer,
                modifier = Modifier.weight(1f),
            )
            StatCard(
                label = "Beacons / BLE",
                value = state.stats.beacons.toString(),
                icon = Icons.Rounded.BluetoothSearching,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(
                label = "Received",
                value = formatBytes(state.stats.rxBytes),
                icon = Icons.Rounded.ArrowDownward,
                modifier = Modifier.weight(1f),
            )
            StatCard(
                label = "Sent",
                value = formatBytes(state.stats.txBytes),
                icon = Icons.Rounded.ArrowUpward,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun QuickActionsCard(
    enabled: Boolean,
    onRefresh: () -> Unit,
    onPing: () -> Unit,
    onRequestScripts: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionTitle(
                title = "Quick actions",
                subtitle = "One-tap protocol helpers",
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(
                    onClick = onRefresh,
                    enabled = enabled,
                    label = { Text("Refresh status") },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
                AssistChip(
                    onClick = onPing,
                    enabled = enabled,
                    label = { Text("Ping") },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.WifiTethering,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
                AssistChip(
                    onClick = onRequestScripts,
                    enabled = enabled,
                    label = { Text("List scripts") },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.Description,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }
        }
    }
}
