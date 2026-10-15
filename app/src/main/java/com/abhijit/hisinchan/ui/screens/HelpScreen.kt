package com.abhijit.hisinchan.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.abhijit.hisinchan.BuildConfig

private val tryThese = listOf(
    "“Hi Sinchan” … wait for Sinchan to greet you … “What's the capital of Australia?”",
    "“Remember that my name is Abhijit.”  Later: “What is my name?”",
    "“Memorise that I prefer short answers.”",
    "“What do you remember about me?”  /  “Show me everything you have memorised.”",
    "“Forget my favourite colour.”",
    "Tap Sinchan's face, leaf or hoodie — each reacts differently. Tap fast for a surprise.",
)

private val troubleshooting = listOf(
    "Sinchan doesn't answer" to "Open Settings → AI provider and press “Test connection”. The backend URL, token or API key is usually the cause. Check Wi-Fi.",
    "“Hi Sinchan” isn't detected" to "Make sure the wake word switch is on and the top chip says “Wake word on (on-device)”. Speak clearly, about an arm's length away. Turn off “Strict wake phrase” for easier triggering. The first start needs to unpack or download the small offline model.",
    "Too many accidental wake-ups" to "Turn on “Strict wake phrase” so Sinchan needs “Hi / Hey / Hello” first.",
    "“I didn't catch that” a lot" to "Speech recognition comes from the Google app / Speech Services. Update them, pick the right language in Settings, reduce background noise, or use the keyboard button.",
    "Speech recognition needs internet" to "Download the offline language in Android Settings → System → Languages → Speech → Offline speech recognition (menu names vary by brand).",
    "Robotic voice" to "Install “Speech Services by Google”, then in Android Settings → Accessibility → Text-to-speech download a high-quality voice. Choose it in Settings → Voice.",
    "Wake word stops when the app is closed" to "That's the default to protect privacy and battery. Turn on “Keep listening in the background”. Some brands (Xiaomi, Samsung, Realme…) also need battery optimisation disabled for Hi Sinchan.",
    "Memories" to "Only phrases like “remember that…”, “memorise…”, “don't forget…” save memories. Sinchan confirms only after the database write succeeds. Manage them in the Memory screen (brain icon).",
)

@Composable
fun HelpScreen(onBack: () -> Unit) {
    SubScreen(title = "Help & troubleshooting", onBack = onBack) {
        item { SectionTitle("Things to try") }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                tryThese.forEach { Text("• $it", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 4.dp)) }
            }
        }
        item { SectionTitle("Troubleshooting") }
        troubleshooting.forEach { (q, a) ->
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text(q, style = MaterialTheme.typography.titleMedium)
                    Text(a, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        item { SectionTitle("About") }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Hi Sinchan ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Sinchan is an AI companion, not a person. Answers come from the AI model you configure and can be wrong — double-check important facts.\n\n" +
                        "Built with Kotlin, Jetpack Compose, Room, Android SpeechRecognizer & TextToSpeech, and the Vosk offline speech toolkit (Apache 2.0) for the wake word. " +
                        "The Sinchan character is an original design drawn in code.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
