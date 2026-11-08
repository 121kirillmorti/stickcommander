package com.stickcommander.ui.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatUptime(totalSeconds: Long): String {
    if (totalSeconds <= 0) return "—"
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "%dh %02dm".format(Locale.US, hours, minutes)
        minutes > 0 -> "%dm %02ds".format(Locale.US, minutes, seconds)
        else -> "${seconds}s"
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_048_576 -> "%.1f KB".format(Locale.US, bytes / 1_024.0)
    else -> "%.1f MB".format(Locale.US, bytes / 1_048_576.0)
}

fun formatRelativeTime(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    if (timestamp <= 0) return "never"
    val diff = (now - timestamp).coerceAtLeast(0)
    return when {
        diff < 60_000 -> "just now"
        diff < 3_600_000 -> "${diff / 60_000} min ago"
        diff < 86_400_000 -> "${diff / 3_600_000} h ago"
        diff < 7 * 86_400_000L -> "${diff / 86_400_000} d ago"
        else -> SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(timestamp))
    }
}

fun estimateBatteryPercent(millivolts: Int?): Int? {
    if (millivolts == null) return null
    return when {
        millivolts >= 4_200 -> 100
        millivolts <= 3_300 -> 0
        else -> (((millivolts - 3_300) / 900f) * 100f).toInt().coerceIn(0, 100)
    }
}
