package me.rerere.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.InputSchema
import me.rerere.common.platform.PlatformHttpRequest
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.json
import me.rerere.search.SearchService.Companion.platformHttpClient

private const val TAG = "AiStudioSearchService"

object AiStudioSearchService : SearchService<SearchServiceOptions.AiStudioOptions> {
    override val name: String = "Google AI Studio"

    override val parameters: InputSchema?
        get() = InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "The question to ask, can be a natural language question")
                })
            },
            required = listOf("query")
        )

    override val scrapingParameters: InputSchema? = null

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.AiStudioOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            if (serviceOptions.apiKey.isBlank()) {
                error("Google AI Studio API key is required")
            }

            val query = params["query"]?.jsonPrimitive?.content
                ?: error("query is required")

            val body = buildJsonObject {
                putJsonArray("contents") {
                    add(buildJsonObject {
                        putJsonArray("parts") {
                            add(buildJsonObject {
                                put("text", JsonPrimitive(query))
                            })
                        }
                    })
                }
                putJsonArray("tools") {
                    add(buildJsonObject {
                        put("googleSearch", buildJsonObject {})
                    })
                }
            }

            println("$TAG search: $query")

            val url = "https://generativelanguage.googleapis.com/v1beta/models/${serviceOptions.model}:generateContent?key=${serviceOptions.apiKey}"
            val response = platformHttpClient.execute(
                PlatformHttpRequest(
                    method = "POST",
                    url = url,
                    headers = mapOf(
                        "Content-Type" to "application/json"
                    ),
                    body = body.toString().encodeToByteArray(),
                    mediaType = "application/json"
                )
            )
            if (response.statusCode !in 200..299) {
                error("response failed #${response.statusCode}: ${response.body.decodeToString()}")
            }

            val responseBodyString = response.body.decodeToString()
            val responseEl = json.parseToJsonElement(responseBodyString).jsonObject
            val candidate = responseEl["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: error("No candidates found in Gemini API response")

            val answer = candidate["content"]?.jsonObject?.get("parts")?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content

            val groundingMetadata = candidate["groundingMetadata"]?.jsonObject
            val groundingChunks = groundingMetadata?.get("groundingChunks")?.jsonArray

            val items = groundingChunks?.mapNotNull { chunkEl ->
                val web = chunkEl.jsonObject["web"]?.jsonObject ?: return@mapNotNull null
                val title = web["title"]?.jsonPrimitive?.content.orEmpty()
                val itemUrl = web["uri"]?.jsonPrimitive?.content.orEmpty()
                if (itemUrl.isBlank()) return@mapNotNull null
                SearchResultItem(
                    title = title.takeIf { it.isNotBlank() } ?: itemUrl,
                    url = itemUrl,
                    text = ""
                )
            }?.distinctBy { it.url }?.take(commonOptions.resultSize).orEmpty()

            SearchResult(
                answer = answer,
                items = items
            )
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.AiStudioOptions
    ): Result<ScrapedResult> {
        return Result.failure(Exception("Scraping is not supported for Google AI Studio"))
    }
}
