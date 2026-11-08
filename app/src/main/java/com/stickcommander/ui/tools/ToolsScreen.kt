@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.stickcommander.ui.tools

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stickcommander.data.transport.isConnected
import com.stickcommander.ui.components.SectionTitle
import com.stickcommander.ui.util.formatBytes
import kotlinx.coroutines.delay

enum class ToolSection(val label: String, val icon: ImageVector) {
    MENU("Live menu", Icons.Rounded.Menu),
    SCREEN("Screen", Icons.Rounded.Smartphone),
    IR("IR", Icons.Rounded.SettingsRemote),
    FILES("Files", Icons.Rounded.Folder),
}

@Composable
fun ToolsScreen(
    viewModel: ToolsViewModel,
    modifier: Modifier = Modifier,
) {
    val menu by viewModel.menu.collectAsStateWithLifecycle()
    val fs by viewModel.fs.collectAsStateWithLifecycle()
    val display by viewModel.display.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()

    var section by rememberSaveable { mutableStateOf(ToolSection.MENU) }
    val uploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.uploadDocument(uri)
    }

    LaunchedEffect(connection.isConnected) {
        if (connection.isConnected) {
            viewModel.refreshMenu()
            viewModel.refreshFs()
        }
    }

    LaunchedEffect(section, connection.isConnected) {
        if (connection.isConnected &&
            (section == ToolSection.MENU || section == ToolSection.SCREEN)
        ) {
            while (true) {
                viewModel.refreshMenu()
                delay(1_000)
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        ToolSelector(selected = section, onSelect = { section = it })
        HorizontalDivider()

        when (section) {
            ToolSection.MENU -> MenuTool(
                menu = menu,
                onOpen = viewModel::openMenu,
                onBack = viewModel::menuBack,
                onHome = viewModel::menuHome,
                onRefresh = viewModel::refreshMenu,
                modifier = Modifier.weight(1f),
            )

            ToolSection.SCREEN -> ScreenTool(
                menu = menu,
                display = display,
                onSendText = viewModel::sendScreenText,
                onClear = viewModel::clearScreen,
                modifier = Modifier.weight(1f),
            )

            ToolSection.IR -> IrTool(
                onSend = viewModel::sendIr,
                modifier = Modifier.weight(1f),
            )

            ToolSection.FILES -> FileTool(
                state = fs,
                onOpen = viewModel::openEntry,
                onUp = viewModel::goUp,
                onRefresh = { viewModel.refreshFs() },
                onMkdir = viewModel::makeDirectory,
                onDelete = viewModel::deleteEntry,
                onUpload = { uploadLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.weight(1f),
            )
        }
    }

    fs.previewText?.let { text ->
        FilePreviewDialog(
            title = fs.previewTitle ?: "Preview",
            text = text,
            onDismiss = viewModel::clearPreview,
        )
    }
}

@Composable
private fun ToolSelector(
    selected: ToolSection,
    onSelect: (ToolSection) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ToolSection.entries.forEach { section ->
            FilterChip(
                selected = selected == section,
                onClick = { onSelect(section) },
                label = { Text(section.label) },
                leadingIcon = {
                    Icon(
                        imageVector = section.icon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
        }
    }
}

@Composable
private fun MenuTool(
    menu: MenuUiState,
    onOpen: (Int) -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle(
            title = "Live Stick menu",
            subtitle = "This is the current menu on the Stick. Tap an item to select it remotely.",
        )
        StickScreenCard(
            menu = menu,
            onSelect = onOpen,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onRefresh) {
                Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Refresh")
            }
            OutlinedButton(onClick = onBack, enabled = menu.available) {
                Icon(Icons.Rounded.ArrowBack, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Back")
            }
            OutlinedButton(onClick = onHome, enabled = menu.available) {
                Icon(Icons.Rounded.Home, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Main")
            }
        }
        Text(
            text = if (menu.loading) "Syncing menu…" else "Live updates every second.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StickScreenCard(
    menu: MenuUiState,
    modifier: Modifier = Modifier,
    onSelect: ((Int) -> Unit)? = null,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF0A0F14),
        shadowElevation = 6.dp,
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "M5StickC Plus 2",
                    color = Color(0xFF7FB3FF),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "LIVE UI",
                    color = Color(0xFF7F8C99),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 6.dp),
                color = Color(0xFF1E2A36),
            )

            if (!menu.available) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No menu data.\nOpen a menu on the Stick\nor press Refresh.",
                        color = Color(0xFF8A9BA8),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                Text(
                    text = menu.title.ifBlank { "Menu" },
                    color = Color(0xFFFFB784),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(items = menu.items, key = { it.index }) { item ->
                        val selected = item.index == menu.selectedIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 1.dp)
                                .background(
                                    color = if (selected) Color(0xFF1E3320) else Color.Transparent,
                                    shape = RoundedCornerShape(4.dp),
                                )
                                .clickable(
                                    enabled = onSelect != null && item.enabled,
                                    onClick = { onSelect?.invoke(item.index) },
                                )
                                .padding(horizontal = 4.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (selected) ">" else " ",
                                color = Color(0xFFB9F6CA),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                            )
                            Text(
                                text = item.label,
                                color = if (item.enabled) {
                                    Color(0xFFB9F6CA)
                                } else {
                                    Color(0xFF5A6A70)
                                },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            Text(
                text = "135 × 240 · text mirror",
                color = Color(0xFF7F8C99),
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ScreenTool(
    menu: MenuUiState,
    display: List<String>,
    onSendText: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable { mutableStateOf("Hello from StickCommander") }

    Column(
        modifier = modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StickScreenCard(
            menu = menu,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SectionTitle(
                    title = "Phone → Stick display",
                    subtitle = "Draws text on the Stick's own TFT",
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 3,
                    placeholder = { Text("Text to show on the Stick…") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onSendText(text) },
                        enabled = text.isNotBlank(),
                    ) {
                        Icon(Icons.Rounded.Send, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Send to Stick")
                    }
                    OutlinedButton(onClick = onClear) {
                        Text("Clear")
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "Mirrored DISPLAY frames",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = display.takeLast(4).joinToString("\n").ifBlank { "—" },
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun IrTool(
    onSend: (String, String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var protocol by rememberSaveable { mutableStateOf("NEC") }
    var address by rememberSaveable { mutableStateOf("04") }
    var command by rememberSaveable { mutableStateOf("08") }
    val protocols = listOf("NEC", "SAMSUNG", "SONY")

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle(
            title = "IR blaster",
            subtitle = "Use the Stick as an external IR transmitter for your phone.",
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            protocols.forEachIndexed { index, name ->
                SegmentedButton(
                    selected = protocol == name,
                    onClick = { protocol = name },
                    shape = SegmentedButtonDefaults.itemShape(index, protocols.size),
                    label = { Text(name) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier.weight(1f),
                label = { Text("Address") },
                placeholder = { Text("04") },
                singleLine = true,
            )
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                modifier = Modifier.weight(1f),
                label = { Text("Command") },
                placeholder = { Text("08") },
                singleLine = true,
            )
        }
        Text(
            text = "Two hex digits per field. Point the Stick's IR LED at the device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SectionTitle(title = "NEC presets", subtitle = "Address 04")
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                "Power" to "08",
                "Vol +" to "02",
                "Vol −" to "03",
                "Mute" to "0C",
            ).forEach { (label, code) ->
                AssistChip(
                    onClick = {
                        address = "04"
                        command = code
                    },
                    label = { Text(label) },
                )
            }
        }
        Button(
            onClick = { onSend(protocol, address, command) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled = address.isNotBlank() && command.isNotBlank(),
        ) {
            Icon(Icons.Rounded.SettingsRemote, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Send IR")
        }
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("How it works", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "The Stick emits the code through its IR LED; the phone only sends " +
                        "the protocol, address and command. Use the Terminal tab for raw IR files.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FileTool(
    state: FsUiState,
    onOpen: (FsEntryItem) -> Unit,
    onUp: () -> Unit,
    onRefresh: () -> Unit,
    onMkdir: (String) -> Unit,
    onDelete: (FsEntryItem) -> Unit,
    onUpload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showMkdir by rememberSaveable { mutableStateOf(false) }
    var folderName by rememberSaveable { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<FsEntryItem?>(null) }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onUp, enabled = state.path != "/") {
                Icon(Icons.Rounded.ArrowUpward, contentDescription = "Parent folder")
            }
            Text(
                text = state.path,
                modifier = Modifier.weight(1f),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onRefresh) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Refresh files")
            }
            IconButton(onClick = { showMkdir = true }) {
                Icon(Icons.Rounded.CreateNewFolder, contentDescription = "Create folder")
            }
            IconButton(onClick = onUpload) {
                Icon(Icons.Rounded.UploadFile, contentDescription = "Upload file")
            }
        }

        if (state.uploading) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                LinearProgressIndicator(
                    progress = { state.uploadProgress ?: 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Uploading… ${((state.uploadProgress ?: 0f) * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.busy && state.entries.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(items = state.entries, key = { it.name }) { entry ->
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(entry) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = if (entry.isDirectory) {
                                    Icons.Rounded.Folder
                                } else {
                                    Icons.Rounded.InsertDriveFile
                                },
                                contentDescription = null,
                                tint = if (entry.isDirectory) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.tertiary
                                },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = if (entry.isDirectory) "Folder" else formatBytes(entry.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { deleteTarget = entry }) {
                                Icon(
                                    Icons.Rounded.DeleteOutline,
                                    contentDescription = "Delete ${entry.name}",
                                )
                            }
                        }
                    }
                }

                if (!state.busy && state.entries.isEmpty()) {
                    item {
                        Text(
                            text = "Folder is empty. Upload a file or create a folder.",
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Text(
            text = "LittleFS · tap a file to preview · up to 128 KB per upload",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showMkdir) {
        AlertDialog(
            onDismissRequest = { showMkdir = false },
            title = { Text("New folder") },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("Folder name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onMkdir(folderName)
                        folderName = ""
                        showMkdir = false
                    },
                    enabled = folderName.isNotBlank(),
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMkdir = false }) { Text("Cancel") }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete?") },
            text = { Text("\"${target.name}\" will be removed from the Stick.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(target)
                        deleteTarget = null
                    },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun FilePreviewDialog(
    title: String,
    text: String,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title.substringAfterLast('/'),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Box(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                SelectionContainer {
                    Text(
                        text = text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(text))
                    onDismiss()
                },
            ) {
                Text("Copy")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
