package com.abhijit.hisinchan.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subject
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abhijit.hisinchan.HiSinchanApp
import com.abhijit.hisinchan.avatar.SinchanAvatar
import com.abhijit.hisinchan.ui.MainViewModel
import com.abhijit.hisinchan.ui.Phase
import com.abhijit.hisinchan.ui.TranscriptLine
import com.abhijit.hisinchan.ui.theme.LocalAmbient
import com.abhijit.hisinchan.voice.WakeBus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(vm: MainViewModel, navigate: (String) -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val wakeState by WakeBus.state.collectAsState()
    val wakeDetail by WakeBus.detail.collectAsState()
    val app = LocalContext.current.applicationContext as HiSinchanApp
    val memories by app.memories.memories.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var typing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }

    val name = settings.userName.ifBlank {
        memories.firstNotNullOfOrNull { Regex("(?i)\\bmy name is ([\\p{L}]+)").find(it.text)?.groupValues?.get(1) } ?: ""
    }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(15_000) } }

    Box(Modifier.fillMaxSize()) {
        AmbientBackground()
        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            val landscape = maxWidth > maxHeight
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 10.dp)) {
                TopBar(name = name, now = now, wakeOn = settings.wakeWordEnabled, wakeState = wakeState, wakeDetail = wakeDetail,
                    micActive = ui.phase == Phase.LISTENING, navigate = navigate)

                val showTranscript = settings.showTranscript && ui.transcript.isNotEmpty()
                if (landscape) {
                    Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        AvatarStage(vm, Modifier.weight(1f).fillMaxHeight())
                        AnimatedVisibility(showTranscript, enter = fadeIn(), exit = fadeOut()) {
                            TranscriptPanel(ui.transcript, Modifier.width(400.dp).fillMaxHeight().padding(vertical = 12.dp))
                        }
                    }
                } else {
                    Column(Modifier.weight(1f).fillMaxWidth()) {
                        AvatarStage(vm, Modifier.weight(1f).fillMaxWidth())
                        AnimatedVisibility(showTranscript, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                            TranscriptPanel(ui.transcript, Modifier.fillMaxWidth().height(260.dp).padding(vertical = 8.dp))
                        }
                    }
                }

                AnimatedVisibility(typing) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = draft, onValueChange = { draft = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Type to Sinchan… (try “remember that my name is Abhijit”)") },
                            maxLines = 4,
                            shape = RoundedCornerShape(20.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { vm.sendText(draft); draft = "" }),
                        )
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton(onClick = { vm.sendText(draft); draft = "" }, enabled = draft.isNotBlank(),
                            modifier = Modifier.size(56.dp)) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                        }
                    }
                }

                ControlBar(
                    phase = ui.phase,
                    level = ui.level,
                    typing = typing,
                    transcriptOn = settings.showTranscript,
                    onMic = vm::onMicButton,
                    onStop = vm::stopAll,
                    onRepeat = vm::repeatLast,
                    onKeyboard = { typing = !typing },
                    onTranscript = { scope.launch { app.settings.update { it.copy(showTranscript = !it.showTranscript) } } },
                    onNewChat = vm::newConversation,
                )
            }
        }

        if (!settings.onboardingDone) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Meet Sinchan 👋") },
                text = {
                    Text(
                        "Sinchan is your AI companion. A few promises before we start:\n\n" +
                            "• The microphone is only used when you tap the mic, or — if you turn it on — for the on-device “Hi Sinchan” wake word. A notification always shows while it listens.\n" +
                            "• Your voice is turned into text by your tablet's speech service; no recordings are saved.\n" +
                            "• Sinchan only memorises things when you say “remember that…”. You can view, edit, export or delete memories anytime.\n" +
                            "• To answer questions, your text is sent to the AI service you configure in Settings → AI provider."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch { app.settings.update { it.copy(onboardingDone = true) } }
                    }) { Text("Let's go!") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        scope.launch { app.settings.update { it.copy(onboardingDone = true) } }
                        navigate("settings")
                    }) { Text("Set up AI first") }
                },
            )
        }
    }
}

