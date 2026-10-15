package com.lifedeck.app

import android.app.Application
import com.lifedeck.app.data.AppContainer

/**
 * Application entry point. Owns the manual dependency container so every layer
 * (data, notifications, sync, UI) shares one set of singletons.
 */
class LifeDeckApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)
        container.bootstrap()
    }

    companion object {
        lateinit var instance: LifeDeckApp
            private set
    }
}
