package com.jarvis.assistant

import android.app.Application

class JarvisApp : Application() {
    lateinit var engine: JarvisEngine
    override fun onCreate() {
        super.onCreate()
        engine = JarvisEngine(this)
    }
}
