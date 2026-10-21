package org.lacan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
private val Success @Composable get() = MaterialTheme.colorScheme.primary

private enum class LacanTab(val title: String) {
    Dictation("Диктовка"), Models("Модели"), History("История"), Settings("Настройки")
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LacanScreen(state: ScreenState, actions: UiActions) {
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(LacanTab.Dictation) }
    var confirmClear by remember { mutableStateOf(false) }
    val scrollStates = LacanTab.entries.map { rememberScrollState() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE) }
    var appearance by remember { mutableStateOf(Appearance.entries.firstOrNull { it.name == preferences.getString("theme", null) } ?: Appearance.System) }

    LacanTheme(appearance) {
        androidx.compose.material3.Scaffold(
            topBar = {
                androidx.compose.material3.TopAppBar(
                    title = { Text(if (tab == LacanTab.Dictation) "Lacan" else tab.title) },
                    colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            },
            bottomBar = {
                androidx.compose.material3.NavigationBar {
                    LacanTab.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = tab == destination,
                            onClick = { tab = destination },
                            icon = { androidx.compose.material3.Icon(destinationIcon(destination.ordinal), contentDescription = null) },
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
                    LacanTab.Dictation -> DictationContent(state, actions) { tab = LacanTab.Models }
                    LacanTab.Models -> ModelsContent(state, actions)
                    LacanTab.History -> HistoryContent(state, actions, onClear = { confirmClear = true })
                    LacanTab.Settings -> SettingsContent(state, actions, appearance) { value ->
                        appearance = value
                        preferences.edit().putString("theme", value.name).apply()
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
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
private fun DictationContent(state: ScreenState, actions: UiActions, openModels: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = MaterialTheme.shapes.extraLarge
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(if (state.recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary))
                    Spacer(Modifier.width(9.dp))
                    Text(if (state.recording) "ИДЁТ ЗАПИСЬ" else if (state.busy) "ОБРАБОТКА" else "СОСТОЯНИЕ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
                }
                Text(state.status, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
                if (!state.notificationsGranted) {
                    Text("Разрешите уведомления в настройках: там доступны Стоп и Отмена, когда вы в другом приложении.", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyMedium)
                }
                if (!state.microphoneGranted) {
                    Text("Для записи нужен доступ к микрофону. Его можно включить в настройках разрешений.", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.recording) {
                        Button(onClick = actions::stop, modifier = Modifier.weight(1f).height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                            Text("Стоп", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(onClick = actions::cancel, modifier = Modifier.weight(1f).height(52.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
                            Text("Отмена")
                        }
                    } else if (state.busy) {
                        OutlinedButton(onClick = actions::cancel, modifier = Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
                            Text("Отменить обработку")
                        }
                    } else {
                        Button(
                            onClick = actions::start,
                            enabled = state.microphoneGranted && state.notificationsGranted && state.enabled && state.models.any { it.selected && it.installed },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent)
                        ) { Text("Начать диктовку", fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }

        if (state.preview.isNotBlank()) {
            ContentCard(title = "Предварительный текст", supporting = "Текст может уточняться до остановки записи.") {
                SelectionContainer {
                    Text(state.preview, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Предварительный текст, можно выделить и скопировать" }, style = MaterialTheme.typography.bodyLarge, color = Ink)
                }
            }
        }

        if (state.result.isNotBlank()) {
            ContentCard(title = "Результат") {
                SelectionContainer {
                    Text(state.result, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Текст результата, можно выделить и скопировать" }, style = MaterialTheme.typography.bodyLarge, color = Ink)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { actions.copy(state.result) }, modifier = Modifier.weight(1f).height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Копировать") }
                    OutlinedButton(onClick = actions::dismissResult, modifier = Modifier.weight(1f).height(48.dp)) { Text("Закрыть") }
                }
            }
        }

        ContentCard(title = "Язык распознавания", supporting = "Выбор действует для следующей диктовки.") {
            LanguagePicker(state.language, enabled = !state.busy && !state.recording, onSelect = actions::setLanguage)
            Text("GigaAM v3 — русский с пунктуацией. Whisper, GigaAM Multilingual и Parakeet поддерживают русский и английский. Parakeet определяет язык автоматически; выбор языка к ней не применяется.", style = MaterialTheme.typography.bodySmall, color = MutedInk)
        }

        if (state.models.none { it.selected && it.installed }) {
            ContentCard(title = "Модель не выбрана", supporting = "Скачайте модель, чтобы начать локальное распознавание.") {
                Button(onClick = openModels, modifier = Modifier.fillMaxWidth().height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Выбрать модель") }
            }
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
        state.models.forEach { model ->
            val downloading = state.downloadingId == model.id
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
                            Text(model.description, style = MaterialTheme.typography.bodyMedium, color = MutedInk)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("${model.sizeMb} МБ", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Ink)
                    }
                    if (model.sizeMb >= 200) {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                            Text("Требовательная модель · ${model.sizeMb} МБ на устройстве. Загрузка и распознавание могут занять больше времени.", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                    if (downloading) {
                        val progress = state.downloadProgress.coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = Accent, trackColor = SoftAccent)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${if (model.localImportSupported) "Импорт" else "Загрузка"} · ${(progress * 100).toInt()}%", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MutedInk)
                            TextButton(onClick = actions::cancelDownload, modifier = Modifier.height(48.dp)) { Text("Отменить") }
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
                                model.downloadable -> Button(onClick = { actions.download(model.id) }, enabled = !state.busy && !state.recording && state.downloadingId == null, modifier = Modifier.fillMaxWidth().height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                                    Text("Скачать · ${model.sizeMb} МБ")
                                }
                            }
                        }
                    }
                }
            }
        }
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
private fun SettingsContent(state: ScreenState, actions: UiActions, appearance: Appearance, onAppearance: (Appearance) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            Text("GigaAM v3 — русский с пунктуацией. Whisper, GigaAM Multilingual и Parakeet поддерживают русский и английский. Для Whisper можно выбрать «Русский» явно; Parakeet всегда определяет язык автоматически.", style = MaterialTheme.typography.bodySmall, color = MutedInk)
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
            Text(if (granted) "Есть" else "Нет", style = MaterialTheme.typography.labelLarge, color = if (granted) Success else MutedInk, fontWeight = FontWeight.SemiBold)
        }
        TextButton(onClick = onAction, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text(if (granted) "Разрешение включено · настройки" else actionLabel, modifier = Modifier.fillMaxWidth())
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
