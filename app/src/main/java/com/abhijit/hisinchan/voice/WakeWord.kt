package com.abhijit.hisinchan.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.abhijit.hisinchan.MainActivity
import com.abhijit.hisinchan.R
import com.abhijit.hisinchan.ai.sharedHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/** Matching of Vosk output against the wake phrase (pure Kotlin, unit-tested). */
object WakePhrase {
    private val nameRx = Regex("\\b(sin|shin|sean) chan\\b")
    private val greetRx = Regex("\\b(hi|hey|hello|high) (sin|shin|sean) chan\\b")

    fun matches(text: String, strict: Boolean): Boolean {
        val t = text.lowercase().replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim()
        return if (strict) greetRx.containsMatchIn(t) else nameRx.containsMatchIn(t)
    }
}

/** Shared, in-process link between the UI and the wake-word service. */
object WakeBus {
    enum class State { OFF, PREPARING, LISTENING, PAUSED, ERROR }

    private val _state = MutableStateFlow(State.OFF)
    val state: StateFlow<State> = _state.asStateFlow()
    private val _detail = MutableStateFlow("")
    val detail: StateFlow<String> = _detail.asStateFlow()

    private val _detections = MutableSharedFlow<Long>(extraBufferCapacity = 4)
    val detections: SharedFlow<Long> = _detections.asSharedFlow()

    /** UI asks the service to release the microphone while SpeechRecognizer is in use. */
    private val _pauseRequested = MutableStateFlow(false)
    val pauseRequested: StateFlow<Boolean> = _pauseRequested.asStateFlow()

    @Volatile var appInForeground: Boolean = false
    @Volatile var strictMatching: Boolean = false

    fun pause() { _pauseRequested.value = true }
    fun resume() { _pauseRequested.value = false }

    internal fun setState(s: State, detail: String = "") { _state.value = s; _detail.value = detail }
    internal fun detected() { _detections.tryEmit(System.currentTimeMillis()) }
}

/**
 * Offline Vosk speech model (vosk-model-small-en-in-0.4, Indian English, ~36 MB, Apache-2.0).
 * Bundled into the APK at build time when available; otherwise downloaded once on first use.
 */
object VoskModelManager {
    const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip"
    private const val ASSET_DIR = "model-en-in"

    fun modelDir(context: Context) = File(context.filesDir, "vosk-model-en-in")
    private fun marker(context: Context) = File(modelDir(context), ".ready")
    fun isReady(context: Context) = marker(context).exists()

    fun hasBundledModel(context: Context): Boolean =
        runCatching { context.assets.list(ASSET_DIR)?.isNotEmpty() == true }.getOrDefault(false)

    suspend fun prepare(context: Context, onProgress: (String) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = modelDir(context)
        if (isReady(context)) return@withContext dir
        dir.deleteRecursively(); dir.mkdirs()
        if (hasBundledModel(context)) {
            onProgress("Unpacking on-device wake-word model…")
            copyAssetDir(context, ASSET_DIR, dir)
        } else {
            onProgress("Downloading wake-word model (about 36 MB, one time)…")
            download(dir, onProgress)
        }
        if (!File(dir, "conf").exists() && !File(dir, "am").exists()) {
            dir.deleteRecursively()
            throw IllegalStateException("Wake-word model files are incomplete")
        }
        marker(context).writeText("ok")
        dir
    }