@Composable
private fun TopBar(
    name: String, now: Long, wakeOn: Boolean, wakeState: WakeBus.State, wakeDetail: String,
    micActive: Boolean, navigate: (String) -> Unit,
) {
    val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
    val greet = when (hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..21 -> "Good evening"; else -> "Hello, night owl" }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (name.isNotBlank()) "$greet, $name!" else "$greet!", style = MaterialTheme.typography.headlineMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(SimpleDateFormat("EEEE, d MMMM · h:mm a", Locale.getDefault()).format(Date(now)),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PrivacyChip(wakeOn, wakeState, wakeDetail, micActive)
        Spacer(Modifier.width(8.dp))
        QuickAction(Icons.Filled.Psychology, "Memory") { navigate("memory") }
        QuickAction(Icons.Filled.History, "History") { navigate("history") }
        QuickAction(Icons.Filled.Settings, "Settings") { navigate("settings") }
        QuickAction(Icons.Filled.HelpOutline, "Help") { navigate("help") }
    }
}

@Composable
private fun QuickAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(52.dp)) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp))
    }
}

/** Always-visible indicator of what the microphone is doing. */
@Composable
private fun PrivacyChip(wakeOn: Boolean, state: WakeBus.State, detail: String, micActive: Boolean) {
    val pulse by rememberInfiniteTransition(label = "chip").animateFloat(0.4f, 1f,
        infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "p")
    val (color, text) = when {
        micActive -> Color(0xFFE53958) to "Mic on — listening to you"
        wakeOn && state == WakeBus.State.LISTENING -> Color(0xFF2FA866) to "Wake word on (on-device)"
        wakeOn && state == WakeBus.State.PREPARING -> Color(0xFFF2A93B) to detail.ifBlank { "Preparing wake word…" }
        wakeOn && state == WakeBus.State.PAUSED -> Color(0xFFF2A93B) to "Wake word paused"
        wakeOn && state == WakeBus.State.ERROR -> Color(0xFFE53958) to detail.ifBlank { "Wake word error" }
        else -> Color(0xFF9A93AE) to "Mic off"
    }
    Surface(shape = RoundedCornerShape(50), color = LocalAmbient.current.glass) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).scale(if (micActive || state == WakeBus.State.LISTENING) pulse else 1f)
                .clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 260.dp))
        }
    }
}

