package org.lacan.ui

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Semantic aliases keep every surface, including dialogs, in the active theme.
private val Ink @Composable get() = MaterialTheme.colorScheme.onSurface
private val MutedInk @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val CardPaper @Composable get() = MaterialTheme.colorScheme.surfaceContainerLow
private val Accent @Composable get() = MaterialTheme.colorScheme.primary
private val SoftAccent @Composable get() = MaterialTheme.colorScheme.primaryContainer
private val Line @Composable get() = MaterialTheme.colorScheme.outlineVariant
private val Success @Composable get() = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFF81C784) else Color(0xFF2E7D32)

private enum class LacanTab(val title: String) {
    Models("Модели"), History("История"), Settings("Настройки")
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LacanScreen(
    state: ScreenState,
    actions: UiActions,
    openSettingsRequest: Int = 0,
    setupMessage: String? = null,
    onSetupMessageConsumed: () -> Unit = {}
) {
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(LacanTab.Models) }
    var confirmClear by remember { mutableStateOf(false) }
    val scrollStates = LacanTab.entries.map { rememberScrollState() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE) }
    var appearance by remember { mutableStateOf(Appearance.entries.firstOrNull { it.name == preferences.getString("theme", null) } ?: Appearance.System) }

    val setupPreferences = remember(context) { context.getSharedPreferences("setup", android.content.Context.MODE_PRIVATE) }
    var showSetup by androidx.compose.runtime.saveable.rememberSaveable {
        mutableStateOf(!setupPreferences.getBoolean("presented", false) && state.nextSetupStep() != null)
    }
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    androidx.compose.runtime.LaunchedEffect(openSettingsRequest) {
        if (openSettingsRequest > 0) { tab = LacanTab.Settings; showSetup = false }
    }
    androidx.compose.runtime.LaunchedEffect(setupMessage) {
        if (setupMessage != null) { snackbar.showSnackbar(setupMessage); onSetupMessageConsumed() }
    }
    androidx.compose.runtime.LaunchedEffect(showSetup, state.nextSetupStep()) {
        if (showSetup) setupPreferences.edit().putBoolean("presented", true).apply()
        if (state.nextSetupStep() == null) showSetup = false
    }

    LacanTheme(appearance) {
        androidx.compose.material3.Scaffold(
            snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
            topBar = {
                androidx.compose.material3.TopAppBar(
                    title = { Text(tab.title) },
                    colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            },
            bottomBar = {
                androidx.compose.material3.NavigationBar {
                    LacanTab.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = tab == destination,
                            onClick = { tab = destination },
                            icon = { androidx.compose.material3.Icon(destinationIcon(destination.ordinal + 1), contentDescription = null) },
                            label = { Text(destination.title, maxLines = 1) }
                        )
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(scrollStates[tab.ordinal]).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when (tab) {
                    LacanTab.Models -> ModelsContent(state, actions)
                    LacanTab.History -> HistoryContent(state, actions, onClear = { confirmClear = true })
                    LacanTab.Settings -> SettingsContent(state, actions, appearance, onSetup = { showSetup = true }) { value ->
                        appearance = value
                        preferences.edit().putString("theme", value.name).apply()
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        if (showSetup) SetupWizard(state, actions, onDismiss = { showSetup = false })
        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text("Очистить историю?") },
                text = { Text("Все сохранённые расшифровки будут удалены с устройства.") },
                confirmButton = {
                    TextButton(onClick = { actions.clearHistory(); confirmClear = false }, modifier = Modifier.height(48.dp)) {
                        Text("Удалить всё", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmClear = false }, modifier = Modifier.height(48.dp)) { Text("Отмена") }
                }
            )
        }
    }
}

@Composable
private fun ModelsContent(state: ScreenState, actions: UiActions) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Скачайте модель или импортируйте локальный файл. Распознавание выполняется на устройстве.", style = MaterialTheme.typography.bodyMedium, color = MutedInk)
        if (state.models.isEmpty()) {
            EmptyCard("Каталог моделей пока пуст.")
        }
        Text(state.status, style = MaterialTheme.typography.bodySmall, color = MutedInk)
        state.models.forEach { model ->
            val downloading = state.downloadingId == model.id
            val queued = model.id in state.queuedDownloadIds && state.downloadingId != model.id
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.outlinedCardColors(containerColor = CardPaper),
                border = androidx.compose.foundation.BorderStroke(if (model.selected) 1.5.dp else 1.dp, if (model.selected) Accent else Line)
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(model.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Ink)
                            RecommendedModelLabel(model.id)
                            Text(model.description, style = MaterialTheme.typography.bodyMedium, color = MutedInk)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("${model.sizeMb} МБ", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Ink)
                    }
                    if (downloading || queued) {
                        val progress = (state.downloadProgressById[model.id] ?: if (queued) 0f else state.downloadProgress).coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = Accent, trackColor = SoftAccent)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (queued) "В очереди · ${(progress * 100).toInt()}%" else "${if (model.localImportSupported) "Импорт" else "Загрузка"} · ${(progress * 100).toInt()}%", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MutedInk)
                            TextButton(onClick = { actions.cancelDownload(model.id) }, modifier = Modifier.height(48.dp)) { Text("Отменить") }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            when {
                                model.installed -> {
                                    if (model.selected) {
                                        Text("Выбрана", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = Success, fontWeight = FontWeight.SemiBold)
                                    } else {
                                        OutlinedButton(onClick = { actions.selectModel(model.id) }, enabled = !state.busy && !state.recording && state.downloadingId == null, modifier = Modifier.weight(1f).height(48.dp)) { Text("Выбрать") }
                                    }
                                    TextButton(onClick = { actions.removeModel(model.id) }, enabled = !state.busy && !state.recording && state.downloadingId == null && !model.selected, modifier = Modifier.height(48.dp)) { Text("Удалить") }
                                }
                                model.localImportSupported -> Button(onClick = { actions.importModel(model.id) }, enabled = !state.busy && !state.recording && state.downloadingId == null, modifier = Modifier.fillMaxWidth().height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                                    Text("Импортировать .mnn")
                                }
                                model.downloadable -> Button(onClick = { actions.download(model.id) }, enabled = !state.busy && !state.recording, modifier = Modifier.fillMaxWidth().height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                                    Text("Скачать")
                                }
                            }
                        }
                    }
                    state.downloadErrors[model.id]?.let { error ->
                        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
internal fun RecommendedModelLabel(modelId: String) {
    if (modelId == "gigaam-v3-fp32") {
        Text("Рекомендуемая", style = MaterialTheme.typography.labelSmall, color = Success)
    }
}

@Composable
private fun HistoryContent(state: ScreenState, actions: UiActions, onClear: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Сохранённые расшифровки хранятся на устройстве.", style = MaterialTheme.typography.bodyMedium, color = MutedInk)
            }
            if (state.history.isNotEmpty()) TextButton(onClick = onClear, modifier = Modifier.height(48.dp)) { Text("Очистить") }
        }
        if (state.history.isEmpty()) EmptyCard("Здесь появятся завершённые диктовки.")
        state.history.forEach { item ->
            ContentCard(title = item.date, supporting = item.detail) {
                SelectionContainer {
                    Text(item.text, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Расшифровка, можно выделить и скопировать" }, style = MaterialTheme.typography.bodyLarge, color = Ink)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { actions.copy(item.text) }, modifier = Modifier.weight(1f).height(48.dp)) { Text("Копировать") }
                    TextButton(onClick = { actions.deleteHistory(item.id) }, modifier = Modifier.weight(1f).height(48.dp)) { Text("Удалить") }
                }
            }
        }
    }
}

