package com.devhorizon.online.ggufchat.data.chat

import android.content.Context
import android.util.Log
import com.devhorizon.online.ggufchat.data.model.ChatMessage
import com.devhorizon.online.ggufchat.data.model.ChatSession
import com.devhorizon.online.ggufchat.data.model.SessionDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class ChatRepository(private val context: Context) {
    private val TAG = "ChatRepository"

    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    private val sessionsDir: File
        get() = File(context.filesDir, "sessions").also { if (!it.exists()) it.mkdirs() }

    init {
        loadSessions()
    }

    private fun loadSessions() {
        try {
            val files = sessionsDir.listFiles { f -> f.extension == "json" } ?: emptyArray()
            val list = files.mapNotNull { file ->
                try {
                    parseSession(file.readText())
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse session: ${file.name}", e)
                    null
                }
            }.sortedByDescending { it.createdAt }
            _sessions.value = list
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load sessions", e)
        }
    }

    private fun parseSession(json: String): ChatSession {
        val o = JSONObject(json)
        val arr = o.getJSONArray("messages")
        val messages = mutableListOf<ChatMessage>()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            messages.add(
                ChatMessage(
                    role = m.getString("role"),
                    content = m.getString("content"),
                    timestamp = m.optLong("timestamp", System.currentTimeMillis()),
                    imagePath = m.optString("imagePath", "").ifBlank { null },
                    generationMs = m.optLong("generationMs", 0L),
                    tokenCount = m.optInt("tokenCount", 0)
                )
            )
        }
        return ChatSession(
            id = o.getString("id"),
            title = o.optString("title", "New Chat"),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            messages = messages,
            documents = parseDocuments(o)
        )
    }

    private fun parseDocuments(o: JSONObject): List<SessionDocument> {
        val arr = o.optJSONArray("documents") ?: return emptyList()
        val docs = mutableListOf<SessionDocument>()
        for (i in 0 until arr.length()) {
            val d = arr.optJSONObject(i) ?: continue
            val text = d.optString("text", "")
            if (text.isEmpty() && d.optString("name", "").isEmpty()) continue
            docs.add(
                SessionDocument(
                    id = d.optString("id", UUID.randomUUID().toString()),
                    name = d.optString("name", "document"),
                    ext = d.optString("ext", ""),
                    sizeBytes = d.optLong("sizeBytes", 0L),
                    text = text,
                    truncated = d.optBoolean("truncated", false),
                    addedAt = d.optLong("addedAt", System.currentTimeMillis())
                )
            )
        }
        return docs
    }

    private fun saveSession(session: ChatSession) {
        try {
            val o = JSONObject()
            o.put("id", session.id)
            o.put("title", session.title)
            o.put("createdAt", session.createdAt)
            val arr = JSONArray()
            for (m in session.messages) {
                val mo = JSONObject()
                mo.put("role", m.role)
                mo.put("content", m.content)
                mo.put("timestamp", m.timestamp)
                if (!m.imagePath.isNullOrBlank()) mo.put("imagePath", m.imagePath)
                if (m.generationMs > 0) mo.put("generationMs", m.generationMs)
                if (m.tokenCount > 0) mo.put("tokenCount", m.tokenCount)
                arr.put(mo)
            }
            o.put("messages", arr)

            val docsArr = JSONArray()
            for (d in session.documents) {
                val dobj = JSONObject()
                dobj.put("id", d.id)
                dobj.put("name", d.name)
                dobj.put("ext", d.ext)
                dobj.put("sizeBytes", d.sizeBytes)
                dobj.put("text", d.text)
                dobj.put("truncated", d.truncated)
                dobj.put("addedAt", d.addedAt)
                docsArr.put(dobj)
            }
            o.put("documents", docsArr)
            File(sessionsDir, "${session.id}.json").writeText(o.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save session: ${session.id}", e)
        }
    }

    fun createSession(): ChatSession {
        val session = ChatSession(
            id = UUID.randomUUID().toString(),
            title = "New Chat",
            messages = emptyList()
        )
        _sessions.value = listOf(session) + _sessions.value
        _currentSessionId.value = session.id
        saveSession(session)
        return session
    }

    fun selectSession(id: String) {
        _currentSessionId.value = id
    }

    fun getCurrentSession(): ChatSession? {
        val id = _currentSessionId.value ?: return null
        return _sessions.value.find { it.id == id }
    }

    suspend fun addMessage(sessionId: String, message: ChatMessage) = withContext(Dispatchers.IO) {
        val session = _sessions.value.find { it.id == sessionId } ?: return@withContext
        val updatedMessages = session.messages + message

        // Auto-generate title from first user message
        val updatedTitle = if (session.title == "New Chat" && message.role == "user") {
            message.content.take(40).replace("\n", " ") + if (message.content.length > 40) "..." else ""
        } else {
            session.title
        }

        val updated = session.copy(
            messages = updatedMessages,
            title = updatedTitle
        )
        _sessions.value = _sessions.value.map { if (it.id == sessionId) updated else it }
        saveSession(updated)
    }

    suspend fun addDocument(sessionId: String, document: SessionDocument) = withContext(Dispatchers.IO) {
        val session = _sessions.value.find { it.id == sessionId } ?: return@withContext
        val updated = session.copy(documents = session.documents + document)
        _sessions.value = _sessions.value.map { if (it.id == sessionId) updated else it }
        saveSession(updated)
    }

    suspend fun removeDocument(sessionId: String, documentId: String) = withContext(Dispatchers.IO) {
        val session = _sessions.value.find { it.id == sessionId } ?: return@withContext
        val updated = session.copy(documents = session.documents.filterNot { it.id == documentId })
        _sessions.value = _sessions.value.map { if (it.id == sessionId) updated else it }
        saveSession(updated)
    }

    fun deleteSession(id: String) {
        File(sessionsDir, "$id.json").delete()
        _sessions.value = _sessions.value.filter { it.id != id }
        if (_currentSessionId.value == id) {
            _currentSessionId.value = null
        }
    }

    fun clearAll() {
        sessionsDir.listFiles()?.forEach { it.delete() }
        _sessions.value = emptyList()
        _currentSessionId.value = null
    }
}
