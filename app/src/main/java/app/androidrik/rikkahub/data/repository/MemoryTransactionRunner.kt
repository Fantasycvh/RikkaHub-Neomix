package app.androidrik.rikkahub.data.repository

import androidx.room.RoomDatabase
import androidx.room.withTransaction

/**
 * Short, re-entrant local transactions shared by all memory repositories.
 * The production runner must use the same Room database as their DAOs. Never run model/network work here.
 * Nested calls join the outer Room transaction, so proposals and progress can commit together.
 */
interface MemoryTransactionRunner {
    suspend fun <T> run(block: suspend () -> T): T
}

class RoomMemoryTransactionRunner(private val database: RoomDatabase) : MemoryTransactionRunner {
    override suspend fun <T> run(block: suspend () -> T): T = database.withTransaction { block() }
}
