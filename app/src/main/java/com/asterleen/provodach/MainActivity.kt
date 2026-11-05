package com.asterleen.provodach


import android.content.*
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.asterleen.provodach.ui.VKAuthActivity
import com.koushikdutta.async.http.AsyncHttpClient
import com.koushikdutta.async.http.AsyncHttpGet
import com.koushikdutta.async.http.AsyncHttpResponse
import org.json.JSONException
import org.json.JSONObject
import java.util.regex.Pattern
import android.content.Intent
import android.net.Uri
import androidx.preference.PreferenceManager;


class MainActivity : AppCompatActivity() {
    private var menu: Menu? = null
    private var messages: ListView? = null
    private var messagesAdapter: ChatMessageAdapter? = null
    private var authCode: String? = null
    private var messageTextEdit: EditText? = null
    private val authUrlPattern: Pattern = Pattern.compile("code=([a-z0-9]{32})")
    private lateinit var prefs: SharedPreferences

    var chatService : ChatService? = null

    enum class AuthWays {
        TELEGRAM,
        VK
    }

    private val chatConnection = object : ServiceConnection, ChatListener {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as ChatService.ChatBinder
            chatService = binder.getService()
            chatService!!.listener = this
            chatService!!.authCode = authCode
            chatService!!.setName(prefs.getString("user_name", "")!!)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            chatService = null
        }

        override fun socketConnectionError(message: String?) {}
        override fun socketClosed() {}
        override fun socketRawContent(content: String) {}
        override fun ready(isReadonly: Boolean) {}
        override fun motdReceived(message: String?) {}
        override fun messageSent(messageId: String, messageInternalCode: String) {
            runOnUiThread {
                findViewById<EditText>(R.id.message).setText("") // TODO: clear()?
            }
        }
        override fun messageReceived(message: ChatMessage) {
            runOnUiThread {
                messagesAdapter?.add(message)
            }
        }

