package com.stickcommander.ui.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.stickcommander.data.protocol.DeviceCommand
import com.stickcommander.data.protocol.NavDirection
import com.stickcommander.data.protocol.StickButton
import com.stickcommander.data.repository.DeviceRepository
import com.stickcommander.data.transport.ConnectionState
import com.stickcommander.data.model.DeviceStatus
import kotlinx.coroutines.flow.StateFlow

class RemoteViewModel(
    private val repository: DeviceRepository,
) : ViewModel() {

    val connection: StateFlow<ConnectionState> = repository.connection
    val display: StateFlow<List<String>> = repository.display
    val status: StateFlow<DeviceStatus> = repository.status

    /** Sends PRESS/RELEASE so firmware-side long-press gestures keep working. */
    fun onButton(button: StickButton, pressed: Boolean) {
        repository.sendButton(button, pressed)
    }

    fun onNav(direction: NavDirection, pressed: Boolean) {
        repository.sendNav(direction, pressed)
    }

    fun sendText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) {
            repository.send(DeviceCommand.Text(trimmed))
        }
    }

    fun stopScript() = repository.stopScript()

    fun clearDisplay() = repository.clearDisplay()

    companion object {
        fun factory(repository: DeviceRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { RemoteViewModel(repository) }
        }
    }
}
