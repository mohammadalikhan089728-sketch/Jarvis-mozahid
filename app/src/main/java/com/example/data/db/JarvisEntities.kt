package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "jarvis_memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val keywords: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "jarvis_alarms")
data class AlarmEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val triggerAtMillis: Long,
    val reason: String,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
