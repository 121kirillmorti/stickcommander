package com.stickcommander.ui.tools

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.stickcommander.data.model.LogLevel
import com.stickcommander.data.protocol.ProtocolEvent
import com.stickcommander.data.repository.DeviceRepository
import com.stickcommander.data.transport.isConnected
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Base64

data class LiveMenuItem(
    val index: Int,
    val label: String,
    val enabled: Boolean,
)

data class MenuUiState(
    val title: String = "",
    val items: List<LiveMenuItem> = emptyList(),
    val selectedIndex: Int = -1,
    val available: Boolean = false,
    val loading: Boolean = false,
)

data class FsEntryItem(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
)

data class FsUiState(
    val path: String = "/",
    val entries: List<FsEntryItem> = emptyList(),
    val busy: Boolean = false,
    val uploading: Boolean = false,
    val uploadProgress: Float? = null,
    val previewTitle: String? = null,
    val previewText: String? = null,
)

class ToolsViewModel(
    private val repository: DeviceRepository,
    private val context: Context,
) : ViewModel() {

    private val _menu = MutableStateFlow(MenuUiState())
    val menu: StateFlow<MenuUiState> = _menu.asStateFlow()

    private val _fs = MutableStateFlow(FsUiState())
    val fs: StateFlow<FsUiState> = _fs.asStateFlow()

    val display: StateFlow<List<String>> = repository.display
    val connection = repository.connection

    private var menuTimeout: Job? = null
    private var downloadPath: String = ""
    private var downloadSize: Long = 0
    private var downloadBuffer = StringBuilder()

    init {
        viewModelScope.launch {
            repository.events.collect { event -> handleEvent(event) }
        }
    }

    // ------------------------------------------------------------------ menu

    fun refreshMenu() {
        if (!repository.connection.value.isConnected) return
        _menu.update { it.copy(loading = true) }
        repository.requestMenus()
        menuTimeout?.cancel()
        menuTimeout = viewModelScope.launch {
            delay(1_500)
            _menu.update { it.copy(loading = false) }
        }
    }

    fun openMenu(index: Int) {
        repository.openMenu(index)
    }

    fun menuBack() = repository.menuBack()

    fun menuHome() = repository.menuHome()

    // ----------------------------------------------------------------- screen

    fun sendScreenText(text: String) {
        if (text.isBlank()) {
            repository.postMessage("Enter text for the Stick display.")
            return
        }
        repository.sendScreenText(text)
        repository.addLog(LogLevel.INFO, "screen", "Sent to Stick display")
    }

    fun clearScreen() = repository.clearScreen()

    // --------------------------------------------------------------------- IR

    fun sendIr(protocol: String, address: String, command: String) {
        val addr = normalizeHexByte(address)
        val cmd = normalizeHexByte(command)
        if (addr.isEmpty() || cmd.isEmpty()) {
            repository.postMessage("IR address and command must be hex bytes.")
            return
        }
        repository.sendIr(protocol, addr, cmd)
        repository.postMessage("IR $protocol $addr:$cmd sent.")
    }

    // ------------------------------------------------------------------ files

    fun refreshFs(path: String = _fs.value.path) {
        val normalized = normalizePath(path)
        _fs.update {
            it.copy(path = normalized, entries = emptyList(), busy = true)
        }
        repository.fsList(normalized)
    }

    fun openEntry(entry: FsEntryItem) {
        val target = joinPath(_fs.value.path, entry.name)
        if (entry.isDirectory) {
            refreshFs(target)
        } else {
            readFile(target)
        }
    }

    fun goUp() {
        val current = _fs.value.path
        if (current == "/") return
        val parent = current.substringBeforeLast('/').ifEmpty { "/" }
        refreshFs(parent)
    }

    fun deleteEntry(entry: FsEntryItem) {
        val target = joinPath(_fs.value.path, entry.name)
        _fs.update { it.copy(busy = true) }
        repository.fsDelete(target)
    }

    fun makeDirectory(name: String) {
        val clean = name.trim().replace("/", "_").replace("\\", "_")
        if (clean.isBlank()) return
        val target = joinPath(_fs.value.path, clean)
        _fs.update { it.copy(busy = true) }
        repository.fsMkdir(target)
    }

    fun readFile(path: String) {
        _fs.update {
            it.copy(busy = true, previewTitle = null, previewText = null)
        }
        repository.fsRead(path)
    }

    fun clearPreview() {
        _fs.update { it.copy(previewTitle = null, previewText = null) }
    }

    fun uploadDocument(uri: Uri) {
        if (!repository.connection.value.isConnected) {
            repository.postMessage("Connect to your Stick before uploading.")
            return
        }
        viewModelScope.launch {
            val uploaded = withContext(Dispatchers.IO) { readDocument(uri) }
            if (uploaded == null) {
                repository.postMessage("Could not read the selected file.")
                return@launch
            }
            val (name, data) = uploaded
            if (data.size > MAX_UPLOAD_BYTES) {
                repository.postMessage("File is larger than 128 KB. Use a smaller file.")
                return@launch
            }

            val target = joinPath(_fs.value.path, sanitizeFileName(name))
            _fs.update {
                it.copy(uploading = true, busy = true, uploadProgress = 0f)
            }
            try {
                repository.fsUploadBegin(target, data.size)
                var offset = 0
                while (offset < data.size) {
                    val end = minOf(offset + UPLOAD_CHUNK, data.size)
                    repository.fsUploadChunk(data.copyOfRange(offset, end))
                    offset = end
                    _fs.update {
                        it.copy(uploadProgress = offset.toFloat() / data.size.toFloat())
                    }
                }
                repository.fsUploadEnd()
            } catch (t: Throwable) {
                _fs.update {
                    it.copy(uploading = false, busy = false, uploadProgress = null)
                }
                repository.postMessage("Upload failed: ${t.message ?: "unknown error"}")
            }
        }
    }

    // ------------------------------------------------------------- event bridge

    private fun handleEvent(event: ProtocolEvent) {
        when (event) {
            is ProtocolEvent.MenuTitle -> _menu.value = MenuUiState(
                title = event.title,
                available = true,
                loading = true,
            )

            is ProtocolEvent.MenuItem -> _menu.update { current ->
                current.copy(
                    items = (current.items.filterNot { it.index == event.index } +
                        LiveMenuItem(event.index, event.label, event.enabled))
                        .sortedBy { it.index },
                )
            }

            is ProtocolEvent.MenuSelected -> _menu.update { it.copy(selectedIndex = event.index) }

            ProtocolEvent.MenuEnd -> _menu.update {
                it.copy(loading = false, available = true)
            }

            ProtocolEvent.MenuNone -> _menu.value = MenuUiState(available = false)

            is ProtocolEvent.FsListing -> _fs.update {
                it.copy(path = event.path, entries = emptyList(), busy = true)
            }

            is ProtocolEvent.FsEntry -> _fs.update { current ->
                val entry = FsEntryItem(event.name, event.isDirectory, event.size)
                current.copy(
                    entries = (current.entries.filterNot { it.name == entry.name } + entry)
                        .sortedWith(
                            compareByDescending<FsEntryItem> { it.isDirectory }
                                .thenBy { it.name.lowercase() },
                        ),
                )
            }

            ProtocolEvent.FsListEnd -> _fs.update { it.copy(busy = false) }

            is ProtocolEvent.FsFileStart -> {
                downloadPath = event.path
                downloadSize = event.size
                downloadBuffer = StringBuilder()
                _fs.update { it.copy(busy = true) }
            }

            is ProtocolEvent.FsData -> downloadBuffer.append(event.base64)

            ProtocolEvent.FsFileEnd -> finishDownload()

            is ProtocolEvent.FsError -> {
                _fs.update {
                    it.copy(busy = false, uploading = false, uploadProgress = null)
                }
                repository.postMessage(event.message)
            }

            is ProtocolEvent.FsOk -> {
                if (event.action == "begin") {
                    _fs.update { it.copy(busy = true) }
                    return
                }
                _fs.update {
                    it.copy(busy = false, uploading = false, uploadProgress = null)
                }
                repository.postMessage(fsOkMessage(event))
                if (event.action != "write") refreshFs()
            }

            else -> Unit
        }
    }

    private fun finishDownload() {
        val bytes = runCatching { Base64.getDecoder().decode(downloadBuffer.toString()) }
            .getOrElse { ByteArray(0) }
        val text = if (bytes.isNotEmpty() && looksTextual(bytes)) {
            bytes.toString(Charsets.UTF_8)
        } else {
            "Binary file — ${bytes.size} bytes."
        }
        _fs.update {
            it.copy(
                busy = false,
                previewTitle = downloadPath.ifBlank { "File" },
                previewText = text,
            )
        }
        downloadBuffer = StringBuilder()
        if (downloadSize > MAX_PREVIEW_BYTES) {
            repository.postMessage(
                "File is ${downloadSize} bytes; preview is truncated by the link.",
            )
        }
    }

    private fun fsOkMessage(event: ProtocolEvent.FsOk): String = when (event.action) {
        "upload" -> "Uploaded ${event.path.substringAfterLast('/')} (${event.size} bytes)."
        "delete" -> "Deleted ${event.path.substringAfterLast('/')}."
        "mkdir" -> "Created folder ${event.path.substringAfterLast('/')}."
        "rename" -> "Renamed to ${event.path.substringAfterLast('/')}."
        else -> "Filesystem: ${event.action}."
    }

    // ------------------------------------------------------------------ helpers

    private fun readDocument(uri: Uri): Pair<String, ByteArray>? {
        return try {
            val name = queryDisplayName(uri) ?: "upload.bin"
            val input = context.contentResolver.openInputStream(uri) ?: return null
            val data = input.use { it.readBytes() }
            name to data
        } catch (_: Throwable) {
            null
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim()
        return cleaned.ifBlank { "upload.bin" }
    }

    private fun normalizePath(path: String): String {
        var value = path.trim()
        if (value.isEmpty()) return "/"
        if (!value.startsWith("/")) value = "/$value"
        value = value.replace(Regex("/+"), "/")
        if (value.contains("..")) return "/"
        if (value.length > 1 && value.endsWith("/")) value = value.dropLast(1)
        return value
    }

    private fun joinPath(parent: String, name: String): String {
        val base = normalizePath(parent)
        return if (base == "/") "/$name" else "$base/$name"
    }

    private fun normalizeHexByte(value: String): String {
        val cleaned = value.trim()
            .removePrefix("0x")
            .removePrefix("0X")
            .filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
            .uppercase()
        if (cleaned.isEmpty()) return ""
        return if (cleaned.length > 2) cleaned.takeLast(2) else cleaned.padStart(2, '0')
    }

    private fun looksTextual(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        var printable = 0
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            if (value == 9 || value == 10 || value == 13 || value in 32..126 || value >= 160) {
                printable++
            }
        }
        return printable.toFloat() / bytes.size >= 0.85f
    }

    companion object {
        private const val UPLOAD_CHUNK = 256
        private const val MAX_UPLOAD_BYTES = 128 * 1024
        private const val MAX_PREVIEW_BYTES = 48L * 1024

        fun factory(
            repository: DeviceRepository,
            context: Context,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ToolsViewModel(repository, context.applicationContext) }
        }
    }
}
