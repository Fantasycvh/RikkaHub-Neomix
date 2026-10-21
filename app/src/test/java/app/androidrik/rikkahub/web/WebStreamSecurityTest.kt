package app.androidrik.rikkahub.web

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WebStreamSecurityTest {
    @Test
    fun `password rotation closes existing stream without another data event`() = runTest {
        val passwords = MutableStateFlow("old")
        val events = MutableSharedFlow<Int>()
        val received = mutableListOf<Int>()
        val job = launch { events.untilWebPasswordChanges(passwords, "old").toList(received) }
        runCurrent()
        events.emit(1)
        runCurrent()
        assertEquals(listOf(1), received)
        passwords.value = "new"
        runCurrent()
        assertTrue(job.isCompleted)
        events.emit(2)
        assertEquals(listOf(1), received)
    }

    @Test
    fun `clearing password closes stream and releases source`() = runTest {
        val passwords = MutableStateFlow("old")
        val events = MutableSharedFlow<Int>()
        val job = launch { events.untilWebPasswordChanges(passwords, "old").toList() }
        runCurrent()
        assertEquals(1, events.subscriptionCount.value)
        passwords.value = ""
        runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(0, events.subscriptionCount.value)
    }

    @Test
    fun `normal completion and disconnect do not leak watcher or source`() = runTest {
        val passwords = MutableStateFlow("old")
        assertEquals(listOf(1, 2), listOf(1, 2).asFlow().untilWebPasswordChanges(passwords, "old").toList())
        assertEquals(0, passwords.subscriptionCount.value)
        val events = MutableSharedFlow<Int>()
        val job = launch { events.untilWebPasswordChanges(passwords, "old").toList() }
        runCurrent()
        job.cancel()
        runCurrent()
        assertEquals(0, passwords.subscriptionCount.value)
        assertEquals(0, events.subscriptionCount.value)
    }
}
