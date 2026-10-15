# Hi Sinchan — personal AI companion for Android tablets

Sinchan is an original, animated "sprout buddy" character who lives on your tablet. Say **"Hi Sinchan"**, ask anything, and Sinchan answers out loud with matching facial expressions. Tap Sinchan to get a reaction. Ask Sinchan to **remember** things, and those memories are still there after you restart the app.

Native Android: Kotlin, Jetpack Compose, Room, Android SpeechRecognizer and TextToSpeech, Vosk (offline wake word), OkHttp. An optional FastAPI backend keeps your AI key off the tablet.

---

## 1. Install the APK

1. Copy `HiSinchan-debug.apk` to the tablet (USB, Google Drive, or email it to yourself).
2. Open it on the tablet. Android will ask to allow **Install unknown apps** for the app you opened it from (Files, Drive or Chrome). Allow it, then tap **Install**.
3. Requirements: Android 8.0 (API 26) or newer. Tested target: Android 15 (API 35). Works in portrait and landscape.
4. Updates: newer debug APKs from this project use the same signing key, so they install over the old version and keep your memories.

> Play Protect may warn that the app is from an unknown developer. That's normal for a personal APK. Choose **Install anyway**.

## 2. First run: connect an AI brain (required)

Open **Settings (gear) → AI provider** and choose one of these:

| Mode | Where the API key lives | Best for |
|---|---|---|
| **Sinchan backend** (recommended) | On your own server, never on the tablet | Safest, and lets you share the APK |
| **Anthropic Claude: direct** | Encrypted on the tablet (Android Keystore) | Quickest personal setup |
| **OpenAI-compatible: direct** | Encrypted on the tablet | OpenAI, OpenRouter, Groq, or a local Ollama / LM Studio on your Wi-Fi |

Press **Save**, then **Test connection**. You should see "✅ Connected".

No API key is built into the APK, and none should be.

### Option A: run the backend (recommended)

`backend/` contains a ~100-line FastAPI server.

```bash
cd backend
cp .env.example .env              # then edit .env
python -m venv .venv && . .venv/bin/activate      # Windows: .venv\Scripts\activate
pip install -r requirements.txt
# generate a token for APP_TOKEN:
python -c "import secrets;print(secrets.token_urlsafe(32))"
set -a; . ./.env; set +a          # Windows PowerShell: set each variable with $env:NAME="value"
uvicorn main:app --host 0.0.0.0 --port 8000
```

* **At home on your PC:** use `http://<your-PC-IP>:8000` as the Backend URL (find the IP with `ipconfig`). The tablet must be on the same Wi-Fi. Allow port 8000 through Windows Firewall.
* **On the internet:** deploy the included `Dockerfile` to Render, Railway, Fly.io, Google Cloud Run or similar. Set the same environment variables in the host's dashboard and use the **https://** URL it gives you.
* In the app, enter the **Backend URL** and the **App token** (your `APP_TOKEN`).

Environment variables:

| Variable | Meaning |
|---|---|
| `APP_TOKEN` | Shared secret the app must send (required) |
| `PROVIDER` | `anthropic` (default) or `openai` |
| `ANTHROPIC_API_KEY`, `ANTHROPIC_MODEL` | Claude key and model (default `claude-sonnet-5-5`) |
| `OPENAI_API_KEY`, `OPENAI_BASE_URL`, `OPENAI_MODEL` | For any OpenAI-compatible service |

Check it's running: `curl http://localhost:8000/health`.

### Option B: personal direct key

Choose *Anthropic Claude: direct* and paste your key from console.anthropic.com. The key is stored with EncryptedSharedPreferences (AES-256, backed by the Android Keystore) and excluded from backups. Use this only on your own tablet. Usage is billed to your account.

### Web search

With **Web search for current info** on, the backend and Anthropic modes let Claude use Anthropic's server-side web search tool when a question needs fresh information. Searches are billed by Anthropic. OpenAI-compatible mode has no web search. In that mode Sinchan says when its information may be out of date.

## 3. Talking to Sinchan

