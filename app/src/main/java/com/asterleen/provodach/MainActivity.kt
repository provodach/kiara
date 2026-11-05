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
import com.asterleen.provodach.ui.TelegramAuthViewModel
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import android.content.Intent
import android.net.Uri
import androidx.preference.PreferenceManager;


class MainActivity : AppCompatActivity() {
    private var menu: Menu? = null
    private var messages: ListView? = null
    private var messagesAdapter: ChatMessageAdapter? = null
    private var authCode: String? = null
    private var messageTextEdit: EditText? = null
    private lateinit var prefs: SharedPreferences
    private lateinit var telegramAuth: TelegramAuthViewModel

    var chatService : ChatService? = null

    private val chatConnection = object : ServiceConnection, ChatListener {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as ChatService.ChatBinder
            val service = binder.getService()
            chatService = service
            service.listener = this
            service.setName(prefs.getString("user_name", "")!!)
            val token = authCode
            if (token != null && (service.authCode != token ||
                    service.authState == ChatService.AuthState.READONLY ||
                    service.authState == ChatService.AuthState.FAILED)) {
                service.reauth(token)
            }
            // AUTH may have arrived before this Activity attached its listener.
            updateTextboxState()
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
        Utils.applySystemInsets(this)
        supportActionBar?.apply {
            setDisplayShowTitleEnabled(false)
            setCustomView(R.layout.actionbar_logo)
            setDisplayShowCustomEnabled(true)
        }

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
        telegramAuth = ViewModelProvider(this).get(TelegramAuthViewModel::class.java)
        telegramAuth.result.observe(this, Observer { result ->
            when {
                result.token != null -> {
                    authCode = result.token
                    startChatService(authCode)
                }
                result.error != null -> AlertDialog.Builder(this)
                    .setTitle(R.string.authError)
                    .setMessage(getString(R.string.authApiError) + ":\n" + result.error)
                    .show()
            }
            if (result.token != null || result.error != null) telegramAuth.consumeResult()
        })

        if (intent != null)
            processAuthIntent(intent)
        else
            startChatService(authCode)
    }


    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            this.intent = intent
            processAuthIntent(intent)
        }
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
        AlertDialog.Builder(this)
            .setTitle(R.string.auth)
            .setItems(arrayOf(getString(R.string.auth_via_vk), getString(R.string.auth_via_telegram))) { _, which ->
                if (which == 0) {
                    startActivity(Intent(this, VKAuthActivity::class.java))
                } else {
                    val botUri = Uri.parse("https://t.me/provodach_bot").buildUpon()
                        .appendQueryParameter("text", "/passme")
                        .build()
                    startActivity(Intent(Intent.ACTION_VIEW, botUri))
                }
            }
            .show()
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
                ChatService.AuthState.NONE -> {
                    textboxContainer.visibility = View.GONE
                    textboxPlaceholder.visibility = View.VISIBLE
                }
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
        val uri = intent.data
        if (uri != null) {
            val isTelegramCallback = (uri.scheme == "telegram-auth" && uri.host == "provodach" &&
                uri.path.isNullOrEmpty()) || (uri.scheme == "https" && uri.host == "api.nay.su" &&
                uri.path == "/airin/authgram")
            if (isTelegramCallback) {
                val code = uri.getQueryParameter("code")
                // Consume the delivery before starting an asynchronous, one-time exchange.
                intent.data = null
                if (code?.matches(Regex("[a-f0-9]{32}")) == true) telegramAuth.exchange(code)
            }
        } else {
            authCode = prefs.getString("auth_code", null)
            startChatService(authCode)
        }
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

    companion object {
        const val WEB_API_ENDPOINT = "https://api.nay.su/airin"
    }

}