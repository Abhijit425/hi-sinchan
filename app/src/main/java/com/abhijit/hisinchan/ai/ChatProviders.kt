package com.abhijit.hisinchan.ai

import com.abhijit.hisinchan.data.AiProvider
import com.abhijit.hisinchan.data.AppSettings
import com.abhijit.hisinchan.data.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ChatTurn(val role: String, val content: String)

class AiException(val userMessage: String, cause: Throwable? = null) : Exception(userMessage, cause)

interface ChatProvider {
    suspend fun complete(system: String, turns: List<ChatTurn>, webSearch: Boolean): String
}

private val JSON = "application/json".toMediaType()
private val json = Json { ignoreUnknownKeys = true }

val sharedHttp: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(90, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .callTimeout(120, TimeUnit.SECONDS)
    .build()

/** Cancellable OkHttp call: stopping Sinchan cancels the request in flight. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

private object Retry

/** POST JSON with one retry on transient network errors / 5xx / 429. */
private suspend fun postJson(url: String, headers: Map<String, String>, body: String): JsonObject {
    var lastError: AiException? = null
    for (attempt in 0..1) {
        if (attempt > 0) delay(1500)
        val req = try {
            Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }
                .post(body.toRequestBody(JSON)).build()
        } catch (e: IllegalArgumentException) {
            throw AiException("The AI address in settings isn't a valid URL.", e)
        }
        val outcome: Any = try {
            sharedHttp.newCall(req).await().use { resp ->
                val text = withContext(Dispatchers.IO) { resp.body?.string().orEmpty() }
                when {
                    resp.isSuccessful -> runCatching { json.parseToJsonElement(text).jsonObject }
                        .getOrElse { throw AiException("The AI service sent a reply I couldn't read.") }
                    resp.code == 401 || resp.code == 403 ->
                        throw AiException("My AI service rejected the key or token. Please check AI settings.")
                    resp.code == 404 -> throw AiException("The AI address or model name wasn't found. Please check AI settings.")
                    resp.code == 400 -> throw AiException("The AI service didn't accept the request: ${shortErr(text)}")
                    resp.code == 429 || resp.code >= 500 -> {
                        lastError = AiException("The AI service is busy right now (HTTP ${resp.code}). Please try again.")
                        Retry
                    }
                    else -> throw AiException("AI service error (HTTP ${resp.code}).")
                }
            }
        } catch (e: AiException) {
            throw e
        } catch (e: UnknownHostException) {
            throw AiException("I can't reach the internet or the AI server right now. Check Wi-Fi and the address in settings.", e)
        } catch (e: SocketTimeoutException) {
            lastError = AiException("The AI service took too long to answer.", e)
            Retry
        } catch (e: IOException) {
            if (e.message?.contains("Canceled", ignoreCase = true) == true) {
                throw kotlinx.coroutines.CancellationException("cancelled")
            }
            lastError = AiException("Network problem: ${e.message ?: "connection failed"}", e)
            Retry
        }
        if (outcome is JsonObject) return outcome
    }
    throw lastError ?: AiException("Something went wrong talking to the AI.")
}

private fun shortErr(body: String): String = runCatching {
    val o = json.parseToJsonElement(body).jsonObject
    (o["error"]?.let { e ->
        if (e is JsonObject) e["message"]?.jsonPrimitive?.contentOrNull else e.jsonPrimitive.contentOrNull
    } ?: o["detail"]?.toString())?.take(160)
}.getOrNull() ?: body.take(160)

/** Merge consecutive turns from the same role; ensure the list starts with a user turn. */
private fun normalizeTurns(turns: List<ChatTurn>): List<ChatTurn> {
    val out = mutableListOf<ChatTurn>()
    for (t in turns.filter { it.content.isNotBlank() }) {
        if (out.isNotEmpty() && out.last().role == t.role) {
            out[out.lastIndex] = ChatTurn(t.role, out.last().content + "\n" + t.content)
        } else out += t
    }
    while (out.isNotEmpty() && out.first().role != "user") out.removeAt(0)
    return out
}

