package com.sierxue.english

import android.app.Application
import com.sierxue.english.di.AppContainer

class SiErXueApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
