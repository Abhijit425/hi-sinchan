package com.abhijit.hisinchan.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.speech.SpeechRecognizer
import androidx.compose.ui.geometry.Offset
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.abhijit.hisinchan.HiSinchanApp
import com.abhijit.hisinchan.ai.AiException
import com.abhijit.hisinchan.ai.ChatTurn
import com.abhijit.hisinchan.ai.UiAction
import com.abhijit.hisinchan.avatar.Emotion
import com.abhijit.hisinchan.avatar.Gesture
import com.abhijit.hisinchan.data.AppSettings
import com.abhijit.hisinchan.data.MessageEntity
import com.abhijit.hisinchan.voice.SpeechInput
import com.abhijit.hisinchan.voice.SpeechOutput
import com.abhijit.hisinchan.voice.VoiceOption
import com.abhijit.hisinchan.voice.WakeBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Phase { IDLE, LISTENING, THINKING, SPEAKING }

data class TranscriptLine(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val isError: Boolean = false,
    val fromMemory: Boolean = false,
)

data class UiState(
    val phase: Phase = Phase.IDLE,
    val emotion: Emotion = Emotion.HAPPY,
    val partial: String = "",
    val transcript: List<TranscriptLine> = emptyList(),
    val level: Float = 0f,
    val gesture: Gesture = Gesture.NONE,
    val gestureKey: Int = 0,
    val look: Offset? = null,
    val wordPulse: Int = 0,
    val status: String = "Tap the mic or say “Hi Sinchan”",
    val lastReply: String = "",
    val bubble: String = "",
)

sealed interface UiEvent {
    data object OpenMemory : UiEvent
    data class Toast(val message: String) : UiEvent
    data object NeedMicPermission : UiEvent
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val container = app as HiSinchanApp
    private val engine = container.engine
    private val messageDao = container.database.messageDao()

    val settings: StateFlow<AppSettings> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    private val speechIn = SpeechInput(app)
    val speechOut = SpeechOutput(app)

    /** Temporary conversation context (NOT persistent memory). Cleared with "New chat". */
    private val context = mutableListOf<ChatTurn>()
    private var sessionId = System.currentTimeMillis()
    private var lineIds = 0L

    private var aiJob: Job? = null
    private var idleJob: Job? = null
    private var lookJob: Job? = null
    private var lastInputWasVoice = false
    private var followUp = false
    private var noMatchCount = 0
    private var userCancelledListening = false
    private var pendingAfterPermission: (() -> Unit)? = null

    private val tapTimes = ArrayDeque<Long>()
    private var lastTapLine = -1

    init {
        viewModelScope.launch {
            WakeBus.detections.collect { onWakeWord() }
        }
        viewModelScope.launch {
            settings.collect { WakeBus.strictMatching = it.wakeSensitivityStrict }
        }
        scheduleIdle()
    }

    // ---------------------------------------------------------------- public actions

    fun onMicButton() {
        when (_ui.value.phase) {
            Phase.LISTENING -> { userCancelledListening = false; speechIn.stop() }
            Phase.SPEAKING, Phase.THINKING -> { stopAll(); startListening() }
            Phase.IDLE -> startListening()
        }
    }

    fun stopAll() {
        aiJob?.cancel()
        speechOut.stop()
        userCancelledListening = true
        speechIn.cancel()
        followUp = false
        setPhase(Phase.IDLE)
        _ui.update { it.copy(partial = "", level = 0f, status = "Stopped. Tap the mic when you're ready.") }
        relaxEmotionSoon(0)
    }

    fun repeatLast() {
        val last = _ui.value.lastReply
        if (last.isBlank()) {
            _events.tryEmit(UiEvent.Toast("Nothing to repeat yet"))
            return
        }
        stopAll()
        speak(last, Emotion.HAPPY, allowFollowUp = false)
    }

    fun sendText(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        stopAll()
        lastInputWasVoice = false
        handleUserText(t)
    }

    fun newConversation() {
        stopAll()
        context.clear()
        sessionId = System.currentTimeMillis()
        _ui.update { it.copy(transcript = emptyList(), lastReply = "", bubble = "", status = "New conversation started") }
    }

