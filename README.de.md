# GGUF Chat — Android LLM client

**RU** [Русский](README.ru.md) · **EN** [English](README.md) · **DE** [Deutsch](README.de.md)

---

## 1. Überblick

`GGUF Chat` ist eine native Android-App (Paket `com.devhorizon.online.ggufchat`), die:
- lokale GGUF-Modelle lädt und die Generierung auf der CPU über ein eingebettetes `llama.cpp` ausführt;
- **Vision** (Bilder) über `mtmd` + einen `mmproj`-Projektor unterstützt (Qwen2-VL, Gemma 4 E2B …);
- Dokumente im Chat, Spracheingabe (STT), Sprachausgabe (TTS), Vorschlags-Chips, Hugging-Face-Suche/-Download, einen RAM-Monitor und einen **Free-RAM**-Button bietet;
- in **en/ru/de** lokalisiert ist und 6 Themes mitbringt.

## 2. Voller Stack

| Schicht | Technologie |
|---|---|
| Sprache | Kotlin `2.2.10` (JVM-Target 17) |
| Build | Gradle + AGP `9.1.1`, Kotlin-Compose-Plugin |
| UI | Jetpack Compose (BOM `2024.09.00`), Material3, Material Icons (core+extended), Navigation Compose `2.8.9` |
| State | AndroidX Lifecycle/ViewModel `2.8.7`, Kotlin Coroutines/Flow `1.10.2` |
| Daten | `org.json` (JSONObject/JSONArray), `SharedPreferences`, Session-Dateien unter `files/sessions/*.json` |
| Netz | `HttpURLConnection`, Hugging-Face-REST-API |
| Medien | Android `SpeechRecognizer` (STT), `TextToSpeech` (TTS), `ContentResolver`/Document-Picker, `FileProvider` (Kamera), `BitmapFactory` |
| Native | C++17, JNI, **llama.cpp** (`app/src/main/cpp/llama.cpp-new`) + `tools/mtmd`, CMake `3.22.1`, NDK `28.2.13676358`, nur `arm64-v8a` |
| Plattform | `minSdk 26`, `targetSdk 36`, `compileSdk 36`, `ANDROID_STL=c++_shared`, nativer Build immer `Release` (`-DCMAKE_BUILD_TYPE=Release`) |

Wichtige Abhängigkeiten (`app/build.gradle.kts`): `androidx.compose.material3`, `androidx.navigation.compose`, `kotlinx.coroutines.android`, `kotlinx.serialization.json`. Manifest: Berechtigungen `RECORD_AUDIO`, `INTERNET`; `FileProvider` mit Authority `${applicationId}.fileprovider`.

## 3. APK-Architektur

```
┌───────────────────────────── UI (Compose) ─────────────────────────────┐
│ MainActivity / AppNavigation (NavHost: chat, models, settings)          │
│ ChatScreen · ModelPickerScreen · SettingsScreen · DrawerContent         │
│ MarkdownText · Theme · Localization                                     │
└───────────────▲───────────────────────────────────────────▲────────────┘
                │ collectAsState()                          │ l(key, lang)
┌───────────────┴───────────── ViewModel ────────────────────┴────────────┐
│ ChatViewModel (AndroidViewModel): StateFlow, sendMessage, loadModel,     │
│ freeRam, Vorschläge, Dokumente, STT/TTS, Einstellungen                  │
└───▲──────────▲───────────────▲───────────────────▲──────────────────────┘
    │          │               │                   │
┌───┴──────┐ ┌─┴───────────┐ ┌─┴──────────────┐ ┌──┴───────────────┐
│ Chat     │ │ LlmEngine   │ │ Suggestion     │ │ SttManager /     │
│ Repo     │ │ (Inferenz,  │ │ Engine         │ │ TtsManager       │
│ (Sessions│ │  Modelle,   │ │ (Dedup, Retry) │ │ (Stimme)         │
│  Doku)   │ │  mmproj,    │ └────────────────┘ └──────────────────┘
└──────────┘ │  Settings)  │
             └──────┬──────┘
                    │ JNI (LocalLlmNative)
┌───────────────────┴────────────────────────────────────────────────────┐
│ llama_jni.cpp  →  llama.cpp (llama) + mtmd (Vision) + ggml (CPU)        │
│ libllama_jni.so (arm64-v8a)                                             │
└────────────────────────────────────────────────────────────────────────┘
```

