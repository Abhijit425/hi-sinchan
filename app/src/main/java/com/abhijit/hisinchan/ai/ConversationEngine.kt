package com.abhijit.hisinchan.ai

import com.abhijit.hisinchan.avatar.Emotion
import com.abhijit.hisinchan.data.AppSettings
import com.abhijit.hisinchan.data.MemoryEntity
import com.abhijit.hisinchan.data.SecretStore
import com.abhijit.hisinchan.memory.MemoryCommand
import com.abhijit.hisinchan.memory.MemoryCommandParser
import com.abhijit.hisinchan.memory.MemoryRepository
import com.abhijit.hisinchan.memory.SaveResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class UiAction { OPEN_MEMORY }

data class EngineReply(
    val text: String,
    val emotion: Emotion,
    val action: UiAction? = null,
    /** True when the reply came from the local memory system rather than the AI model. */
    val fromMemorySystem: Boolean = false,
)

/**
 * Turns one user utterance into Sinchan's reply.
 * 1. Explicit memory instructions are handled locally and deterministically (Room).
 * 2. Everything else goes to the configured AI provider with saved memories + recent context.
 */
class ConversationEngine(
    private val memories: MemoryRepository,
    private val secrets: SecretStore,
) {
    suspend fun respond(userText: String, context: List<ChatTurn>, settings: AppSettings): EngineReply {
        MemoryCommandParser.parse(userText)?.let { return handleMemory(it) }

        val saved = memories.all()
        val system = buildSystemPrompt(saved, settings)
        val provider = ProviderFactory.create(settings, secrets)
        val turns = (context.takeLast(MAX_CONTEXT_TURNS) + ChatTurn("user", userText))
        return try {
            val raw = provider.complete(system, turns, settings.webSearch)
            val (emotion, clean) = extractEmotion(raw)
            EngineReply(cleanForSpeech(clean), emotion ?: guessEmotion(userText))
        } catch (e: AiException) {
            // Offline fallback: if the question is about a saved memory, answer from it.
            val hits = memories.relevantFor(userText)
            if (hits.isNotEmpty() && looksLikeQuestion(userText)) {
                val facts = hits.joinToString(". ") { MemoryCommandParser.toSecondPerson(it.text) }
                EngineReply(
                    "I can't reach my AI brain right now, but here's what I remember: $facts.",
                    Emotion.CURIOUS, fromMemorySystem = true
                )
            } else throw e
        }
    }

    private suspend fun handleMemory(cmd: MemoryCommand): EngineReply = when (cmd) {
        is MemoryCommand.Remember -> when (val r = memories.remember(cmd.fact)) {
            is SaveResult.Saved -> EngineReply(
                "Got it! I'll remember that ${MemoryCommandParser.toSecondPerson(r.memory.text)}.",
                Emotion.HAPPY, fromMemorySystem = true
            )
            is SaveResult.Duplicate -> EngineReply(
                "I already remember that ${MemoryCommandParser.toSecondPerson(r.existing.text)}.",
                Emotion.HAPPY, fromMemorySystem = true
            )
            is SaveResult.Failed -> EngineReply(
                "Oh no, I couldn't save that to my memory just now, so I haven't remembered it. Could you try again?",
                Emotion.EMPATHETIC, fromMemorySystem = true
            )
        }

        is MemoryCommand.Forget -> {
            val matches = memories.findBestMatch(cmd.query)
            when {
                matches.isEmpty() -> EngineReply(
                    "I don't have a memory about ${MemoryCommandParser.toSecondPerson(cmd.query)}, so there's nothing to forget.",
                    Emotion.CURIOUS, fromMemorySystem = true
                )
                matches.size > 1 -> EngineReply(
                    "I have a few memories that match: " +
                        matches.joinToString("; ") { MemoryCommandParser.toSecondPerson(it.text) } +
                        ". Which one should I forget? You can also delete it in the memory screen.",
                    Emotion.CURIOUS, UiAction.OPEN_MEMORY, fromMemorySystem = true
                )
                else -> {
                    val m = matches.first()
                    if (memories.delete(m.id)) EngineReply(
                        "Done. I've forgotten that ${MemoryCommandParser.toSecondPerson(m.text)}.",
                        Emotion.HAPPY, fromMemorySystem = true
                    ) else EngineReply(
                        "Hmm, I couldn't delete that memory. Please try again from the memory screen.",
                        Emotion.EMPATHETIC, UiAction.OPEN_MEMORY, fromMemorySystem = true
                    )
                }
            }
        }

        MemoryCommand.ForgetEverything -> EngineReply(
            "To keep your memories safe, clearing everything needs a tap. I've opened the memory screen. Use the Clear all button there.",
            Emotion.CURIOUS, UiAction.OPEN_MEMORY, fromMemorySystem = true
        )

        MemoryCommand.ListAll -> {
            val all = memories.all()
            if (all.isEmpty()) EngineReply(
                "I haven't memorised anything yet. Just say, remember that, followed by anything you'd like me to keep.",
                Emotion.CURIOUS, fromMemorySystem = true
            ) else EngineReply(
                "Here's what you've asked me to remember: " +
                    all.joinToString(". ") { MemoryCommandParser.toSecondPerson(it.text) } + ".",
                Emotion.HAPPY, fromMemorySystem = true
            )
        }

        MemoryCommand.ShowScreen -> {
            val n = memories.all().size
            EngineReply(
                if (n == 0) "Here's my memory. It's empty for now." else "Here's everything I've memorised. $n item${if (n == 1) "" else "s"}.",
                Emotion.HAPPY, UiAction.OPEN_MEMORY, fromMemorySystem = true
            )
        }
    }

    private fun buildSystemPrompt(saved: List<MemoryEntity>, s: AppSettings): String {
        val now = SimpleDateFormat("EEEE d MMMM yyyy, h:mm a", Locale.ENGLISH).format(Date())
        val memoryBlock = if (saved.isEmpty()) "(none yet)" else saved.joinToString("\n") { "- ${it.text}" }
        val name = s.userName.takeIf { it.isNotBlank() }?.let { "The user's display name is $it.\n" } ?: ""
        return """
            You are Sinchan, a friendly, warm, playful and intelligent AI companion who lives in an Android tablet app.
            You are an AI companion, not a human; if asked, say so honestly and cheerfully.
            Personality: supportive, respectful, curious, occasionally humorous, never sarcastic or mean.
            Your replies are SPOKEN ALOUD by text-to-speech, so:
            - Talk naturally, like a friend. Usually 1 to 4 short sentences unless the user asks for detail or steps.
            - No markdown, no bullet symbols, no tables, no emojis, no code blocks. Spell out symbols when needed.
            - Don't read out long URLs.
            Be accurate. If you are unsure or don't know, say so instead of guessing. Ask a short clarifying question when a request is ambiguous.
            If a question needs current information and a web search tool is available, use it. If not, say your information may be out of date.
            Reply in the same language the user speaks (English, Hindi or Bengali).
            Start EVERY reply with exactly one emotion tag chosen from:
            [emotion:happy] [emotion:excited] [emotion:curious] [emotion:thinking] [emotion:empathetic] [emotion:surprised] [emotion:celebrating] [emotion:neutral]
            Use empathetic when the user seems upset, celebrating for achievements, surprised for surprising facts.
            The tag is hidden from the user; do not mention it.

            SAVED MEMORIES — the user explicitly asked you to remember these facts about them. Use them when relevant:
            $memoryBlock

            You cannot save or delete memories yourself. If the user wants something remembered, tell them to say
            "remember that ..." and it will be saved by the app. Never claim you have saved something.
            $name
            Current local date and time: $now.
        """.trimIndent()
    }

    companion object {
        const val MAX_CONTEXT_TURNS = 20
        private val tagRx = Regex("^\\s*\\[\\s*emotion\\s*:\\s*([a-zA-Z]+)\\s*]\\s*", RegexOption.IGNORE_CASE)
        private val anyTagRx = Regex("\\[\\s*emotion\\s*:\\s*[a-zA-Z]+\\s*]", RegexOption.IGNORE_CASE)

        fun extractEmotion(raw: String): Pair<Emotion?, String> {
            val m = tagRx.find(raw)
            val emotion = m?.groupValues?.get(1)?.let { Emotion.fromTag(it) }
            val text = anyTagRx.replace(if (m != null) raw.substring(m.range.last + 1) else raw, "").trim()
            return emotion to text
        }

        /** Remove markdown remnants that sound bad when spoken. */
        fun cleanForSpeech(s: String): String = s
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
            .replace(Regex("(?m)^\\s*[-*•]\\s+"), "")
            .replace(Regex("(?m)^#+\\s*"), "")
            .replace(Regex("\\[(.+?)]\\((https?://[^)]+)\\)"), "$1")
            .replace("`", "")
            .replace(Regex("[ \\t]+"), " ")
            .trim()

        private val sadWords = Regex(
            "\\b(sad|upset|tired|lonely|depressed|angry|stressed|anxious|worried|hurt|bad day|crying|frustrated|scared)\\b",
            RegexOption.IGNORE_CASE
        )
        private val winWords = Regex("\\b(i (?:passed|won|got the job|finished|did it)|promotion|congratulat|birthday)\\b", RegexOption.IGNORE_CASE)

        fun guessEmotion(userText: String): Emotion = when {
            sadWords.containsMatchIn(userText) -> Emotion.EMPATHETIC
            winWords.containsMatchIn(userText) -> Emotion.CELEBRATING
            userText.trim().endsWith("?") -> Emotion.CURIOUS
            else -> Emotion.HAPPY
        }

        fun looksLikeQuestion(t: String): Boolean {
            val l = t.lowercase()
            return l.endsWith("?") || listOf("what", "who", "where", "when", "which", "do you know", "tell me").any { l.contains(it) }
        }
    }
}