@Composable
private fun SettingsContent(state: ScreenState, actions: UiActions, appearance: Appearance, onSetup: () -> Unit, onAppearance: (Appearance) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ContentCard(title = "Настройка Lacan", supporting = state.setupBlockReason() ?: "Всё готово для диктовки в других приложениях.") {
            OutlinedButton(onClick = onSetup, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Открыть мастер настройки") }
        }
        ContentCard(title = "Оформление", supporting = "Системная тема следует настройкам Android.") {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Appearance.entries.forEach { item ->
                    androidx.compose.material3.FilterChip(selected = appearance == item, onClick = { onAppearance(item) }, label = { Text(item.title) }, modifier = Modifier.height(48.dp))
                }
            }
        }
        ContentCard(title = "Работа Lacan", supporting = "Когда приложение выключено, панель диктовки недоступна.") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.enabled) "Включено" else "Выключено", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = Ink)
                Switch(checked = state.enabled, onCheckedChange = actions::setEnabled, enabled = !state.busy && !state.recording, modifier = Modifier.semantics { contentDescription = "Включить или выключить Lacan" })
            }
        }
        ContentCard(title = "Разрешения", supporting = "Android открывает системные экраны для каждого доступа.") {
            PermissionRow("Микрофон", "Нужен для записи речи", state.microphoneGranted, "Открыть настройки микрофона", actions::microphone)
            PermissionRow("Уведомления", "Показывают управление активной записью", state.notificationsGranted, "Открыть настройки уведомлений", actions::notifications)
            PermissionRow("Плавающая панель", "Позволяет управлять диктовкой поверх приложений", state.overlayGranted, "Открыть настройки панели", actions::overlay)
            PermissionRow("Специальные возможности", "Нужны для ввода текста в активное поле", state.accessibilityEnabled, "Открыть настройки специальных возможностей", actions::accessibility)
            Text("При установке APK в обход магазина Android может попросить отдельно разрешить включение службы специальных возможностей.", style = MaterialTheme.typography.bodySmall, color = MutedInk)
        }
        ContentCard(title = "Язык", supporting = "Настройка применяется при следующем запуске распознавания.") {
            LanguagePicker(state.language, enabled = !state.busy && !state.recording, onSelect = actions::setLanguage)
            Text("GigaAM Multilingual и Parakeet определяют язык автоматически.", style = MaterialTheme.typography.bodySmall, color = MutedInk)
        }
    }
}

