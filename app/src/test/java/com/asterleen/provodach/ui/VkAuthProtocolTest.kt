package com.asterleen.provodach.ui

import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class VkAuthProtocolTest {
    private val now = 1_700_000_000_000L
    private val session = VkAuthProtocol.Session("a".repeat(32), "b".repeat(64), now)
    private val ticket = "c".repeat(64)
    private val callback = "kiara-vk://auth?state=${session.state}&ticket=$ticket"

    @Test fun browserCallbackIsAcceptedOnlyForPendingSession() {
        assertEquals(ticket, VkAuthProtocol.parseCallback(callback, session, now)?.ticket)
        assertNull(VkAuthProtocol.parseCallback(callback, session.copy(state = "d".repeat(32)), now))
    }

    @Test fun callbackRejectsUnexpectedTargetsAndCredentials() {
        listOf(
            callback.replace("auth?", "auth.evil?"),
            callback.replace("auth?", "auth/path?"),
            callback.replace("kiara-vk:", "https:"),
            callback.replace("auth?", "user@auth?"),
            "$callback#fragment",
            "$callback&state=${session.state}",
            "$callback&error=authorization_failed",
            "kiara-vk://auth?state=${session.state}&access_token=secret"
        ).forEach { assertNull(it, VkAuthProtocol.parseCallback(it, session, now)) }
    }

    @Test fun expiredFutureAndMalformedSessionsAreRejected() {
        assertNull(VkAuthProtocol.parseCallback(callback, session, now + VkAuthProtocol.SESSION_TTL_MS))
        assertNull(VkAuthProtocol.parseCallback(callback, session, now - 1))
        assertFalse(VkAuthProtocol.isValid(session.copy(verifier = ""), now))
        assertFalse(VkAuthProtocol.isValid(session.copy(state = ""), now))
    }

    @Test fun errorsRequireTheCorrectStateAndContainNoTicket() {
        val error = "kiara-vk://auth?state=${session.state}&error=authorization_failed"
        assertEquals("authorization_failed", VkAuthProtocol.parseCallback(error, session, now)?.error)
        assertNull(VkAuthProtocol.parseCallback(error.replace(session.state, "d".repeat(32)), session, now))
    }

    @Test fun browserRequestUsesServerCallbackAndNeverSendsPrivateVerifier() {
        val url = VkAuthProtocol.authorizationUrl(session)
        val uri = URI(url)
        assertEquals("https", uri.scheme)
        assertEquals("oauth.vk.com", uri.host)
        assertTrue(uri.rawQuery.contains("client_id=5216648"))
        assertTrue(uri.rawQuery.contains("response_type=code"))
        assertTrue(url.contains(VkAuthProtocol.challenge(session.verifier)))
        assertFalse(url.contains(session.verifier))
        assertTrue(url.contains("https%3A%2F%2Fapi.nay.su%2Fairin%2Fauth%2Fexternal"))
    }

    @Test fun challengeMatchesServerSha256Contract() {
        assertEquals(
            "ffe054fe7ae0cb6dc65c3af9b61d5209f439851db43d0ba5997337df154668eb",
            VkAuthProtocol.challenge("a".repeat(64))
        )
    }

    @Test fun realAirinTokenFormatIsAcceptedWithoutChangingCase() {
        assertTrue(VkAuthProtocol.isValidAuthToken("Az09".repeat(16)))
        assertTrue(VkAuthProtocol.isValidAuthToken("z".repeat(64)))
        assertTrue(VkAuthProtocol.isValidAuthToken("0".repeat(64)))
    }

    @Test fun malformedAndPreviouslyAssumedTokenFormatsAreRejected() {
        listOf("", "a".repeat(32), "a".repeat(63), "a".repeat(65),
            "a".repeat(63) + "-", "a".repeat(63) + "\n").forEach {
            assertFalse(VkAuthProtocol.isValidAuthToken(it))
        }
    }

    @Test fun sessionsUseFreshUnpredictableValues() {
        val first = VkAuthProtocol.newSession(now)
        val second = VkAuthProtocol.newSession(now)
        assertTrue(VkAuthProtocol.isValid(first, now))
        assertNotEquals(first.state, second.state)
        assertNotEquals(first.verifier, second.verifier)
    }
}
