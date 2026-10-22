package org.lacan.ui

/** Configuration, not model warmup: a cold loaded-on-demand model can still start recording. */
enum class SetupStep { Model, Microphone, Notifications, Overlay, Accessibility, Enable }

fun ScreenState.nextSetupStep(): SetupStep? = when {
    models.none { it.selected && it.installed } -> SetupStep.Model
    !microphoneGranted -> SetupStep.Microphone
    !notificationsGranted -> SetupStep.Notifications
    !overlayGranted -> SetupStep.Overlay
    !accessibilityEnabled -> SetupStep.Accessibility
    !enabled -> SetupStep.Enable
    else -> null
}

fun ScreenState.setupBlockReason(): String? = when (nextSetupStep()) {
    SetupStep.Model -> "Выберите и скачайте модель для диктовки"
    SetupStep.Microphone -> "Разрешите Lacan доступ к микрофону"
    SetupStep.Notifications -> "Разрешите уведомления Lacan для управления записью"
    SetupStep.Overlay -> "Разрешите Lacan показывать кнопку поверх других приложений"
    SetupStep.Accessibility -> "Включите службу Lacan в специальных возможностях для вставки текста"
    SetupStep.Enable -> "Включите диктовку Lacan в настройках"
    null -> null
}
