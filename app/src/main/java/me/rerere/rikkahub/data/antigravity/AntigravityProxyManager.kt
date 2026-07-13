package me.rerere.rikkahub.data.antigravity

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.ai.provider.ProviderSetting
import java.io.File

private const val TAG = "AntigravityProxy"

class AntigravityProxyManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
) {
    private val mutex = Mutex()
    private var process: Process? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val port = 3000

    suspend fun ensureRunning() {
        mutex.withLock {
            if (process?.isAlive == true) {
                return
            }
            startProxyInternal()
        }
    }

    private suspend fun startProxyInternal() {
        try {
            Log.i(TAG, "Starting Antigravity Proxy...")
            
            val filesDir = context.filesDir
            val configFile = File(filesDir, "antigravity-config.json")
            val accountsFile = File(filesDir, "antigravity-accounts.json")
            
            // 1. Write config.json if not present
            if (!configFile.exists()) {
                val defaultConfig = """
                {
                  "rotation": {
                    "strategy": "hybrid",
                    "cooldown": {
                      "defaultDurationMs": 60000,
                      "maxDurationMs": 3600000
                    }
                  },
                  "scoring": {
                    "healthRange": {
                      "min": 0,
                      "max": 100,
                      "initial": 100
                    },
                    "penalties": {
                      "apiError": -10,
                      "refreshError": -20,
                      "fatalError": -50,
                      "systemicError": -5
                    },
                    "rewards": {
                      "success": 1
                    },
                    "weights": {
                      "health": 2.0,
                      "lru": 0.1
                    }
                  },
                  "models": {
                    "blacklist": [],
                    "routing": {
                      "sandboxKeywords": ["gpt", "antigravity", "image"],
                      "cliKeywords": ["claude", "gemini-2.0", "gemini-2.5", "-preview"],
                      "forceToSandbox": ["gpt"]
                    },
                    "timeouts": {
                      "default": 30000,
                      "claude": 60000,
                      "gemini-3-pro": 45000,
                      "gemini-3.1-pro": 45000,
                      "thinking": 120000
                    }
                  },
                  "retry": {
                    "maxAttempts": 5,
                    "transientRetryThresholdSeconds": 5
                  },
                  "tokens": {
                    "expiryBufferMs": 60000
                  },
                  "quota": {
                    "refreshIntervalMs": 300000,
                    "initialDelayMs": 10000
                  },
                  "endpoints": {
                    "sandbox": [
                      "https://cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse",
                      "https://daily-cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse"
                    ],
                    "cli": [
                      "https://cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse",
                      "https://daily-cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse"
                    ]
                  },
                  "logging": {
                    "maxBufferSize": 200,
                    "enableConsoleCapture": true,
                    "disableRequestLogging": false
                  },
                  "features": {
                    "googleSearchGrounding": true,
                    "exposeVariants": false,
                    "groundingMode": "auto",
                    "keepThinking": false,
                    "sanitizeToolNames": true,
                    "pidOffsetEnabled": false,
                    "softQuotaThresholdPercent": 90,
                    "jitterEnabled": false,
                    "jitterMinMs": 50,
                    "jitterMaxMs": 300,
                    "defaultProjectId": null,
                    "sanitizeAntigravityPrompts": false,
                    "syntheticSearch": false,
                    "syntheticSearchModel": "gemini-2.5-flash",
                    "interceptSearch": false,
                    "prioritizeSearchOverTools": false,
                    "obscureModels": false,
                    "exactRequestCaching": false,
                    "promptCaching": false,
                    "fastMode": false,
                    "safeguardEmptyContent": false,
                    "safeguardRoles": false,
                    "safeguardSchemas": false,
                    "safeguardContext": false
                  },
                  "scheduling": {
                    "mode": "balance",
                    "maxCacheFirstWaitSeconds": 5,
                    "maxRateLimitWaitSeconds": 300
                  },
                  "alerting": {
                    "webhookUrl": "",
                    "healthThreshold": 30,
                    "notifyOnFullCooldown": true
                  }
                }
                """.trimIndent()
                configFile.writeText(defaultConfig)
            }

            // 2. Read accounts from SettingsStore and write to accountsFile (preserving proxy states if matched)
            val settings = settingsStore.settingsFlowRaw.first()
            val antigravityProviders = settings.providers.filterIsInstance<ProviderSetting.Antigravity>()

            val existingAccounts = runCatching {
                if (accountsFile.exists()) {
                    Json.parseToJsonElement(accountsFile.readText()).jsonObject["accounts"]?.jsonArray
                } else null
            }.getOrNull()?.associateBy { it.jsonObject["email"]?.jsonPrimitive?.contentOrNull ?: "" }
            
            val accountsJsonObj = buildJsonObject {
                putJsonArray("accounts") {
                    antigravityProviders.forEach { provider ->
                        val existing = existingAccounts?.get(provider.email)?.jsonObject
                        addJsonObject {
                            put("email", provider.email)
                            put("refreshToken", provider.refreshToken)

                            val sameToken = existing?.get("refreshToken")?.jsonPrimitive?.contentOrNull == provider.refreshToken
                            put("accessToken", if (sameToken) existing?.get("accessToken")?.jsonPrimitive?.contentOrNull ?: provider.accessToken else provider.accessToken)
                            put("expiresAt", if (sameToken) existing?.get("expiresAt")?.jsonPrimitive?.longOrNull ?: provider.tokenExpiry else provider.tokenExpiry)

                            val projId = provider.projectId.ifEmpty { existing?.get("projectId")?.jsonPrimitive?.contentOrNull ?: "" }
                            if (projId.isNotEmpty()) {
                                put("projectId", projId)
                                put("managedProjectId", projId)
                            }

                            put("healthScore", if (sameToken) existing?.get("healthScore")?.jsonPrimitive?.intOrNull ?: 100 else 100)
                            put("lastUsed", if (sameToken) existing?.get("lastUsed")?.jsonPrimitive?.longOrNull ?: 0L else 0L)
                            put("tokenUsage", if (sameToken) existing?.get("tokenUsage")?.jsonPrimitive?.longOrNull ?: 0L else 0L)

                            if (sameToken && existing != null) {
                                existing["modelScores"]?.let { put("modelScores", it) }
                                existing["cooldowns"]?.let { put("cooldowns", it) }
                                existing["fingerprint"]?.let { put("fingerprint", it) }
                                existing["quota"]?.let { put("quota", it) }
                            }
                        }
                    }
                }
                put("strategy", "hybrid")
            }
            accountsFile.writeText(accountsJsonObj.toString())

            // 3. Start the process
            val nativeLibraryDir = context.applicationInfo.nativeLibraryDir
            val binaryFile = File(nativeLibraryDir, "libantigravity-proxy.so")
            if (!binaryFile.exists()) {
                Log.e(TAG, "Antigravity Proxy binary not found: ${binaryFile.absolutePath}")
                return
            }

            binaryFile.setExecutable(true)

            val pb = ProcessBuilder(binaryFile.absolutePath)
                .directory(filesDir)
                .redirectErrorStream(true)

            pb.environment().apply {
                put("PORT", port.toString())
                put("CONFIG_FILE", configFile.absolutePath)
                put("ACCOUNTS_FILE", accountsFile.absolutePath)
            }

            val proc = pb.start()
            process = proc
            Log.i(TAG, "Antigravity Proxy process started successfully.")

            job?.cancel()
            job = scope.launch {
                try {
                    proc.inputStream.bufferedReader().use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            Log.d(TAG, "[Rust] $line")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading proxy output", e)
                } finally {
                    runCatching { Log.w(TAG, "Antigravity Proxy process exited with code: ${proc.exitValue()}") }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Antigravity Proxy", e)
        }
    }

    fun stop() {
        process?.destroy()
        process = null
        job?.cancel()
        job = null
    }
}
