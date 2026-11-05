package com.asterleen.provodach

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import com.koushikdutta.async.future.Future
import com.koushikdutta.async.http.AsyncHttpClient
import com.koushikdutta.async.http.AsyncHttpGet
import com.koushikdutta.async.http.AsyncHttpResponse
import org.json.JSONObject
import java.util.concurrent.Executors

/** Publishes BASS playback through Android's standard media controls and Bluetooth AVRCP. */
class PlayerNotification(private val player: PlayerService, private val channelId: String) {
    private val handler = Handler(Looper.getMainLooper())
    private val radioName = player.getString(R.string.radio_name)
    private val mediaSession = MediaSessionCompat(player, radioName)
    private val notificationManager =
        player.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as NotificationManager
    private val contentIntent = PendingIntent.getActivity(
        player, 0, Intent(player, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag
    )
    private var currentArtist = radioName
    private var currentTitle = player.getString(R.string.noTrack)
    private var state = PlaybackStateCompat.STATE_PAUSED
    private var released = false
    private var request: Future<JSONObject>? = null
    private val artworkResources = player.resources.obtainTypedArray(R.array.radio_backgrounds).let { array ->
        try {
            IntArray(array.length()) { array.getResourceId(it, 0) }
        } finally {
            array.recycle()
        }
    }
    private val artworkSelector = TrackArtworkSelector(artworkResources.size)
    private val artworkExecutor = Executors.newSingleThreadExecutor()
    private var artworkRequest = 0
    private var artworkResource = artworkResources[artworkSelector.index]
    private var artwork: Bitmap? = null

    val isPlaying: Boolean
        get() = state == PlaybackStateCompat.STATE_PLAYING ||
            state == PlaybackStateCompat.STATE_BUFFERING

    private val pollTrack: Runnable = object : Runnable {
        override fun run() {
            if (released) return
            val get = AsyncHttpGet(TRACK_SOURCE_URL).apply { setTimeout(10000) }
            request = AsyncHttpClient.getDefaultInstance().executeJSONObject(
                get, object : AsyncHttpClient.JSONObjectCallback() {
                    override fun onCompleted(
                        e: Exception?, source: AsyncHttpResponse?, result: JSONObject?
                    ) {
                        handler.post {
                            if (released) return@post
                            val payload = if (e == null && result?.optInt("status", -1) == 0)
                                result.optJSONObject("payload") else null
                            if (payload != null) {
                                setTrack(
                                    payload.optString("artist").ifBlank { radioName },
                                    payload.optString("title").ifBlank { player.getString(R.string.noTrack) }
                                )
                            }
                            // Retain the last known track on transient API errors.
                            handler.postDelayed(this@PlayerNotification.pollTrack, 15000)
                        }
                    }
                }
            )
        }
    }

    init {
        mediaSession.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        mediaSession.setPlaybackToLocal(AudioManager.STREAM_MUSIC)
        mediaSession.setSessionActivity(contentIntent)
        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() = player.play()
            override fun onPause() = player.pause()
            override fun onStop() = player.pause()
        }, handler)
        publishMetadata()
        loadArtwork()
        mediaSession.isActive = true
        handler.post(pollTrack)
    }

    fun handleMediaButton(intent: Intent) {
        MediaButtonReceiver.handleIntent(mediaSession, intent)
    }

    fun setState(newState: Int) {
        if (released) return
        state = newState
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP
                )
                // Live radio has no timeline, duration, seek or skip commands.
                .setState(
                    state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    if (state == PlaybackStateCompat.STATE_PLAYING) 1f else 0f
                )
                .build()
        )
        val notification = buildNotification()
        if (isPlaying) {
            player.startForeground(NOTIFICATION_ID, notification)
        } else {
            notificationManager.notify(NOTIFICATION_ID, notification)
            player.stopForeground(false)
        }
    }

    private fun setTrack(artist: String, title: String) {
        if (artist == currentArtist && title == currentTitle) return
        currentArtist = artist
        currentTitle = title
        loadArtwork()
        publishMetadata()
        if (state != PlaybackStateCompat.STATE_NONE) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun loadArtwork() {
        val resource = artworkResources[artworkSelector.select(currentArtist, currentTitle)]
        val version = ++artworkRequest
        artworkExecutor.execute {
            // nodpi assets are already 512x512; keep decoding off the media-session thread.
            val bitmap = BitmapFactory.decodeResource(
                player.resources, resource,
                BitmapFactory.Options().apply {
                    inScaled = false
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
            )
            handler.post {
                if (released || version != artworkRequest) return@post
                artwork = bitmap
                artworkResource = resource
                publishMetadata()
                notificationManager.notify(NOTIFICATION_ID, buildNotification())
            }
        }
    }

    private fun publishMetadata() {
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, currentTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, currentArtist)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, radioName)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, radioName)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork)
                .putString(
                    MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI,
                    "android.resource://${player.packageName}/$artworkResource"
                )
                .build()
        )
    }

    private fun serviceIntent(action: String): PendingIntent =
        PendingIntent.getService(
            player, 0, Intent(player, PlayerService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag
        )

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(player, channelId)
            .setSmallIcon(R.drawable.ic_radio_notification)
            .setLargeIcon(artwork)
            .setContentTitle(currentTitle)
            .setContentText(currentArtist)
            .setSubText(radioName)
            .setContentIntent(contentIntent)
            .setDeleteIntent(serviceIntent(PlayerService.ACTION_TERMINATE))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(isPlaying)
            .setStyle(MediaStyle().setMediaSession(mediaSession.sessionToken).setShowActionsInCompactView(0))
            .addAction(
                if (isPlaying) R.drawable.ic_pause_white_36dp else R.drawable.ic_play_arrow_white_36dp,
                player.getString(if (isPlaying) R.string.pause_playback else R.string.resume_playback),
                serviceIntent(if (isPlaying) PlayerService.ACTION_STOP else PlayerService.ACTION_PLAY)
            )
            .build()

    fun remove() {
        released = true
        artworkRequest++
        artworkExecutor.shutdownNow()
        artwork = null
        handler.removeCallbacksAndMessages(null)
        request?.cancel()
        mediaSession.isActive = false
        mediaSession.setCallback(null)
        mediaSession.release()
        player.stopForeground(true)
        notificationManager.cancel(NOTIFICATION_ID)
    }

    companion object {
        private const val NOTIFICATION_ID = 1337
        private const val TRACK_SOURCE_URL = "https://core.waveradio.org/public/current?station=provodach"
        private val immutableFlag: Int
            get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
    }
}