        override fun authStateChanged(isReadonlyAvailable: ChatService.AuthState, authErrorText: String?) {
            runOnUiThread {
                updateTextboxState()
            }
        }
        override fun error(code: Int, text: String) {}
    }

    private val vkAuthActivity = object : VKAuthActivity() {
        override fun authCodeReady(url: String) {
            authBy(AuthWays.VK, extractAuthCodeFromURL(url))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Utils.applySystemInsets(this)

        prefs = PreferenceManager.getDefaultSharedPreferences(this)


        val playerReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent) {
                val playerButton = menu?.findItem(R.id.appbar_play) ?: return
                when (intent.action) {
                    PlayerService.ACTION_PLAY -> {
                        playerButton.isChecked = true
                        playerButton.setIcon(R.drawable.ic_pause_white_36dp)
                    }
                    PlayerService.ACTION_STOP, PlayerService.ACTION_TERMINATE -> {
                        playerButton.isChecked = false
                        playerButton.setIcon(R.drawable.ic_play_arrow_white_36dp)
                    }
                }
            }
        }

        val playerActionFilter = IntentFilter()
        playerActionFilter.addAction(PlayerService.ACTION_PLAY)
        playerActionFilter.addAction(PlayerService.ACTION_STOP)
        playerActionFilter.addAction(PlayerService.ACTION_TERMINATE)
        Utils.registerPrivateReceiver(this, playerReceiver, playerActionFilter)


        val prefsChangeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent) {
                when (intent.action) {
                    SettingsActivity.ACTION_UPDATE_HLS -> {
                        prefs.edit().putBoolean("use_hls", intent.extras?.getBoolean("use_hls") == true).apply()

                        val playerButton = menu?.findItem(R.id.appbar_play) ?: return
                        if (playerButton.isChecked)
                            restartPlayer(if (intent.extras?.getBoolean("use_hls") == true) "hls" else "stream")
                    }
                    SettingsActivity.ACTION_UPDATE_NAME -> {
                        prefs.edit().putString("user_name", intent.extras?.getString("user_name")).apply()
                        chatService?.setName(prefs.getString("user_name", "")!!);
                    }
                }
            }
        }

        val prefsActionFilter = IntentFilter()
        prefsActionFilter.addAction(SettingsActivity.ACTION_UPDATE_HLS)
        prefsActionFilter.addAction(SettingsActivity.ACTION_UPDATE_NAME)
        Utils.registerPrivateReceiver(this, prefsChangeReceiver, prefsActionFilter)

        messagesAdapter = ChatMessageAdapter(this@MainActivity)
        messages = findViewById<ListView>(R.id.messages)
        messageTextEdit = findViewById<EditText>(R.id.message)

        messagesAdapter!!.listView = messages
        messages!!.adapter = messagesAdapter

        authCode = prefs.getString("auth_code", null)

        if (intent != null)
            processAuthIntent(intent)
        else
            startChatService(authCode)
    }


    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null)
            processAuthIntent(intent)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.chat, menu)
        this.menu = menu
        sendBroadcast(Intent(PlayerService.ACTION_STATE).setPackage(packageName))
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.appbar_play -> {
                if (item.isChecked) startService(Intent(this, PlayerService::class.java).setAction(PlayerService.ACTION_STOP)) else
                    restartPlayer(if (prefs.getBoolean("use_hls", false)) "hls" else "stream")
                return true
            }

            R.id.appbar_settings -> {
                openSettings()
                return true
            }

            /*R.id.appbar_settings -> {
                Toast.makeText(this@MainActivity, "Code: " + getPref("auth_code"), Toast.LENGTH_SHORT).show()
            }*/
        }
        return super.onOptionsItemSelected(item)
    }

    fun onMessageSendButtonClick(view: View) {
        chatService?.sendMessage(messageTextEdit?.text.toString())
    }

    fun onChatMessagePlaceholderClick(view: View) {
        val url = "https://t.me/provodach_bot"
        val i = Intent(Intent.ACTION_VIEW)
        i.data = Uri.parse(url)
        startActivity(i)
    }

    private fun openSettings() {
        val intent = Intent(this@MainActivity, SettingsActivity::class.java)
        startActivity(intent)
    }

    private fun updateTextboxState() {
        if (chatService != null) {
            val textboxPlaceholder = findViewById<LinearLayout>(R.id.chatMessagePlaceholder)
            val textboxContainer = findViewById<LinearLayout>(R.id.chatMessageBox)
            when (chatService!!.authState) {
                ChatService.AuthState.NONE -> Unit
                ChatService.AuthState.FULL -> {
                    textboxPlaceholder.visibility = View.GONE
                    textboxContainer.visibility = View.VISIBLE
                }
                ChatService.AuthState.READONLY, ChatService.AuthState.FAILED -> { // TODO: different messages when readonly & so on
                    textboxContainer.visibility = View.GONE
                    textboxPlaceholder.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun processAuthIntent(intent: Intent) {
        val intentURI = if (intent.data != null) intent.data.toString() else null

        if (intentURI != null && intentURI.isNotEmpty()) {
            authBy(AuthWays.TELEGRAM, extractAuthCodeFromURL(intentURI))
        } else {
            startChatService(prefs.getString("auth_code", null))
        }
    }

    private fun extractAuthCodeFromURL(url: String) : String? {
        val matcher = authUrlPattern.matcher(url)

        return if (matcher.find()) matcher.group(1) else null
    }

    private fun authBy(way: AuthWays, nonce: String?) {
        val authRoutePart : String = when (way) {
            AuthWays.TELEGRAM -> "authgram"
            AuthWays.VK -> "auth"
        }

        AsyncHttpClient.getDefaultInstance().executeJSONObject(
                AsyncHttpGet("$WEB_API_ENDPOINT/$authRoutePart/external?code=$nonce"),
                object : AsyncHttpClient.JSONObjectCallback() {
                    override fun onCompleted(e: Exception?, source: AsyncHttpResponse?, result: JSONObject) {
                        val status = try {
                            result.getInt("status")
                        } catch (ex: JSONException) {
                            // ERROR
                        } catch (ex: NullPointerException) {
                            // ERROR
                        }

                        val payload = result.getString("payload")
                        when (status) {
                            0 -> {
                                authCode = payload
                                setPrefString("auth_code", payload)

                                startChatService(authCode)
                            }
                            else -> {
                                runOnUiThread { AlertDialog.Builder(this@MainActivity)
                                        .setTitle(R.string.authError)
                                        .setMessage(getString(R.string.authApiError) + ":\n#$status $payload")
                                        .show()
                                }
                            }
                        }
                    }
                })
    }

    private fun startChatService(authCode: String?) {
        if (chatService == null) {
            val intent = Intent(this, ChatService::class.java)
            Log.d("ChatSvc", "Attempting to start up the Chat service...")
            bindService(intent, chatConnection, Context.BIND_AUTO_CREATE)
        } else {
            if (authCode != null) {
                chatService!!.reauth(authCode)
            } else {
                Log.w("KiaraChatService", "Useless call of startChatService, plz check that condition")
            }
        }
    }

    private fun startPlayer(streamType: String = "stream") {
        val intent = Intent(this, PlayerService::class.java)
        val b = Bundle()
        b.putString("stream", streamType)
        intent.putExtras(b)

        androidx.core.content.ContextCompat.startForegroundService(this, intent)
    }

    private fun restartPlayer(streamType: String = "stream") {
        startPlayer(streamType)
    }

    private fun setPrefString(key: String, value: String) {
        with (prefs.edit()) {
            putString(key, value)
            apply()
        }
    }

    companion object {
        const val WEB_API_ENDPOINT = "https://api.nay.su/airin"
    }

}