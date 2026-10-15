package com.abhijit.hisinchan.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "sinchan_settings")

enum class AiProvider(val label: String) {
    BACKEND("Sinchan backend (recommended)"),
    ANTHROPIC("Anthropic Claude – direct, personal use"),
    OPENAI_COMPATIBLE("OpenAI-compatible – direct, personal use"),
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    // Voice & wake word
    val wakeWordEnabled: Boolean = false,
    val wakeInBackground: Boolean = false,
    val followUpListening: Boolean = true,
    val speakReplies: Boolean = true,
    val speechRate: Float = 1.0f,
    val speechPitch: Float = 1.1f,
    val voiceName: String = "",
    val language: String = "en-IN",
    val wakeSensitivityStrict: Boolean = false,
    // Character & UI
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val showTranscript: Boolean = true,
    val keepScreenOn: Boolean = false,
    val tapComments: Boolean = true,
    val userName: String = "",
    // AI
    val provider: AiProvider = AiProvider.BACKEND,
    val backendUrl: String = "",
    val anthropicModel: String = "claude-sonnet-5-5",
    val openAiBaseUrl: String = "https://api.openai.com/v1",
    val openAiModel: String = "gpt-4o-mini",
    val webSearch: Boolean = true,
    // Privacy
    val saveHistory: Boolean = true,
    val onboardingDone: Boolean = false,
)

class SettingsRepository(private val context: Context) {

