package com.lowlatency.visualizer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresPermission
import com.lowlatency.visualizer.hue.HueLightController
import kotlin.concurrent.thread

/**
 * Foreground service that manages audio capture (Mic or System Audio) and
 * Philips Hue synchronization. By moving these to a service, the light show
 * can continue running even when the app is in the background or closed.
 */
class VisualizerService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): VisualizerService = this@VisualizerService
    }

    private val binder = LocalBinder()

    companion object {
        private const val TAG = "VisualizerService"
        private const val CHANNEL_ID = "visualizer_service"
        private const val NOTIFICATION_ID = 0x5C09

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        const val ACTION_STOP = "com.lowlatency.visualizer.ACTION_STOP"
        const val ACTION_STOPPED = "com.lowlatency.visualizer.ACTION_STOPPED"

        private const val SAMPLE_RATE = 48_000
        private const val CHANNELS = 2
        private const val SYSTEM_AUDIO_GAIN = 0.30f

        fun newIntent(context: Context): Intent =
            Intent(context, VisualizerService::class.java)

        fun captureIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, VisualizerService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
    }

    @Volatile private var capturing = false
    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var readerThread: Thread? = null

    // The single source of truth for Hue sync. Managed by the service so it
    // survives activity destruction.
    lateinit var hueController: HueLightController
        private set

    override fun onCreate() {
        super.onCreate()
        hueController = HueLightController(this)
        Log.i(TAG, "Service created, HueController initialized.")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    @RequiresPermission(allOf = [Manifest.permission.RECORD_AUDIO, Manifest.permission.FOREGROUND_SERVICE_MICROPHONE])
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Always ensure the notification is up if we are starting.
        updateNotification()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)

        if (resultCode != 0 && data != null) {
            startInternalAudioCapture(resultCode, data)
        }

        return START_STICKY
    }

    fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Visualizer Background Service",
                NotificationManager.IMPORTANCE_LOW
            )
        )

        val stopIntent = Intent(this, VisualizerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val hueActive = hueController.isEnabled
        val internalAudioActive = capturing

        val contentText = when {
            hueActive && internalAudioActive -> "Visualizing system audio & Hue sync active"
            hueActive -> "Hue light show active"
            internalAudioActive -> "Visualizing system audio"
            else -> "Visualizer service ready"
        }

        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Low Latency Visualizer")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stopPendingIntent).build())
            .build()

        // Combine types based on active features.
        var type = 0
        if (internalAudioActive) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        // Even if only Hue is active, we might need Mic if that's the audio source.
        // For compliance, if we might be using the mic in the background, we need the type.
        type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

        try {
            startForeground(NOTIFICATION_ID, notification, type)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startInternalAudioCapture(resultCode: Int, data: Intent) {
        if (capturing) return

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(resultCode, data)
        if (mp == null) {
            Log.e(TAG, "Could not obtain MediaProjection")
            return
        }
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection stopped.")
                stopInternalAudioCapture()
                updateNotification()
            }
        }, null)
        projection = mp

        val config = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)

        record = AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(minBuf * 2)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        record?.startRecording()
        capturing = true

        readerThread = thread(name = "PlaybackCaptureReader", priority = Thread.MAX_PRIORITY) {
            val frames = minBuf / (CHANNELS * 2)
            val buf = ShortArray(frames * CHANNELS)
            while (capturing) {
                val read = record?.read(buf, 0, buf.size) ?: -1
                if (read > 0) {
                    NativeBridge.nativePushPcm(buf, read / CHANNELS, CHANNELS, SYSTEM_AUDIO_GAIN)
                } else if (read < 0) break
            }
        }
        updateNotification()
    }

    fun stopInternalAudioCapture() {
        capturing = false
        record?.runCatching { stop() }
        readerThread?.runCatching { join(300) }
        readerThread = null
        record?.runCatching { release() }
        record = null
        projection?.stop()
        projection = null
        updateNotification()
    }

    override fun onDestroy() {
        capturing = false
        hueController.disable()
        stopInternalAudioCapture()
        sendBroadcast(Intent(ACTION_STOPPED).setPackage(packageName))
        super.onDestroy()
    }
}
