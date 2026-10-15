package com.abhijit.hisinchan.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Speech-to-text for the user's question, using Android's SpeechRecognizer
 * (Google / device recognition service; usually needs internet unless the
 * offline language pack is installed). Must be used from the main thread.
 *
 * Note: this API is NOT a continuous wake-word listener — that job belongs to
 * [WakeWordService] (on-device Vosk). This class only runs for one utterance at a time.
 */
class SpeechInput(private val context: Context) {

    interface Listener {
        fun onReady() {}
        fun onSpeechStart() {}
        fun onLevel(rmsDb: Float) {}
        fun onPartial(text: String) {}
        fun onResult(text: String)
        fun onError(code: Int, message: String)
    }

    private var recognizer: SpeechRecognizer? = null
    var isListening = false
        private set

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(languageTag: String, listener: Listener) {
        cancel()
        val rec = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = listener.onReady()
            override fun onBeginningOfSpeech() = listener.onSpeechStart()
            override fun onRmsChanged(rmsdB: Float) = listener.onLevel(rmsdB)
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                isListening = false
                listener.onError(error, describe(error))
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isBlank()) listener.onError(SpeechRecognizer.ERROR_NO_MATCH, describe(SpeechRecognizer.ERROR_NO_MATCH))
                else listener.onResult(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }?.let(listener::onPartial)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        }
        isListening = true
        rec.startListening(intent)
    }

    /** Stop and deliver whatever was heard so far. */
    fun stop() {
        recognizer?.stopListening()
    }

    /** Abort without a result. */
    fun cancel() {
        if (isListening) recognizer?.cancel()
        isListening = false
    }

    fun destroy() {
        runCatching { recognizer?.destroy() }
        recognizer = null
        isListening = false
    }

    companion object {
        fun describe(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_NO_MATCH -> "I didn't catch that."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't hear anything."
            SpeechRecognizer.ERROR_AUDIO -> "The microphone had a problem."
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                "Speech recognition needs the internet right now."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "I need microphone permission to listen."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech recognizer is busy."
            SpeechRecognizer.ERROR_CLIENT -> "Listening was stopped."
            SpeechRecognizer.ERROR_SERVER -> "The speech service had a problem."
            11 /* ERROR_SERVER_DISCONNECTED */ -> "The speech service disconnected."
            12 /* ERROR_LANGUAGE_NOT_SUPPORTED */ -> "That language isn't supported by the speech service."
            13 /* ERROR_LANGUAGE_UNAVAILABLE */ -> "That language pack isn't downloaded yet."
            else -> "Speech recognition error ($code)."
        }
    }
}