Datenfluss: UI → `ChatViewModel` → `LlmEngine.generate/generateVision` → JNI → `llama_decode` → Tokens über `TokenCallback` → `StateFlow` → Compose. Sessions/Dokumente in `ChatRepository` (JSON auf Platte); Modelle/Einstellungen in `SharedPreferences` (`gguf_chat_prefs`).

## 4. Funktionen und Code

### 4.1 Einstieg & Navigation — `MainActivity.kt`

| Funktion | Aufgabe | Code |
|---|---|---|
| `MainActivity.onCreate` | Einstiegspunkt, Edge-to-Edge + Compose | `setContent { AppNavigation() }` |
| `AppNavigation()` |erstellt `ChatViewModel`, `NavHost`, Theme, Lokalisierung `l` | `val l = { key -> Localization.getString(key, lang) }` |
| `AppContent(...)` | Drawer + `NavHost` mit Routen `chat`/`models`/`settings` | `navController.navigate("models")` |
| Route `chat` | Chat-Screen | `composable("chat") { ChatScreen(...) }` |

### 4.2 ViewModel — `ui/viewmodel/ChatViewModel.kt`

| Funktion | Aufgabe | Code |
|---|---|---|
| `importModel(uri)` | Modell aus Datei importieren | `val result = llmEngine.importModel(uri, displayName)` |
| `downloadModelFromUrl(url)` | Modell laden; mmproj-URL → Projektor | `if (rawName.contains("mmproj", ignoreCase = true)) { ... }` |
| `searchModels(q, maxMB, includeProj)` | HF-Suche im IO-Dispatcher | `HuggingFaceClient.searchGgufModels(...)` |
| `loadModel(model)` | Modell + Projektor laden | `llmEngine.loadModel(modelInfo, modelInfo.mmprojPath)` |
| `attachImage(uri)` | Gewähltes Bild nach `files/images` kopieren | `java.io.File(dir, "img_${UUID.randomUUID()}.jpg")` |
| `clearPendingImage()` | Ausstehendes Bild löschen | `_pendingImage.value = null` |
| `unloadModel()` | Aktuelles Modell entladen | `llmEngine.unloadModel()` |
| `refreshMemory()` | RAM total/frei lesen | `am.getMemoryInfo(mi)` |
| `freeRam()` | **Alles entladen** und Speicher an das OS zurückgeben | `llmEngine.unloadModel(); System.gc(); LocalLlmNative.nativeFreeMemory()` |
| `deleteModel(modelInfo)` | Modell + Eintrag löschen | `llmEngine.deleteModel(modelInfo)` |
| `createNewSession()` | Neue Chat-Session | `chatRepository.createSession()` |
| `selectSession(id)` | Session wechseln | `chatRepository.selectSession(id)` |
| `addDocument(uri)` | Dokument lesen und auf das Kontext-Budget kürzen | `val (text, truncated) = DocumentReader.truncate(raw.text, remaining)` |
| `removeDocument(id)` | Dokument aus der Session entfernen | `chatRepository.removeDocument(sessionId, id)` |
| `documentBudgetTokens()` | Token-Budget für Dokumente | `DocumentReader.budgetTokens(...)` |
| `sendMessage()` | Hauptablauf: Vision/Text, Generieren, Speichern, Chips; TTS-Chunks werden bei Satzzeichen eingereiht | `val useVision = imagePath != null && llmEngine.hasVision` |
| `lastTtsDelimiter(sb)` | Index des letzten Satzzeichens im TTS-Puffer | `'.', '!', '?', ';', ':', ',', '…' -> return i` |
| `attachMmprojToModel(model, uri)` | Projektor importieren + binden | `llmEngine.importMmproj(uri).onSuccess { llmEngine.setModelMmproj(...) }` |
| `downloadMmprojForModel(...)` | Projektor laden und binden | `llmEngine.downloadMmprojFromUrl(url, name)` |
| `removeMmprojFromModel(model)` | Projektor-Bindung lösen | `llmEngine.setModelMmproj(model.path, null)` |
| `stopGeneration()` | Generierung stoppen | `llmEngine.cancelGeneration()` |
| `generateSuggestions(onlyNew)` | Vorschläge über `SuggestionEngine` | `suggestionEngine.generate(...)` |
| `onSuggestionClicked(s)` | Vorschlag ins Eingabefeld | `_inputText.value = suggestion` |
| `startVoiceInput()/stopVoiceInput()` | STT | `sttManager.startListening(lang) { ... }` |
| `speakText()/stopSpeaking()` | TTS | `ttsManager.speak(text)` |
| `updateSettings(new)` / `setAppLanguage(lang)` | Einstellungen speichern | `llmEngine.saveSettings(newSettings)` |
| `onCleared()` | Ressourcen freigeben | `llmEngine.unloadModel()` |

