package me.rerere.rikkahub.data.antigravity

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "AntigravityManager"

object AntigravityAccountManager {
    // Email -> (ModelFamily -> Cooldown expiry timestamp in ms)
    private val cooldowns = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()
    
    // Email -> Health Score (0-100)
    private val healthScores = ConcurrentHashMap<String, Int>()
    
    // Email -> Last Used Timestamp (ms)
    private val lastUsed = ConcurrentHashMap<String, Long>()
    
    // Email -> Consecutive Failures
    private val consecutiveFailures = ConcurrentHashMap<String, Int>()
    
    // SessionId -> Email (Sticky Client Mapping)
    private val clientStickyMap = ConcurrentHashMap<String, String>()

    fun getFamilyName(modelId: String): String {
        val n = modelId.lowercase()
        return when {
            n.contains("gemini") && n.contains("flash") && !n.contains("2.5") -> "Gemini 3 Flash"
            n.contains("gemini") && (n.contains("pro") || n.contains("image")) && !n.contains("2.5") -> "Gemini 3 Pro"
            n.contains("2.5") -> "Gemini 2.5"
            n.contains("claude") || n.contains("gpt") -> "Claude/GPT"
            else -> "Other"
        }
    }

    fun getHealthScore(email: String): Int {
        return healthScores.getOrPut(email) { 100 }
    }

    fun getLastUsed(email: String): Long {
        return lastUsed.getOrPut(email) { 0L }
    }

    fun isCooldown(email: String, modelId: String): Boolean {
        val family = getFamilyName(modelId)
        val expiry = cooldowns[email]?.get(family) ?: 0L
        return expiry > System.currentTimeMillis()
    }

    fun getCooldownExpiry(email: String, modelId: String): Long {
        val family = getFamilyName(modelId)
        return cooldowns[email]?.get(family) ?: 0L
    }

    fun setCooldown(email: String, modelId: String, durationMs: Long) {
        val family = getFamilyName(modelId)
        cooldowns.getOrPut(email) { ConcurrentHashMap() }[family] = System.currentTimeMillis() + durationMs
        Log.i(TAG, "Account $email put on cooldown for family $family for ${durationMs / 1000}s")
    }

    fun onSuccess(email: String, modelId: String, sessionId: String?) {
        val family = getFamilyName(modelId)
        cooldowns[email]?.remove(family)
        consecutiveFailures[email] = 0
        lastUsed[email] = System.currentTimeMillis()
        
        val currentHealth = getHealthScore(email)
        healthScores[email] = (currentHealth + 2).coerceAtMost(100)
        
        if (!sessionId.isNullOrBlank()) {
            clientStickyMap[sessionId] = email
            Log.d(TAG, "Sticky mapping set: Session $sessionId -> Account $email")
        }
    }

    fun onFailure(email: String, modelId: String, sessionId: String?, statusCode: Int?) {
        val family = getFamilyName(modelId)
        lastUsed[email] = System.currentTimeMillis()
        
        val failures = (consecutiveFailures[email] ?: 0) + 1
        consecutiveFailures[email] = failures
        
        val currentHealth = getHealthScore(email)
        val penalty = when (statusCode) {
            403 -> 50 // Disabled / impersonation error
            429 -> 20 // Rate limited
            else -> 10 // Other error
        }
        healthScores[email] = (currentHealth - penalty).coerceAtLeast(0)
        
        // Put account on cooldown on rate limits or consecutive failures
        val cooldownMs = if (statusCode == 429) 60000L else 30000L * failures.coerceAtMost(5)
        setCooldown(email, modelId, cooldownMs)

        if (!sessionId.isNullOrBlank() && clientStickyMap[sessionId] == email) {
            clientStickyMap.remove(sessionId)
        }
    }

    fun getStickyAccount(sessionId: String?): String? {
        if (sessionId.isNullOrBlank()) return null
        return clientStickyMap[sessionId]
    }
}
