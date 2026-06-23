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
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlin.concurrent.thread

/**
 * Foreground service that keeps the mic (Oboe) and/or system-audio capture
 * alive while the app is backgrounded. Only started when the user explicitly
 * enables "Run in Background" — never on cold launch.
 *
 * The service does NOT own the HueLightController; the activity does. The
 * service's sole job is holding the foreground-service wakelock + notification
 * so Android doesn't kill the process while the Hue sender thread (which lives
 * in HueLightController, running in the activity's process) keeps streaming.
 */
class VisualizerService : Service() {

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

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @RequiresPermission(allOf = [Manifest.permission.RECORD_AUDIO, Manifest.permission.FOREGROUND_SERVICE_MICROPHONE])
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        showNotification()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)

        if (resultCode != 0 && data != null) {
            startInternalAudioCapture(resultCode, data)
        }

        return START_NOT_STICKY
    }

    fun updateNotificationText(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val notification = buildNotification(text)
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun showNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Background Light Sync",
                NotificationManager.IMPORTANCE_LOW
            )
        )

        val notification = buildNotification("Hue light show running in background")
        try {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
        }
    }

    private fun buildNotification(contentText: String): Notification {
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

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Velo")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stopPendingIntent).build())
            .build()
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
    }

    override fun onDestroy() {
        stopInternalAudioCapture()
        sendBroadcast(Intent(ACTION_STOPPED).setPackage(packageName))
        Log.i(TAG, "Service destroyed.")
        super.onDestroy()
    }
}
