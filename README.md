# GGUF Chat — Android LLM client

**RU** [Русский](README.ru.md) · **EN** [English](README.md) · **DE** [Deutsch](README.de.md)

🔎 **Live code map:** [sashok53011.github.io/gguf-chat-android](https://sashok53011.github.io/gguf-chat-android/) — interactive 3-column map of the hand-written code (name · technology · exact code lines), collapsible, RU/EN/DE.

---

## 1. What it is

`GGUF Chat` is a native Android app (package `com.devhorizon.online.ggufchat`) that:
- loads local GGUF models and runs generation on CPU through a vendored `llama.cpp`;
- supports **vision** (images) via `mtmd` + an `mmproj` projector (Qwen2-VL, Gemma 4 E2B, …);
- offers document-in-chat, voice input (STT) and text-to-speech (TTS), suggestion chips, Hugging Face model search/download, a RAM monitor and a **Free RAM** button;
- is localized in **en/ru/de** and ships 6 themes.

## 2. Full stack

| Layer | Technology |
|---|---|
| Language | Kotlin `2.2.10` (JVM target 17) |
| Build | Gradle + AGP `9.1.1`, Kotlin Compose plugin |
| UI | Jetpack Compose (BOM `2024.09.00`), Material3, Material Icons (core+extended), Navigation Compose `2.8.9` |
| State | AndroidX Lifecycle/ViewModel `2.8.7`, Kotlin Coroutines/Flow `1.10.2` |
| Data | `org.json` (JSONObject/JSONArray), `SharedPreferences`, session files under `files/sessions/*.json` |
| Network | `HttpURLConnection`, Hugging Face REST API |
| Media | Android `SpeechRecognizer` (STT), `TextToSpeech` (TTS), `ContentResolver`/document picker, `FileProvider` (camera), `BitmapFactory` |
| Native | C++17, JNI, **llama.cpp** (`app/src/main/cpp/llama.cpp-new`) + `tools/mtmd`, CMake `3.22.1`, NDK `28.2.13676358`, `arm64-v8a` only |
| Platform | `minSdk 26`, `targetSdk 36`, `compileSdk 36`, `ANDROID_STL=c++_shared`, native build always `Release` (`-DCMAKE_BUILD_TYPE=Release`) |

Key dependencies (`app/build.gradle.kts`): `androidx.compose.material3`, `androidx.navigation.compose`, `kotlinx.coroutines.android`, `kotlinx.serialization.json`. Manifest: permissions `RECORD_AUDIO`, `INTERNET`; `FileProvider` with authority `${applicationId}.fileprovider`.

## 3. APK architecture

```
┌───────────────────────────── UI (Compose) ─────────────────────────────┐
│ MainActivity / AppNavigation (NavHost: chat, models, settings)          │
│ ChatScreen · ModelPickerScreen · SettingsScreen · DrawerContent         │
│ MarkdownText · Theme · Localization                                     │
└───────────────▲───────────────────────────────────────────▲────────────┘
                │ collectAsState()                          │ l(key, lang)
┌───────────────┴───────────── ViewModel ────────────────────┴────────────┐
│ ChatViewModel (AndroidViewModel): StateFlow state, sendMessage,          │
│ loadModel, freeRam, suggestions, documents, STT/TTS, settings           │
└───▲──────────▲───────────────▲───────────────────▲──────────────────────┘
    │          │               │                   │
┌───┴──────┐ ┌─┴───────────┐ ┌─┴──────────────┐ ┌──┴───────────────┐
│ Chat     │ │ LlmEngine   │ │ Suggestion     │ │ SttManager /     │
│ Repo     │ │ (inference, │ │ Engine         │ │ TtsManager       │
│ (sessions│ │  models,    │ │ (dedup, retry) │ │ (voice)          │
│  docs)   │ │  mmproj,    │ └────────────────┘ └──────────────────┘
└──────────┘ │  settings)  │
             └──────┬──────┘
                    │ JNI (LocalLlmNative)
┌───────────────────┴────────────────────────────────────────────────────┐
│ llama_jni.cpp  →  llama.cpp (llama) + mtmd (vision) + ggml (CPU)        │
│ libllama_jni.so (arm64-v8a)                                             │
└────────────────────────────────────────────────────────────────────────┘
```

Data flow: UI → `ChatViewModel` → `LlmEngine.generate/generateVision` → JNI → `llama_decode` → tokens stream back via `TokenCallback` → `StateFlow` → Compose. Sessions/documents are handled by `ChatRepository` (JSON on disk); models/settings live in `SharedPreferences` (`gguf_chat_prefs`).

## 4. Functions and code

### 4.1 Entry & navigation — `MainActivity.kt`

| Function | What it does | Code |
|---|---|---|
| `MainActivity.onCreate` | Entry point, edge-to-edge + Compose | `setContent { AppNavigation() }` |
| `AppNavigation()` | Creates `ChatViewModel`, `NavHost`, theme, localizer `l` | `val l = { key -> Localization.getString(key, lang) }` |
| `AppContent(...)` | Drawer + `NavHost` with routes `chat`/`models`/`settings` | `navController.navigate("models")` |
| route `chat` | Chat screen | `composable("chat") { ChatScreen(...) }` |

### 4.2 ViewModel — `ui/viewmodel/ChatViewModel.kt`

| Function | What it does | Code |
|---|---|---|
| `importModel(uri)` | Import a model from a file | `val result = llmEngine.importModel(uri, displayName)` |
| `downloadModelFromUrl(url)` | Download a model; mmproj URL is routed to projectors | `if (rawName.contains("mmproj", ignoreCase = true)) { ... }` |
| `searchModels(q, maxMB, includeProj)` | HF search on the IO dispatcher | `HuggingFaceClient.searchGgufModels(...)` |
| `loadModel(model)` | Load model + its projector | `llmEngine.loadModel(modelInfo, modelInfo.mmprojPath)` |
| `attachImage(uri)` | Copy the picked image to `files/images` | `java.io.File(dir, "img_${UUID.randomUUID()}.jpg")` |
| `clearPendingImage()` | Clear the pending image | `_pendingImage.value = null` |
| `unloadModel()` | Unload the current model | `llmEngine.unloadModel()` |
| `refreshMemory()` | Read total/available RAM | `am.getMemoryInfo(mi)` |
| `freeRam()` | **Unload everything** and return memory to the OS | `llmEngine.unloadModel(); System.gc(); LocalLlmNative.nativeFreeMemory()` |
| `deleteModel(modelInfo)` | Delete model + entry | `llmEngine.deleteModel(modelInfo)` |
| `createNewSession()` | New chat session | `chatRepository.createSession()` |
| `selectSession(id)` | Switch session | `chatRepository.selectSession(id)` |
| `addDocument(uri)` | Read a document and trim to the context budget | `val (text, truncated) = DocumentReader.truncate(raw.text, remaining)` |
| `removeDocument(id)` | Remove a document from the session | `chatRepository.removeDocument(sessionId, id)` |
| `documentBudgetTokens()` | Token budget for documents | `DocumentReader.budgetTokens(...)` |
| `sendMessage()` | Main flow: choose vision/text, generate, save, autosuggest; enqueues TTS chunks as punctuation arrives | `val useVision = imagePath != null && llmEngine.hasVision` |
| `lastTtsDelimiter(sb)` | Index of the last sentence/clause punctuation in the TTS buffer | `'.', '!', '?', ';', ':', ',', '…' -> return i` |
| `attachMmprojToModel(model, uri)` | Import + bind a projector | `llmEngine.importMmproj(uri).onSuccess { llmEngine.setModelMmproj(...) }` |
| `downloadMmprojForModel(...)` | Download a projector and bind it | `llmEngine.downloadMmprojFromUrl(url, name)` |
| `removeMmprojFromModel(model)` | Unbind the projector | `llmEngine.setModelMmproj(model.path, null)` |
| `stopGeneration()` | Stop generation | `llmEngine.cancelGeneration()` |
| `generateSuggestions(onlyNew)` | Run suggestions via `SuggestionEngine` | `suggestionEngine.generate(...)` |
| `onSuggestionClicked(s)` | Put a suggestion into the input field | `_inputText.value = suggestion` |
| `startVoiceInput()/stopVoiceInput()` | STT | `sttManager.startListening(lang) { ... }` |
| `speakText()/stopSpeaking()` | TTS | `ttsManager.speak(text)` |
| `updateSettings(new)` / `setAppLanguage(lang)` | Persist settings | `llmEngine.saveSettings(newSettings)` |
| `onCleared()` | Release resources | `llmEngine.unloadModel()` |

### 4.3 Chat data — `data/chat/`

| Function | What it does | Code |
|---|---|---|
| `ChatRepository.loadSessions()` | Reads `files/sessions/*.json` | `sessionsDir.listFiles { f -> f.extension == "json" }` |
| `parseSession(json)` | Parses session/messages | `val arr = o.getJSONArray("messages")` |
| `parseDocuments(o)` | Parses session documents | `val arr = o.optJSONArray("documents") ?: return emptyList()` |
| `saveSession(session)` | Writes session to JSON | `File(sessionsDir, "${session.id}.json").writeText(o.toString())` |
| `createSession()` | Creates a session and makes it current | `_currentSessionId.value = session.id` |
| `addMessage(id, msg)` | Appends a message + auto title | `message.content.take(40).replace("\n", " ")` |
| `addDocument` / `removeDocument` | Change session documents | `session.copy(documents = session.documents + document)` |
| `deleteSession(id)` / `clearAll()` | Delete sessions | `File(sessionsDir, "$id.json").delete()` |
| `DocumentReader.estimateTokens(t)` | Token heuristic (Cyrillic is heavier) | `val charsPerToken = if (cyrRatio > 0.2) 1.8 else 3.5` |
| `DocumentReader.truncate(t, n)` | Trim to a limit | `(remainingTokens * charsPerToken(text)).toInt()` |
| `DocumentReader.read(ctx, uri)` | Reads txt/md/json/html/csv (≤8 MB) | `val ALLOWED_EXTENSIONS = setOf("txt","md","markdown","json","htm","html","csv")` |
| `stripHtml(html)` | Turn HTML into text | `s.replace(Regex("<[^>]+>"), " ")` |
| `SuggestionEngine.generate(...)` | Suggestions with dedup + temperature retries | `val temperatures = listOf(0.7f, 0.85f, 1.0f)` |
| `SuggestionEngine.normalize/markShown/clearHistory` | Dedup shown suggestions | `shownSuggestions.add(normalize(it))` |

### 4.4 Inference engine — `data/llm/LlmEngine.kt`

| Function | What it does | Code |
|---|---|---|
| `loadModelsFromPrefs()` | Load list; drop missing; migrate stale mmproj | `if (m.mmprojPath != null && !ModelCompatibility.isVisionArch(m.architecture)) m = m.copy(mmprojPath = null)` |
| `saveModelsToPrefs()` | Persist the list | `prefs.edit().putString(KEY_MODELS, arr.toString()).apply()` |
| `loadSettingsFromPrefs()` / `saveSettings()` | Settings | `o.put("temperature", ...)` |
| `importModel(uri)` | Copy GGUF into `files/models` with progress | `context.contentResolver.openInputStream(uri)` |
| `downloadModelFromUrl(url)` | Download with size/validity checks | `if (totalBytes > 0 && totalRead != totalBytes)` |
| `loadModel(model, mmproj)` | Load model, RAM gate, bind vision projector | `val isVision = ModelCompatibility.isVisionArch(arch)` |
| Effective context length | Requested ctx is clamped to the model's max and applied at load | `val effectiveCtx = s.contextLength.coerceIn(512, modelMaxCtx)` |
| `unloadModel()` | `nativeFreeModel` + reset state | `LocalLlmNative.nativeFreeModel(handle)` |
| `loadMmproj(path)` | Bind a projector to the loaded model | `LocalLlmNative.nativeLoadMmproj(handle, path)` |
| `setModelMmproj(path, mmproj)` | Persist projector binding | `it.copy(mmprojPath = mmprojPath)` |
| `autoFindMmproj(modelPath)` | Find sibling `mmproj*.gguf` | `n.endsWith(".gguf") && n.startsWith("mmproj")` |
| `importMmproj(uri)` / `downloadMmprojFromUrl(url)` | Import/download a projector | `ModelCompatibility.isProjectorFileName(name)` |
| `deleteModel(model)` | Delete file + entry | `File(modelInfo.path).delete()` |
| `generate(messages, onToken, docs)` | Text chat: chat-template + language + stream | `val templated = LocalLlmNative.nativeApplyChatTemplate(...)` |
| `generateVision(messages, imagePath, ...)` | Vision: RGB → mtmd | `LocalLlmNative.nativeGenerateStreamingVision(handle, prompt, rgb, w, h, ...)` |
| `buildVisionPrompt()` | Pick a template by architecture | `if (arch.startsWith("gemma")) buildGemmaTurnPrompt(...) else buildChatmlPrompt(...)` |
| `buildChatmlPrompt()` / `buildGemmaTurnPrompt()` | Manual Qwen/Gemma templates with media marker | `sb.append(mediaMarker)` |
| `buildManualPrompt()` | Fallback when the model has no chat-template | `when { arch.startsWith("qwen") -> ... }` |
| `cancelGeneration()` | Cancel | `LocalLlmNative.nativeCancel(handle)` |
| `resolveAnswerLanguageCode()/answerLanguageName()` | Answer language (auto → app language) | `if (code.isBlank() \|\| code == "auto") s.appLanguage else code` |
| `languageReminder()/languageDirective()` | Language instructions for the model | `"Reply in English only…"` |
| `generateSuggestions(...)` | JSON suggestions + language filter | `.filter { suggestionMatchesLanguage(it, langCode) }` |
| `buildSuggestionPrompt(...)` | Suggestion prompt **in the target language** (ru/de/en) | `private data class SuggestionStrings(...)` |
| `suggestionMatchesLanguage(t, code)` | Drop wrong-language chips | `"ru" -> hasCyr` |
| `parsePartial()` / `parseSuggestions()` / `unescape()` | Lenient JSON-suggestion parsing | `Regex("\\[(.*?)\\]", DOT_MATCHES_ALL)` |
| `formatBytes()` | Human-readable size | `"%.1f GB".format(bytes / 1_073_741_824.0)` |

### 4.5 GGUF parser & compatibility — `data/llm/`

| Function | What it does | Code |
|---|---|---|
| `GgufReader.readHeaders(ctx, uri)` / `readHeadersFromFile(file)` | Read a GGUF header | `context.contentResolver.openInputStream(uri)` |
| `readHeadersFromStream()` | Magic/version/tensors/KV | `if (String(magic) != "GGUF")` |
| `readGgufValue(buf, type)` | Decode GGUF value types | `8 -> { val len = buffer.long ... }` |
| `deriveArchitecture(meta)` | Fallback architecture from keys | `metadata.keys.any { it.startsWith("$arch.") }` |
| `ModelCompatibility.isArchSupported(arch)` | Supported-architecture gate | `arch.lowercase() in SUPPORTED_ARCHS` |
| `isModelFileName(name)` | Skip mmproj/lora/split | `NON_MODEL_MARKERS.any { n.contains(it) }` |
| `isProjectorFileName(name)` | Detect mmproj | `n.endsWith(".gguf") && n.contains("mmproj")` |
| `isVisionArch(arch)` | Vision architectures | `arch.lowercase() in VISION_ARCHS` |

### 4.6 Network — `data/llm/HuggingFaceClient.kt`

| Function | What it does | Code |
|---|---|---|
| `searchGgufModels(q, maxBytes, ...)` | Parallel repository search | `Semaphore(MAX_CONCURRENT_REQUESTS)` |
| `collectRepoFiles(repo, ...)` | Walk repo tree, filter .gguf | `$BASE/api/models/${encodePath(repoId)}/tree/main?recursive=true` |
| `encodePath(path)` | URL-encode a path | `URLEncoder.encode(it, "UTF-8").replace("+", "%20")` |
| `httpGetJsonArray(url)` | HTTP GET → JSONArray | `conn.setRequestProperty("User-Agent", USER_AGENT)` |

### 4.7 Voice — `data/voice/`

| Function | What it does | Code |
|---|---|---|
| `SttManager.startListening(lang, onResult)` | Speech recognition | `Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)` |
| `SttManager.stopListening()/destroy()` | Stop/release STT | `speechRecognizer.destroy()` |
| `onResults` / `onPartialResults` | Recognition results | `results?.getStringArrayList(RESULTS_RECOGNITION)` |
| `TtsManager.initialize()` | TTS init | `TextToSpeech(context) { status -> ... }` |
| `TtsManager.setLanguage(lang)` | Locale ru/de/en | `"ru" -> Locale("ru", "RU")` |
| `TtsManager.speak(text)` | Speak a full text (flushes the queue) | `fun speak(text: String) = speakChunk(text, flush = true)` |
| `TtsManager.speakChunk(text, flush)` | Streaming speech: append the chunk or flush the queue | `val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD` |
| `TtsManager.stop()/shutdown()` | Stop/release | `tts?.shutdown()` |

### 4.8 UI screens & components

| Function | What it does | Code |
|---|---|---|
| `ChatScreen(...)` | Chat: RAM header, message list, input, chips | `val ramFree by viewModel.ramFree.collectAsState()` |
| Chat auto-scroll | Follows the streaming answer's END, then jumps to the answer's START once the suggestion chips are ready | `listState.scrollToItem(index, overflow)` |
| `AnswerFooter(message)` | Under each answer: copy-to-clipboard, full timestamp (date/time/secs), generation time (mm:ss), token speed (t/s); stats come from `ChatMessage.generationMs`/`tokenCount` | `clipboard.setText(AnnotatedString(message.content))` |
| `MarkdownMessage(text)` / `CodeBlock` | Fenced code blocks with a language label, syntax highlighting and a per-block copy button | `FENCE.findAll(text)` |
| `takePhoto()` | Capture via `FileProvider` | `FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)` |
| `sendCurrent()` | Send + hide keyboard | `viewModel.sendMessage()` |
| `MessageBubble(...)` | Message bubble (+image) | `val isUser = message.role == "user"` |
| `chipsContent`/`suggestionsArea` | Suggestion chips (wrap/single-row) | `AssistChip(onClick = { viewModel.onSuggestionClicked(suggestion) })` |
| “+” menu | One button → document/gallery/camera | `DropdownMenu(expanded = showAttachMenu, ...)` |
| `ModelPickerScreen(...)` | Model list, import/download/search/projectors | `filePickerLauncher.launch(arrayOf("*/*"))` |
| `ModelCard(...)` | Model card + projector status | `val projectorName = model.mmprojPath?.let { File(it).name }` |
| `DownloadModelDialog` / `SearchModelsDialog` / `ProjectorDownloadDialog` | URL/search/projector dialogs | `viewModel.downloadModelFromUrl(url, name)` |
| `SettingsScreen(...)` | Settings (temp, languages, theme, layout) | `viewModel.updateSettings(currentSettings)` |
| Context size field | Numeric input 512..131072; the effective value is clamped to the loaded model | `currentSettings.copy(contextLength = v.coerceIn(512, 131072))` |
| Quick downloads dialog | One-tap preset downloads: Gemma 4 E2B + Q8 mmproj and Qwen2-VL 2B + Q8 mmproj | `QUICK_DOWNLOADS` + `viewModel.downloadModelFromUrl(url, fileName)` |
| `DropdownSelector/SliderSetting/SwitchSetting/SectionTitle` | Settings widgets | `var expanded by remember { mutableStateOf(false) }` |
| `DrawerContent(...)` | Drawer: new chat, models, settings, history | `Text(l("models"), style = MaterialTheme.typography.bodyLarge)` |
| `MarkdownText(...)` / `parseMarkdown` / `parseInline` | Lightweight markdown rendering | `val annotated = parseMarkdown(text, textColor)` |
| `GGUFChatTemplateTheme(...)` | Themes (light/dark/green/orange/light_blue/yellow) | `enum class AppTheme { LIGHT, DARK, ... }` |
| `Localization.getString(key, lang)` | en/ru/de string lookup | `val selectedLang = if (translations.containsKey(lang)) lang else "en"` |
| `fmtRam(bytes)` | Format RAM for the header | `"%.1f GB".format(bytes / 1_073_741_824.0)` |

### 4.9 Native layer — `app/src/main/cpp/llama_jni.cpp`

| JNI function | What it does | Code |
|---|---|---|
| `nativeLoadModel` | Load model + context | `session->model = llama_model_load_from_file(path.c_str(), mparams)` |
| `nativeApplyChatTemplate` | Apply the model chat-template | `llama_chat_apply_template(tmpl, messages.data(), messages.size(), true, ...)` |
| `nativeGenerateStreaming` | Streaming text generation | `llama_tokenize(...)`, `llama_decode(s->ctx, batch)`, `llama_sampler_sample(...)` |
| `nativeLoadMmproj` | Load mmproj (mtmd) | `s->mtmd = mtmd_init_from_file(path.c_str(), s->model, mp)` |
| `nativeHasMmproj` | Is a projector present | `s->mtmd != nullptr` |
| `nativeMediaMarker` | Image placeholder marker | `mtmd_default_marker()` |
| `nativeGenerateStreamingVision` | Vision inference | `mtmd_tokenize(...)`, `mtmd_helper_eval_chunks(...)` |
| `nativeCancel` | Cancel flag | `s->cancel_flag.store(true, ...)` |
| `nativeFreeModel` | Free model/projector | `mtmd_free(s->mtmd)` |
| `nativeFreeMemory` | Return free native memory to the OS | `mallopt(M_PURGE, 0)` |
| `nativeIsModelLoaded` | Loaded check | `s->ready` |

Sampler (`make_sampler`): `llama_sampler_init_penalties(...)` → `llama_sampler_init_temp(temp)` → `llama_sampler_init_top_p(topP, 1)` → `llama_sampler_init_dist(LLAMA_DEFAULT_SEED)`.

### 4.10 Build — `CMakeLists.txt` / Gradle

| File | What it does | Code |
|---|---|---|
| `app/src/main/cpp/CMakeLists.txt` | Pulls in llama.cpp-new + mtmd | `set(LLAMA_CPP_DIR ".../llama.cpp-new")` |
| `add_library(llama_jni ...)` | Builds the JNI library | `target_link_libraries(llama_jni mtmd llama ggml android log)` |
| `app/build.gradle.kts` | Android/NDK/Release config | `arguments += "-DCMAKE_BUILD_TYPE=Release"` |
| `settings.gradle.kts` | App module | `include(":app")` |

## 5. TODO (updated — current status)

| # | Feature | Status | Note |
|---|---|---|---|
| 1 | Chat (Compose, streaming generation) | ✅ done | `ChatScreen` + `nativeGenerateStreaming` |
| 2 | Import local model | ✅ done | `importModel` |
| 3 | Download model by URL | ✅ done | `downloadModelFromUrl` + integrity check |
| 4 | Hugging Face model search | ✅ done | `HuggingFaceClient.searchGgufModels` |
| 5 | Vision projector (mmproj): bind/import/download | ✅ done | `attachMmprojToModel`, `importMmproj` |
| 6 | Auto-bind mmproj only for vision architectures + purge stale bindings | ✅ done | `isVisionArch` in `loadModel`/`loadModelsFromPrefs` |
| 7 | Multimodal (mtmd) inference Qwen2-VL / Gemma 4 E2B | ✅ verified | answered “7391” on the test image |
| 8 | Chat-templates: native + manual fallback (Qwen/Gemma/Llama/Phi) | ✅ done | `buildManualPrompt` |
| 9 | Model answer language = setting (text + vision) | ✅ done | `languageReminder` on both paths |
| 10 | Suggestion chips (stream, dedup, target language, language filter) | ✅ done | `buildSuggestionPrompt`, `suggestionMatchesLanguage` |
| 11 | Document-in-chat (txt/md/json/html/csv) | ✅ done | `DocumentReader` |
| 12 | STT (voice input) | ✅ done | `SttManager` |
| 13 | TTS (speech output) | ✅ done | `TtsManager` — streaming, sentence-by-sentence as punctuation arrives |
| 14 | Settings (temp, top_p, max tokens, ctx, threads, languages, themes, layout) | ✅ done | `SettingsScreen` |
| 15 | Localization en/ru/de + 6 themes | ✅ done | `Localization`, `AppTheme` |
| 16 | RAM monitor + “Free RAM” button | ✅ done | `refreshMemory`, `freeRam` |
| 17 | “+” button with menu (document/gallery/camera) | ✅ done | `DropdownMenu` |
| 18 | Architecture compatibility gate | ✅ done | `ModelCompatibility` |
| 19 | GGUF header parser | ✅ done | `GgufReader` |
| 20 | Q8_0 mmproj from ggml-org on new mtmd | ✅ verified | works |
| 21 | GPU/NNAPI inference acceleration | ⏳ pending | CPU-only, `mtmd use_gpu=false` |
| 22 | Sampler UI (top_k, repeat/presence, seed) | ⏳ pending | only temperature/top_p exposed |
| 23 | Chat export/import | ⏳ pending | sessions only in `files/sessions` |
| 24 | RAG/embeddings for documents | ⏳ pending | simple budget truncation now |
| 25 | LoRA/adapter | ⏳ pending | markers exist, no loading |
| 26 | Split GGUF (`-00001-of-0000N`) | ⏳ pending | parts are rejected |
| 27 | Exact token counts via native vocab | ⏳ pending | `DocumentReader` heuristic |
| 28 | Model metadata viewer | ⏳ pending | `GgufInfo.metadata` collected, no screen |
| 29 | Vision for gemma3n/llama4/minicpm | ⏳ untested | listed in `VISION_ARCHS`, not tested |
| 30 | Offline STT (whisper) instead of the system recognizer | ⏳ pending | uses `SpeechRecognizer` |
| 31 | Keep model warm / no double load | ⏳ backlog | `freeRam` unloads the model |
| 32 | Unit/UI tests and CI | ⏳ pending | none |
| 33 | Streaming TTS (speak each clause as tokens arrive) | ✅ verified | 8 synthesis requests during one answer |
| 34 | Smart chat auto-scroll (follow end, jump to start after chips) | ✅ done | `ChatScreen` LaunchedEffects |
| 35 | Answer footer: copy, full timestamp, generation time, token speed | ✅ verified | footer "2026-10-05 06:25:39 · 0:33 · 1.3 t/s" |
| 36 | Markdown code blocks with syntax highlighting + per-block copy | ✅ done | `MarkdownMessage.kt` |
| 37 | Configurable context size for all models (512..131072, clamped to the model's max) | ✅ verified | `coerceIn(512, modelMaxCtx)`, header shows "· 8192 ctx" |
| 38 | One-tap preset downloads (Gemma 4 E2B, Qwen2-VL 2B + Q8 mmproj) | ✅ built | device verification pending |