    private fun copyAssetDir(context: Context, assetPath: String, target: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input -> FileOutputStream(target).use { input.copyTo(it) } }
        } else {
            target.mkdirs()
            children.forEach { copyAssetDir(context, "$assetPath/$it", File(target, it)) }
        }
    }

    private fun download(target: File, onProgress: (String) -> Unit) {
        val req = Request.Builder().url(MODEL_URL).build()
        sharedHttp.newBuilder().readTimeout(java.time.Duration.ofMinutes(2)).callTimeout(java.time.Duration.ofMinutes(15))
            .build().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("Model download failed (HTTP ${resp.code})")
                val body = resp.body ?: throw IllegalStateException("Empty model download")
                val total = body.contentLength()
                var read = 0L
                var lastPct = -1
                val counting = object : java.io.FilterInputStream(body.byteStream()) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val n = super.read(b, off, len)
                        if (n > 0 && total > 0) {
                            read += n
                            val pct = (read * 100 / total).toInt()
                            if (pct != lastPct && pct % 5 == 0) { lastPct = pct; onProgress("Downloading wake-word model… $pct%") }
                        }
                        return n
                    }
                }
                ZipInputStream(counting).use { zip ->
                    var entry = zip.nextEntry
                    val root = target.canonicalPath
                    while (entry != null) {
                        // strip the top-level folder "vosk-model-small-en-in-0.4/"
                        val rel = entry.name.substringAfter('/', "")
                        if (rel.isNotEmpty()) {
                            val out = File(target, rel)
                            if (!out.canonicalPath.startsWith(root)) throw SecurityException("Bad zip entry")
                            if (entry.isDirectory) out.mkdirs() else {
                                out.parentFile?.mkdirs()
                                FileOutputStream(out).use { zip.copyTo(it) }
                            }
                        }
                        entry = zip.nextEntry
                    }
                }
            }
    }
}

/**
 * Foreground service that listens ON-DEVICE for "Hi Sinchan".
 *
 * Why a service + Vosk: Android's SpeechRecognizer cannot run continuously as a custom
 * wake-word detector (it stops after each utterance, beeps, and usually sends audio to a
 * server). Vosk runs fully offline with a tiny grammar, so no audio leaves the tablet and
 * nothing is recorded or stored. A persistent notification shows whenever it is listening.
 */