@Composable
private fun AvatarStage(vm: MainViewModel, modifier: Modifier) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight * 0.78f).coerceAtMost(600.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Speech bubble: what you're saying (live) or what Sinchan is saying.
            val bubbleText = when {
                ui.phase == Phase.LISTENING && ui.partial.isNotBlank() -> "“${ui.partial}”"
                ui.phase == Phase.SPEAKING -> ui.bubble
                else -> ""
            }
            Box(Modifier.height(86.dp).widthIn(max = 640.dp), contentAlignment = Alignment.BottomCenter) {
                AnimatedVisibility(bubbleText.isNotBlank(), enter = fadeIn(), exit = fadeOut()) {
                    GlassCard(padding = 14.dp) {
                        Text(bubbleText, style = MaterialTheme.typography.bodyLarge, maxLines = 3,
                            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
            }
            SinchanAvatar(
                emotion = ui.emotion,
                speaking = ui.phase == Phase.SPEAKING,
                wordPulse = ui.wordPulse,
                listeningLevel = ui.level,
                gesture = ui.gesture,
                gestureKey = ui.gestureKey,
                look = ui.look,
                modifier = Modifier.size(side),
                onTap = vm::onAvatarTap,
            )
            StatusPill(ui.phase, ui.status, ui.emotion.label)
        }
    }
}

@Composable
private fun StatusPill(phase: Phase, status: String, mood: String) {
    val color by animateColorAsState(
        when (phase) {
            Phase.LISTENING -> Color(0xFF2FA866)
            Phase.THINKING -> Color(0xFFF2A93B)
            Phase.SPEAKING -> Color(0xFF7B5CFA)
            Phase.IDLE -> Color(0xFF9A93AE)
        }, label = "pill"
    )
    Surface(shape = RoundedCornerShape(50), color = LocalAmbient.current.glass) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot(color, 12.dp)
            Spacer(Modifier.width(10.dp))
            Text(status, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 480.dp))
            Spacer(Modifier.width(10.dp))
            Text("· $mood", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TranscriptPanel(lines: List<TranscriptLine>, modifier: Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) state.animateScrollToItem(lines.lastIndex) }
    GlassCard(modifier, padding = 12.dp) {
        Text("Conversation", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp))
        LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            items(lines, key = { it.id }) { line ->
                val bg = when {
                    line.isError -> MaterialTheme.colorScheme.errorContainer
                    line.fromUser -> MaterialTheme.colorScheme.primary
                    line.fromMemory -> MaterialTheme.colorScheme.tertiaryContainer
                    else -> MaterialTheme.colorScheme.surface
                }
                val fg = when {
                    line.isError -> MaterialTheme.colorScheme.onErrorContainer
                    line.fromUser -> MaterialTheme.colorScheme.onPrimary
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.fromUser) Arrangement.End else Arrangement.Start) {
                    Column(
                        Modifier.widthIn(max = 340.dp)
                            .clip(RoundedCornerShape(18.dp, 18.dp, if (line.fromUser) 4.dp else 18.dp, if (line.fromUser) 18.dp else 4.dp))
                            .background(bg).padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        if (line.fromMemory) Text("Memory", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = fg)
                        Text(line.text, style = MaterialTheme.typography.bodyMedium, color = fg)
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlBar(
    phase: Phase, level: Float, typing: Boolean, transcriptOn: Boolean,
    onMic: () -> Unit, onStop: () -> Unit, onRepeat: () -> Unit, onKeyboard: () -> Unit,
    onTranscript: () -> Unit, onNewChat: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SmallControl(if (typing) Icons.Filled.Close else Icons.Filled.Keyboard, if (typing) "Close keyboard" else "Type instead", onKeyboard)
        SmallControl(Icons.Filled.Replay, "Repeat last answer", onRepeat)
        Spacer(Modifier.width(18.dp))

        val busy = phase != Phase.IDLE
        val scaleMic by animateFloatAsState(if (phase == Phase.LISTENING) 1f + level * 0.18f else 1f, label = "mic")
        Box(contentAlignment = Alignment.Center) {
            LargeFloatingActionButton(
                onClick = onMic,
                shape = CircleShape,
                containerColor = if (phase == Phase.LISTENING) Color(0xFFE53958) else MaterialTheme.colorScheme.primary,
                contentColor = Color.White,
                modifier = Modifier.scale(scaleMic),
            ) {
                Icon(if (phase == Phase.LISTENING) Icons.Filled.MicOff else Icons.Filled.Mic,
                    contentDescription = if (phase == Phase.LISTENING) "Finish speaking" else "Talk to Sinchan",
                    modifier = Modifier.size(40.dp))
            }
        }
        Spacer(Modifier.width(18.dp))
        FilledIconButton(
            onClick = onStop, enabled = busy, modifier = Modifier.size(56.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.secondary),
        ) { Icon(Icons.Filled.Stop, contentDescription = "Stop / mute Sinchan") }
        SmallControl(if (transcriptOn) Icons.Filled.Subject else Icons.Filled.ChatBubbleOutline,
            if (transcriptOn) "Hide transcript" else "Show transcript", onTranscript)
        SmallControl(Icons.Filled.Refresh, "New conversation", onNewChat)
    }
}

@Composable
private fun SmallControl(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.padding(horizontal = 4.dp).size(56.dp)) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp))
    }
}
