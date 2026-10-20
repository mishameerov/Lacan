package org.lacan.history

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "history")
data class HistoryEntry(
    @PrimaryKey val id: String,
    val startedAt: Long,
    val endedAt: Long,
    val durationMs: Long,
    val text: String,
    val model: String,
    val language: String,
    val reason: String,
    val delivery: String = "pending"
)

@Entity(tableName = "history_drafts")
internal data class HistoryDraft(
    @PrimaryKey val id: String,
    val startedAt: Long,
    val endedAt: Long,
    val durationMs: Long,
    val text: String,
    val model: String,
    val language: String
)

@Dao
internal interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY endedAt DESC, id ASC")
    fun observeEntries(): Flow<List<HistoryEntry>>

    @Query("SELECT * FROM history ORDER BY endedAt DESC, id ASC")
    suspend fun allEntries(): List<HistoryEntry>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntry(entry: HistoryEntry): Long

    @Query("UPDATE history SET delivery = :status WHERE id = :id")
    suspend fun updateDelivery(id: String, status: String)

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun deleteEntry(id: String)

    @Query("DELETE FROM history")
    suspend fun clearEntries()

    @Query("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY endedAt DESC, id ASC LIMIT 100)")
    suspend fun pruneEntries()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putDraft(draft: HistoryDraft)

    @Query("SELECT * FROM history_drafts ORDER BY endedAt DESC, id ASC")
    suspend fun allDrafts(): List<HistoryDraft>

    @Query("DELETE FROM history_drafts WHERE id = :id")
    suspend fun deleteDraft(id: String)

    @Query("DELETE FROM history_drafts")
    suspend fun clearDrafts()
}

@Database(entities = [HistoryEntry::class, HistoryDraft::class], version = 1, exportSchema = false)
internal abstract class HistoryDatabase : RoomDatabase() {
    internal abstract fun historyDao(): HistoryDao
}

class HistoryStore internal constructor(private val database: HistoryDatabase) {
    constructor(context: Context) : this(
        Room.databaseBuilder(context.applicationContext, HistoryDatabase::class.java, "lacan-history.db").build()
    )

    private val dao = database.historyDao()

    val entries: Flow<List<HistoryEntry>> = dao.observeEntries()

    suspend fun save(entry: HistoryEntry) {
        database.withTransaction {
            dao.deleteDraft(entry.id)
            val cleanedText = entry.text.trim()
            if (cleanedText.isNotEmpty()) {
                dao.insertEntry(entry.copy(text = cleanedText))
                dao.pruneEntries()
            }
        }
    }

    suspend fun updateDelivery(id: String, status: String) {
        dao.updateDelivery(id, status)
    }

    suspend fun delete(id: String) {
        dao.deleteEntry(id)
    }

    suspend fun clear() {
        dao.clearEntries()
    }

    suspend fun draft(entry: HistoryEntry) {
        val stableText = entry.text.trim()
        if (stableText.isEmpty()) {
            dao.deleteDraft(entry.id)
        } else {
            dao.putDraft(
                HistoryDraft(
                    id = entry.id,
                    startedAt = entry.startedAt,
                    endedAt = entry.endedAt,
                    durationMs = entry.durationMs,
                    text = stableText,
                    model = entry.model,
                    language = entry.language
                )
            )
        }
    }

    suspend fun recoverDrafts() {
        database.withTransaction {
            dao.allDrafts().forEach { draft ->
                val text = draft.text.trim()
                if (text.isNotEmpty()) {
                    dao.insertEntry(
                        HistoryEntry(
                            id = draft.id,
                            startedAt = draft.startedAt,
                            endedAt = draft.endedAt,
                            durationMs = draft.durationMs,
                            text = text,
                            model = draft.model,
                            language = draft.language,
                            reason = "interrupted",
                            delivery = "manual"
                        )
                    )
                }
            }
            dao.clearDrafts()
            dao.pruneEntries()
        }
    }

    suspend fun discardDraft(id: String) {
        dao.deleteDraft(id)
    }

    fun close() {
        database.close()
    }
}
