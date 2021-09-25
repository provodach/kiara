package com.asterleen.provodach

import android.text.SpannableStringBuilder
import java.time.LocalDate
import java.util.*
import java.text.SimpleDateFormat

class ChatMessage(val id: Int, val timestamp: Long,
                  val name: String, val hash: String, val content: SpannableStringBuilder, val forMe: Boolean) {

    var prepared = false
    var selected = false
    var date : String = ""
    var time : String = ""
    var isServiceMessage : Boolean = false
    private var dateObject: Date? = null

    init {
        dateObject = Date(timestamp * 1000)
        date = SimpleDateFormat("d.M.y").format(dateObject)
        time = SimpleDateFormat("H:m:s").format(dateObject)
    }
}