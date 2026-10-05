package com.devhorizon.online.ggufchat.ui.screens

import android.graphics.BitmapFactory
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devhorizon.online.ggufchat.data.model.ChatMessage
import com.devhorizon.online.ggufchat.data.model.ModelState
import com.devhorizon.online.ggufchat.data.chat.DocumentReader
import com.devhorizon.online.ggufchat.ui.components.MarkdownText
import com.devhorizon.online.ggufchat.ui.theme.AssistantBubble
import com.devhorizon.online.ggufchat.ui.theme.UserBubble
import com.devhorizon.online.ggufchat.ui.theme.UserBubbleText
import com.devhorizon.online.ggufchat.ui.theme.AssistantBubbleText
import com.devhorizon.online.ggufchat.ui.theme.Localization
import com.devhorizon.online.ggufchat.ui.viewmodel.ChatViewModel
import androidx.core.content.FileProvider
import java.io.File

private fun fmtRam(bytes: Long): String =
    if (bytes >= 1_073_741_824L) "%.1f GB".format(bytes / 1_073_741_824.0)
    else "%.0f MB".format(bytes / 1_048_576.0)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val lang by viewModel.appLanguage.collectAsState()
    val l = { key: String -> Localization.getString(key, lang) }

    val modelState by viewModel.llmEngine.state.collectAsState()
    val mmprojReady by viewModel.llmEngine.mmprojLoaded.collectAsState()
    val mmprojName by viewModel.llmEngine.mmprojName.collectAsState()
    val isPreparingVision by viewModel.isPreparingVision.collectAsState()
    val modelName by viewModel.llmEngine.modelName.collectAsState()
    val progress by viewModel.llmEngine.progress.collectAsState()
    val progressMessage by viewModel.llmEngine.progressMessage.collectAsState()
    val ramTotal by viewModel.ramTotal.collectAsState()
    val ramUsed by viewModel.ramUsed.collectAsState()
    val ramFree by viewModel.ramFree.collectAsState()

    val inputText by viewModel.inputText.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val streamingText by viewModel.streamingText.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    val pendingSuggestion by viewModel.pendingSuggestion.collectAsState()
    val isGeneratingSuggestions by viewModel.isGeneratingSuggestions.collectAsState()
    val statusText by viewModel.statusText.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(statusText) {
        if (statusText.isNotBlank()) snackbarHostState.showSnackbar(statusText)
    }
    val settings by viewModel.llmEngine.settings.collectAsState()
    val inputAtTop = settings.inputPosition == "top"
    val suggestionsWrap = settings.suggestionsLayout != "single_row"

    val isListening by viewModel.sttManager.isListening.collectAsState()
    val partialResult by viewModel.sttManager.partialResult.collectAsState()

    val documents by viewModel.documents.collectAsState()
    var showDocsSheet by remember { mutableStateOf(false) }
    var showAttachMenu by remember { mutableStateOf(false) }
    val docPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.addDocument(uri)
    }

    // ---- Image input (gallery + camera) ----
    val pendingImage by viewModel.pendingImage.collectAsState()
    val appContext = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.attachImage(uri)
    }
    var cameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = cameraUri
        if (success && uri != null) viewModel.attachImage(uri)
    }
    fun takePhoto() {
        try {
            val dir = File(appContext.cacheDir, "images").apply { mkdirs() }
            val file = File(dir, "cam_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
            cameraUri = uri
            cameraLauncher.launch(uri)
        } catch (e: Exception) {
            Log.e("ChatScreen", "takePhoto failed", e)
        }
    }

    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // Send and dismiss the keyboard so the reply is not hidden behind it.
    fun sendCurrent() {
        viewModel.sendMessage()
        keyboardController?.hide()
        focusManager.clearFocus()
    }

    // Reactive session source: selecting/creating a chat must rebuild the list.
    val sessions by viewModel.chatRepository.sessions.collectAsState()
    val currentSessionId by viewModel.chatRepository.currentSessionId.collectAsState()
    val session = sessions.find { it.id == currentSessionId }
    val messages = session?.messages ?: emptyList()

    val listState = rememberLazyListState()

    // Auto-scroll rules:
    //  * while the answer is streaming        -> keep the END of the growing bubble in view;
    //  * after the suggestion chips are ready -> jump to the START of that answer;
    //  * afterwards the user scrolls freely   (no further auto-scroll).
    var wasStreaming by remember { mutableStateOf(false) }
    var awaitSuggestions by remember { mutableStateOf(false) }
    LaunchedEffect(streamingText, messages.size) {
        val streaming = streamingText.isNotEmpty()
        when {
            streaming -> {
                // The streaming bubble is the last item (index == messages.size). Snap it
                // into view, then align its bottom with the viewport bottom so the newest
                // tokens stay visible even when the answer is taller than the screen.
                val index = messages.size
                listState.scrollToItem(index)
                val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                if (info != null) {
                    val overflow = (info.offset + info.size) - listState.layoutInfo.viewportEndOffset
                    if (overflow > 0) listState.scrollToItem(index, overflow)
                }
            }
            wasStreaming && messages.isNotEmpty() -> {
                // The answer finished. Do NOT jump to its start yet: wait until the
                // suggestion chips are generated so the layout is stable first.
                awaitSuggestions = true
            }
            messages.isNotEmpty() -> {
                // A new message (e.g. the user's) was appended while idle.
                listState.scrollToItem(messages.size - 1)
            }
        }
        wasStreaming = streaming
    }

    // Once the suggestions are generated (or generation is skipped), show the
    // answer from its beginning; after that the user scrolls freely.
    LaunchedEffect(isGeneratingSuggestions, awaitSuggestions) {
        if (awaitSuggestions && !isGeneratingSuggestions) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
            awaitSuggestions = false
        }
    }

    // Opening a session: start at the latest message.
    LaunchedEffect(currentSessionId) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.size - 1)
    }

    // Suggestion chips — shared by both layouts
    val chipContent: @Composable () -> Unit = {
        // Refresh with dedup: asks the model for suggestions not shown yet
        AssistChip(
            onClick = { viewModel.generateSuggestions(onlyNew = true) },
            label = { Text(l("new_suggestions"), maxLines = 1) },
            leadingIcon = {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = l("refresh"),
                    modifier = Modifier.size(16.dp)
                )
            }
        )
        suggestions.forEach { suggestion ->
            AssistChip(
                onClick = { viewModel.onSuggestionClicked(suggestion) },
                modifier = Modifier.widthIn(max = 320.dp),
                label = {
                    Text(
                        suggestion,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            )
        }
        // Live "being typed" suggestion, so generation streams like a reply
        pendingSuggestion?.takeIf { it.isNotBlank() }?.let { pending ->
            AssistChip(
                onClick = {},
                enabled = false,
                modifier = Modifier.widthIn(max = 320.dp),
                label = {
                    Text(
                        "$pending…",
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
        }
        if (isGeneratingSuggestions) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp
            )
        }
    }

    // Suggestions block; "wrap" flows to new lines, "single_row" scrolls.
    // Chips appear as they stream in; a spinner is shown only before the first one.
    val hasSuggestionContent = suggestions.isNotEmpty() || !pendingSuggestion.isNullOrBlank()
    val suggestionsArea: @Composable () -> Unit = {
        AnimatedVisibility(
            visible = hasSuggestionContent || isGeneratingSuggestions,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                if (!hasSuggestionContent && isGeneratingSuggestions) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = l("generating_suggestions"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (suggestionsWrap) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        chipContent()
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        chipContent()
                    }
                }
            }
        }
    }

    // Input row (text field, voice, send/stop)
    val inputArea: @Composable () -> Unit = {
        Surface(
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                // Pending image preview
                if (!pendingImage.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp, vertical = 4.dp)
                    ) {
                        val bmp = remember(pendingImage) { BitmapFactory.decodeFile(pendingImage) }
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = l("attach_image"),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                        }
                        IconButton(
                            onClick = { viewModel.clearPendingImage() },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = l("remove_image"),
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }

                // Attached documents (tap a chip to open the documents panel)
                if (documents.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        documents.forEach { doc ->
                            AssistChip(
                                onClick = { showDocsSheet = true },
                                label = {
                                    Text(
                                        "📄 ${doc.name}",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                        }
                    }
                }

                if (isListening && partialResult.isNotEmpty()) {
                    Text(
                        text = partialResult,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box {
                        IconButton(
                            onClick = { showAttachMenu = true },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = l("attach"),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        DropdownMenu(
                            expanded = showAttachMenu,
                            onDismissRequest = { showAttachMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(l("documents")) },
                                leadingIcon = {
                                    Icon(Icons.Default.AttachFile, contentDescription = null)
                                },
                                onClick = {
                                    showAttachMenu = false
                                    showDocsSheet = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(l("attach_image")) },
                                leadingIcon = {
                                    Icon(Icons.Default.Image, contentDescription = null)
                                },
                                enabled = mmprojReady,
                                onClick = {
                                    showAttachMenu = false
                                    imagePicker.launch("image/*")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(l("take_photo")) },
                                leadingIcon = {
                                    Icon(Icons.Default.AddAPhoto, contentDescription = null)
                                },
                                enabled = mmprojReady,
                                onClick = {
                                    showAttachMenu = false
                                    takePhoto()
                                }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = if (isListening) partialResult else inputText,
                        onValueChange = { viewModel.setInputText(it) },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(l("input_placeholder")) },
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { sendCurrent() }),
                        shape = RoundedCornerShape(24.dp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = {
                            if (isListening) viewModel.stopVoiceInput()
                            else viewModel.startVoiceInput()
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                if (isListening) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = l("voice_input"),
                            tint = if (isListening) Color.White
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    if (isGenerating) {
                        IconButton(
                            onClick = { viewModel.stopGeneration() },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error)
                        ) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = l("stop"),
                                tint = Color.White
                            )
                        }
                    } else {
                        IconButton(
                            onClick = { sendCurrent() },
                            enabled = inputText.isNotBlank() && modelState == ModelState.LOADED,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(
                                    if (inputText.isNotBlank() && modelState == ModelState.LOADED)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                        ) {
                            Icon(
                                Icons.Default.Send,
                                contentDescription = l("send"),
                                tint = if (inputText.isNotBlank() && modelState == ModelState.LOADED)
                                    MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column(modifier = Modifier.statusBarsPadding()) {
                // RAM monitor + "free everything" action, above the model name.
                Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Memory,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${l("ram")} ${fmtRam(ramUsed)} / ${fmtRam(ramTotal)} · " +
                                "${l("ram_free")} ${fmtRam(ramFree)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { viewModel.freeRam() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            enabled = modelState != ModelState.LOADING
                        ) {
                            Text(l("free_ram"), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                TopAppBar(
                    windowInsets = WindowInsets(0),
                    title = {
                        Column {
                            Text(
                                text = modelName.ifEmpty { l("no_model") },
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (modelState == ModelState.LOADING) {
                                Text(
                                    text = "${l("loading")} ${(progress * 100).toInt()}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else if (modelState == ModelState.LOADED) {
                                // Model status feedback: vision availability + projector name
                                Text(
                                    text = if (mmprojReady) "👁 ${l("vision_ready")} · $mmprojName"
                                    else l("text_only"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (mmprojReady) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onOpenDrawer) {
                            Icon(Icons.Default.Menu, contentDescription = l("chat_history"))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Loading progress overlay
            AnimatedVisibility(
                visible = modelState == ModelState.LOADING,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = progressMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Input at the top when configured; suggestions always stay at the bottom
            if (inputAtTop) {
                inputArea()
            }

            // Messages
            Box(modifier = Modifier.weight(1f)) {
                if (messages.isEmpty() && streamingText.isEmpty()) {
                    // Empty state
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = if (modelState == ModelState.LOADED) "Start a conversation" else "Load a model to start chatting",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(messages) { message ->
                            MessageBubble(message = message, l = l)
                        }

                        // Streaming message
                        if (streamingText.isNotEmpty()) {
                            item {
                                MessageBubble(
                                    message = ChatMessage(role = "assistant", content = streamingText),
                                    l = l,
                                    isStreaming = true
                                )
                            }
                        }
                    }
                }
            }

            // Vision encoder feedback: encoding can take minutes, let the user cancel
            AnimatedVisibility(visible = isPreparingVision, enter = fadeIn(), exit = fadeOut()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = l("preparing_image"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { viewModel.stopGeneration() }) {
                        Text(l("stop"))
                    }
                }
            }

            // Suggestions always at the bottom (regardless of input position)
            suggestionsArea()

            // Input area at the bottom (only when not at the top)
            if (!inputAtTop) {
                inputArea()
            }
        }
    }

    // Documents panel ("chat with docs")
    if (showDocsSheet) {
        val docBudget = viewModel.documentBudgetTokens()
        val docUsed = documents.sumOf { DocumentReader.estimateTokens(it.text) }
        ModalBottomSheet(onDismissRequest = { showDocsSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
            ) {
                Text(l("documents"), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "${l("docs_context_hint")}: $docUsed / $docBudget",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (documents.isEmpty()) {
                    Text(
                        l("documents_empty"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                        items(documents, key = { it.id }) { doc ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Description,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        doc.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        buildString {
                                            append(formatDocSize(doc.sizeBytes))
                                            if (doc.truncated) {
                                                append(" · ").append(l("doc_truncated"))
                                            }
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { viewModel.removeDocument(doc.id) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = l("remove_document")
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { docPicker.launch(arrayOf("text/*", "application/json")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.AttachFile, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(l("add_document"))
                }
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(
                    onClick = { showDocsSheet = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(l("close"))
                }
            }
        }
    }
}

private fun formatDocSize(bytes: Long): String {
    val kb = bytes / 1024.0
    return if (kb < 1024) String.format("%.1f KB", kb)
    else String.format("%.2f MB", kb / 1024.0)
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    l: (String) -> String,
    isStreaming: Boolean = false
) {
    val isUser = message.role == "user"
    val bubbleColor = if (isUser) UserBubble else AssistantBubble
    val textColor = if (isUser) UserBubbleText else AssistantBubbleText

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text("AI", color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column {
                message.imagePath?.let { path ->
                    val bmp = remember(path) { BitmapFactory.decodeFile(path) }
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp)
                                .clip(
                                    RoundedCornerShape(
                                        topStart = 16.dp,
                                        topEnd = 16.dp
                                    )
                                )
                        )
                    }
                }
                MarkdownText(
                    text = message.content,
                    textColor = textColor,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
}


