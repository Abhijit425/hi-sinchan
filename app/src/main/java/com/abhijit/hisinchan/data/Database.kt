package com.abhijit.hisinchan.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * A fact the user EXPLICITLY asked Sinchan to remember.
 * Never created automatically from ordinary conversation.
 */
@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The fact as the user phrased it, e.g. "My favourite colour is blue". */
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** One line of a conversation (only stored when "Save conversation history" is on). */
@Entity(tableName = "messages", indices = [Index("sessionId")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    /** "user" or "assistant" */
    val role: String,
    val text: String,
    val emotion: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

data class SessionSummary(
    val sessionId: Long,
    val startedAt: Long,
    val messageCount: Int,
    val firstUserText: String?,
)

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories ORDER BY createdAt ASC")
    suspend fun getAll(): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun getById(id: Long): MemoryEntity?

    @Insert
    suspend fun insert(memory: MemoryEntity): Long

    @Update
    suspend fun update(memory: MemoryEntity): Int

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM memories")
    suspend fun deleteAll(): Int

    @Query("SELECT COUNT(*) FROM memories")
    suspend fun count(): Int
}

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC, id ASC")
    suspend fun getSession(sessionId: Long): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC, id ASC")
    fun observeSession(sessionId: Long): Flow<List<MessageEntity>>

    @Query(
        """
        SELECT sessionId,
               MIN(timestamp) AS startedAt,
               COUNT(*) AS messageCount,
               (SELECT m2.text FROM messages m2 WHERE m2.sessionId = messages.sessionId AND m2.role = 'user'
                ORDER BY m2.timestamp ASC LIMIT 1) AS firstUserText
        FROM messages GROUP BY sessionId ORDER BY startedAt DESC
        """
    )
    fun observeSessions(): Flow<List<SessionSummary>>

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: Long): Int

    @Query("DELETE FROM messages")
    suspend fun deleteAll(): Int
}

@Database(entities = [MemoryEntity::class, MessageEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
    abstract fun messageDao(): MessageDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "sinchan.db")
                .build()
    }
}
