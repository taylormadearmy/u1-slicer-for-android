package com.u1.slicer

import android.app.Application

class U1SlicerApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        AppForegroundTracker.register()
        container = AppContainer(this)
    }
}
