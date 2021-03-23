package com.asterleen.provodach.ui

import android.text.TextPaint
import android.text.style.ClickableSpan
import android.view.View


/**
 * Created by Alex on 12.08.2015.
 */
class SpoilerSpan(private val font: Int, private val background: Int) : ClickableSpan() {
    var open = false
    override fun onClick(widget: View) {
        open = true
        widget.visibility = View.INVISIBLE
        widget.visibility = View.VISIBLE
    }

    override fun updateDrawState(ds: TextPaint) {
        if (open) {
            ds.color = font
            ds.bgColor = background
        } else {
            ds.color = background
            ds.bgColor = background
        }
    }
}