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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun SetupWizard(state: ScreenState, actions: UiActions, onDismiss: () -> Unit) {
    val step = state.nextSetupStep()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.9f),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Настройка Lacan", style = MaterialTheme.typography.headlineSmall)
                if (step == null) {
                    Text("Всё готово. Откройте поле ввода в другом приложении и начните диктовку с плавающей панели.")
                    Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Готово") }
                } else {
                    Text("Шаг ${step.ordinal + 1} из ${SetupStep.entries.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        when (step) {
                            SetupStep.Model -> {
                                Text("Выберите модель", style = MaterialTheme.typography.titleLarge)
                                Text("Сначала скачайте и выберите модель. Речь распознаётся на устройстве; для загрузки модели нужен интернет.")
                                state.models.forEach { model ->
                                    OutlinedCard(Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Text(model.title, fontWeight = FontWeight.Bold)
                                            Text(model.description, style = MaterialTheme.typography.bodyMedium)
                                            Text("${model.sizeMb} МБ", style = MaterialTheme.typography.labelLarge)
                                            if (state.downloadingId == model.id) {
                                                LinearProgressIndicator(progress = { state.downloadProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                    Text("${(state.downloadProgress.coerceIn(0f, 1f) * 100).toInt()}%")
                                                    TextButton(onClick = actions::cancelDownload) { Text("Отменить") }
                                                }
                                            } else {
                                                Button(
                                                    onClick = { if (model.installed) actions.selectModel(model.id) else actions.downloadForSetup(model.id) },
                                                    enabled = !state.busy && !state.recording && state.downloadingId == null && (model.installed || model.downloadable),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) { Text(if (model.installed) "Выбрать" else "Скачать и выбрать") }
                                            }
                                        }
                                    }
                                }
                                Text(state.status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                "Служба специальных возможностей вставляет распознанный текст в активное поле ввода. Откройте «Установленные приложения» или «Скачанные приложения», выберите Lacan и включите службу, подтвердив системное предупреждение. Затем вернитесь сюда.\n\nЕсли Android блокирует службу после установки APK: откройте сведения о приложении Lacan, меню ⋮ и «Разрешить ограниченные настройки», затем повторите включение службы. Названия пунктов зависят от устройства.",
                                "Открыть специальные возможности", actions::accessibility
                            )
                            SetupStep.Enable -> SetupPermission(
                                "Включите Lacan",
                                "Разрешения и модель готовы. Включите приложение, чтобы появилась панель диктовки.",
                                "Включить Lacan", { actions.setEnabled(true) }
                            )
                        }
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Позже") }
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
