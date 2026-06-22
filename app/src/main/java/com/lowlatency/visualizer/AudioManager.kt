package com.lowlatency.visualizer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class AudioManager(private val activity: MainActivity) {

    private lateinit var segMic: Button
    private lateinit var segInternal: Button
    private lateinit var internalAudioWarning: TextView

    var systemAudioMode = false
        private set
    
    var deferredPermissionRequest = false

    lateinit var requestPermissions: ActivityResultLauncher<Array<String>>
    lateinit var projectionLauncher: ActivityResultLauncher<Intent>

    fun bindViews(root: View) {
        segMic = root.findViewById(R.id.seg_mic)
        segInternal = root.findViewById(R.id.seg_internal)
        internalAudioWarning = root.findViewById(R.id.internal_audio_warning)
    }

    fun setup() {
        segMic.setOnClickListener { selectMicrophone() }
        segInternal.setOnClickListener { selectInternalAudio() }
        
        requestPermissions = activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { grants ->
            if (grants[Manifest.permission.RECORD_AUDIO] == true) {
                startMicrophone()
            } else {
                Toast.makeText(activity, "Microphone permission required.", Toast.LENGTH_LONG).show()
            }

            if (Build.VERSION.SDK_INT >= 36) {
                if (grants["android.permission.ACCESS_LOCAL_NETWORK"] == true) {
                    Log.d(TAG, "Android 17 Local Network permission GRANTED.")
                } else {
                    Log.e(TAG, "Android 17 Local Network permission DENIED. Hue will timeout.")
                }
            }
        }

        projectionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val data = result.data
            if (result.resultCode == AppCompatActivity.RESULT_OK && data != null) {
                NativeBridge.nativeStop()
                activity.startForegroundService(
                    AudioCaptureService.newIntent(activity, result.resultCode, data)
                )
                systemAudioMode = true
                
                val hueManager = activity.hueManager
                if (hueManager.hueController.isEnabled) {
                    hueManager.hueController.disable()
                    hueManager.updateHueSyncButton(false)
                    hueManager.updateHueConn(HueManager.HueConn.REACHABLE)
                    activity.findViewById<TextView>(R.id.hue_status).setText(R.string.hue_mic_only)
                }
            } else {
                Toast.makeText(activity, "System-audio capture denied.", Toast.LENGTH_SHORT).show()
                systemAudioMode = false
            }
            activity.updateSourceSelection()
            activity.updateStatus()
        }
    }

    fun selectMicrophone() {
        if (systemAudioMode) {
            activity.stopService(Intent(activity, AudioCaptureService::class.java))
            systemAudioMode = false
        }
        ensureMicAndStart()
        activity.updateSourceSelection()
        activity.updateStatus()
    }

    private fun selectInternalAudio() {
        if (systemAudioMode) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SCREENSHARE_RATIONALE, false)) {
            requestSystemAudioCapture()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.screenshare_title)
            .setMessage(R.string.screenshare_message)
            .setPositiveButton(R.string.screenshare_continue) { _, _ ->
                prefs.edit().putBoolean(KEY_SCREENSHARE_RATIONALE, true).apply()
                requestSystemAudioCapture()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                activity.updateSourceSelection()
            }
            .show()
    }

    private fun requestSystemAudioCapture() {
        val mpm = activity.getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    fun startMicrophone() {
        if (!NativeBridge.nativeStartMicrophone()) {
            Toast.makeText(activity, "Failed to open audio stream.", Toast.LENGTH_LONG).show()
        }
        activity.updateStatus()
    }

    fun ensureMicAndStart() {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startMicrophone()
        } else {
            requestPermissions.launch(buildPermissionList())
        }
    }

    fun buildPermissionList(): Array<String> {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
            perms += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        if (Build.VERSION.SDK_INT >= 36) {
            perms += "android.permission.ACCESS_LOCAL_NETWORK"
        }
        return perms.toTypedArray()
    }

    fun fireDeferredPermissionRequest() {
        if (!deferredPermissionRequest) return
        deferredPermissionRequest = false
        requestPermissions.launch(buildPermissionList())
    }

    fun updateSourceSelection() {
        segMic.isSelected = !systemAudioMode
        segInternal.isSelected = systemAudioMode
        internalAudioWarning.visibility = if (systemAudioMode) View.VISIBLE else View.GONE
    }

    companion object {
        private const val TAG = "AudioManager"
        const val PREFS = "visualizer_prefs"
        const val KEY_SCREENSHARE_RATIONALE = "screenshare_rationale_shown"
    }
}