### 4.3 Chat-Daten — `data/chat/`

| Funktion | Aufgabe | Code |
|---|---|---|
| `ChatRepository.loadSessions()` | Liest `files/sessions/*.json` | `sessionsDir.listFiles { f -> f.extension == "json" }` |
| `parseSession(json)` | Session/Nachrichten parsen | `val arr = o.getJSONArray("messages")` |
| `parseDocuments(o)` | Session-Dokumente parsen | `val arr = o.optJSONArray("documents") ?: return emptyList()` |
| `saveSession(session)` | Session als JSON schreiben | `File(sessionsDir, "${session.id}.json").writeText(o.toString())` |
| `createSession()` | Session anlegen und aktivieren | `_currentSessionId.value = session.id` |
| `addMessage(id, msg)` | Nachricht + Auto-Titel | `message.content.take(40).replace("\n", " ")` |
| `addDocument` / `removeDocument` | Dokumente der Session ändern | `session.copy(documents = session.documents + document)` |
| `deleteSession(id)` / `clearAll()` | Sessions löschen | `File(sessionsDir, "$id.json").delete()` |
| `DocumentReader.estimateTokens(t)` | Token-Heuristik (Kyrillisch teurer) | `val charsPerToken = if (cyrRatio > 0.2) 1.8 else 3.5` |
| `DocumentReader.truncate(t, n)` | Auf Limit kürzen | `(remainingTokens * charsPerToken(text)).toInt()` |
| `DocumentReader.read(ctx, uri)` | txt/md/json/html/csv lesen (≤8 MB) | `val ALLOWED_EXTENSIONS = setOf("txt","md","markdown","json","htm","html","csv")` |
| `stripHtml(html)` | HTML in Text wandeln | `s.replace(Regex("<[^>]+>"), " ")` |
| `SuggestionEngine.generate(...)` | Vorschläge mit Dedup + Temperatur-Retries | `val temperatures = listOf(0.7f, 0.85f, 1.0f)` |
| `SuggestionEngine.normalize/markShown/clearHistory` | Dedup gezeigter Vorschläge | `shownSuggestions.add(normalize(it))` |

### 4.4 Inferenz-Engine — `data/llm/LlmEngine.kt`

