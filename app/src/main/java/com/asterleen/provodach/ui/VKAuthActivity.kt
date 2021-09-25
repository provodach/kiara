package com.asterleen.provodach.ui

import android.app.Activity

import android.os.Bundle
import android.util.Log
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import com.koushikdutta.async.http.AsyncHttpClient.JSONObjectCallback



open class VKAuthActivity : AppCompatActivity() {
    private var view: WebView? = null
    private var payloadCallback: JSONObjectCallback? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setResult(Activity.RESULT_CANCELED)

        view = WebView(this)
        setContentView(view)
        view!!.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {

            }

            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                Log.d(TAG, "Location: $url")
                if (url.startsWith("$API_ADDR?error=access_denied&error_reason=user_denied")) {
                    finish()
                    return true
                } else if (url.startsWith("$API_ADDR?code=")) {
                    authCodeReady(url)
                    return true
                }
                return false
            }
        }
        reload()
    }

    override fun onDestroy() {
        CookieManager.getInstance().removeAllCookie()
        super.onDestroy()
    }

    open fun authCodeReady(url: String) {} // to be overridden

    private fun reload() {
        view!!.loadUrl("https://oauth.vk.com/authorize?client_id=$API_VK_ID&display=page&redirect_uri=$API_ADDR&response_type=code")
    }

    companion object {
        private const val TAG = "VKAuth"
        private const val API_ADDR = "https://api.nyan.pw/airin/auth/external"
        private const val API_VK_ID = "5216648"
    }
}
