package org.lacan.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptAssemblerTest {
    private fun join(before: String, after: String, overlap: Boolean = true): String =
        TranscriptAssembler().apply { append(before, false); append(after, overlap) }.text

    @Test fun normalizesYoCaseAndBoundaryPunctuation() {
        assertEquals("Проверь всё готово, дальше", join("Проверь всё готово,", "ВСЕ ГОТОВО дальше"))
        assertEquals("Включи подъём уровня", join("Включи подъём", "подъем уровня"))
    }

    @Test fun acceptsOneLetterVariationWithTwoExactNeighbours() {
        assertEquals("Открой сейчас этот проект и проверь", join("Открой сейчас этот проект", "сейчас этот проэкт и проверь"))
        assertEquals("Проверь настройки этого проекта сегодня", join("Проверь настройки этого проекта", "настройки этого проэкта сегодня"))
    }

    @Test fun acceptsMissingLetterInLongWordWithAnchor() {
        assertEquals("Обнови настройки этого приложения сейчас", join("Обнови настройки этого приложения", "настройки этого приложеня сейчас"))
    }

    @Test fun doesNotGuessSingleWordOrWeakAnchors() {
        assertEquals("пошли домой пошли долой", join("пошли домой", "пошли долой"))
        assertEquals("этот проект этот проэкт", join("этот проект", "этот проэкт"))
        assertEquals("Открой проект проэкт готов", join("Открой проект", "проэкт готов"))
        assertEquals("и проект и проэкт готов", join("и проект", "и проэкт готов"))
    }

    @Test fun preservesNumbersNegationAndShortDistinctWords() {
        assertEquals("версия 1234 версия 1235 готова", join("версия 1234", "версия 1235 готова"))
        assertEquals("теперь не надо теперь мне надо", join("теперь не надо", "теперь мне надо"))
        assertEquals("это кот это кит", join("это кот", "это кит"))
    }

    @Test fun preservesRepetitionsOutsideBoundaryAndWithoutAudioOverlap() {
        assertEquals("он сказал да да дальше", join("он сказал да", "да да дальше"))
        assertEquals("этот проект этот проэкт", join("этот проект", "этот проэкт", false))
        assertEquals("да, да да", join("да, да", "да", false))
        assertEquals("вчера этот проект закрыли этот проэкт открыли", join("вчера этот проект закрыли", "этот проэкт открыли"))
    }

    @Test fun rejectsMultipleUncertainWords() {
        assertEquals("Открой настройки проекта настройкии проэкта сейчас", join("Открой настройки проекта", "настройкии проэкта сейчас"))
    }
}
