package com.abhijit.hisinchan.voice

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Text-to-speech through Android's TTS engine (Google Speech Services on most tablets).
 * Word-boundary callbacks (onRangeStart) drive Sinchan's lip-sync.
 * Interruptible via [stop]. Some "network" voices need internet — the voice picker labels them.
 */
class SpeechOutput(context: Context) {

    interface Callbacks {
        fun onStart() {}
        fun onWord(start: Int, end: Int) {}
        fun onDone()
        fun onError() { onDone() }
    }

    private val main = Handler(Looper.getMainLooper())
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking

    private var callbacks: Callbacks? = null
    private var lastUtterancePrefix = ""
    private var lastChunkId = ""
    private val counter = AtomicInteger()
    private var pending: (() -> Unit)? = null

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        _ready.value = status == TextToSpeech.SUCCESS
        if (status == TextToSpeech.SUCCESS) main.post { pending?.invoke(); pending = null }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId?.startsWith(lastUtterancePrefix) == true && utteranceId.endsWith("#0")) {
                    main.post { callbacks?.onStart() }
                }
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                if (utteranceId?.startsWith(lastUtterancePrefix) == true) main.post { callbacks?.onWord(start, end) }
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == lastChunkId) main.post {
                    _speaking.value = false
                    val cb = callbacks; callbacks = null
                    cb?.onDone()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId?.startsWith(lastUtterancePrefix) == true) main.post {
                    _speaking.value = false
                    val cb = callbacks; callbacks = null
                    cb?.onError()
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                @Suppress("DEPRECATION") onError(utteranceId)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                if (utteranceId?.startsWith(lastUtterancePrefix) == true) main.post { _speaking.value = false }
            }
        })
    }

    fun speak(text: String, languageTag: String, rate: Float, pitch: Float, voiceName: String, cb: Callbacks) {
        if (!_ready.value) {
            pending = { speak(text, languageTag, rate, pitch, voiceName, cb) }
            return
        }
        stop()
        applyVoice(languageTag, voiceName)
        tts.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
        tts.setPitch(pitch.coerceIn(0.5f, 2.0f))
        callbacks = cb
        lastUtterancePrefix = "u${counter.incrementAndGet()}#"
        val chunks = chunk(text, (TextToSpeech.getMaxSpeechInputLength() - 100).coerceAtLeast(500))
        _speaking.value = true
        chunks.forEachIndexed { i, c ->
            val id = lastUtterancePrefix + i
            if (i == chunks.lastIndex) lastChunkId = id
            val r = tts.speak(c, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, Bundle(), id)
            if (r == TextToSpeech.ERROR) {
                _speaking.value = false
                callbacks = null
                cb.onError()
                return
            }
        }
    }

    fun stop() {
        callbacks = null
        pending = null
        if (_ready.value) tts.stop()
        _speaking.value = false
    }

    private fun applyVoice(languageTag: String, voiceName: String) {
        val voices = runCatching { tts.voices }.getOrNull().orEmpty()
        val chosen = voices.firstOrNull { it.name == voiceName }
        if (chosen != null) {
            tts.voice = chosen
        } else {
            val locale = Locale.forLanguageTag(languageTag)
            val res = tts.setLanguage(locale)
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.ENGLISH)
            }
        }
    }

    /** Voices for a language, best quality first. */
    fun voicesFor(languageTag: String): List<VoiceOption> {
        val lang = Locale.forLanguageTag(languageTag).language
        return runCatching { tts.voices }.getOrNull().orEmpty()
            .filter { it.locale.language == lang && !it.features.contains("notInstalled") }
            .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.isNetworkConnectionRequired }.thenBy { it.name })
            .map {
                VoiceOption(
                    name = it.name,
                    label = buildString {
                        append(it.locale.displayCountry.ifBlank { it.locale.displayLanguage })
                        append(" · ")
                        append(it.name.substringAfterLast('-').take(18))
                        if (it.quality >= Voice.QUALITY_HIGH) append(" · high quality")
                        if (it.isNetworkConnectionRequired) append(" · needs internet")
                    },
                    needsNetwork = it.isNetworkConnectionRequired,
                )
            }
    }

    fun shutdown() {
        runCatching { tts.stop(); tts.shutdown() }
    }

    private fun chunk(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (sentence in text.split(Regex("(?<=[.!?।])\\s+"))) {
            if (sb.length + sentence.length + 1 > max && sb.isNotEmpty()) {
                out += sb.toString(); sb.clear()
            }
            if (sentence.length > max) sentence.chunked(max).forEach { out += it } else sb.append(sentence).append(' ')
        }
        if (sb.isNotBlank()) out += sb.toString().trim()
        return out
    }
}

data class VoiceOption(val name: String, val label: String, val needsNetwork: Boolean)
