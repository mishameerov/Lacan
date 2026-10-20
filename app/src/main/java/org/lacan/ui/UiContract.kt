package org.lacan.ui

data class ModelCard(val id: String, val title: String, val description: String, val sizeMb: Int, val installed: Boolean, val selected: Boolean)
data class HistoryCard(val id: String, val date: String, val text: String, val detail: String)
data class ScreenState(
    val microphoneGranted: Boolean = false,
    val notificationsGranted: Boolean = false,
    val overlayGranted: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val enabled: Boolean = true,
    val busy: Boolean = false,
    val recording: Boolean = false,
    val status: String = "Выберите и скачайте модель",
    val preview: String = "",
    val result: String = "",
    val models: List<ModelCard> = emptyList(),
    val downloadingId: String? = null,
    val downloadProgress: Float = 0f,
    val language: String = "ru",
    val history: List<HistoryCard> = emptyList()
)
interface UiActions {
    fun microphone(); fun notifications(); fun overlay(); fun accessibility()
    fun setEnabled(value: Boolean); fun setLanguage(value: String)
    fun download(id: String); fun cancelDownload(); fun selectModel(id: String); fun removeModel(id: String)
    fun start(); fun stop(); fun cancel(); fun copy(text: String)
    fun deleteHistory(id: String); fun clearHistory(); fun dismissResult()
}
