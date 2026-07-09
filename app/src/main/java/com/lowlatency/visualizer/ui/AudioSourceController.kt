package com.lowlatency.visualizer.ui

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import com.lowlatency.visualizer.AudioCaptureService
import com.lowlatency.visualizer.NativeBridge
import com.lowlatency.visualizer.R

/**
 * Audio-source switching: the mic / internal-audio / local-file segmented
 * control plus all of the engine and permission plumbing behind it.
 *
 * Three source paths feed the same native ring buffers, one at a time — this
 * controller is the single owner of which one is live ([source]):
 *
 *   - MIC: a low-latency native Oboe input stream.
 *   - SYSTEM: internal audio via the screen-capture API (MediaProjection
 *     consent dialog + a foreground service pushing PCM down to the engine).
 *   - LOCAL: file playback decoded by [LocalAudioPlayer]; the host owns the
 *     player and calls [enterLocalPlayback]/[exitLocalPlayback] around it.
 *
 * This controller owns the runtime-permission launcher, the projection
 * launcher, the "capture stopped" broadcast receiver, the toggle UI, and the
 * deferred-permission handshake (held back until the GL intro finishes on
 * first launch). Anything that merely *reacts* to the source — the lighting
 * tab's availability, beat haptics, the About dialog's engine line — observes
 * [systemAudioMode]/[isLocalPlayback] and is refreshed via [onSourceChanged];
 * this controller never reaches into those concerns itself.
 *
 * The two `registerForActivityResult` launchers are registered in property
 * initialisers, so this controller must be constructed before the activity is
 * STARTED (it is — the host builds it in `onCreate`/`initControllers`).
 *
 * @param onSourceChanged invoked after every source transition so the host can
 *   refresh source-dependent UI.
 * @param onMicStarted invoked after the mic stream successfully starts (every time).
 * @param onLocalFileRequested invoked when the user taps the Local File segment
 *   (the host launches its file picker, then calls [enterLocalPlayback] on pick).
 * @param onExternalSourceRequested invoked before switching to mic/system so the
 *   host can stop any live local playback session first.
 */
