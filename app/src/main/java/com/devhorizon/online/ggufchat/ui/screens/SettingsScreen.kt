package com.devhorizon.online.ggufchat.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.devhorizon.online.ggufchat.data.model.LlmSettings
import com.devhorizon.online.ggufchat.ui.theme.Localization
import com.devhorizon.online.ggufchat.ui.viewmodel.ChatViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val lang by viewModel.appLanguage.collectAsState()
    val l = { key: String -> Localization.getString(key, lang) }
    val settings by viewModel.llmEngine.settings.collectAsState()

    var currentSettings by remember(settings) { mutableStateOf(settings) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(l("settings")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = l("back"))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Language
            SectionTitle(l("language"))
            DropdownSelector(
                label = l("language"),
                options = listOf("en" to l("english"), "ru" to l("russian"), "de" to l("german")),
                selected = currentSettings.appLanguage,
                onSelect = { currentSettings = currentSettings.copy(appLanguage = it) }
            )

            DropdownSelector(
                label = l("answer_language"),
                options = listOf(
                    "auto" to l("lang_auto"),
                    "en" to l("english"),
                    "ru" to l("russian"),
                    "de" to l("german")
                ),
                selected = currentSettings.answerLanguage,
                onSelect = { currentSettings = currentSettings.copy(answerLanguage = it) }
            )

            // Theme
            SectionTitle(l("appearance"))
            DropdownSelector(
                label = l("appearance"),
                options = listOf(
                    "light" to "Light",
                    "dark" to "Dark",
                    "green" to "Green",
                    "orange" to "Orange",
                    "light_blue" to "Light Blue",
                    "yellow" to "Yellow"
                ),
                selected = currentSettings.appTheme,
                onSelect = { currentSettings = currentSettings.copy(appTheme = it) }
            )

            // Interface layout
            SectionTitle(l("interface_section"))

            DropdownSelector(
                label = l("input_position"),
                options = listOf(
                    "bottom" to l("position_bottom"),
                    "top" to l("position_top")
                ),
                selected = currentSettings.inputPosition,
                onSelect = { currentSettings = currentSettings.copy(inputPosition = it) }
            )

            DropdownSelector(
                label = l("suggestions_layout"),
                options = listOf(
                    "wrap" to l("layout_wrap"),
                    "single_row" to l("layout_single_row")
                ),
                selected = currentSettings.suggestionsLayout,
                onSelect = { currentSettings = currentSettings.copy(suggestionsLayout = it) }
            )

            // Inference settings
            SectionTitle(l("inference_settings"))

            OutlinedTextField(
                value = currentSettings.systemPrompt,
                onValueChange = { currentSettings = currentSettings.copy(systemPrompt = it) },
                label = { Text(l("system_prompt")) },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3
            )

            SliderSetting(
                label = l("temperature"),
                value = currentSettings.temperature,
                range = 0f..2f,
                onValueChange = { currentSettings = currentSettings.copy(temperature = it) }
            )

            SliderSetting(
                label = l("top_p"),
                value = currentSettings.topP,
                range = 0f..1f,
                onValueChange = { currentSettings = currentSettings.copy(topP = it) }
            )

            SliderSetting(
                label = l("max_tokens"),
                value = currentSettings.maxTokens.toFloat(),
                range = 64f..4096f,
                steps = 63,
                onValueChange = { currentSettings = currentSettings.copy(maxTokens = it.roundToInt()) }
            )

            SliderSetting(
                label = l("context_size"),
                value = currentSettings.contextLength.toFloat(),
                range = 512f..8192f,
                steps = 15,
                onValueChange = { currentSettings = currentSettings.copy(contextLength = it.roundToInt()) }
            )

            SliderSetting(
                label = l("threads"),
                value = currentSettings.threads.toFloat(),
                range = 1f..8f,
                steps = 6,
                onValueChange = { currentSettings = currentSettings.copy(threads = it.roundToInt()) }
            )

            // Voice settings
            SectionTitle(l("voice_settings"))

            SwitchSetting(
                label = l("tts_enabled"),
                checked = currentSettings.ttsEnabled,
                onCheckedChange = { currentSettings = currentSettings.copy(ttsEnabled = it) }
            )

            DropdownSelector(
                label = l("tts_language"),
                options = listOf("en" to l("english"), "ru" to l("russian"), "de" to l("german")),
                selected = currentSettings.ttsLanguage,
                onSelect = { currentSettings = currentSettings.copy(ttsLanguage = it) }
            )

            SliderSetting(
                label = l("tts_rate"),
                value = currentSettings.ttsRate,
                range = 0.5f..2f,
                onValueChange = { currentSettings = currentSettings.copy(ttsRate = it) }
            )

            DropdownSelector(
                label = l("stt_language"),
                options = listOf("en" to l("english"), "ru" to l("russian"), "de" to l("german")),
                selected = currentSettings.sttLanguage,
                onSelect = { currentSettings = currentSettings.copy(sttLanguage = it) }
            )

            // Suggestion settings
            SectionTitle(l("suggestion_settings"))

            SwitchSetting(
                label = l("auto_suggest"),
                checked = currentSettings.autoSuggest,
                onCheckedChange = { currentSettings = currentSettings.copy(autoSuggest = it) }
            )

            SliderSetting(
                label = l("suggestion_count"),
                value = currentSettings.suggestionCount.toFloat(),
                range = 2f..8f,
                steps = 5,
                onValueChange = { currentSettings = currentSettings.copy(suggestionCount = it.roundToInt()) }
            )

            SliderSetting(
                label = l("suggestion_doc_tokens"),
                value = currentSettings.suggestionDocTokens.toFloat(),
                range = 0f..2048f,
                steps = 7,
                onValueChange = { currentSettings = currentSettings.copy(suggestionDocTokens = (it / 256f).roundToInt() * 256) }
            )

            // Save button
            Spacer(modifier = Modifier.height(16.dp))
            androidx.compose.material3.Button(
                onClick = {
                    viewModel.updateSettings(currentSettings)
                    onBack()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(l("save"))
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun SliderSetting(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (value == value.roundToInt().toFloat()) value.roundToInt().toString()
                else "%.2f".format(value),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps
        )
    }
}

@Composable
private fun SwitchSetting(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownSelector(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.find { it.first == selected }?.second ?: selected

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (value, display) ->
                DropdownMenuItem(
                    text = { Text(display) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    }
                )
            }
        }
    }
}
