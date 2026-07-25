package me.rerere.rikkahub.data.antigravity

import android.content.Context
import kotlinx.coroutines.flow.Flow
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.ui.ImageGenerationResult
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.common.platform.PlatformMediaEncoder
import me.rerere.common.platform.android.OkHttpPlatformHttpClient
import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.OkHttpClient

class AntigravityProvider(
    private val context: Context,
    private val client: OkHttpClient,
    private val mediaEncoder: PlatformMediaEncoder,
    private val oauthManager: AntigravityOAuthManager,
    private val settingsStore: SettingsStore,
    private val antigravityProxyManager: AntigravityProxyManager,
) : Provider<ProviderSetting.Antigravity> {

    private val platformHttpClient = OkHttpPlatformHttpClient(client)
    private val delegate = me.rerere.ai.provider.providers.OpenAIProvider(
        platformHttpClient = platformHttpClient,
        platformMediaEncoder = mediaEncoder,
    )

    private fun ProviderSetting.Antigravity.toOpenAI(): ProviderSetting.OpenAI {
        val port = antigravityProxyManager.activePort
        val rawUrl = this.baseUrl.trim()
        val effectiveBaseUrl = if (rawUrl.startsWith("http://127.0.0.1") || rawUrl.startsWith("http://localhost")) {
            rawUrl.trimEnd('/')
        } else if (rawUrl.contains("cloudcode-pa.googleapis.com") || rawUrl.isBlank()) {
            "http://127.0.0.1:$port/v1"
        } else {
            rawUrl.trimEnd('/')
        }
        return ProviderSetting.OpenAI(
            id = this.id,
            enabled = this.enabled,
            name = this.name,
            models = this.models,
            proxy = this.proxy,
            balanceOption = this.balanceOption,
            tags = this.tags,
            customIconUri = this.customIconUri,
            apiKey = this.apiKey.ifBlank { "any" },
            baseUrl = effectiveBaseUrl,
            chatCompletionsPath = "/chat/completions",
            useResponseApi = false
        )
    }

    override suspend fun listModels(providerSetting: ProviderSetting.Antigravity): List<Model> {
        antigravityProxyManager.ensureRunning()
        return delegate.listModels(providerSetting.toOpenAI())
    }

    override suspend fun getBalance(providerSetting: ProviderSetting.Antigravity): String {
        antigravityProxyManager.ensureRunning()
        return delegate.getBalance(providerSetting.toOpenAI())
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.Antigravity,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): MessageChunk {
        antigravityProxyManager.ensureRunning()
        return delegate.generateText(providerSetting.toOpenAI(), messages, params)
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Antigravity,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<MessageChunk> {
        antigravityProxyManager.ensureRunning()
        return delegate.streamText(providerSetting.toOpenAI(), messages, params)
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams
    ): ImageGenerationResult {
        antigravityProxyManager.ensureRunning()
        val mappedSetting = (providerSetting as? ProviderSetting.Antigravity)?.toOpenAI() ?: providerSetting
        return delegate.generateImage(mappedSetting, params)
    }

    override suspend fun createEmbedding(
        providerSetting: ProviderSetting.Antigravity,
        input: List<String>,
        model: Model
    ): List<List<Float>> {
        antigravityProxyManager.ensureRunning()
        return delegate.createEmbedding(providerSetting.toOpenAI(), input, model)
    }
}
