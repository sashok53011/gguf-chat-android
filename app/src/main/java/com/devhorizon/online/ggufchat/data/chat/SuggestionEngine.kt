package com.devhorizon.online.ggufchat.data.chat

import android.util.Log
import com.devhorizon.online.ggufchat.data.llm.LlmEngine
import com.devhorizon.online.ggufchat.data.model.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Generates quick-reply suggestions using the local GGUF model.
 * Tracks all suggestions shown in the current session so that
 * "New Suggestions" can produce only novel ones.
 */
class SuggestionEngine(private val llmEngine: LlmEngine) {
    private val TAG = "SuggestionEngine"

    // All suggestions shown in this session (normalized for dedup)
    private val shownSuggestions = mutableSetOf<String>()

    fun normalize(text: String): String {
        return text
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), "") // strip emoji/punctuation
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun markShown(suggestions: List<String>) {
        suggestions.forEach { shownSuggestions.add(normalize(it)) }
    }

    fun clearHistory() {
        shownSuggestions.clear()
    }

    suspend fun generate(
        messages: List<ChatMessage>,
        count: Int,
        onlyNew: Boolean = false,
        docsBlock: String? = null,
        onPartial: ((List<String>, String?) -> Unit)? = null
    ): List<String> = withContext(Dispatchers.IO) {
        if (!llmEngine.isLoaded) return@withContext emptyList()

        val exclude = if (onlyNew) shownSuggestions else emptySet()
        val excludeList = exclude.toList()

        // Try with increasing temperature until enough suggestions are found.
        // Base/small models often return fewer than requested in a single pass.
        val temperatures = listOf(0.7f, 0.85f, 1.0f)
        var allSuggestions = mutableListOf<String>()
        Log.d(TAG, "generate: count=$count onlyNew=$onlyNew temps=$temperatures shown=${shownSuggestions.size}")

        for (temp in temperatures) {
            val suggestions = llmEngine.generateSuggestions(
                messages = messages,
                count = count,
                excludeSuggestions = excludeList.toSet(),
                temperature = temp,
                docsBlock = docsBlock,
                onPartial = if (onPartial == null) null else { complete, pending ->
                    // Merge the already-collected suggestions with the ones that
                    // just finished streaming, keeping the dedup rules.
                    val merged = LinkedHashSet<String>()
                    merged.addAll(allSuggestions)
                    for (s in complete) {
                        val norm = normalize(s)
                        if (norm.isNotEmpty() && !shownSuggestions.contains(norm)) merged.add(s)
                    }
                    onPartial.invoke(merged.toList(), pending)
                }
            )
            Log.d(TAG, "round temp=$temp -> ${suggestions.size} suggestions: $suggestions")

            for (s in suggestions) {
                val norm = normalize(s)
                if (norm.isNotEmpty() && !shownSuggestions.contains(norm) && !allSuggestions.contains(s)) {
                    allSuggestions.add(s)
                }
            }

            if (allSuggestions.size >= count) break
        }
        Log.d(TAG, "generate -> ${allSuggestions.size} / $count")

        allSuggestions.take(count)
    }
}
