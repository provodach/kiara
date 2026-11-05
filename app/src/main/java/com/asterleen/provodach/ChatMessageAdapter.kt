package com.asterleen.provodach

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationManager
import android.app.NotificationChannel
import android.os.Build
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.core.view.isVisible
import com.asterleen.provodach.ui.MessageLinkSpan
import com.asterleen.provodach.ui.SpoilerSpan
import com.asterleen.provodach.ui.UrlSpan
import java.util.*
import java.util.regex.Pattern


/**
 * Created by Alex on 11.07.2015.
 * blyalol
 */
class ChatMessageAdapter(context: Context) : ArrayAdapter<ChatMessage?>(context, R.layout.message, ArrayList<ChatMessage>() as List<ChatMessage?>) {
    internal class ViewHolder {
        var name: TextView? = null
        var datetime: TextView? = null
        var content: TextView? = null
    }

    // Activity parent;
    var listView: ListView? = null
    var inflater: LayoutInflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater

    // Наверное, регулярочки следует компилировать при создании. Так и сделаем.
    private val boldRegex: Pattern = Pattern.compile("\\[b].+?\\[/b]", Pattern.MULTILINE or Pattern.CASE_INSENSITIVE)
    private val italicRegex: Pattern = Pattern.compile("\\[i].+?\\[/i]", Pattern.MULTILINE or Pattern.CASE_INSENSITIVE)
    private val strikethroughRegex: Pattern = Pattern.compile("\\[s].+?\\[/s]", Pattern.MULTILINE or Pattern.CASE_INSENSITIVE)
    private val underlinedRegex: Pattern = Pattern.compile("\\[u].+?\\[/u]", Pattern.MULTILINE or Pattern.CASE_INSENSITIVE)
    private val appealRegex: Pattern = Pattern.compile(">>([0-9]{1,10})", Pattern.MULTILINE)
    private val quoteRegex: Pattern = Pattern.compile("^>[^>](.*)$", Pattern.MULTILINE)
    private val redRegex: Pattern = Pattern.compile("^#!(.*)$", Pattern.MULTILINE)
    private val spoilerRegex: Pattern = Pattern.compile("\\[spoiler].+?\\[/spoiler]", Pattern.MULTILINE or Pattern.CASE_INSENSITIVE)
    private val urlRegex: Pattern = Pattern.compile("(?!src=\"|href=\"|getFancy\\(\')(https?://([a-zA-Z0-9\\-.]+)/?[a-zA-Z0-9?&=.:;#/\\-_~%+]*)", Pattern.MULTILINE or Pattern.CASE_INSENSITIVE)

    // А тут мы храним ресурсы. Вот.
    private val spoilerBack: Int = context.resources.getColor(R.color.white)
    private val spoilerFont: Int = context.resources.getColor(R.color.black)
    private val urlColor: Int = context.resources.getColor(R.color.provodach_cyan)
    private val messageLinkColor: Int = context.resources.getColor(R.color.provodach_purple)
    private val quoteColor: Int = context.resources.getColor(R.color.provodach_cyan)
    private val redColor: Int = Color.parseColor("#ff0000")
    private val messageSelectionColor: Int = context.resources.getColor(R.color.provodach_cyan)

    /* Собственно, переменная, указывающая на то, следует ли показывать уведомления */
    var notify = false

    /* Менеджер оповещалок */
    private val notifyMgr: NotificationManager

    /* Действие при нажатии на оповещение */
    private val notificationIntent: PendingIntent = PendingIntent.getActivity(context, 0, Intent(getContext(), MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0))

