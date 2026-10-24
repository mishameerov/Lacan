package org.lacan.core

import org.junit.Assert.assertEquals
import org.junit.Test

class HesitationFilterTest {
    @Test fun removesOnlyConfiguredStandaloneFillerRuns() {
        assertEquals("Пожалуйста, продолжай", HesitationFilter.clean("Пожалуйста, эээ, продолжай"))
        assertEquals("Скажи дальше", HesitationFilter.clean("э-э-э Скажи дальше"))
        assertEquals("Скажи дальше", HesitationFilter.clean("а-а Скажи дальше"))
        assertEquals("Скажи дальше", HesitationFilter.clean("ыыы Скажи дальше"))
        assertEquals("Скажи дальше", HesitationFilter.clean("ммм Скажи дальше"))
        assertEquals("Скажи дальше", HesitationFilter.clean("м—м Скажи дальше"))
    }

    @Test fun preservesShortRunsAndOrdinaryWords() {
        val text = "А, и, э, ну вот, мама, мм, m-m, ООО, ИИ"
        assertEquals(text, HesitationFilter.clean(text))
        assertEquals("проект ааааап", HesitationFilter.clean("проект ааааап"))
        assertEquals("мммщик", HesitationFilter.clean("мммщик"))
    }

    @Test fun supportsCaseAndUnicodeHyphens() {
        assertEquals("Готово", HesitationFilter.clean("А—А—А Готово"))
        assertEquals("Готово", HesitationFilter.clean("МмМ Готово"))
        assertEquals("Готово", HesitationFilter.clean("э‑э‑э Готово"))
    }

    @Test fun preservesSentencePunctuationAndCleansAdjacentSeparators() {
        assertEquals("Я думаю.", HesitationFilter.clean("Я думаю эээ."))
        assertEquals("Ну, продолжай.", HesitationFilter.clean("Ну, эээ , продолжай."))
        assertEquals("Слово, слово", HesitationFilter.clean("Слово, а-а, слово"))
        assertEquals("слово", HesitationFilter.clean("эээ,слово"))
    }

    @Test fun keepsParagraphSpacingAndOnlyRemovesNearbySpaces() {
        val text = "Первая строка.  \n\nВторая  строка эээ дальше."
        assertEquals("Первая строка.  \n\nВторая  строка дальше.", HesitationFilter.clean(text))
    }

    @Test fun emptyAndFillerOnlyInputBecomeEmpty() {
        assertEquals("", HesitationFilter.clean("эээ..."))
        assertEquals("", HesitationFilter.clean("м-м"))
        assertEquals("   ", HesitationFilter.clean("   "))
    }

    @Test fun mergesAdjacentFillersWithoutLosingWords() {
        assertEquals("слово", HesitationFilter.clean("эээ ааа слово"))
        assertEquals("Начало конец.", HesitationFilter.clean("Начало эээ, а-а ммм конец."))
    }

    @Test fun leavesTextIdentityUnchangedWhenThereIsNoFiller() {
        val text = "  Ну, вот!   А дальше?  "
        assertEquals(text, HesitationFilter.clean(text))
    }
}
