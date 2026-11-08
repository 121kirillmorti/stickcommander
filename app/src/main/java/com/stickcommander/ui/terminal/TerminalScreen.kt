package com.stickcommander.ui.terminal

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stickcommander.data.model.LogEntry
import com.stickcommander.data.model.LogLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    var rawCommand by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state.logs.lastOrNull()?.id, state.autoScroll, state.filters) {
        if (state.autoScroll && state.logs.isNotEmpty()) {
            listState.scrollToItem(state.logs.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LogLevel.entries.forEach { level ->
                FilterChip(
                    selected = level in state.filters,
                    onClick = { viewModel.toggleFilter(level) },
                    label = { Text(filterLabel(level)) },
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(levelColor(level), CircleShape),
                        )
                    },
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${state.logs.size} / ${state.totalCount} entries",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "Auto-scroll",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Switch(
                checked = state.autoScroll,
                onCheckedChange = viewModel::setAutoScroll,
            )
            IconButton(
                onClick = {
                    val transcript = state.logs.joinToString("\n") { entry ->
                        "${timeFormat.format(Date(entry.timestamp))} " +
                            "${entry.level.label} [${entry.tag}] ${entry.message}"
                    }
                    clipboard.setText(AnnotatedString(transcript))
                    Toast.makeText(
                        context,
                        "Copied ${state.logs.size} log entries.",
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                enabled = state.logs.isNotEmpty(),
            ) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy filtered logs")
            }
            IconButton(onClick = viewModel::clear) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Clear logs")
            }
        }

        HorizontalDivider()

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
        ) {
            if (state.logs.isEmpty()) {
                EmptyTerminal()
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(items = state.logs, key = { it.id }) { entry ->
                        LogRow(entry = entry, timeFormat = timeFormat)
                    }
                }
            }
        }

        HorizontalDivider()

        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = rawCommand,
                    onValueChange = { rawCommand = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("cmd> raw protocol line") },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            viewModel.sendRaw(rawCommand)
                            rawCommand = ""
                        },
                    ),
                )
                FilledIconButton(
                    onClick = {
                        viewModel.sendRaw(rawCommand)
                        rawCommand = ""
                    },
                    enabled = rawCommand.isNotBlank(),
                ) {
                    Icon(Icons.Rounded.Send, contentDescription = "Send raw command")
                }
            }
        }
    }
}

@Composable
private fun LogRow(
    entry: LogEntry,
    timeFormat: SimpleDateFormat,
) {
    val color = levelColor(entry.level)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = timeFormat.format(Date(entry.timestamp)),
            modifier = Modifier.width(62.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            modifier = Modifier.width(54.dp),
            shape = RoundedCornerShape(4.dp),
            color = color.copy(alpha = 0.16f),
        ) {
            Box(modifier = Modifier.padding(vertical = 2.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = entry.level.label,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = color,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = entry.tag,
            modifier = Modifier.widthIn(max = 76.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.tertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = entry.message,
            modifier = Modifier.weight(1f),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun EmptyTerminal() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Terminal,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "No log frames yet",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Connect a transport and enable logging on the Stick.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun levelColor(level: LogLevel): Color = when (level) {
    LogLevel.INFO -> MaterialTheme.colorScheme.primary
    LogLevel.WARNING -> MaterialTheme.colorScheme.tertiary
    LogLevel.ERROR -> MaterialTheme.colorScheme.error
    LogLevel.BEACON -> MaterialTheme.colorScheme.secondary
    LogLevel.RAW -> MaterialTheme.colorScheme.outline
}

private fun filterLabel(level: LogLevel): String = when (level) {
    LogLevel.INFO -> "INFO"
    LogLevel.WARNING -> "WARN"
    LogLevel.ERROR -> "ERROR"
    LogLevel.BEACON -> "Beacon / BLE"
    LogLevel.RAW -> "RAW"
}
