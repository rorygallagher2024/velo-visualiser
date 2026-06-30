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
 * Audio-source switching: the mic ⇄ internal-audio segmented control plus all of
 * the engine and permission plumbing behind it.
 *
 * Two capture paths feed the same native ring buffer: a low-latency microphone
 * stream, and system/internal audio via the screen-capture API (which Android
 * gates behind a MediaProjection consent dialog + a foreground service). This
 * controller owns the runtime-permission launcher, the projection launcher, the
 * "capture stopped" broadcast receiver, the toggle UI, and the deferred-permission
 * handshake (held back until the GL intro finishes on first launch).
 *
 * Anything that merely *reacts* to the source — the lighting tab's availability,
 * beat haptics, the About dialog's engine line — observes [systemAudioMode] and is
 * refreshed via the [onSourceChanged] callback; this controller never reaches into
 * those concerns itself.
 *
 * The two `registerForActivityResult` launchers are registered in property
 * initialisers, so this controller must be constructed before the activity is
 * STARTED (it is — the host builds it in `onCreate`/`initControllers`).
 *
 * @param onSourceChanged invoked after every source transition so the host can
 *   refresh source-dependent UI (it reads [systemAudioMode]).
 */
class AudioSourceController(
    private val activity: AppCompatActivity,
    private val prefs: SharedPreferences,
    private val onSourceChanged: () -> Unit,
) {
    var systemAudioMode = false
        private set

    private var deferredPermissionRequest = false

    private lateinit var segMic: Button
    private lateinit var segInternal: Button
    private lateinit var internalAudioWarning: TextView

    private val captureStopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (systemAudioMode) selectMicrophone()
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
            systemAudioMode = true
        } else {
            Toast.makeText(activity, "System-audio capture denied.", Toast.LENGTH_SHORT).show()
            systemAudioMode = false
        }
        refreshSelection()
    }

    fun bind() {
        segMic = activity.findViewById(R.id.seg_mic)
        segInternal = activity.findViewById(R.id.seg_internal)
        internalAudioWarning = activity.findViewById(R.id.internal_audio_warning)
        segMic.setOnClickListener { selectMicrophone() }
        segInternal.setOnClickListener { selectInternalAudio() }

        ContextCompat.registerReceiver(
            activity,
            captureStopReceiver,
            IntentFilter(AudioCaptureService.ACTION_STOPPED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** Refresh the segment toggle to match the live source, then notify observers. */
    fun refreshSelection() {
        segMic.isSelected = !systemAudioMode
        segInternal.isSelected = systemAudioMode
        internalAudioWarning.visibility = if (systemAudioMode) View.VISIBLE else View.GONE
        onSourceChanged()
    }

    private fun selectMicrophone() {
        if (systemAudioMode) {
            activity.stopService(Intent(activity, AudioCaptureService::class.java))
            systemAudioMode = false
        }
        ensureMicAndStart()
        refreshSelection()
    }

    private fun selectInternalAudio() {
        if (systemAudioMode) return
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
                refreshSelection()   // stay on the mic toggle
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
        if (!NativeBridge.nativeStartMicrophone()) {
            Toast.makeText(activity, "Failed to open audio stream.", Toast.LENGTH_LONG).show()
        }
        onSourceChanged()
    }

    private fun ensureMicAndStart() {
        if (hasMicPermission()) {
            startMicrophone()
        } else {
            requestPermissions.launch(buildPermissionList())
        }
    }

    /** Re-evaluate the mic stream after an external display attaches/detaches. */
    fun evaluateMicState() {
        if (!systemAudioMode) {
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

    fun requestPermissionsNow() = requestPermissions.launch(buildPermissionList())

    /** Hold the runtime-permission dialog back until [fireDeferredPermissionRequest]. */
    fun deferPermissionRequest() { deferredPermissionRequest = true }

    /** Launch the deferred permission request exactly once. */
    fun fireDeferredPermissionRequest() {
        if (!deferredPermissionRequest) return
        deferredPermissionRequest = false
        requestPermissions.launch(buildPermissionList())
    }

    fun onResume() {
        if (!systemAudioMode) ensureMicAndStart()
    }

    fun onPause() {
        if (!systemAudioMode) NativeBridge.nativeStop()
    }

    fun onDestroy() {
        activity.unregisterReceiver(captureStopReceiver)
    }

    companion object {
        private const val TAG = "AudioSourceController"
        private const val KEY_SCREENSHARE_RATIONALE = "screenshare_rationale_shown"
    }
}
