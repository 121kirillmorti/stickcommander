package com.stickcommander.data.script

import android.content.Context
import com.stickcommander.data.model.LocalScript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Phone-side script library. Payloads live in the app's private files directory,
 * are seeded on first launch, and can be uploaded to the Stick with CMD:SCRIPT:LOAD.
 */
class ScriptRepository(context: Context) {

    private val directory = File(context.filesDir, "scripts").apply { mkdirs() }
    private val _scripts = MutableStateFlow<List<LocalScript>>(emptyList())
    val scripts: StateFlow<List<LocalScript>> = _scripts.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        seedDefaults()
        val loaded = directory.listFiles()
            ?.filter { it.isFile && it.extension.equals("txt", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { file ->
                runCatching {
                    LocalScript(
                        id = file.name,
                        name = file.nameWithoutExtension,
                        content = file.readText(),
                        size = file.length(),
                        updatedAt = file.lastModified(),
                    )
                }.getOrNull()
            }
            .orEmpty()
        _scripts.value = loaded
    }

    suspend fun save(name: String, content: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val safeName = sanitize(name)
            val file = File(directory, "$safeName.txt")
            require(file.canonicalPath.startsWith(directory.canonicalPath)) {
                "Invalid script name."
            }
            file.writeText(content)
            refresh()
        }
    }

    suspend fun delete(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(directory, id)
            require(file.canonicalPath.startsWith(directory.canonicalPath)) {
                "Invalid script path."
            }
            if (file.exists() && !file.delete()) {
                error("Could not delete ${file.name}.")
            }
            refresh()
        }
    }

    private fun sanitize(name: String): String {
        val cleaned = name.trim()
            .removeSuffix(".txt")
            .replace(Regex("[^A-Za-z0-9 _.-]"), "_")
            .trim('.', ' ')
        return cleaned.ifBlank { "script" }.take(48)
    }

    private fun seedDefaults() {
        if (directory.listFiles()?.any { it.isFile } == true) return
        DEFAULT_SCRIPTS.forEach { (name, content) ->
            runCatching { File(directory, "$name.txt").writeText(content) }
        }
    }

    private companion object {
        val DEFAULT_SCRIPTS = mapOf(
            "beacon_probe" to """
                // beacon_probe — inventory nearby BLE advertisements.
                // StickCommander uploads this payload with CMD:SCRIPT:LOAD.
                // The firmware prints results back as BEACON:... frames.
                log("beacon probe started");
                ble.scan(15);
                log("beacon probe finished");
            """.trimIndent(),
            "ir_sweep" to """
                // ir_sweep — walk the common NEC address space.
                for (let address = 0; address < 256; address++) {
                    ir.sendNec(address, 0x00, 32);
                    delay(40);
                }
                log("ir sweep complete");
            """.trimIndent(),
            "link_report" to """
                // link_report — ask the stick for a fresh telemetry snapshot.
                status();
                ping();
                log("link report requested");
            """.trimIndent(),
        )
    }
}
