package com.asterleen.provodach

import android.content.*
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.koushikdutta.async.http.AsyncHttpClient
import com.koushikdutta.async.http.AsyncHttpGet
import com.koushikdutta.async.http.AsyncHttpResponse
import org.json.JSONException
import org.json.JSONObject
import java.util.regex.Matcher
import java.util.regex.Pattern


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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getPreferences(Context.MODE_PRIVATE)

        val playerReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent) {
                val button: MenuItem = menu!!.findItem(R.id.appbar_play)
                when (intent.action) {
                    PlayerService.ACTION_PLAY -> {
                        button.isChecked = true
                        button.setIcon(R.drawable.ic_pause_white_36dp)
                    }
                    PlayerService.ACTION_STOP, PlayerService.ACTION_TERMINATE -> {
                        button.isChecked = false
                        button.setIcon(R.drawable.ic_play_arrow_white_36dp)
                    }
                }
            }
        }

        val actionFilter = IntentFilter()
        actionFilter.addAction(PlayerService.ACTION_PLAY)
        actionFilter.addAction(PlayerService.ACTION_STOP)
        actionFilter.addAction(PlayerService.ACTION_TERMINATE)
        registerReceiver(playerReceiver, actionFilter)

        messagesAdapter = ChatMessageAdapter(this@MainActivity)
        messages = findViewById<ListView>(R.id.messages)
        messageTextEdit = findViewById<EditText>(R.id.message)

        messagesAdapter!!.listView = messages
        messages!!.adapter = messagesAdapter

        authCode = getPref("auth_code")

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
        sendBroadcast(Intent(PlayerService.ACTION_STATE))
        this.menu = menu
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.appbar_play -> {
                if (item.isChecked) stopService(Intent(this, PlayerService::class.java)) else restartPlayer()
                return true
            }
            /*R.id.appbar_settings -> {
                startActivityForResult(Intent(this, SettingsActivity::class.java), SETTINGS_PLS)
                return true
            } */

            R.id.appbar_settings -> {
                Toast.makeText(this@MainActivity, "Code: " + getPref("auth_code"), Toast.LENGTH_SHORT).show()
            }
        }
        return super.onOptionsItemSelected(item)
    }

    fun onMessageSendButtonClick(view: View) {
        chatService?.sendMessage(messageTextEdit?.text.toString())
    }

    private fun updateTextboxState() {
        if (chatService != null) {
            val textboxPlaceholder = findViewById<LinearLayout>(R.id.chatMessagePlaceholder)
            val textboxContainer = findViewById<LinearLayout>(R.id.chatMessageBox)
            when (chatService!!.authState) {
                ChatService.AuthState.FULL -> {
                    textboxPlaceholder.visibility = View.INVISIBLE
                    textboxContainer.visibility = View.VISIBLE
                }
                ChatService.AuthState.READONLY, ChatService.AuthState.FAILED -> { // TODO: different messages when readonly & so on
                    textboxContainer.visibility = View.INVISIBLE
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
            startChatService(getPref("auth_code"))
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
                                setPref("auth_code", payload)

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

    private fun restartPlayer() {
        val intent = Intent(this, PlayerService::class.java)
        stopService(intent)
        startService(intent)
    }

    private fun getPref(key: String, default: String? = null) : String? {
        return prefs.getString("com.asterleen.provodach.prefs.$key", default)
    }

    private fun setPref(key: String, value: String) {
        with (prefs.edit()) {
            putString("com.asterleen.provodach.prefs.$key", value)
            apply()
        }
    }

    companion object {
        const val WEB_API_ENDPOINT = "https://api.nay.su/airin"
    }

}