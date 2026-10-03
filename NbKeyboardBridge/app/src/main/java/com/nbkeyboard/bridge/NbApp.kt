package com.nbkeyboard.bridge

import android.app.Application

class NbApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: NbApp
            private set
    }
}
