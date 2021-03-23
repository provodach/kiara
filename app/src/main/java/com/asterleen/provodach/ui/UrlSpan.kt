package com.asterleen.provodach.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.view.View


/**
 * Created by Alex on 12.08.2015.
 */
class UrlSpan(private val context: Context, val color: Int, private val url: String) : ClickableSpan() {
    override fun onClick(widget: View) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    override fun updateDrawState(ds: TextPaint) {
        ds.color = color
    }
}