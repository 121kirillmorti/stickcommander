package com.stickcommander.data.model

/** Severity buckets used by the terminal filters. */
enum class LogLevel(val label: String) {
    INFO("INFO"),
    WARNING("WARN"),
    ERROR("ERROR"),
    BEACON("BEACON"),
    RAW("RAW"),
}

data class LogEntry(
    val id: Long,
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
)

/**
 * Snapshot of the telemetry the firmware reports. Every field is nullable because
 * firmware revisions expose different subsets (AXP192 battery data is not present
 * on every board revision).
 */
data class DeviceStatus(
    val batteryMillivolts: Int? = null,
    val batteryPercent: Int? = null,
    val uptimeSeconds: Long? = null,
    val rssi: Int? = null,
    val firmware: String? = null,
    val lastUpdated: Long = 0L,
)

data class DeviceStats(
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val rxPackets: Long = 0L,
    val txPackets: Long = 0L,
    val beacons: Long = 0L,
    val connectedAt: Long? = null,
)

/** A script stored in the app's private files directory. */
data class LocalScript(
    val id: String,
    val name: String,
    val content: String,
    val size: Long,
    val updatedAt: Long,
)
