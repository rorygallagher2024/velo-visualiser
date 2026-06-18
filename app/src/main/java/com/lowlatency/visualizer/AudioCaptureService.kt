package com.lowlatency.visualizer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlin.concurrent.thread

/**
 * Foreground service that owns a MediaProjection session and captures *system
 * audio* via AudioPlaybackCapture (API 29+).
 *
 * Android 14+ requires:
 *   - a foreground service of type `mediaProjection`,
 *   - the service started *before* MediaProjection is used,
 *   - FOREGROUND_SERVICE_MEDIA_PROJECTION permission.
 *
 * Captured 16-bit PCM is pushed straight into the native ring buffer through
 * [NativeBridge.nativePushPcm], so the renderer consumes mic and system audio
 * through the exact same path.
 */
class AudioCaptureService : Service() {

    companion object {
        private const val TAG = "AudioCaptureService"
        private const val CHANNEL_ID = "audio_capture"
        private const val NOTIFICATION_ID = 0x5C09

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val SAMPLE_RATE = 48_000
        private const val CHANNELS = 2 // playback capture is stereo

        fun newIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, AudioCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
    }

    @Volatile private var capturing = false
    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        if (resultCode == 0 || data == null) {
            Log.e(TAG, "Missing MediaProjection token; stopping.")
            stopSelf()
            return START_NOT_STICKY
        }

        startCapture(resultCode, data)
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Audio Capture",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Visualizing system audio")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startCapture(resultCode: Int, data: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.e(TAG, "AudioPlaybackCapture requires API 29+.")
            stopSelf()
            return
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        // getMediaProjection() is @Nullable on API 36+ — bail out cleanly if the
        // token can't be turned into a session.
        val mp = mpm.getMediaProjection(resultCode, data)
        if (mp == null) {
            Log.e(TAG, "Could not obtain MediaProjection; stopping.")
            stopSelf()
            return
        }
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection stopped by system/user.")
                stopSelf()
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

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        record = AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(minBuf * 2)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        record?.startRecording()
        capturing = true

        // Read on a dedicated high-priority thread; forward to native engine.
        thread(name = "PlaybackCaptureReader", priority = Thread.MAX_PRIORITY) {
            val frames = minBuf / (CHANNELS * 2) // 16-bit => 2 bytes/sample
            val buf = ShortArray(frames * CHANNELS)
            while (capturing) {
                val read = record?.read(buf, 0, buf.size) ?: -1
                if (read > 0) {
                    NativeBridge.nativePushPcm(buf, read / CHANNELS, CHANNELS)
                } else if (read < 0) {
                    Log.e(TAG, "AudioRecord.read error: $read")
                    break
                }
            }
        }
        Log.i(TAG, "System-audio capture started.")
    }

    override fun onDestroy() {
        capturing = false
        record?.runCatching { stop(); release() }
        record = null
        projection?.stop()
        projection = null
        super.onDestroy()
    }
}
