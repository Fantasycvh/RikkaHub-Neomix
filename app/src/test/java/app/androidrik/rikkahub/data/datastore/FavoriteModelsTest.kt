package app.androidrik.rikkahub.data.datastore

import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class FavoriteModelsTest {
    private val first = Model(modelId = "first")
    private val second = Model(modelId = "second", enabled = false)
    private val providers = listOf(ProviderSetting.OpenAI(models = listOf(first, second)))

    @Test
    fun `legacy duplicates and missing models cannot produce repeated lazy keys`() {
        val favorites = listOf(second.id, first.id, second.id, Uuid.random(), first.id)
        assertEquals(listOf(second.id, first.id), normalizeFavoriteModelIds(favorites, providers))
    }

    @Test
    fun `repeated favorite addition keeps the original position`() {
        val favorites = listOf(second.id, first.id)
        assertEquals(favorites, normalizeFavoriteModelIds(favorites + second.id, providers))
    }

    @Test
    fun `removing all providers clears dangling favorites`() {
        assertEquals(emptyList<Uuid>(), normalizeFavoriteModelIds(listOf(first.id), emptyList()))
    }
}
