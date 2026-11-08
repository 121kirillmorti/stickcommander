@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.stickcommander.ui.scripts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stickcommander.data.model.LocalScript
import com.stickcommander.ui.components.SectionTitle
import com.stickcommander.ui.util.formatBytes
import com.stickcommander.ui.util.formatRelativeTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptManagerScreen(
    viewModel: ScriptManagerViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<LocalScript?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(
                    title = "Script library",
                    subtitle = "Payloads stored on your phone or on the Stick",
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        if (state.storage == ScriptStorage.PHONE) {
                            viewModel.refreshLocalScripts()
                        } else {
                            viewModel.refreshDeviceScripts()
                        }
                    },
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh scripts")
                }
            }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ScriptStorage.entries.forEachIndexed { index, storage ->
                    SegmentedButton(
                        selected = state.storage == storage,
                        onClick = { viewModel.selectStorage(storage) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ScriptStorage.entries.size,
                        ),
                        icon = {
                            Icon(
                                imageVector = if (storage == ScriptStorage.PHONE) {
                                    Icons.Rounded.PhoneAndroid
                                } else {
                                    Icons.Rounded.Storage
                                },
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        label = { Text(storage.label) },
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when {
                state.busy &&
                    state.storage == ScriptStorage.PHONE &&
                    state.localScripts.isEmpty() -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(32.dp),
                    )
                }

                state.storage == ScriptStorage.PHONE && state.localScripts.isEmpty() -> {
                    EmptyLibrary(
                        icon = Icons.Rounded.Description,
                        title = "No phone scripts yet",
                        subtitle = "Create a payload here, then upload it to the Stick with one tap.",
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                state.storage == ScriptStorage.DEVICE && state.deviceScripts.isEmpty() -> {
                    EmptyLibrary(
                        icon = Icons.Rounded.Storage,
                        title = "No device scripts reported",
                        subtitle = "Ask the firmware for its script list. It should reply with SCRIPT:name frames.",
                        modifier = Modifier.align(Alignment.Center),
                        action = {
                            FilledTonalButton(onClick = viewModel::refreshDeviceScripts) {
                                Icon(
                                    Icons.Rounded.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Request list")
                            }
                        },
                    )
                }

                state.storage == ScriptStorage.PHONE -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 4.dp,
                            bottom = 100.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(items = state.localScripts, key = { it.id }) { script ->
                            LocalScriptCard(
                                script = script,
                                onRun = { viewModel.runLocalScript(script) },
                                onDelete = { deleteTarget = script },
                            )
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(items = state.deviceScripts, key = { it }) { name ->
                            DeviceScriptCard(
                                name = name,
                                onRun = { viewModel.runDeviceScript(name) },
                            )
                        }
                    }
                }
            }

            if (state.storage == ScriptStorage.PHONE) {
                ExtendedFloatingActionButton(
                    onClick = { showCreateDialog = true },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text("New script") },
                )
            }
        }
    }

    if (showCreateDialog) {
        CreateScriptDialog(
            onDismiss = { showCreateDialog = false },
            onSave = { name, content ->
                viewModel.saveScript(name, content)
                showCreateDialog = false
            },
        )
    }

    deleteTarget?.let { script ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            icon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
            title = { Text("Delete script?") },
            text = { Text("\"${script.name}\" will be removed from your phone library.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteScript(script)
                        deleteTarget = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun LocalScriptCard(
    script: LocalScript,
    onRun: () -> Unit,
    onDelete: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.Code,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = script.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${formatBytes(script.size)} · ${formatRelativeTime(script.updatedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledIconButton(onClick = onRun) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = "Upload and run ${script.name}",
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = "Delete ${script.name}",
                )
            }
        }
    }
}

@Composable
private fun DeviceScriptCard(
    name: String,
    onRun: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.Storage,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Device-stored payload",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledIconButton(onClick = onRun) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = "Run $name on the Stick",
                )
            }
        }
    }
}

@Composable
private fun EmptyLibrary(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(44.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.size(4.dp))
            action()
        }
    }
}

@Composable
private fun CreateScriptDialog(
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var content by rememberSaveable {
        mutableStateOf("// Describe what this payload does\n")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Code, contentDescription = null) },
        title = { Text("New script") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Name") },
                    placeholder = { Text("beacon_probe") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Payload") },
                    minLines = 6,
                    maxLines = 10,
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), content) },
                enabled = name.isNotBlank(),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
