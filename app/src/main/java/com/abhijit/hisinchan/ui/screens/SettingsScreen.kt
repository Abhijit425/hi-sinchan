package com.abhijit.hisinchan.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abhijit.hisinchan.HiSinchanApp
import com.abhijit.hisinchan.ai.AiException
import com.abhijit.hisinchan.ai.ChatTurn
import com.abhijit.hisinchan.ai.ProviderFactory
import com.abhijit.hisinchan.data.AiProvider
import com.abhijit.hisinchan.data.AppSettings
import com.abhijit.hisinchan.data.SecretStore
import com.abhijit.hisinchan.data.ThemeMode
import com.abhijit.hisinchan.ui.MainViewModel
import com.abhijit.hisinchan.voice.VoskModelManager
import com.abhijit.hisinchan.voice.WakeBus
import kotlinx.coroutines.launch

private val languages = listOf(
    "en-IN" to "English (India)",
    "en-US" to "English (US)",
    "en-GB" to "English (UK)",
    "hi-IN" to "हिन्दी Hindi",
    "bn-IN" to "বাংলা Bengali",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit, requestWakePermissions: ((Boolean) -> Unit) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as HiSinchanApp
    val s by vm.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val wakeState by WakeBus.state.collectAsState()
    val wakeDetail by WakeBus.detail.collectAsState()
    fun update(f: (AppSettings) -> AppSettings) { scope.launch { app.settings.update(f) } }
    fun toast(m: String) = Toast.makeText(context, m, Toast.LENGTH_SHORT).show()

    // Local editable copies of text fields (saved with the Save button).
    var name by remember(s.userName) { mutableStateOf(s.userName) }
    var backendUrl by remember(s.backendUrl) { mutableStateOf(s.backendUrl) }
    var anthropicModel by remember(s.anthropicModel) { mutableStateOf(s.anthropicModel) }
    var openAiBase by remember(s.openAiBaseUrl) { mutableStateOf(s.openAiBaseUrl) }
    var openAiModel by remember(s.openAiModel) { mutableStateOf(s.openAiModel) }
    var backendToken by remember { mutableStateOf(app.secrets.get(SecretStore.BACKEND_TOKEN)) }
    var anthropicKey by remember { mutableStateOf(app.secrets.get(SecretStore.ANTHROPIC_KEY)) }
    var openAiKey by remember { mutableStateOf(app.secrets.get(SecretStore.OPENAI_KEY)) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf("") }

    fun saveAi() {
        app.secrets.set(SecretStore.BACKEND_TOKEN, backendToken)
        app.secrets.set(SecretStore.ANTHROPIC_KEY, anthropicKey)
        app.secrets.set(SecretStore.OPENAI_KEY, openAiKey)
        update { it.copy(backendUrl = backendUrl, anthropicModel = anthropicModel, openAiBaseUrl = openAiBase, openAiModel = openAiModel) }
    }

    val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    SubScreen(title = "Settings", onBack = onBack) {
        // ------------------------------------------------------------- Voice & wake word
        item { SectionTitle("Voice & wake word") }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                ToggleRow(
                    "“Hi Sinchan” wake word",
                    "Listens fully on-device (Vosk). Nothing is recorded or uploaded. A notification shows while it's on.",
                    s.wakeWordEnabled,
                ) { on ->
                    if (on) requestWakePermissions { granted ->
                        if (granted) update { it.copy(wakeWordEnabled = true) } else toast("Microphone permission is required for the wake word")
                    } else update { it.copy(wakeWordEnabled = false) }
                }
                if (s.wakeWordEnabled) {
                    Text("Status: ${wakeDetail.ifBlank { wakeState.name.lowercase() }}", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                }
                Text(
                    if (VoskModelManager.isReady(context)) "Wake-word model: installed on this tablet."
                    else if (VoskModelManager.hasBundledModel(context)) "Wake-word model: bundled in the app (unpacks on first use)."
                    else "Wake-word model: will download once (~36 MB) from alphacephei.com when you turn this on.",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                ToggleRow("Keep listening in the background",
                    "When the app is closed, Sinchan keeps listening and shows a “tap to talk” alert when it hears you. Uses more battery.",
                    s.wakeInBackground) { on -> update { it.copy(wakeInBackground = on) } }
                ToggleRow("Strict wake phrase", "Require “Hi / Hey / Hello” before “Sinchan” — fewer accidental triggers.",
                    s.wakeSensitivityStrict) { on -> update { it.copy(wakeSensitivityStrict = on) } }
                ToggleRow("Hands-free follow-ups", "After answering a spoken question, Sinchan listens again for your follow-up.",
                    s.followUpListening) { on -> update { it.copy(followUpListening = on) } }
                ToggleRow("Speak replies aloud", "Turn off to only show text answers.", s.speakReplies) { on -> update { it.copy(speakReplies = on) } }
            }
        }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Language", style = MaterialTheme.typography.titleMedium)
                Text("Used for both speech recognition and Sinchan's voice.", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    languages.forEach { (tag, label) ->
                        FilterChip(selected = s.language == tag, onClick = { update { it.copy(language = tag, voiceName = "") } },
                            label = { Text(label) })
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                VoicePicker(vm, s) { v -> update { it.copy(voiceName = v) } }
                Text("Speaking speed: ${"%.1f".format(s.speechRate)}×", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
                Slider(value = s.speechRate, onValueChange = { v -> update { it.copy(speechRate = v) } }, valueRange = 0.6f..1.6f, steps = 9)
                Text("Voice pitch: ${"%.1f".format(s.speechPitch)}", style = MaterialTheme.typography.titleMedium)
                Slider(value = s.speechPitch, onValueChange = { v -> update { it.copy(speechPitch = v) } }, valueRange = 0.7f..1.6f, steps = 8)
                OutlinedButton(onClick = { vm.previewVoice() }) { Text("Preview voice") }
                Text("Voices come from your tablet's text-to-speech engine. For the most natural sound install “Speech Services by Google” and download high-quality voices in Android Settings → Text-to-speech. Voices marked “needs internet” stream from Google.",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }

        // ------------------------------------------------------------- Character & display
        item { SectionTitle("Character & display") }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Theme", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    ThemeMode.entries.forEach { m ->
                        FilterChip(selected = s.themeMode == m, onClick = { update { it.copy(themeMode = m) } },
                            label = { Text(m.name.lowercase().replaceFirstChar { c -> c.uppercase() }) })
                    }
                }
                ToggleRow("Show conversation transcript", null, s.showTranscript) { on -> update { it.copy(showTranscript = on) } }
                ToggleRow("Companion mode (keep screen on)", "Handy when the tablet is on a stand or charging.", s.keepScreenOn) { on ->
                    update { it.copy(keepScreenOn = on) }
                }
                ToggleRow("Chatty tap reactions", "Sinchan says something when you tap them.", s.tapComments) { on ->
                    update { it.copy(tapComments = on) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("What should Sinchan call you?") },
                        singleLine = true, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { update { it.copy(userName = name.trim()) }; toast("Saved") }) { Text("Save") }
                }
            }
        }

        // ------------------------------------------------------------- AI provider
        item { SectionTitle("AI provider") }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                AiProvider.entries.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                        .clickable { update { it.copy(provider = p) } }) {
                        RadioButton(selected = s.provider == p, onClick = { update { it.copy(provider = p) } })
                        Text(p.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                when (s.provider) {
                    AiProvider.BACKEND -> {
                        Text("Your own small server keeps the real API key off the tablet (see backend/ in the project and the README).",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(backendUrl, { backendUrl = it }, label = { Text("Backend URL, e.g. https://my-sinchan.onrender.com") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                        if (isInsecureRemote(backendUrl)) Text("⚠ This is http:// on the internet — your messages would travel unencrypted. Use https.",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                        SecretField("App token (APP_TOKEN on the server)", backendToken) { backendToken = it }
                    }
                    AiProvider.ANTHROPIC -> {
                        Text("Personal use only: the key is stored encrypted on this tablet (Android Keystore) and never leaves it except to call Anthropic. Don't share an APK that contains your settings. Usage is billed to your Anthropic account.",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SecretField("Anthropic API key", anthropicKey) { anthropicKey = it }
                        OutlinedTextField(anthropicModel, { anthropicModel = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    AiProvider.OPENAI_COMPATIBLE -> {
                        Text("Works with OpenAI, OpenRouter, Groq, Together, a local Ollama/LM Studio server, etc. Key is stored encrypted on this tablet. Web search isn't available in this mode.",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(openAiBase, { openAiBase = it }, label = { Text("Base URL (…/v1)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (isInsecureRemote(openAiBase)) Text("⚠ http:// on the internet is unencrypted. Use https.",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                        SecretField("API key (leave blank for local servers)", openAiKey) { openAiKey = it }
                        OutlinedTextField(openAiModel, { openAiModel = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }
                ToggleRow("Web search for current info", "Lets Sinchan look things up online (backend / Anthropic modes). May add cost.",
                    s.webSearch) { on -> update { it.copy(webSearch = on) } }
                if (!app.secrets.isAvailable) Text("⚠ Encrypted storage isn't available on this device, keys can't be saved.",
                    color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 10.dp)) {
                    Button(onClick = { saveAi(); toast("AI settings saved") }) { Text("Save") }
                    OutlinedButton(enabled = !testing, onClick = {
                        saveAi()
                        testing = true; testResult = ""
                        scope.launch {
                            val cur = s.copy(backendUrl = backendUrl, anthropicModel = anthropicModel, openAiBaseUrl = openAiBase, openAiModel = openAiModel)
                            testResult = try {
                                val r = ProviderFactory.create(cur, app.secrets)
                                    .complete("Reply in at most eight words.", listOf(ChatTurn("user", "Say hello to Abhijit.")), false)
                                "✅ Connected: $r"
                            } catch (e: AiException) { "❌ ${e.userMessage}" } catch (e: Exception) { "❌ ${e.message}" }
                            testing = false
                        }
                    }) { Text("Test connection") }
                    if (testing) CircularProgressIndicator(Modifier.padding(start = 4.dp))
                }
                if (testResult.isNotBlank()) Text(testResult, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            }
        }

        // ------------------------------------------------------------- Privacy
        item { SectionTitle("Privacy & permissions") }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                ToggleRow("Save conversation history", "Stored only on this tablet. Memories are separate and unaffected.",
                    s.saveHistory) { on -> update { it.copy(saveHistory = on) } }
                Text("Microphone permission: ${if (hasMic) "granted" else "not granted"}", style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 8.dp))
                Text("Sinchan never saves raw audio. Spoken questions are converted to text by your tablet's speech service (Google, usually online). " +
                    "The wake word runs fully offline. Only the text of your conversation is sent to the AI provider you choose.",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                }, modifier = Modifier.padding(top = 8.dp)) { Text("Open Android app permissions") }
            }
        }
        item {
            Text("Hi Sinchan v${com.abhijit.hisinchan.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
        }
    }
}

private fun isInsecureRemote(url: String): Boolean {
    val u = url.trim().lowercase()
    if (!u.startsWith("http://")) return false
    val host = u.removePrefix("http://").substringBefore('/').substringBefore(':')
    return !(host == "localhost" || host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.") ||
        Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(host) || host.endsWith(".local"))
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SecretField(label: String, value: String, onChange: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (show) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = { androidx.compose.material3.TextButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show") } },
    )
}

@Composable
private fun VoicePicker(vm: MainViewModel, s: AppSettings, onPick: (String) -> Unit) {
    val ready by vm.speechOut.ready.collectAsStateWithLifecycle()
    var voices by remember { mutableStateOf(vm.voices(s.language)) }
    LaunchedEffect(ready, s.language) { voices = vm.voices(s.language) }
    var open by remember { mutableStateOf(false) }
    val current = voices.firstOrNull { it.name == s.voiceName }?.label ?: "Default voice for the language"
    Text("Voice", style = MaterialTheme.typography.titleMedium)
    Column {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(current) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Default voice for the language") }, onClick = { onPick(""); open = false })
            voices.forEach { v -> DropdownMenuItem(text = { Text(v.label) }, onClick = { onPick(v.name); open = false }) }
        }
        if (ready && voices.isEmpty()) Text("No voices installed for this language. Install one in Android Settings → Text-to-speech.",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
    }
}
