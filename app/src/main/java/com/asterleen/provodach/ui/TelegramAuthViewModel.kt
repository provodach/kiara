package com.asterleen.provodach.ui

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import com.asterleen.provodach.MainActivity
import com.asterleen.provodach.R
import com.koushikdutta.async.future.Future
import com.koushikdutta.async.http.AsyncHttpClient
import com.koushikdutta.async.http.AsyncHttpGet
import com.koushikdutta.async.http.AsyncHttpResponse
import org.json.JSONObject

/** Owns the one-time exchange across Activity recreation. Never logs codes or tokens. */
internal class TelegramAuthViewModel(application: Application) : AndroidViewModel(application) {
    data class Result(val token: String? = null, val error: String? = null)
    val result = MutableLiveData<Result>()
    private val handledCodes = mutableSetOf<String>()
    private val handler = Handler(Looper.getMainLooper())
    private var request: Future<JSONObject>? = null
    private var cleared = false

    fun exchange(code: String) {
        if (!code.matches(Regex("[a-f0-9]{32}")) || request != null || !handledCodes.add(code)) return
        val uri = Uri.parse("${MainActivity.WEB_API_ENDPOINT}/authgram/external").buildUpon()
            .appendQueryParameter("code", code).build()
        request = AsyncHttpClient.getDefaultInstance().executeJSONObject(
            AsyncHttpGet(uri.toString()).apply { setTimeout(15000) },
            object : AsyncHttpClient.JSONObjectCallback() {
                override fun onCompleted(e: Exception?, source: AsyncHttpResponse?, json: JSONObject?) {
                    handler.post {
                        if (cleared) return@post
                        request = null
                        val status = json?.optInt("status", -1) ?: -1
                        val payload = json?.optString("payload").orEmpty()
                        if (status == 0 && VkAuthProtocol.isValidAuthToken(payload)) {
                            PreferenceManager.getDefaultSharedPreferences(getApplication())
                                .edit().putString("auth_code", payload).apply()
                            result.value = Result(token = payload)
                        } else {
                            val error = if (status != 0 && json != null) "#$status $payload"
                                else getApplication<Application>().getString(R.string.connectionErrorMessage)
                            result.value = Result(error = error)
                        }
                    }
                }
            })
    }

    fun consumeResult() { result.value = Result() }

    override fun onCleared() {
        cleared = true
        request?.cancel()
        handler.removeCallbacksAndMessages(null)
    }
}
