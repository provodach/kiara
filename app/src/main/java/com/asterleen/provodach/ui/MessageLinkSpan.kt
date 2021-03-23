package com.asterleen.provodach.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.Color
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.view.View
import android.widget.ListView
import com.asterleen.provodach.ChatMessage
import com.asterleen.provodach.ChatMessageAdapter


/**
 * Created by Alex on 12.08.2015.
 */
// DONE: допили ето
class MessageLinkSpan(private val adapter: ChatMessageAdapter, private val color: Int, private val selectionColor: Int, val id: Int) : ClickableSpan() {
    var message: ChatMessage? = null
    lateinit var selection: ValueAnimator
    override fun onClick(widget: View) {
        // хмммммммммм~
        if (message == null) return

        /* Та да~ Я все-таки разобрался с этим. Все оказалось не так сложно, но довольно запутанно.
         * На данный момент, несмотря на мое огромное желание заставить именно етот класс делать
         * всю работу, его функция немного разбросана: здесь и в методе getView адаптера.
         *
         * Все бы ничего, работает и ладно, но.. как-то это неправильно, что ли. Хотелось бы,
         * используя ООП, делать так, чтобы каждый класс полностью выполнял свою функцию, не
         * размывая ее просто потому что /иначе нельзя/. Можно. Но я поленился, каюсь.
         * Переписывать целый компонент, разбираться в его логике - немного не то чем я хочу
         * заниматься на данный момент, когда ориентируюсь на скорейший выпуск приложения.
         *
         * Оставим это в планах:
         * TODO: переделать ListView так, чтобы *Span имел бОльшие возможности управления содержимым
         */
        val listView: ListView? = adapter.listView
        listView?.smoothScrollToPosition(id)

        // при работе с ListView, getFirst(Last)VisiblePosition решает все.
        val view = listView?.getChildAt(id - listView.firstVisiblePosition)
        if (view == null) {
            // parent.setSelection(id);
            message!!.selected = true
        } else {
            selection.removeAllUpdateListeners()
            selection.addUpdateListener { animation -> view.setBackgroundColor((animation.animatedValue as Int)) }
            selection.start()
        }
    }

    override fun updateDrawState(ds: TextPaint) {
        ds.color = color
    }

    init {
        if (id != -1) {
            message = adapter.getItem(id)
            selection = ValueAnimator.ofObject(ArgbEvaluator(), color, if (message!!.forMe) selectionColor else Color.TRANSPARENT)
            selection.duration = 600
        }

        /* Сначала надо читать то, что ниже. Потом - это.
         *
         * Еще один пример грубой зависимости от родителя. Все жестко определено. messages должен
         * быть в MainActivity. Должен быть и messageAdapter. И, конечно же, в качестве context
         * нельзя передать ничего кроме MainActivity. Не знаю как с этим жить, лол.
         *
         * Пока пусть будет. */
        // parent = ((MainActivity) context).messages;

        // ммммммммм, анимашки))0))

    }
}
