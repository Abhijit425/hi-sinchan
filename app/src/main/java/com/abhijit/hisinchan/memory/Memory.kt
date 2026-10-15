package com.abhijit.hisinchan.memory

import com.abhijit.hisinchan.data.MemoryDao
import com.abhijit.hisinchan.data.MemoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** What the user asked for, if the utterance is an explicit memory instruction. */
sealed interface MemoryCommand {
    data class Remember(val fact: String) : MemoryCommand
    data class Forget(val query: String) : MemoryCommand
    data object ForgetEverything : MemoryCommand
    data object ListAll : MemoryCommand
    data object ShowScreen : MemoryCommand
}

/**
 * Deterministic, on-device detection of EXPLICIT memory instructions.
 * Only these phrases create or delete memories — ordinary chat never does.
 */
object MemoryCommandParser {

    private val wakePrefix = Regex(
        "^(?:(?:hi|hey|hello|ok|okay)[,!.\\s]+)?(?:sin\\s?chan|shin\\s?chan|sinchan)[,!.:\\s]*",
        RegexOption.IGNORE_CASE
    )
    private val politePrefix = Regex("^(?:please|can you|could you|would you|will you|i want you to)[,\\s]+", RegexOption.IGNORE_CASE)

    private val rememberRx = Regex(
        "^(?:please\\s+)?(?:remember|memori[sz]e|memorize|note down|make a note|keep in mind|save (?:this|that|to memory)|don'?t forget|do not forget)" +
            "(?:\\s+(?:that|this|about))?[:,\\s]+(.+)$",
        RegexOption.IGNORE_CASE
    )

    private val forgetAllRx = Regex(
        "^(?:please\\s+)?(?:forget|erase|delete|clear|wipe)\\s+(?:everything|all (?:of )?(?:your |my )?(?:memories|memory|that you (?:remember|know))|all memories|your (?:whole )?memory)(?:\\s+about me)?[.!?]*$",
        RegexOption.IGNORE_CASE
    )

    private val forgetRx = Regex(
        "^(?:please\\s+)?(?:forget|stop remembering|delete (?:the )?memory(?: about)?|remove (?:the )?memory(?: about)?|erase (?:the )?memory(?: about)?)" +
            "(?:\\s+(?:that|about|the fact that))?\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )

    private val listRx = Regex(
        "(?:what (?:do|did) you (?:remember|know|have memori[sz]ed|have saved)(?: about me)?|" +
            "what have you (?:memori[sz]ed|remembered|saved)|tell me (?:what|everything) you (?:remember|know about me)|" +
            "list (?:your |my |all )?memor(?:y|ies)|what(?:'s| is) in your memory)",
        RegexOption.IGNORE_CASE
    )

    private val showRx = Regex(
        "(?:show|open|display)(?: me)? (?:everything you(?:'ve| have)? (?:memori[sz]ed|remembered|saved)|(?:all |your |my )?memor(?:y|ies)|the memory (?:screen|manager))",
        RegexOption.IGNORE_CASE
    )

    fun stripWake(text: String): String {
        var t = text.trim()
        t = wakePrefix.replace(t, "").trim()
        t = politePrefix.replace(t, "").trim()
        return t
    }

    fun parse(raw: String): MemoryCommand? {
        val t = stripWake(raw).trimEnd('.', '!', ' ')
        if (t.isBlank()) return null
        if (showRx.containsMatchIn(t)) return MemoryCommand.ShowScreen
        if (forgetAllRx.matches(t)) return MemoryCommand.ForgetEverything
        forgetRx.matchEntire(t)?.let { m ->
            val q = m.groupValues[1].trim().trimEnd('.', '!', '?')
            if (q.isNotBlank()) return MemoryCommand.Forget(q)
        }
        rememberRx.matchEntire(t)?.let { m ->
            val fact = cleanFact(m.groupValues[1])
            // "remember?" or "remember when..." questions are not instructions
            if (fact.isNotBlank() && !t.endsWith("?") && !fact.lowercase().startsWith("when ")) {
                return MemoryCommand.Remember(fact)
            }
        }
        if (listRx.containsMatchIn(t)) return MemoryCommand.ListAll
        return null
    }

    private fun cleanFact(s: String): String {
        val f = s.trim().trim('"', '\'', '“', '”').trimEnd('.', '!', ' ')
        return f.replaceFirstChar { it.uppercase() }
    }

    private val pronounRx = Regex(
        "\\b(i am|i'm|i was|i have|i've|i'll|i'd|myself|my|mine|me|i)\\b",
        RegexOption.IGNORE_CASE
    )
    private val pronounMap = mapOf(
        "i am" to "you are", "i'm" to "you're", "i was" to "you were", "i have" to "you have",
        "i've" to "you've", "i'll" to "you'll", "i'd" to "you'd", "myself" to "yourself",
        "my" to "your", "mine" to "yours", "me" to "you", "i" to "you",
    )

    /** "My name is Abhijit" -> "your name is Abhijit", for spoken confirmations. */
    fun toSecondPerson(fact: String): String =
        pronounRx.replace(fact) { m -> pronounMap[m.value.lowercase()] ?: m.value }
}

@Serializable
data class MemoryExport(val app: String = "Hi Sinchan", val exportedAt: Long, val memories: List<Item>) {
    @Serializable
    data class Item(val text: String, val createdAt: Long, val updatedAt: Long)
}

sealed interface SaveResult {
    data class Saved(val memory: MemoryEntity) : SaveResult
    data class Duplicate(val existing: MemoryEntity) : SaveResult
    data class Failed(val error: Throwable) : SaveResult
}

class MemoryRepository(private val dao: MemoryDao) {

