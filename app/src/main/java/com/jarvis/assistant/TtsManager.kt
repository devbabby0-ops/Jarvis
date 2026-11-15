package com.jarvis.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class TtsManager(ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var callback: (() -> Unit)? = null
    private var seq = 0
    private val tts: TextToSpeech = TextToSpeech(ctx) { ready = it == TextToSpeech.SUCCESS }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) = finish(id)
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) = finish(id)
            override fun onStop(id: String?, interrupted: Boolean) {}
        })
    }

    private fun finish(id: String?) {
        main.post {
            if (id == "u$seq") { val cb = callback; callback = null; cb?.invoke() }
        }
    }

    fun speak(text: String, done: () -> Unit) {
        if (!ready) { done(); return }
        val hindi = text.any { it in '\u0900'..'\u097F' }
        tts.language = if (hindi) Locale("hi", "IN") else Locale("en", "IN")
        tts.setPitch(0.9f)
        callback = done
        seq++
        tts.speak(text.take(3500), TextToSpeech.QUEUE_FLUSH, null, "u$seq")
    }

    fun stop() { callback = null; tts.stop() }
}