class WakeWordService : LifecycleService() {

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null
    private var running = false
    private var cooldownUntil = 0L
    private var setupJob: Job? = null
    private val json = Json { ignoreUnknownKeys = true }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf(); return START_NOT_STICKY
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            WakeBus.setState(WakeBus.State.ERROR, "Microphone permission is needed for the wake word.")
            stopSelf(); return START_NOT_STICKY
        }
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification("Listening on-device for “Hi Sinchan”"), type)
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed", t)
            WakeBus.setState(WakeBus.State.ERROR, "Android didn't allow background listening. Open the app and try again.")
            stopSelf(); return START_NOT_STICKY
        }
        if (setupJob == null) setupJob = lifecycleScope.launch { setup() }
        return START_STICKY
    }

    private suspend fun setup() {
        WakeBus.setState(WakeBus.State.PREPARING, "Preparing wake word…")
        try {
            val dir = VoskModelManager.prepare(this) { WakeBus.setState(WakeBus.State.PREPARING, it) }
            withContext(Dispatchers.IO) {
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                val m = Model(dir.absolutePath)
                model = m
                recognizer = Recognizer(m, SAMPLE_RATE, GRAMMAR)
            }
            speechService = SpeechService(recognizer, SAMPLE_RATE)
            lifecycleScope.launch {
                WakeBus.pauseRequested.collect { paused -> if (paused) pauseListening() else resumeListening() }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Wake word setup failed", t)
            WakeBus.setState(WakeBus.State.ERROR, "Wake word unavailable: ${t.message ?: "setup failed"}")
            stopSelf()
        }
    }

    private fun resumeListening() {
        val s = speechService ?: return
        if (running) return
        runCatching { recognizer?.reset() }
        running = s.startListening(listener)
        WakeBus.setState(if (running) WakeBus.State.LISTENING else WakeBus.State.ERROR,
            if (running) "Listening on-device for “Hi Sinchan”" else "Couldn't open the microphone")
        updateNotification(if (running) "Listening on-device for “Hi Sinchan”" else "Microphone busy")
    }

    private fun pauseListening() {
        if (running) runCatching { speechService?.stop() }
        running = false
        WakeBus.setState(WakeBus.State.PAUSED, "Paused while Sinchan is talking with you")
        updateNotification("Paused while you talk with Sinchan")
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) = handleHypothesis(hypothesis, "partial")
        override fun onResult(hypothesis: String?) = handleHypothesis(hypothesis, "text")
        override fun onFinalResult(hypothesis: String?) = handleHypothesis(hypothesis, "text")
        override fun onError(exception: Exception?) {
            Log.e(TAG, "Vosk error", exception)
            running = false
            WakeBus.setState(WakeBus.State.ERROR, "Wake word microphone error")
            lifecycleScope.launch { delay(3000); if (!WakeBus.pauseRequested.value) resumeListening() }
        }
        override fun onTimeout() {
            running = false
            if (!WakeBus.pauseRequested.value) resumeListening()
        }
    }

    private fun handleHypothesis(hypothesis: String?, field: String) {
        if (hypothesis.isNullOrBlank()) return
        val text = runCatching { json.parseToJsonElement(hypothesis).jsonObject[field]?.jsonPrimitive?.contentOrNull }
            .getOrNull().orEmpty()
        if (text.isBlank() || !WakePhrase.matches(text, WakeBus.strictMatching)) return
        val now = System.currentTimeMillis()
        if (now < cooldownUntil) return
        cooldownUntil = now + 2500
        Log.i(TAG, "Wake phrase detected: $text")
        // Release the mic right away so SpeechRecognizer can take over.
        runCatching { speechService?.stop() }
        running = false
        runCatching { recognizer?.reset() }
        WakeBus.pause()
        WakeBus.detected()
        if (!WakeBus.appInForeground) {
            showTapToTalk()
            lifecycleScope.launch { delay(4000); WakeBus.resume() }
        }
    }

    private fun showTapToTalk() {
        val nm = getSystemService(NotificationManager::class.java)
        val n = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Sinchan heard you!")
            .setContentText("Tap to talk with Sinchan")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setTimeoutAfter(20_000)
            .setContentIntent(openAppIntent(autoListen = true))
            .build()
        runCatching { nm.notify(ALERT_ID, n) }
    }

    private fun openAppIntent(autoListen: Boolean): PendingIntent {
        val i = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_AUTO_LISTEN, autoListen)
        }
        return PendingIntent.getActivity(this, if (autoListen) 2 else 1, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun buildNotification(text: String): Notification {
        val stop = PendingIntent.getService(this, 3, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_LISTEN)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Sinchan wake word is on")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent(false))
            .addAction(0, "Turn off", stop)
            .build()
    }

    private fun updateNotification(text: String) {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text)) }
    }

    override fun onDestroy() {
        runCatching { speechService?.stop(); speechService?.shutdown() }
        runCatching { recognizer?.close() }
        runCatching { model?.close() }
        speechService = null; recognizer = null; model = null; running = false
        if (WakeBus.state.value != WakeBus.State.ERROR) WakeBus.setState(WakeBus.State.OFF)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WakeWord"
        private const val SAMPLE_RATE = 16000f
        private const val NOTIF_ID = 42
        private const val ALERT_ID = 43
        const val CHANNEL_LISTEN = "wake_listen"
        const val CHANNEL_ALERT = "wake_alert"
        const val ACTION_STOP = "com.abhijit.hisinchan.STOP_WAKE"

        /**
         * Small closed grammar: Vosk only chooses between these phrases or "[unk]" (anything else),
         * which keeps false triggers low. "Sinchan" isn't an English dictionary word, so it is
         * spelled as sound-alike words the model knows ("sin chan", "shin chan").
         */
        private const val GRAMMAR = """["hi sin chan", "hey sin chan", "hello sin chan", "hi shin chan", "hey shin chan",
            "hello shin chan", "high sin chan", "sin chan", "shin chan", "hi sean chan", "[unk]"]"""

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_LISTEN, context.getString(R.string.wake_channel_name), NotificationManager.IMPORTANCE_LOW)
                    .apply { description = context.getString(R.string.wake_channel_desc); setShowBadge(false) }
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ALERT, "Wake word alerts", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Tap-to-talk alert when Sinchan hears you while the app is in the background." }
            )
        }

        fun start(context: Context) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java)) }
                .onFailure { Log.e(TAG, "Couldn't start wake service", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, WakeWordService::class.java)) }
        }
    }
}
