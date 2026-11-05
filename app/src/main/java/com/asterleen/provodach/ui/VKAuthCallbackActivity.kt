package com.asterleen.provodach.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** The only exported VK callback entry; it never accepts a bearer token or a caller-selected intent. */
class VKAuthCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forward(intent)
        finish()
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        intent = newIntent
        forward(newIntent)
        finish()
    }

    private fun forward(incoming: Intent) {
        if (incoming.action != Intent.ACTION_VIEW) return
        val session = VkAuthSessionStore(this).read() ?: return
        val url = incoming.dataString ?: return
        if (VkAuthProtocol.parseCallback(url, session) == null) return
        // Build a fresh explicit intent; do not forward flags, extras or a nested intent from outside.
        startActivity(Intent(this, VKAuthActivity::class.java).apply {
            action = VKAuthActivity.ACTION_CALLBACK
            data = incoming.data
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
    }
}
