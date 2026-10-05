package com.devhorizon.online.ggufchat.ui.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.devhorizon.online.ggufchat.data.chat.ChatRepository
import com.devhorizon.online.ggufchat.data.chat.DocumentException
import com.devhorizon.online.ggufchat.data.chat.DocumentReader
import com.devhorizon.online.ggufchat.data.chat.SuggestionEngine
import com.devhorizon.online.ggufchat.data.llm.LlmEngine
import com.devhorizon.online.ggufchat.data.model.ChatMessage
import com.devhorizon.online.ggufchat.data.model.ChatSession
import com.devhorizon.online.ggufchat.data.model.LlmSettings
import com.devhorizon.online.ggufchat.data.model.ModelInfo
import com.devhorizon.online.ggufchat.data.model.ModelState
import com.devhorizon.online.ggufchat.data.model.SessionDocument
import com.devhorizon.online.ggufchat.data.voice.SttManager
import com.devhorizon.online.ggufchat.data.voice.TtsManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "ChatViewModel"

    val llmEngine = LlmEngine(application)
    val chatRepository = ChatRepository(application)
    val suggestionEngine = SuggestionEngine(llmEngine)
    val sttManager = SttManager(application)
    val ttsManager = TtsManager(application)

    // Chat state
    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    // Image attached to the next message (local file path), if any
    private val _pendingImage = MutableStateFlow<String?>(null)
    val pendingImage: StateFlow<String?> = _pendingImage.asStateFlow()

    // True while the vision encoder is processing an image (slow, cancellable)
    private val _isPreparingVision = MutableStateFlow(false)
    val isPreparingVision: StateFlow<Boolean> = _isPreparingVision.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()

    // The suggestion currently being typed by the model (live streaming preview)
    private val _pendingSuggestion = MutableStateFlow<String?>(null)
    val pendingSuggestion: StateFlow<String?> = _pendingSuggestion.asStateFlow()

    private val _isGeneratingSuggestions = MutableStateFlow(false)
    val isGeneratingSuggestions: StateFlow<Boolean> = _isGeneratingSuggestions.asStateFlow()

    private val _statusText = MutableStateFlow("")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _appLanguage = MutableStateFlow("en")
    val appLanguage: StateFlow<String> = _appLanguage.asStateFlow()

    private val _appTheme = MutableStateFlow("light")
    val appTheme: StateFlow<String> = _appTheme.asStateFlow()

    // Documents attached to the current session ("chat with docs")
    private val _documents = MutableStateFlow<List<SessionDocument>>(emptyList())
    val documents: StateFlow<List<SessionDocument>> = _documents.asStateFlow()

    // Model search (Hugging Face)
    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    // Last import/download error, shown on the model screen (the search dialog
    // closes immediately, so a failed download was previously silent).
    private val _downloadError = MutableStateFlow<String?>(null)
    val downloadError: StateFlow<String?> = _downloadError.asStateFlow()
    fun clearDownloadError() { _downloadError.value = null }

    private val _searchResults = MutableStateFlow<List<com.devhorizon.online.ggufchat.data.llm.GgufSearchResult>>(emptyList())
    val searchResults: StateFlow<List<com.devhorizon.online.ggufchat.data.llm.GgufSearchResult>> = _searchResults.asStateFlow()

    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError.asStateFlow()

    // System RAM monitor (bytes), refreshed periodically for the header bar.
    private val _ramTotal = MutableStateFlow(0L)
    val ramTotal: StateFlow<Long> = _ramTotal.asStateFlow()
    private val _ramUsed = MutableStateFlow(0L)
    val ramUsed: StateFlow<Long> = _ramUsed.asStateFlow()
    private val _ramFree = MutableStateFlow(0L)
    val ramFree: StateFlow<Long> = _ramFree.asStateFlow()

    private var generationJob: Job? = null
    private var stopRequested = false

    private fun l(key: String): String =
        com.devhorizon.online.ggufchat.ui.theme.Localization.getString(key, llmEngine.settings.value.appLanguage)

    init {
        _appLanguage.value = llmEngine.settings.value.appLanguage
        _appTheme.value = llmEngine.settings.value.appTheme
        ttsManager.initialize()
        ttsManager.setLanguage(llmEngine.settings.value.ttsLanguage)
        ttsManager.setRate(llmEngine.settings.value.ttsRate)
        refreshDocuments()
        refreshMemory()
        viewModelScope.launch {
            while (true) {
                refreshMemory()
                delay(2000)
            }
        }
    }

    // ==================== Model ====================

    fun importModel(uri: Uri, displayName: String? = null) {
        viewModelScope.launch {
            _statusText.value = l("importing_model")
            _downloadError.value = null
            val result = llmEngine.importModel(uri, displayName)
            if (result.isSuccess) {
                _statusText.value = "${l("model_imported")}: ${result.getOrNull()?.name}"
            } else {
                val msg = "${l("import_failed")}: ${result.exceptionOrNull()?.message}"
                _statusText.value = msg
                _downloadError.value = msg
            }
        }
    }

    fun downloadModelFromUrl(url: String, displayName: String? = null) {
        // A projector (mmproj) URL is routed to projector handling and attached to
        // the loaded model (or the first vision-capable one).
        val rawName = displayName ?: url.substringAfterLast('/').substringBefore('?')
        if (rawName.contains("mmproj", ignoreCase = true)) {
            _downloadError.value = null
            val target = llmEngine.models.value.firstOrNull { llmEngine.isLoadedPath(it.path) }
                ?: llmEngine.models.value.firstOrNull {
                    com.devhorizon.online.ggufchat.data.llm.ModelCompatibility.isVisionArch(it.architecture)
                }
            if (target == null) {
                val msg = l("not_a_projector")
                _statusText.value = msg
                _downloadError.value = msg
                return
            }
            downloadMmprojForModel(target, url, displayName)
            return
        }
        viewModelScope.launch {
            _statusText.value = l("downloading_model")
            _downloadError.value = null
            val result = llmEngine.downloadModelFromUrl(url, displayName)
            if (result.isSuccess) {
                _statusText.value = "${l("model_downloaded")}: ${result.getOrNull()?.name}"
            } else {
                val msg = result.exceptionOrNull()?.message ?: l("error_msg")
                val text = if (msg == l("cancelled")) l("download_cancelled")
                else "${l("download_failed")}: $msg"
                _statusText.value = text
                _downloadError.value = text
            }
        }
    }

    fun cancelModelDownload() {
        viewModelScope.launch { llmEngine.cancelDownload() }
    }

    fun searchModels(query: String, maxFileSizeMB: Long, includeProjectors: Boolean = false) {
        viewModelScope.launch {
            _isSearching.value = true
            _searchError.value = null
            _searchResults.value = emptyList()
            val results = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    com.devhorizon.online.ggufchat.data.llm.HuggingFaceClient
                        .searchGgufModels(query, maxFileSizeMB * 1024L * 1024L, includeProjectors = includeProjectors)
                } catch (e: Exception) {
                    Log.e(TAG, "Model search failed", e)
                    emptyList()
                }
            }
            _searchResults.value = results
            _isSearching.value = false
            if (results.isEmpty()) {
                _searchError.value = "no_results"
            }
        }
    }

    fun loadModel(modelInfo: ModelInfo) {
        viewModelScope.launch {
            _statusText.value = l("loading_model")
            val success = llmEngine.loadModel(modelInfo, modelInfo.mmprojPath)
            _statusText.value = if (success) "${l("model_loaded_msg")}: ${modelInfo.name}" else l("load_model_failed")
        }
    }

    // ==================== Image input ====================

    /** Copies the picked image into app storage so it survives and is readable later. */
    fun attachImage(uri: Uri) {
        viewModelScope.launch {
            try {
                val path = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val app = getApplication<Application>()
                    val dir = java.io.File(app.filesDir, "images").apply { mkdirs() }
                    val out = java.io.File(dir, "img_${UUID.randomUUID()}.jpg")
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        out.outputStream().use { input.copyTo(it) }
                    }
                    out.absolutePath
                }
                _pendingImage.value = path
                _statusText.value = l("image_attached")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to attach image", e)
                _statusText.value = l("image_error")
            }
        }
    }

    fun clearPendingImage() {
        _pendingImage.value = null
    }

    fun unloadModel() {
        llmEngine.unloadModel()
        _suggestions.value = emptyList()
        _statusText.value = l("model_unloaded")
    }

    // ==================== RAM ====================

    fun refreshMemory() {
        try {
            val am = getApplication<Application>()
                .getSystemService(Application.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            _ramTotal.value = mi.totalMem
            _ramFree.value = mi.availMem
            _ramUsed.value = (mi.totalMem - mi.availMem).coerceAtLeast(0L)
        } catch (_: Exception) { /* keep the last values */ }
    }

    /**
     * Free RAM: unload the model (which also frees its projector and KV cache),
     * drop transient image state, run GC and return free native arena memory to
     * the OS. One tap = "unload everything".
     */
    fun freeRam() {
        val am = getApplication<Application>()
            .getSystemService(Application.ACTIVITY_SERVICE) as android.app.ActivityManager
        val before = android.app.ActivityManager.MemoryInfo()
            .also { am.getMemoryInfo(it) }.availMem

        llmEngine.unloadModel()
        _pendingImage.value = null
        _suggestions.value = emptyList()
        _streamingText.value = ""
        try { System.gc() } catch (_: Throwable) {}
        try {
            com.devhorizon.online.ggufchat.data.llm.LocalLlmNative.nativeFreeMemory()
        } catch (_: Throwable) {}

        val after = android.app.ActivityManager.MemoryInfo()
            .also { am.getMemoryInfo(it) }.availMem
        refreshMemory()
        val freed = (after - before).coerceAtLeast(0L)
        val freedText = if (freed >= 1_073_741_824L) {
            "%.1f GB".format(freed / 1_073_741_824.0)
        } else {
            "%.0f MB".format(freed / 1_048_576.0)
        }
        _statusText.value = "${l("ram_freed")}: $freedText"
    }

    fun deleteModel(modelInfo: ModelInfo) {
        llmEngine.deleteModel(modelInfo)
    }

    // ==================== Chat ====================

    fun setInputText(text: String) {
        _inputText.value = text
    }

    fun createNewSession() {
        chatRepository.createSession()
        _suggestions.value = emptyList()
        suggestionEngine.clearHistory()
        refreshDocuments()
    }

    fun selectSession(id: String) {
        chatRepository.selectSession(id)
        _suggestions.value = emptyList()
        suggestionEngine.clearHistory()
        refreshDocuments()
    }

    fun deleteSession(id: String) {
        chatRepository.deleteSession(id)
        refreshDocuments()
    }

    // ==================== Documents ("chat with docs") ====================

    private fun refreshDocuments() {
        _documents.value = chatRepository.getCurrentSession()?.documents ?: emptyList()
    }

    /**
     * Reads a picked file, trims it to the remaining context budget and attaches
     * it to the current session. Its text is sent as context on every generation.
     */
    fun addDocument(uri: Uri) {
        viewModelScope.launch {
            val session = chatRepository.getCurrentSession() ?: chatRepository.createSession()
            _statusText.value = l("reading_document")
            try {
                val raw = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    DocumentReader.read(getApplication<Application>(), uri)
                }
                val budget = DocumentReader.budgetTokens(llmEngine.settings.value.contextLength)
                val used = session.documents.sumOf { DocumentReader.estimateTokens(it.text) }
                val remaining = budget - used
                if (remaining <= 10) {
                    _statusText.value = l("docs_budget_full")
                    return@launch
                }
                val (text, truncated) = DocumentReader.truncate(raw.text, remaining)
                if (text.isBlank()) {
                    _statusText.value = l("doc_read_error")
                    return@launch
                }
                val doc = SessionDocument(
                    id = UUID.randomUUID().toString(),
                    name = raw.name,
                    ext = raw.ext,
                    sizeBytes = raw.sizeBytes,
                    text = text,
                    truncated = truncated
                )
                chatRepository.addDocument(session.id, doc)
                refreshDocuments()
                _statusText.value = l("document_added") +
                    if (truncated) " · ${l("doc_truncated")}" else ""
                // Immediately offer suggestions based on the new document context.
                if (llmEngine.settings.value.autoSuggest && llmEngine.isLoaded && !_isGenerating.value) {
                    generateSuggestions()
                }
            } catch (e: DocumentException) {
                _statusText.value = l(e.messageKey)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read document", e)
                _statusText.value = l("doc_read_error")
            }
        }
    }

    fun removeDocument(id: String) {
        val sessionId = chatRepository.getCurrentSession()?.id ?: return
        viewModelScope.launch {
            chatRepository.removeDocument(sessionId, id)
            refreshDocuments()
        }
    }

    fun documentBudgetTokens(): Int =
        DocumentReader.budgetTokens(llmEngine.settings.value.contextLength)

    private fun buildDocsBlock(docs: List<SessionDocument>): String? {
        if (docs.isEmpty()) return null
        val sb = StringBuilder()
        sb.append("You have access to the following document(s). Use their content as context ")
        sb.append("to answer the user's questions. If the answer is not in the documents, say so.\n")
        for (d in docs) {
            sb.append("\n[Document: ").append(d.name).append("]\n")
            sb.append(d.text)
            sb.append("\n[/Document]\n")
        }
        return sb.toString()
    }

    /**
     * Shorter document excerpt used only for the suggestion prompt, bounded by
     * the "suggestionDocTokens" setting. Returns null when docs are disabled or
     * empty (so suggestions fall back to the assistant response alone).
     */
    private fun buildSuggestionDocsBlock(docs: List<SessionDocument>): String? {
        if (docs.isEmpty()) return null
        val budget = llmEngine.settings.value.suggestionDocTokens
        if (budget <= 0) return null
        val sb = StringBuilder()
        for (d in docs) {
            sb.append("\n[Document: ").append(d.name).append("]\n")
            sb.append(d.text)
            sb.append("\n[/Document]\n")
        }
        val (text, _) = DocumentReader.truncate(sb.toString(), budget)
        return text.ifBlank { null }
    }

    fun getCurrentSession(): ChatSession? = chatRepository.getCurrentSession()

    fun sendMessage() {
        val text = _inputText.value.trim()
        if (text.isEmpty() || _isGenerating.value) return
        if (!llmEngine.isLoaded) {
            _statusText.value = l("please_load_model")
            return
        }

        val session = chatRepository.getCurrentSession() ?: chatRepository.createSession()
        val sessionId = session.id
        val imagePath = _pendingImage.value
        val useVision = imagePath != null && llmEngine.hasVision
        if (imagePath != null && !useVision) {
            _statusText.value = l("vision_unavailable")
        }

        // Add user message
        val userMessage = ChatMessage(role = "user", content = text, imagePath = imagePath)
        viewModelScope.launch {
            chatRepository.addMessage(sessionId, userMessage)
        }

        _inputText.value = ""
        _pendingImage.value = null
        _isGenerating.value = true
        _streamingText.value = ""
        _suggestions.value = emptyList()
        stopRequested = false

        generationJob = viewModelScope.launch {
            try {
                val messages = session.messages + userMessage
                val fullResponse = StringBuilder()
                var firstToken = false
                val onTok: (String) -> Unit = { token ->
                    if (!firstToken) {
                        firstToken = true
                        _isPreparingVision.value = false
                    }
                    fullResponse.append(token)
                    _streamingText.value = fullResponse.toString()
                }

                val response = if (useVision) {
                    _isPreparingVision.value = true
                    llmEngine.generateVision(
                        messages,
                        imagePath!!,
                        docsBlock = buildDocsBlock(session.documents),
                        onToken = onTok
                    )
                } else {
                    llmEngine.generate(
                        messages,
                        onToken = onTok,
                        docsBlock = buildDocsBlock(session.documents)
                    )
                }

                val stopped = stopRequested
                stopRequested = false

                // Save assistant message (also the partial one after Stop)
                if (response.isNotEmpty()) {
                    chatRepository.addMessage(
                        sessionId,
                        ChatMessage(role = "assistant", content = response)
                    )
                }

                _streamingText.value = ""
                _isGenerating.value = false

                if (!stopped) {
                    // Auto-suggest
                    if (llmEngine.settings.value.autoSuggest && response.isNotEmpty()) {
                        generateSuggestions()
                    }

                    // TTS
                    if (llmEngine.settings.value.ttsEnabled && response.isNotEmpty()) {
                        ttsManager.speak(response)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Generation failed", e)
                _statusText.value = "${l("error_msg")}: ${e.message}"
                _isGenerating.value = false
                _streamingText.value = ""
            } finally {
                _isPreparingVision.value = false
            }
        }
    }

    // ==================== Projector (mmproj) management ====================

    fun attachMmprojToModel(model: ModelInfo, uri: Uri) {
        viewModelScope.launch {
            _statusText.value = l("projector_importing")
            llmEngine.importMmproj(uri)
                .onSuccess { path ->
                    llmEngine.setModelMmproj(model.path, path)
                    if (llmEngine.isLoadedPath(model.path)) llmEngine.loadMmproj(path)
                    _statusText.value = l("projector_attached")
                }
                .onFailure { e ->
                    _statusText.value = "${l("error_msg")}: ${e.message}"
                }
        }
    }

    fun downloadMmprojForModel(model: ModelInfo, url: String, name: String?) {
        viewModelScope.launch {
            llmEngine.downloadMmprojFromUrl(url, name)
                .onSuccess { path ->
                    llmEngine.setModelMmproj(model.path, path)
                    if (llmEngine.isLoadedPath(model.path)) llmEngine.loadMmproj(path)
                    _statusText.value = l("projector_attached")
                }
                .onFailure { e ->
                    _statusText.value = "${l("error_msg")}: ${e.message}"
                }
        }
    }

    fun removeMmprojFromModel(model: ModelInfo) {
        llmEngine.setModelMmproj(model.path, null)
        _statusText.value = l("projector_removed")
    }

    fun stopGeneration() {
        stopRequested = true
        // nativeCancel no longer blocks: generation does not hold the JNI mutex,
        // so this returns immediately even when called from the UI thread.
        llmEngine.cancelGeneration()
        _isGenerating.value = false
        _streamingText.value = ""
    }

    // ==================== Suggestions ====================

    fun generateSuggestions(onlyNew: Boolean = false) {
        if (!llmEngine.isLoaded || _isGeneratingSuggestions.value || _isGenerating.value) return
        val session = chatRepository.getCurrentSession() ?: return
        val hasAssistant = session.messages.any { it.role == "assistant" }
        val docsBlock = buildSuggestionDocsBlock(session.documents)
        if (!hasAssistant && docsBlock.isNullOrBlank()) return

        _isGeneratingSuggestions.value = true
        _pendingSuggestion.value = null
        viewModelScope.launch {
            try {
                val count = llmEngine.settings.value.suggestionCount
                val newSuggestions = suggestionEngine.generate(
                    messages = session.messages,
                    count = count,
                    onlyNew = onlyNew,
                    docsBlock = docsBlock,
                    onPartial = { partial, pending ->
                        if (partial.isNotEmpty()) _suggestions.value = partial
                        _pendingSuggestion.value = pending
                    }
                )
                if (newSuggestions.isNotEmpty()) {
                    suggestionEngine.markShown(newSuggestions)
                    _suggestions.value = newSuggestions
                } else if (!onlyNew) {
                    _suggestions.value = emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to generate suggestions", e)
            } finally {
                _pendingSuggestion.value = null
                _isGeneratingSuggestions.value = false
            }
        }
    }

    fun onSuggestionClicked(suggestion: String) {
        _inputText.value = suggestion
        _suggestions.value = emptyList()
    }

    // ==================== Voice ====================

    fun startVoiceInput() {
        val lang = llmEngine.settings.value.sttLanguage
        sttManager.startListening(lang) { result ->
            _inputText.value = result
        }
    }

    fun stopVoiceInput() {
        sttManager.stopListening()
    }

    // ==================== TTS ====================

    fun speakText(text: String) {
        ttsManager.speak(text)
    }

    fun stopSpeaking() {
        ttsManager.stop()
    }

    // ==================== Settings ====================

    fun updateSettings(newSettings: LlmSettings) {
        llmEngine.saveSettings(newSettings)
        _appLanguage.value = newSettings.appLanguage
        _appTheme.value = newSettings.appTheme
        ttsManager.setLanguage(newSettings.ttsLanguage)
        ttsManager.setRate(newSettings.ttsRate)
    }

    fun setAppLanguage(lang: String) {
        val current = llmEngine.settings.value
        updateSettings(current.copy(appLanguage = lang))
    }

    // ==================== Cleanup ====================

    override fun onCleared() {
        super.onCleared()
        sttManager.destroy()
        ttsManager.shutdown()
        llmEngine.unloadModel()
    }
}
