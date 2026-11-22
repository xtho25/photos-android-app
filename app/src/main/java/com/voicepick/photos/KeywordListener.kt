package com.voicepick.photos

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

enum class ListenStatus { Off, Disabled, Starting, Listening, Hearing, Unavailable, NoPermission, Error }

/**
 * Keeps Android's SpeechRecognizer running in a loop in [language] (e.g. "el-GR") and calls
 * [onKeyword] once each time [matcher]'s word is heard. Must be used from the main thread.
 */
class KeywordListener(
    private val context: Context,
    private val matcher: KeywordMatcher,
    private val language: String,
    private val onKeyword: () -> Unit,
    private val onStatus: (ListenStatus) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var preferOffline = true
    private var mutedMusic = false

    /** How many keywords we already fired for in the current utterance. */
    private var firedInUtterance = 0
    private var lastFireAt = 0L

    fun start() {
        if (running) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onStatus(ListenStatus.Unavailable)
            return
        }
        running = true
        muteBeep(true)
        recreateRecognizer()
        listen()
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.run {
            runCatching { cancel() }
            runCatching { destroy() }
        }
        recognizer = null
        muteBeep(false)
        onStatus(ListenStatus.Off)
    }

    private fun recreateRecognizer() {
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(listener)
        }
    }

    private fun listen() {
        if (!running) return
        firedInUtterance = 0
        onStatus(ListenStatus.Starting)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.w(TAG, "startListening failed", e)
            restart(delayMs = 500, recreate = true)
        }
    }

    private fun restart(delayMs: Long, recreate: Boolean = false) {
        if (!running) return
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (!running) return@postDelayed
            if (recreate) recreateRecognizer()
            listen()
        }, delayMs)
    }

    private fun handleText(texts: List<String>, isFinal: Boolean) {
        if (texts.isEmpty()) return
        Log.d(TAG, (if (isFinal) "final: " else "partial: ") + texts.joinToString(" / "))
        // Partial results: only the best guess. Final: the best of all alternatives.
        val count = if (isFinal) texts.maxOf { matcher.count(it) } else matcher.count(texts[0])
        if (count > firedInUtterance) {
            firedInUtterance = count
            val now = SystemClock.elapsedRealtime()
            if (now - lastFireAt >= DEBOUNCE_MS) {
                lastFireAt = now
                Log.i(TAG, "keyword detected")
                onKeyword()
            }
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = onStatus(ListenStatus.Listening)
        override fun onBeginningOfSpeech() = onStatus(ListenStatus.Hearing)
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            handleText(results(partialResults), isFinal = false)
        }

        override fun onResults(results: Bundle?) {
            handleText(results(results), isFinal = true)
            restart(delayMs = 50)
        }

        override fun onError(error: Int) {
            Log.d(TAG, "error $error")
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restart(delayMs = 50)

                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_CLIENT,
                SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> restart(delayMs = 400, recreate = true)

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    onStatus(ListenStatus.NoPermission)
                    running = false
                    muteBeep(false)
                }

                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    if (preferOffline) {
                        // No offline language pack: fall back to online recognition.
                        preferOffline = false
                        restart(delayMs = 200, recreate = true)
                    } else {
                        onStatus(ListenStatus.Unavailable)
                        restart(delayMs = 5000, recreate = true)
                    }
                }

                else -> { // network, audio, server, too many requests...
                    onStatus(ListenStatus.Error)
                    restart(delayMs = 1500, recreate = true)
                }
            }
        }
    }

    /** Mutes the music stream so that the recognizer's start "beep" is silent on most phones. */
    private fun muteBeep(mute: Boolean) {
        if (mute == mutedMusic) return
        runCatching {
            audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                0,
            )
            mutedMusic = mute
        }
    }

    companion object {
        private const val TAG = "KeywordListener"
        private const val DEBOUNCE_MS = 1200L

        private fun results(b: Bundle?): List<String> =
            b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
    }
}