    fun onMicPermissionResult(granted: Boolean) {
        val action = pendingAfterPermission
        pendingAfterPermission = null
        if (granted) action?.invoke()
        else {
            _ui.update { it.copy(status = "Microphone permission is off. You can still type to Sinchan.") }
            _events.tryEmit(UiEvent.Toast("Without microphone access I can't hear you — you can type instead."))
        }
    }

    fun onAvatarTap(pos: Offset) {
        markInteraction()
        val now = System.currentTimeMillis()
        tapTimes.addLast(now)
        while (tapTimes.isNotEmpty() && now - tapTimes.first() > 4000) tapTimes.removeFirst()

        lookJob?.cancel()
        _ui.update { it.copy(look = pos) }
        lookJob = viewModelScope.launch { delay(1600); _ui.update { it.copy(look = null) } }

        val phase = _ui.value.phase
        val wasSleepy = _ui.value.emotion == Emotion.SLEEPY
        val reaction: TapReaction = when {
            wasSleepy -> TapReaction(Gesture.BOUNCE, Emotion.SURPRISED, "Oh! I'm awake, I'm awake! Hi there!")
            tapTimes.size >= 5 -> TapReaction(Gesture.GIGGLE, Emotion.EXCITED, tickle.random())
            pos.y < -0.55f -> TapReaction(Gesture.WINK, Emotion.HAPPY, leafLines.random())
            pos.y > 0.3f -> TapReaction(Gesture.GIGGLE, Emotion.EXCITED, tummyLines.random())
            else -> {
                var i: Int
                do { i = faceReactions.indices.random() } while (i == lastTapLine && faceReactions.size > 1)
                lastTapLine = i
                faceReactions[i]
            }
        }
        _ui.update { it.copy(gesture = reaction.gesture, gestureKey = it.gestureKey + 1) }
        // Only change mood / talk when Sinchan isn't busy with you.
        if (phase == Phase.IDLE) {
            _ui.update { it.copy(emotion = reaction.emotion) }
            if (settings.value.tapComments) {
                speak(reaction.line, reaction.emotion, allowFollowUp = false, addToTranscript = false)
            } else relaxEmotionSoon()
        }
    }

    // ---------------------------------------------------------------- listening

