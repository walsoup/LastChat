package me.rerere.search

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.common.platform.PlatformHttpClient
import me.rerere.common.platform.PlatformHttpRequest
import me.rerere.common.platform.PlatformHttpResponse
import me.rerere.common.platform.PlatformServerEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GrokSearchServiceTest {

    private lateinit var mockHttpClient: RecordingHttpClient

    @Before
    fun setUp() {
        mockHttpClient = RecordingHttpClient()
        SearchService.installPlatformHttpClient(mockHttpClient)
    }

    @Test
    fun `successful search query request payload and response parsing`() = runBlocking {
        val mockApiResponse = """
            {
              "output": [
                {
                  "type": "message",
                  "role": "assistant",
                  "content": [
                    {
                      "type": "output_text",
                      "text": "Here is information about Rust programming language.",
                      "annotations": [
                        {
                          "type": "url_citation",
                          "url": "https://rust-lang.org",
                          "title": "Rust Programming Language"
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        mockHttpClient.setResponse(mockApiResponse)

        val params = buildJsonObject {
            put("query", "What is Rust?")
        }
        val serviceOptions = SearchServiceOptions.GrokOptions(
            apiKey = "grok-key-abc",
            model = "grok-beta"
        )
        val commonOptions = SearchCommonOptions(resultSize = 5)

        val result = GrokSearchService.search(params, commonOptions, serviceOptions)

        assertTrue(result.isSuccess)
        val searchResult = result.getOrThrow()
        assertEquals("Here is information about Rust programming language.", searchResult.answer)
        assertEquals(1, searchResult.items.size)
        assertEquals("Rust Programming Language", searchResult.items[0].title)
        assertEquals("https://rust-lang.org", searchResult.items[0].url)

        // Verify request payload
        assertEquals(1, mockHttpClient.requests.size)
        val request = mockHttpClient.requests[0]
        assertEquals("POST", request.method)
        assertEquals("https://api.x.ai/v1/responses", request.url)
        assertEquals("Bearer grok-key-abc", request.headers["Authorization"])

        val requestBodyJson = SearchService.json.parseToJsonElement(request.body!!.decodeToString()).jsonObject
        assertEquals("grok-beta", requestBodyJson["model"]?.jsonPrimitive?.content)
    }

    @Test
    fun `fails when api key is blank`() = runBlocking {
        val params = buildJsonObject {
            put("query", "What is Rust?")
        }
        val serviceOptions = SearchServiceOptions.GrokOptions(
            apiKey = "",
            model = "grok-beta"
        )
        val commonOptions = SearchCommonOptions(resultSize = 5)

        val result = GrokSearchService.search(params, commonOptions, serviceOptions)
        assertTrue(result.isFailure)
        assertEquals("Grok API key is required", result.exceptionOrNull()?.message)
    }

    private class RecordingHttpClient : PlatformHttpClient {
        val requests = mutableListOf<PlatformHttpRequest>()
        private var nextResponseBody: String = ""
        private var nextStatusCode: Int = 200

        fun setResponse(body: String, statusCode: Int = 200) {
            nextResponseBody = body
            nextStatusCode = statusCode
        }

        override suspend fun execute(request: PlatformHttpRequest): PlatformHttpResponse {
            requests += request
            return PlatformHttpResponse(
                statusCode = nextStatusCode,
                body = nextResponseBody.encodeToByteArray()
            )
        }

        override fun streamEvents(request: PlatformHttpRequest): Flow<PlatformServerEvent> = emptyFlow()
    }
}
