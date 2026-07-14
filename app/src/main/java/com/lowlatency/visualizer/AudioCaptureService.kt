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
import android.os.IBinder
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.IntentCompat
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

        const val ACTION_STOP = "com.lowlatency.visualizer.ACTION_STOP"
        const val ACTION_STOPPED = "com.lowlatency.visualizer.ACTION_STOPPED"

        /** Set on [ACTION_STOPPED] when capture never started (vs a normal stop). */
        const val EXTRA_FAILED = "capture_failed"

        private const val SAMPLE_RATE = 48_000
        private const val CHANNELS = 2 // playback capture is stereo

        // Read granularity, NOT buffer capacity: a blocking read() only returns
        // once this many frames have accumulated, so reading minBuf-sized
        // chunks (~40 ms on some devices) added that much latency on top of
        // the OS capture path. 256 frames ≈ 5.3 ms keeps delivery tracking the
        // mixer's own burst cadence instead — matching the local-playback
        // mirror's chunk convention (AudioEngine::pushPlaybackAudio).
        private const val CHUNK_FRAMES = 256

        // System audio (AudioPlaybackCapture) arrives near full-scale, whereas
        // the UNPROCESSED mic the visuals were tuned for is much quieter — so
        // internal audio saturates every scene. Attenuate it here, at the shared
        // ring-buffer source, so the waveform, the native FFT bands, and the
        // Kotlin spectrum analyzer all scale down together. ~ -10 dBFS.
        // MUST match AudioEngine::kDigitalMonoGain (local playback's mirror)
        // so every digital source drives the visuals identically.
        private const val SYSTEM_AUDIO_GAIN = 0.30f

        fun newIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, AudioCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
    }

    @Volatile private var capturing = false
    @Volatile private var captureFailed = false
    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var readerThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = intent?.let {
            IntentCompat.getParcelableExtra(it, EXTRA_RESULT_DATA, Intent::class.java)
        }
        if (resultCode == 0 || data == null) {
            Log.e(TAG, "Missing MediaProjection token; stopping.")
            captureFailed = true
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
                getString(R.string.capture_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val stopIntent = Intent(this, AudioCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = android.app.PendingIntent.getService(
            this, 0, stopIntent, android.app.PendingIntent.FLAG_IMMUTABLE
        )
        // Tapping the notification body brings the visualizer forward
        // (singleTop launch mode resumes the existing instance).
        val openAppPendingIntent = android.app.PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null, getString(R.string.capture_notification_stop), stopPendingIntent
                ).build()
            )
            .build()

        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startCapture(resultCode: Int, data: Intent) {

        val mpm = getSystemService(MediaProjectionManager::class.java)
        // getMediaProjection() is @Nullable on API 36+ — bail out cleanly if the
        // token can't be turned into a session.
        val mp = mpm.getMediaProjection(resultCode, data)
        if (mp == null) {
            Log.e(TAG, "Could not obtain MediaProjection; stopping.")
            captureFailed = true
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

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val rec = buildAndStartPlaybackCapture(mp, minBuf)
        if (rec == null) {
            // stopSelf() → onDestroy() broadcasts ACTION_STOPPED (+ EXTRA_FAILED),
            // which flips the UI back to the microphone with an explanation
            // instead of leaving a dead SYSTEM segment.
            captureFailed = true
            stopSelf()
            return
        }
        record = rec
        capturing = true

        // Read on a dedicated high-priority thread; forward to native engine.
        readerThread = thread(name = "PlaybackCaptureReader") {
            // Real audio scheduling class — Thread.MAX_PRIORITY barely moves
            // the Linux nice value, and 5 ms reads are jitter-sensitive.
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ShortArray(CHUNK_FRAMES * CHANNELS)
            while (capturing) {
                val read = record?.read(buf, 0, buf.size) ?: -1
                if (read > 0) {
                    NativeBridge.nativePushPcm(buf, read / CHANNELS, CHANNELS, SYSTEM_AUDIO_GAIN)
                } else if (read < 0) {
                    // Mid-session death (route lost, permission revoked, …):
                    // stop the whole service so the notification clears and the
                    // UI falls back to the mic with a toast, instead of leaving
                    // a frozen SYSTEM screen under a live notification.
                    Log.e(TAG, "AudioRecord.read error: $read")
                    if (capturing) {
                        captureFailed = true
                        stopSelf()
                    }
                    break
                }
            }
        }
        Log.i(TAG, "System-audio capture started.")
    }

    /**
     * Builds and starts the playback-capture AudioRecord, or returns null when
     * the device rejects it. Both build() and startRecording() throw on some
     * devices (rejected config, missing RECORD_AUDIO grant), and an uncaught
     * exception in onStartCommand would take down the whole app.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun buildAndStartPlaybackCapture(mp: MediaProjection, minBuf: Int): AudioRecord? {
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

        return try {
            val rec = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(minBuf * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "AudioRecord did not enter RECORDING state; giving up.")
                rec.release()
                null
            } else {
                rec
            }
        } catch (e: RuntimeException) {
            // UnsupportedOperationException / IllegalStateException / SecurityException
            Log.e(TAG, "Playback-capture AudioRecord failed", e)
            null
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app from recents kills the task without the activity's
        // onDestroy necessarily running — don't keep capturing for nobody.
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Stop the loop, then unblock + drain the reader BEFORE releasing the
        // AudioRecord — releasing it while the reader is parked in read() is
        // undefined and can crash the native layer on some devices.
        capturing = false
        record?.runCatching { stop() }          // unblocks a pending blocking read()
        readerThread?.runCatching { join(300) } // wait for the reader to exit its loop
        readerThread = null
        record?.runCatching { release() }
        record = null
        projection?.stop()
        projection = null
        sendBroadcast(
            Intent(ACTION_STOPPED)
                .setPackage(packageName)
                .putExtra(EXTRA_FAILED, captureFailed)
        )
        super.onDestroy()
    }
}
