package app.androidrik.rikkahub.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoroutineUtilsTest {
    @Test fun `state flow cancellation closes collector without terminating process`() = runTest {
        val owner = Job()
        var finalized = false
        val source = flow {
            try { emit(7); awaitCancellation() } finally { finalized = true }
        }
        val state = source.toMutableStateFlow(CoroutineScope(coroutineContext + owner), 0)
        runCurrent()
        assertEquals(7, state.value)
        owner.cancel()
        runCurrent()
        assertTrue(finalized)
        assertTrue(owner.isCancelled)
    }
}
