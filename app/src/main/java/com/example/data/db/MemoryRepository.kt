package com.example.data.db

import kotlinx.coroutines.flow.Flow

/**
 * Local-only repository enforcing Zero-Token-Wastage memory recall:
 * performs local keyword matching in Kotlin and returns ONLY the single top matching snippet.
 */
class MemoryRepository(
    private val memoryDao: MemoryDao,
    private val alarmDao: AlarmDao
) {
    val allMemories: Flow<List<MemoryEntity>> = memoryDao.observeAll()
    val activeAlarms: Flow<List<AlarmEntity>> = alarmDao.observeActiveAlarms()

    suspend fun saveMemory(content: String): Long {
        val clean = content.trim()
        if (clean.isEmpty()) return -1L
        val keywords = extractKeywords(clean).joinToString(",")
        return memoryDao.insert(MemoryEntity(content = clean, keywords = keywords))
    }

    suspend fun deleteMemory(id: Long) = memoryDao.deleteById(id)

    suspend fun saveAlarm(triggerAtMillis: Long, reason: String): Long =
        alarmDao.insert(AlarmEntity(triggerAtMillis = triggerAtMillis, reason = reason.trim()))

    suspend fun deactivateAlarm(id: Long) = alarmDao.deactivate(id)

    suspend fun deleteAlarm(id: Long) = alarmDao.deleteById(id)

    /**
     * Local keyword search in Kotlin returning ONLY the single best matching snippet
     * (truncated to 200 chars) so we never dump the entire database into prompts.
     */
    suspend fun findTopMatchingSnippet(query: String): String? {
        val queryTokens = extractKeywords(query)
        if (queryTokens.isEmpty()) return null

        val all = memoryDao.getAllSnapshot()
        if (all.isEmpty()) return null

        var bestMemory: MemoryEntity? = null
        var bestScore = 0

        for (item in all) {
            val itemTokens = (item.keywords.split(",") + extractKeywords(item.content))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()

            var score = 0
            for (qt in queryTokens) {
                if (itemTokens.contains(qt)) {
                    score += 3
                } else if (item.content.lowercase().contains(qt)) {
                    score += 2
                }
            }
            if (score > bestScore) {
                bestScore = score
                bestMemory = item
            }
        }

        return bestMemory?.content?.take(200)
    }

    private fun extractKeywords(text: String): Set<String> {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\u0900-\\u097F\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .toSet()
    }

    companion object {
        private val STOP_WORDS = setOf(
            "the", "is", "at", "which", "on", "a", "an", "and", "or", "for", "to", "in", "of",
            "my", "me", "i", "you", "what", "where", "when", "who", "how", "did", "do", "does",
            "was", "were", "are", "be", "been", "that", "this", "it", "about", "please", "sir",
            "jarvis", "remember", "tell", "kya", "hai", "hain", "mera", "meri", "mere", "ko",
            "ka", "ki", "ke", "se", "mein", "me", "tha", "thi", "batao", "yaad", "rakho"
        )
    }
}
