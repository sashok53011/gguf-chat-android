package com.devhorizon.online.ggufchat.data.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class TtsManager(private val context: Context) {
    private val TAG = "TtsManager"

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private var pendingText: String? = null

    fun initialize(onReady: (() -> Unit)? = null) {
        if (isInitialized) {
            onReady?.invoke()
            return
        }
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                _isReady.value = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _isSpeaking.value = true
                    }

                    override fun onDone(utteranceId: String?) {
                        _isSpeaking.value = false
                    }

                    override fun onError(utteranceId: String?) {
                        _isSpeaking.value = false
                    }
                })
                onReady?.invoke()
            } else {
                Log.e(TAG, "TTS initialization failed with status: $status")
                _isReady.value = false
            }
        }
    }

    fun setLanguage(language: String) {
        if (!isInitialized) return
        val locale = when (language) {
            "ru" -> Locale("ru", "RU")
            "de" -> Locale("de", "DE")
            else -> Locale("en", "US")
        }
        tts?.language = locale
    }

    fun setRate(rate: Float) {
        if (!isInitialized) return
        tts?.setSpeechRate(rate)
    }

    fun speak(text: String) {
        if (!isInitialized) {
            pendingText = text
            return
        }
        // Clean markdown for speech
        val cleaned = text
            .replace(Regex("""[*_`#\[\]()]"""), "")
            .replace(Regex("""\n+"""), ". ")
            .trim()

        if (cleaned.isEmpty()) return

        tts?.speak(cleaned, TextToSpeech.QUEUE_FLUSH, null, "gguf_tts_${System.currentTimeMillis()}")
    }

    fun stop() {
        tts?.stop()
        _isSpeaking.value = false
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        _isReady.value = false
        _isSpeaking.value = false
    }
}
