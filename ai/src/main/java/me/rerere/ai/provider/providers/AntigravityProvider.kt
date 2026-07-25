package me.rerere.ai.provider.providers

import kotlinx.coroutines.flow.Flow
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ImageGenerationResult
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.common.platform.PlatformHttpClient
import me.rerere.common.platform.PlatformMediaEncoder

class AntigravityProvider(
    platformHttpClient: PlatformHttpClient,
    platformMediaEncoder: PlatformMediaEncoder,
) : Provider<ProviderSetting.Antigravity> {
    private val delegate = OpenAIProvider(platformHttpClient, platformMediaEncoder)

    private fun ProviderSetting.Antigravity.toOpenAI(): ProviderSetting.OpenAI {
        val rawUrl = this.baseUrl.trim()
        val cleanBaseUrl = if (rawUrl.contains("cloudcode-pa.googleapis.com") || rawUrl.isBlank()) {
            "http://127.0.0.1:3000/v1"
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
            builtIn = this.builtIn,
            apiKey = this.apiKey.ifBlank { "any" },
            baseUrl = cleanBaseUrl,
            chatCompletionsPath = "/chat/completions",
            useResponseApi = false
        )
    }

    override suspend fun listModels(providerSetting: ProviderSetting.Antigravity): List<Model> {
        return delegate.listModels(providerSetting.toOpenAI())
    }

    override suspend fun getBalance(providerSetting: ProviderSetting.Antigravity): String {
        return delegate.getBalance(providerSetting.toOpenAI())
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.Antigravity,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): MessageChunk {
        return delegate.generateText(providerSetting.toOpenAI(), messages, params)
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Antigravity,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<MessageChunk> {
        return delegate.streamText(providerSetting.toOpenAI(), messages, params)
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams
    ): ImageGenerationResult {
        val mappedSetting = (providerSetting as? ProviderSetting.Antigravity)?.toOpenAI() ?: providerSetting
        return delegate.generateImage(mappedSetting, params)
    }

    override suspend fun createEmbedding(
        providerSetting: ProviderSetting.Antigravity,
        input: List<String>,
        model: Model
    ): List<List<Float>> {
        return delegate.createEmbedding(providerSetting.toOpenAI(), input, model)
    }
}