    fun startListening(isFollowUp: Boolean = false) {
        val ctx = getApplication<Application>()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingAfterPermission = { startListening(isFollowUp) }
            _events.tryEmit(UiEvent.NeedMicPermission)
            return
        }
        if (!speechIn.isAvailable()) {
            _ui.update { it.copy(status = "No speech recognition service found. Install or enable Google app / Speech Services, or type instead.") }
            _events.tryEmit(UiEvent.Toast("Speech recognition isn't available on this tablet"))
            return
        }
        markInteraction()
        speechOut.stop()
        followUp = isFollowUp
        userCancelledListening = false
        lastInputWasVoice = true
        WakeBus.pause()
        setPhase(Phase.LISTENING)
        _ui.update {
            it.copy(emotion = Emotion.LISTENING, partial = "", level = 0f,
                status = if (isFollowUp) "Listening for a follow-up…" else "Listening… go ahead")
        }
        // Give the wake-word engine a moment to release the microphone.
        viewModelScope.launch {
            delay(150)
            if (_ui.value.phase != Phase.LISTENING) return@launch
            speechIn.start(settings.value.language, recognitionListener)
        }
    }

    private val recognitionListener = object : SpeechInput.Listener {
        override fun onLevel(rmsDb: Float) {
            _ui.update { it.copy(level = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)) }
        }

        override fun onPartial(text: String) {
            _ui.update { it.copy(partial = text, emotion = Emotion.CURIOUS) }
        }

        override fun onResult(text: String) {
            noMatchCount = 0
            _ui.update { it.copy(partial = "", level = 0f) }
            handleUserText(text)
        }

        override fun onError(code: Int, message: String) {
            _ui.update { it.copy(partial = "", level = 0f) }
            if (userCancelledListening && code == SpeechRecognizer.ERROR_CLIENT) return
            if (_ui.value.phase != Phase.LISTENING) return
            when (code) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    if (followUp) { // silence after an answer = conversation over
                        followUp = false
                        setPhase(Phase.IDLE)
                        _ui.update { it.copy(status = "Say “Hi Sinchan” or tap the mic to talk again") }
                        relaxEmotionSoon(0)
                        return
                    }
                    noMatchCount++
                    if (noMatchCount <= 1) {
                        speak("Sorry, I didn't catch that. Could you say it again?", Emotion.CURIOUS,
                            allowFollowUp = false, addToTranscript = false) { startListening() }
                    } else {
                        noMatchCount = 0
                        setPhase(Phase.IDLE)
                        _ui.update { it.copy(emotion = Emotion.EMPATHETIC,
                            status = "I couldn't hear clearly. If it's noisy, move closer or use the keyboard.") }
                        relaxEmotionSoon()
                    }
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    speechIn.destroy()
                    viewModelScope.launch { delay(400); startListening(followUp) }
                }
                else -> {
                    setPhase(Phase.IDLE)
                    _ui.update { it.copy(emotion = Emotion.EMPATHETIC, status = message) }
                    addLine(false, message, isError = true)
                    relaxEmotionSoon()
                }
            }
        }
    }

    private fun onWakeWord() {
        val phase = _ui.value.phase
        if (phase == Phase.LISTENING || phase == Phase.THINKING) return
        markInteraction()
        speechOut.stop()
        _ui.update { it.copy(gesture = Gesture.WAVE, gestureKey = it.gestureKey + 1, emotion = Emotion.EXCITED) }
        speak(greetings.random(), Emotion.LISTENING, allowFollowUp = false, addToTranscript = false) {
            startListening()
        }
    }

    /** Called by MainActivity when it was opened from the "Sinchan heard you" notification. */
    fun onOpenedFromWakeNotification() {
        onWakeWord()
    }

    // ---------------------------------------------------------------- thinking / answering

    private fun handleUserText(text: String) {
        addLine(true, text)
        persist("user", text, null)
        setPhase(Phase.THINKING)
        _ui.update { it.copy(emotion = Emotion.THINKING, status = "Thinking…", bubble = "") }
        aiJob?.cancel()
        aiJob = viewModelScope.launch {
            try {
                val reply = engine.respond(text, context.toList(), settings.value)
                if (!reply.fromMemorySystem) {
                    context += ChatTurn("user", text)
                    context += ChatTurn("assistant", reply.text)
                    while (context.size > 40) context.removeAt(0)
                }
                persist("assistant", reply.text, reply.emotion.name)
                if (reply.action == UiAction.OPEN_MEMORY) _events.tryEmit(UiEvent.OpenMemory)
                if (reply.emotion == Emotion.CELEBRATING || reply.emotion == Emotion.EXCITED) {
                    _ui.update { it.copy(gesture = Gesture.BOUNCE, gestureKey = it.gestureKey + 1) }
                }
                speak(reply.text, reply.emotion, allowFollowUp = true, fromMemory = reply.fromMemorySystem)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                showError(e.userMessage)
            } catch (e: Exception) {
                showError("Something went wrong: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun showError(msg: String) {
        addLine(false, msg, isError = true)
        speak(msg, Emotion.EMPATHETIC, allowFollowUp = false, addToTranscript = false)
    }

    // ---------------------------------------------------------------- speaking

    private fun speak(
        text: String,
        emotion: Emotion,
        allowFollowUp: Boolean,
        addToTranscript: Boolean = true,
        fromMemory: Boolean = false,
        then: (() -> Unit)? = null,
    ) {
        if (addToTranscript) {
            addLine(false, text, fromMemory = fromMemory)
            _ui.update { it.copy(lastReply = text) }
        }
        val shownEmotion = if (emotion == Emotion.NEUTRAL) Emotion.SPEAKING else emotion
        _ui.update { it.copy(emotion = shownEmotion, bubble = text, status = "Speaking… tap stop to interrupt") }
        val s = settings.value
        val finish = {
            setPhase(Phase.IDLE)
            _ui.update { it.copy(status = idleStatus()) }
            when {
                then != null -> then()
                allowFollowUp && lastInputWasVoice && s.followUpListening -> startListening(isFollowUp = true)
                else -> relaxEmotionSoon()
            }
        }
        if (!s.speakReplies) {
            setPhase(Phase.IDLE)
            viewModelScope.launch { delay(300); finish() }
            return
        }
        setPhase(Phase.SPEAKING)
        speechOut.speak(text, s.language, s.speechRate, s.speechPitch, s.voiceName, object : SpeechOutput.Callbacks {
            override fun onWord(start: Int, end: Int) {
                _ui.update { it.copy(wordPulse = it.wordPulse + 1) }
            }
            override fun onDone() { finish() }
            override fun onError() {
                _events.tryEmit(UiEvent.Toast("Text-to-speech failed. Check Settings → Voice."))
                finish()
            }
        })
    }

    fun voices(lang: String): List<VoiceOption> = speechOut.voicesFor(lang)

    fun previewVoice() {
        stopAll()
        speak("Hi! I'm Sinchan. This is how I sound.", Emotion.HAPPY, allowFollowUp = false, addToTranscript = false)
    }

    // ---------------------------------------------------------------- helpers

    private fun setPhase(p: Phase) {
        _ui.update { it.copy(phase = p) }
        if (p == Phase.IDLE) WakeBus.resume() else WakeBus.pause()
        if (p == Phase.IDLE) scheduleIdle()
    }

    private fun idleStatus(): String =
        if (settings.value.wakeWordEnabled) "Say “Hi Sinchan” or tap the mic" else "Tap the mic or type to talk"

    private fun relaxEmotionSoon(ms: Long = 2500) {
        viewModelScope.launch {
            delay(ms)
            if (_ui.value.phase == Phase.IDLE && _ui.value.emotion != Emotion.SLEEPY) {
                _ui.update { it.copy(emotion = Emotion.HAPPY, status = idleStatus()) }
            }
        }
    }

    private fun markInteraction() = scheduleIdle()

    private fun scheduleIdle() {
        idleJob?.cancel()
        idleJob = viewModelScope.launch {
            delay(90_000)
            if (_ui.value.phase == Phase.IDLE) _ui.update { it.copy(emotion = Emotion.SLEEPY, bubble = "") }
        }
    }

    private fun addLine(fromUser: Boolean, text: String, isError: Boolean = false, fromMemory: Boolean = false) {
        _ui.update { it.copy(transcript = (it.transcript + TranscriptLine(++lineIds, fromUser, text, isError, fromMemory)).takeLast(200)) }
    }

    private fun persist(role: String, text: String, emotion: String?) {
        if (!settings.value.saveHistory) return
        viewModelScope.launch {
            runCatching { messageDao.insert(MessageEntity(sessionId = sessionId, role = role, text = text, emotion = emotion)) }
        }
    }

    override fun onCleared() {
        speechIn.destroy()
        speechOut.shutdown()
        super.onCleared()
    }

    private data class TapReaction(val gesture: Gesture, val emotion: Emotion, val line: String)

    private val faceReactions = listOf(
        TapReaction(Gesture.WAVE, Emotion.HAPPY, "Hi hi! Did you need something?"),
        TapReaction(Gesture.GIGGLE, Emotion.HAPPY, "Hehe! That's my nose!"),
        TapReaction(Gesture.WINK, Emotion.HAPPY, "You found me!"),
        TapReaction(Gesture.SPIN_EYES, Emotion.SURPRISED, "Whoa, everything's spinning!"),
        TapReaction(Gesture.SHY, Emotion.HAPPY, "Aww, you're making me blush."),
        TapReaction(Gesture.NOD, Emotion.CURIOUS, "Yes? I'm all ears. Well, tiny ears."),
        TapReaction(Gesture.BOUNCE, Emotion.EXCITED, "Boing! Ready when you are!"),
        TapReaction(Gesture.WAVE, Emotion.CURIOUS, "What shall we learn today?"),
    )
    private val leafLines = listOf(
        "Careful, that's my thinking leaf!",
        "My sprout says hello!",
        "Hehe, the leaf tickles.",
    )
    private val tummyLines = listOf(
        "Hehe, that tickles!",
        "Hey, no tickling the hoodie!",
        "That's my favourite hoodie, you know.",
    )
    private val tickle = listOf(
        "Okay okay, I'm awake! Hehe!",
        "So many taps! Are we playing a game?",
        "Haha, stop, that tickles!",
    )
    private val greetings = listOf(
        "Hey! I'm here. What would you like to know?",
        "Hi! I'm listening.",
        "Yes? What's up?",
        "Hello! How can I help?",
    )
}
