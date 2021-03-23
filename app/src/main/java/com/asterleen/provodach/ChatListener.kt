package com.asterleen.provodach

interface ChatListener {
    fun socketConnectionError(message: String?)
    fun socketClosed()
    fun socketRawContent(content: String)
    fun ready(isReadonly: Boolean)
    fun motdReceived(message: String?)
    fun messageSent(messageId: String, messageInternalCode: String)
    fun messageReceived(message: ChatMessage)
    fun authStateChanged(isReadonlyAvailable: ChatService.AuthState, authErrorText: String? = null)
    fun error(code: Int, text: String)
}