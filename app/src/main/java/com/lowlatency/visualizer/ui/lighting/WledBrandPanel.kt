package com.lowlatency.visualizer.ui.lighting

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.lowlatency.visualizer.R
import com.lowlatency.visualizer.wled.WledController

/**
 * WLED brand panel: mDNS discovery of WLED devices, multi-select, and a
 * realtime DRGB UDP audio sync. First implementation of [LightBrandPanel] — the
 * template the remaining brands (and Govee) can follow.
 *
 * NOTE: shipped UNVERIFIED — built without a WLED device to test against.
 */
class WledBrandPanel(
    private val activity: AppCompatActivity,
    private val onSyncStateChanged: () -> Unit,
) : LightBrandPanel {

    private val wledController = WledController(activity)
    private var syncing = false

    private lateinit var containerView: View
    private lateinit var selectorBtn: Button
    private lateinit var advancedBtn: Button
    private lateinit var btnScan: Button
    private lateinit var scanSpinner: ProgressBar
    private lateinit var deviceContainer: LinearLayout
    private lateinit var btnSync: Button
    private lateinit var tvState: TextView
    private lateinit var imgState: android.widget.ImageView

    override val container: View get() = containerView
    override val selectorButton: Button get() = selectorBtn
    override val advancedButton: Button get() = advancedBtn
    override val isSyncing: Boolean get() = wledController.isStreaming

    override fun bind() {
        containerView = activity.findViewById(R.id.container_wled)
        selectorBtn = activity.findViewById(R.id.btn_brand_wled)
        advancedBtn = activity.findViewById(R.id.btn_wled_advanced)
        btnScan = activity.findViewById(R.id.btn_wled_scan)
        scanSpinner = activity.findViewById(R.id.wled_scan_spinner)
        deviceContainer = activity.findViewById(R.id.wled_device_container)
        btnSync = activity.findViewById(R.id.btn_wled_sync)
        val statusWled = activity.findViewById<View>(R.id.status_wled)
        tvState = statusWled.findViewById(R.id.tv_state)
        imgState = statusWled.findViewById(R.id.img_state)
        tvState.text = "Not Scanned"

        btnScan.setOnClickListener { startScan() }
        btnSync.setOnClickListener { toggleSync() }
    }

    private fun startScan() {
        if (!isWifiConnected()) {
            Toast.makeText(activity, "Connect to Wi-Fi first", Toast.LENGTH_SHORT).show()
            return
        }
        scanSpinner.visibility = View.VISIBLE
        deviceContainer.removeAllViews()
        btnScan.isEnabled = false
        btnSync.isEnabled = false

        tvState.text = "Searching network..."
        tvState.setTextColor(activity.getColor(R.color.text_primary))
        imgState.imageTintList = ColorStateList.valueOf(activity.getColor(R.color.hue_pending))

        wledController.startDiscovery(
            onDeviceFound = { device ->
                activity.runOnUiThread {
                    addDeviceRow(device.name, device.ip.hostAddress ?: return@runOnUiThread)
                    btnSync.isEnabled = true
                    btnSync.setText(R.string.hue_sync_off)
                }
            },
            onFinished = {
                activity.runOnUiThread {
                    scanSpinner.visibility = View.GONE
                    btnScan.isEnabled = true
                    if (deviceContainer.childCount == 0) {
                        tvState.text = "No devices found"
                        tvState.setTextColor(activity.getColor(R.color.text_destructive))
                        imgState.imageTintList = ColorStateList.valueOf(activity.getColor(R.color.text_destructive))
                        Toast.makeText(activity, "No WLED devices found on network.", Toast.LENGTH_SHORT).show()
                    } else {
                        tvState.text = "Ready"
                        tvState.setTextColor(activity.getColor(R.color.hue_connected))
                        imgState.imageTintList = ColorStateList.valueOf(activity.getColor(R.color.hue_connected))
                    }
                }
            }
        )
    }

    private fun addDeviceRow(name: String, ip: String) {
        val dp = activity.resources.displayMetrics.density
        val row = Button(activity).apply {
            text = name
            isAllCaps = false
            textSize = 13f
            setTextColor(ContextCompat.getColorStateList(activity, R.color.btn_text))
            setBackgroundResource(R.drawable.pill_button_bg)
            stateListAnimator = null
            isSelected = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (dp * 44).toInt()
            ).apply { bottomMargin = (dp * 8).toInt() }
            setOnClickListener {
                val checked = !isSelected
                wledController.setDeviceSelected(ip, checked)
                isSelected = checked
            }
        }
        deviceContainer.addView(row)
    }

    private fun toggleSync() {
        if (!isWifiConnected()) {
            Toast.makeText(activity, "Connect to Wi-Fi first", Toast.LENGTH_SHORT).show()
            return
        }
        if (syncing) {
            wledController.disableStreaming()
            syncing = false
            btnSync.setText(R.string.hue_sync_off)
            btnSync.isSelected = false
            tvState.text = "Ready"
        } else {
            if (!wledController.hasSelectedDevices()) {
                Toast.makeText(activity, "Please select at least one device first.", Toast.LENGTH_SHORT).show()
                return
            }
            wledController.enableStreaming()
            syncing = true
            btnSync.setText(R.string.hue_sync_on)
            btnSync.isSelected = true
            tvState.text = "Streaming Active"
            tvState.setTextColor(activity.getColor(R.color.hue_connected))
            imgState.imageTintList = ColorStateList.valueOf(activity.getColor(R.color.hue_connected))
        }
        onSyncStateChanged()
    }

    override fun onBands(low: Float, mid: Float, high: Float) = wledController.onBands(low, mid, high)

    override fun onLinkBeat() = wledController.onLinkBeat()

    override fun stopForSystemAudio() {
        if (wledController.isStreaming) {
            wledController.disableStreaming()
            syncing = false
            if (::btnSync.isInitialized) {
                btnSync.setText(R.string.hue_sync_off)
                btnSync.isSelected = false
                tvState.text = "Ready"
            }
        }
    }

    override fun onDestroy() {
        wledController.disableStreaming()
    }

    private fun isWifiConnected(): Boolean {
        val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val cap = cm.getNetworkCapabilities(cm.activeNetwork)
        return cap?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
    }
}