    private object K {
        val wake = booleanPreferencesKey("wake")
        val wakeBg = booleanPreferencesKey("wake_bg")
        val followUp = booleanPreferencesKey("follow_up")
        val speak = booleanPreferencesKey("speak")
        val rate = floatPreferencesKey("rate")
        val pitch = floatPreferencesKey("pitch")
        val voice = stringPreferencesKey("voice")
        val lang = stringPreferencesKey("lang")
        val strict = booleanPreferencesKey("strict")
        val theme = stringPreferencesKey("theme")
        val transcript = booleanPreferencesKey("transcript")
        val screenOn = booleanPreferencesKey("screen_on")
        val tapComments = booleanPreferencesKey("tap_comments")
        val userName = stringPreferencesKey("user_name")
        val provider = stringPreferencesKey("provider")
        val backendUrl = stringPreferencesKey("backend_url")
        val anthropicModel = stringPreferencesKey("anthropic_model")
        val openAiBase = stringPreferencesKey("openai_base")
        val openAiModel = stringPreferencesKey("openai_model")
        val webSearch = booleanPreferencesKey("web_search")
        val saveHistory = booleanPreferencesKey("save_history")
        val onboarding = booleanPreferencesKey("onboarding")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        val d = AppSettings()
        AppSettings(
            wakeWordEnabled = p[K.wake] ?: d.wakeWordEnabled,
            wakeInBackground = p[K.wakeBg] ?: d.wakeInBackground,
            followUpListening = p[K.followUp] ?: d.followUpListening,
            speakReplies = p[K.speak] ?: d.speakReplies,
            speechRate = p[K.rate] ?: d.speechRate,
            speechPitch = p[K.pitch] ?: d.speechPitch,
            voiceName = p[K.voice] ?: d.voiceName,
            language = p[K.lang] ?: d.language,
            wakeSensitivityStrict = p[K.strict] ?: d.wakeSensitivityStrict,
            themeMode = runCatching { ThemeMode.valueOf(p[K.theme] ?: "") }.getOrDefault(d.themeMode),
            showTranscript = p[K.transcript] ?: d.showTranscript,
            keepScreenOn = p[K.screenOn] ?: d.keepScreenOn,
            tapComments = p[K.tapComments] ?: d.tapComments,
            userName = p[K.userName] ?: d.userName,
            provider = runCatching { AiProvider.valueOf(p[K.provider] ?: "") }.getOrDefault(d.provider),
            backendUrl = p[K.backendUrl] ?: d.backendUrl,
            anthropicModel = p[K.anthropicModel] ?: d.anthropicModel,
            openAiBaseUrl = p[K.openAiBase] ?: d.openAiBaseUrl,
            openAiModel = p[K.openAiModel] ?: d.openAiModel,
            webSearch = p[K.webSearch] ?: d.webSearch,
            saveHistory = p[K.saveHistory] ?: d.saveHistory,
            onboardingDone = p[K.onboarding] ?: d.onboardingDone,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val old = AppSettings() // defaults; we always write every field
            val s = transform(
                AppSettings(
                    wakeWordEnabled = p[K.wake] ?: old.wakeWordEnabled,
                    wakeInBackground = p[K.wakeBg] ?: old.wakeInBackground,
                    followUpListening = p[K.followUp] ?: old.followUpListening,
                    speakReplies = p[K.speak] ?: old.speakReplies,
                    speechRate = p[K.rate] ?: old.speechRate,
                    speechPitch = p[K.pitch] ?: old.speechPitch,
                    voiceName = p[K.voice] ?: old.voiceName,
                    language = p[K.lang] ?: old.language,
                    wakeSensitivityStrict = p[K.strict] ?: old.wakeSensitivityStrict,
                    themeMode = runCatching { ThemeMode.valueOf(p[K.theme] ?: "") }.getOrDefault(old.themeMode),
                    showTranscript = p[K.transcript] ?: old.showTranscript,
                    keepScreenOn = p[K.screenOn] ?: old.keepScreenOn,
                    tapComments = p[K.tapComments] ?: old.tapComments,
                    userName = p[K.userName] ?: old.userName,
                    provider = runCatching { AiProvider.valueOf(p[K.provider] ?: "") }.getOrDefault(old.provider),
                    backendUrl = p[K.backendUrl] ?: old.backendUrl,
                    anthropicModel = p[K.anthropicModel] ?: old.anthropicModel,
                    openAiBaseUrl = p[K.openAiBase] ?: old.openAiBaseUrl,
                    openAiModel = p[K.openAiModel] ?: old.openAiModel,
                    webSearch = p[K.webSearch] ?: old.webSearch,
                    saveHistory = p[K.saveHistory] ?: old.saveHistory,
                    onboardingDone = p[K.onboarding] ?: old.onboardingDone,
                )
            )
            p[K.wake] = s.wakeWordEnabled
            p[K.wakeBg] = s.wakeInBackground
            p[K.followUp] = s.followUpListening
            p[K.speak] = s.speakReplies
            p[K.rate] = s.speechRate
            p[K.pitch] = s.speechPitch
            p[K.voice] = s.voiceName
            p[K.lang] = s.language
            p[K.strict] = s.wakeSensitivityStrict
            p[K.theme] = s.themeMode.name
            p[K.transcript] = s.showTranscript
            p[K.screenOn] = s.keepScreenOn
            p[K.tapComments] = s.tapComments
            p[K.userName] = s.userName
            p[K.provider] = s.provider.name
            p[K.backendUrl] = s.backendUrl.trim()
            p[K.anthropicModel] = s.anthropicModel.trim()
            p[K.openAiBase] = s.openAiBaseUrl.trim()
            p[K.openAiModel] = s.openAiModel.trim()
            p[K.webSearch] = s.webSearch
            p[K.saveHistory] = s.saveHistory
            p[K.onboarding] = s.onboardingDone
        }
    }
}

/**
 * Credentials (backend token, personal API keys) live ONLY in Android-Keystore-backed
 * EncryptedSharedPreferences on this device. They are never compiled into the APK
 * and are excluded from cloud backup.
 */
class SecretStore(context: Context) {
    private val prefs: SharedPreferences? = try {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context,
            "sinchan_secrets",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (t: Throwable) {
        Log.e("SecretStore", "Encrypted storage unavailable", t)
        null
    }

    val isAvailable: Boolean get() = prefs != null

    fun get(name: String): String = prefs?.getString(name, "") ?: ""
    fun set(name: String, value: String) {
        prefs?.edit()?.putString(name, value.trim())?.apply()
    }

    companion object {
        const val BACKEND_TOKEN = "backend_token"
        const val ANTHROPIC_KEY = "anthropic_key"
        const val OPENAI_KEY = "openai_key"
    }
}
