package com.stickcommander.ui.scripts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.stickcommander.data.model.LocalScript
import com.stickcommander.data.model.LogLevel
import com.stickcommander.data.repository.DeviceRepository
import com.stickcommander.data.script.ScriptRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ScriptStorage(val label: String) {
    PHONE("On phone"),
    DEVICE("On device"),
}

data class ScriptsUiState(
    val storage: ScriptStorage = ScriptStorage.PHONE,
    val localScripts: List<LocalScript> = emptyList(),
    val deviceScripts: List<String> = emptyList(),
    val busy: Boolean = true,
)

class ScriptManagerViewModel(
    private val scriptRepository: ScriptRepository,
    private val deviceRepository: DeviceRepository,
) : ViewModel() {

    private val storage = MutableStateFlow(ScriptStorage.PHONE)
    private val busy = MutableStateFlow(true)

    val uiState: StateFlow<ScriptsUiState> = combine(
        scriptRepository.scripts,
        deviceRepository.deviceScripts,
        storage,
        busy,
    ) { localScripts, deviceScripts, selectedStorage, isLoading ->
        ScriptsUiState(
            storage = selectedStorage,
            localScripts = localScripts,
            deviceScripts = deviceScripts,
            busy = isLoading,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ScriptsUiState(),
    )

    init {
        refreshLocalScripts()
    }

    fun selectStorage(value: ScriptStorage) {
        storage.value = value
        if (value == ScriptStorage.DEVICE) {
            deviceRepository.requestDeviceScripts()
        }
    }

    fun refreshLocalScripts() {
        viewModelScope.launch {
            busy.value = true
            runCatching { scriptRepository.refresh() }
            busy.value = false
        }
    }

    fun refreshDeviceScripts() {
        deviceRepository.requestDeviceScripts()
    }

    fun runLocalScript(script: LocalScript) {
        deviceRepository.runLocalScript(script)
    }

    fun runDeviceScript(name: String) {
        deviceRepository.runDeviceScript(name)
    }

    fun saveScript(name: String, content: String) {
        viewModelScope.launch {
            busy.value = true
            scriptRepository.save(name, content)
                .onSuccess {
                    deviceRepository.addLog(LogLevel.INFO, "app", "Saved script: $name")
                    deviceRepository.postMessage("Saved \"$name\" to your phone library.")
                }
                .onFailure {
                    deviceRepository.postMessage(it.message ?: "Could not save the script.")
                }
            busy.value = false
        }
    }

    fun deleteScript(script: LocalScript) {
        viewModelScope.launch {
            scriptRepository.delete(script.id)
                .onSuccess {
                    deviceRepository.addLog(LogLevel.INFO, "app", "Deleted script: ${script.name}")
                }
                .onFailure {
                    deviceRepository.postMessage(it.message ?: "Could not delete the script.")
                }
        }
    }

    companion object {
        fun factory(
            scriptRepository: ScriptRepository,
            deviceRepository: DeviceRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ScriptManagerViewModel(scriptRepository, deviceRepository) }
        }
    }
}
