package com.stickcommander

import android.app.Application
import com.stickcommander.di.AppContainer

class StickCommanderApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