/** Recommended: your own small server holds the real API keys. */
class BackendProvider(private val baseUrl: String, private val token: String) : ChatProvider {
    override suspend fun complete(system: String, turns: List<ChatTurn>, webSearch: Boolean): String {
        if (baseUrl.isBlank()) throw AiException("No backend address yet. Open Settings → AI provider to set one up.")
        val body = buildJsonObject {
            put("system", system)
            put("web_search", webSearch)
            putJsonArray("messages") {
                normalizeTurns(turns).forEach { t -> addJsonObject { put("role", t.role); put("content", t.content) } }
            }
        }.toString()
        val headers = buildMap { if (token.isNotBlank()) put("Authorization", "Bearer $token") }
        val res = postJson(baseUrl.trimEnd('/') + "/v1/chat", headers, body)
        return res["reply"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw AiException("The backend sent an empty answer.")
    }
}

/** Personal use: calls Anthropic directly with a key stored encrypted on this tablet. */
class AnthropicProvider(private val apiKey: String, private val model: String) : ChatProvider {
    override suspend fun complete(system: String, turns: List<ChatTurn>, webSearch: Boolean): String {
        if (apiKey.isBlank()) throw AiException("No Anthropic API key yet. Add it in Settings → AI provider.")
        val body = buildJsonObject {
            put("model", model.ifBlank { "claude-sonnet-5-5" })
            put("max_tokens", 1024)
            put("system", system)
            putJsonArray("messages") {
                normalizeTurns(turns).forEach { t -> addJsonObject { put("role", t.role); put("content", t.content) } }
            }
            if (webSearch) putJsonArray("tools") {
                addJsonObject {
                    put("type", "web_search_20250305")
                    put("name", "web_search")
                    put("max_uses", 3)
                }
            }
        }.toString()
        val res = postJson(
            "https://api.anthropic.com/v1/messages",
            mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01"),
            body,
        )
        val text = (res["content"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
            .joinToString("") { it["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
            .trim()
        if (text.isBlank()) throw AiException("Claude sent an empty answer.")
        return text
    }
}

/** Personal use: any OpenAI-compatible /chat/completions endpoint (OpenAI, OpenRouter, Groq, Ollama…). */
class OpenAiCompatibleProvider(private val baseUrl: String, private val apiKey: String, private val model: String) : ChatProvider {
    override suspend fun complete(system: String, turns: List<ChatTurn>, webSearch: Boolean): String {
        if (baseUrl.isBlank()) throw AiException("No API base URL yet. Add it in Settings → AI provider.")
        val body = buildJsonObject {
            put("model", model)
            put("temperature", 0.7)
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", system) }
                normalizeTurns(turns).forEach { t -> addJsonObject { put("role", t.role); put("content", t.content) } }
            }
        }.toString()
        val headers = buildMap { if (apiKey.isNotBlank()) put("Authorization", "Bearer $apiKey") }
        val res = postJson(baseUrl.trimEnd('/') + "/chat/completions", headers, body)
        val text = res["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull?.trim()
        if (text.isNullOrBlank()) throw AiException("The AI sent an empty answer.")
        return text
    }
}

object ProviderFactory {
    fun create(settings: AppSettings, secrets: SecretStore): ChatProvider = when (settings.provider) {
        AiProvider.BACKEND -> BackendProvider(settings.backendUrl, secrets.get(SecretStore.BACKEND_TOKEN))
        AiProvider.ANTHROPIC -> AnthropicProvider(secrets.get(SecretStore.ANTHROPIC_KEY), settings.anthropicModel)
        AiProvider.OPENAI_COMPATIBLE -> OpenAiCompatibleProvider(
            settings.openAiBaseUrl, secrets.get(SecretStore.OPENAI_KEY), settings.openAiModel
        )
    }
}

