package com.asterleen.provodach.ui

import android.content.Context

internal class VkAuthSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("vk_auth_pending", Context.MODE_PRIVATE)

    fun read(): VkAuthProtocol.Session? {
        val state = prefs.getString("state", null) ?: return null
        val verifier = prefs.getString("verifier", null) ?: return null
        return VkAuthProtocol.Session(state, verifier, prefs.getLong("created_at", 0))
            .takeIf { VkAuthProtocol.isValid(it) }
    }

    fun save(session: VkAuthProtocol.Session) {
        prefs.edit().putString("state", session.state).putString("verifier", session.verifier)
            .putLong("created_at", session.createdAt).commit()
    }

    fun clear() { prefs.edit().clear().apply() }
}
