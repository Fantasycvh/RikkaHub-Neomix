package app.androidrik.rikkahub.service

import kotlinx.serialization.json.JsonPrimitive
import app.androidrik.ai.core.ReasoningLevel
import app.androidrik.ai.provider.CustomBody
import app.androidrik.ai.provider.CustomHeader
import app.androidrik.ai.provider.Model
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatServiceTest {
    @Test
    fun `background generation params include model custom request configuration`() {
        val headers = listOf(CustomHeader(name = "X-Gateway-Token", value = "test-token"))
        val bodies = listOf(CustomBody(key = "gateway_mode", value = JsonPrimitive("strict")))
        val model = Model(
            modelId = "custom-chat-model",
            customHeaders = headers,
            customBodies = bodies,
        )

        val params = backgroundTextGenerationParams(model)

        assertEquals(model, params.model)
        assertEquals(ReasoningLevel.AUTO, params.reasoningLevel)
        assertEquals(headers, params.customHeaders)
        assertEquals(bodies, params.customBody)
    }
}