| Funktion | Aufgabe | Code |
|---|---|---|
| `loadModelsFromPrefs()` | Liste laden; fehlende entfernen; veralteten mmproj migrieren | `if (m.mmprojPath != null && !ModelCompatibility.isVisionArch(m.architecture)) m = m.copy(mmprojPath = null)` |
| `saveModelsToPrefs()` | Liste speichern | `prefs.edit().putString(KEY_MODELS, arr.toString()).apply()` |
| `loadSettingsFromPrefs()` / `saveSettings()` | Einstellungen | `o.put("temperature", ...)` |
| `importModel(uri)` | GGUF mit Fortschritt nach `files/models` kopieren | `context.contentResolver.openInputStream(uri)` |
| `downloadModelFromUrl(url)` | Download mit Größen-/Gültigkeitsprüfung | `if (totalBytes > 0 && totalRead != totalBytes)` |
| `loadModel(model, mmproj)` | Modell laden, RAM-Gate, Vision-Projektor binden | `val isVision = ModelCompatibility.isVisionArch(arch)` |
| Effektiver Kontext | Angeforderter ctx wird auf das Modellmaximum begrenzt und beim Laden angewandt | `val effectiveCtx = s.contextLength.coerceIn(512, modelMaxCtx)` |
| `unloadModel()` | `nativeFreeModel` + State-Reset | `LocalLlmNative.nativeFreeModel(handle)` |
| `loadMmproj(path)` | Projektor an geladenes Modell binden | `LocalLlmNative.nativeLoadMmproj(handle, path)` |
| `setModelMmproj(path, mmproj)` | Projektor-Bindung persistieren | `it.copy(mmprojPath = mmprojPath)` |
| `autoFindMmproj(modelPath)` | Sibling `mmproj*.gguf` finden | `n.endsWith(".gguf") && n.startsWith("mmproj")` |
| `importMmproj(uri)` / `downloadMmprojFromUrl(url)` | Projektor importieren/laden | `ModelCompatibility.isProjectorFileName(name)` |
| `deleteModel(model)` | Datei + Eintrag löschen | `File(modelInfo.path).delete()` |
| `generate(messages, onToken, docs)` | Textchat: Chat-Template + Sprache + Stream | `val templated = LocalLlmNative.nativeApplyChatTemplate(...)` |
| `generateVision(messages, imagePath, ...)` | Vision: RGB → mtmd | `LocalLlmNative.nativeGenerateStreamingVision(handle, prompt, rgb, w, h, ...)` |
| `buildVisionPrompt()` | Template nach Architektur wählen | `if (arch.startsWith("gemma")) buildGemmaTurnPrompt(...) else buildChatmlPrompt(...)` |
| `buildChatmlPrompt()` / `buildGemmaTurnPrompt()` | Manuelle Qwen/Gemma-Templates mit Media-Marker | `sb.append(mediaMarker)` |
| `buildManualPrompt()` | Fallback ohne Chat-Template | `when { arch.startsWith("qwen") -> ... }` |
| `cancelGeneration()` | Abbrechen | `LocalLlmNative.nativeCancel(handle)` |
| `resolveAnswerLanguageCode()/answerLanguageName()` | Antwortsprache (auto → App-Sprache) | `if (code.isBlank() \|\| code == "auto") s.appLanguage else code` |
| `languageReminder()/languageDirective()` | Sprach-Anweisungen für das Modell | `"Antworte nur auf Deutsch…"` |
| `generateSuggestions(...)` | JSON-Vorschläge + Sprachfilter | `.filter { suggestionMatchesLanguage(it, langCode) }` |
| `buildSuggestionPrompt(...)` | Vorschlags-Prompt **in der Zielsprache** (ru/de/en) | `private data class SuggestionStrings(...)` |
| `suggestionMatchesLanguage(t, code)` | Fremdsprachige Chips verwerfen | `"ru" -> hasCyr` |
| `parsePartial()` / `parseSuggestions()` / `unescape()` | Nachsichtiges JSON-Parsing | `Regex("\\[(.*?)\\]", DOT_MATCHES_ALL)` |
| `formatBytes()` | Lesbare Größe | `"%.1f GB".format(bytes / 1_073_741_824.0)` |

### 4.5 GGUF-Parser & Kompatibilität — `data/llm/`

| Funktion | Aufgabe | Code |
|---|---|---|
| `GgufReader.readHeaders(ctx, uri)` / `readHeadersFromFile(file)` | GGUF-Header lesen | `context.contentResolver.openInputStream(uri)` |
| `readHeadersFromStream()` | Magic/Version/Tensors/KV | `if (String(magic) != "GGUF")` |
| `readGgufValue(buf, type)` | GGUF-Werttypen dekodieren | `8 -> { val len = buffer.long ... }` |
| `deriveArchitecture(meta)` | Fallback-Architektur aus Keys | `metadata.keys.any { it.startsWith("$arch.") }` |
| `ModelCompatibility.isArchSupported(arch)` | Gate unterstützter Architekturen | `arch.lowercase() in SUPPORTED_ARCHS` |
| `isModelFileName(name)` | mmproj/lora/split aussortieren | `NON_MODEL_MARKERS.any { n.contains(it) }` |
| `isProjectorFileName(name)` | mmproj erkennen | `n.endsWith(".gguf") && n.contains("mmproj")` |
| `isVisionArch(arch)` | Vision-Architekturen | `arch.lowercase() in VISION_ARCHS` |

### 4.6 Netz — `data/llm/HuggingFaceClient.kt`

