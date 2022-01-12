package com.asterleen.provodach


import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.koushikdutta.async.http.AsyncHttpClient
import com.koushikdutta.async.http.AsyncHttpGet
import com.koushikdutta.async.http.AsyncHttpResponse
import org.json.JSONException
import org.json.JSONObject
import java.util.*

/**
 * Created by Alex on 19.07.2016.
 *
 * Оповещение плеера *
 *
 * Класс для работы с оповещением. Предоставляет удобный интерфейс через методы update() и
 * setPlaying(boolean). Так же выполняет функцию обработчика кнопок класс и дискласс (вынести).
 *
 * Самостоятельно обновляет текущий трек с сервера.
 *
 * Использует кастомное оповещение.  */
class PlayerNotification(private val player: PlayerService, private val notificationId: String) {
    private val timer: Timer
    private val notification: Notification

    private var currentArtist: String? = ""
    private var currentTitle: String? = ""

    var isPlaying = false
        private set
    fun setPlaying(playing: Boolean): PlayerNotification {
        isPlaying = playing
        return this
    }

    fun getNotificationView() : RemoteViews {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Notification.Builder.recoverBuilder(player, notification).createContentView()
        } else {
            notification.contentView
        }
    }

    fun update(): PlayerNotification {
        if (isPlaying) {
            getNotificationView().setViewVisibility(R.id.player_pause, View.VISIBLE)
            getNotificationView().setViewVisibility(R.id.player_play, View.GONE)
            player.startForeground(NOTIFICATION_ID, notification)
        } else {
            getNotificationView().setViewVisibility(R.id.player_pause, View.GONE)
            getNotificationView().setViewVisibility(R.id.player_play, View.VISIBLE)
            player.startForeground(NOTIFICATION_ID, notification)
            player.stopForeground(false)
        }
        return this
    }

    fun remove() {
        timer.cancel()
        player.stopForeground(true)
    }

    fun setNotificationTitle(artist: String?, title: String?) {

        if (artist != currentArtist || title != currentTitle) {
            currentArtist = artist
            currentTitle = title
            getNotificationView().setTextViewText(R.id.player_title, title)
            getNotificationView().setTextViewText(R.id.player_artist_and_status, artist)
        }

    }

    companion object {
        private const val NOTIFICATION_ID = 1337
        private const val TRACK_SOURCE_URL = "https://core.waveradio.org/public/current?station=provodach"
    }

    init {
        val contentView = RemoteViews(player.application.packageName, R.layout.notification_player)

        val deleteIntent = PendingIntent.getBroadcast(
                player, 1, Intent(PlayerService.ACTION_TERMINATE), PendingIntent.FLAG_UPDATE_CURRENT)
        contentView.setOnClickPendingIntent(R.id.player_play, PendingIntent.getBroadcast(
                player, 2, Intent(PlayerService.ACTION_PLAY), PendingIntent.FLAG_UPDATE_CURRENT))
        contentView.setOnClickPendingIntent(R.id.player_pause, PendingIntent.getBroadcast(
                player, 3, Intent(PlayerService.ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT))
        contentView.setOnClickPendingIntent(R.id.player_close, deleteIntent)
        notification = NotificationCompat.Builder(player, notificationId) // .setWhen(0)
                .setOngoing(true)
                .setCustomContentView(contentView)
                .setContentTitle("Provoda.ch")
                .setSmallIcon(R.drawable.ic_play_arrow_white_36dp)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(PendingIntent.getActivity(player, 0, Intent(player, MainActivity::class.java), 0))
                .setDeleteIntent(deleteIntent)
                .build()

        timer = Timer()
        timer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                AsyncHttpClient.getDefaultInstance().executeJSONObject(
                        AsyncHttpGet(TRACK_SOURCE_URL),
                        object : AsyncHttpClient.JSONObjectCallback() {
                            override fun onCompleted(e: Exception?, source: AsyncHttpResponse?, result: JSONObject) {
                                val status = try {
                                    result.getInt("status")
                                } catch (ex: JSONException) {
                                    setNotificationTitle(R.string.trackError.toString(), null)
                                } catch (ex: NullPointerException) {
                                    setNotificationTitle(R.string.trackError.toString(), null)
                                }

                                when (status) {
                                    0 -> {
                                        // assume that everything is okay and just set the artist & title
                                        val payload = result.getJSONObject("payload")
                                        setNotificationTitle(payload.getString("artist"), payload.getString("title"))
                                    }
                                    else -> {
                                        setNotificationTitle(R.string.noTrack.toString(), null)
                                    }
                                }
                                update()
                            }
                        })
            }
        }, 0, 15000)
    }
}