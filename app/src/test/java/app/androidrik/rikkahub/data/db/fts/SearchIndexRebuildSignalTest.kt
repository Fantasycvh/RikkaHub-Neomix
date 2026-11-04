package app.androidrik.rikkahub.data.db.fts

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchIndexRebuildSignalTest {
    @Test fun reportsRunningProgressAndTerminalStates() {
        SearchIndexRebuildSignal.reset()
        assertEquals(SearchIndexRebuildState.Idle, SearchIndexRebuildSignal.state.value)

        SearchIndexRebuildSignal.started(0)
        assertEquals(SearchIndexRebuildState.Running(0, 0), SearchIndexRebuildSignal.state.value)

        SearchIndexRebuildSignal.progress(3, 10)
        assertEquals(SearchIndexRebuildState.Running(3, 10), SearchIndexRebuildSignal.state.value)

        SearchIndexRebuildSignal.succeeded(10)
        assertEquals(SearchIndexRebuildState.Succeeded(10), SearchIndexRebuildSignal.state.value)

        SearchIndexRebuildSignal.failed("boom")
        assertEquals(SearchIndexRebuildState.Failed("boom"), SearchIndexRebuildSignal.state.value)

        SearchIndexRebuildSignal.reset()
        assertEquals(SearchIndexRebuildState.Idle, SearchIndexRebuildSignal.state.value)
    }
}
