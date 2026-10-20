package org.lacan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val Ink = androidx.compose.ui.graphics.Color(0xFF17243A)
private val MutedInk = androidx.compose.ui.graphics.Color(0xFF647084)
private val Paper = androidx.compose.ui.graphics.Color(0xFFF5F2EC)
private val CardPaper = androidx.compose.ui.graphics.Color(0xFFFFFEFB)
private val Accent = androidx.compose.ui.graphics.Color(0xFFE9783B)
private val SoftAccent = androidx.compose.ui.graphics.Color(0xFFFFE8D9)
private val Line = androidx.compose.ui.graphics.Color(0xFFE5E0D7)
private val Success = androidx.compose.ui.graphics.Color(0xFF3E775C)

private enum class LacanTab(val title: String) {
    Dictation("Диктовка"), Models("Модели"), History("История"), Settings("Настройки")
}

@Composable
fun LacanScreen(state: ScreenState, actions: UiActions) {
    var tab by remember { mutableStateOf(LacanTab.Dictation) }
    var confirmClear by remember { mutableStateOf(false) }

    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            primary = Accent,
            onPrimary = androidx.compose.ui.graphics.Color.White,
            secondary = Ink,
            onSurface = Ink,
            surface = CardPaper,
            background = Paper
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding(), color = Paper) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(org.lacan.R.drawable.lacan_art),
                            contentDescription = "Lacan",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(42.dp).clip(RoundedCornerShape(14.dp))
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Lacan", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink)
                            Text("Голос остаётся на устройстве", style = MaterialTheme.typography.bodySmall, color = MutedInk)
                        }
                        Box(
                            Modifier.size(10.dp).clip(CircleShape).background(if (state.enabled) Success else MutedInk)
                                .semantics { contentDescription = if (state.enabled) "Lacan включён" else "Lacan выключен" }
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        LacanTab.entries.forEach { item ->
                            val selected = item == tab
                            TextButton(
                                onClick = { tab = item },
                                modifier = Modifier.height(48.dp).semantics { contentDescription = "Раздел ${item.title}${if (selected) ", выбран" else ""}" },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = if (selected) Ink else MutedInk,
                                    containerColor = if (selected) SoftAccent else androidx.compose.ui.graphics.Color.Transparent
                                ),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text(item.title, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    when (tab) {
                        LacanTab.Dictation -> DictationContent(state, actions) { tab = LacanTab.Models }
                        LacanTab.Models -> ModelsContent(state, actions)
                        LacanTab.History -> HistoryContent(state, actions, onClear = { confirmClear = true })
                        LacanTab.Settings -> SettingsContent(state, actions)
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить историю?") },
            text = { Text("Все сохранённые расшифровки будут удалены с устройства.") },
            confirmButton = {
                TextButton(onClick = { actions.clearHistory(); confirmClear = false }, modifier = Modifier.height(48.dp)) {
                    Text("Удалить всё", color = Accent, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }, modifier = Modifier.height(48.dp)) { Text("Отмена") }
            },
            containerColor = CardPaper,
            titleContentColor = Ink,
            textContentColor = MutedInk
        )
    }
}

@Composable
private fun DictationContent(state: ScreenState, actions: UiActions, openModels: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Ink),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(if (state.recording) Accent else androidx.compose.ui.graphics.Color(0xFF91A0B6)))
                    Spacer(Modifier.width(9.dp))
                    Text(if (state.recording) "ИДЁТ ЗАПИСЬ" else if (state.busy) "ОБРАБОТКА" else "СОСТОЯНИЕ", style = MaterialTheme.typography.labelMedium, color = androidx.compose.ui.graphics.Color(0xFFCAD2DE), fontWeight = FontWeight.SemiBold)
                }
                Text(state.status, style = MaterialTheme.typography.titleMedium, color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.SemiBold)
                if (!state.notificationsGranted) {
                    Text("Разрешите уведомления в настройках: там доступны Стоп и Отмена, когда вы в другом приложении.", color = androidx.compose.ui.graphics.Color(0xFFD1D7E0), style = MaterialTheme.typography.bodyMedium)
                }
                if (!state.microphoneGranted) {
                    Text("Для записи нужен доступ к микрофону. Его можно включить в настройках разрешений.", color = androidx.compose.ui.graphics.Color(0xFFD1D7E0), style = MaterialTheme.typography.bodyMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.recording) {
                        Button(onClick = actions::stop, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                            Text("Стоп", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(onClick = actions::cancel, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = androidx.compose.ui.graphics.Color.White)) {
                            Text("Отмена")
                        }
                    } else if (state.busy) {
                        OutlinedButton(onClick = actions::cancel, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = androidx.compose.ui.graphics.Color.White)) {
                            Text("Отменить обработку")
                        }
                    } else {
                        Button(
                            onClick = actions::start,
                            enabled = state.microphoneGranted && state.notificationsGranted && state.enabled && state.models.any { it.selected && it.installed },
                            modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp),
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
                    Button(onClick = { actions.copy(state.result) }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Ink)) { Text("Копировать") }
                    OutlinedButton(onClick = actions::dismissResult, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) { Text("Закрыть") }
                }
            }
        }

        ContentCard(title = "Язык распознавания", supporting = "Выбор действует для следующей диктовки.") {
            LanguagePicker(state.language, enabled = !state.busy && !state.recording, onSelect = actions::setLanguage)
            Text("GigaAM настроена на русский язык. Распознавание смешанной русско-английской речи для неё пока не проверено.", style = MaterialTheme.typography.bodySmall, color = MutedInk)
        }

        if (state.models.none { it.selected && it.installed }) {
            ContentCard(title = "Модель не выбрана", supporting = "Скачайте модель, чтобы начать локальное распознавание.") {
                Button(onClick = openModels, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Выбрать модель") }
            }
        }
    }
}

