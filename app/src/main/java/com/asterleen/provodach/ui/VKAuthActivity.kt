package com.asterleen.provodach.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.asterleen.provodach.MainActivity
import com.asterleen.provodach.R
import com.asterleen.provodach.Utils

/** Uses VK's SDK_AUTH transport when installed; otherwise the user's external browser. */
class VKAuthActivity : AppCompatActivity() {
    private lateinit var model: VkAuthViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vk_auth)
        Utils.applySystemInsets(this)
        title = getString(R.string.auth_via_vk)
        model = ViewModelProvider(this).get(VkAuthViewModel::class.java)
        findViewById<Button>(R.id.vk_auth_cancel).setOnClickListener {
            model.cancel()
            finish()
        }
        findViewById<Button>(R.id.vk_auth_retry).setOnClickListener {
            model.start()
        }
        findViewById<Button>(R.id.vk_auth_browser).setOnClickListener { openBrowser() }
        model.state.observe(this, Observer { state ->
            findViewById<TextView>(R.id.vk_auth_message).setText(state.message)
            findViewById<ProgressBar>(R.id.vk_auth_progress).visibility =
                if (state.phase == VkAuthViewModel.Phase.LOADING) View.VISIBLE else View.GONE
            findViewById<Button>(R.id.vk_auth_retry).visibility =
                if (state.phase == VkAuthViewModel.Phase.ERROR) View.VISIBLE else View.GONE
            findViewById<Button>(R.id.vk_auth_browser).visibility =
                if (state.phase == VkAuthViewModel.Phase.WAITING) View.VISIBLE else View.GONE
            when (state.phase) {
                VkAuthViewModel.Phase.LAUNCH -> {
                    // Native VK login is temporarily disabled; always use the default browser.
                    openBrowser()
                }
                VkAuthViewModel.Phase.SUCCESS -> {
                    startActivity(Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    finish()
                }
                else -> Unit
            }
        })
        if (intent.action == ACTION_CALLBACK) {
            intent.dataString?.let(model::receiveCallback)
        } else {
            model.start()
        }
    }

    override fun onNewIntent(newIntent: Intent?) {
        super.onNewIntent(newIntent)
        if (newIntent == null) return
        intent = newIntent
        if (newIntent.action == ACTION_CALLBACK) newIntent.dataString?.let(model::receiveCallback)
    }

    private fun openVkApp(): Boolean {
        // Same public transport and extras used by VKCOM/vk-android-sdk's VKAuthManager/VKAuthParams.
        val request = Intent("com.vkontakte.android.action.SDK_AUTH").apply {
            setPackage(VK_PACKAGE)
            putExtra("client_id", API_VK_ID.toInt())
            putExtra("revoke", false)
            putExtra("scope", "")
            putExtra("redirect_url", "https://oauth.vk.com/blank.html")
        }
        val target = packageManager.resolveActivity(request, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
            ?: return false
        if (target.packageName != VK_PACKAGE || !target.enabled || !target.exported) return false
        request.component = ComponentName(target.packageName, target.name)
        return try {
            startActivityForResult(request, VK_REQUEST_CODE)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun openBrowser() {
        val pending = model.session ?: return
        if (!VkAuthProtocol.isValid(pending)) {
            model.cancel()
            model.fail(R.string.vk_auth_expired)
            return
        }
        model.transportLaunched()
        // Resolve a generic HTTPS URL so VK's own app-link handler cannot capture browser fallback.
        val generic = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val resolved = packageManager.resolveActivity(generic, PackageManager.MATCH_DEFAULT_ONLY)
        val browserPackage = resolved?.activityInfo?.packageName?.takeUnless { it == "android" }
        val tab = CustomTabsIntent.Builder().build()
        if (browserPackage != null) tab.intent.setPackage(browserPackage)
        try {
            tab.launchUrl(this, Uri.parse(VkAuthProtocol.authorizationUrl(pending)))
        } catch (_: ActivityNotFoundException) {
            model.fail(R.string.vk_auth_no_browser)
        } catch (_: SecurityException) {
            model.fail(R.string.vk_auth_no_browser)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != VK_REQUEST_CODE) return
        val token = data?.getStringExtra("access_token")
        when {
            resultCode == Activity.RESULT_OK && !token.isNullOrBlank() -> model.receiveNativeToken(token)
            data?.getStringExtra("error") == "access_denied" || data == null -> {
                model.cancel()
                finish()
            }
            else -> openBrowser()
        }
    }

    override fun onBackPressed() {
        model.cancel()
        super.onBackPressed()
    }

    companion object {
        const val API_ADDR = VkAuthProtocol.API_ADDR
        const val API_VK_ID = VkAuthProtocol.API_VK_ID
        internal const val ACTION_CALLBACK = "com.asterleen.provodach.VK_AUTH_CALLBACK"
        private const val VK_PACKAGE = "com.vkontakte.android"
        private const val VK_REQUEST_CODE = 282
    }
}
