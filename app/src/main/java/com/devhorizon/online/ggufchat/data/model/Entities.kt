package com.devhorizon.online.ggufchat.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val role: String, // "user" | "assistant"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    // Optional image shown with this message (local file path)
    val imagePath: String? = null,
    // Generation stats for assistant replies (0 for user messages / unknown)
    val generationMs: Long = 0L,
    val tokenCount: Int = 0
)

@Serializable
data class SessionDocument(
    val id: String,
    val name: String,
    val ext: String,
    val sizeBytes: Long,
    val text: String,
    val truncated: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
)

@Serializable
data class ChatSession(
    val id: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val messages: List<ChatMessage> = emptyList(),
    // Documents attached to this chat, injected as context on every generation.
    val documents: List<SessionDocument> = emptyList()
)

@Serializable
data class ModelInfo(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val architecture: String = "Unknown",
    val contextLength: Int = 4096,
    val tensorCount: Long = 0,
    val addedAt: Long = System.currentTimeMillis(),
    // Optional multimodal projector (mmproj) enabling image input
    val mmprojPath: String? = null
)

data class GgufInfo(
    val isValid: Boolean,
    val version: Int = 0,
    val tensorCount: Long = 0,
    val kvCount: Long = 0,
    val error: String? = null,
    val modelArchitecture: String = "Unknown",
    val contextLength: Int = 2048,
    val metadata: Map<String, Any> = emptyMap()
)

enum class ModelState { UNLOADED, LOADING, LOADED, ERROR }

data class LlmSettings(
    val systemPrompt: String = "You are a helpful assistant.",
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val maxTokens: Int = 1024,
    val contextLength: Int = 4096,
    val threads: Int = 4,
    val autoSuggest: Boolean = true,
    val suggestionCount: Int = 4,
    // Max tokens of attached documents fed into the suggestion prompt (0 = ignore docs)
    val suggestionDocTokens: Int = 768,
    val ttsEnabled: Boolean = true,
    val ttsLanguage: String = "en",
    val ttsRate: Float = 1.0f,
    val sttLanguage: String = "en",
    val appLanguage: String = "en",
    val appTheme: String = "light",
    // Language for assistant replies AND suggestions: "auto" (follow appLanguage),
    // or "en" / "ru" / "de". Enforced regardless of message/document language.
    val answerLanguage: String = "auto",
    // "bottom" (default) or "top"
    val inputPosition: String = "bottom",
    // "wrap" (chips flow onto new lines) or "single_row" (one scrollable row)
    val suggestionsLayout: String = "wrap"
)