    /* Callback на случай, если что-то происходит с сообщением. Например, пользователь открыл изображение
     * на весь экран. */
    private fun makeNotification(title: String, content: String) {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "provodach_chat"
            notifyMgr.createNotificationChannel(
                NotificationChannel(channelId, context.getString(R.string.chat), NotificationManager.IMPORTANCE_DEFAULT)
            )
            Notification.Builder(context, channelId)
        } else {
            Notification.Builder(context)
        }
        val notification: Notification = builder
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(notificationIntent)
                .setAutoCancel(true)
                .build()
        notifyMgr.notify(NOTIFICATION_ID, notification)
    }

    fun addMessage(message: ChatMessage) {
        if (notify && message.forMe) makeNotification("Сообщение от " + message.name, message.content.toString())
        Utils.runOnUiThread(Runnable { add(message) })
    }

    private fun getPositionById(id: Int): Int {
        for (i in 0 until count) {
            val message: ChatMessage? = getItem(i)
            if (message?.id === id) return getPosition(message)
        }

        // Что же будет, если ето когда-нибудь случится х)
        return -1
    }

    private fun parse(contentView: TextView?, message: ChatMessage?) {
        val parsed: SpannableStringBuilder = message!!.content
        /* Делаем [b]ЖЫРНОТУ[/b] */
        var matcher = boldRegex.matcher(parsed)
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            parsed.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            parsed.delete(end - 4, end)
            parsed.delete(start, start + 3)
            matcher.reset(parsed)
        }

        /* Создаем [i]наклонности[/i] */matcher = italicRegex.matcher(parsed)
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            parsed.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            parsed.delete(end - 4, end)
            parsed.delete(start, start + 3)
            matcher.reset(parsed)
        }

        /* [s]Перечеркиваем[/s] неважное */matcher = strikethroughRegex.matcher(parsed)
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            parsed.setSpan(StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            parsed.delete(end - 4, end)
            parsed.delete(start, start + 3)
            matcher.reset(parsed)
        }

        /* [u]Подчеркиваем[/u] важное */matcher = underlinedRegex.matcher(parsed)
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            parsed.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            parsed.delete(end - 4, end)
            parsed.delete(start, start + 3)
            matcher.reset(parsed)
        }

        /* >> выделяем посты */

        // TODO: тут надо подумать
        matcher = appealRegex.matcher(parsed)
        while (matcher.find()) {
            /* Ура! Ничего не удаляем~ */
            try {
                val id = matcher.group(1).toInt()
                parsed.setSpan(MessageLinkSpan(this, messageLinkColor, messageSelectionColor, getPositionById(id)),
                        matcher.start(), matcher.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } catch (e: NumberFormatException) {
                // мммм, ок
            }
        }

        /* >цитируем интересное */

        // TODO: тут надо подумать
        matcher = quoteRegex.matcher(parsed)
        while (matcher.find()) {
            /* Ура! Ничего не удаляем~ */
            parsed.setSpan(ForegroundColorSpan(quoteColor), matcher.start(), matcher.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        /* #!добавляем особенностей */matcher = redRegex.matcher(parsed)
        while (matcher.find()) {
            val start = matcher.start()
            parsed.setSpan(ForegroundColorSpan(redColor), matcher.start(), matcher.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            parsed.delete(start, start + 2)
            matcher.reset(parsed)
        }

        /* Скрываем [spoiler]всякое[/spoiler] */matcher = spoilerRegex.matcher(parsed)
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            parsed.setSpan(SpoilerSpan(spoilerFont, spoilerBack), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            parsed.delete(end - 10, end)
            parsed.delete(start, start + 9)
            matcher.reset(parsed)
        }

        /* Добавляем http://ссылочки */
        matcher = urlRegex.matcher(parsed)
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            parsed.setSpan(UrlSpan(context, urlColor, matcher.group(1)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            parsed.replace(start, end, "[" + matcher.group(2) + "]")
            matcher.reset(parsed)
        }

        // маленький фикс ~
        // делает ну оооооооочень много :з
        parsed.append(' ')
        message!!.prepared = true
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        /* Етот метод вызывается многократно в течении жизни приложения, но
        * для меня до сих пор остается загадкой зачем каждый раз обновлять
        * содержимое компонентов из convertView (по сути это *Layout для ListView).
        * Пока оставим как есть, но я понятия не имею что буду делать в случае, если
        * содержимое действительно требуется устанавливать наново и, как следствие,
        * парсить!

        * ..и так на каждый элемент */

        /* Итак, как ето все-таки работает. Андроид йоба-экономный до ресурсов, а особенно
        * до выделения памяти на них. Поэтому он старается как можно реже создавать новые
        * View для элементов, а вместо этого использовать старые. Именно поэтому аргумент
        * называется <i>convert</i>View. То есть там могут присутствовать старые данные,
        * которые требуется заменить на новые. Такие дела.
        *
        * Как в итоге получать доступ к ВИDам которые "не видны" остается загадкой. Пока.
        * И, да. Теперь парсим не каждый раз. */
        var convertView = convertView
        val holder: ViewHolder
        if (convertView == null) {
            /* Такое случается редко, но случается. А ViewHolder, который избавляет нас от
             * регулярного вызова findViewById действительно сильно ускоряет работу. И чем
             * больше сообщений, тем сильнее. */
            convertView = inflater.inflate(R.layout.message, parent, false)
            holder = ViewHolder()
            holder.name = convertView!!.findViewById<View>(R.id.name) as TextView?
            holder.datetime = convertView.findViewById<View>(R.id.datetime) as TextView?
            // никто не должен знать, но на самом деле это не оригинальный TextView, а его сын..!
            holder.content = convertView.findViewById<View>(R.id.content) as TextView?
            convertView.tag = holder
        } else holder = convertView.tag as ViewHolder
        val message: ChatMessage? = getItem(position)
        holder.name!!.text = (if (message?.id == 0) "" else "[" + message?.id.toString() + "] ") + message?.name
        holder.name!!.setTextColor(Color.parseColor('#' + message?.hash.toString()))
        holder.datetime!!.text = message?.time
        holder.datetime!!.isVisible = message?.timestamp != 0L

        /* Пиздец, сука. ЕГО ДЕЙСТВИТЕЛЬНО НАДО КАЖДЫЙ РАЗ ПАРСИТЬ БЛЯТЬ!!!111
        * (самое обидное, что я даже не могу узнать когда создается новый view)
        *
        * Таки разобрались. */if (!message!!.prepared) {
            /* Тратата, тратата, сообщение парсит само себя!
             * .. (уже не парсит)
             * ..мммм, снова парсит. PEEEZDOS */
            parse(holder.content, message)
        }

        holder.content?.text = message.content
        if (message.forMe) convertView.setBackgroundColor(messageSelectionColor) else convertView!!.setBackgroundColor(Color.TRANSPARENT)
        if (message.selected) {
            val view = convertView
            message.selected = false
            val selection = ValueAnimator.ofObject(ArgbEvaluator(), messageSelectionColor,
                    if (message.forMe) messageSelectionColor else Color.TRANSPARENT)
            selection.duration = 600
            selection.addUpdateListener { animation -> view.setBackgroundColor((animation.animatedValue as Int)) }
            selection.start()
        }
        return convertView
    }

    /*
    fun setMessageListener(listener: ChatMessageListener?) {
        this.listener = listener
    }
     */

    companion object {
        private const val TAG = "Airin"
        private const val NOTIFICATION_ID : Int = 31337
    }

    init {
        notifyMgr = getContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        // makePurr = prefs.getBoolean("notify", true);
    }
}
