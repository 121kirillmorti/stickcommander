package com.stickcommander.ui.remote

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stickcommander.data.protocol.NavDirection
import com.stickcommander.data.protocol.StickButton
import com.stickcommander.data.transport.isConnected
import com.stickcommander.ui.components.ConnectionStatusChip
import com.stickcommander.ui.components.SectionTitle
import com.stickcommander.ui.util.estimateBatteryPercent
import java.util.Locale

@Composable
fun RemoteControlScreen(
    viewModel: RemoteViewModel,
    modifier: Modifier = Modifier,
) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val display by viewModel.display.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val connected = connection.isConnected

    var text by rememberSaveable { mutableStateOf("") }
    val sendText: () -> Unit = {
        if (text.isNotBlank()) {
            viewModel.sendText(text)
            text = ""
        }
    }

    val percent = status.batteryPercent ?: estimateBatteryPercent(status.batteryMillivolts)
    val voltage = status.batteryMillivolts
        ?.let { String.format(Locale.US, "%.2f V", it / 1000f) }
    val batterySummary = listOfNotNull(
        percent?.let { "$it%" },
        voltage,
    ).joinToString(" · ").ifBlank { "No telemetry" }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ConnectionStatusChip(state = connection)
            Spacer(Modifier.weight(1f))
            Text(
                text = batterySummary,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        DisplayPanel(lines = display)

        SectionTitle(
            title = "Virtual controls",
            subtitle = "Hold to send PRESS, release to send RELEASE",
        )

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            DPad(
                enabled = connected,
                onPress = { viewModel.onNav(it, true) },
                onRelease = { viewModel.onNav(it, false) },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HoldButton(
                label = "M5",
                caption = "front",
                enabled = connected,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                onPress = { viewModel.onButton(StickButton.M5, true) },
                onRelease = { viewModel.onButton(StickButton.M5, false) },
            )
            HoldButton(
                label = "A",
                caption = "action",
                enabled = connected,
                onPress = { viewModel.onButton(StickButton.ACTION, true) },
                onRelease = { viewModel.onButton(StickButton.ACTION, false) },
            )
            HoldButton(
                label = "B",
                caption = "side",
                enabled = connected,
                onPress = { viewModel.onButton(StickButton.BUTTON_B, true) },
                onRelease = { viewModel.onButton(StickButton.BUTTON_B, false) },
            )
            HoldButton(
                label = "PWR",
                caption = "power",
                enabled = connected,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                onPress = { viewModel.onButton(StickButton.POWER, true) },
                onRelease = { viewModel.onButton(StickButton.POWER, false) },
            )
        }

        OutlinedButton(
            onClick = viewModel::stopScript,
            enabled = connected,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Stop running script")
        }

        TextInputCard(
            text = text,
            onTextChange = { text = it },
            enabled = connected,
            onSend = sendText,
        )
    }
}

@Composable
private fun DisplayPanel(lines: List<String>) {
    val transition = rememberInfiniteTransition(label = "cursor")
    val cursorAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "cursorAlpha",
    )

    val screenGreen = Color(0xFFB9F6CA)
    val screenDim = Color(0xFF8A9BA8)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 190.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF0B1015),
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "M5StickC Plus 2",
                    color = Color(0xFF7FB3FF),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "TTY · 115200",
                    color = screenDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 6.dp),
                color = Color(0xFF1E2A36),
            )

            if (lines.isEmpty()) {
                Text(
                    text = "> waiting for DISPLAY frames…",
                    color = screenDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            } else {
                lines.takeLast(5).forEach { line ->
                    Text(
                        text = "> $line",
                        color = screenGreen,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row {
                Text(
                    text = ">",
                    color = screenGreen,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
                Text(
                    text = "█",
                    color = screenGreen.copy(alpha = cursorAlpha),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun DPad(
    enabled: Boolean,
    onPress: (NavDirection) -> Unit,
    onRelease: (NavDirection) -> Unit,
) {
    Box(modifier = Modifier.size(236.dp), contentAlignment = Alignment.Center) {
        HoldButton(
            label = "▲",
            caption = null,
            enabled = enabled,
            size = 64.dp,
            modifier = Modifier.align(Alignment.TopCenter),
            onPress = { onPress(NavDirection.UP) },
            onRelease = { onRelease(NavDirection.UP) },
        )
        HoldButton(
            label = "▼",
            caption = null,
            enabled = enabled,
            size = 64.dp,
            modifier = Modifier.align(Alignment.BottomCenter),
            onPress = { onPress(NavDirection.DOWN) },
            onRelease = { onRelease(NavDirection.DOWN) },
        )
        HoldButton(
            label = "◀",
            caption = null,
            enabled = enabled,
            size = 64.dp,
            modifier = Modifier.align(Alignment.CenterStart),
            onPress = { onPress(NavDirection.LEFT) },
            onRelease = { onRelease(NavDirection.LEFT) },
        )
        HoldButton(
            label = "▶",
            caption = null,
            enabled = enabled,
            size = 64.dp,
            modifier = Modifier.align(Alignment.CenterEnd),
            onPress = { onPress(NavDirection.RIGHT) },
            onRelease = { onRelease(NavDirection.RIGHT) },
        )
        HoldButton(
            label = "OK",
            caption = null,
            enabled = enabled,
            size = 72.dp,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            onPress = { onPress(NavDirection.OK) },
            onRelease = { onRelease(NavDirection.OK) },
        )
    }
}

@Composable
private fun HoldButton(
    label: String,
    caption: String?,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 66.dp,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    shape: Shape = CircleShape,
) {
    val haptics = LocalHapticFeedback.current

    Surface(
        modifier = modifier
            .semantics {
                role = Role.Button
                contentDescription = label + (caption?.let { ", $it" } ?: "")
            }
            .size(size)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onPress()
                        tryAwaitRelease()
                        onRelease()
                    },
                )
            },
        shape = shape,
        color = if (enabled) containerColor else containerColor.copy(alpha = 0.38f),
        contentColor = if (enabled) contentColor else contentColor.copy(alpha = 0.5f),
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            if (caption != null) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun TextInputCard(
    text: String,
    onTextChange: (String) -> Unit,
    enabled: Boolean,
    onSend: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionTitle(
                title = "Text console",
                subtitle = "Sends CMD:TEXT:… to the firmware",
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    singleLine = true,
                    placeholder = { Text("Message or command…") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                )
                FilledIconButton(
                    onClick = onSend,
                    enabled = enabled && text.isNotBlank(),
                ) {
                    Icon(Icons.Rounded.Send, contentDescription = "Send text")
                }
            }
        }
    }
}
