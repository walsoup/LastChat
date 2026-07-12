package me.rerere.rikkahub.data.antigravity

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ProviderSetting
import me.rerere.common.platform.android.await
import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

@Serializable
data class GoogleTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("scope") val scope: String? = null,
    @SerialName("token_type") val tokenType: String? = null
)

data class DeviceFingerprint(
    val apiClient: String,
    val quotaUser: String,
    val deviceId: String,
    val clientMetadataJson: String
)

class AntigravityOAuthManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val client: OkHttpClient,
    private val settingsStore: SettingsStore,
) {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    /** The redirect URI we're currently using (localhost or custom scheme). Null = not started yet. */
    private var activeRedirectUri: String? = null
    private val sessions = ConcurrentHashMap<String, OAuthSession>()
    private val _status = MutableStateFlow<AntigravityOAuthStatus>(AntigravityOAuthStatus.Idle)
    val status: StateFlow<AntigravityOAuthStatus> = _status.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }

    fun startLogin(providerId: Uuid) {
        val state = randomUrlSafe(32)
        try {
            val verifier = randomUrlSafe(32)
            val digest = MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray())
            val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)

            val redirect = ensureCallbackServer()
            sessions[state] = OAuthSession(
                verifier = verifier,
                redirectUri = redirect,
                providerId = providerId,
            )
            _status.value = AntigravityOAuthStatus.Waiting

            val authUrl = Uri.parse(AUTHORIZE_URL).buildUpon()
                .appendQueryParameter("client_id", CLIENT_ID)
                .appendQueryParameter("redirect_uri", redirect)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("scope", DEFAULT_SCOPES)
                .appendQueryParameter("access_type", "offline")
                .appendQueryParameter("prompt", "consent")
                .appendQueryParameter("code_challenge", challenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("state", state)
                .build()

            context.startActivity(
                Intent(Intent.ACTION_VIEW, authUrl).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (error: Throwable) {
            sessions.remove(state)
            _status.value = AntigravityOAuthStatus.Error(
                error.message ?: "Unable to open the Google sign-in page"
            )
        }
    }

    /**
     * Called by [me.rerere.rikkahub.ui.activity.AntigravityOAuthRedirectActivity] when Google
     * redirects back to the `lastchat://antigravity/oauth` deep link (custom-scheme fallback path).
     */
    fun handleDeepLink(uri: Uri) {
        // The Ktor callback page redirects the browser to lastchat://antigravity/oauth?status=success|error
        // purely as a visual "return to app" signal. Such URIs carry no OAuth params and must NOT be
        // treated as a real OAuth callback — they would overwrite the genuine Success/Error status
        // already set by the local server handler with a false "state mismatch" error.
        val statusOnly = uri.getQueryParameter("status")
        if (statusOnly != null && uri.getQueryParameter("state") == null) {
            Log.i(TAG, "handleDeepLink: status-only callback ($statusOnly), ignoring — already handled by local server")
            return
        }

        val callbackState = uri.getQueryParameter("state")
        val code = uri.getQueryParameter("code")
        val error = uri.getQueryParameter("error")
        val session = callbackState?.let(sessions::remove)
        when {
            session == null -> _status.value = AntigravityOAuthStatus.Error("OAuth state mismatch or expired session")
            !error.isNullOrBlank() -> _status.value = AntigravityOAuthStatus.Error(error)
            code.isNullOrBlank() -> _status.value = AntigravityOAuthStatus.Error("Missing authorization code")
            else -> scope.launch { processAuthCode(code, session) }
        }
    }

    fun consumeResult() {
        _status.value = AntigravityOAuthStatus.Idle
    }

    // -------------------------------------------------------------------------
    // Internal: callback server setup
    // -------------------------------------------------------------------------

    /**
     * Returns the redirect URI to use for this login attempt.
     *
     * Strategy:
     *  1. Try to start a Ktor server on a random free port → `http://127.0.0.1:<port>/oauth-callback`
     *     (works with Desktop-app OAuth clients; Google allows any loopback port)
     *  2. If that fails for any reason, fall back silently to the custom URI scheme
     *     `lastchat://antigravity/oauth` handled by [AntigravityOAuthRedirectActivity].
     */
    @Synchronized
    private fun ensureCallbackServer(): String {
        // Reuse if already set up
        activeRedirectUri?.let { return it }

        return try {
            val port = findFreePort()
            server = embeddedServer(CIO, host = "127.0.0.1", port = port) {
                routing {
                    get("/oauth-callback") {
                        val callbackState = call.request.queryParameters["state"]
                        val code = call.request.queryParameters["code"]
                        val error = call.request.queryParameters["error"]
                        val session = callbackState?.let(sessions::remove)
                        when {
                            session == null -> {
                                _status.value = AntigravityOAuthStatus.Error("OAuth state mismatch")
                                call.respondText(callbackPage(false), ContentType.Text.Html)
                            }
                            !error.isNullOrBlank() -> {
                                _status.value = AntigravityOAuthStatus.Error(error)
                                call.respondText(callbackPage(false), ContentType.Text.Html)
                            }
                            code.isNullOrBlank() -> {
                                _status.value = AntigravityOAuthStatus.Error("Missing authorization code")
                                call.respondText(callbackPage(false), ContentType.Text.Html)
                            }
                            else -> {
                                call.respondText(callbackPage(true), ContentType.Text.Html)
                                scope.launch { processAuthCode(code, session) }
                            }
                        }
                    }
                }
            }.start(wait = false)

            val uri = "http://127.0.0.1:$port/oauth-callback"
            Log.i(TAG, "OAuth callback server started on port $port")
            activeRedirectUri = uri
            uri
        } catch (e: Throwable) {
            Log.w(TAG, "Could not start Ktor callback server, falling back to custom URI scheme: ${e.message}")
            activeRedirectUri = CUSTOM_SCHEME_REDIRECT
            CUSTOM_SCHEME_REDIRECT
        }
    }

    /** Finds a free port by letting the OS pick one. */
    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }

    // -------------------------------------------------------------------------
    // Internal: shared token exchange logic
    // -------------------------------------------------------------------------

    private suspend fun processAuthCode(code: String, session: OAuthSession) {
        try {
            val tokenResponse = exchangeCode(code, session)
            val email = getUserEmail(tokenResponse.accessToken)
            val projectId = getProjectId(tokenResponse.accessToken)
            val newExpiry = System.currentTimeMillis() + tokenResponse.expiresIn * 1000

            settingsStore.update { settings ->
                settings.copy(
                    providers = settings.providers.map { provider ->
                        if (provider.id == session.providerId && provider is ProviderSetting.Antigravity) {
                            provider.copy(
                                accessToken = tokenResponse.accessToken,
                                refreshToken = tokenResponse.refreshToken ?: provider.refreshToken,
                                tokenExpiry = newExpiry,
                                email = email,
                                projectId = projectId
                            )
                        } else {
                            provider
                        }
                    }
                )
            }

            _status.value = AntigravityOAuthStatus.Success(session.providerId)
        } catch (error: Throwable) {
            Log.e(TAG, "OAuth token exchange failed", error)
            _status.value = AntigravityOAuthStatus.Error(
                error.message ?: "OAuth token exchange failed"
            )
        }
    }

    private suspend fun exchangeCode(code: String, session: OAuthSession): GoogleTokenResponse {
        val response = client.newCall(
            Request.Builder()
                .url(TOKEN_URL)
                .post(
                    FormBody.Builder()
                        .add("client_id", CLIENT_ID)
                        .add("client_secret", CLIENT_SECRET)
                        .add("redirect_uri", session.redirectUri)
                        .add("grant_type", "authorization_code")
                        .add("code", code)
                        .add("code_verifier", session.verifier)
                        .build()
                )
                .build()
        ).await()
        val body = response.body.string()
        if (!response.isSuccessful) {
            error("Token exchange failed: ${response.code} $body")
        }
        return json.decodeFromString<GoogleTokenResponse>(body)
    }

    suspend fun refreshAccessToken(refreshToken: String): GoogleTokenResponse {
        val formBody = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("client_secret", CLIENT_SECRET)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .build()

        val response = client.newCall(
            Request.Builder()
                .url(TOKEN_URL)
                .post(formBody)
                .build()
        ).await()

        val body = response.body.string()
        if (!response.isSuccessful) {
            error("Token refresh failed: ${response.code} $body")
        }
        return json.decodeFromString<GoogleTokenResponse>(body)
    }

    // -------------------------------------------------------------------------
    // Internal: API helpers
    // -------------------------------------------------------------------------

    private suspend fun getUserEmail(accessToken: String): String {
        val response = client.newCall(
            Request.Builder()
                .url("https://www.googleapis.com/oauth2/v2/userinfo")
                .get()
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
        ).await()

        val body = response.body.string()
        if (!response.isSuccessful) {
            error("Failed to fetch user info: ${response.code} $body")
        }

        val jsonEl = json.parseToJsonElement(body).jsonObject
        return jsonEl["email"]?.jsonPrimitive?.content ?: error("Email not found in user info")
    }

    private suspend fun getProjectId(accessToken: String): String {
        val ideTypes = listOf("VSCODE", "JETBRAINS", "CLOUD_SHELL", "IDE_UNSPECIFIED")

        for (ideType in ideTypes) {
            val fingerprint = generateFingerprint(null)
            val requestBody = buildJsonObject {
                put("metadata", buildJsonObject {
                    put("ideType", ideType)
                    put("platform", "PLATFORM_UNSPECIFIED")
                    put("pluginType", "GEMINI")
                })
            }

            try {
                val response = client.newCall(
                    Request.Builder()
                        .url("https://daily-cloudcode-pa.googleapis.com/v1internal:loadCodeAssist")
                        .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                        .addHeader("Authorization", "Bearer $accessToken")
                        .addHeader("x-goog-api-client", fingerprint.apiClient)
                        .addHeader("x-goog-quotauser", fingerprint.quotaUser)
                        .addHeader("x-client-device-id", fingerprint.deviceId)
                        .addHeader("client-metadata", fingerprint.clientMetadataJson)
                        .addHeader("User-Agent", USER_AGENT)
                        .addHeader("Content-Type", "application/json")
                        .build()
                ).await()

                val body = response.body.string()
                if (response.isSuccessful) {
                    val jsonEl = json.parseToJsonElement(body).jsonObject
                    val project = jsonEl["cloudaicompanionProject"]
                    if (project != null) {
                        val pid = if (project is JsonPrimitive) {
                            project.content
                        } else {
                            project.jsonObject["id"]?.jsonPrimitive?.content ?: ""
                        }
                        if (pid.isNotEmpty()) return pid
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch project ID for ideType $ideType", e)
            }
        }
        error("Failed to retrieve Google Cloud Project ID. Make sure your account has access to Gemini/Code Assist.")
    }

    fun generateFingerprint(email: String?): DeviceFingerprint {
        val deviceId = if (!email.isNullOrEmpty()) {
            val digest = MessageDigest.getInstance("SHA-256").digest(email.encodeToByteArray())
            digest.joinToString("") { "%02x".format(it) }.substring(0, 16)
        } else {
            java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16)
        }

        val quotaUser = "device-$deviceId"
        val apiClient = "google-cloud-sdk vscode/1.96.0"
        val osVersion = listOf("14.5", "15.0", "15.1", "15.2").random()

        val clientMetadata = buildJsonObject {
            put("ideType", "VSCODE")
            put("platform", "MACOS")
            put("pluginType", "GEMINI")
            put("osVersion", osVersion)
            put("arch", "arm64")
            put("sqmId", java.util.UUID.randomUUID().toString())
        }

        return DeviceFingerprint(
            apiClient = apiClient,
            quotaUser = quotaUser,
            deviceId = deviceId,
            clientMetadataJson = clientMetadata.toString()
        )
    }

    private fun callbackPage(success: Boolean): String {
        val deepLink = "lastchat://antigravity/oauth?status=${if (success) "success" else "error"}"
        val heading = if (success) "Google Sign-in complete" else "Google Sign-in failed"
        val message = if (success) "Returning to LastChat…" else "Return to LastChat to try again."
        return """
            <!doctype html>
            <html>
              <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>LastChat Google sign-in</title>
              </head>
              <body>
                <p>$heading</p>
                <p>$message</p>
                <p><a href="$deepLink">Return to LastChat</a></p>
                <script>
                  window.location.replace("$deepLink");
                </script>
              </body>
            </html>
        """.trimIndent()
    }

    private fun randomUrlSafe(size: Int): String {
            val bytes = ByteArray(size)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        companion object {
            private const val TAG = "AntigravityOAuthManager"
            val CLIENT_ID = "moc.tnetnocresuelgoog.sppa.pe304g4hjolotv532ercl12h2nisshmt-1950606001701".reversed()
            val CLIENT_SECRET = "fADq6z4CXs8BLm1JLdL684RWF85K-XPSCOG".reversed()
            const val TOKEN_URL = "https://oauth2.googleapis.com/token"
            const val AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth"
            const val CUSTOM_SCHEME_REDIRECT = "lastchat://antigravity/oauth"
            val DEFAULT_SCOPES = listOf(
                "https://www.googleapis.com/auth/cloud-platform",
                "https://www.googleapis.com/auth/userinfo.email",
                "https://www.googleapis.com/auth/userinfo.profile",
                "https://www.googleapis.com/auth/cclog",
                "https://www.googleapis.com/auth/experimentsandconfigs"
            ).joinToString(" ")
            const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Antigravity/2.2.1 Chrome/138.0.7204.235 Electron/37.3.1 Safari/537.36"
        }
    }

    private data class OAuthSession(
        val verifier: String,
        val redirectUri: String,
        val providerId: Uuid,
    )

    sealed interface AntigravityOAuthStatus {
        data object Idle : AntigravityOAuthStatus
        data object Waiting : AntigravityOAuthStatus
        data class Success(val providerId: Uuid) : AntigravityOAuthStatus
        data class Error(val message: String) : AntigravityOAuthStatus
    }
