package com.asterleen.provodach

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.media.AudioManager
import android.media.AudioManager.OnAudioFocusChangeListener
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.un4seen.bass.BASS


/**
 * Сервис плеера *
 *
 * Все, что касается воспроизведения аудиопотока, вынесено сюда, в остальных классах
 * весь подобный функционал сведен к минимуму. Их задача - работать с этим процессом.
 *
 * Сервис имеет оповещение и обрабатывает команды от него. Кроме очевидных, еще и голосовалку.
 *
 * Управление сервисом осуществляется через обмен broadcast-сообщениями. Сервис может сообщать
 * свое состояние в ответ на ACTION_STATE для корректной установки состояния активити.
 *
 * Запуск осуществляется передачей intent с параметром lowstream, определяющим тип потока,
 * и token, необходимого для голосования за треки (может быть null).  */
class PlayerService : Service(), OnAudioFocusChangeListener {
    private var actionReceiver: BroadcastReceiver? = null
    private var channel = 0
    private var stream: String? = null
    private var token: String? = null
    private var notification: PlayerNotification? = null

    // WifiManager.WifiLock wifiLock;
    override fun onCreate() {
        super.onCreate()
        actionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_PLAY -> {
                        notification?.setPlaying(true)?.update()
                        start(stream)
                    }
                    ACTION_STOP -> {
                        stop()
                        notification?.setPlaying(false)?.update()
                    }
                    ACTION_TERMINATE -> stopSelf()
                    ACTION_STATE -> if (notification!!.isPlaying) sendBroadcast(Intent(ACTION_PLAY)) else sendBroadcast(Intent(ACTION_STOP))
                }
            }
        }

        Log.d(TAG, "PlayerService starting")

        val actionFilter = IntentFilter()
        actionFilter.addAction(ACTION_PLAY)
        actionFilter.addAction(ACTION_STOP)
        actionFilter.addAction(ACTION_STATE)
        actionFilter.addAction(ACTION_TERMINATE)
        actionFilter.addAction(ACTION_TOKEN)
        registerReceiver(actionReceiver, actionFilter)
        if ((getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                        .requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            stopSelf()
            sendBroadcast(Intent(ACTION_STOP))
            Toast.makeText(this@PlayerService, R.string.playerFocusRequestError, Toast.LENGTH_LONG).show()
            return
        }

        if (!BASS.BASS_Init(-1, 44100, 0)) {
            stopSelf()
            sendBroadcast(Intent(ACTION_STOP))
            Toast.makeText(this@PlayerService, "Can't start up BASS player", Toast.LENGTH_LONG).show()
            return
        }

        BASS.BASS_SetConfig(BASS.BASS_CONFIG_NET_PREBUF, 0)
        notification = PlayerNotification(this, getNotificationChannelId(NOTIFICATION_ID, NOTIFICATION_STR))

        Log.d(TAG, "PlayerService ready")
    }

    override fun onDestroy() {
        sendBroadcast(Intent(ACTION_STOP))
        unregisterReceiver(actionReceiver)
        (getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocus(this)
        notification?.remove()
        BASS.BASS_StreamFree(channel)
        BASS.BASS_Free()
        Log.d(TAG, "PlayerService freed")
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        stream = if (intent.getBooleanExtra("lowstream", false)) STREAM_LOW else STREAM
        token = intent.getStringExtra("token")

        // Log.d(TAG, stream);
        notification?.setPlaying(true)?.update()
        sendBroadcast(Intent(ACTION_PLAY))
        start(stream)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    private fun start(stream: String?) {
        if (BASS.BASS_ChannelIsActive(channel) == BASS.BASS_ACTIVE_PLAYING && stream == this.stream) return
        BASS.BASS_StreamFree(channel)
        channel = BASS.BASS_StreamCreateURL(stream, 0, BASS.BASS_STREAM_AUTOFREE, null, null)
        if (channel == 0) {
            Toast.makeText(this@PlayerService, R.string.playerConnectionError, Toast.LENGTH_LONG).show()
            Log.e(TAG, "BASS shows the error #" + BASS.BASS_ErrorGetCode())
            sendBroadcast(Intent(ACTION_STOP))
            stopSelf()
            return
        }
        BASS.BASS_ChannelPlay(channel, false)
    }

    private fun stop() {
        BASS.BASS_StreamFree(channel)
    }

    override fun onAudioFocusChange(duration: Int) {
        when (duration) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE -> {
                stop()
                sendBroadcast(Intent(ACTION_STOP))
                notification?.setPlaying(false)?.update()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                notification?.setPlaying(true)?.update()
                sendBroadcast(Intent(ACTION_PLAY))
                start(stream)
            }

            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> BASS.BASS_ChannelSlideAttribute(channel, BASS.BASS_ATTRIB_VOL, 0.5f, 1000)
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> BASS.BASS_ChannelSlideAttribute(channel, BASS.BASS_ATTRIB_VOL, 1f, 1000)
        }
    }

    private fun getNotificationChannelId(channelId: String, channelName: String): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel(channelId, channelName)
        } else {
            ""
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(channelId: String, channelName: String): String {
        val chan = NotificationChannel(channelId,
                channelName, NotificationManager.IMPORTANCE_DEFAULT)
        chan.lightColor = Color.DKGRAY
        chan.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        service.createNotificationChannel(chan)
        return channelId
    }

    companion object {
        private const val TAG = "PlayerService"
        private const val STREAM = "https://station.waveradio.org/provodach"
        private const val STREAM_LOW = "https://station.waveradio.org/provodach-low"
        const val ACTION_PLAY = "com.asterleen.provodach.player.PLAY"
        const val ACTION_STOP = "com.asterleen.provodach.player.STOP"
        const val ACTION_STATE = "com.asterleen.provodach.player.STATE"
        const val ACTION_TERMINATE = "com.asterleen.provodach.player.TERMINATE"
        const val ACTION_TOKEN = "com.asterleen.provodach.player.TOKEN"

        const val NOTIFICATION_ID = "provodach_player"
        const val NOTIFICATION_STR = "Provoda.ch Player"
    }
}