| Funktion | Aufgabe | Code |
|---|---|---|
| `searchGgufModels(q, maxBytes, ...)` | Parallele Repository-Suche | `Semaphore(MAX_CONCURRENT_REQUESTS)` |
| `collectRepoFiles(repo, ...)` | Repo-Baum durchsuchen, .gguf filtern | `$BASE/api/models/${encodePath(repoId)}/tree/main?recursive=true` |
| `encodePath(path)` | Pfad URL-kodieren | `URLEncoder.encode(it, "UTF-8").replace("+", "%20")` |
| `httpGetJsonArray(url)` | HTTP GET → JSONArray | `conn.setRequestProperty("User-Agent", USER_AGENT)` |

### 4.7 Stimme — `data/voice/`

| Funktion | Aufgabe | Code |
|---|---|---|
| `SttManager.startListening(lang, onResult)` | Spracherkennung | `Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)` |
| `SttManager.stopListening()/destroy()` | STT stoppen/freigeben | `speechRecognizer.destroy()` |
| `onResults` / `onPartialResults` | Erkennungsergebnisse | `results?.getStringArrayList(RESULTS_RECOGNITION)` |
| `TtsManager.initialize()` | TTS initialisieren | `TextToSpeech(context) { status -> ... }` |
| `TtsManager.setLanguage(lang)` | Locale ru/de/en | `"ru" -> Locale("ru", "RU")` |
| `TtsManager.speak(text)` | Vollständigen Text vorlesen (Queue leeren) | `fun speak(text: String) = speakChunk(text, flush = true)` |
| `TtsManager.speakChunk(text, flush)` | Streaming-Sprache: Chunk anhängen oder Queue leeren | `val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD` |
| `TtsManager.stop()/shutdown()` | Stoppen/freigeben | `tts?.shutdown()` |

### 4.8 UI-Screens & Komponenten

| Funktion | Aufgabe | Code |
|---|---|---|
| `ChatScreen(...)` | Chat: RAM-Leiste, Nachrichtenliste, Eingabe, Chips | `val ramFree by viewModel.ramFree.collectAsState()` |
| Chat-Auto-Scroll | Folgt dem Ende der Streaming-Antwort, springt nach den Chips an den Anfang | `listState.scrollToItem(index, overflow)` |
| `AnswerFooter(message)` | Unter jeder Antwort: Kopieren, voller Zeitstempel (Datum/Zeit/Sek.), Generierzeit (mm:ss), Token-Tempo (t/s); Daten aus `ChatMessage.generationMs`/`tokenCount` | `clipboard.setText(AnnotatedString(message.content))` |
| `MarkdownMessage(text)` / `CodeBlock` | Codeblöcke mit Sprachlabel, Syntax-Highlighting und Copy-Button je Block | `FENCE.findAll(text)` |
| `takePhoto()` | Aufnahme über `FileProvider` | `FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)` |
| `sendCurrent()` | Senden + Tastatur verbergen | `viewModel.sendMessage()` |
| `MessageBubble(...)` | Nachrichtenblase (+Bild) | `val isUser = message.role == "user"` |
| `chipsContent`/`suggestionsArea` | Vorschlags-Chips (wrap/single-row) | `AssistChip(onClick = { viewModel.onSuggestionClicked(suggestion) })` |
| „+“-Menü | Ein Button → Dokument/Galerie/Kamera | `DropdownMenu(expanded = showAttachMenu, ...)` |
| `ModelPickerScreen(...)` | Modellliste, Import/Download/Suche/Projektoren | `filePickerLauncher.launch(arrayOf("*/*"))` |
| `ModelCard(...)` | Modellkarte + Projektor-Status | `val projectorName = model.mmprojPath?.let { File(it).name }` |
| `DownloadModelDialog` / `SearchModelsDialog` / `ProjectorDownloadDialog` | URL-/Such-/Projektor-Dialoge | `viewModel.downloadModelFromUrl(url, name)` |
| `SettingsScreen(...)` | Einstellungen (Temp., Sprachen, Theme, Layout) | `viewModel.updateSettings(currentSettings)` |
| Kontextgröße-Feld | Zahleneingabe 512..131072; effektiver Wert auf das Modell begrenzt | `currentSettings.copy(contextLength = v.coerceIn(512, 131072))` |
| `DropdownSelector/SliderSetting/SwitchSetting/SectionTitle` | Einstellungs-Widgets | `var expanded by remember { mutableStateOf(false) }` |
| `DrawerContent(...)` | Drawer: neuer Chat, Modelle, Einstellungen, Verlauf | `Text(l("models"), style = MaterialTheme.typography.bodyLarge)` |
| `MarkdownText(...)` / `parseMarkdown` / `parseInline` | Leichtes Markdown-Rendering | `val annotated = parseMarkdown(text, textColor)` |
| `GGUFChatTemplateTheme(...)` | Themes (light/dark/green/orange/light_blue/yellow) | `enum class AppTheme { LIGHT, DARK, ... }` |
| `Localization.getString(key, lang)` | en/ru/de-Übersetzung | `val selectedLang = if (translations.containsKey(lang)) lang else "en"` |
| `fmtRam(bytes)` | RAM-Format für die Kopfzeile | `"%.1f GB".format(bytes / 1_073_741_824.0)` |

