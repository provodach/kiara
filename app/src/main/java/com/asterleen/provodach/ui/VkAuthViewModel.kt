package com.asterleen.provodach.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import com.asterleen.provodach.R
import com.koushikdutta.async.future.Future
import com.koushikdutta.async.http.AsyncHttpClient
import com.koushikdutta.async.http.AsyncHttpPost
import com.koushikdutta.async.http.AsyncHttpResponse
import com.koushikdutta.async.http.Multimap
import com.koushikdutta.async.http.body.UrlEncodedFormBody
import org.json.JSONObject

internal class VkAuthViewModel(application: Application) : AndroidViewModel(application) {
    enum class Phase { IDLE, LOADING, LAUNCH, WAITING, SUCCESS, ERROR }
    data class State(val phase: Phase, val message: Int = R.string.vk_auth_waiting)

    val state = MutableLiveData<State>(State(Phase.IDLE))
    private val store = VkAuthSessionStore(application)
    private val handler = Handler(Looper.getMainLooper())
    private var request: Future<JSONObject>? = null
    private var cleared = false
    private var requestVersion = 0
    var session = store.read()
        private set

    fun start() {
        if (state.value?.phase != Phase.IDLE && state.value?.phase != Phase.ERROR) return
        if (session != null) {
            state.value = State(Phase.WAITING)
            return
        }
        state.value = State(Phase.LOADING, R.string.vk_auth_connecting)
        post(mapOf("action" to "capabilities")) { json ->
            if (json?.optInt("status", -1) != 0 ||
                json.optJSONObject("payload")?.optString("protocol") != VkAuthProtocol.PROTOCOL
            ) {
                state.value = State(Phase.ERROR, if (json == null) R.string.connectionErrorMessage else R.string.vk_auth_server_update)
            } else {
                session = VkAuthProtocol.newSession().also(store::save)
                state.value = State(Phase.LAUNCH)
            }
        }
    }

    fun transportLaunched() { state.value = State(Phase.WAITING) }

    fun fail(message: Int = R.string.vkErrorMessage) {
        state.value = State(Phase.ERROR, message)
    }

    fun receiveCallback(url: String) {
        if (state.value?.phase == Phase.LOADING || state.value?.phase == Phase.SUCCESS) return
        val pending = session ?: return
        val callback = VkAuthProtocol.parseCallback(url, pending) ?: return
        if (callback.error != null) {
            store.clear()
            session = null
            fail()
            return
        }
        exchange(mapOf("action" to "redeem", "ticket" to callback.ticket!!, "verifier" to pending.verifier))
    }

    fun receiveNativeToken(token: String) {
        if (session?.let { VkAuthProtocol.isValid(it) } != true || token.isBlank() || token.length > 8192) {
            fail()
            return
        }
        exchange(mapOf("action" to "native", "access_token" to token))
    }

    private fun exchange(params: Map<String, String>) {
        state.value = State(Phase.LOADING, R.string.vk_auth_connecting)
        post(params) { json ->
            val token = json?.optString("payload")?.takeIf {
                json.optInt("status", -1) == 0 && VkAuthProtocol.isValidAuthToken(it)
            }
            store.clear()
            session = null
            if (token == null) {
                fail(if (json == null) R.string.connectionErrorMessage else R.string.vkErrorMessage)
            } else {
                PreferenceManager.getDefaultSharedPreferences(getApplication())
                    .edit().putString("auth_code", token).apply()
                state.value = State(Phase.SUCCESS)
            }
        }
    }

    private fun post(params: Map<String, String>, completed: (JSONObject?) -> Unit) {
        val version = ++requestVersion
        val values = Multimap()
        params.forEach { (key, value) -> values.add(key, value) }
        val post = AsyncHttpPost(VkAuthProtocol.API_ADDR).apply {
            setTimeout(15000)
            setBody(UrlEncodedFormBody(values))
        }
        request = AsyncHttpClient.getDefaultInstance().executeJSONObject(
            post, object : AsyncHttpClient.JSONObjectCallback() {
                override fun onCompleted(e: Exception?, source: AsyncHttpResponse?, result: JSONObject?) {
                    handler.post {
                        if (!cleared && version == requestVersion) completed(if (e == null) result else null)
                    }
                }
            }
        )
    }

    fun cancel() {
        requestVersion++
        request?.cancel()
        store.clear()
        session = null
    }

    override fun onCleared() {
        cleared = true
        request?.cancel()
        handler.removeCallbacksAndMessages(null)
        super.onCleared()
    }
}
