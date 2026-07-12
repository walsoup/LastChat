package me.rerere.rikkahub.data.antigravity

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import me.rerere.ai.util.removeElements
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ImageGenerationResult
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.common.platform.android.await
import me.rerere.common.platform.PlatformMediaEncoder
import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

class AntigravityProvider(
    private val context: Context,
    private val client: OkHttpClient,
    private val mediaEncoder: PlatformMediaEncoder,
    private val oauthManager: AntigravityOAuthManager,
    private val settingsStore: SettingsStore,
) : Provider<ProviderSetting.Antigravity> {
    private val json = Json { ignoreUnknownKeys = true }
    private val toolNameRemapCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val eventSourceClient by lazy {
        client.newBuilder()
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.isSuccessful && response.header("Content-Type") == null) {
                    val body = response.body
                    response.newBuilder()
                        .header("Content-Type", "text/event-stream")
                        .body(
                            body.source().asResponseBody(
                                contentType = "text/event-stream".toMediaType(),
                                contentLength = body.contentLength(),
                            )
                        )
                        .build()
                } else {
                    response
                }
            }
            .build()
    }

    private suspend fun getOrRefreshAccessToken(providerSetting: ProviderSetting.Antigravity): String {
        val now = System.currentTimeMillis()
        if (providerSetting.accessToken.isNotEmpty() && now < providerSetting.tokenExpiry - 60_000) {
            return providerSetting.accessToken
        }

        if (providerSetting.refreshToken.isEmpty()) {
            error("No refresh token available. Please sign in again.")
        }

        val response = oauthManager.refreshAccessToken(providerSetting.refreshToken)
        val newExpiry = System.currentTimeMillis() + response.expiresIn * 1000

        settingsStore.update { settings ->
            settings.copy(
                providers = settings.providers.map { provider ->
                    if (provider.id == providerSetting.id && provider is ProviderSetting.Antigravity) {
                        provider.copy(
                            accessToken = response.accessToken,
                            refreshToken = response.refreshToken ?: provider.refreshToken,
                            tokenExpiry = newExpiry
                        )
                    } else {
                        provider
                    }
                }
            )
        }

        return response.accessToken
    }

    private fun getFallbackModels(obscure: Boolean): List<Model> {
        val defaultModels = listOf(
            "gemini-3.5-flash",
            "gemini-3.5-flash-low",
            "gemini-3.5-flash-extra-low",
            "gemini-3.1-pro",
            "gemini-3.1-pro-low",
            "gemini-2.5-flash",
            "gemini-2.5-pro",
            "claude-sonnet-4-6-thinking",
            "claude-opus-4-6-thinking"
        )
        return defaultModels.map { id ->
            val isThinking = id.contains("thinking", ignoreCase = true)
            val abilities = buildList {
                add(ModelAbility.TOOL)
                if (isThinking) {
                    add(ModelAbility.REASONING)
                }
            }
            Model(
                modelId = id,
                displayName = if (obscure) id else id.replace("-", " ").uppercase(),
                inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
                outputModalities = listOf(Modality.TEXT),
                abilities = abilities
            )
        }
    }

    override suspend fun listModels(providerSetting: ProviderSetting.Antigravity): List<Model> =
        withContext(Dispatchers.IO) {
            val obscure = providerSetting.obscureModels
            val accessToken = try {
                getOrRefreshAccessToken(providerSetting)
            } catch (e: Exception) {
                android.util.Log.e("AntigravityProvider", "Failed to get access token for model list", e)
                return@withContext getFallbackModels(obscure)
            }

            if (providerSetting.projectId.isBlank()) {
                return@withContext getFallbackModels(obscure)
            }

            val fingerprint = oauthManager.generateFingerprint(providerSetting.email)
            val requestBody = buildJsonObject {
                put("project", providerSetting.projectId)
            }

            val baseUrlClean = providerSetting.baseUrl.removeSuffix("/")
            val baseDomain = if (baseUrlClean.contains("/v1internal:streamGenerateContent")) {
                baseUrlClean.substringBefore("/v1internal:streamGenerateContent")
            } else {
                baseUrlClean
            }
            val fetchUrl = "$baseDomain/v1internal:fetchAvailableModels"

            val response = try {
                client.newCall(
                    Request.Builder()
                        .url(fetchUrl)
                        .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
                        .addHeader("Authorization", "Bearer $accessToken")
                        .addHeader("x-goog-api-client", fingerprint.apiClient)
                        .addHeader("x-goog-quotauser", fingerprint.quotaUser)
                        .addHeader("x-client-device-id", fingerprint.deviceId)
                        .addHeader("client-metadata", fingerprint.clientMetadataJson)
                        .addHeader("User-Agent", AntigravityOAuthManager.USER_AGENT)
                        .addHeader("Content-Type", "application/json")
                        .build()
                ).await()
            } catch (e: Exception) {
                android.util.Log.e("AntigravityProvider", "fetchAvailableModels call failed", e)
                return@withContext getFallbackModels(obscure)
            }

            val body = response.body.string()
            if (!response.isSuccessful) {
                android.util.Log.e("AntigravityProvider", "fetchAvailableModels failed: ${response.code} $body")
                return@withContext getFallbackModels(obscure)
            }

            val jsonEl = json.parseToJsonElement(body).jsonObject
            val rawModels = jsonEl["availableModels"]?.jsonObject
                ?: jsonEl["models"]?.jsonObject
                ?: run {
                    android.util.Log.w("AntigravityProvider", "No availableModels/models in response: $body")
                    return@withContext getFallbackModels(obscure)
                }

            var geminiRemaining: Double? = null
            var geminiReset: String? = null
            var nonGeminiRemaining: Double? = null
            var nonGeminiReset: String? = null

            for ((modelName, element) in rawModels.entries) {
                val m = element.jsonObject
                val label = m["displayMetadata"]?.jsonObject?.get("label")?.jsonPrimitive?.contentOrNull
                    ?: m["displayName"]?.jsonPrimitive?.contentOrNull
                    ?: m["model"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                    ?: modelName

                val quotaInfo = m["quotaInfo"]?.jsonObject ?: continue
                val remainingFraction = quotaInfo["remainingFraction"]?.jsonPrimitive?.doubleOrNull ?: 1.0
                val resetTime = quotaInfo["quotaResetTime"]?.jsonPrimitive?.contentOrNull
                    ?: quotaInfo["resetTime"]?.jsonPrimitive?.contentOrNull
                    ?: quotaInfo["quota_reset_time"]?.jsonPrimitive?.contentOrNull
                    ?: ""

                val isGemini = label.contains("Gemini", ignoreCase = true) || 
                               label.contains("chat", ignoreCase = true) || 
                               label.contains("tab_flash", ignoreCase = true)
                val isNonGemini = label.contains("Claude", ignoreCase = true) || 
                                  label.contains("Anthropic", ignoreCase = true) || 
                                  label.contains("GPT", ignoreCase = true)

                if (isGemini) {
                    if (geminiRemaining == null || remainingFraction < geminiRemaining) {
                        geminiRemaining = remainingFraction
                        geminiReset = resetTime
                    }
                } else if (isNonGemini) {
                    if (nonGeminiRemaining == null || remainingFraction < nonGeminiRemaining) {
                        nonGeminiRemaining = remainingFraction
                        nonGeminiReset = resetTime
                    }
                }
            }

            if (geminiRemaining != null || nonGeminiRemaining != null) {
                try {
                    settingsStore.update { settings ->
                        settings.copy(
                            providers = settings.providers.map { provider ->
                                if (provider.id == providerSetting.id && provider is ProviderSetting.Antigravity) {
                                    provider.copy(
                                        geminiQuotaRemaining = ((geminiRemaining ?: 1.0) * 100.0).toInt().coerceIn(0, 100),
                                        geminiQuotaResetTime = geminiReset ?: "",
                                        nonGeminiQuotaRemaining = ((nonGeminiRemaining ?: 1.0) * 100.0).toInt().coerceIn(0, 100),
                                        nonGeminiQuotaResetTime = nonGeminiReset ?: ""
                                    )
                                } else {
                                    provider
                                }
                            }
                        )
                    }
                } catch (e: Exception) {
                    android.util.Log.e("AntigravityProvider", "Failed to update quota settings", e)
                }
            }

            val fetchedModels = rawModels.entries.mapNotNull { (modelName, element) ->
                val m = element.jsonObject
                val name = m["model"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: modelName
                if (name.isBlank()) return@mapNotNull null

                val modelId = name.replace("models/", "")
                val displayName = if (obscure) {
                    modelId
                } else {
                    m["displayMetadata"]?.jsonObject?.get("label")?.jsonPrimitive?.content
                        ?: m["displayName"]?.jsonPrimitive?.content
                        ?: modelId
                }

                val isThinking = modelId.contains("thinking", ignoreCase = true)
                val abilities = buildList {
                    add(ModelAbility.TOOL)
                    if (isThinking) {
                        add(ModelAbility.REASONING)
                    }
                }

                Model(
                    modelId = modelId,
                    displayName = displayName,
                    inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
                    outputModalities = listOf(Modality.TEXT),
                    abilities = abilities
                )
            }

            if (fetchedModels.isEmpty()) {
                getFallbackModels(obscure)
            } else {
                fetchedModels
            }
        }

    override suspend fun getBalance(providerSetting: ProviderSetting.Antigravity): String {
        return "Quota status managed on Google Cloud console."
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.Antigravity,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): MessageChunk {
        var collected = listOf(UIMessage.assistant(""))
        var usage: TokenUsage? = null
        streamText(providerSetting, messages, params).collect { chunk ->
            collected = collected.handleMessageChunk(chunk, params.model)
            usage = chunk.usage ?: usage
        }
        return MessageChunk(
            id = "",
            model = params.model.modelId,
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = null,
                    message = collected.last(),
                    finishReason = "stop",
                )
            ),
            usage = usage,
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Antigravity,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<MessageChunk> = callbackFlow {
        val accessToken = try {
            getOrRefreshAccessToken(providerSetting)
        } catch (e: Exception) {
            close(e)
            return@callbackFlow
        }

        val googleModel = resolveGoogleModel(params.model.modelId, params.thinkingBudget ?: 0)
        val sanitizeAntigravityPrompts = true
        val sanitizeToolNames = true
        val googleSearch = providerSetting.googleSearch
        val prioritizeSearchOverTools = googleSearch

        // System Instruction
        val systemMessage = messages.find { it.role == MessageRole.SYSTEM }
        val systemInstruction = systemMessage?.parts?.filterIsInstance<UIMessagePart.Text>()?.joinToString("\n") { it.text }?.let { text ->
            val sanitized = if (sanitizeAntigravityPrompts) sanitizePrompts(text) else text
            val searchInstruction = if (googleSearch) "\n\nYou have access to Google Search. Use it to find up-to-date information when necessary." else ""
            buildJsonObject {
                putJsonArray("parts") {
                    add(buildJsonObject {
                        put("text", sanitized + searchInstruction)
                    })
                }
            }
        } ?: if (googleSearch) {
            buildJsonObject {
                putJsonArray("parts") {
                    add(buildJsonObject {
                        put("text", "You have access to Google Search. Use it to find up-to-date information when necessary.")
                    })
                }
            }
        } else {
            null
        }

        // Contents
        val contents = buildJsonArray {
            messages
                .filter { it.role != MessageRole.SYSTEM && it.isValidToUpload() }
                .forEach { message ->
                    add(buildJsonObject {
                        put("role", if (message.role == MessageRole.ASSISTANT) "model" else "user")
                        putJsonArray("parts") {
                            for (part in message.parts) {
                                when (part) {
                                    is UIMessagePart.Text -> {
                                        val text = if (message.role == MessageRole.USER && sanitizeAntigravityPrompts) {
                                            sanitizePrompts(part.text)
                                        } else {
                                            part.text
                                        }
                                        add(buildJsonObject {
                                            put("text", text)
                                        })
                                    }
                                    is UIMessagePart.Image -> {
                                        mediaEncoder.encodeImage(part.url, withPrefix = false).onSuccess { base64Data ->
                                            add(buildJsonObject {
                                                put("inlineData", buildJsonObject {
                                                    put("mimeType", "image/png")
                                                    put("data", base64Data)
                                                })
                                            })
                                        }
                                    }
                                    is UIMessagePart.ToolCall -> {
                                        add(buildJsonObject {
                                            put("functionCall", buildJsonObject {
                                                put("name", sanitizeFunctionName(part.toolName))
                                                put("args", json.parseToJsonElement(part.arguments))
                                            })
                                        })
                                    }
                                    is UIMessagePart.ToolResult -> {
                                        add(buildJsonObject {
                                            put("functionResponse", buildJsonObject {
                                                put("name", sanitizeFunctionName(part.toolName))
                                                put("response", buildJsonObject {
                                                    put("result", part.content)
                                                })
                                            })
                                        })
                                    }
                                    else -> {}
                                }
                            }
                        }
                    })
                }
        }

        val safetySettings = buildJsonArray {
            listOf(
                "HARM_CATEGORY_HARASSMENT",
                "HARM_CATEGORY_HATE_SPEECH",
                "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                "HARM_CATEGORY_DANGEROUS_CONTENT"
            ).forEach { category ->
                add(buildJsonObject {
                    put("category", category)
                    put("threshold", "BLOCK_NONE")
                })
            }
        }

        val maxOutputTokens = if (googleModel.contains("thinking") || (params.thinkingBudget ?: 0) > 0) {
            val mt = params.maxTokens ?: 0
            if (mt <= 0) 64000 else mt.coerceIn(64000, 2000000)
        } else {
            params.maxTokens ?: 4096
        }

        val generationConfig = buildJsonObject {
            put("temperature", params.temperature ?: 0.7)
            put("topP", params.topP ?: 0.95)
            put("maxOutputTokens", maxOutputTokens)
            put("candidateCount", 1)

            val isThinkingEligible = googleModel.contains("thinking") || googleModel.contains("gemini-3") || googleModel.contains("agent")
            if (isThinkingEligible) {
                put("thinkingConfig", buildJsonObject {
                    val budget = params.thinkingBudget ?: 16000
                    put("thinkingBudget", if (budget <= 0) 16000 else budget)
                    put("includeThoughts", true)
                })
            }
        }

        val googleTools = buildJsonArray {
            if (params.tools.isNotEmpty()) {
                val functionDeclarations = buildJsonArray {
                    params.tools.forEach { t ->
                        val cleanName = sanitizeFunctionName(t.name)
                        add(buildJsonObject {
                            put("name", cleanName)
                            put("description", t.description)
                            val schema = json.encodeToJsonElement(t.parameters())
                                .removeElements(
                                    listOf(
                                        "const",
                                        "exclusiveMaximum",
                                        "exclusiveMinimum",
                                        "format",
                                        "additionalProperties",
                                        "enum",
                                    )
                                )
                            put("parameters", schema)
                        })
                    }
                }

                val skipTools = googleSearch && prioritizeSearchOverTools
                if (!skipTools) {
                    add(buildJsonObject {
                        put("functionDeclarations", functionDeclarations)
                    })
                }
            }

            if (googleSearch) {
                add(buildJsonObject {
                    put("googleSearch", buildJsonObject {})
                })
            }
        }

        val requestObj = buildJsonObject {
            put("contents", contents)
            put("generationConfig", generationConfig)
            put("safetySettings", safetySettings)
            put("sessionId", java.util.UUID.randomUUID().toString())
            if (systemInstruction != null) {
                put("systemInstruction", systemInstruction)
            }
            if (googleTools.isNotEmpty()) {
                put("tools", googleTools)
            }
        }

        val finalPayload = buildJsonObject {
            put("project", providerSetting.projectId)
            put("model", googleModel)
            put("userAgent", "antigravity")
            put("requestId", "agent-${java.util.UUID.randomUUID()}")
            put("requestType", "agent")
            put("request", requestObj)
        }

        val fingerprint = oauthManager.generateFingerprint(providerSetting.email)
        val baseUrlClean = providerSetting.baseUrl.removeSuffix("/")
        val finalUrl = if (baseUrlClean.endsWith("/v1internal:streamGenerateContent")) {
            "$baseUrlClean?alt=sse"
        } else if (baseUrlClean.endsWith("/v1internal:streamGenerateContent?alt=sse")) {
            baseUrlClean
        } else {
            "$baseUrlClean/v1internal:streamGenerateContent?alt=sse"
        }

        val request = Request.Builder()
            .url(finalUrl)
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("x-goog-api-client", fingerprint.apiClient)
            .addHeader("x-goog-quotauser", fingerprint.quotaUser)
            .addHeader("x-client-device-id", fingerprint.deviceId)
            .addHeader("client-metadata", fingerprint.clientMetadataJson)
            .addHeader("User-Agent", AntigravityOAuthManager.USER_AGENT)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(json.encodeToString(finalPayload).toRequestBody("application/json".toMediaType()))
            .build()

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                // Connection opened successfully
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                val googleEvent = runCatching {
                    json.parseToJsonElement(data).jsonObject
                }.getOrElse {
                    close(it)
                    return
                }

                val responseObj = googleEvent["response"]?.jsonObject ?: googleEvent

                val usage = responseObj["usageMetadata"]?.jsonObject?.let { u ->
                    TokenUsage(
                        promptTokens = u["promptTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
                        completionTokens = (u["candidatesTokenCount"]?.jsonPrimitive?.intOrNull ?: 0) +
                                (u["thoughtsTokenCount"]?.jsonPrimitive?.intOrNull ?: 0),
                        totalTokens = u["totalTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
                        cachedTokens = u["cachedContentTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
                    )
                }

                val candidates = responseObj["candidates"]?.jsonArray
                if (candidates == null || candidates.isEmpty()) {
                    if (usage != null) {
                        trySend(
                            MessageChunk(
                                id = "agent-${java.util.UUID.randomUUID()}",
                                model = params.model.modelId,
                                choices = emptyList(),
                                usage = usage
                            )
                        )
                    }
                    return
                }

                val candidate = candidates[0].jsonObject
                val parts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray
                val finishReason = candidate["finishReason"]?.jsonPrimitive?.contentOrNull

                val groundingMetadata = responseObj["groundingMetadata"]?.jsonObject
                val groundingSources = if (groundingMetadata != null) {
                    val chunks = groundingMetadata["groundingChunks"]?.jsonArray ?: emptyList()
                    if (chunks.isNotEmpty()) {
                        val sb = java.lang.StringBuilder("\n\n---\n**Sources:**\n")
                        var addedSources = 0
                        chunks.forEach { chunk ->
                            val web = chunk.jsonObject["web"]?.jsonObject
                            if (web != null) {
                                val uri = web["uri"]?.jsonPrimitive?.contentOrNull
                                val title = web["title"]?.jsonPrimitive?.contentOrNull
                                if (uri != null && title != null) {
                                    addedSources++
                                    sb.append("$addedSources. [$title]($uri)\n")
                                }
                            }
                        }
                        if (addedSources > 0) sb.toString() else null
                    } else null
                } else null

                val listDeltaParts = mutableListOf<UIMessagePart>()

                if (parts != null) {
                    for (partEl in parts) {
                        val part = partEl.jsonObject
                        val isThought = part.containsKey("thought") ||
                                part.containsKey("thoughtText") ||
                                part["type"]?.jsonPrimitive?.contentOrNull == "thinking"

                        val text = part["text"]?.jsonPrimitive?.contentOrNull
                        if (!text.isNullOrEmpty()) {
                            var cleanText = text
                            if (cleanText.contains("thoughtSignature:")) {
                                cleanText = cleanText.replace(Regex("thoughtSignature:[a-zA-Z0-9\\-_]+"), "").trim()
                            }
                            if (cleanText.isNotEmpty()) {
                                if (isThought) {
                                    listDeltaParts.add(UIMessagePart.Reasoning(reasoning = cleanText))
                                } else {
                                    listDeltaParts.add(UIMessagePart.Text(text = cleanText))
                                }
                            }
                        }

                        val exec = part["executableCode"]?.jsonObject
                        if (exec != null) {
                            val lang = exec["language"]?.jsonPrimitive?.contentOrNull ?: "python"
                            val code = exec["code"]?.jsonPrimitive?.contentOrNull ?: ""
                            listDeltaParts.add(UIMessagePart.Reasoning(reasoning = "\n```$lang\n$code\n```\n"))
                        }

                        val res = part["codeExecutionResult"]?.jsonObject
                        if (res != null) {
                            val output = res["output"]?.jsonPrimitive?.contentOrNull ?: ""
                            listDeltaParts.add(UIMessagePart.Reasoning(reasoning = "\n```output\n$output\n```\n"))
                        }

                        val call = part["functionCall"]?.jsonObject ?: part["function_call"]?.jsonObject
                        if (call != null) {
                            val rawId = call["id"]?.jsonPrimitive?.contentOrNull
                                ?: call["callId"]?.jsonPrimitive?.contentOrNull
                                ?: call["call_id"]?.jsonPrimitive?.contentOrNull
                                ?: ""
                            val callId = if (rawId.isEmpty()) {
                                "call_${java.util.UUID.randomUUID().toString().substring(0, 8)}"
                            } else {
                                rawId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                            }
                            val name = call["name"]?.jsonPrimitive?.contentOrNull ?: ""
                            val originalName = getOriginalToolName(name)
                            val argsVal = call["args"]
                            val argumentsStr = if (argsVal is JsonPrimitive && argsVal.isString) {
                                argsVal.content
                            } else {
                                json.encodeToString(argsVal ?: buildJsonObject {})
                            }

                            listDeltaParts.add(
                                UIMessagePart.ToolCall(
                                    toolCallId = callId,
                                    toolName = originalName,
                                    arguments = argumentsStr
                                )
                            )
                        }
                    }
                }

                if (groundingSources != null) {
                    listDeltaParts.add(UIMessagePart.Text(text = groundingSources))
                }

                if (listDeltaParts.isNotEmpty() || finishReason != null) {
                    val mappedFinishReason = when (finishReason) {
                        "STOP" -> "stop"
                        "MAX_TOKENS" -> "length"
                        "SAFETY" -> "content_filter"
                        "MALFORMED_FUNCTION_CALL" -> "tool_calls"
                        else -> {
                            if (listDeltaParts.any { it is UIMessagePart.ToolCall }) {
                                "tool_calls"
                            } else if (finishReason != null) {
                                "stop"
                            } else null
                        }
                    }

                    trySend(
                        MessageChunk(
                            id = "agent-${java.util.UUID.randomUUID()}",
                            model = params.model.modelId,
                            choices = listOf(
                                UIMessageChoice(
                                    index = 0,
                                    delta = UIMessage(
                                        role = MessageRole.ASSISTANT,
                                        parts = listDeltaParts
                                    ),
                                    message = null,
                                    finishReason = mappedFinishReason
                                )
                            ),
                            usage = usage
                        )
                    )
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val detail = response?.takeUnless { it.isSuccessful }?.body?.string()
                close(t ?: IllegalStateException("Antigravity request failed: ${response?.code} $detail"))
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }

        val eventSource = EventSources.createFactory(eventSourceClient).newEventSource(request, listener)
        awaitClose { eventSource.cancel() }
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams
    ): ImageGenerationResult {
        error("Image generation is not supported by the Antigravity provider")
    }

    override suspend fun createEmbedding(
        providerSetting: ProviderSetting.Antigravity,
        input: List<String>,
        model: Model
    ): List<List<Float>> {
        error("Embedding generation is not supported by the Antigravity provider")
    }

    private fun resolveGoogleModel(rawModel: String, thinkingBudget: Int): String {
        val modelLower = rawModel.lowercase()

        val tier = when {
            modelLower.endsWith("-extra-low") -> "extra-low"
            modelLower.endsWith("-low") -> "low"
            modelLower.endsWith("-medium") -> "medium"
            modelLower.endsWith("-high") -> "high"
            modelLower.endsWith("-xhigh") -> "xhigh"
            else -> null
        }

        var baseModel = modelLower
        if (tier != null) {
            baseModel = baseModel.replace("-$tier", "").replace("-thinking-$tier", "")
        }
        baseModel = baseModel.replace("-preview", "")

        if (baseModel.contains("claude")) {
            var googleModel = baseModel
            if (googleModel == "claude-opus-4-6") {
                googleModel = "claude-opus-4-6-thinking"
            }
            if (googleModel == "claude-sonnet-4-6-thinking" || googleModel.contains("claude-3-7-sonnet") || googleModel.contains("claude-3.7-sonnet")) {
                googleModel = "claude-sonnet-4-6"
            }
            if (googleModel == "claude-sonnet-4-5") {
                googleModel = "claude-sonnet-4-5-thinking"
            }
            return googleModel
        }

        val adaptiveTier = when {
            thinkingBudget > 16000 -> "high"
            thinkingBudget > 8192 -> "medium"
            tier != null -> tier
            else -> "low"
        }

        val googleModel = when {
            baseModel.contains("gemini-3.1-pro") -> {
                if (adaptiveTier == "xhigh" || adaptiveTier == "high") {
                    "gemini-pro-agent"
                } else {
                    "gemini-3.1-pro-low"
                }
            }
            baseModel.contains("gemini-3-pro") -> "gemini-3-pro"
            baseModel.contains("gemini-3.5-flash") -> {
                if (adaptiveTier == "xhigh" || adaptiveTier == "high") {
                    "gemini-3-flash-agent"
                } else if (adaptiveTier == "extra-low") {
                    "gemini-3.5-flash-extra-low"
                } else {
                    "gemini-3.5-flash-low"
                }
            }
            baseModel.contains("gemini-3-flash") -> "gemini-3-flash"
            else -> modelLower
        }

        return googleModel
    }

    private fun sanitizePrompts(input: String): String {
        val tags = listOf(
            "identity", "user_information", "web_application_development",
            "ephemeral_message", "subagents", "messaging",
            "conversation_transcript", "artifacts", "slash_commands",
            "guidelines", "communication_style"
        )
        var result = input
        for (tag in tags) {
            val regex = Regex("<$tag>[\\s\\S]*?</$tag>\\n*", RegexOption.IGNORE_CASE)
            result = regex.replace(result, "")
        }
        return result
    }

    private fun sanitizeFunctionName(name: String): String {
        if (name.matches(Regex("^[a-zA-Z_][a-zA-Z0-9_]*$"))) {
            return name
        }
        toolNameRemapCache[name]?.let { return it }

        var sanitized = name.replace(Regex("[^a-zA-Z0-9_]"), "_")
        if (sanitized.firstOrNull()?.isDigit() == true) {
            sanitized = "fn_$sanitized"
        }
        if (sanitized.isEmpty()) {
            sanitized = "fn_${java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8)}"
        }
        toolNameRemapCache[name] = sanitized
        return sanitized
    }

    private fun getOriginalToolName(sanitizedName: String): String {
        return toolNameRemapCache.entries.find { it.value == sanitizedName }?.key ?: sanitizedName
    }

    private fun UIMessage.isValidToUpload(): Boolean {
        return parts.any {
            when (it) {
                is UIMessagePart.Text -> it.text.isNotBlank()
                is UIMessagePart.Image -> it.url.isNotBlank()
                is UIMessagePart.ToolCall -> it.toolName.isNotBlank()
                is UIMessagePart.ToolResult -> it.toolName.isNotBlank()
                else -> false
            }
        }
    }
}
