package me.rerere.rikkahub.ui.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import me.rerere.rikkahub.data.antigravity.AntigravityOAuthManager
import org.koin.android.ext.android.inject

class AntigravityOAuthRedirectActivity : ComponentActivity() {
    private val oauthManager: AntigravityOAuthManager by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Forward the OAuth deep link URI to the manager for token exchange
        val uri = intent?.data
        if (uri != null) {
            oauthManager.handleDeepLink(uri)
        }
        // Return to main app
        startActivity(
            Intent(this, me.rerere.rikkahub.RouteActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
        )
        finish()
    }
}