### 4.9 Native Schicht — `app/src/main/cpp/llama_jni.cpp`

| JNI-Funktion | Aufgabe | Code |
|---|---|---|
| `nativeLoadModel` | Modell + Kontext laden | `session->model = llama_model_load_from_file(path.c_str(), mparams)` |
| `nativeApplyChatTemplate` | Chat-Template anwenden | `llama_chat_apply_template(tmpl, messages.data(), messages.size(), true, ...)` |
| `nativeGenerateStreaming` | Streaming-Textgenerierung | `llama_tokenize(...)`, `llama_decode(s->ctx, batch)`, `llama_sampler_sample(...)` |
| `nativeLoadMmproj` | mmproj laden (mtmd) | `s->mtmd = mtmd_init_from_file(path.c_str(), s->model, mp)` |
| `nativeHasMmproj` | Projektor vorhanden | `s->mtmd != nullptr` |
| `nativeMediaMarker` | Marker für das Bild | `mtmd_default_marker()` |
| `nativeGenerateStreamingVision` | Vision-Inferenz | `mtmd_tokenize(...)`, `mtmd_helper_eval_chunks(...)` |
| `nativeCancel` | Abbruch-Flag | `s->cancel_flag.store(true, ...)` |
| `nativeFreeModel` | Modell/Projektor freigeben | `mtmd_free(s->mtmd)` |
| `nativeFreeMemory` | Freien nativen Speicher an das OS zurückgeben | `mallopt(M_PURGE, 0)` |
| `nativeIsModelLoaded` | Geladen-Prüfung | `s->ready` |

Sampler (`make_sampler`): `llama_sampler_init_penalties(...)` → `llama_sampler_init_temp(temp)` → `llama_sampler_init_top_p(topP, 1)` → `llama_sampler_init_dist(LLAMA_DEFAULT_SEED)`.

### 4.10 Build — `CMakeLists.txt` / Gradle

| Datei | Aufgabe | Code |
|---|---|---|
| `app/src/main/cpp/CMakeLists.txt` | Bindet llama.cpp-new + mtmd ein | `set(LLAMA_CPP_DIR ".../llama.cpp-new")` |
| `add_library(llama_jni ...)` | Baut die JNI-Bibliothek | `target_link_libraries(llama_jni mtmd llama ggml android log)` |
| `app/build.gradle.kts` | Android/NDK/Release-Konfiguration | `arguments += "-DCMAKE_BUILD_TYPE=Release"` |
| `settings.gradle.kts` | App-Modul | `include(":app")` |

## 5. TODO (aktualisiert — aktueller Stand)

