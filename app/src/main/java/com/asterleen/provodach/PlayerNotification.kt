package com.asterleen.provodach


import android.app.Notification
import androidx.core.app.NotificationCompat

import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.MediaMetadataCompat
import androidx.media.app.NotificationCompat.MediaStyle

import android.app.PendingIntent
import android.content.Intent

import android.support.v4.media.session.PlaybackStateCompat

import com.koushikdutta.async.http.AsyncHttpClient
import com.koushikdutta.async.http.AsyncHttpGet
import com.koushikdutta.async.http.AsyncHttpResponse
import org.json.JSONException
import org.json.JSONObject
import java.util.*

/**
 *
 * Created by Alex on 19.07.2016.
 *
 * */
class PlayerNotification(private val player: PlayerService, private val notificationId: String) {
    private val timer: Timer
    private val notification: Notification
    private val mediaSession: MediaSessionCompat

    private var currentArtist: String? = ""
    private var currentTitle: String? = ""

    var isPlaying = false
        private set
    fun setPlaying(playing: Boolean): PlayerNotification {
        isPlaying = playing
        return this
    }


    fun update(): PlayerNotification {
        if (isPlaying) {
            player.startForeground(NOTIFICATION_ID, notification)
        } else {
            player.startForeground(NOTIFICATION_ID, notification)
            player.stopForeground(false)
        }

        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(
                    if (isPlaying)
                        PlaybackStateCompat.STATE_PLAYING
                    else PlaybackStateCompat.STATE_STOPPED, 0, 0F
                )
                .setActions(if (isPlaying)
                    PlaybackStateCompat.ACTION_STOP
                    else PlaybackStateCompat.ACTION_PLAY)
                .build()
        )

        return this
    }

    fun remove() {
        timer.cancel()
        player.stopForeground(true)
    }

    fun setNotificationTitle(artist: String?, title: String?) {

        if (artist != currentArtist || title != currentTitle) {

            mediaSession.setMetadata(
                MediaMetadataCompat.Builder()

                    // Title.
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)

                    // Artist.
                    // Could also be the channel name or TV series.
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)

                    .build()

            )
        }

    }

    companion object {
        private const val NOTIFICATION_ID = 1337
        private const val TRACK_SOURCE_URL = "https://core.waveradio.org/public/current?station=provodach"
    }

    init {

        val deleteIntent = PendingIntent.getBroadcast(
                player, 1, Intent(PlayerService.ACTION_TERMINATE), PendingIntent.FLAG_UPDATE_CURRENT)

        mediaSession = MediaSessionCompat(player.applicationContext, "PlayerService")
        mediaSession.isActive = true

        val closeAction: NotificationCompat.Action? = NotificationCompat.Action.Builder(
            R.drawable.ic_close_white_36dp, "Close", deleteIntent
        ).build()

        notification = NotificationCompat.Builder(player, notificationId) // .setWhen(0)
                .setContentTitle("Provoda.ch")
                .setSmallIcon(R.drawable.ic_play_arrow_white_36dp)
                .setDeleteIntent(deleteIntent)
                .setStyle(
                    MediaStyle()
                        .setMediaSession(mediaSession.sessionToken)
                        .setShowActionsInCompactView(0))
                .addAction(closeAction)

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
                            }
                        })
            }
        }, 0, 15000)
    }
}