* **Mic button:** tap, speak, then stop talking. Sinchan answers automatically. If you started by voice, Sinchan then listens for a follow-up. Stay silent to end the conversation.
* **Stop button:** interrupts speech or listening at any time.
* **Repeat:** says the last answer again.
* **Keyboard:** text chat fallback.
* **Transcript:** show or hide the conversation panel. **New conversation** clears the temporary context.
* **Language:** Settings → Voice. Choose English (India/US/UK), Hindi or Bengali for both recognition and voice.

### Wake word "Hi Sinchan"

Turn it on in **Settings → Voice & wake word**.

How it works, and why: Android's `SpeechRecognizer` can't run as an always-on custom wake-word listener. It stops after each phrase, often beeps, and usually sends audio to Google. Sinchan instead runs **Vosk**, a fully offline speech recognizer, inside a **foreground service** of type `microphone`. Vosk uses a tiny closed grammar: it can only output "hi sin chan", "hey shin chan" and similar phrases, or "unknown". "Sinchan" isn't a dictionary word, so the app listens for sound-alikes ("sin chan" / "shin chan").

* The audio never leaves the tablet and is never stored.
* A persistent notification and the coloured chip on the home screen show whenever the wake word is listening. The notification has a **Turn off** button.
* When Sinchan hears the phrase, it releases the microphone, greets you, and hands over to the normal speech recognizer for your question.
* **Background:** by default, listening stops when you leave the app, to protect privacy and battery. With **Keep listening in the background** on, the service keeps running. On a detection Sinchan shows a "tap to talk" alert, because Android doesn't let background apps open themselves.
* **Strict wake phrase** requires "Hi / Hey / Hello" before "Sinchan", which reduces accidental triggers.
* **Model:** `vosk-model-small-en-in-0.4` (Indian English, ~36 MB, Apache-2.0). The CI build bundles it into the APK. If a build doesn't include it, the app downloads it once from alphacephei.com the first time you enable the wake word.
* **Companion mode** (keep screen on) suits a tablet on a stand.

Some brands (Xiaomi, Samsung, Realme, Oppo) aggressively kill background services. If background listening stops, open Android Settings → Apps → Hi Sinchan → Battery and set it to **Unrestricted**.

## 4. Memory: only when you ask

| Say | What happens |
|---|---|
| "Sinchan, remember that my favourite colour is blue." | Saved to the on-device Room database. Sinchan confirms **only after the write succeeds**. |
| "Memorise that I prefer short answers." / "Don't forget that…" | Saved |
| "Remember that my favourite colour is green." | Replaces the older "favourite colour" memory instead of keeping both |
| "What do you remember about me?" | Sinchan reads the list aloud, straight from the database |
| "Show me everything you have memorised." | Opens the Memory screen |
| "Forget my favourite colour." | Deletes the best-matching memory. If several match, Sinchan asks which one. |
| "Forget everything" | Opens the Memory screen, where **Clear all** needs a tap and a confirmation |

* Memories are separate from conversation context. Context is temporary and cleared by *New conversation*. Memories persist across restarts and are sent to the AI as "facts the user explicitly asked you to remember".
* Ordinary chat **never** becomes a memory automatically, and the AI model can't save memories itself.
* **Memory screen** (brain icon): view, add, edit, delete, **export to JSON**, or clear all.
* If the AI is offline and you ask about something you saved, Sinchan answers from memory.

Conversation **history** (clock icon) is stored separately on the tablet. You can turn it off or delete it in Settings → Privacy and on the History screen.

## 5. Sinchan's emotions

Happy · Excited · Curious · Thinking · Empathetic · Surprised · Sleepy (after 90 s idle) · Speaking (lip-sync from TTS word callbacks) · Listening (ripples react to your voice level) · Celebrating (confetti).

The AI chooses an emotion for each reply through a hidden `[emotion:…]` tag. Tapping different areas gives different reactions: the face waves, winks, blushes, spins its eyes or nods; the leaf and the hoodie get their own reactions; tapping fast makes Sinchan giggle. Taps never trigger purchases, messages or other sensitive actions. The character is drawn entirely in Compose Canvas, with no bitmaps, so it stays sharp on any screen and is light on battery.

