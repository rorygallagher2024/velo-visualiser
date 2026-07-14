package com.lowlatency.visualizer.ui

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.lowlatency.visualizer.R

/**
 * Input-device selection for microphone mode: a row under the source toggle —
 * shown only while the mic is the live source AND a wired/USB input exists —
 * naming the current input, tap to choose between "Auto" and the externals.
 *
 * Selection is session-only (AudioDeviceInfo ids aren't stable across
 * reboots); "Auto" (id 0) follows Android's default routing, which already
 * prefers a freshly attached wired/USB device. Choosing a device explicitly
 * still matters: the engine opens an explicit device in stereo when it
 * supports it, feeding the phase-accurate scope scenes true L/R — e.g. a USB
 * interface carrying line-level audio.
 *
 * Bluetooth inputs are deliberately absent: SCO capture is narrowband and
 * high-latency — the worst source by every measure this app cares about.
 *
 * @param isMicMode whether the microphone is the live audio source.
 * @param onSelectionChanged invoked after the selection changes so the host
 *   can restart the input stream on the new device.
 */
class InputDeviceController(
    private val activity: AppCompatActivity,
    private val isMicMode: () -> Boolean,
    private val onSelectionChanged: () -> Unit,
) {
    /** AudioDeviceInfo.getId() of the chosen input; 0 = system default. */
    var selectedDeviceId = 0
        private set

    private lateinit var row: Button
    private val audioManager = activity.getSystemService(AudioManager::class.java)

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = refreshRow()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            if (removed.any { it.id == selectedDeviceId }) {
                // The chosen input vanished (unplugged): back to Auto.
                selectedDeviceId = 0
                Toast.makeText(activity, R.string.input_device_lost, Toast.LENGTH_SHORT).show()
                onSelectionChanged()
            }
            refreshRow()
        }
    }

    fun bind() {
        row = activity.findViewById(R.id.btn_input_device)
        row.setOnClickListener { showPicker() }
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        refreshRow()
    }

    fun onDestroy() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
    }

    /** Re-evaluate the row's visibility + label (source change, hotplug). */
    fun refreshRow() {
        if (!::row.isInitialized) return
        val externals = externalInputs()
        // Only surface the row when there's an actual choice to make.
        row.visibility = if (isMicMode() && externals.isNotEmpty()) View.VISIBLE else View.GONE
        row.text = activity.getString(R.string.input_device_row, currentLabel(externals))
    }

    private fun currentLabel(externals: List<AudioDeviceInfo>): String =
        externals.firstOrNull { it.id == selectedDeviceId }?.let { label(it) }
            ?: activity.getString(R.string.input_device_auto)

    private fun showPicker() {
        val externals = externalInputs()
        val labels = listOf(activity.getString(R.string.input_device_auto)) + externals.map { label(it) }
        val ids = listOf(0) + externals.map { it.id }
        val checked = ids.indexOf(selectedDeviceId).coerceAtLeast(0)
        AlertDialog.Builder(activity)
            .setTitle(R.string.input_device_title)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                if (ids[which] != selectedDeviceId) {
                    selectedDeviceId = ids[which]
                    refreshRow()
                    onSelectionChanged()
                }
            }
            .show()
    }

    /** Wired/USB inputs worth offering; built-in mics stay behind "Auto". */
    private fun externalInputs(): List<AudioDeviceInfo> =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.type in EXTERNAL_TYPES }
            .distinctBy { it.id }

    private fun label(device: AudioDeviceInfo): String = when (device.type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> activity.getString(R.string.input_device_wired)
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> activity.getString(R.string.input_device_line)
        // USB devices usually report a useful product name (e.g. "Wave:3").
        else -> device.productName?.toString()?.takeIf { it.isNotBlank() }
            ?: activity.getString(R.string.input_device_usb)
    }

    companion object {
        private val EXTERNAL_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_DOCK,
        )
    }
}
