package it.faricelli.leggimi.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey val id: String,
    val title: String,
    val format: String,
    val fileName: String,
    val addedAt: Long,
    val lastOpenedAt: Long,
    val segmentCount: Int,
    val charCount: Int,
    val positionSegment: Int = 0,
    val positionMs: Long = 0,
    /** Voce e modello specifici del documento; null = usa quelli predefiniti. */
    val voiceId: String? = null,
    val voiceName: String? = null,
    val modelId: String? = null,
    val modelName: String? = null,
) {
    val progress: Float get() = if (segmentCount <= 1) 0f else positionSegment.toFloat() / (segmentCount - 1)
}

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents ORDER BY lastOpenedAt DESC")
    fun observeAll(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE id = :id")
    fun observe(id: String): Flow<DocumentEntity?>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun get(id: String): DocumentEntity?

    @Insert
    suspend fun insert(doc: DocumentEntity)

    @Query("UPDATE documents SET positionSegment = :segment, positionMs = :ms WHERE id = :id")
    suspend fun updatePosition(id: String, segment: Int, ms: Long)

    @Query("UPDATE documents SET segmentCount = :segmentCount, charCount = :charCount, positionSegment = :positionSegment, positionMs = 0 WHERE id = :id")
    suspend fun updateContent(id: String, segmentCount: Int, charCount: Int, positionSegment: Int)

    @Query("UPDATE documents SET lastOpenedAt = :time WHERE id = :id")
    suspend fun touch(id: String, time: Long)

    @Query("UPDATE documents SET voiceId = :voiceId, voiceName = :voiceName WHERE id = :id")
    suspend fun setVoice(id: String, voiceId: String?, voiceName: String?)

    @Query("UPDATE documents SET modelId = :modelId, modelName = :modelName WHERE id = :id")
    suspend fun setModel(id: String, modelId: String?, modelName: String?)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(entities = [DocumentEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun documents(): DocumentDao
}
