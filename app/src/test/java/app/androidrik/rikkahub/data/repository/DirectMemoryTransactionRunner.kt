package app.androidrik.rikkahub.data.repository

/** For sequential policy tests only. Does not simulate rollback or prove Room atomicity. */
object DirectMemoryTransactionRunner : MemoryTransactionRunner {
    override suspend fun <T> run(block: suspend () -> T): T = block()
}
