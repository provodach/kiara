package com.asterleen.provodach.ui

import android.content.Context
import android.text.Selection
import android.text.Spannable
import android.text.method.LinkMovementMethod
import android.text.method.Touch
import android.text.style.ClickableSpan
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.TextView


/**
 * Created by Alex on 22.07.2015.
 */
/* Очень, ооооооочень большой фикс TextView. Мне кажется, что изначально следовало бы
 * наследовать абсолютно все используемые классы и переписывать их под свои нужды.
 * Странно, что еще никто не сделал какой-нибудь годный фреймворк для разработки.
 *
 * Главное - работает. Такие дела. */
class TouchTextView : androidx.appcompat.widget.AppCompatTextView {
    // ПОЧЕМУ ???!?!?!!!??!?!?!!?!?!??!!?
    var dontConsumeNonUrlClicks = false // true;
    var linkHit = false

    constructor(context: Context?) : super(context!!) {
        movementMethod = LocalLinkMovementMethod.instance
    }

    constructor(context: Context?, attrs: AttributeSet?) : super(context!!, attrs) {
        movementMethod = LocalLinkMovementMethod.instance
    }

    constructor(context: Context?, attrs: AttributeSet?, defStyle: Int) : super(context!!, attrs, defStyle) {
        movementMethod = LocalLinkMovementMethod.instance
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        linkHit = false
        val res = super.onTouchEvent(event)
        return if (dontConsumeNonUrlClicks) linkHit else res
    }

    private class LocalLinkMovementMethod : LinkMovementMethod() {
        override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
            val action = event.action
            if (action == MotionEvent.ACTION_UP ||
                    action == MotionEvent.ACTION_DOWN) {
                var x = event.x.toInt()
                var y = event.y.toInt()
                x -= widget.totalPaddingLeft
                y -= widget.totalPaddingTop
                x += widget.scrollX
                y += widget.scrollY
                val layout = widget.layout
                val line = layout.getLineForVertical(y)
                val off = layout.getOffsetForHorizontal(line, x.toFloat())
                val link = buffer.getSpans(off, off, ClickableSpan::class.java)
                return if (link.size != 0) {
                    if (action == MotionEvent.ACTION_UP) {
                        link[0].onClick(widget)
                    } else if (action == MotionEvent.ACTION_DOWN) {
                        Selection.setSelection(buffer,
                                buffer.getSpanStart(link[0]),
                                buffer.getSpanEnd(link[0]))
                    }
                    if (widget is TouchTextView) {
                        widget.linkHit = true
                    }
                    true
                } else {
                    Selection.removeSelection(buffer)
                    Touch.onTouchEvent(widget, buffer, event)
                    false
                }
            }
            return Touch.onTouchEvent(widget, buffer, event)
        }

        companion object {
            var sInstance: LocalLinkMovementMethod? = null
            val instance: LocalLinkMovementMethod?
                get() {
                    if (sInstance == null) sInstance = LocalLinkMovementMethod()
                    return sInstance
                }
        }
    }
}
