package com.asterleen.provodach

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.text.SpannableStringBuilder
import android.util.Log
import com.koushikdutta.async.callback.CompletedCallback
import com.koushikdutta.async.http.AsyncHttpClient
import com.koushikdutta.async.http.AsyncHttpClient.WebSocketConnectCallback
import com.koushikdutta.async.http.WebSocket
import java.util.concurrent.atomic.AtomicInteger


class ChatService (): Service() {

    enum class AuthState {
        NONE,
        READONLY,
        FULL,
        FAILED
    }

    inner class ChatBinder : Binder() {
        fun getService(): ChatService = this@ChatService
    }

    private val binder = ChatBinder()

    var listener: ChatListener? = null
    var authCode: String? = null
    var userName: String = ""
    var authState: AuthState = AuthState.NONE
    private var myMessagesIds: ArrayList<String> = ArrayList<String>()
    private var socket : WebSocket? = null
    private val connectionGeneration = AtomicInteger()
    private var historyRequested = false
    private var lastMessageCode : String = ""

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onCreate() {
        Log.d(TAG, "Creating Chat service")
        super.onCreate()
        openConnection()
    }

    private fun openConnection(readonly: Boolean = false) {
        val generation = connectionGeneration.incrementAndGet()
        val client = AsyncHttpClient.getDefaultInstance()
        client.server.post {
            if (generation != connectionGeneration.get()) return@post
            val previous = socket
            socket = null
            previous?.close()
            updateAuthState(AuthState.NONE)
            client.websocket(CHAT_SERVER_ADDRESS, "mew", WebSocketConnectCallback { ex, webSocket ->
            if (generation != connectionGeneration.get()) {
                webSocket?.close()
                return@WebSocketConnectCallback
            }
            if (ex != null) {
                Log.w(TAG, "Chat connection failed")
                updateAuthState(AuthState.FAILED)
                listener?.socketConnectionError("Chat connection failed")
                return@WebSocketConnectCallback
            }

            Log.i(TAG, "Connected")

            socket = webSocket
            webSocket.stringCallback = WebSocket.StringCallback { s ->
                if (generation != connectionGeneration.get()) return@StringCallback
                listener?.socketRawContent(s)

                val cmd = s.split(" ").toTypedArray()
                val fullText = s.substring(s.indexOf('#') + 1)
                when (cmd[0]) {
                    "INIT" -> {
                        socketSend("LEVEL $AIRIN_API_LEVEL")
                        socketSend("CONNECT " + (if (readonly || authCode.isNullOrEmpty()) "READONLY" else authCode) + " #Kiara/" + CLIENT_VERSION)
                    }

                    "AUTH" -> {
                        when (cmd[1]) {
                            "OK", "READONLY" -> {
                                if (!historyRequested) {
                                    socketSend("LOG 20 ASC")
                                    historyRequested = true
                                }

                                if (cmd[1] == "READONLY") {
                                    updateAuthState(AuthState.READONLY)
                                } else {
                                    updateAuthState(AuthState.FULL)
                                    setName(userName)
                                }
                            }
                            "FAIL" -> {
                                if (readonly) {
                                    updateAuthState(AuthState.FAILED, fullText)
                                } else {
                                    // CONNECT is accepted only once on each WebSocket.
                                    openConnection(readonly = true)
                                }

                            }
                        }
                    }

                    "NUS" -> {
                        socketSend("SUS")
                    }

                    //    0      1        2          3        4    fullText
                    // LOGCON 122263 1612543588 silentchan fadd74 #Привет снова, есть живые?
                    "CONTENT", "LOGCON", "SERVICE" -> {
                        val message: ChatMessage =
                            if (cmd[0] == "SERVICE") {
                                ChatMessage(
                                    id = 0,
                                    timestamp = 0L,
                                    name = "*AirinService*",
                                    hash = serviceCodeToColor(cmd[1]),
                                    content = SpannableStringBuilder(fullText),
                                    forMe = false
                                )
                            } else {
                                ChatMessage(
                                    id = cmd[1].toInt(),
                                    timestamp = cmd[2].toLong(),
                                    name = cmd[3],
                                    hash = cmd[4],
                                    content = SpannableStringBuilder(fullText),
                                    forMe = false
                                )
                            }

                        listener?.messageReceived(message)
                    }

                    // CONREC uydrgdsjtxjiferdhopq 122542
                    "CONREC" -> {
                        if (cmd[1] == lastMessageCode) {
                            addMyMessage(cmd[2])
                            listener?.messageSent(cmd[2], cmd[1])
                        }
                    }
                }
            }
            webSocket.closedCallback = CompletedCallback {
                if (generation == connectionGeneration.get()) {
                    socket = null
                    updateAuthState(AuthState.FAILED)
                    listener?.socketClosed()
                }
            }
            })
        }
    }

    private fun serviceCodeToColor(code: String): String {
        return when (code) {
            "INFO" -> "4ac9ff"
            "WARNING" -> "ffff4a"
            "ERROR" -> "ff051e"
            else -> "f5f5f5"
        }
    }

    private fun updateAuthState(state: AuthState, comment: String? = null) {
        authState = state
        listener?.authStateChanged(state, comment)
    }

    private fun addMyMessage(messageId: String) {
        if (myMessagesIds.size >= MAX_MY_MESSAGES_QUEUE) {
            myMessagesIds.removeFirst()
        }

        myMessagesIds.add(messageId)
    }

    private fun socketSend(data: String) {
        if (socket != null && socket!!.isOpen) {
            socket!!.send(data)
            Log.d(TAG, "Sent chat command: " + data.substringBefore(' '))
        }
    }

    override fun onDestroy() {
        connectionGeneration.incrementAndGet()
        AsyncHttpClient.getDefaultInstance().server.post {
            socket?.close()
            socket = null
        }
        super.onDestroy()
        Log.d(TAG, "Socket service stopped")
    }

    fun setName(name: String = "") {
        userName = name

        if (authState == AuthState.FULL)
            socketSend("IAM #$name")
    }

    fun sendMessage(message: String) : Boolean {
        lastMessageCode = Utils.randomString(32)
        return if (authState == AuthState.FULL) {
            socketSend("CONTENT $lastMessageCode #$message")
            true
        } else
            false
    }

    fun reauth(authCode: String) {
        this.authCode = authCode
        openConnection()
    }

    companion object {
        private const val CHAT_SERVER_ADDRESS = "wss://airin.nay.su"
        private const val TAG = "ChatService"
        private const val AIRIN_API_LEVEL = 3
        private const val CLIENT_VERSION = "0.1"
        private const val MAX_MY_MESSAGES_QUEUE = 128
    }
}