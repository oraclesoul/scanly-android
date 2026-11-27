package com.scanly.app

import android.app.Application
import android.content.Context
import org.opencv.android.OpenCVLoader

class ScanlyApp : Application() {
    lateinit var repo: DocRepository
        private set
    lateinit var settings: AppSettings
        private set

    override fun onCreate() {
        super.onCreate()
        OpenCVLoader.initLocal()
        repo = DocRepository(this)
        settings = AppSettings(this)
    }
}

val Context.repo: DocRepository
    get() = (applicationContext as ScanlyApp).repo

val Context.appSettings: AppSettings
    get() = (applicationContext as ScanlyApp).settings
