package dev.winniesi.zbox

import android.app.Application
import dev.winniesi.zbox.di.AppContainer

class ZBoxApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
