package com.asterleen.provodach.ui

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/** Wire contract shared with server/airin-web-api/engine/kiara-auth.php. */
internal object VkAuthProtocol {
    const val API_ADDR = "https://api.nay.su/airin/auth/external"
    const val API_VK_ID = "5216648"
    const val CALLBACK = "kiara-vk://auth"
    const val PROTOCOL = "kiara-vk-1"
    const val SESSION_TTL_MS = 10 * 60 * 1000L

    data class Session(val state: String, val verifier: String, val createdAt: Long)
    data class Callback(val ticket: String?, val error: String?)

    private fun randomHex(bytes: Int): String = ByteArray(bytes).also {
        SecureRandom().nextBytes(it)
    }.joinToString("") { "%02x".format(it.toInt() and 255) }

    fun newSession(now: Long = System.currentTimeMillis()): Session =
        Session(randomHex(16), randomHex(32), now)

    fun challenge(verifier: String): String =
        MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun isValid(session: Session, now: Long = System.currentTimeMillis()): Boolean =
        session.state.matches(Regex("[a-f0-9]{32}")) &&
            session.verifier.matches(Regex("[a-f0-9]{64}")) &&
            now >= session.createdAt && now - session.createdAt < SESSION_TTL_MS

    // engine/functions.php::mknonce() produces 64 case-sensitive alphanumeric characters.
    fun isValidAuthToken(token: String): Boolean = token.matches(Regex("[A-Za-z0-9]{64}"))

    fun authorizationUrl(session: Session): String {
        val params = linkedMapOf(
            "client_id" to API_VK_ID,
            "redirect_uri" to API_ADDR,
            "response_type" to "code",
            "display" to "mobile",
            // State identifies the app flow and binds its one-time handoff to a private verifier.
            "state" to "kiara1.${session.state}.${challenge(session.verifier)}"
        )
        return "https://oauth.vk.com/authorize?" + params.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
    }

    fun parseCallback(url: String, session: Session, now: Long = System.currentTimeMillis()): Callback? {
        if (!isValid(session, now)) return null
        return try {
            val uri = URI(url)
            if (uri.scheme != "kiara-vk" || uri.rawAuthority != "auth" ||
                !uri.rawPath.isNullOrEmpty() || uri.rawFragment != null) return null
            val params = linkedMapOf<String, String>()
            for (part in (uri.rawQuery ?: return null).split("&")) {
                val pair = part.split("=", limit = 2)
                if (pair.size != 2) return null
                val key = URLDecoder.decode(pair[0], "UTF-8")
                if (params.containsKey(key)) return null
                params[key] = URLDecoder.decode(pair[1], "UTF-8")
            }
            val state = params["state"] ?: return null
            if (!MessageDigest.isEqual(state.toByteArray(), session.state.toByteArray())) return null
            val ticket = params["ticket"]
            val error = params["error"]
            if (error != null && ticket == null && error == "authorization_failed") Callback(null, error)
            else if (error == null && ticket?.matches(Regex("[a-f0-9]{64}")) == true) Callback(ticket, null)
            else null
        } catch (_: Exception) {
            null
        }
    }
}
