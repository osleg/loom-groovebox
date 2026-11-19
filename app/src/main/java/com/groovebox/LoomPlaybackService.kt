/* SPDX-License-Identifier: GPL-3.0-only */
package com.groovebox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.groovebox.cafa.CafaConnection
import org.fm1.companion.protocol.Wire6

/** Keeps an explicitly started FM-1 session alive while the activity is off screen. */
class LoomPlaybackService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var audioManager: AudioManager
    private var connection: CafaConnection? = null
    private var generation = 0L
    private var focusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL, "FM-1 playback", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val requested = intent.getLongExtra(EXTRA_GENERATION, 0L)
                if (!requests.take(requested)) {
                    stopIfIdle()
                    return START_NOT_STICKY
                }
                val midiId = intent.getIntExtra(EXTRA_MIDI_ID, -1)
                val audioId = intent.getIntExtra(EXTRA_AUDIO_ID, -1)
                val audioMode = intent.getIntExtra(EXTRA_AUDIO_MODE, -1)
                connect(requested, midiId, audioId, audioMode)
            }
            ACTION_STOP -> {
                if (requests.acceptsStop(intent.getLongExtra(EXTRA_GENERATION, 0L)))
                    stopPlayback("FM-1 stopped. Reconnect explicitly.")
            }
            else -> stopIfIdle()
        }
        return START_NOT_STICKY
    }

    private fun connect(requested: Long, midiId: Int, audioId: Int, audioMode: Int) {
        // A new explicit request replaces the previous session on the same main thread.
        cleanup("Reconnecting to FM-1…", disconnect = connection != null, releasePin = false)
        val runtime = LoomRuntime.from(this)
        val selected = runtime.acquirePlayback()
        if (selected == null) {
            stopPlayback("Loom is not ready. Open the app and reconnect to FM-1.")
            return
        }
        requests.claim(requested)
        connection = selected
        generation = requested
        if (midiId < 0 || audioId < 0 || audioMode !in listOf(Wire6.PHONE, Wire6.USB_RETURN)) {
            stopPlayback("Choose FM-1 MIDI and an audio output. Reconnect explicitly.")
            return
        }
        try {
            showNotification(requested, audioMode)
            acquireWakeLock()
            if (!requestAudioFocus(requested)) {
                stopPlayback("Audio focus unavailable. Reconnect explicitly.")
                return
            }
            selected.onSessionEnded = {
                if (generation == requested && connection === selected)
                    stopPlayback(null)
            }
            selected.connect(midiId, audioId, audioMode)
        } catch (error: Exception) {
            stopPlayback("Could not start FM-1 playback: ${error.message ?: "unknown error"}. Reconnect explicitly.")
        }
    }

    private fun requestAudioFocus(requested: Long): Boolean {
        lateinit var request: AudioFocusRequest
        request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                if (change != AudioManager.AUDIOFOCUS_GAIN && generation == requested &&
                    connection != null && focusRequest === request)
                    stopPlayback("Audio focus lost. Reconnect explicitly.")
            }, main).build()
        focusRequest = request
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun acquireWakeLock() {
        val lock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FM1:LoomPlayback")
        lock.setReferenceCounted(false)
        lock.acquire()
        wakeLock = lock
    }

    private fun showNotification(requested: Long, audioMode: Int) {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, requested.toInt(),
            Intent(this, LoomPlaybackService::class.java).setAction(ACTION_STOP)
                .putExtra(EXTRA_GENERATION, requested),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Loom · FM-1")
            .setContentText(if (audioMode == Wire6.PHONE) "Phone output · FM-1 controls"
                else "FM-1 USB return · FM-1 controls")
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIFICATION_ID, notification)
        foreground = true
    }

    private fun stopPlayback(reason: String?) {
        cleanup(reason, disconnect = reason != null, releasePin = true)
        if (!requests.hasPending()) stopSelf()
    }

    private fun cleanup(reason: String?, disconnect: Boolean, releasePin: Boolean) {
        val old = connection
        connection = null
        generation = 0L
        old?.onSessionEnded = null
        if (disconnect && old != null) runCatching { old.disconnect(reason ?: "FM-1 stopped") }
        focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        focusRequest = null
        wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
        wakeLock = null
        if (foreground) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            foreground = false
        }
        if (releasePin) {
            if (requests.serviceStopped()) LoomRuntime.from(this).releasePlayback()
        }
    }

    private fun stopIfIdle() {
        if (connection == null) {
            val (release, stop) = requests.idle()
            if (release) LoomRuntime.from(this).releasePlayback()
            if (stop) stopSelf()
        }
    }

    override fun onDestroy() {
        cleanup("FM-1 playback ended. Reconnect explicitly.", disconnect = connection != null,
            releasePin = true)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "fm1_loom_playback"
        private const val NOTIFICATION_ID = 1338
        private const val ACTION_CONNECT = "com.groovebox.fm1.CONNECT"
        private const val ACTION_STOP = "com.groovebox.fm1.STOP"
        private const val EXTRA_MIDI_ID = "midi_id"
        private const val EXTRA_AUDIO_ID = "audio_id"
        private const val EXTRA_AUDIO_MODE = "audio_mode"
        private const val EXTRA_GENERATION = "generation"
        private val requests = PlaybackRequests()

        /** Only call for a selection made in the visible activity. */
        fun start(context: Context, midiId: Int, audioId: Int, audioMode: Int) {
            val runtime = LoomRuntime.from(context)
            if (runtime.acquirePlayback() == null)
                throw IllegalStateException("Loom is not ready. Open the app and reconnect to FM-1.")
            val requested = requests.submitted()
            val intent = Intent(context, LoomPlaybackService::class.java).setAction(ACTION_CONNECT)
                .putExtra(EXTRA_MIDI_ID, midiId)
                .putExtra(EXTRA_AUDIO_ID, audioId)
                .putExtra(EXTRA_AUDIO_MODE, audioMode)
                .putExtra(EXTRA_GENERATION, requested)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (error: Exception) {
                if (requests.submissionFailed(requested)) runtime.releasePlayback()
                throw error
            }
        }
    }
}

/** Owns the one runtime pin across pending and active service starts. */
internal class PlaybackRequests {
    private var latest = 0L
    private val pending = mutableSetOf<Long>()
    private var reserved = false
    private var active = 0L

    @Synchronized fun submitted(): Long {
        reserved = true
        return (++latest).also { pending.add(it) }
    }

    @Synchronized fun take(token: Long): Boolean =
        pending.remove(token) && token != 0L && token == latest

    @Synchronized fun claim(token: Long) { active = token }

    @Synchronized fun acceptsStop(token: Long): Boolean = token != 0L && token == active

    @Synchronized fun submissionFailed(token: Long): Boolean {
        pending.remove(token)
        return releaseIfUnused()
    }

    @Synchronized fun serviceStopped(): Boolean {
        active = 0L
        return releaseIfUnused()
    }

    @Synchronized fun idle(): Pair<Boolean, Boolean> {
        val stop = active == 0L && pending.isEmpty()
        return releaseIfUnused() to stop
    }

    @Synchronized fun hasPending(): Boolean = pending.isNotEmpty()

    private fun releaseIfUnused(): Boolean {
        if (!reserved || active != 0L || pending.isNotEmpty()) return false
        reserved = false
        return true
    }
}
