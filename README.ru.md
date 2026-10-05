# GGUF Chat — Android LLM client

**RU** [Русский](README.ru.md) · **EN** [English](README.md) · **DE** [Deutsch](README.de.md)

🔎 **Живая карта кода:** [sashok53011.github.io/gguf-chat-android](https://sashok53011.github.io/gguf-chat-android/) — интерактивная таблица на 3 колонки (наименование · технология · конкретные строки кода), со сворачиванием, RU/EN/DE.

---

## 1. Что это

`GGUF Chat` — нативное Android-приложение (пакет `com.devhorizon.online.ggufchat`), которое:
- загружает локальные GGUF-модели и запускает генерацию на CPU через вендоренный `llama.cpp`;
- поддерживает **зрение** (изображения) через `mtmd` + `mmproj`-проектор (Qwen2-VL, Gemma 4 E2B и др.);
- умеет текстовый чат по документам, голосовой ввод (STT) и озвучку (TTS), подсказки-чипы, поиск/скачивание моделей с Hugging Face, монитор ОЗУ и кнопку «Освободить ОЗУ»;
- локализован на **en / ru / de** и имеет 6 тем.

## 2. Полный стек

| Слой | Технологии |
|---|---|
| Язык | Kotlin `2.2.10` (JVM target 17) |
| Сборка | Gradle + AGP `9.1.1`, Kotlin Compose plugin |
| UI | Jetpack Compose (BOM `2024.09.00`), Material3, Material Icons (core+extended), Navigation Compose `2.8.9` |
| Состояние | AndroidX Lifecycle/ViewModel `2.8.7`, Kotlin Coroutines/Flow `1.10.2` |
| Данные | `org.json` (JSONObject/JSONArray), `SharedPreferences`, файлы сессий в `files/sessions/*.json` |
| Сеть | `HttpURLConnection`, REST API Hugging Face |
| Медиа | Android `SpeechRecognizer` (STT), `TextToSpeech` (TTS), `ContentResolver`/document picker, `FileProvider` (камера), `BitmapFactory` |
| Нативный слой | C++17, JNI, **llama.cpp** (`app/src/main/cpp/llama.cpp-new`) + `tools/mtmd`, CMake `3.22.1`, NDK `28.2.13676358`, только `arm64-v8a` |
| Платформа | `minSdk 26`, `targetSdk 36`, `compileSdk 36`, `ANDROID_STL=c++_shared`, нативная сборка всегда `Release` (`-DCMAKE_BUILD_TYPE=Release`) |

Ключевые зависимости (`app/build.gradle.kts`): `androidx.compose.material3`, `androidx.navigation.compose`, `kotlinx.coroutines.android`, `kotlinx.serialization.json`. Манифест: разрешения `RECORD_AUDIO`, `INTERNET`; `FileProvider` с authority `${applicationId}.fileprovider`.

## 3. Архитектура APK

```
┌───────────────────────────── UI (Compose) ─────────────────────────────┐
│ MainActivity / AppNavigation (NavHost: chat, models, settings)          │
│ ChatScreen · ModelPickerScreen · SettingsScreen · DrawerContent         │
│ MarkdownText · Theme · Localization                                     │
└───────────────▲───────────────────────────────────────────▲────────────┘
                │ collectAsState()                          │ l(key, lang)
┌───────────────┴───────────── ViewModel ────────────────────┴────────────┐
│ ChatViewModel (AndroidViewModel): StateFlow-состояние, sendMessage,      │
│ loadModel, freeRam, suggestions, documents, STT/TTS, настройки          │
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

Поток данных: UI → `ChatViewModel` → `LlmEngine.generate/generateVision` → JNI → `llama_decode` → токены стримятся обратно через `TokenCallback` → `StateFlow` → Compose. Сессии/документы — `ChatRepository` (JSON на диске); модели/настройки — `SharedPreferences` (`gguf_chat_prefs`).

## 4. Функции и код

### 4.1 Точка входа и навигация — `MainActivity.kt`

| Функция | Что делает | Код |
|---|---|---|
| `MainActivity.onCreate` | Точка входа, включает edge-to-edge и контент Compose | `setContent { AppNavigation() }` |
| `AppNavigation()` | Создаёт `ChatViewModel`, `NavHost`, тему, локализатор `l` | `val l = { key -> Localization.getString(key, lang) }` |
| `AppContent(...)` | Drawer + `NavHost` с маршрутами `chat`/`models`/`settings` | `navController.navigate("models")` |
| маршрут `chat` | Экран чата | `composable("chat") { ChatScreen(...) }` |

### 4.2 ViewModel — `ui/viewmodel/ChatViewModel.kt`

| Функция | Что делает | Код |
|---|---|---|
| `importModel(uri)` | Импорт модели из файла | `val result = llmEngine.importModel(uri, displayName)` |
| `downloadModelFromUrl(url)` | Скачивание модели; mmproj-URL роутится в проектор | `if (rawName.contains("mmproj", ignoreCase = true)) { ... }` |
| `searchModels(q, maxMB, includeProj)` | Поиск на HF в IO-диспетчере | `HuggingFaceClient.searchGgufModels(...)` |
| `loadModel(model)` | Загрузка модели + её проектора | `llmEngine.loadModel(modelInfo, modelInfo.mmprojPath)` |
| `attachImage(uri)` | Копирует выбранную картинку в `files/images` | `java.io.File(dir, "img_${UUID.randomUUID()}.jpg")` |
| `clearPendingImage()` | Сбрасывает выбранную картинку | `_pendingImage.value = null` |
| `unloadModel()` | Выгрузка текущей модели | `llmEngine.unloadModel()` |
| `refreshMemory()` | Читает total/avail ОЗУ | `am.getMemoryInfo(mi)` |
| `freeRam()` | **Выгружает всё** и возвращает память ОС | `llmEngine.unloadModel(); System.gc(); LocalLlmNative.nativeFreeMemory()` |
| `deleteModel(modelInfo)` | Удаляет модель и запись | `llmEngine.deleteModel(modelInfo)` |
| `createNewSession()` | Новая сессия чата | `chatRepository.createSession()` |
| `selectSession(id)` | Переключение сессии | `chatRepository.selectSession(id)` |
| `addDocument(uri)` | Читает документ и режет под бюджет контекста | `val (text, truncated) = DocumentReader.truncate(raw.text, remaining)` |
| `removeDocument(id)` | Удаляет документ из сессии | `chatRepository.removeDocument(sessionId, id)` |
| `documentBudgetTokens()` | Бюджет токенов под документы | `DocumentReader.budgetTokens(...)` |
| `sendMessage()` | Главный сценарий: выбор vision/text, генерация, сохранение, автоподсказки; фрагменты TTS ставятся в очередь по знакам препинания | `val useVision = imagePath != null && llmEngine.hasVision` |
| `lastTtsDelimiter(sb)` | Индекс последнего знака препинания в буфере TTS | `'.', '!', '?', ';', ':', ',', '…' -> return i` |
| `attachMmprojToModel(model, uri)` | Импорт и привязка проектора | `llmEngine.importMmproj(uri).onSuccess { llmEngine.setModelMmproj(...) }` |
| `downloadMmprojForModel(...)` | Скачивание проектора и привязка | `llmEngine.downloadMmprojFromUrl(url, name)` |
| `removeMmprojFromModel(model)` | Снимает привязку проектора | `llmEngine.setModelMmproj(model.path, null)` |
| `stopGeneration()` | Остановка генерации | `llmEngine.cancelGeneration()` |
| `generateSuggestions(onlyNew)` | Запуск подсказок через `SuggestionEngine` | `suggestionEngine.generate(...)` |
| `onSuggestionClicked(s)` | Подставляет подсказку в поле ввода | `_inputText.value = suggestion` |
| `startVoiceInput()/stopVoiceInput()` | STT | `sttManager.startListening(lang) { ... }` |
| `speakText()/stopSpeaking()` | TTS | `ttsManager.speak(text)` |
| `updateSettings(new)` / `setAppLanguage(lang)` | Сохранение настроек | `llmEngine.saveSettings(newSettings)` |
| `onCleared()` | Освобождение ресурсов | `llmEngine.unloadModel()` |

### 4.3 Данные чата — `data/chat/`

| Функция | Что делает | Код |
|---|---|---|
| `ChatRepository.loadSessions()` | Читает `files/sessions/*.json` | `sessionsDir.listFiles { f -> f.extension == "json" }` |
| `parseSession(json)` | Парсит сессию/сообщения | `val arr = o.getJSONArray("messages")` |
| `parseDocuments(o)` | Парсит документы сессии | `val arr = o.optJSONArray("documents") ?: return emptyList()` |
| `saveSession(session)` | Пишет сессию в JSON | `File(sessionsDir, "${session.id}.json").writeText(o.toString())` |
| `createSession()` | Создаёт сессию и делает текущей | `_currentSessionId.value = session.id` |
| `addMessage(id, msg)` | Добавляет сообщение + авто-заголовок | `message.content.take(40).replace("\n", " ")` |
| `addDocument` / `removeDocument` | Изменяют документы сессии | `session.copy(documents = session.documents + document)` |
| `deleteSession(id)` / `clearAll()` | Удаление сессий | `File(sessionsDir, "$id.json").delete()` |
| `DocumentReader.estimateTokens(t)` | Эвристика токенов (кириллица дороже) | `val charsPerToken = if (cyrRatio > 0.2) 1.8 else 3.5` |
| `DocumentReader.truncate(t, n)` | Обрезка под лимит | `(remainingTokens * charsPerToken(text)).toInt()` |
| `DocumentReader.read(ctx, uri)` | Читает txt/md/json/html/csv (≤8 МБ) | `val ALLOWED_EXTENSIONS = setOf("txt","md","markdown","json","htm","html","csv")` |
| `stripHtml(html)` | Превращает HTML в текст | `s.replace(Regex("<[^>]+>"), " ")` |
| `SuggestionEngine.generate(...)` | Подсказки с дедупом и ретраями по температуре | `val temperatures = listOf(0.7f, 0.85f, 1.0f)` |
| `SuggestionEngine.normalize/markShown/clearHistory` | Дедуп показанных подсказок | `shownSuggestions.add(normalize(it))` |

### 4.4 Движок инференса — `data/llm/LlmEngine.kt`

| Функция | Что делает | Код |
|---|---|---|
| `loadModelsFromPrefs()` | Загрузка списка моделей; дроп отсутствующих; миграция stale-mmproj | `if (m.mmprojPath != null && !ModelCompatibility.isVisionArch(m.architecture)) m = m.copy(mmprojPath = null)` |
| `saveModelsToPrefs()` | Сохранение списка | `prefs.edit().putString(KEY_MODELS, arr.toString()).apply()` |
| `loadSettingsFromPrefs()` / `saveSettings()` | Настройки | `o.put("temperature", ...)` |
| `importModel(uri)` | Копирует GGUF в `files/models` с прогрессом | `context.contentResolver.openInputStream(uri)` |
| `downloadModelFromUrl(url)` | Скачивает с проверкой размера/валидности | `if (totalBytes > 0 && totalRead != totalBytes)` |
| `loadModel(model, mmproj)` | Грузит модель, чистит RAM-гейт, привязывает vision-проектор | `val isVision = ModelCompatibility.isVisionArch(arch)` |
| Эффективный ctx | Запрошенный ctx ограничивается максимумом модели и применяется при загрузке | `val effectiveCtx = s.contextLength.coerceIn(512, modelMaxCtx)` |
| `unloadModel()` | `nativeFreeModel` + сброс состояния | `LocalLlmNative.nativeFreeModel(handle)` |
| `loadMmproj(path)` | Привязка проектора к загруженной модели | `LocalLlmNative.nativeLoadMmproj(handle, path)` |
| `setModelMmproj(path, mmproj)` | Персистентная привязка проектора | `it.copy(mmprojPath = mmprojPath)` |
| `autoFindMmproj(modelPath)` | Ищет sibling `mmproj*.gguf` | `n.endsWith(".gguf") && n.startsWith("mmproj")` |
| `importMmproj(uri)` / `downloadMmprojFromUrl(url)` | Импорт/скачивание проектора | `ModelCompatibility.isProjectorFileName(name)` |
| `deleteModel(model)` | Удаление файла+записи | `File(modelInfo.path).delete()` |
| `generate(messages, onToken, docs)` | Текстовый чат: chat-template + язык + стрим | `val templated = LocalLlmNative.nativeApplyChatTemplate(...)` |
| `generateVision(messages, imagePath, ...)` | Vision: RGB → mtmd | `LocalLlmNative.nativeGenerateStreamingVision(handle, prompt, rgb, w, h, ...)` |
| `buildVisionPrompt()` | Выбор шаблона по архитектуре | `if (arch.startsWith("gemma")) buildGemmaTurnPrompt(...) else buildChatmlPrompt(...)` |
| `buildChatmlPrompt()` / `buildGemmaTurnPrompt()` | Ручные шаблоны Qwen/Gemma с медиа-маркером | `sb.append(mediaMarker)` |
| `buildManualPrompt()` | Fallback, если у модели нет chat-template | `when { arch.startsWith("qwen") -> ... }` |
| `cancelGeneration()` | Отмена | `LocalLlmNative.nativeCancel(handle)` |
| `resolveAnswerLanguageCode()/answerLanguageName()` | Язык ответа (auto → язык приложения) | `if (code.isBlank() \|\| code == "auto") s.appLanguage else code` |
| `languageReminder()/languageDirective()` | Инструкции языка для модели | `"Отвечай только на русском языке…"` |
| `generateSuggestions(...)` | JSON-подсказки + фильтр языка | `.filter { suggestionMatchesLanguage(it, langCode) }` |
| `buildSuggestionPrompt(...)` | Промпт подсказок **на языке-цели** (ru/de/en) | `private data class SuggestionStrings(...)` |
| `suggestionMatchesLanguage(t, code)` | Отсев иноязычных чипов | `"ru" -> hasCyr` |
| `parsePartial()` / `parseSuggestions()` / `unescape()` | Парсинг JSON-подсказок (терпимый) | `Regex("\\[(.*?)\\]", DOT_MATCHES_ALL)` |
| `formatBytes()` | Человекочитаемый размер | `"%.1f GB".format(bytes / 1_073_741_824.0)` |

### 4.5 GGUF-парсер и совместимость — `data/llm/`

| Функция | Что делает | Код |
|---|---|---|
| `GgufReader.readHeaders(ctx, uri)` / `readHeadersFromFile(file)` | Чтение заголовка GGUF | `context.contentResolver.openInputStream(uri)` |
| `readHeadersFromStream()` | Magic/version/tensors/KV | `if (String(magic) != "GGUF")` |
| `readGgufValue(buf, type)` | Разбор типов значений GGUF | `8 -> { val len = buffer.long ... }` |
| `deriveArchitecture(meta)` | Fallback-архитектура по ключам | `metadata.keys.any { it.startsWith("$arch.") }` |
| `ModelCompatibility.isArchSupported(arch)` | Гейт поддерживаемых архитектур | `arch.lowercase() in SUPPORTED_ARCHS` |
| `isModelFileName(name)` | Отсев mmproj/lora/split | `NON_MODEL_MARKERS.any { n.contains(it) }` |
| `isProjectorFileName(name)` | Определяет mmproj | `n.endsWith(".gguf") && n.contains("mmproj")` |
| `isVisionArch(arch)` | Vision-архитектуры | `arch.lowercase() in VISION_ARCHS` |

### 4.6 Сеть — `data/llm/HuggingFaceClient.kt`

| Функция | Что делает | Код |
|---|---|---|
| `searchGgufModels(q, maxBytes, ...)` | Параллельный поиск репозиториев | `Semaphore(MAX_CONCURRENT_REQUESTS)` |
| `collectRepoFiles(repo, ...)` | Обходит дерево репо, фильтрует .gguf | `$BASE/api/models/${encodePath(repoId)}/tree/main?recursive=true` |
| `encodePath(path)` | URL-кодирование пути | `URLEncoder.encode(it, "UTF-8").replace("+", "%20")` |
| `httpGetJsonArray(url)` | HTTP GET → JSONArray | `conn.setRequestProperty("User-Agent", USER_AGENT)` |

### 4.7 Голос — `data/voice/`

| Функция | Что делает | Код |
|---|---|---|
| `SttManager.startListening(lang, onResult)` | Распознавание речи | `Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)` |
| `SttManager.stopListening()/destroy()` | Остановка/освобождение STT | `speechRecognizer.destroy()` |
| `onResults` / `onPartialResults` | Результаты распознавания | `results?.getStringArrayList(RESULTS_RECOGNITION)` |
| `TtsManager.initialize()` | Инициализация TTS | `TextToSpeech(context) { status -> ... }` |
| `TtsManager.setLanguage(lang)` | Локаль ru/de/en | `"ru" -> Locale("ru", "RU")` |
| `TtsManager.speak(text)` | Озвучка полного текста (сбрасывает очередь) | `fun speak(text: String) = speakChunk(text, flush = true)` |
| `TtsManager.speakChunk(text, flush)` | Потоковая озвучка: добавить фрагмент или сбросить очередь | `val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD` |
| `TtsManager.stop()/shutdown()` | Стоп/освобождение | `tts?.shutdown()` |

### 4.8 UI-экраны и компоненты

| Функция | Что делает | Код |
|---|---|---|
| `ChatScreen(...)` | Чат: шапка с ОЗУ, список сообщений, ввод, чипы | `val ramFree by viewModel.ramFree.collectAsState()` |
| Автоскролл чата | Во время стрима держит конец ответа, после готовности чипов прыгает к началу ответа | `listState.scrollToItem(index, overflow)` |
| `AnswerFooter(message)` | Под каждым ответом: copy в буфер, полный timestamp (дата/время/секунды), время генерации (мм:сс), скорость (t/s); статистика из `ChatMessage.generationMs`/`tokenCount` | `clipboard.setText(AnnotatedString(message.content))` |
| `MarkdownMessage(text)` / `CodeBlock` | Код-блоки с подписью языка, подсветкой синтаксиса и кнопкой copy у блока | `FENCE.findAll(text)` |
| `takePhoto()` | Съёмка через `FileProvider` | `FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)` |
| `sendCurrent()` | Отправка + скрытие клавиатуры | `viewModel.sendMessage()` |
| `MessageBubble(...)` | Пузырь сообщения (+картинка) | `val isUser = message.role == "user"` |
| `chipsContent`/`suggestionsArea` | Чипы подсказок (wrap/single-row) | `AssistChip(onClick = { viewModel.onSuggestionClicked(suggestion) })` |
| меню «+» | Одна кнопка → меню документ/галерея/камера | `DropdownMenu(expanded = showAttachMenu, ...)` |
| `ModelPickerScreen(...)` | Список моделей, импорт/скачивание/поиск/проекторы | `filePickerLauncher.launch(arrayOf("*/*"))` |
| `ModelCard(...)` | Карточка модели + статус проектора | `val projectorName = model.mmprojPath?.let { File(it).name }` |
| `DownloadModelDialog` / `SearchModelsDialog` / `ProjectorDownloadDialog` | Диалоги URL/поиска/проектора | `viewModel.downloadModelFromUrl(url, name)` |
| `SettingsScreen(...)` | Настройки (темп., языки, тема, layout) | `viewModel.updateSettings(currentSettings)` |
| Поле ctx size | Числовой ввод 512..131072; эффективное значение ограничивается моделью | `currentSettings.copy(contextLength = v.coerceIn(512, 131072))` |
| Диалог быстрых загрузок | Загрузка в один тап: Gemma 4 E2B + mmproj Q8 и Qwen2-VL 2B + mmproj Q8 | `QUICK_DOWNLOADS` + `viewModel.downloadModelFromUrl(url, fileName)` |
| `DropdownSelector/SliderSetting/SwitchSetting/SectionTitle` | Виджеты настроек | `var expanded by remember { mutableStateOf(false) }` |
| `DrawerContent(...)` | Ящик: новый чат, модели, настройки, история | `Text(l("models"), style = MaterialTheme.typography.bodyLarge)` |
| `MarkdownText(...)` / `parseMarkdown` / `parseInline` | Лёгкий markdown-рендер | `val annotated = parseMarkdown(text, textColor)` |
| `GGUFChatTemplateTheme(...)` | Темы (light/dark/green/orange/light_blue/yellow) | `enum class AppTheme { LIGHT, DARK, ... }` |
| `Localization.getString(key, lang)` | Перевод строк en/ru/de | `val selectedLang = if (translations.containsKey(lang)) lang else "en"` |
| `fmtRam(bytes)` | Формат ОЗУ в шапке | `"%.1f GB".format(bytes / 1_073_741_824.0)` |

### 4.9 Нативный слой — `app/src/main/cpp/llama_jni.cpp`

| JNI-функция | Что делает | Код |
|---|---|---|
| `nativeLoadModel` | Загрузка модели + контекста | `session->model = llama_model_load_from_file(path.c_str(), mparams)` |
| `nativeApplyChatTemplate` | Применение chat-template модели | `llama_chat_apply_template(tmpl, messages.data(), messages.size(), true, ...)` |
| `nativeGenerateStreaming` | Текстовая генерация стримом | `llama_tokenize(...)`, `llama_decode(s->ctx, batch)`, `llama_sampler_sample(...)` |
| `nativeLoadMmproj` | Загрузка mmproj (mtmd) | `s->mtmd = mtmd_init_from_file(path.c_str(), s->model, mp)` |
| `nativeHasMmproj` | Есть ли проектор | `s->mtmd != nullptr` |
| `nativeMediaMarker` | Маркер места картинки | `mtmd_default_marker()` |
| `nativeGenerateStreamingVision` | Vision-инференс | `mtmd_tokenize(...)`, `mtmd_helper_eval_chunks(...)` |
| `nativeCancel` | Флаг отмены | `s->cancel_flag.store(true, ...)` |
| `nativeFreeModel` | Освобождение модели/проектора | `mtmd_free(s->mtmd)` |
| `nativeFreeMemory` | Возврат свободной нативной памяти ОС | `mallopt(M_PURGE, 0)` |
| `nativeIsModelLoaded` | Проверка загрузки | `s->ready` |

Сэмплер (`make_sampler`): `llama_sampler_init_penalties(...)` → `llama_sampler_init_temp(temp)` → `llama_sampler_init_top_p(topP, 1)` → `llama_sampler_init_dist(LLAMA_DEFAULT_SEED)`.

### 4.10 Сборка — `CMakeLists.txt` / Gradle

| Файл | Что делает | Код |
|---|---|---|
| `app/src/main/cpp/CMakeLists.txt` | Подключает llama.cpp-new + mtmd | `set(LLAMA_CPP_DIR ".../llama.cpp-new")` |
| `add_library(llama_jni ...)` | Собирает JNI-библиотеку | `target_link_libraries(llama_jni mtmd llama ggml android log)` |
| `app/build.gradle.kts` | Конфиг Android/NDK/Release | `arguments += "-DCMAKE_BUILD_TYPE=Release"` |
| `settings.gradle.kts` | Модуль приложения | `include(":app")` |

## 5. TODO (обновлено — статус на текущий момент)

| # | Фича | Статус | Комментарий |
|---|---|---|---|
| 1 | Чат (Compose, потоковая генерация) | ✅ готово | `ChatScreen` + `nativeGenerateStreaming` |
| 2 | Импорт локальной модели | ✅ готово | `importModel` |
| 3 | Скачивание модели по URL | ✅ готово | `downloadModelFromUrl` + проверка целостности |
| 4 | Поиск моделей на Hugging Face | ✅ готово | `HuggingFaceClient.searchGgufModels` |
| 5 | Vision-проектор (mmproj): привязка/импорт/скачивание | ✅ готово | `attachMmprojToModel`, `importMmproj` |
| 6 | Авто-привязка mmproj только для vision-архитектур + чистка stale-привязок | ✅ готово | `isVisionArch` в `loadModel`/`loadModelFromPrefs` |
| 7 | Multimodal-инференс (mtmd) Qwen2-VL / Gemma 4 E2B | ✅ проверено | ответ «7391» на тестовой картинке |
| 8 | Chat-templates: native + ручной fallback (Qwen/Gemma/Llama/Phi) | ✅ готово | `buildManualPrompt` |
| 9 | Язык ответа модели = настройка (text + vision) | ✅ готово | `languageReminder` в обоих путях |
| 10 | Подсказки-чипы (стрим, дедуп, язык-цель, фильтр языка) | ✅ готово | `buildSuggestionPrompt`, `suggestionMatchesLanguage` |
| 11 | Чат с документами (txt/md/json/html/csv) | ✅ готово | `DocumentReader` |
| 12 | STT (голосовой ввод) | ✅ готово | `SttManager` |
| 13 | TTS (озвучка) | ✅ готово | `TtsManager` — потоковая, по фрагментам при появлении знаков препинания |
| 14 | Настройки (temp, top_p, max tokens, ctx, threads, языки, темы, layout) | ✅ готово | `SettingsScreen` |
| 15 | Локализация en/ru/de + 6 тем | ✅ готово | `Localization`, `AppTheme` |
| 16 | Монитор ОЗУ + кнопка «Освободить ОЗУ» | ✅ готово | `refreshMemory`, `freeRam` |
| 17 | Кнопка «+» с меню (документ/галерея/камера) | ✅ готово | `DropdownMenu` |
| 18 | Гейт совместимости архитектур | ✅ готово | `ModelCompatibility` |
| 19 | Парсер GGUF-заголовков | ✅ готово | `GgufReader` |
| 20 | Q8_0 mmproj из ggml-org на новом mtmd | ✅ проверено | работает |
| 21 | GPU/NNAPI-ускорение инференса | ⏳ не сделано | сейчас CPU-only, `mtmd use_gpu=false` |
| 22 | UI для sampler (top_k, repeat/presence, seed) | ⏳ не сделано | в UI только temperature/top_p |
| 23 | Экспорт/импорт чатов | ⏳ не сделано | сессии только в `files/sessions` |
| 24 | RAG/эмбеддинги для документов | ⏳ не сделано | сейчас простая обрезка по бюджету |
| 25 | LoRA/adapter | ⏳ не сделано | маркеры есть, загрузки нет |
| 26 | Split-GGUF (`-00001-of-0000N`) | ⏳ не сделано | части отклоняются |
| 27 | Точный подсчёт токенов через нативную лексику | ⏳ не сделано | эвристика `DocumentReader` |
| 28 | Метаданные модели в UI | ⏳ не сделано | `GgufInfo.metadata` собирается, экрана нет |
| 29 | Vision для gemma3n/llama4/minicpm | ⏳ не проверено | в списке `VISION_ARCHS`, но не тестировалось |
| 30 | Офлайн-STT (whisper) вместо системного | ⏳ не сделано | используется `SpeechRecognizer` |
| 31 | Модель не грузится дважды / прогрев | ⏳ бэклог | `freeRam` выгружает модель |
| 32 | Unit/UI-тесты и CI | ⏳ не сделано | тестов нет |
| 33 | Потоковый TTS (озвучка фрагментов по мере генерации) | ✅ проверено | 8 запросов синтеза за один ответ |
| 34 | Умный автоскролл (за концом, к началу после чипов) | ✅ готово | `LaunchedEffect` в `ChatScreen` |
| 35 | Футер ответа: copy, полный timestamp, время генерации, скорость токенов | ✅ проверено | «2026-10-05 06:25:39 · 0:33 · 1.3 t/s» |
| 36 | Код-блоки markdown с подсветкой синтаксиса и copy у блока | ✅ готово | `MarkdownMessage.kt` |
| 37 | Настройка ctx size для всех моделей (512..131072, ограничение по максимуму модели) | ✅ проверено | `coerceIn(512, modelMaxCtx)`, в шапке «· 8192 ctx» |
| 38 | Загрузка пресетов в один тап (Gemma 4 E2B, Qwen2-VL 2B + mmproj Q8) | ✅ собрано | проверка на устройстве ожидает |

---

