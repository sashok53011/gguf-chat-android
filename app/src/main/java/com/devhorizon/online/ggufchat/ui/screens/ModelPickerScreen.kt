package com.devhorizon.online.ggufchat.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Eject
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devhorizon.online.ggufchat.data.llm.GgufSearchResult
import com.devhorizon.online.ggufchat.data.model.ModelInfo
import com.devhorizon.online.ggufchat.data.model.ModelState
import com.devhorizon.online.ggufchat.ui.theme.Localization
import com.devhorizon.online.ggufchat.ui.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val lang by viewModel.appLanguage.collectAsState()
    val l = { key: String -> Localization.getString(key, lang) }

    val models by viewModel.llmEngine.models.collectAsState()
    val modelState by viewModel.llmEngine.state.collectAsState()
    val modelName by viewModel.llmEngine.modelName.collectAsState()
    val progress by viewModel.llmEngine.progress.collectAsState()
    val progressMessage by viewModel.llmEngine.progressMessage.collectAsState()

    var modelToDelete by remember { mutableStateOf<ModelInfo?>(null) }
    var showDownloadDialog by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var showQuickDialog by remember { mutableStateOf(false) }

    val isDownloading by viewModel.llmEngine.isDownloading.collectAsState()
    val downloadProgress by viewModel.llmEngine.downloadProgress.collectAsState()
    val downloadMessage by viewModel.llmEngine.downloadMessage.collectAsState()

    val isSearching by viewModel.isSearching.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val searchError by viewModel.searchError.collectAsState()
    val downloadError by viewModel.downloadError.collectAsState()

    val context = LocalContext.current
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            // Take persistable permission
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            viewModel.importModel(it)
        }
    }

    // ---- Projector (mmproj) attach / download ----
    var projectorTarget by remember { mutableStateOf<ModelInfo?>(null) }
    val projectorPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val target = projectorTarget
        if (uri != null && target != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            viewModel.attachMmprojToModel(target, uri)
        }
    }
    var projectorDownloadTarget by remember { mutableStateOf<ModelInfo?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(l("models")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = l("back"))
                    }
                },
                actions = {
                    IconButton(onClick = { showQuickDialog = true }) {
                        Icon(Icons.Default.CloudDownload, contentDescription = l("quick_downloads"))
                    }
                    IconButton(onClick = { showSearchDialog = true }) {
                        Icon(Icons.Default.Search, contentDescription = l("search_models"))
                    }
                    IconButton(onClick = { showDownloadDialog = true }) {
                        Icon(Icons.Default.Download, contentDescription = l("download_model"))
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { filePickerLauncher.launch(arrayOf("*/*")) }
            ) {
                Icon(Icons.Default.Add, contentDescription = l("load_model"))
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Import / download error banner (the search dialog closes silently,
            // so a rejected download used to leave the user with no explanation).
            downloadError?.let { msg ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = msg,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        IconButton(onClick = { viewModel.clearDownloadError() }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = l("cancel"),
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            // Loading progress
            if (modelState == ModelState.LOADING) {
                Column(modifier = Modifier.padding(16.dp)) {
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

            if (models.isEmpty()) {
                // Empty state
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.Memory,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = l("no_model"),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = l("tap_import_hint"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(models) { model ->
                        ModelCard(
                            model = model,
                            isLoaded = model.name == modelName && modelState == ModelState.LOADED,
                            isLoading = model.name == modelName && modelState == ModelState.LOADING,
                            l = l,
                            onLoad = {
                                viewModel.loadModel(model)
                                onBack()
                            },
                            onUnload = { viewModel.unloadModel() },
                            onDelete = { modelToDelete = model },
                            onAttachProjector = {
                                projectorTarget = model
                                projectorPicker.launch(arrayOf("*/*"))
                            },
                            onDownloadProjector = { projectorDownloadTarget = model },
                            onRemoveProjector = { viewModel.removeMmprojFromModel(model) }
                        )
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    modelToDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { modelToDelete = null },
            title = { Text(l("confirm_delete")) },
            text = { Text("${l("delete_model")}: ${model.name}?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteModel(model)
                    modelToDelete = null
                }) {
                    Text(l("confirm"))
                }
            },
            dismissButton = {
                TextButton(onClick = { modelToDelete = null }) {
                    Text(l("cancel"))
                }
            }
        )
    }

    // Download from URL dialog
    if (showDownloadDialog) {
        DownloadModelDialog(
            l = l,
            isDownloading = isDownloading,
            progress = downloadProgress,
            message = downloadMessage,
            onDownload = { url, name ->
                viewModel.downloadModelFromUrl(url, name)
            },
            onCancel = { viewModel.cancelModelDownload() },
            onDismiss = { if (!isDownloading) showDownloadDialog = false }
        )
    }

    // Quick downloads: preset models + their Q8 projectors
    if (showQuickDialog) {
        QuickDownloadDialog(
            l = l,
            isDownloading = isDownloading,
            progress = downloadProgress,
            message = downloadMessage,
            onDownload = { fileName, url -> viewModel.downloadModelFromUrl(url, fileName) },
            onCancel = { viewModel.cancelModelDownload() },
            onDismiss = { if (!isDownloading) showQuickDialog = false }
        )
    }

    // Search models online dialog
    if (showSearchDialog) {
        SearchModelsDialog(
            l = l,
            isSearching = isSearching,
            results = searchResults,
            errorKey = searchError,
            isDownloading = isDownloading,
            downloadProgress = downloadProgress,
            downloadMessage = downloadMessage,
            onSearch = { query, maxMB, includeProj -> viewModel.searchModels(query, maxMB, includeProj) },
            onDownload = { result ->
                viewModel.downloadModelFromUrl(result.url, result.fileName)
                showSearchDialog = false
            },
            onDismiss = { if (!isSearching) showSearchDialog = false }
        )
    }

    // Download a projector (mmproj) for a specific model
    projectorDownloadTarget?.let { model ->
        ProjectorDownloadDialog(
            l = l,
            modelName = model.name,
            isDownloading = isDownloading,
            progress = downloadProgress,
            message = downloadMessage,
            onDownload = { url, name ->
                viewModel.downloadMmprojForModel(model, url, name)
                projectorDownloadTarget = null
            },
            onDismiss = { if (!isDownloading) projectorDownloadTarget = null }
        )
    }
}

@Composable
private fun ProjectorDownloadDialog(
    l: (String) -> String,
    modelName: String,
    isDownloading: Boolean,
    progress: Float,
    message: String,
    onDownload: (url: String, name: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        title = { Text(l("download_projector")) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (isDownloading) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = modelName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text(l("projector_url")) },
                        placeholder = { Text("https://huggingface.co/.../mmproj-...gguf") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(l("file_name")) },
                        placeholder = { Text("mmproj-model-f16.gguf") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            if (isDownloading) {
                TextButton(onClick = onDismiss) { Text(l("cancel")) }
            } else {
                Button(
                    onClick = { onDownload(url, name.ifBlank { null }) },
                    enabled = url.isNotBlank()
                ) {
                    Text(l("download"))
                }
            }
        },
        dismissButton = {
            if (!isDownloading) {
                TextButton(onClick = onDismiss) { Text(l("cancel")) }
            }
        }
    )
}

@Composable
private fun ModelCard(
    model: ModelInfo,
    isLoaded: Boolean,
    isLoading: Boolean,
    l: (String) -> String,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onDelete: () -> Unit,
    onAttachProjector: () -> Unit,
    onDownloadProjector: () -> Unit,
    onRemoveProjector: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onLoad() },
        colors = CardDefaults.cardColors(
            containerColor = if (isLoaded) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Memory,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = if (isLoaded) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = formatSize(model.sizeBytes),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${model.architecture} · ${model.contextLength} ctx",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Projector (mmproj) status + actions
                val projectorName = model.mmprojPath?.let { java.io.File(it).name }
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Image,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (projectorName != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = projectorName ?: l("no_projector"),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (projectorName != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = onAttachProjector,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                    ) {
                        Text(l("attach_projector"), style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(
                        onClick = onDownloadProjector,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                    ) {
                        Text(l("download_projector"), style = MaterialTheme.typography.labelSmall)
                    }
                    if (projectorName != null) {
                        Spacer(modifier = Modifier.width(4.dp))
                        TextButton(
                            onClick = onRemoveProjector,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                        ) {
                            Text(
                                l("remove_projector"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                if (isLoading) {
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            if (isLoaded) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = l("model_loaded"),
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                IconButton(onClick = onUnload) {
                    Icon(
                        Icons.Default.Eject,
                        contentDescription = l("unload_model"),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = l("delete_model"),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun DownloadModelDialog(
    l: (String) -> String,
    isDownloading: Boolean,
    progress: Float,
    message: String,
    onDownload: (url: String, name: String?) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var wasDownloading by remember { mutableStateOf(false) }

    // Close dialog automatically when download finishes
    LaunchedEffect(isDownloading) {
        if (wasDownloading && !isDownloading) {
            onDismiss()
        }
        wasDownloading = isDownloading
    }

    AlertDialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        title = { Text(l("download_model")) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (isDownloading) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text(l("model_url")) },
                        placeholder = { Text("https://huggingface.co/.../model.gguf") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(l("file_name")) },
                        placeholder = { Text("model.gguf") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = l("download_hint"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            if (isDownloading) {
                TextButton(onClick = onCancel) {
                    Text(l("cancel"))
                }
            } else {
                Button(
                    onClick = { onDownload(url, name.ifBlank { null }) },
                    enabled = url.isNotBlank()
                ) {
                    Text(l("download"))
                }
            }
        },
        dismissButton = {
            if (!isDownloading) {
                TextButton(onClick = onDismiss) {
                    Text(l("cancel"))
                }
            }
        }
    )
}

@Composable
private fun SearchModelsDialog(
    l: (String) -> String,
    isSearching: Boolean,
    results: List<GgufSearchResult>,
    errorKey: String?,
    isDownloading: Boolean,
    downloadProgress: Float,
    downloadMessage: String,
    onSearch: (query: String, maxFileSizeMB: Long, includeProjectors: Boolean) -> Unit,
    onDownload: (GgufSearchResult) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var maxFileSizeMB by remember { mutableStateOf(2048L) }
    var selected by remember { mutableStateOf<GgufSearchResult?>(null) }
    var includeProjectors by remember { mutableStateOf(false) }

    val sizePresets = listOf(
        512L to "512 MB",
        1024L to "1 GB",
        2048L to "2 GB",
        4096L to "4 GB",
        8192L to "8 GB",
        0L to l("no_limit")
    )

    AlertDialog(
        onDismissRequest = { if (!isSearching) onDismiss() },
        title = { Text(l("search_models")) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(l("search_keyword")) },
                    placeholder = { Text("qwen, phi, llama...") },
                    singleLine = true,
                    enabled = !isSearching,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = l("max_size_mb"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                // Preset size buttons (rows of 3)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    sizePresets.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { (value, label) ->
                                val isSelected = maxFileSizeMB == value
                                OutlinedButton(
                                    onClick = { maxFileSizeMB = value },
                                    enabled = !isSearching,
                                    modifier = Modifier.weight(1f),
                                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surface
                                    )
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(
                        checked = includeProjectors,
                        onCheckedChange = { includeProjectors = it },
                        enabled = !isSearching
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = l("include_projectors"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { onSearch(query, maxFileSizeMB, includeProjectors) },
                    enabled = query.isNotBlank() && !isSearching && !isDownloading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isSearching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(l("search"))
                }

                if (isDownloading) {
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = downloadMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (!isSearching && errorKey != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = l(errorKey),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (results.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${l("search_results")} (${results.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(360.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(results) { result ->
                            SearchResultRow(
                                result = result,
                                isSelected = selected?.url == result.url,
                                l = l,
                                onClick = { selected = result }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { selected?.let(onDownload) },
                enabled = selected != null && !isSearching && !isDownloading
            ) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(l("download"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSearching) {
                Text(l("cancel"))
            }
        }
    )
}

@Composable
private fun SearchResultRow(
    result: GgufSearchResult,
    isSelected: Boolean,
    l: (String) -> String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = isSelected, onClick = onClick)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = formatSize(result.sizeBytes),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = result.repoId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "↓ ${result.downloads}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}

/** One-tap downloads: the two tested vision models and their Q8 projectors. */
private data class QuickDownload(
    val label: String,
    val fileName: String,
    val url: String,
    val note: String
)

private val QUICK_DOWNLOADS = listOf(
    QuickDownload(
        label = "Gemma 4 E2B (Q4_K_M)",
        fileName = "gemma-4-E2B-it-Q4_K_M.gguf",
        url = "https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_K_M.gguf",
        note = "~3.0 GB - model"
    ),
    QuickDownload(
        label = "Gemma 4 E2B - mmproj Q8",
        fileName = "mmproj-gemma-4-E2B-it-Q8_0.gguf",
        url = "https://huggingface.co/prithivMLmods/gemma-4-E2B-it-F32-GGUF/resolve/main/GGUF/gemma-4-E2B-it.mmproj-q8_0.gguf",
        note = "~0.5 GB - vision projector"
    ),
    QuickDownload(
        label = "Qwen2-VL 2B (Q4_K_M)",
        fileName = "Qwen2-VL-2B-Instruct-Q4_K_M.gguf",
        url = "https://huggingface.co/ggml-org/Qwen2-VL-2B-Instruct-GGUF/resolve/main/Qwen2-VL-2B-Instruct-Q4_K_M.gguf",
        note = "~0.9 GB - model"
    ),
    QuickDownload(
        label = "Qwen2-VL 2B - mmproj Q8",
        fileName = "mmproj-Qwen2-VL-2B-Instruct-Q8_0.gguf",
        url = "https://huggingface.co/ggml-org/Qwen2-VL-2B-Instruct-GGUF/resolve/main/mmproj-Qwen2-VL-2B-Instruct-Q8_0.gguf",
        note = "~0.7 GB - vision projector"
    )
)

@Composable
private fun QuickDownloadDialog(
    l: (String) -> String,
    isDownloading: Boolean,
    progress: Float,
    message: String,
    onDownload: (fileName: String, url: String) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        title = { Text(l("quick_downloads")) },
        text = {
            Column {
                QUICK_DOWNLOADS.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                item.note,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            onClick = { onDownload(item.fileName, item.url) },
                            enabled = !isDownloading
                        ) {
                            Text(l("download"))
                        }
                    }
                }
                if (isDownloading) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            if (isDownloading) {
                TextButton(onClick = onCancel) { Text(l("cancel")) }
            } else {
                TextButton(onClick = onDismiss) { Text(l("close")) }
            }
        }
    )
}