    val memories: Flow<List<MemoryEntity>> = dao.observeAll()

    suspend fun all(): List<MemoryEntity> = dao.getAll()

    /** Returns Saved only after Room confirms the insert (row id > 0). */
    suspend fun remember(fact: String): SaveResult = try {
        val existing = dao.getAll().firstOrNull { normalize(it.text) == normalize(fact) }
        if (existing != null) SaveResult.Duplicate(existing)
        else {
            // A newer fact on the same subject replaces the older one ("my favourite colour is ...")
            val subject = subjectOf(fact)
            val replaced = if (subject != null) dao.getAll().firstOrNull { subjectOf(it.text) == subject } else null
            if (replaced != null) {
                val updated = replaced.copy(text = fact, updatedAt = System.currentTimeMillis())
                if (dao.update(updated) == 1) SaveResult.Saved(updated)
                else SaveResult.Failed(IllegalStateException("Update failed"))
            } else {
                val id = dao.insert(MemoryEntity(text = fact))
                val saved = dao.getById(id)
                if (id > 0 && saved != null) SaveResult.Saved(saved)
                else SaveResult.Failed(IllegalStateException("Insert not confirmed"))
            }
        }
    } catch (t: Throwable) {
        SaveResult.Failed(t)
    }

    suspend fun update(id: Long, newText: String): Boolean {
        val m = dao.getById(id) ?: return false
        return dao.update(m.copy(text = newText.trim(), updatedAt = System.currentTimeMillis())) == 1
    }

    suspend fun delete(id: Long): Boolean = dao.deleteById(id) == 1

    suspend fun clearAll(): Int = dao.deleteAll()

    /** Best keyword match for "forget my favourite colour". */
    suspend fun findBestMatch(query: String): List<MemoryEntity> {
        val q = keywords(query)
        if (q.isEmpty()) return emptyList()
        val scored = dao.getAll().map { m ->
            val k = keywords(m.text)
            m to q.count { it in k }.toDouble() / q.size
        }.filter { it.second > 0.0 }.sortedByDescending { it.second }
        if (scored.isEmpty()) return emptyList()
        val best = scored.first().second
        return scored.filter { it.second >= best - 0.0001 }.map { it.first }
    }

    suspend fun relevantFor(text: String, limit: Int = 3): List<MemoryEntity> {
        val q = keywords(text)
        if (q.isEmpty()) return emptyList()
        return dao.getAll().map { it to keywords(it.text).count { w -> w in q } }
            .filter { it.second > 0 }.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    suspend fun exportJson(): String {
        val items = dao.getAll().map { MemoryExport.Item(it.text, it.createdAt, it.updatedAt) }
        return Json { prettyPrint = true }.encodeToString(MemoryExport(exportedAt = System.currentTimeMillis(), memories = items))
    }

    companion object {
        private val stop = setOf(
            "the", "a", "an", "is", "are", "was", "my", "me", "i", "that", "this", "to", "of", "and",
            "it", "in", "on", "for", "about", "you", "your", "what", "do", "with", "am", "be", "please", "remember",
        )

        fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z0-9\\u0900-\\u097F\\u0980-\\u09FF ]"), "").replace(Regex("\\s+"), " ").trim()

        fun keywords(s: String): Set<String> =
            normalize(s).split(" ").map { it.removeSuffix("s") }.filter { it.length > 1 && it !in stop }.toSet()

        /** "My favourite colour is blue" -> "my favourite colour". Used to update instead of duplicate. */
        fun subjectOf(s: String): String? {
            val n = normalize(s)
            val m = Regex("^(my [a-z ]{2,40}?) (?:is|are)\\b").find(n) ?: return null
            return m.groupValues[1].replace("favorite", "favourite").replace("color", "colour")
        }
    }
}
