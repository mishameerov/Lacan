package org.lacan.ui

/** Stable preference values; cancellation red is deliberately absent. */
enum class DictationButtonColor(val title: String, val argb: Int) {
    Orange("Оранжевый", 0xFFDC790A.toInt()),
    Yellow("Жёлтый", 0xFFAE8A00.toInt()),
    Green("Зелёный", 0xFF288247.toInt()),
    Teal("Бирюзовый", 0xFF087F8C.toInt()),
    Blue("Синий", 0xFF356FDA.toInt()),
    Purple("Фиолетовый", 0xFF8053B5.toInt());

    companion object {
        fun fromPreference(value: String?): DictationButtonColor = entries.firstOrNull { it.name == value } ?: Orange
    }
}
