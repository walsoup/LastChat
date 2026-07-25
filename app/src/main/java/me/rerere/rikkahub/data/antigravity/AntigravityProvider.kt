package me.rerere.rikkahub.data.antigravity

import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.buildGoogleToolsPayload
import me.rerere.ai.registry.ModelIdNormalizer
import me.rerere.ai.ui.ImageGenerationResult
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.providerIoDispatcher
import me.rerere.common.platform.PlatformHttpRequest
import me.rerere.common.platform.PlatformMediaEncoder
import me.rerere.common.platform.PlatformServerEvent
import me.rerere.common.platform.android.OkHttpPlatformHttpClient
import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "AntigravityProvider"
private const val CLOUDCODE_ENDPOINT = "https://daily-cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse"
private const val FETCH_MODELS_ENDPOINT = "https://daily-cloudcode-pa.googleapis.com/v1internal:fetchAvailableModels"

class AntigravityProvider(
    private val context: Context,
    private val client: OkHttpClient,
    private val mediaEncoder: PlatformMediaEncoder,
    private val oauthManager: AntigravityOAuthManager,
    private val settingsStore: SettingsStore,
) : Provider<ProviderSetting.Antigravity> {

    private val platformHttpClient = OkHttpPlatformHttpClient(client)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Resolves the best available Antigravity account using scoring, cooldown state, and sticky session mapping.
     */
    private suspend fun selectBestAccount(
        primarySetting: ProviderSetting.Antigravity,
        modelId: String,
        sessionId: String?,
        excludeEmails: Set<String> = emptySet()
    ): ProviderSetting.Antigravity {
        val settings = settingsStore.settingsFlowRaw.first()
        val allAccounts = settings.providers
            .filterIsInstance<ProviderSetting.Antigravity>()
            .filter { it.enabled && it.refreshToken.isNotBlank() && !excludeEmails.contains(it.email) }

        if (allAccounts.isEmpty()) {
            return primarySetting
        }

        val now = System.currentTimeMillis()

        // 1. Check sticky session mapping
        val stickyEmail = AntigravityAccountManager.getStickyAccount(sessionId)
        if (!stickyEmail.isNullOrBlank()) {
            val sticky = allAccounts.find { it.email == stickyEmail }
            if (sticky != null && !AntigravityAccountManager.isCooldown(sticky.email, modelId)) {
                return sticky
            }
        }

        // 2. Filter accounts not on cooldown
        var candidates = allAccounts.filter { !AntigravityAccountManager.isCooldown(it.email, modelId) }

        // 3. Fallback / Rescue: Sort by earliest cooldown expiration
        if (candidates.isEmpty()) {
            candidates = allAccounts.sortedBy { AntigravityAccountManager.getCooldownExpiry(it.email, modelId) }
        }

        if (candidates.isEmpty()) {
            return primarySetting
        }

        // 4. Score candidates based on health score + LRU recency
        val best = candidates.maxByOrNull { acc ->
            val health = AntigravityAccountManager.getHealthScore(acc.email)
            val lastUsedTime = AntigravityAccountManager.getLastUsed(acc.email)
            val secondsSinceUsed = (now - lastUsedTime).coerceAtLeast(0) / 1000.0
            
            // Priority formula matches Rust proxy weighting: (health * 2.0) + (secondsSinceUsed * 0.1)
            (health * 2.0) + (secondsSinceUsed * 0.1)
        }

        return best ?: primarySetting
    }

    private suspend fun getValidAccessToken(providerSetting: ProviderSetting.Antigravity): Pair<String, ProviderSetting.Antigravity> {
        val now = System.currentTimeMillis()
        if (providerSetting.accessToken.isNotBlank() && now < providerSetting.tokenExpiry - 60000) {
            return providerSetting.accessToken to providerSetting
        }
        if (providerSetting.refreshToken.isBlank()) {
            error("No refresh token available for ${providerSetting.email}. Please sign in with Google.")
        }
        Log.i(TAG, "Refreshing access token for ${providerSetting.email}...")
        val tokenResponse = oauthManager.refreshAccessToken(providerSetting.refreshToken)
        val newAccessToken = tokenResponse.accessToken
        val newExpiry = System.currentTimeMillis() + (tokenResponse.expiresIn * 1000)

        val updated = providerSetting.copy(accessToken = newAccessToken, tokenExpiry = newExpiry)

        // Persist to Datastore
        runCatching {
            settingsStore.update { raw ->
                val updatedProviders = raw.providers.map { p ->
                    if (p is ProviderSetting.Antigravity && (p.id == providerSetting.id || p.email == providerSetting.email)) {
                        p.copy(accessToken = newAccessToken, tokenExpiry = newExpiry)
                    } else p
                }
                raw.copy(providers = updatedProviders)
            }
        }
        return newAccessToken to updated
    }

    override suspend fun listModels(providerSetting: ProviderSetting.Antigravity): List<Model> = withContext(providerIoDispatcher) {
        val activeAccount = selectBestAccount(providerSetting, "gemini-3-flash", null)
        val tokenPair = runCatching { getValidAccessToken(activeAccount) }.getOrNull()
        if (tokenPair != null) {
            val (token, account) = tokenPair
            val fingerprint = oauthManager.generateFingerprint(account.email)
            val requestBody = buildJsonObject {
                if (account.projectId.isNotEmpty()) {
                    put("project", account.projectId)
                }
            }

            val request = Request.Builder()
                .url(FETCH_MODELS_ENDPOINT)
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $token")
                .addHeader("x-goog-api-client", fingerprint.apiClient)
                .addHeader("x-goog-quotauser", fingerprint.quotaUser)
                .addHeader("x-client-device-id", fingerprint.deviceId)
                .addHeader("client-metadata", fingerprint.clientMetadataJson)
                .addHeader("User-Agent", "google-api-nodejs-client/9.15.1")
                .addHeader("Content-Type", "application/json")
                .build()

            try {
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val jsonEl = json.parseToJsonElement(body).jsonObject
                    val modelsArray = jsonEl["models"]?.jsonArray
                        ?: jsonEl["availableModels"]?.jsonArray
                        ?: return@withContext defaultAntigravityModels()

                    val parsed = modelsArray.mapNotNull { item ->
                        val obj = item.jsonObject
                        val modelId = obj["name"]?.jsonPrimitive?.contentOrNull
                            ?: obj["modelId"]?.jsonPrimitive?.contentOrNull
                            ?: return@mapNotNull null
                        val cleanId = modelId.removePrefix("publishers/google/models/").removePrefix("models/")
                        val displayName = obj["displayName"]?.jsonPrimitive?.contentOrNull ?: cleanId

                        Model(
                            modelId = cleanId,
                            displayName = displayName,
                            canonicalModelId = ModelIdNormalizer.canonicalize(cleanId),
                            type = ModelType.CHAT
                        )
                    }
                    if (parsed.isNotEmpty()) return@withContext parsed
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch models from Cloud Code API, falling back to default model list", e)
            }
        }
        defaultAntigravityModels()
    }

    private fun defaultAntigravityModels(): List<Model> {
        return listOf(
            Model(modelId = "gemini-3.1-pro", displayName = "Gemini 3.1 Pro (Antigravity)", canonicalModelId = "gemini-3.1-pro", type = ModelType.CHAT),
            Model(modelId = "gemini-3-flash", displayName = "Gemini 3 Flash (Antigravity)", canonicalModelId = "gemini-3-flash", type = ModelType.CHAT),
            Model(modelId = "gemini-2.5-pro", displayName = "Gemini 2.5 Pro (Antigravity)", canonicalModelId = "gemini-2.5-pro", type = ModelType.CHAT),
            Model(modelId = "gemini-2.5-flash", displayName = "Gemini 2.5 Flash (Antigravity)", canonicalModelId = "gemini-2.5-flash", type = ModelType.CHAT),
            Model(modelId = "claude-3-7-sonnet", displayName = "Claude 3.7 Sonnet (Antigravity)", canonicalModelId = "claude-3-7-sonnet", type = ModelType.CHAT),
            Model(modelId = "gpt-4o", displayName = "GPT-4o (Antigravity)", canonicalModelId = "gpt-4o", type = ModelType.CHAT),
        )
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.Antigravity,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): MessageChunk = withContext(providerIoDispatcher) {
        val modelId = params.model.modelId
        val excludeEmails = mutableSetOf<String>()
        var attempts = 0
        var lastError: Throwable? = null

        while (attempts < 3) {
            val account = selectBestAccount(providerSetting, modelId, params.sessionId, excludeEmails)
            attempts++
            try {
                val (token, activeAccount) = getValidAccessToken(account)
                val fingerprint = oauthManager.generateFingerprint(activeAccount.email)
                val payload = buildPayload(activeAccount, messages, params)

                val headers = mapOf(
                    "Authorization" to "Bearer $token",
                    "x-goog-api-client" to fingerprint.apiClient,
                    "x-goog-quotauser" to fingerprint.quotaUser,
                    "x-client-device-id" to fingerprint.deviceId,
                    "client-metadata" to fingerprint.clientMetadataJson,
                    "User-Agent" to "google-api-nodejs-client/9.15.1",
                    "Content-Type" to "application/json",
                )

                val response = platformHttpClient.execute(
                    PlatformHttpRequest(
                        method = "POST",
                        url = CLOUDCODE_ENDPOINT,
                        headers = headers,
                        body = json.encodeToString(payload).encodeToByteArray(),
                        mediaType = "application/json",
                        proxy = activeAccount.proxy.toPlatformProxy()
                    )
                )

                val bodyStr = response.body.decodeToString()
                if (response.statusCode !in 200..299) {
                    throw Exception("Antigravity request failed #${response.statusCode}: $bodyStr")
                }

                val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
                val candidates = bodyJson["candidates"]?.jsonArray ?: JsonArray(emptyList())

                AntigravityAccountManager.onSuccess(activeAccount.email, modelId, params.sessionId)

                return@withContext MessageChunk(
                    id = Uuid.random().toString(),
                    model = modelId,
                    choices = candidates.map { candidate ->
                        UIMessageChoice(
                            message = parseMessage(candidate.jsonObject),
                            index = 0,
                            finishReason = candidate.jsonObject["finishReason"]?.jsonPrimitive?.contentOrNull,
                            delta = null
                        )
                    }
                )
            } catch (e: Throwable) {
                lastError = e
                Log.e(TAG, "Request failed for account ${account.email}, retrying... error: ${e.message}")
                AntigravityAccountManager.onFailure(account.email, modelId, params.sessionId, (e as? Exception)?.message?.let {
                    if (it.contains("429")) 429 else if (it.contains("403")) 403 else null
                })
                excludeEmails.add(account.email)
            }
        }
        throw lastError ?: Exception("Failed to execute generateText after 3 attempts")
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Antigravity,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<MessageChunk> = callbackFlow {
        val modelId = params.model.modelId
        val excludeEmails = mutableSetOf<String>()
        var attempts = 0
        var streamStarted = false

        while (attempts < 3 && !streamStarted) {
            val account = selectBestAccount(providerSetting, modelId, params.sessionId, excludeEmails)
            attempts++
            try {
                val (token, activeAccount) = getValidAccessToken(account)
                val fingerprint = oauthManager.generateFingerprint(activeAccount.email)
                val payload = buildPayload(activeAccount, messages, params)

                val headers = mapOf(
                    "Authorization" to "Bearer $token",
                    "x-goog-api-client" to fingerprint.apiClient,
                    "x-goog-quotauser" to fingerprint.quotaUser,
                    "x-client-device-id" to fingerprint.deviceId,
                    "client-metadata" to fingerprint.clientMetadataJson,
                    "User-Agent" to "google-api-nodejs-client/9.15.1",
                    "Content-Type" to "application/json",
                )

                val encodedRequestBody = json.encodeToString(payload)
                val request = PlatformHttpRequest(
                    method = "POST",
                    url = CLOUDCODE_ENDPOINT,
                    headers = headers,
                    body = encodedRequestBody.encodeToByteArray(),
                    mediaType = "application/json",
                    proxy = activeAccount.proxy.toPlatformProxy()
                )

                streamStarted = true
                val job = launch {
                    platformHttpClient.streamEvents(request).collect { event ->
                        when (event) {
                            is PlatformServerEvent.Open -> {
                                AntigravityAccountManager.onSuccess(activeAccount.email, modelId, params.sessionId)
                            }
                            PlatformServerEvent.Closed -> close()
                            is PlatformServerEvent.Failure -> {
                                AntigravityAccountManager.onFailure(activeAccount.email, modelId, params.sessionId, event.statusCode)
                                close(parseStreamFailure(event))
                            }
                            is PlatformServerEvent.Event -> {
                                runCatching {
                                    parseStreamChunk(event.data, modelId)?.let { trySend(it) }
                                }.onFailure { error ->
                                    error.printStackTrace()
                                    close(error)
                                }
                            }
                        }
                    }
                }

                awaitClose { job.cancel() }
                return@callbackFlow
            } catch (e: Throwable) {
                Log.e(TAG, "Stream launch failed for account ${account.email}, retrying... error: ${e.message}")
                AntigravityAccountManager.onFailure(account.email, modelId, params.sessionId, null)
                excludeEmails.add(account.email)
            }
        }
        close(Exception("Failed to initiate Antigravity stream after 3 attempts"))
    }

    private fun buildPayload(
        providerSetting: ProviderSetting.Antigravity,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): JsonObject = buildJsonObject {
        if (providerSetting.projectId.isNotEmpty()) {
            put("project", providerSetting.projectId)
        }

        val systemMessage = messages.firstOrNull { it.role == MessageRole.SYSTEM }
        if (systemMessage != null) {
            put("systemInstruction", buildJsonObject {
                putJsonArray("parts") {
                    add(buildJsonObject {
                        put("text", systemMessage.parts.filterIsInstance<UIMessagePart.Text>().joinToString { it.text })
                    })
                }
            })
        }

        put("generationConfig", buildJsonObject {
            if (params.temperature != null) put("temperature", params.temperature)
            if (params.topP != null) put("topP", params.topP)
            if (params.maxTokens != null) put("maxOutputTokens", params.maxTokens)
            if (params.model.abilities.contains(ModelAbility.REASONING)) {
                put("thinkingConfig", buildJsonObject {
                    put("includeThoughts", true)
                })
            }
        })

        put("contents", buildContents(messages))

        buildGoogleToolsPayload(params)?.let { toolsPayload ->
            put("tools", toolsPayload)
        }
    }

    private fun buildContents(messages: List<UIMessage>): JsonArray = buildJsonArray {
        messages.filter { it.role != MessageRole.SYSTEM && it.isValidToUpload() }.forEach { message ->
            add(buildJsonObject {
                put("role", if (message.role == MessageRole.USER) "user" else "model")
                putJsonArray("parts") {
                    message.parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> add(buildJsonObject { put("text", part.text) })
                            is UIMessagePart.Image -> add(buildJsonObject {
                                put("inlineData", buildJsonObject {
                                    put("mimeType", "image/png")
                                    put("data", part.url)
                                })
                            })
                            else -> {}
                        }
                    }
                }
            })
        }
    }

    private fun parseMessage(message: JsonObject): UIMessage {
        val content = message["content"]?.jsonObject
        val parts = content?.get("parts")?.jsonArray?.mapNotNull { part ->
            (part as? JsonObject)?.let { parseMessagePart(it) }
        } ?: emptyList()

        return UIMessage(
            role = MessageRole.ASSISTANT,
            parts = parts
        )
    }

    private fun parseMessagePart(jsonObject: JsonObject): UIMessagePart? {
        return when {
            jsonObject.containsKey("text") -> {
                val thought = jsonObject["thought"]?.jsonPrimitive?.contentOrNull == "true"
                val text = jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: ""
                if (thought) UIMessagePart.Reasoning(reasoning = text, createdAt = Clock.System.now(), finishedAt = null)
                else UIMessagePart.Text(text = text)
            }
            jsonObject.containsKey("functionCall") -> {
                val functionCall = jsonObject["functionCall"]?.jsonObject ?: return null
                UIMessagePart.ToolCall(
                    toolCallId = "",
                    toolName = functionCall["name"]?.jsonPrimitive?.contentOrNull ?: "",
                    arguments = json.encodeToString(functionCall["args"] ?: JsonObject(emptyMap()))
                )
            }
            else -> null
        }
    }

    private fun parseStreamChunk(data: String, modelId: String): MessageChunk? {
        val jsonData = json.parseToJsonElement(data).jsonObject
        val candidates = jsonData["candidates"]?.jsonArray ?: return null
        if (candidates.isEmpty()) return null

        return MessageChunk(
            id = Uuid.random().toString(),
            model = modelId,
            choices = candidates.mapIndexed { index, candidate ->
                val candidateObj = candidate.jsonObject
                val finishReason = candidateObj["finishReason"]?.jsonPrimitive?.contentOrNull
                val message = parseMessage(candidateObj)

                UIMessageChoice(
                    index = index,
                    delta = message,
                    message = null,
                    finishReason = finishReason
                )
            }
        )
    }

    private fun parseStreamFailure(event: PlatformServerEvent.Failure): Throwable {
        return Exception("Stream failed${event.statusCode?.let { " #$it" }.orEmpty()}: ${event.message.orEmpty()}")
    }

    override suspend fun getBalance(providerSetting: ProviderSetting.Antigravity): String = "Unlimited (Google OAuth)"

    override suspend fun generateImage(providerSetting: ProviderSetting, params: ImageGenerationParams): ImageGenerationResult {
        error("Image generation not supported directly via Antigravity text API")
    }

    override suspend fun createEmbedding(providerSetting: ProviderSetting.Antigravity, input: List<String>, model: Model): List<List<Float>> {
        error("Embeddings not supported directly via Antigravity text API")
    }
}
