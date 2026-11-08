package com.stickcommander.di

import android.content.Context
import com.stickcommander.data.repository.DeviceRepository
import com.stickcommander.data.script.ScriptRepository
import com.stickcommander.data.transport.ble.BleConnectionManager
import com.stickcommander.data.transport.usb.UsbConnectionManager

/**
 * Small hand-rolled DI container. Everything is app-scoped, which mirrors the
 * lifecycle of the physical connection and keeps the sample dependency-free.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val usbConnectionManager = UsbConnectionManager(appContext)
    val bleConnectionManager = BleConnectionManager(appContext)

    val deviceRepository = DeviceRepository(
        context = appContext,
        usb = usbConnectionManager,
        ble = bleConnectionManager,
    )

    val scriptRepository = ScriptRepository(appContext)
}
