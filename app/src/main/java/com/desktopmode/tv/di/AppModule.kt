package com.desktopmode.tv.di

import com.desktopmode.tv.network.NsdScanner
import com.desktopmode.tv.network.VideoClient
import com.desktopmode.tv.network.InputClient
import org.koin.dsl.module

val appModule = module {
    single { NsdScanner(get()) }
    single { VideoClient() }
    single { InputClient() }
}
