package app.androidrik.ai.util

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiEndpointTest {

    @Test
    fun `api endpoint appends path when base url has no path`() {
        assertEquals("https://api.openai.com/v1/responses", apiEndpoint("https://api.openai.com/v1", "/responses"))
    }

    @Test
    fun `api endpoint does not duplicate path when base url already ends with it`() {
        // 回归：base_url 已含 /responses 时曾拼成 /responses/responses 404
        assertEquals(
            "https://gateway.example.com/responses",
            apiEndpoint("https://gateway.example.com/responses", "/responses"),
        )
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            apiEndpoint("https://api.openai.com/v1/chat/completions", "/chat/completions"),
        )
    }

    @Test
    fun `api endpoint trims trailing slash from base url`() {
        assertEquals("https://api.openai.com/v1/responses", apiEndpoint("https://api.openai.com/v1/", "/responses"))
    }
}
