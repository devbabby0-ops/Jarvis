package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Thin wrapper over Android SpeechRecognizer. Must be used from the main thread. */
class SpeechManager(private val ctx: Context, private val cb: Callback) {

    interface Callback {
        fun onResult(text: String)
        fun onPartial(text: String)
        fun onLevel(level: Float)
        fun onError(code: Int)
    }

    private var rec: SpeechRecognizer? = null

    fun start() {
        cancel()
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { cb.onError(-1); return }
        rec = SpeechRecognizer.createSpeechRecognizer(ctx).also { it.setRecognitionListener(listener) }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        }
        rec?.startListening(i)
    }

    fun cancel() {
        rec?.let { it.setRecognitionListener(null); it.cancel(); it.destroy() }
        rec = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { cb.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) { cb.onError(error) }
        override fun onResults(r: Bundle?) {
            val t = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            cb.onResult(t)
        }
        override fun onPartialResults(r: Bundle?) {
            r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { cb.onPartial(it) }
        }
        override fun onEvent(t: Int, p: Bundle?) {}
    }
}