@Composable
private fun PermissionRow(title: String, description: String, granted: Boolean, actionLabel: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Ink)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MutedInk)
            }
            androidx.compose.material3.Icon(
                imageVector = if (granted) permissionCheckIcon else permissionCrossIcon,
                contentDescription = if (granted) "Разрешение включено" else "Разрешение не включено",
                tint = if (granted) Success else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(24.dp)
            )
        }
        if (!granted) TextButton(onClick = onAction, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text(actionLabel, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun LanguagePicker(language: String, enabled: Boolean, onSelect: (String) -> Unit) {
    val options = listOf("auto" to "Авто", "ru" to "Русский", "en" to "English")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            androidx.compose.material3.FilterChip(
                selected = language == value,
                onClick = { onSelect(value) },
                enabled = enabled,
                label = { Text(label) },
                modifier = Modifier.height(48.dp)
            )
        }
    }
}

@Composable
private fun ContentCard(title: String, supporting: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CardPaper), shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Ink, fontWeight = FontWeight.Bold)
            if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodySmall, color = MutedInk)
            content()
        }
    }
}

@Composable
private fun EmptyCard(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = CardPaper), shape = MaterialTheme.shapes.large) {
        Text(message, modifier = Modifier.fillMaxWidth().padding(18.dp), style = MaterialTheme.typography.bodyMedium, color = MutedInk)
    }
}

private fun permissionIcon(name: String, path: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(pathData = PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
        .build()

private val permissionCheckIcon = permissionIcon("PermissionGranted", "M9,16.17L4.83,12l-1.42,1.41L9,19L21,7l-1.41,-1.41z")
private val permissionCrossIcon = permissionIcon("PermissionMissing", "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z")