| # | Feature | Status | Hinweis |
|---|---|---|---|
| 1 | Chat (Compose, Streaming) | ✅ fertig | `ChatScreen` + `nativeGenerateStreaming` |
| 2 | Lokales Modell importieren | ✅ fertig | `importModel` |
| 3 | Modell per URL laden | ✅ fertig | `downloadModelFromUrl` + Integritätsprüfung |
| 4 | Hugging-Face-Suche | ✅ fertig | `HuggingFaceClient.searchGgufModels` |
| 5 | Vision-Projektor (mmproj): binden/importieren/laden | ✅ fertig | `attachMmprojToModel`, `importMmproj` |
| 6 | mmproj-Auto-Bind nur für Vision-Architekturen + Bereinigung veralteter Bindungen | ✅ fertig | `isVisionArch` in `loadModel`/`loadModelsFromPrefs` |
| 7 | Multimodale (mtmd) Inferenz Qwen2-VL / Gemma 4 E2B | ✅ geprüft | Antwort „7391“ auf dem Testbild |
| 8 | Chat-Templates: nativ + manueller Fallback (Qwen/Gemma/Llama/Phi) | ✅ fertig | `buildManualPrompt` |
| 9 | Antwortsprache = Einstellung (Text + Vision) | ✅ fertig | `languageReminder` in beiden Pfaden |
| 10 | Vorschlags-Chips (Stream, Dedup, Zielsprache, Sprachfilter) | ✅ fertig | `buildSuggestionPrompt`, `suggestionMatchesLanguage` |
| 11 | Dokumente im Chat (txt/md/json/html/csv) | ✅ fertig | `DocumentReader` |
| 12 | STT (Spracheingabe) | ✅ fertig | `SttManager` |
| 13 | TTS (Sprachausgabe) | ✅ fertig | `TtsManager` — Streaming, satzweise bei Satzzeichen |
| 14 | Einstellungen (temp, top_p, max tokens, ctx, threads, Sprachen, Themes, Layout) | ✅ fertig | `SettingsScreen` |
| 15 | Lokalisierung en/ru/de + 6 Themes | ✅ fertig | `Localization`, `AppTheme` |
| 16 | RAM-Monitor + „Free RAM“-Button | ✅ fertig | `refreshMemory`, `freeRam` |
| 17 | „+“-Button mit Menü (Dokument/Galerie/Kamera) | ✅ fertig | `DropdownMenu` |
| 18 | Kompatibilitäts-Gate für Architekturen | ✅ fertig | `ModelCompatibility` |
| 19 | GGUF-Header-Parser | ✅ fertig | `GgufReader` |
| 20 | Q8_0-mmproj von ggml-org auf neuem mtmd | ✅ geprüft | funktioniert |
| 21 | GPU/NNAPI-Beschleunigung | ⏳ offen | nur CPU, `mtmd use_gpu=false` |
| 22 | Sampler-UI (top_k, repeat/presence, seed) | ⏳ offen | nur temperature/top_p |
| 23 | Chat-Export/-Import | ⏳ offen | Sessions nur in `files/sessions` |
| 24 | RAG/Embeddings für Dokumente | ⏳ offen | derzeit einfache Budget-Kürzung |
| 25 | LoRA/Adapter | ⏳ offen | Marker vorhanden, kein Laden |
| 26 | Split-GGUF (`-00001-of-0000N`) | ⏳ offen | Teile werden abgelehnt |
| 27 | Exakte Token-Zählung über natives Vokabular | ⏳ offen | `DocumentReader`-Heuristik |
| 28 | Modell-Metadaten-Ansicht | ⏳ offen | `GgufInfo.metadata` gesammelt, kein Screen |
| 29 | Vision für gemma3n/llama4/minicpm | ⏳ ungetestet | in `VISION_ARCHS`, nicht getestet |
| 30 | Offline-STT (whisper) statt System-Erkennung | ⏳ offen | nutzt `SpeechRecognizer` |
| 31 | Modell warm halten / kein Doppel-Laden | ⏳ Backlog | `freeRam` entlädt das Modell |
| 32 | Unit-/UI-Tests und CI | ⏳ offen | keine |
| 33 | Streaming-TTS (jede Klausel bei Token-Eingang) | ✅ geprüft | 8 Synthese-Anfragen pro Antwort |
| 34 | Intelligenter Chat-Auto-Scroll (Ende folgen, nach Chips zum Anfang) | ✅ fertig | `ChatScreen`-LaunchedEffects |
| 35 | Antwort-Fußzeile: Kopieren, voller Zeitstempel, Generierzeit, Token-Tempo | ✅ geprüft | „2026-10-05 06:25:39 · 0:33 · 1.3 t/s“ |
| 36 | Markdown-Codeblöcke mit Syntax-Highlighting + Kopieren je Block | ✅ fertig | `MarkdownMessage.kt` |
| 37 | Konfigurierbare Kontextgröße für alle Modelle (512..131072, auf Modellmaximum begrenzt) | ✅ geprüft | `coerceIn(512, modelMaxCtx)`, Kopfzeile „· 8192 ctx“ |
