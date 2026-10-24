package org.lacan.history

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryStoreTest {
    private lateinit var database: HistoryDatabase
    private lateinit var store: HistoryStore

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = HistoryStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun saveTrimsTextIgnoresBlankAndKeepsDuplicateIdempotent() = runBlocking {
        store.save(entry(id = "same", text = "  hello  "))
        store.save(entry(id = "same", text = "replacement"))
        store.save(entry(id = "blank", text = " \n "))

        val rows = store.entries.first()
        assertEquals(1, rows.size)
        assertEquals("hello", rows.single().text)
    }

    @Test
    fun saveRetainsNewestHundredOrderedByEndTime() = runBlocking {
        repeat(101) { index -> store.save(entry(id = "entry-$index", endedAt = index.toLong() + 1)) }

        val rows = store.entries.first()
        assertEquals(100, rows.size)
        assertEquals("entry-100", rows.first().id)
        assertEquals("entry-1", rows.last().id)
    }

    @Test
    fun recoverDraftsSavesInterruptedManualResultOnlyOnce() = runBlocking {
        store.draft(entry(id = "draft-1", text = " stable words "))

        store.recoverDrafts()
        store.recoverDrafts()

        val recovered = store.entries.first().single()
        assertEquals("stable words", recovered.text)
        assertEquals("interrupted", recovered.reason)
        assertEquals("manual", recovered.delivery)
    }

    @Test
    fun discardDraftPreventsCancelledSessionFromEnteringHistory() = runBlocking {
        store.draft(entry(id = "cancelled", text = "discard me"))
        store.discardDraft("cancelled")
        store.recoverDrafts()

        assertTrue(store.entries.first().isEmpty())
    }

    @Test
    fun saveRemovesDraftForSameSession() = runBlocking {
        store.draft(entry(id = "session", text = "partial"))
        store.save(entry(id = "session", text = "final"))
        store.recoverDrafts()

        val rows = store.entries.first()
        assertEquals(1, rows.size)
        assertEquals("final", rows.single().text)
    }

    private fun entry(
        id: String,
        text: String = "spoken text",
        endedAt: Long = 1L
    ) = HistoryEntry(
        id = id,
        startedAt = endedAt - 100,
        endedAt = endedAt,
        durationMs = 100,
        text = text,
        model = "whisper",
        language = "ru",
        reason = "completed"
    )
}
