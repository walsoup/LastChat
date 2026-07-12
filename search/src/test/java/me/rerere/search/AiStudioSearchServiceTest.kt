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
import kotlin.uuid.Uuid

class AiStudioSearchServiceTest {

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
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "According to search results, Kotlin is a modern programming language."
                      }
                    ]
                  },
                  "groundingMetadata": {
                    "webSearchQueries": ["Kotlin language description"],
                    "groundingChunks": [
                      {
                        "web": {
                          "uri": "https://kotlinlang.org",
                          "title": "Kotlin Programming Language"
                        }
                      },
                      {
                        "web": {
                          "uri": "https://wikipedia.org/wiki/Kotlin",
                          "title": "Kotlin (programming language) - Wikipedia"
                        }
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        mockHttpClient.setResponse(mockApiResponse)

        val params = buildJsonObject {
            put("query", "What is Kotlin?")
        }
        val serviceOptions = SearchServiceOptions.AiStudioOptions(
            apiKey = "test-api-key-123",
            model = "gemini-2.0-flash"
        )
        val commonOptions = SearchCommonOptions(resultSize = 5)

        val result = AiStudioSearchService.search(params, commonOptions, serviceOptions)

        assertTrue(result.isSuccess)
        val searchResult = result.getOrThrow()
        assertEquals("According to search results, Kotlin is a modern programming language.", searchResult.answer)
        assertEquals(2, searchResult.items.size)
        assertEquals("Kotlin Programming Language", searchResult.items[0].title)
        assertEquals("https://kotlinlang.org", searchResult.items[0].url)
        assertEquals("Kotlin (programming language) - Wikipedia", searchResult.items[1].title)
        assertEquals("https://wikipedia.org/wiki/Kotlin", searchResult.items[1].url)

        // Verify request payload
        assertEquals(1, mockHttpClient.requests.size)
        val request = mockHttpClient.requests[0]
        assertEquals("POST", request.method)
        assertTrue(request.url.contains("gemini-2.0-flash:generateContent"))
        assertTrue(request.url.contains("key=test-api-key-123"))

        val requestBodyJson = SearchService.json.parseToJsonElement(request.body!!.decodeToString()).jsonObject
        val contents = requestBodyJson["contents"]?.jsonArray
        val prompt = contents?.get(0)?.jsonObject?.get("parts")?.jsonArray?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content
        assertEquals("What is Kotlin?", prompt)

        val tools = requestBodyJson["tools"]?.jsonArray
        val hasGoogleSearch = tools?.any { it.jsonObject.containsKey("googleSearch") } ?: false
        assertTrue(hasGoogleSearch)
    }

    @Test
    fun `fails when api key is blank`() = runBlocking {
        val params = buildJsonObject {
            put("query", "What is Kotlin?")
        }
        val serviceOptions = SearchServiceOptions.AiStudioOptions(
            apiKey = "",
            model = "gemini-2.0-flash"
        )
        val commonOptions = SearchCommonOptions(resultSize = 5)

        val result = AiStudioSearchService.search(params, commonOptions, serviceOptions)
        assertTrue(result.isFailure)
        assertEquals("Google AI Studio API key is required", result.exceptionOrNull()?.message)
    }

    @Test
    fun `fails when query is missing`() = runBlocking {
        val params = buildJsonObject {}
        val serviceOptions = SearchServiceOptions.AiStudioOptions(
            apiKey = "key",
            model = "gemini-2.0-flash"
        )
        val commonOptions = SearchCommonOptions(resultSize = 5)

        val result = AiStudioSearchService.search(params, commonOptions, serviceOptions)
        assertTrue(result.isFailure)
        assertEquals("query is required", result.exceptionOrNull()?.message)
    }

    @Test
    fun `handles HTTP error status codes`() = runBlocking {
        mockHttpClient.setResponse("API key not valid", statusCode = 400)

        val params = buildJsonObject {
            put("query", "What is Kotlin?")
        }
        val serviceOptions = SearchServiceOptions.AiStudioOptions(
            apiKey = "bad-key",
            model = "gemini-2.0-flash"
        )
        val commonOptions = SearchCommonOptions(resultSize = 5)

        val result = AiStudioSearchService.search(params, commonOptions, serviceOptions)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("response failed #400") == true)
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
