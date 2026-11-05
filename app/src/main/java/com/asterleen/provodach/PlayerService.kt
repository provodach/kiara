package com.asterleen.provodach

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.widget.Toast
import androidx.preference.PreferenceManager
import com.un4seen.bass.BASS
import com.un4seen.bass.BASSHLS
import java.util.concurrent.Executors

/** BASS owns audio playback; the media session exposes its controls and metadata to Android. */
class PlayerService : Service(), AudioManager.OnAudioFocusChangeListener {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audioManager: AudioManager
    private var notification: PlayerNotification? = null
    private var initialized = false
    private var destroyed = false
    private var resumeOnFocusGain = false
    private var useHls = false
    private var stream = STREAM
    // BASS channel operations are serialized on audioExecutor.
    private var channel = 0
    @Volatile private var generation = 0

    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_STATE -> broadcastState()
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> pause()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val filter = IntentFilter().apply {
            addAction(ACTION_STATE)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }
        registerReceiver(actionReceiver, filter)
        useHls = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("use_hls", false)
        stream = if (useHls) STREAM_HLS else STREAM

        notification = PlayerNotification(this, createNotificationChannel())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            Intent.ACTION_MEDIA_BUTTON -> notification?.handleMediaButton(intent)
            ACTION_PLAY -> play()
            ACTION_STOP -> pause()
            ACTION_TERMINATE -> stopSelf()
            ACTION_STATE -> broadcastState()
            else -> {
                if (intent?.hasExtra("stream") == true) {
                    val type = intent.getStringExtra("stream")
                    val nextStream = when (type) {
                        "hls" -> STREAM_HLS
                        "low" -> STREAM_LOW
                        else -> STREAM
                    }
                    if (nextStream != stream) pause()
                    useHls = type == "hls"
                    stream = nextStream
                }
                play()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = null

    fun play() {
        if (destroyed || notification?.isPlaying == true) return
        resumeOnFocusGain = false
        if (audioManager.requestAudioFocus(
                this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
            ) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        ) {
            Toast.makeText(this, R.string.playerFocusRequestError, Toast.LENGTH_LONG).show()
            notification?.setState(PlaybackStateCompat.STATE_PAUSED)
            broadcastState()
            return
        }

        val requestGeneration = ++generation
        val url = stream
        val hls = useHls
        notification?.setState(PlaybackStateCompat.STATE_BUFFERING)
        broadcastState()
        // Opening a network stream must not block media buttons or the service's main thread.
        audioExecutor.execute {
            if (requestGeneration != generation) return@execute
            if (!initialized) {
                initialized = BASS.BASS_Init(-1, 44100, 0)
                if (!initialized) {
                    handler.post {
                        if (!destroyed && requestGeneration == generation) {
                            notification?.setState(PlaybackStateCompat.STATE_PAUSED)
                            audioManager.abandonAudioFocus(this)
                            broadcastState()
                            Toast.makeText(this, R.string.playerConnectionError, Toast.LENGTH_LONG).show()
                        }
                    }
                    return@execute
                }
                BASS.BASS_SetConfig(BASS.BASS_CONFIG_NET_PREBUF, 0)
                BASS.BASS_SetConfig(BASS.BASS_CONFIG_NET_TIMEOUT, 10000)
            }
            freeStream()
            val newChannel = if (hls)
                BASSHLS.BASS_HLS_StreamCreateURL(url, 0, null, null)
            else
                BASS.BASS_StreamCreateURL(url, 0, 0, null, null)

            if (requestGeneration != generation) {
                if (newChannel != 0) BASS.BASS_StreamFree(newChannel)
                return@execute
            }
            channel = newChannel
            val playing = channel != 0 && BASS.BASS_ChannelPlay(channel, false)
            val error = if (playing) 0 else BASS.BASS_ErrorGetCode()
            if (playing) {
                BASS.BASS_ChannelSetSync(channel, BASS.BASS_SYNC_END, 0, { _, _, _, _ ->
                    handler.post {
                        if (!destroyed && requestGeneration == generation) pause()
                    }
                }, null)
            } else {
                freeStream()
            }
            handler.post {
                if (destroyed || requestGeneration != generation) return@post
                if (playing) {
                    notification?.setState(PlaybackStateCompat.STATE_PLAYING)
                } else {
                    Log.e(TAG, "BASS connection/playback error #$error")
                    notification?.setState(PlaybackStateCompat.STATE_PAUSED)
                    audioManager.abandonAudioFocus(this)
                    Toast.makeText(this, R.string.playerConnectionError, Toast.LENGTH_LONG).show()
                }
                broadcastState()
            }
        }
    }

    /** A live stream resumes at the current broadcast, rather than buffering old audio. */
    fun pause(abandonFocus: Boolean = true) {
        if (destroyed) return
        resumeOnFocusGain = false
        generation++
        audioExecutor.execute { freeStream() }
        notification?.setState(PlaybackStateCompat.STATE_PAUSED)
        if (abandonFocus) audioManager.abandonAudioFocus(this)
        broadcastState()
    }

    private fun freeStream() {
        if (channel != 0) {
            BASS.BASS_StreamFree(channel)
            channel = 0
        }
    }

    private fun broadcastState() {
        sendBroadcast(Intent(if (notification?.isPlaying == true) ACTION_PLAY else ACTION_STOP)
            .setPackage(packageName))
    }

    override fun onAudioFocusChange(focusChange: Int) {
        handler.post {
            if (destroyed) return@post
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS -> pause()
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    val wasPlaying = notification?.isPlaying == true
                    pause(abandonFocus = false)
                    resumeOnFocusGain = wasPlaying
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                    audioExecutor.execute {
                        if (channel != 0) BASS.BASS_ChannelSetAttribute(channel, BASS.BASS_ATTRIB_VOL, 0.2f)
                    }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    if (resumeOnFocusGain) play()
                    audioExecutor.execute {
                        if (channel != 0) BASS.BASS_ChannelSetAttribute(channel, BASS.BASS_ATTRIB_VOL, 1f)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        destroyed = true
        generation++
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(actionReceiver)
        audioManager.abandonAudioFocus(this)
        notification?.remove()
        sendBroadcast(Intent(ACTION_STOP).setPackage(packageName))
        audioExecutor.execute {
            freeStream()
            if (initialized) BASS.BASS_Free()
        }
        super.onDestroy()
    }

    private fun createNotificationChannel(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(NOTIFICATION_ID, NOTIFICATION_STR, NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NOTIFICATION_ID
    }

    companion object {
        // Shared queue prevents a destroyed service from freeing BASS after its replacement starts.
        private val audioExecutor = Executors.newSingleThreadExecutor()
        private const val TAG = "PlayerService"
        private const val STREAM = "https://station.waveradio.org/provodach"
        private const val STREAM_LOW = "https://station.waveradio.org/provodach-low"
        private const val STREAM_HLS = "https://station.waveradio.org/hls/provodach/provodach.m3u8"
        const val ACTION_PLAY = "com.asterleen.provodach.player.PLAY"
        const val ACTION_STOP = "com.asterleen.provodach.player.STOP"
        const val ACTION_STATE = "com.asterleen.provodach.player.STATE"
        const val ACTION_TERMINATE = "com.asterleen.provodach.player.TERMINATE"
        const val NOTIFICATION_ID = "provodach_player"
        const val NOTIFICATION_STR = "Provoda.ch Player"
    }
}
