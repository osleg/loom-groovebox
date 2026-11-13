package com.groovebox

import android.Manifest
import android.content.pm.PackageManager
import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class AudioCaptureService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val nativeLib = NativeLib()
    
    companion object {
        const val CHANNEL_ID = "AudioCaptureChannel"
        const val NOTIFICATION_ID = 1337
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
                if (resultData != null) {
                    startForeground(NOTIFICATION_ID, createNotification())
                    startCapture(resultData)
                }
            }
            ACTION_STOP -> {
                stopCapture()
                stopForeground(true)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture(resultData: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w("AudioCaptureService", "Audio capture requires microphone permission")
            stopCapture()
            stopSelf()
            return
        }

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(Activity.RESULT_OK, resultData)
        
        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val sampleRate = 48000
        val channelConfig = AudioFormat.CHANNEL_IN_STEREO
        val audioFormat = AudioFormat.ENCODING_PCM_FLOAT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

        try {
            audioRecord = AudioRecord.Builder()
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build())
                .setAudioPlaybackCaptureConfig(config)
                .setBufferSizeInBytes(bufferSize)
                .build()

            audioRecord?.startRecording()
            
            captureJob = CoroutineScope(Dispatchers.IO).launch {
                val stereoBuffer = FloatArray(2048)
                val monoBuffer = FloatArray(1024)
                while (isActive && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val read = audioRecord?.read(stereoBuffer, 0, stereoBuffer.size, AudioRecord.READ_BLOCKING) ?: 0
                    if (read > 0) {
                        val numFrames = read / 2
                        for (i in 0 until numFrames) {
                            monoBuffer[i] = (stereoBuffer[i * 2] + stereoBuffer[i * 2 + 1]) * 0.5f
                        }
                        nativeLib.pushSystemAudioSamples(monoBuffer.copyOfRange(0, numFrames))
                    }
                }
            }
        } catch (e: SecurityException) {
            // Permission may be revoked after the explicit check above.
            Log.w("AudioCaptureService", "Audio capture permission was revoked", e)
            stopCapture()
            stopSelf()
        } catch (e: Exception) {
            Log.e("AudioCaptureService", "Failed to start capture: ${e.message}")
        }
    }

    private fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e("AudioCaptureService", "Error stopping record: ${e.message}")
        }
        audioRecord = null
        mediaProjection?.stop()
        mediaProjection = null
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Loom System Audio Capture")
            .setContentText("Recording system audio for engines...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "System Audio Capture",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}
