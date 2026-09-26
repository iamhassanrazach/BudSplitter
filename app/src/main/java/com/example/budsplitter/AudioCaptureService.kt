package com.example.budsplitter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlin.concurrent.thread

class AudioCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var isCapturing = false
    private var captureThread: Thread? = null
    private var currentGain = 2.0f // 200% default

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_GAIN_UP -> {
                currentGain = (currentGain + 0.25f).coerceAtMost(5.0f)
                MainActivity.updateNativeGain(currentGain)
                updateNotification()
                return START_NOT_STICKY
            }
            ACTION_GAIN_DOWN -> {
                currentGain = (currentGain - 0.25f).coerceAtLeast(0.0f)
                MainActivity.updateNativeGain(currentGain)
                updateNotification()
                return START_NOT_STICKY
            }
            ACTION_GAIN_MUTE -> {
                currentGain = 0.0f
                MainActivity.updateNativeGain(currentGain)
                updateNotification()
                return START_NOT_STICKY
            }
        }

        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != 0 && resultData != null && !isCapturing) {
            startMediaCapture(resultCode, resultData)
        }

        return START_NOT_STICKY
    }

    private fun startMediaCapture(resultCode: Int, resultData: Intent) {
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = projectionManager?.getMediaProjection(resultCode, resultData)

        if (mediaProjection == null) {
            stopSelf()
            return
        }

        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val sampleRate = 48000
        val channelConfig = AudioFormat.CHANNEL_IN_STEREO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        try {
            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(minBufSize * 2)
                .build()

            audioRecord?.startRecording()
            isCapturing = true

            captureThread = thread(name = "BudAudioCapture") {
                val buffer = ShortArray(1024)
                while (isCapturing) {
                    audioRecord?.read(buffer, 0, buffer.size)
                }
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
            stopSelf()
        }
    }

    private fun stopCapture() {
        isCapturing = false
        captureThread?.interrupt()
        captureThread = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        mediaProjection?.stop()
        mediaProjection = null
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "BudSplitter Mic Controller",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pct = (currentGain * 100).toInt()

        val mainIntent = Intent(this, MainActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val downIntent = Intent(this, AudioCaptureService::class.java).apply { action = ACTION_GAIN_DOWN }
        val downPendingIntent = PendingIntent.getService(
            this, 1, downIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val upIntent = Intent(this, AudioCaptureService::class.java).apply { action = ACTION_GAIN_UP }
        val upPendingIntent = PendingIntent.getService(
            this, 2, upIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, AudioCaptureService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 3, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BudSplitter Active")
            .setContentText("Ambient Mic: $pct% (Right Ear)")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setProgress(500, pct, false)
            .addAction(android.R.drawable.ic_media_previous, "-25%", downPendingIntent)
            .addAction(android.R.drawable.ic_media_next, "+25%", upPendingIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification())
    }

    companion object {
        const val CHANNEL_ID = "budsplitter_service_channel"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        const val ACTION_STOP = "com.example.budsplitter.ACTION_STOP"
        const val ACTION_GAIN_UP = "com.example.budsplitter.ACTION_GAIN_UP"
        const val ACTION_GAIN_DOWN = "com.example.budsplitter.ACTION_GAIN_DOWN"
        const val ACTION_GAIN_MUTE = "com.example.budsplitter.ACTION_GAIN_MUTE"
    }
}