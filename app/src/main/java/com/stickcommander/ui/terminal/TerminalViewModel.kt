package com.stickcommander.ui.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.stickcommander.data.model.LogEntry
import com.stickcommander.data.model.LogLevel
import com.stickcommander.data.repository.DeviceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class TerminalUiState(
    val logs: List<LogEntry> = emptyList(),
    val filters: Set<LogLevel> = LogLevel.entries.toSet(),
    val autoScroll: Boolean = true,
    val totalCount: Int = 0,
)

class TerminalViewModel(
    private val repository: DeviceRepository,
) : ViewModel() {

    private val filters = MutableStateFlow(LogLevel.entries.toSet())
    private val autoScroll = MutableStateFlow(true)

    val uiState: StateFlow<TerminalUiState> = combine(
        repository.logs,
        filters,
        autoScroll,
    ) { logs, selectedFilters, shouldAutoScroll ->
        TerminalUiState(
            logs = logs.filter { it.level in selectedFilters },
            filters = selectedFilters,
            autoScroll = shouldAutoScroll,
            totalCount = logs.size,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TerminalUiState(),
    )

    /** Keeps at least one filter active so the console never silently goes blank. */
    fun toggleFilter(level: LogLevel) {
        filters.update { current ->
            if (level in current) {
                (current - level).ifEmpty { current }
            } else {
                current + level
            }
        }
    }

    fun setAutoScroll(enabled: Boolean) {
        autoScroll.value = enabled
    }

    fun clear() = repository.clearLogs()

    fun sendRaw(line: String) = repository.sendRaw(line)

    companion object {
        fun factory(repository: DeviceRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { TerminalViewModel(repository) }
        }
    }
}
