package com.devhorizon.online.ggufchat.data.llm

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.util.Log
import com.devhorizon.online.ggufchat.data.model.GgufInfo
import com.devhorizon.online.ggufchat.data.model.LlmSettings
import com.devhorizon.online.ggufchat.data.model.ModelInfo
import com.devhorizon.online.ggufchat.data.model.ModelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class LlmEngine(private val context: Context) {
    private val TAG = "LlmEngine"
    private val PREFS_NAME = "gguf_chat_prefs"
    private val KEY_MODELS = "models_json"
    private val KEY_SETTINGS = "settings_json"
    private val MIN_STORAGE_MB = 500

    private val mutex = Mutex()
    private var handle: Long = -1L
    private var loadedModelPath: String = ""
    private var loadedArchitecture: String = "Unknown"
    private val _contextSize = MutableStateFlow(0)
    val contextSize: StateFlow<Int> = _contextSize.asStateFlow()

    private val _state = MutableStateFlow(ModelState.UNLOADED)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    // True when an mmproj is attached -> image input is available
    private val _mmprojLoaded = MutableStateFlow(false)
    val mmprojLoaded: StateFlow<Boolean> = _mmprojLoaded.asStateFlow()
    val hasVision: Boolean get() = handle > 0 && _mmprojLoaded.value

    /** Context length actually used by the loaded model (falls back to the setting). */
    val effectiveContextLength: Int
        get() = if (_contextSize.value > 0) _contextSize.value else _settings.value.contextLength

    // Name of the currently loaded projector (for status display)
    private val _mmprojName = MutableStateFlow("")
    val mmprojName: StateFlow<String> = _mmprojName.asStateFlow()

    /** True when [path] is the currently loaded model file. */
    fun isLoadedPath(path: String): Boolean = handle > 0 && loadedModelPath == path

    private val _modelName = MutableStateFlow("")
    val modelName: StateFlow<String> = _modelName.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _progressMessage = MutableStateFlow("")
    val progressMessage: StateFlow<String> = _progressMessage.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private val _downloadMessage = MutableStateFlow("")
    val downloadMessage: StateFlow<String> = _downloadMessage.asStateFlow()

    @Volatile
    private var cancelDownloadFlag = false

    private val _models = MutableStateFlow<List<ModelInfo>>(emptyList())
    val models: StateFlow<List<ModelInfo>> = _models.asStateFlow()

    private val _settings = MutableStateFlow(LlmSettings())
    val settings: StateFlow<LlmSettings> = _settings.asStateFlow()

    val isLoaded: Boolean get() = handle > 0 && _state.value == ModelState.LOADED
    val isAvailable: Boolean get() = try { LocalLlmNative; true } catch (_: Throwable) { false }

    private fun l(key: String): String =
        com.devhorizon.online.ggufchat.ui.theme.Localization.getString(key, _settings.value.appLanguage)

    init {
        loadModelsFromPrefs()
        loadSettingsFromPrefs()
    }

    // ==================== Model Management ====================

    private fun loadModelsFromPrefs() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_MODELS, null) ?: return
        try {
            val arr = JSONArray(json)
            val list = mutableListOf<ModelInfo>()
            var changed = false
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                var m = ModelInfo(
                    name = o.getString("name"),
                    path = o.getString("path"),
                    sizeBytes = o.optLong("sizeBytes", 0),
                    architecture = o.optString("architecture", "Unknown"),
                    contextLength = o.optInt("contextLength", 4096),
                    tensorCount = o.optLong("tensorCount", 0),
                    addedAt = o.optLong("addedAt", System.currentTimeMillis()),
                    mmprojPath = o.optString("mmprojPath", "").ifBlank { null }
                )

                // Drop entries whose file was deleted outside the app.
                val file = File(m.path)
                if (!file.exists()) {
                    Log.w(TAG, "Model file missing, dropping entry: ${m.name}")
                    changed = true
                    continue
                }

                // Refresh architecture for entries imported before the GGUF
                // metadata parser was fixed (they were stored as "Unknown").
                if (m.architecture == "Unknown") {
                    try {
                        val info = GgufReader.readHeadersFromFile(file)
                        if (info.isValid && info.modelArchitecture != "Unknown") {
                            m = m.copy(
                                architecture = info.modelArchitecture,
                                contextLength = info.contextLength
                            )
                            changed = true
                        }
                    } catch (_: Exception) { /* keep the entry as-is */ }
                }

                // A projector only makes sense for a vision-capable architecture.
                // Drop a stale binding left on a text model by the old auto-attach
                // behaviour (it wasted ~1.3 GB RAM and mis-reported vision status).
                if (m.mmprojPath != null &&
                    !ModelCompatibility.isVisionArch(m.architecture)
                ) {
                    m = m.copy(mmprojPath = null)
                    changed = true
                }

                list.add(m)
            }
            _models.value = list
            if (changed) saveModelsToPrefs()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load models from prefs", e)
        }
    }

    private fun saveModelsToPrefs() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = JSONArray()
        for (m in _models.value) {
            val o = JSONObject()
            o.put("name", m.name)
            o.put("path", m.path)
            o.put("sizeBytes", m.sizeBytes)
            o.put("architecture", m.architecture)
            o.put("contextLength", m.contextLength)
            o.put("tensorCount", m.tensorCount)
            o.put("addedAt", m.addedAt)
            o.put("mmprojPath", m.mmprojPath ?: "")
            arr.put(o)
        }
        prefs.edit().putString(KEY_MODELS, arr.toString()).apply()
    }

    private fun loadSettingsFromPrefs() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_SETTINGS, null) ?: return
        try {
            val o = JSONObject(json)
            _settings.value = LlmSettings(
                systemPrompt = o.optString("systemPrompt", "You are a helpful assistant."),
                temperature = o.optDouble("temperature", 0.7).toFloat(),
                topP = o.optDouble("topP", 0.9).toFloat(),
                maxTokens = o.optInt("maxTokens", 1024),
                contextLength = o.optInt("contextLength", 4096),
                threads = o.optInt("threads", 4),
                autoSuggest = o.optBoolean("autoSuggest", true),
                suggestionCount = o.optInt("suggestionCount", 4),
                suggestionDocTokens = o.optInt("suggestionDocTokens", 768),
                ttsEnabled = o.optBoolean("ttsEnabled", true),
                ttsLanguage = o.optString("ttsLanguage", "en"),
                ttsRate = o.optDouble("ttsRate", 1.0).toFloat(),
                sttLanguage = o.optString("sttLanguage", "en"),
                appLanguage = o.optString("appLanguage", "en"),
                appTheme = o.optString("appTheme", "light"),
                answerLanguage = o.optString("answerLanguage", "auto"),
                inputPosition = o.optString("inputPosition", "bottom"),
                suggestionsLayout = o.optString("suggestionsLayout", "wrap")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load settings from prefs", e)
        }
    }

    fun saveSettings(newSettings: LlmSettings) {
        _settings.value = newSettings
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val o = JSONObject()
        o.put("systemPrompt", newSettings.systemPrompt)
        o.put("temperature", newSettings.temperature.toDouble())
        o.put("topP", newSettings.topP.toDouble())
        o.put("maxTokens", newSettings.maxTokens)
        o.put("contextLength", newSettings.contextLength)
        o.put("threads", newSettings.threads)
        o.put("autoSuggest", newSettings.autoSuggest)
        o.put("suggestionCount", newSettings.suggestionCount)
        o.put("suggestionDocTokens", newSettings.suggestionDocTokens)
        o.put("ttsEnabled", newSettings.ttsEnabled)
        o.put("ttsLanguage", newSettings.ttsLanguage)
        o.put("ttsRate", newSettings.ttsRate.toDouble())
        o.put("sttLanguage", newSettings.sttLanguage)
        o.put("appLanguage", newSettings.appLanguage)
        o.put("appTheme", newSettings.appTheme)
        o.put("answerLanguage", newSettings.answerLanguage)
        o.put("inputPosition", newSettings.inputPosition)
        o.put("suggestionsLayout", newSettings.suggestionsLayout)
        prefs.edit().putString(KEY_SETTINGS, o.toString()).apply()
    }

    fun getGgufInfo(uri: Uri): GgufInfo = GgufReader.readHeaders(context, uri)

    fun getGgufInfo(file: File): GgufInfo = GgufReader.readHeadersFromFile(file)

    suspend fun importModel(uri: Uri, displayName: String? = null): Result<ModelInfo> = withContext(Dispatchers.IO) {
        try {
            val ggufInfo = GgufReader.readHeaders(context, uri)
            if (!ggufInfo.isValid) {
                return@withContext Result.failure(Exception(ggufInfo.error ?: l("invalid_gguf")))
            }
            if (!ModelCompatibility.isArchSupported(ggufInfo.modelArchitecture)) {
                return@withContext Result.failure(
                    Exception("${l("unsupported_arch")}: ${ggufInfo.modelArchitecture}")
                )
            }

            val name = displayName ?: uri.lastPathSegment?.substringAfterLast('/') ?: "model.gguf"
            if (!ModelCompatibility.isModelFileName(name)) {
                return@withContext Result.failure(Exception(l("not_a_model")))
            }
            val modelsDir = File(context.filesDir, "models")
            if (!modelsDir.exists()) modelsDir.mkdirs()

            // Check storage
            val stat = StatFs(modelsDir.path)
            val availableMB = stat.availableBytes / (1024 * 1024)
            if (availableMB < MIN_STORAGE_MB) {
                return@withContext Result.failure(Exception(l("no_storage")))
            }

            val destFile = File(modelsDir, name)
            if (destFile.exists()) {
                return@withContext Result.failure(Exception(l("model_exists")))
            }

            // Copy with progress
            _progress.value = 0f
            _progressMessage.value = l("copying_model")
            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var totalRead = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        totalRead += read
                        // We don't know total size from URI easily, so just show MB copied
                        _progressMessage.value = "${l("copying_mb")} ${totalRead / (1024 * 1024)} MB"
                    }
                }
            } ?: return@withContext Result.failure(Exception(l("error_msg")))

            val modelInfo = ModelInfo(
                name = name,
                path = destFile.absolutePath,
                sizeBytes = destFile.length(),
                architecture = ggufInfo.modelArchitecture,
                contextLength = ggufInfo.contextLength,
                tensorCount = ggufInfo.tensorCount
            )

            _models.value = _models.value + modelInfo
            saveModelsToPrefs()
            _progress.value = 1f
            _progressMessage.value = l("model_imported")
            Result.success(modelInfo)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import model", e)
            Result.failure(e)
        }
    }

    suspend fun cancelDownload() {
        cancelDownloadFlag = true
    }

    /**
     * Downloads a GGUF model from [url] into the app models dir, validates it,
     * and registers it like a regular import.
     */
    suspend fun downloadModelFromUrl(url: String, displayName: String? = null): Result<ModelInfo> = withContext(Dispatchers.IO) {
        var tempFile: File? = null
        try {
            val trimmed = url.trim()
            if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                return@withContext Result.failure(Exception(l("invalid_url")))
            }

            val modelsDir = File(context.filesDir, "models")
            if (!modelsDir.exists()) modelsDir.mkdirs()

            val fileName = (displayName?.takeIf { it.isNotBlank() }
                ?: trimmed.substringAfterLast('/').substringBefore('?').takeIf { it.isNotBlank() }
                ?: "model_${System.currentTimeMillis()}.gguf")
                .let { if (it.endsWith(".gguf")) it else "$it.gguf" }

            if (!ModelCompatibility.isModelFileName(fileName)) {
                return@withContext Result.failure(Exception(l("not_a_model")))
            }

            val destFile = File(modelsDir, fileName)
            if (destFile.exists()) {
                return@withContext Result.failure(Exception(l("model_exists")))
            }

            cancelDownloadFlag = false
            _isDownloading.value = true
            _downloadProgress.value = 0f
            _downloadMessage.value = l("connecting")

            val conn = URL(trimmed).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            conn.connect()

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                return@withContext Result.failure(Exception("HTTP ${conn.responseCode}"))
            }

            // Storage pre-check when server reports size
            val totalBytes = conn.contentLengthLong
            if (totalBytes > 0) {
                val stat = StatFs(modelsDir.path)
                if (stat.availableBytes < totalBytes + MIN_STORAGE_MB * 1024L * 1024L) {
                    conn.disconnect()
                    return@withContext Result.failure(Exception(l("no_storage")))
                }
            }

            val tmp = File(modelsDir, "$fileName.part")
            tempFile = tmp

            var totalRead = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        if (cancelDownloadFlag) {
                            conn.disconnect()
                            tmp.delete()
                            return@withContext Result.failure(Exception(l("cancelled")))
                        }
                        output.write(buffer, 0, read)
                        totalRead += read
                        if (totalBytes > 0) {
                            _downloadProgress.value = (totalRead.toFloat() / totalBytes).coerceIn(0f, 1f)
                            _downloadMessage.value = "${l("downloading_mb")} ${totalRead / (1024 * 1024)} / ${totalBytes / (1024 * 1024)} MB"
                        } else {
                            _downloadMessage.value = "${l("downloading_mb")} ${totalRead / (1024 * 1024)} MB"
                        }
                    }
                }
            }
            conn.disconnect()

            // Verify the download is complete — a dropped connection looks like EOF
            if (totalBytes > 0 && totalRead != totalBytes) {
                tmp.delete()
                return@withContext Result.failure(
                    Exception("${l("incomplete_download")}: $totalRead / $totalBytes B")
                )
            }

            // Validate GGUF headers before registering
            _downloadMessage.value = l("validating_file")
            val ggufInfo = GgufReader.readHeadersFromFile(tmp)
            if (!ggufInfo.isValid) {
                tmp.delete()
                return@withContext Result.failure(Exception(ggufInfo.error ?: l("invalid_gguf")))
            }
            if (!ModelCompatibility.isArchSupported(ggufInfo.modelArchitecture)) {
                tmp.delete()
                return@withContext Result.failure(
                    Exception("${l("unsupported_arch")}: ${ggufInfo.modelArchitecture}")
                )
            }

            if (!tmp.renameTo(destFile)) {
                tmp.copyTo(destFile, overwrite = true)
                tmp.delete()
            }

            val modelInfo = ModelInfo(
                name = fileName,
                path = destFile.absolutePath,
                sizeBytes = destFile.length(),
                architecture = ggufInfo.modelArchitecture,
                contextLength = ggufInfo.contextLength,
                tensorCount = ggufInfo.tensorCount
            )

            _models.value = _models.value + modelInfo
            saveModelsToPrefs()
            _downloadProgress.value = 1f
            _downloadMessage.value = l("download_complete")
            Result.success(modelInfo)
        } catch (e: Exception) {
            tempFile?.delete()
            Log.e(TAG, "Failed to download model", e)
            Result.failure(e)
        } finally {
            _isDownloading.value = false
        }
    }

    suspend fun loadModel(modelInfo: ModelInfo, mmprojPath: String? = null): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                // The file may have been deleted outside the app.
                val modelFile = File(modelInfo.path)
                if (!modelFile.exists()) {
                    _state.value = ModelState.ERROR
                    _progressMessage.value = "${l("model_file_missing")}: ${modelInfo.name}"
                    // Drop the stale entry from the list
                    _models.value = _models.value.filterNot { it.path == modelInfo.path }
                    saveModelsToPrefs()
                    return@withLock false
                }

                // Check architecture support — covers models imported before
                // the validation existed (their stored arch may be "Unknown")
                var arch = modelInfo.architecture
                if (arch == "Unknown" || arch.isBlank()) {
                    arch = GgufReader.readHeadersFromFile(modelFile).modelArchitecture
                }
                if (arch != "Unknown" && !ModelCompatibility.isArchSupported(arch)) {
                    _state.value = ModelState.ERROR
                    _progressMessage.value = "${l("unsupported_arch")}: $arch"
                    return@withLock false
                }
                loadedArchitecture = arch.lowercase()

                // Reject models that clearly cannot fit into device RAM
                val totalRamBytes = android.app.ActivityManager.MemoryInfo().let { mi ->
                    (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
                        .getMemoryInfo(mi)
                    mi.totalMem
                }
                if (totalRamBytes > 0 && modelInfo.sizeBytes > totalRamBytes * 3 / 4) {
                    _state.value = ModelState.ERROR
                    _progressMessage.value = "${l("model_too_large")}: " +
                            "${formatBytes(modelInfo.sizeBytes)} / ${formatBytes(totalRamBytes)} ${l("ram")}"
                    return@withLock false
                }

                // Unload current model first
                if (handle > 0) {
                    LocalLlmNative.nativeFreeModel(handle)
                    handle = -1L
                }

                _state.value = ModelState.LOADING
                _progress.value = 0.7f
                _progressMessage.value = l("loading_memory")

                val s = _settings.value
                // Apply the requested context size but never exceed what the model was
                // trained for (its GGUF context length). This lets every model use its
                // own maximum while keeping the setting global.
                val modelMaxCtx = modelInfo.contextLength.coerceAtLeast(512)
                val effectiveCtx = s.contextLength.coerceIn(512, modelMaxCtx)
                val newHandle = LocalLlmNative.nativeLoadModel(
                    modelInfo.path,
                    s.threads,
                    effectiveCtx,
                    object : LocalLlmNative.ProgressCallback {
                        override fun onProgress(progress: Float) {
                            _progress.value = 0.7f + progress * 0.3f
                        }
                    }
                )

                if (newHandle < 0) {
                    _state.value = ModelState.ERROR
                    _progressMessage.value = l("load_model_failed")
                    return@withLock false
                }

                handle = newHandle
                loadedModelPath = modelInfo.path
                _contextSize.value = effectiveCtx
                _mmprojLoaded.value = false
                val isVision = ModelCompatibility.isVisionArch(arch)
                val requested = mmprojPath?.takeIf { it.isNotBlank() && File(it).exists() }
                // Only a vision-capable architecture may carry a projector; an
                // explicit or stale binding on a text model is ignored and dropped.
                val effectiveMmproj =
                    if (isVision) (requested ?: autoFindMmproj(modelInfo.path)) else null
                if (!isVision && !modelInfo.mmprojPath.isNullOrBlank()) {
                    // Remove the stale binding so the model list stops showing it.
                    setModelMmproj(modelInfo.path, null)
                }
                if (!effectiveMmproj.isNullOrBlank()) {
                    _progressMessage.value = l("loading_projector")
                    val ok = LocalLlmNative.nativeLoadMmproj(newHandle, effectiveMmproj)
                    _mmprojLoaded.value = ok
                    _mmprojName.value = if (ok) File(effectiveMmproj).name else ""
                    if (ok) {
                        Log.i(TAG, "mmproj loaded: $effectiveMmproj")
                        if (effectiveMmproj != modelInfo.mmprojPath) {
                            setModelMmproj(modelInfo.path, effectiveMmproj)
                        }
                    } else {
                        Log.e(TAG, "mmproj load failed: $effectiveMmproj")
                    }
                }
                _modelName.value = modelInfo.name
                _state.value = ModelState.LOADED
                _progress.value = 1f
                _progressMessage.value = l("ready")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load model", e)
                _state.value = ModelState.ERROR
                _progressMessage.value = e.message ?: l("error_msg")
                false
            }
        }
    }

    fun unloadModel() {
        if (handle > 0) {
            LocalLlmNative.nativeFreeModel(handle)
            handle = -1L
        }
        _mmprojLoaded.value = false
        _mmprojName.value = ""
        _state.value = ModelState.UNLOADED
        _modelName.value = ""
        _progress.value = 0f
        _progressMessage.value = ""
        loadedArchitecture = "Unknown"
        _contextSize.value = 0
    }

    /** Attach an mmproj projector to the currently loaded model (enables images). */
    fun loadMmproj(path: String): Boolean {
        if (handle <= 0) return false
        val ok = LocalLlmNative.nativeLoadMmproj(handle, path)
        _mmprojLoaded.value = ok
        _mmprojName.value = if (ok) File(path).name else ""
        Log.i(TAG, "loadMmproj($path) -> $ok")
        return ok
    }

    /** Remember which mmproj belongs to a model (persisted in prefs). */
    fun setModelMmproj(modelPath: String, mmprojPath: String?) {
        _models.value = _models.value.map {
            if (it.path == modelPath) it.copy(mmprojPath = mmprojPath) else it
        }
        saveModelsToPrefs()
    }

    /** Looks for a sibling mmproj-*.gguf next to the model file. */
    private fun autoFindMmproj(modelPath: String): String? = try {
        val dir = File(modelPath).parentFile ?: return null
        dir.listFiles()?.firstOrNull {
            val n = it.name.lowercase()
            n.endsWith(".gguf") && (n.startsWith("mmproj") || n.contains(".mmproj."))
        }?.absolutePath
    } catch (e: Exception) {
        null
    }

    /** Imports a projector file (mmproj) picked by the user. */
    suspend fun importMmproj(uri: Uri, displayName: String? = null): Result<String> = withContext(Dispatchers.IO) {
        try {
            val name = displayName
                ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: "mmproj.gguf"
            if (!ModelCompatibility.isProjectorFileName(name)) {
                return@withContext Result.failure(Exception(l("not_a_projector")))
            }
            val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
            val dest = File(modelsDir, name)
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            } ?: return@withContext Result.failure(Exception(l("error_msg")))
            _progressMessage.value = l("projector_imported")
            Result.success(dest.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import mmproj", e)
            Result.failure(e)
        }
    }

    /** Downloads an mmproj projector from a direct URL into the models dir. */
    suspend fun downloadMmprojFromUrl(url: String, displayName: String? = null): Result<String> = withContext(Dispatchers.IO) {
        try {
            val trimmed = url.trim()
            if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                return@withContext Result.failure(Exception(l("invalid_url")))
            }
            val rawName = displayName?.takeIf { it.isNotBlank() }
                ?: trimmed.substringAfterLast('/').substringBefore('?')
            val name = if (rawName.endsWith(".gguf", ignoreCase = true)) rawName else "$rawName.gguf"
            if (!ModelCompatibility.isProjectorFileName(name)) {
                return@withContext Result.failure(Exception(l("not_a_projector")))
            }
            val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
            val dest = File(modelsDir, name)

            _isDownloading.value = true
            _downloadProgress.value = 0f
            _downloadMessage.value = name
            cancelDownloadFlag = false
            try {
                val conn = (URL(trimmed).openConnection() as HttpURLConnection)
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.connect()
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var readTotal = 0L
                        var r: Int
                        while (input.read(buf).also { r = it } != -1) {
                            if (cancelDownloadFlag) throw Exception(l("cancelled"))
                            out.write(buf, 0, r)
                            readTotal += r
                            if (total > 0) {
                                _downloadProgress.value = (readTotal.toFloat() / total).coerceIn(0f, 1f)
                            }
                        }
                    }
                }
                conn.disconnect()
            } finally {
                _isDownloading.value = false
            }
            Result.success(dest.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download mmproj", e)
            Result.failure(e)
        }
    }

    fun deleteModel(modelInfo: ModelInfo) {
        if (loadedModelPath == modelInfo.path) {
            unloadModel()
        }
        File(modelInfo.path).delete()
        _models.value = _models.value.filter { it.path != modelInfo.path }
        saveModelsToPrefs()
    }

    // ==================== Chat Generation ====================

    fun buildMessagesJson(messages: List<com.devhorizon.online.ggufchat.data.model.ChatMessage>): String {
        val arr = JSONArray()
        for (m in messages) {
            val o = JSONObject()
            o.put("role", m.role)
            o.put("content", m.content)
            arr.put(o)
        }
        return arr.toString()
    }

    suspend fun generate(
        messages: List<com.devhorizon.online.ggufchat.data.model.ChatMessage>,
        onToken: (String) -> Unit,
        docsBlock: String? = null
    ): String = withContext(Dispatchers.IO) {
        if (!isLoaded) return@withContext ""

        val s = _settings.value
        // Language directive + session documents as a leading system turn.
        // The user's custom system prompt is applied on the manual-fallback path
        // only, matching the previous behaviour.
        val langBlock = languageReminder() + "\n" + languageDirective()
        val templatedSystem = buildString {
            append(langBlock)
            if (!docsBlock.isNullOrBlank()) {
                append("\n\n").append(docsBlock)
            }
        }
        val manualSystem = buildString {
            if (s.systemPrompt.isNotBlank()) append(s.systemPrompt.trim()).append("\n\n")
            append(langBlock)
            if (!docsBlock.isNullOrBlank()) {
                append("\n\n").append(docsBlock)
            }
        }
        val reinforced = if (messages.isEmpty()) {
            messages
        } else {
            val idx = messages.indexOfLast { it.role == "user" }
            if (idx >= 0) {
                messages.toMutableList().also {
                    val r = languageReminder()
                    it[idx] = it[idx].copy(
                        content = r + "\n\n" + it[idx].content + "\n\n" + r
                    )
                }
            } else messages
        }
        val effective = listOf(
            com.devhorizon.online.ggufchat.data.model.ChatMessage(role = "system", content = templatedSystem)
        ) + reinforced
        val roles = effective.map { it.role }.toTypedArray()
        val contents = effective.map { it.content }.toTypedArray()

        // Apply chat template
        val templated = LocalLlmNative.nativeApplyChatTemplate(handle, roles, contents, "")
        val prompt = if (templated.startsWith("[error:")) {
            // Fallback: build prompt manually, folding everything into the system
            // prompt (buildManualPrompt has no system-role message).
            buildManualPrompt(reinforced, manualSystem)
        } else {
            templated
        }

        val result = StringBuilder()
        val callback = object : LocalLlmNative.TokenCallback {
            override fun onToken(token: String) {
                result.append(token)
                onToken(token)
            }
            override fun onComplete(fullResponse: String) {
                // Already handled via onToken
            }
        }

        LocalLlmNative.nativeGenerateStreaming(
            handle,
            prompt,
            s.maxTokens,
            s.temperature,
            s.topP,
            null,
            callback
        )

        result.toString()
    }

    /** Cached mtmd media marker (e.g. "<__media__>"). */
    private val mediaMarker: String by lazy {
        try { LocalLlmNative.nativeMediaMarker() } catch (e: Throwable) { "<__media__>" }
    }

    /**
     * Image-conditioned generation. Decodes the image to raw RGB and routes it
     * through mtmd (prompt contains the media marker where the image goes).
     */
    suspend fun generateVision(
        messages: List<com.devhorizon.online.ggufchat.data.model.ChatMessage>,
        imagePath: String,
        docsBlock: String? = null,
        onToken: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        if (!isLoaded || !_mmprojLoaded.value) return@withContext ""

        val bmp0 = android.graphics.BitmapFactory.decodeFile(imagePath) ?: return@withContext ""
        // Cap the long side to bound the number of image tokens.
        val maxSide = 1024
        val scale = minOf(1f, maxSide.toFloat() / maxOf(bmp0.width, bmp0.height))
        val bmp = if (scale < 1f) {
            android.graphics.Bitmap.createScaledBitmap(
                bmp0,
                (bmp0.width * scale).toInt().coerceAtLeast(1),
                (bmp0.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else bmp0
        if (bmp !== bmp0) bmp0.recycle()
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        bmp.recycle()
        val rgb = ByteArray(w * h * 3)
        var k = 0
        for (px in pixels) {
            rgb[k++] = ((px shr 16) and 0xFF).toByte()
            rgb[k++] = ((px shr 8) and 0xFF).toByte()
            rgb[k++] = (px and 0xFF).toByte()
        }

        val s = _settings.value
        val systemContent = buildString {
            if (s.systemPrompt.isNotBlank()) append(s.systemPrompt.trim()).append("\n\n")
            append(languageReminder())
            append("\n")
            append(languageDirective())
            if (!docsBlock.isNullOrBlank()) append("\n\n").append(docsBlock)
        }
        // Small vision models tend to mirror the question's language and ignore a
        // system-level instruction, so reinforce the target language on the last
        // user turn exactly like the text path does.
        val reinforcedVision = if (messages.isEmpty()) messages else {
            val idx = messages.indexOfLast { it.role == "user" }
            if (idx >= 0) {
                messages.toMutableList().also {
                    val r = languageReminder()
                    it[idx] = it[idx].copy(content = r + "\n\n" + it[idx].content + "\n\n" + r)
                }
            } else messages
        }
        val prompt = buildVisionPrompt(reinforcedVision, systemContent)

        val result = StringBuilder()
        val callback = object : LocalLlmNative.TokenCallback {
            override fun onToken(token: String) {
                result.append(token)
                onToken(token)
            }
            override fun onComplete(fullResponse: String) {}
        }
        LocalLlmNative.nativeGenerateStreamingVision(
            handle, prompt, rgb, w, h, s.maxTokens, s.temperature, s.topP, null, callback
        )
        result.toString()
    }

    /** Vision prompt: model-family aware, with the mtmd media marker. */
    private fun buildVisionPrompt(
        messages: List<com.devhorizon.online.ggufchat.data.model.ChatMessage>,
        systemContent: String
    ): String {
        val arch = loadedArchitecture.lowercase()
        return if (arch.startsWith("gemma")) {
            buildGemmaTurnPrompt(messages, systemContent, withMarker = true)
        } else {
            buildChatmlPrompt(messages, systemContent, withMarker = true)
        }
    }

    /** Qwen2-VL/LLaVA ChatML; the current user turn carries the media marker. */
    private fun buildChatmlPrompt(
        messages: List<com.devhorizon.online.ggufchat.data.model.ChatMessage>,
        systemContent: String,
        withMarker: Boolean
    ): String {
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n").append(systemContent).append("<|im_end|>\n")
        val lastUserIdx = messages.indexOfLast { it.role == "user" }
        messages.forEachIndexed { i, m ->
            val role = if (m.role == "assistant") "assistant" else "user"
            sb.append("<|im_start|>").append(role).append("\n")
            if (withMarker && i == lastUserIdx) sb.append(mediaMarker).append("\n")
            sb.append(m.content).append("<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    /** Gemma 3/4 turns: <|turn>role\n…<turn|>\n (assistant role is "model"). */
    private fun buildGemmaTurnPrompt(
        messages: List<com.devhorizon.online.ggufchat.data.model.ChatMessage>,
        systemContent: String,
        withMarker: Boolean
    ): String {
        val sb = StringBuilder()
        sb.append("<|turn>system\n").append(systemContent).append("<turn|>\n")
        val lastUserIdx = messages.indexOfLast { it.role == "user" }
        messages.forEachIndexed { i, m ->
            val role = if (m.role == "assistant") "model" else "user"
            sb.append("<|turn>").append(role).append("\n")
            if (withMarker && i == lastUserIdx) sb.append(mediaMarker).append("\n")
            sb.append(m.content).append("<turn|>\n")
        }
        sb.append("<|turn>model\n")
        return sb.toString()
    }

    fun cancelGeneration() {
        if (handle > 0) {
            LocalLlmNative.nativeCancel(handle)
        }
    }

    /** Effective language code for replies/suggestions: explicit choice or app language. */
    private fun resolveAnswerLanguageCode(): String {
        val s = _settings.value
        val code = s.answerLanguage
        return if (code.isBlank() || code == "auto") s.appLanguage else code
    }

    private fun answerLanguageName(): String = when (resolveAnswerLanguageCode()) {
        "ru" -> "Russian"
        "de" -> "German"
        else -> "English"
    }

    /** Hard instruction so replies and suggestions ignore message/document language. */
    private fun languageDirective(): String =
        "Always write your entire reply in ${answerLanguageName()}. " +
            "This language is fixed by the app settings: use it even if the user's message " +
            "or any attached documents are written in a different language."

    /** Same instruction in the target language, appended to the last user turn. */
    private fun languageReminder(): String = when (resolveAnswerLanguageCode()) {
        "ru" -> "Отвечай только на русском языке, независимо от языка вопроса и прикреплённых документов."
        "de" -> "Antworte nur auf Deutsch, unabhängig von der Sprache der Frage oder der angehängten Dokumente."
        else -> "Reply in English only, regardless of the language of the question or attached documents."
    }

    private fun buildManualPrompt(
        messages: List<com.devhorizon.online.ggufchat.data.model.ChatMessage>,
        systemPrompt: String
    ): String {
        val arch = loadedArchitecture.lowercase()
        val sb = StringBuilder()

        when {
            // Gemma 3/4 use the <|turn> format (different from gemma1/2).
            arch.startsWith("gemma4") || arch.startsWith("gemma3") -> {
                return buildGemmaTurnPrompt(messages, systemPrompt, withMarker = false)
            }
            // Gemma / Gemma2: <start_of_turn>user ... <end_of_turn>. No system role.
            arch.startsWith("gemma") -> {
                var sys = systemPrompt.trim()
                for (m in messages) {
                    val role = if (m.role == "assistant") "model" else "user"
                    var content = m.content
                    if (role == "user" && sys.isNotEmpty()) {
                        content = "$sys\n\n$content"
                        sys = ""
                    }
                    sb.append("<start_of_turn>").append(role).append("\n")
                        .append(content).append("<end_of_turn>\n")
                }
                sb.append("<start_of_turn>model\n")
            }

            // Qwen / Qwen2 / Qwen2.5: ChatML
            arch.startsWith("qwen") -> {
                if (systemPrompt.isNotBlank()) {
                    sb.append("<|im_start|>system\n").append(systemPrompt).append("<|im_end|>\n")
                }
                for (m in messages) {
                    val role = if (m.role == "assistant") "assistant" else "user"
                    sb.append("<|im_start|>").append(role).append("\n")
                        .append(m.content).append("<|im_end|>\n")
                }
                sb.append("<|im_start|>assistant\n")
            }

            // Llama 3 / 3.1 / 3.2
            arch == "llama" -> {
                if (systemPrompt.isNotBlank()) {
                    sb.append("<|start_header_id|>system<|end_header_id|>\n\n")
                        .append(systemPrompt).append("<|eot_id|>")
                }
                for (m in messages) {
                    val role = if (m.role == "assistant") "assistant" else "user"
                    sb.append("<|start_header_id|>").append(role).append("<|end_header_id|>\n\n")
                        .append(m.content).append("<|eot_id|>")
                }
                sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
            }

            // Phi-3 / Phi-3.5
            arch.startsWith("phi") -> {
                if (systemPrompt.isNotBlank()) {
                    sb.append("<|system|>\n").append(systemPrompt).append("<|end|>\n")
                }
                for (m in messages) {
                    val role = if (m.role == "assistant") "assistant" else "user"
                    sb.append("<|").append(role).append("|>\n")
                        .append(m.content).append("<|end|>\n")
                }
                sb.append("<|assistant|>\n")
            }

            // Generic fallback
            else -> {
                if (systemPrompt.isNotBlank()) {
                    sb.append("<|system|>\n").append(systemPrompt).append("\n</s>\n")
                }
                for (m in messages) {
                    val role = if (m.role == "assistant") "assistant" else "user"
                    sb.append("<|").append(role).append("|>\n")
                        .append(m.content).append("\n</s>\n")
                }
                sb.append("<|assistant|>\n")
            }
        }
        return sb.toString()
    }

    // ==================== Suggestions ====================

    suspend fun generateSuggestions(
        messages: List<com.devhorizon.online.ggufchat.data.model.ChatMessage>,
        count: Int,
        excludeSuggestions: Set<String> = emptySet(),
        temperature: Float = 0.7f,
        docsBlock: String? = null,
        onPartial: ((List<String>, String?) -> Unit)? = null
    ): List<String> = withContext(Dispatchers.IO) {
        if (!isLoaded) return@withContext emptyList()

        val lastResponse = messages.lastOrNull { it.role == "assistant" }?.content
        val hasDocs = !docsBlock.isNullOrBlank()
        if (lastResponse.isNullOrBlank() && !hasDocs) return@withContext emptyList()

        val langCode = resolveAnswerLanguageCode()
        val prompt = buildSuggestionPrompt(count, hasDocs, lastResponse, docsBlock, excludeSuggestions)

        val templated = LocalLlmNative.nativeApplyChatTemplate(
            handle,
            arrayOf("user"),
            arrayOf(prompt),
            ""
        )
        val finalPrompt = if (templated.startsWith("[error:")) {
            buildManualPrompt(
                listOf(
                    com.devhorizon.online.ggufchat.data.model.ChatMessage(
                        role = "user", content = prompt
                    )
                ),
                ""
            )
        } else templated

        val result = StringBuilder()
        val callback = object : LocalLlmNative.TokenCallback {
            override fun onToken(token: String) {
                result.append(token)
                if (onPartial != null) {
                    val (complete, pending) = parsePartial(result.toString())
                    val okComplete = complete.filter { suggestionMatchesLanguage(it, langCode) }
                    val okPending = pending?.takeIf { suggestionMatchesLanguage(it, langCode) }
                    onPartial.invoke(okComplete, okPending)
                }
            }
            override fun onComplete(fullResponse: String) {}
        }

        LocalLlmNative.nativeGenerateStreaming(
            handle, finalPrompt, 256, temperature, 0.9f, null, callback
        )

        val parsed = parseSuggestions(result.toString())
            .filter { suggestionMatchesLanguage(it, langCode) }
        Log.d(TAG, "Suggestion raw: <<<$result>>> -> ${parsed.size}: $parsed")
        parsed
    }

    /** Per-language fragments for the suggestion prompt. Writing the whole prompt
     *  in the target language makes small models mirror it instead of answering in
     *  the question's language. */
    private data class SuggestionStrings(
        val introBoth: String,
        val introDocs: String,
        val introResp: String,
        val shortRule: String,
        val langRule: String,
        val jsonRule: String,
        val noMdRule: String,
        val excludeRule: String,
        val docsHeader: String,
        val aiHeader: String
    )

    private fun buildSuggestionPrompt(
        count: Int,
        hasDocs: Boolean,
        lastResponse: String?,
        docsBlock: String?,
        excludeSuggestions: Set<String>
    ): String {
        val code = resolveAnswerLanguageCode()
        val h = when (code) {
            "ru" -> SuggestionStrings(
                introBoth = "На основе следующего ответа ИИ и прикреплённых документов сгенерируй $count разных, уместных и интересных вопросов или подсказок, которые пользователь мог бы задать далее.",
                introDocs = "На основе прикреплённых документов сгенерируй $count разных, уместных и интересных вопросов или подсказок, которые пользователь мог бы задать о них.",
                introResp = "На основе следующего ответа ИИ сгенерируй $count разных, уместных и интересных вопросов или подсказок, которые пользователь мог бы задать далее.",
                shortRule = "Каждая подсказка — короткая (не более 8 слов).",
                langRule = "Пиши каждую подсказку ТОЛЬКО на русском языке, даже если документы или ответ ИИ на другом языке.",
                jsonRule = "Ответь ТОЛЬКО сырым JSON-массивом строк, например: [\"Подсказка 1\", \"Подсказка 2\"].",
                noMdRule = "Без markdown и без блоков кода.",
                excludeRule = "НЕ предлагай ничего из следующего (уже показано пользователю): ",
                docsHeader = "Прикреплённые документы:",
                aiHeader = "Ответ ИИ:"
            )
            "de" -> SuggestionStrings(
                introBoth = "Erzeuge auf Basis der folgenden KI-Antwort und der angehängten Dokumente $count verschiedene, passende und interessante Folgefragen oder Vorschläge, die der Nutzer als Nächstes stellen könnte.",
                introDocs = "Erzeuge auf Basis der angehängten Dokumente $count verschiedene, passende und interessante Fragen oder Vorschläge, die der Nutzer dazu stellen könnte.",
                introResp = "Erzeuge auf Basis der folgenden KI-Antwort $count verschiedene, passende und interessante Folgefragen oder Vorschläge, die der Nutzer als Nächstes stellen könnte.",
                shortRule = "Jeder Vorschlag ist kurz (höchstens 8 Wörter).",
                langRule = "Schreibe jeden Vorschlag NUR auf Deutsch, auch wenn Dokumente oder die KI-Antwort in einer anderen Sprache sind.",
                jsonRule = "Antworte NUR mit einem rohen JSON-Array aus Strings, z. B.: [\"Vorschlag 1\", \"Vorschlag 2\"].",
                noMdRule = "Ohne Markdown und ohne Codeblöcke.",
                excludeRule = "Schlage nichts von Folgendem vor (dem Nutzer bereits gezeigt): ",
                docsHeader = "Angehängte Dokumente:",
                aiHeader = "KI-Antwort:"
            )
            else -> SuggestionStrings(
                introBoth = "Based on the following AI response and the attached documents, generate $count distinct, highly relevant, and engaging follow-up questions or prompt suggestions that the user might want to ask next.",
                introDocs = "Based on the attached documents, generate $count distinct, highly relevant, and engaging questions or prompt suggestions the user might want to ask about them.",
                introResp = "Based on the following AI response, generate $count distinct, highly relevant, and engaging follow-up questions or prompt suggestions that the user might want to ask next.",
                shortRule = "Keep each suggestion short (under 8 words).",
                langRule = "Write every suggestion in English only, even if the attached documents or the AI response are in another language.",
                jsonRule = "Respond with ONLY a raw JSON array of strings, like: [\"Suggestion 1\", \"Suggestion 2\"].",
                noMdRule = "Do not include markdown formatting or code block tags.",
                excludeRule = "Do NOT suggest any of the following (already shown to the user): ",
                docsHeader = "Attached documents:",
                aiHeader = "AI Response:"
            )
        }
        return buildString {
            append(languageReminder())
            append("\n\n").append(
                when {
                    hasDocs && !lastResponse.isNullOrBlank() -> h.introBoth
                    hasDocs -> h.introDocs
                    else -> h.introResp
                }
            )
            append("\n").append(h.shortRule)
            append("\n").append(h.langRule)
            append("\n").append(h.jsonRule)
            append("\n").append(h.noMdRule)
            if (excludeSuggestions.isNotEmpty()) {
                append("\n").append(h.excludeRule).append(excludeSuggestions.joinToString("; "))
            }
            if (hasDocs) append("\n\n").append(h.docsHeader).append("\n").append(docsBlock)
            if (!lastResponse.isNullOrBlank()) {
                append("\n\n").append(h.aiHeader).append("\n").append(lastResponse)
            }
            // Reinforce at the very end: small models follow the closest instruction.
            append("\n\n").append(languageReminder())
        }
    }

    /** Distinctive German function words (ambiguous English homographs like
     *  "was"/"die"/"man" are deliberately excluded). */
    private val GERMAN_HINT_WORDS = setOf(
        "der", "das", "und", "ist", "sind", "wie", "wer", "wo", "ein", "eine",
        "einen", "für", "mit", "den", "dem", "sich", "nicht", "auch", "kann",
        "kannst", "können", "oder", "aber", "wird", "werden", "haben", "bitte",
        "welche", "welcher", "welches", "warum", "wann", "mehr", "diese",
        "dieser", "dieses", "dein", "deine", "du", "auf", "zur", "zum", "von"
    )

    /** Keeps only suggestions written in the configured answer language, so a
     *  weak model can never leak wrong-language chips into the UI. */
    private fun suggestionMatchesLanguage(text: String, code: String): Boolean {
        val hasCyr = text.any { it.code in 0x0400..0x04FF }
        val hasLatin = text.any { it in 'a'..'z' || it in 'A'..'Z' }
        return when (code.lowercase()) {
            "ru" -> hasCyr
            "en" -> hasLatin && !hasCyr
            "de" -> !hasCyr && (
                text.any { it in "äöüßÄÖÜ" } ||
                    Regex("[A-Za-zÄÖÜäöüß]+").findAll(text)
                        .any { it.value.lowercase() in GERMAN_HINT_WORDS }
                )
            else -> true
        }
    }

    /**
     * Incremental parse used for live streaming: returns the quoted strings that
     * are already closed, plus the text of the string currently being generated
     * (null when none). Handles the common one-array / array-per-line output.
     */
    private fun parsePartial(raw: String): Pair<List<String>, String?> {
        val complete = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
            .findAll(raw)
            .map { unescape(it.groupValues[1]) }
            .filter { it.isNotBlank() }
            .toList()

        var quoteCount = 0
        var lastOpen = -1
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\') { i += 2; continue }
            if (c == '"') { quoteCount++; lastOpen = i }
            i++
        }
        val pending = if (quoteCount % 2 == 1 && lastOpen >= 0) {
            raw.substring(lastOpen + 1).take(160).ifBlank { null }
        } else null

        return complete to pending
    }

    private fun unescape(s: String): String =
        s.replace("\\n", " ")
            .replace("\\t", " ")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")

    /**
     * Suggestions are parsed leniently: small / base models rarely emit a
     * perfectly valid JSON array. Notably, some models emit ONE array per line:
     *   ["a"]
     *   ["b"]
     * Android's JSONArray is lenient and would silently keep only the first one,
     * so parse every bracket block and aggregate.
     */
    private fun parseSuggestions(raw: String): List<String> {
        val text = raw.trim()
            .removeSurrounding("```json", "```")
            .removeSurrounding("```")
            .trim()
        if (text.isEmpty()) return emptyList()

        val out = LinkedHashSet<String>()

        // 1) every [...] block (handles one array, or one array per line)
        for (block in Regex("\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
            try {
                val arr = JSONArray(block.value)
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i, "").trim()
                    if (s.isNotEmpty()) out.add(s)
                }
            } catch (_: Exception) { /* not valid JSON, ignore block */ }
        }
        if (out.isNotEmpty()) return out.toList().take(20)

        // 2) a bare JSON array in extra prose (no brackets matched above)
        try {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "").trim()
                if (s.isNotEmpty()) out.add(s)
            }
            if (out.isNotEmpty()) return out.toList().take(20)
        } catch (_: Exception) { /* fall through */ }

        // 3) any quoted strings
        Regex("\"([^\"]{2,80})\"").findAll(text)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() && !it.equals("json", ignoreCase = true) }
            .forEach { out.add(it) }
        if (out.isNotEmpty()) return out.toList().take(20)

        // 4) plain lines, stripping bullets and numbering
        return text.lines()
            .map { it.trim() }
            .map { it.replace(Regex("^[-*•]\\s*"), "") }
            .map { it.replace(Regex("^\\d+[.)]\\s*"), "") }
            .map { it.trim('"', ' ', '\'', ',', '[', ']') }
            .filter { it.isNotEmpty() && it.length in 2..80 }
            .distinct()
            .take(20)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        else -> "$bytes B"
    }
}