@Composable
private fun ModelsContent(state: ScreenState, actions: UiActions) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Модели", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Ink)
        Text("Файлы моделей скачиваются по вашему запросу. Распознавание выполняется на устройстве.", style = MaterialTheme.typography.bodyMedium, color = MutedInk)
        if (state.models.isEmpty()) {
            EmptyCard("Каталог моделей пока пуст.")
        }
        state.models.forEach { model ->
            val downloading = state.downloadingId == model.id
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
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
                    if (model.title.contains("GigaAM", ignoreCase = true) || model.id.contains("giga", ignoreCase = true)) {
                        Text("Для русского языка; смешанная речь с английским не проверена.", style = MaterialTheme.typography.bodySmall, color = MutedInk)
                    }
                    if (downloading) {
                        val progress = state.downloadProgress.coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = Accent, trackColor = SoftAccent)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Загрузка · ${(progress * 100).toInt()}%", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MutedInk)
                            TextButton(onClick = actions::cancelDownload, modifier = Modifier.height(48.dp)) { Text("Отменить") }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            when {
                                model.installed -> {
                                    if (model.selected) {
                                        Text("Выбрана", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = Success, fontWeight = FontWeight.SemiBold)
                                    } else {
                                        OutlinedButton(onClick = { actions.selectModel(model.id) }, enabled = !state.busy && !state.recording, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) { Text("Выбрать") }
                                    }
                                    TextButton(onClick = { actions.removeModel(model.id) }, enabled = !state.busy && !state.recording && !model.selected, modifier = Modifier.height(48.dp)) { Text("Удалить") }
                                }
                                else -> Button(onClick = { actions.download(model.id) }, enabled = !state.busy && !state.recording && state.downloadingId == null, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
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
                Text("История", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Ink)
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
                    OutlinedButton(onClick = { actions.copy(item.text) }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) { Text("Копировать") }
                    TextButton(onClick = { actions.deleteHistory(item.id) }, modifier = Modifier.weight(1f).height(48.dp)) { Text("Удалить") }
                }
            }
        }
    }
}

@Composable
private fun SettingsContent(state: ScreenState, actions: UiActions) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Настройки", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Ink)
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
            Text("Режим GigaAM ориентирован на русский. Смешанная русско-английская речь для него не проверена.", style = MaterialTheme.typography.bodySmall, color = MutedInk)
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
            val selected = language == value
            if (selected) {
                Button(onClick = { onSelect(value) }, enabled = enabled, modifier = Modifier.height(48.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Ink)) { Text(label) }
            } else {
                OutlinedButton(onClick = { onSelect(value) }, enabled = enabled, modifier = Modifier.height(48.dp), shape = RoundedCornerShape(14.dp)) { Text(label) }
            }
        }
    }
}

@Composable
private fun ContentCard(title: String, supporting: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CardPaper), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Ink, fontWeight = FontWeight.Bold)
            if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodySmall, color = MutedInk)
            content()
        }
    }
}

@Composable
private fun EmptyCard(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = CardPaper), shape = RoundedCornerShape(20.dp)) {
        Text(message, modifier = Modifier.fillMaxWidth().padding(18.dp), style = MaterialTheme.typography.bodyMedium, color = MutedInk)
    }
}
