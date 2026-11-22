package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
    @Query("SELECT * FROM jarvis_memories ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM jarvis_memories ORDER BY createdAt DESC")
    suspend fun getAllSnapshot(): List<MemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: MemoryEntity): Long

    @Query("DELETE FROM jarvis_memories WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM jarvis_memories")
    suspend fun deleteAll()
}

@Dao
interface AlarmDao {
    @Query("SELECT * FROM jarvis_alarms WHERE isActive = 1 ORDER BY triggerAtMillis ASC")
    fun observeActiveAlarms(): Flow<List<AlarmEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(alarm: AlarmEntity): Long

    @Query("UPDATE jarvis_alarms SET isActive = 0 WHERE id = :id")
    suspend fun deactivate(id: Long)

    @Query("DELETE FROM jarvis_alarms WHERE id = :id")
    suspend fun deleteById(id: Long)
}
