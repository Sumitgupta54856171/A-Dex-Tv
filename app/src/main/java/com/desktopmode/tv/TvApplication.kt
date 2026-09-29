package com.desktopmode.tv

import android.app.Application
import com.desktopmode.tv.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import timber.log.Timber

class TvApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())

        startKoin {
            androidContext(this@TvApplication)
            modules(appModule)
        }
    }
}
