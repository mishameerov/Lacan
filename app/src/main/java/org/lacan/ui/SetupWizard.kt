package org.lacan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun SetupWizard(state: ScreenState, actions: UiActions, onDismiss: () -> Unit) {
    var modelStepPassed by rememberSaveable { mutableStateOf(false) }
    val step = state.nextWizardStep(modelStepPassed)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.9f),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Настройка Lacan", style = MaterialTheme.typography.headlineSmall)
                if (step == null) {
                    Text(if (state.nextSetupStep() == null)
                        "Всё готово. Откройте поле ввода в другом приложении и начните диктовку с плавающей панели."
                    else "Разрешения настроены. Дождитесь окончания загрузки модели — после этого можно будет начать диктовку. Загрузка продолжится, если закрыть мастер.")
                    Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(if (state.nextSetupStep() == null) "Готово" else "Закрыть") }
                } else {
                    Text("Шаг ${step.ordinal + 1} из ${SetupStep.entries.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        when (step) {
                            SetupStep.Model -> {
                                Text("Выберите модель", style = MaterialTheme.typography.titleLarge)
                                Text("Выберите модель и начните загрузку. Затем нажмите «Далее», чтобы настроить разрешения, пока модель скачивается. Речь распознаётся на устройстве.")
                                state.models.forEach { model ->
                                    OutlinedCard(Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Text(model.title, fontWeight = FontWeight.Bold)
                                            RecommendedModelLabel(model.id)
                                            Text(model.description, style = MaterialTheme.typography.bodyMedium)
                                            Text("${model.sizeMb} МБ", style = MaterialTheme.typography.labelLarge)
                                            val downloading = state.downloadingId == model.id
                                            val queued = model.id in state.queuedDownloadIds && state.downloadingId != model.id
                                            if (downloading || queued) {
                                                val progress = (state.downloadProgressById[model.id] ?: if (queued) 0f else state.downloadProgress).coerceIn(0f, 1f)
                                                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                    Text(if (queued) "В очереди · ${(progress * 100).toInt()}%" else "Загрузка · ${(progress * 100).toInt()}%")
                                                    TextButton(onClick = { actions.cancelDownload(model.id) }) { Text("Отменить") }
                                                }
                                            } else {
                                                Button(
                                                    onClick = {
                                                        when {
                                                            model.installed -> actions.selectModel(model.id)
                                                            !state.hasPendingSetupModel() -> actions.downloadForSetup(model.id)
                                                            else -> actions.download(model.id)
                                                        }
                                                    },
                                                    enabled = !state.busy && !state.recording && (model.installed || model.downloadable),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) { Text(when {
                                                    model.installed -> "Выбрать"
                                                    !state.hasPendingSetupModel() -> "Скачать и выбрать"
                                                    else -> "Скачать"
                                                }) }
                                            }
                                            state.downloadErrors[model.id]?.let { error ->
                                                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                    }
                                }
                            }
                            SetupStep.Microphone -> SetupPermission(
                                "Разрешите микрофон",
                                "Микрофон нужен для записи вашей речи во время диктовки. Нажмите кнопку и разрешите запись звука в системном запросе Android. Если доступ был запрещён, откроется объяснение и настройки приложения.",
                                "Разрешить микрофон", actions::microphone
                            )
                            SetupStep.Notifications -> SetupPermission(
                                "Включите уведомления",
                                "Уведомление показывает состояние записи и кнопки остановки и отмены, пока вы в другом приложении. Разрешите уведомления Android. Если они или канал диктовки отключены, включите их в открывшихся настройках уведомлений.",
                                "Разрешить уведомления", actions::notifications
                            )
                            SetupStep.Overlay -> SetupPermission(
                                "Разрешите плавающую панель",
                                "Панель позволяет запускать и останавливать диктовку поверх других приложений. В системном экране найдите Lacan и включите «Поверх других приложений», затем вернитесь сюда. Это разрешение только на показ панели.",
                                "Открыть системные настройки", actions::overlay
                            )
                            SetupStep.Accessibility -> SetupPermission(
                                "Включите ввод в поле",
                                "Служба Lacan вставляет распознанный текст в выбранное поле ввода.\n\n1. Нажмите кнопку ниже.\n2. Откройте «Установленные приложения» или «Скачанные приложения».\n3. Выберите Lacan, включите службу и подтвердите предупреждение Android.\n4. Вернитесь в Lacan.\n\nЕсли переключатель недоступен: откройте настройки Android → Приложения → Lacan → меню ⋮ → «Разрешить ограниченные настройки». Затем повторите шаги выше. Названия пунктов могут отличаться.",
                                "Открыть специальные возможности", actions::accessibility
                            )
                            SetupStep.Enable -> SetupPermission(
                                "Включите Lacan",
                                "Включите приложение. Панель диктовки станет доступна, когда модель будет загружена и все разрешения включены.",
                                "Включить Lacan", { actions.setEnabled(true) }
                            )
                        }
                    }
                    if (step == SetupStep.Model && state.hasPendingSetupModel()) {
                        Button(onClick = { modelStepPassed = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Далее") }
                    } else {
                        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Позже") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupPermission(title: String, explanation: String, label: String, action: () -> Unit) {
    Text(title, style = MaterialTheme.typography.titleLarge)
    Text(explanation, style = MaterialTheme.typography.bodyLarge)
    Button(onClick = action, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(label) }
}