## 6. Privacy summary

* Microphone permission is requested only when you first tap the mic or turn on the wake word. The app explains why before asking.
* The wake word runs on the device. Spoken questions are turned into text by your tablet's speech service, which on most tablets is Google and usually online. No raw audio is ever saved.
* Only the **text** of the conversation, plus your saved memories, goes to the AI provider you configured.
* Data (memories, history, settings, keys) stays on the tablet and is excluded from cloud backup.
* Delete individual memories or conversations, or everything, at any time.

## 7. Troubleshooting

| Problem | Fix |
|---|---|
| "My AI service rejected the key or token" | Re-enter the key or token in Settings → AI provider, then press Save and Test connection |
| "Can't reach the internet or the AI server" | Check Wi-Fi. For a home backend, confirm the PC IP and port and the firewall, and that both devices are on the same network |
| Wake word chip shows an error | Turn the switch off and on. Check the microphone permission. The first run needs ~36 MB of storage (and internet if the model isn't bundled) |
| "I didn't catch that" | Reduce noise, speak closer, check the language setting, update the Google app and Speech Services |
| No speech recognition at all | Install or enable the **Google** app / **Speech Services by Google** |
| Robotic or wrong-language voice | Android Settings → Text-to-speech: install Speech Services by Google and download voices. Pick one in Settings → Voice. Voices labelled "needs internet" stream from Google. |
| App can't install over an older copy | Uninstall the old one first (only needed if it was signed with a different key) |

## 8. Building from source

### Requirements
* JDK 17
* Android SDK with platform 35 (Android Studio Ladybug or newer installs this automatically)
* Internet access to `google()` and `mavenCentral()` for dependencies

### Commands
```bash
./gradlew testDebugUnitTest      # JVM unit tests (memory parser, emotion tags, wake phrase)
./gradlew assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
```
Windows: use `gradlew.bat`. Or open the folder in Android Studio and press ▶.

Optional: bundle the wake-word model so it works offline from first launch.
```bash
curl -L -o model.zip https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip
unzip model.zip && mv vosk-model-small-en-in-0.4 app/src/main/assets/model-en-in
```

### GitHub Actions
`.github/workflows/build.yml` runs the unit tests, downloads and bundles the Vosk model, builds the debug APK, and uploads it as the **HiSinchan-debug-apk** artifact (Actions tab → latest run → Artifacts).

### Signed release APK
1. Create a keystore once, and keep it and its passwords safe. You need them for every future update.
   ```bash
   keytool -genkeypair -v -keystore release.jks -alias sinchan -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Create `keystore.properties` in the project root. It is git-ignored.
   ```properties
   storeFile=release.jks
   storePassword=…
   keyAlias=sinchan
   keyPassword=…
   ```
3. `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk` (minified and signed).
4. For Google Play, use `./gradlew bundleRelease` and upload the `.aab`.

The debug build is signed with `keystore/debug.keystore`, a public, well-known debug key committed so debug updates install cleanly. Never use it for distribution.

## 9. Project layout

```
app/src/main/java/com/abhijit/hisinchan/
├── HiSinchanApp.kt            app container (DB, settings, secrets, engine)
├── MainActivity.kt            navigation, permissions, wake-service lifecycle
├── ai/                        ChatProviders (backend / Anthropic / OpenAI-compatible), ConversationEngine
├── memory/                    explicit-memory command parser + repository
├── data/                      Room entities/DAOs, DataStore settings, encrypted SecretStore
├── voice/                     SpeechInput (STT), SpeechOutput (TTS + lip-sync), WakeWord (Vosk service)
├── avatar/                    Emotion states + SinchanAvatar (Compose Canvas character)
└── ui/                        MainViewModel, theme, screens (Home, Memory, History, Settings, Help)
backend/                       FastAPI proxy server + Dockerfile
```

Sinchan is an AI companion, not a person, and its answers can be wrong. Double-check anything important.