class AudioSourceController(
    private val activity: AppCompatActivity,
    private val prefs: SharedPreferences,
    private val onSourceChanged: () -> Unit,
    private val onMicStarted: () -> Unit = {},
    private val onLocalFileRequested: () -> Unit = {},
    private val onExternalSourceRequested: () -> Unit = {},
) {
    enum class Source { MIC, SYSTEM, LOCAL }

    /** The live audio source — the single source of truth for the app. */
    var source = Source.MIC
        private set

    val systemAudioMode: Boolean get() = source == Source.SYSTEM
    val isLocalPlayback: Boolean get() = source == Source.LOCAL

    private var deferredPermissionRequest = false

    private lateinit var segMic: Button
    private lateinit var segInternal: Button
    private lateinit var segLocal: Button
    private lateinit var internalAudioWarning: TextView

    private val captureStopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (source == Source.SYSTEM) selectMicrophone()
        }
    }

    private val requestPermissions = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            startMicrophone()
        } else {
            Toast.makeText(activity, "Microphone permission required.", Toast.LENGTH_LONG).show()
        }

        // Log network grant for debugging (Hue/Link need local-network reachability).
        if (Build.VERSION.SDK_INT >= 36) {
            if (grants["android.permission.ACCESS_LOCAL_NETWORK"] == true) {
                Log.d(TAG, "Android 17 Local Network permission GRANTED.")
            } else {
                Log.e(TAG, "Android 17 Local Network permission DENIED. Hue will timeout.")
            }
        }
    }

    private val projectionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            // Mic and system capture feed the same ring buffer; stop the mic so
            // we visualize system audio cleanly.
            NativeBridge.nativeStop()
            activity.startForegroundService(
                AudioCaptureService.newIntent(activity, result.resultCode, data)
            )
            source = Source.SYSTEM
        } else {
            Toast.makeText(activity, "System-audio capture denied.", Toast.LENGTH_SHORT).show()
            // Fall back to the mic (also covers arriving here from local playback).
            source = Source.MIC
            evaluateMicState()
        }
        refreshSelection()
    }

    fun bind() {
        segMic = activity.findViewById(R.id.seg_mic)
        segInternal = activity.findViewById(R.id.seg_internal)
        segLocal = activity.findViewById(R.id.seg_local)
        internalAudioWarning = activity.findViewById(R.id.internal_audio_warning)
        segMic.setOnClickListener { selectMicrophone() }
        segInternal.setOnClickListener { selectInternalAudio() }
        segLocal.setOnClickListener { onLocalFileRequested() }

        ContextCompat.registerReceiver(
            activity,
            captureStopReceiver,
            IntentFilter(AudioCaptureService.ACTION_STOPPED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** Refresh the segment toggle to match the live source, then notify observers. */
    fun refreshSelection() {
        segMic.isSelected = source == Source.MIC
        segInternal.isSelected = source == Source.SYSTEM
        segLocal.isSelected = source == Source.LOCAL
        internalAudioWarning.visibility = if (source == Source.SYSTEM) View.VISIBLE else View.GONE
        onSourceChanged()
    }

    /**
     * Switches the live source to LOCAL: stops whichever capture path is
     * running so the playback session is the ring buffers' only producer.
     * Called by the host once a file has actually been picked.
     */
    fun enterLocalPlayback() {
        when (source) {
            Source.MIC -> NativeBridge.nativeStop()
            Source.SYSTEM ->
                activity.stopService(Intent(activity, AudioCaptureService::class.java))
            Source.LOCAL -> Unit   // replacing the current track
        }
        source = Source.LOCAL
        refreshSelection()
    }

    /**
     * Ends LOCAL mode. Playback always hands back to the microphone — a
     * stopped MediaProjection can't be resumed without re-consent, so falling
     * back to SYSTEM would just re-prompt the user out of nowhere.
     */
    fun exitLocalPlayback() {
        if (source != Source.LOCAL) return
        source = Source.MIC
        refreshSelection()
        evaluateMicState()
    }

    private fun selectMicrophone() {
        onExternalSourceRequested()
        if (source == Source.SYSTEM) {
            activity.stopService(Intent(activity, AudioCaptureService::class.java))
        }
        source = Source.MIC
        ensureMicAndStart()
        refreshSelection()
    }

    private fun selectInternalAudio() {
        if (source == Source.SYSTEM) return
        onExternalSourceRequested()
        // First time: explain why Android will ask for screen-capture permission
        // (internal audio is routed through the screen-capture API).
        if (prefs.getBoolean(KEY_SCREENSHARE_RATIONALE, false)) {
            requestSystemAudioCapture()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.screenshare_title)
            .setMessage(R.string.screenshare_message)
            .setPositiveButton(R.string.screenshare_continue) { _, _ ->
                prefs.edit { putBoolean(KEY_SCREENSHARE_RATIONALE, true) }
                requestSystemAudioCapture()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                // Any live local session was already stopped, so land on the mic.
                source = Source.MIC
                evaluateMicState()
                refreshSelection()
            }
            .show()
    }

    private fun buildPermissionList(): Array<String> {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
            perms += Manifest.permission.NEARBY_WIFI_DEVICES // Fallback for Android 13-16
        }
        if (Build.VERSION.SDK_INT >= 36) {
            perms += "android.permission.ACCESS_LOCAL_NETWORK" // Android 17+ strict requirement
        }

        return perms.toTypedArray()
    }

    private fun startMicrophone() {
        if (NativeBridge.nativeStartMicrophone()) {
            onMicStarted()
        } else {
            Toast.makeText(activity, "Failed to open audio stream.", Toast.LENGTH_LONG).show()
        }
        onSourceChanged()
    }

    private fun ensureMicAndStart() {
        if (hasMicPermission()) {
            startMicrophone()
        } else {
            launchMicPermissionWithRationale()
        }
    }

    /** Re-evaluate the mic stream after an external display attaches/detaches. */
    fun evaluateMicState() {
        if (source == Source.MIC) {
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                ensureMicAndStart()
            } else {
                NativeBridge.nativeStop()
            }
        }
    }

    private fun requestSystemAudioCapture() {
        val mpm = activity.getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun requestPermissionsNow() = launchMicPermissionWithRationale()

    private fun launchMicPermissionWithRationale() {
        if (prefs.getBoolean(KEY_MIC_RATIONALE, false)) {
            requestPermissions.launch(buildPermissionList())
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.mic_rationale_title)
            .setMessage(R.string.mic_rationale_message)
            .setPositiveButton(R.string.mic_rationale_continue) { _, _ ->
                prefs.edit { putBoolean(KEY_MIC_RATIONALE, true) }
                requestPermissions.launch(buildPermissionList())
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                refreshSelection()
            }
            .show()
    }

    /** Hold the runtime-permission dialog back until [fireDeferredPermissionRequest]. */
    fun deferPermissionRequest() { deferredPermissionRequest = true }

    /** Launch the deferred permission request exactly once. */
    fun fireDeferredPermissionRequest() {
        if (!deferredPermissionRequest) return
        deferredPermissionRequest = false
        launchMicPermissionWithRationale()
    }

    fun onResume() {
        // LOCAL owns its own stream (the host pauses/resumes the player);
        // SYSTEM keeps capturing via its foreground service.
        if (source == Source.MIC) ensureMicAndStart()
    }

    fun onPause() {
        if (source == Source.MIC) NativeBridge.nativeStop()
    }

    fun onDestroy() {
        activity.unregisterReceiver(captureStopReceiver)
    }

    companion object {
        private const val TAG = "AudioSourceController"
        private const val KEY_SCREENSHARE_RATIONALE = "screenshare_rationale_shown"
        private const val KEY_MIC_RATIONALE = "mic_rationale_shown"
    }
}
