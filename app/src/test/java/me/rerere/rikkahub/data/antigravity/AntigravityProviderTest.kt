package me.rerere.rikkahub.data.antigravity

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class AntigravityProviderTest {

    @Test
    fun testRealApiCall() = runBlocking {
        // Read credentials dynamically from local proxy configuration file to avoid committing secrets
        val accountsFile = File("/data/data/com.termux/files/home/antigravity-proxy-fork/antigravity-proxy-rust/antigravity-accounts.json")
        if (!accountsFile.exists()) {
            println("Skipping integration test: antigravity-accounts.json not found locally")
            return@runBlocking
        }

        val json = Json { ignoreUnknownKeys = true }
        val accountsJson = json.parseToJsonElement(accountsFile.readText()).jsonObject
        val firstAccount = accountsJson["accounts"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: error("No accounts found in proxy config")

        val refreshToken = firstAccount["refreshToken"]?.jsonPrimitive?.content ?: ""
        val projectId = firstAccount["projectId"]?.jsonPrimitive?.content ?: "walproj"
        val email = firstAccount["email"]?.jsonPrimitive?.content ?: "walidelonk@gmail.com"

        assertTrue("Refresh token must not be blank", refreshToken.isNotBlank())

        val client = OkHttpClient()

        // 1. Refresh access token
        val tokenResponse = try {
            val response = client.newCall(
                okhttp3.Request.Builder()
                    .url(AntigravityOAuthManager.TOKEN_URL)
                    .post(
                        okhttp3.FormBody.Builder()
                            .add("client_id", AntigravityOAuthManager.CLIENT_ID)
                            .add("client_secret", AntigravityOAuthManager.CLIENT_SECRET)
                            .add("grant_type", "refresh_token")
                            .add("refresh_token", refreshToken)
                            .build()
                    )
                    .build()
            ).execute()
            
            val body = response.body?.string() ?: ""
            assertTrue("Token refresh response should be successful: $body", response.isSuccessful)
            
            json.decodeFromString<GoogleTokenResponse>(body)
        } catch (e: Exception) {
            println("Token refresh failed: ${e.message}")
            throw e
        }

        println("Refreshed access token successfully: ${tokenResponse.accessToken.take(15)}...")

        // 2. Fetch models using the refreshed access token
        val deviceId = "6893e03dd9a032a9"
        val quotaUser = "device-$deviceId"
        val apiClient = "google-cloud-sdk vscode/1.96.0"
        val clientMetadataJson = "{\"ideType\":\"VSCODE\",\"platform\":\"MACOS\",\"pluginType\":\"GEMINI\",\"osVersion\":\"15.0\",\"arch\":\"arm64\",\"sqmId\":\"8063272e-58c5-47a5-993d-b15033dcdbeb\"}"

        val requestBody = buildJsonObject {
            put("project", JsonPrimitive(projectId))
        }

        val modelResponse = client.newCall(
            okhttp3.Request.Builder()
                .url("https://daily-cloudcode-pa.googleapis.com/v1internal:fetchAvailableModels")
                .post(
                    okhttp3.RequestBody.create(
                        okhttp3.MediaType.parse("application/json"),
                        requestBody.toString()
                    )
                )
                .addHeader("Authorization", "Bearer ${tokenResponse.accessToken}")
                .addHeader("x-goog-api-client", apiClient)
                .addHeader("x-goog-quotauser", quotaUser)
                .addHeader("x-client-device-id", deviceId)
                .addHeader("client-metadata", clientMetadataJson)
                .addHeader("User-Agent", "antigravity")
                .addHeader("Content-Type", "application/json")
                .build()
        ).execute()

        val modelBody = modelResponse.body?.string() ?: ""
        println("Model list response code: ${modelResponse.code}")
        println("Model list response body: $modelBody")

        assertTrue("Model list response should be successful: $modelBody", modelResponse.isSuccessful)
        assertTrue("Response should contain availableModels or models", modelBody.contains("models"))
    }
}
