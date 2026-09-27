package com.quickbite.app

import android.app.Application
import org.osmdroid.config.Configuration

class QuickBiteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Configuration.getInstance().userAgentValue = packageName // OSM tile servers require a user agent
    }
}
