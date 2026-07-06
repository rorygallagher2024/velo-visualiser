package com.lowlatency.visualizer.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.lowlatency.visualizer.BeatSettings
import com.lowlatency.visualizer.LightingSettings
import com.lowlatency.visualizer.LinkSync
import com.lowlatency.visualizer.R
import com.lowlatency.visualizer.hue.HueCredentialStore
import com.lowlatency.visualizer.hue.HueCredentials
import com.lowlatency.visualizer.hue.HueEntertainmentArea
import com.lowlatency.visualizer.hue.HueLightController
import com.lowlatency.visualizer.lifx.LifxController
import com.lowlatency.visualizer.nanoleaf.NanoleafController
import com.lowlatency.visualizer.ui.lighting.LightBrandPanel
import com.lowlatency.visualizer.ui.lighting.WledBrandPanel

/**
 * Owns the entire Lighting tab: the brand selector (Hue / LIFX / Nanoleaf), each
 * brand's connect/scan/sync panel, the shared connection-state chrome, the Hue
 * bridge ping poller, and the Advanced tuning dialog. The per-brand protocol
 * clients ([HueLightController], [LifxController], [NanoleafController]) are owned
 * here too, so audio frames and Link beats fan out to them from one place.
 *
 * [MainActivity] keeps the GL audio sinks (it also drives the beat/drop diagnostic
 * dots) and just forwards [onBands]/[onLinkBeat] plus the lifecycle. Extracted
 * from MainActivity as the second section controller; behaviour is a verbatim move.
 */
class LightingController(
    private val activity: AppCompatActivity,
    private val prefs: SharedPreferences,
    private val backgroundedAtMs: () -> Long,
) {

    lateinit var hueController: HueLightController
        private set
    private lateinit var lifxController: LifxController
    private lateinit var nanoleafController: NanoleafController
    private lateinit var hueStore: HueCredentialStore

    // New-style brand panels (behind LightBrandPanel). WLED is the first; the
    // original three brands are still inline above and can migrate here later.
    private val wledPanel: LightBrandPanel = WledBrandPanel(activity) { updateAdvancedVisibility() }

    private var hueAreas: List<HueEntertainmentArea> = emptyList()
    private var selectedArea: HueEntertainmentArea? = null
    private var activeBrand = LightingBrand.HUE
    private var lifxSyncing = false
    private var lastHueRttMs = -1L
    private var currentHueState = HueConn.DISCONNECTED

    private val huePingHandler = Handler(Looper.getMainLooper())
    private var huePingPollerRunning = false
    private var lifxPingPollerRunning = false
    private val uiHandler = Handler(Looper.getMainLooper())

    // ----- Views (bound in [bind]) -----
    private lateinit var btnAdvanced: Button
    private lateinit var btnLifxAdvanced: Button
    private lateinit var btnNanoleafAdvanced: Button
    private lateinit var btnHueConnect: Button
    private lateinit var hueAreaContainer: LinearLayout
    private lateinit var btnHueSync: Button
    private lateinit var hueStatus: TextView
    private lateinit var hueConn: TextView
    private lateinit var imgHueState: android.widget.ImageView
    private lateinit var hueStatusSpinner: ProgressBar
    private lateinit var btnHueForget: Button
    private lateinit var huePrerequisites: View
    private lateinit var hueAreaSection: LinearLayout
    private lateinit var hueSyncSection: LinearLayout
    private lateinit var btnBrandHue: Button
    private lateinit var btnBrandLifx: Button
    private lateinit var containerHue: View
    private lateinit var containerLifx: View
    private lateinit var btnLifxScan: Button
    private lateinit var lifxScanSpinner: ProgressBar
    private lateinit var lifxBulbContainer: android.widget.GridLayout
    private lateinit var btnLifxSync: Button
    private lateinit var tvLifxState: TextView
    private lateinit var imgLifxState: android.widget.ImageView
    private lateinit var tvLifxHint: TextView
    private lateinit var btnNanoleafSync: Button
    private lateinit var lightControlSection: LinearLayout
    private lateinit var sceneGrid: LinearLayout
    private lateinit var brightnessSlider: SeekBar

    // Shared cross-brand reactivity presets (the "Reactivity" section, shown when a
    // light is syncing). One choice drives every integration via LightingSettings.
    private lateinit var reactivitySection: LinearLayout
    private lateinit var btnReactSmooth: Button
    private lateinit var btnReactReactive: Button
    private lateinit var btnReactStrobe: Button

    private val huePingPoller = object : Runnable {
        override fun run() {
            val creds = hueStore.loadCredentials() ?: return
            hueController.setup.pingBridge(creds) { rtt ->
                if (!huePingPollerRunning) return@pingBridge
                if (rtt != null) {
                    lastHueRttMs = rtt
                    val state = if (hueController.isEnabled) HueConn.STREAMING else HueConn.REACHABLE
                    updateHueConn(state)
                    if (hueController.isEnabled) {
                        hueStatus.text = activity.getString(R.string.hue_status_synced)
                    } else if (hueStatus.text == activity.getString(R.string.hue_status_unreachable)) {
                        hueStatus.text = activity.getString(R.string.hue_status_ready)
                    }
                } else {
                    lastHueRttMs = -1L
                    if (!hueController.isEnabled) {
                        updateHueConn(HueConn.PAIRED)
                        hueStatus.text = activity.getString(R.string.hue_status_unreachable)
                    }
                }
                if (huePingPollerRunning) huePingHandler.postDelayed(this, 5000L)
            }
        }
    }

    private val lifxPingPoller = object : Runnable {
        override fun run() {
            if (!lifxPingPollerRunning) return
            
            if (::tvLifxState.isInitialized) {
                if (!isWifiConnected()) {
                    if (tvLifxState.text != "Disconnected") {
                        tvLifxState.text = "Disconnected"
                        tvLifxState.setTextColor(activity.getColor(R.color.text_dim))
                        imgLifxState.imageTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.hue_disconnected))
                        btnLifxSync.isEnabled = false
                    }
                } else {
                    if (tvLifxState.text == "Disconnected") {
                        val bulbCount = lifxBulbContainer.childCount
                        if (bulbCount == 0) {
                            tvLifxState.text = "Not Scanned"
                            tvLifxState.setTextColor(activity.getColor(R.color.hue_pending))
                            imgLifxState.imageTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.hue_pending))
                            btnLifxSync.isEnabled = false
                        } else {
                            if (lifxSyncing) {
                                tvLifxState.text = "Streaming Active"
                                tvLifxState.setTextColor(activity.getColor(R.color.hue_connected))
                                imgLifxState.imageTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.hue_connected))
                            } else {
                                tvLifxState.text = "Ready"
                                tvLifxState.setTextColor(activity.getColor(R.color.hue_connected))
                                imgLifxState.imageTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.hue_connected))
                            }
                            btnLifxSync.isEnabled = true
                        }
                    }
                }
                tvLifxHint.visibility = if (tvLifxState.text == "Ready" || tvLifxState.text == "Streaming Active") View.GONE else View.VISIBLE
            }
            if (lifxPingPollerRunning) huePingHandler.postDelayed(this, 3000L)
        }
    }

    // ----- Public API used by MainActivity -----

    /** Per-frame audio bands from the GL thread; fan out to every brand. */
    fun onBands(low: Float, mid: Float, high: Float) {
        if (::hueController.isInitialized) hueController.onBands(low, mid, high)
        lifxController.onBands(low, mid, high)
        nanoleafController.onBands(low, mid, high)
        wledPanel.onBands(low, mid, high)
    }

    /** Raw (ungated) Link beat from the GL thread; fan out to every brand. */
    fun onLinkBeat() {
        if (::hueController.isInitialized) hueController.onLinkBeat()
        lifxController.onLinkBeat()
        nanoleafController.onLinkBeat()
        wledPanel.onLinkBeat()
    }

    /** Re-evaluate which Advanced buttons are visible (called when Link mode flips). */
    fun refreshAdvancedVisibility() {
        if (::btnAdvanced.isInitialized) updateAdvancedVisibility()
    }

    /** Snapshot for the performance overlay's Hue row. */
    fun huePerfStats(): PerfOverlayController.HueStats =
        if (::hueController.isInitialized) {
            PerfOverlayController.HueStats(
                active = hueController.isEnabled,
                packetsSent = hueController.huePacketsSent,
                packetsFailed = hueController.huePacketsFailed,
                rttMs = lastHueRttMs,
            )
        } else {
            PerfOverlayController.HueStats(false, 0L, 0L, -1L)
        }

    /** Switching to system (internal) audio: light sync is mic-only, so stop it. */
    fun onSystemAudioEngaged() {
        if (::hueController.isInitialized && hueController.isEnabled) {
            hueController.disable(turnOff = true)
            updateHueSyncButton(false)
            updateHueConn(HueConn.REACHABLE)
            hueStatus.setText(R.string.hue_status_ready)
            updateHueSections()
        }
        if (lifxSyncing) {
            lifxController.disableStreaming()
            lifxSyncing = false
            btnLifxSync.setText(R.string.hue_sync_off)
            btnLifxSync.isSelected = false
            if (::tvLifxState.isInitialized) {
                if (lifxBulbContainer.childCount == 0) {
                    tvLifxState.text = "Not Scanned"
                    tvLifxState.setTextColor(activity.getColor(R.color.hue_pending))
                    imgLifxState.imageTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.hue_pending))
                } else {
                    tvLifxState.text = "Ready"
                    tvLifxState.setTextColor(activity.getColor(R.color.hue_connected))
                    imgLifxState.imageTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.hue_connected))
                }
                tvLifxHint.visibility = if (tvLifxState.text == "Ready") View.GONE else View.VISIBLE
            }
        }
        if (btnNanoleafSync.text == activity.getString(R.string.hue_sync_on)) {
            nanoleafController.stopSync()
            btnNanoleafSync.text = activity.getString(R.string.hue_sync_off)
            btnNanoleafSync.isSelected = false
        }
        wledPanel.stopForSystemAudio()
    }

    fun onResume() {
        if (::hueController.isInitialized) hueController.paused = false
        refreshHueAfterResume()
        if (huePingPollerRunning) {
            huePingHandler.removeCallbacks(huePingPoller)
            huePingHandler.post(huePingPoller)
        } else if (::hueStore.isInitialized && hueStore.loadCredentials() != null) {
            startHuePingPoller()
            if (!hueController.isEnabled) {
                val creds = hueStore.loadCredentials() ?: return
                hueController.setup.pingBridge(creds) { rtt ->
                    if (rtt != null) {
                        updateHueConn(HueConn.REACHABLE)
                        hueStatus.text = activity.getString(R.string.hue_status_ready)
                        if (hueAreas.isEmpty()) loadHueAreas()
                    } else {
                        updateHueConn(HueConn.PAIRED)
                    }
                }
            }
        }
        if (activeBrand == LightingBrand.NANOLEAF) {
            nanoleafController.startReachabilityPoller()
        } else if (activeBrand == LightingBrand.LIFX) {
            startLifxPingPoller()
        }
    }

    fun onPause() {
        if (::hueController.isInitialized) hueController.paused = true
        huePingHandler.removeCallbacks(huePingPoller)
        wledPanel.onPause()
        nanoleafController.stopReachabilityPoller()
        stopLifxPingPoller()
        if (::lifxScanSpinner.isInitialized) lifxScanSpinner.visibility = View.GONE
    }

    fun onDestroy() {
        stopHuePingPoller()
        if (::hueController.isInitialized) hueController.disable()
        wledPanel.onDestroy()
    }

    private fun isWifiConnected(): Boolean {
        val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val cap = cm.getNetworkCapabilities(cm.activeNetwork)
        return cap?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    /** Bind views, instantiate controllers, restore prefs, and wire every panel. */
    fun bind() {
        hueStore = HueCredentialStore(activity)
        hueController = HueLightController(activity)
        lifxController = LifxController()
        nanoleafController = NanoleafController(activity)

        // ----- View binding (moved from MainActivity.bindViews) -----
        btnAdvanced = activity.findViewById(R.id.btn_advanced)
        btnLifxAdvanced = activity.findViewById(R.id.btn_lifx_advanced)
        btnNanoleafAdvanced = activity.findViewById(R.id.btn_nanoleaf_advanced)
        btnHueConnect = activity.findViewById(R.id.btn_hue_connect)
        hueAreaContainer = activity.findViewById(R.id.hue_area_container)
        btnHueSync = activity.findViewById(R.id.btn_hue_sync)
        hueStatus = activity.findViewById(R.id.hue_status)
        val statusHue = activity.findViewById<View>(R.id.status_hue)
        hueConn = statusHue.findViewById(R.id.tv_state)
        imgHueState = statusHue.findViewById(R.id.img_state)
        hueStatusSpinner = statusHue.findViewById(R.id.status_spinner)
        btnHueForget = statusHue.findViewById(R.id.btn_forget)
        huePrerequisites = activity.findViewById(R.id.hue_prerequisites)
        hueAreaSection = activity.findViewById(R.id.hue_section_areas)
        hueSyncSection = activity.findViewById(R.id.hue_section_sync)

        btnBrandHue = activity.findViewById(R.id.btn_brand_hue)
        btnBrandLifx = activity.findViewById(R.id.btn_brand_lifx)
        containerHue = activity.findViewById(R.id.container_hue)
        containerLifx = activity.findViewById(R.id.container_lifx)
        btnLifxScan = activity.findViewById(R.id.btn_lifx_scan)
        lifxScanSpinner = activity.findViewById(R.id.lifx_scan_spinner)
        lifxBulbContainer = activity.findViewById(R.id.lifx_bulb_container)
        btnLifxSync = activity.findViewById(R.id.btn_lifx_sync)
        val statusLifx = activity.findViewById<View>(R.id.status_lifx)
        tvLifxState = statusLifx.findViewById(R.id.tv_state)
        imgLifxState = statusLifx.findViewById(R.id.img_state)
        tvLifxHint = activity.findViewById(R.id.tv_lifx_hint)
        tvLifxState.text = "Not Scanned"
        tvLifxState.setTextColor(activity.getColor(R.color.hue_pending))
        imgLifxState.imageTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.hue_pending))
        tvLifxHint.visibility = View.VISIBLE

        // Light-sync tuning (persisted). Colour/timing are restored individually;
        // the reactivity dynamics come from the saved preset (CUSTOM restores the
        // hand-tuned slider values). See LightingSettings.
        LightingSettings.linkBeatFlashEnabled = prefs.getBoolean(KEY_ADV_LINK_BEAT_FLASH, LightingSettings.linkBeatFlashEnabled)
        LightingSettings.colourSplit = prefs.getFloat(KEY_ADV_COLOUR, LightingSettings.colourSplit)
        LightingSettings.hueLookaheadMs = prefs.getFloat(KEY_ADV_HUE_LOOKAHEAD, LightingSettings.hueLookaheadMs)
        restorePreset()
        btnAdvanced.setOnClickListener { showAdvancedDialog() }
        btnLifxAdvanced.setOnClickListener { showAdvancedDialog() }
        btnNanoleafAdvanced.setOnClickListener { showAdvancedDialog() }

        btnHueConnect.setOnClickListener { onHueConnectClicked() }
        btnHueSync.setOnClickListener { onHueSyncToggle() }
        btnHueForget.setOnClickListener { confirmForgetBridge() }
        buildLightControlUI()
        wireReactivityPresets()

        btnBrandHue.setOnClickListener { switchBrand(LightingBrand.HUE) }
        btnBrandLifx.setOnClickListener { switchBrand(LightingBrand.LIFX) }
        activity.findViewById<View>(R.id.btn_brand_nanoleaf).setOnClickListener { switchBrand(LightingBrand.NANOLEAF) }

        // WLED panel (new-style LightBrandPanel).
        wledPanel.bind()
        wledPanel.selectorButton.setOnClickListener { switchBrand(LightingBrand.WLED) }
        wledPanel.advancedButton?.setOnClickListener { showAdvancedDialog() }

        switchBrand(LightingBrand.HUE)

        val lifxAutoOffRunnable = Runnable {
            if (lifxSyncing && !lifxController.hasSelectedBulbs()) {
                btnLifxSync.performClick()
                android.widget.Toast.makeText(activity, "LIFX Sync stopped (no bulbs selected)", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        btnLifxScan.setOnClickListener {
            if (!isWifiConnected()) {
                android.widget.Toast.makeText(activity, "Connect to Wi-Fi first", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifxScanSpinner.visibility = View.VISIBLE
            lifxBulbContainer.removeAllViews()
            btnLifxScan.isEnabled = false

            tvLifxState.text = "Searching network..."
            tvLifxState.setTextColor(activity.getColor(R.color.text_primary))
            imgLifxState.imageTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.hue_pending))
            tvLifxHint.visibility = View.VISIBLE

            lifxController.startDiscovery(
                onBulbFound = { bulb ->
                    activity.runOnUiThread {
                        val card = activity.layoutInflater.inflate(R.layout.lifx_bulb_item, lifxBulbContainer, false)
                        val bulbNameText = card.findViewById<TextView>(R.id.bulb_name)
                        bulbNameText.text = bulb.label

                        // The view starts unselected unless it was preserved during a rescan
                        card.isSelected = bulb.isSelected

                        // Add simple scale animation on touch/click
                        card.setOnClickListener { v ->
                            val isChecked = !v.isSelected
                            lifxController.setBulbSelected(bulb.ip, isChecked)
                            v.isSelected = isChecked

                            v.removeCallbacks(lifxAutoOffRunnable)
                            if (!lifxController.hasSelectedBulbs() && lifxSyncing) {
                                v.postDelayed(lifxAutoOffRunnable, 5000)
                            }

                            // Micro-animation "spring" bounce
                            v.animate()
                                .scaleX(0.95f).scaleY(0.95f)
                                .setDuration(50)
                                .withEndAction {
                                    v.animate()
                                        .scaleX(1.0f).scaleY(1.0f)
                                        .setDuration(150)
                                        .setInterpolator(android.view.animation.OvershootInterpolator(1.5f))
                                        .start()
                                }
                                .start()
                        }

                        lifxBulbContainer.addView(card)
                        btnLifxSync.isEnabled = true
                        btnLifxSync.setText(R.string.hue_sync_off)
                    }
                },
                onFinished = {
                    activity.runOnUiThread {
                        lifxScanSpinner.visibility = View.GONE
                        btnLifxScan.isEnabled = true
                        if (lifxBulbContainer.childCount == 0) {
                            tvLifxState.text = "No bulbs found"
                            tvLifxState.setTextColor(activity.getColor(R.color.text_destructive))
                            imgLifxState.imageTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.text_destructive))
                            android.widget.Toast.makeText(activity, "No LIFX bulbs found on network.", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            tvLifxState.text = "Ready"
                            tvLifxState.setTextColor(activity.getColor(R.color.hue_connected))
                            imgLifxState.imageTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.hue_connected))
                        }
                        tvLifxHint.visibility = if (tvLifxState.text == "Ready") View.GONE else View.VISIBLE
                    }
                }
            )
        }

        btnLifxSync.setOnClickListener {
            if (!isWifiConnected()) {
                android.widget.Toast.makeText(activity, "Connect to Wi-Fi first", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (lifxSyncing) {
                lifxController.disableStreaming()
                lifxSyncing = false
                btnLifxSync.setText(R.string.hue_sync_off)
                btnLifxSync.isSelected = false
                tvLifxState.text = "Ready"
                tvLifxHint.visibility = View.GONE
                updateAdvancedVisibility()
            } else {
                if (!lifxController.hasSelectedBulbs()) {
                    android.widget.Toast.makeText(activity, "Please select at least one bulb first.", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                lifxController.enableStreaming()
                lifxSyncing = true
                btnLifxSync.setText(R.string.hue_sync_on)
                btnLifxSync.isSelected = true

                tvLifxState.text = "Streaming Active"
                tvLifxState.setTextColor(activity.getColor(R.color.hue_connected))
                imgLifxState.imageTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.hue_connected))
                tvLifxHint.visibility = View.GONE
                updateAdvancedVisibility()
            }
        }

        // Nanoleaf UI
        val btnNanoleafScan = activity.findViewById<Button>(R.id.btn_nanoleaf_scan)
        btnNanoleafSync = activity.findViewById(R.id.btn_nanoleaf_sync)
        val statusNanoleaf = activity.findViewById<View>(R.id.status_nanoleaf)
        val tvNanoleafState = statusNanoleaf.findViewById<TextView>(R.id.tv_state)
        val imgNanoleafState = statusNanoleaf.findViewById<android.widget.ImageView>(R.id.img_state)
        val btnNanoleafForget = statusNanoleaf.findViewById<Button>(R.id.btn_forget)
        val tvNanoleafHint = activity.findViewById<TextView>(R.id.tv_nanoleaf_hint)

        btnNanoleafScan.setOnClickListener {
            if (!isWifiConnected()) {
                android.widget.Toast.makeText(activity, "Connect to Wi-Fi first", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            nanoleafController.search()
        }

        btnNanoleafSync.setOnClickListener {
            if (btnNanoleafSync.text == activity.getString(R.string.hue_sync_off)) {
                nanoleafController.startSync()
                btnNanoleafSync.text = activity.getString(R.string.hue_sync_on)
                btnNanoleafSync.isSelected = true
            } else {
                nanoleafController.stopSync()
                btnNanoleafSync.text = activity.getString(R.string.hue_sync_off)
                btnNanoleafSync.isSelected = false
            }
        }

        btnNanoleafForget.setOnClickListener {
            android.app.AlertDialog.Builder(activity)
                .setTitle("Forget Controller")
                .setMessage("Are you sure you want to disconnect and forget this Nanoleaf controller?")
                .setPositiveButton("Forget") { _, _ ->
                    nanoleafController.forget()
                    btnNanoleafSync.text = activity.getString(R.string.hue_sync_off)
                    btnNanoleafSync.isSelected = false
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        nanoleafController.stateListener = { state ->
            activity.runOnUiThread {
                when (state) {
                    NanoleafController.State.DISCONNECTED -> {
                        tvNanoleafState.text = "Disconnected"
                        tvNanoleafState.setTextColor(activity.getColor(R.color.text_dim))
                        imgNanoleafState.imageTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.hue_disconnected))
                        btnNanoleafScan.visibility = View.VISIBLE
                        btnNanoleafSync.text = activity.getString(R.string.hue_sync_off)
                        btnNanoleafSync.isSelected = false
                        btnNanoleafSync.isEnabled = false
                        btnNanoleafForget.visibility = View.GONE
                    }
                    NanoleafController.State.SEARCHING -> {
                        tvNanoleafState.text = "Searching network..."
                        tvNanoleafState.setTextColor(activity.getColor(R.color.text_primary))
                        imgNanoleafState.imageTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.hue_pending))
                    }
                    NanoleafController.State.FOUND_UNPAIRED -> {
                        tvNanoleafState.text = "Found. Hold power button for 5-7s until lights flash."
                        tvNanoleafState.setTextColor(activity.getColor(R.color.text_primary))
                        imgNanoleafState.imageTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.hue_pending))
                        btnNanoleafScan.visibility = View.GONE
                    }
                    NanoleafController.State.PAIRING -> {
                        tvNanoleafState.text = "Pairing..."
                    }
                    NanoleafController.State.PAIRED -> {
                        tvNanoleafState.text = "Ready"
                        tvNanoleafState.setTextColor(activity.getColor(R.color.hue_connected))
                        imgNanoleafState.imageTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.hue_connected))
                        btnNanoleafScan.visibility = View.GONE
                        btnNanoleafSync.text = activity.getString(R.string.hue_sync_off)
                        btnNanoleafSync.isSelected = false
                        btnNanoleafSync.isEnabled = true
                        btnNanoleafForget.visibility = View.VISIBLE
                    }
                    NanoleafController.State.STREAMING -> {
                        tvNanoleafState.text = "Streaming Active"
                        btnNanoleafSync.text = activity.getString(R.string.hue_sync_on)
                        btnNanoleafSync.isSelected = true
                        btnNanoleafSync.isEnabled = true
                    }
                    NanoleafController.State.ERROR -> {
                        tvNanoleafState.text = "Error Occurred"
                        tvNanoleafState.setTextColor(activity.getColor(R.color.text_destructive))
                        imgNanoleafState.imageTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.text_destructive))
                        btnNanoleafSync.text = activity.getString(R.string.hue_sync_off)
                        btnNanoleafSync.isSelected = false
                    }
                    NanoleafController.State.UNREACHABLE -> {
                        tvNanoleafState.text = "Paired · not reachable"
                        tvNanoleafState.setTextColor(activity.getColor(R.color.hue_pending))
                        imgNanoleafState.imageTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.hue_pending))
                        btnNanoleafScan.visibility = View.GONE
                        btnNanoleafSync.text = activity.getString(R.string.hue_sync_off)
                        btnNanoleafSync.isSelected = false
                        btnNanoleafSync.isEnabled = false
                        btnNanoleafForget.visibility = View.VISIBLE
                    }
                }

                tvNanoleafHint.visibility = if (state == NanoleafController.State.PAIRED || state == NanoleafController.State.STREAMING) View.GONE else View.VISIBLE
                updateAdvancedVisibility()
            }
        }

        val savedCreds = hueStore.loadCredentials()
        if (savedCreds != null) {
            huePrerequisites.visibility = View.GONE
            btnHueConnect.setText(R.string.hue_reconnect)
            btnHueForget.visibility = View.VISIBLE
            updateHueConn(HueConn.CHECKING)
            hueController.setup.pingBridge(savedCreds) { rtt ->
                if (rtt != null) {
                    updateHueConn(HueConn.REACHABLE)
                    hueStatus.text = activity.getString(R.string.hue_status_ready)
                    startHuePingPoller()
                    loadHueAreas()
                } else {
                    updateHueConn(HueConn.PAIRED)
                    hueStatus.text = activity.getString(R.string.hue_status_unreachable)
                }
            }
        } else {
            updateHueConn(HueConn.DISCONNECTED)
        }

        // Nothing is syncing yet, so the Advanced buttons start hidden.
        updateAdvancedVisibility()
    }

    private fun onHueConnectClicked() {
        val existing = hueStore.loadCredentials()
        if (existing != null) {
            updateHueConn(HueConn.CHECKING)
            hueStatus.text = ""
            hueController.setup.pingBridge(existing) { rtt ->
                if (rtt != null) {
                    updateHueConn(HueConn.REACHABLE)
                    hueStatus.text = activity.getString(R.string.hue_status_ready)
                    startHuePingPoller()
                    loadHueAreas()
                } else {
                    rediscoverBridge(existing)
                }
            }
            return
        }
        hueStatus.setText(R.string.hue_status_searching)
        updateHueConn(HueConn.SEARCHING)
        hueController.setup.discoverBridges { bridges ->
            val bridge = bridges.firstOrNull()
            if (bridge == null) {
                hueStatus.setText(R.string.hue_status_no_bridge)
                updateHueConn(HueConn.DISCONNECTED)
                return@discoverBridges
            }
            hueController.setup.pair(
                bridgeIp = bridge.ip,
                onCountdown = { s -> hueStatus.text = activity.getString(R.string.hue_status_press_button, s) },
                onSuccess = {
                    huePrerequisites.visibility = View.GONE
                    hueStatus.setText(R.string.hue_status_ready)
                    updateHueConn(HueConn.REACHABLE)
                    btnHueConnect.setText(R.string.hue_reconnect)
                    btnHueForget.visibility = View.VISIBLE
                    startHuePingPoller()
                    loadHueAreas()
                },
                onError = { msg ->
                    hueStatus.text = msg
                    updateHueConn(HueConn.DISCONNECTED)
                }
            )
        }
    }

    private fun confirmForgetBridge() {
        AlertDialog.Builder(activity)
            .setTitle(R.string.hue_forget_title)
            .setMessage(R.string.hue_forget_message)
            .setPositiveButton(android.R.string.ok) { _, _ -> forgetHueBridge() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun rediscoverBridge(oldCreds: HueCredentials) {
        updateHueConn(HueConn.SEARCHING)
        hueStatus.setText(R.string.hue_status_searching)
        hueController.setup.discoverBridges { bridges ->
            val bridge = bridges.firstOrNull()
            if (bridge == null) {
                updateHueConn(HueConn.PAIRED)
                hueStatus.text = activity.getString(R.string.hue_status_unreachable)
                return@discoverBridges
            }
            val updatedCreds = HueCredentials(bridge.ip, oldCreds.username, oldCreds.clientKey)
            hueStore.saveCredentials(updatedCreds)
            hueController.setup.pingBridge(updatedCreds) { rtt ->
                if (rtt != null) {
                    updateHueConn(HueConn.REACHABLE)
                    hueStatus.text = activity.getString(R.string.hue_status_ready)
                    startHuePingPoller()
                    loadHueAreas()
                } else {
                    updateHueConn(HueConn.PAIRED)
                    hueStatus.text = activity.getString(R.string.hue_status_unreachable)
                }
            }
        }
    }

    private fun forgetHueBridge() {
        if (::hueController.isInitialized && hueController.isEnabled) {
            hueController.disable(turnOff = true)
            updateHueSyncButton(false)
        }
        stopHuePingPoller()
        hueStore.clear()
        selectedArea = null
        hueAreas = emptyList()
        hueAreaContainer.removeAllViews()
        huePrerequisites.visibility = View.VISIBLE
        btnHueConnect.setText(R.string.hue_connect)
        btnHueSync.isEnabled = false
        btnHueForget.visibility = View.GONE
        updateHueConn(HueConn.DISCONNECTED)
        hueStatus.setText(R.string.hue_status_idle)
    }

    private fun loadHueAreas() {
        val creds = hueStore.loadCredentials() ?: return
        hueController.setup.listEntertainmentAreas(
            creds = creds,
            onResult = { areas -> showHueAreas(areas) },
            onError = { msg ->
                hueStatus.text = msg
                updateHueConn(HueConn.PAIRED)
                stopHuePingPoller()
            },
            onAuthError = {
                forgetHueBridge()
                hueStatus.setText(R.string.hue_status_auth_expired)
            },
        )
    }

    private fun showHueAreas(areas: List<HueEntertainmentArea>) {
        hueAreas = areas
        hueAreaContainer.removeAllViews()
        if (areas.isEmpty()) {
            hueStatus.text = activity.getString(R.string.hue_status_no_areas)
            updateHueSections()
            return
        }
        val h = activity.resources.displayMetrics.density * 48
        for (area in areas) {
            val label = if (area.channels.isNotEmpty())
                activity.getString(R.string.hue_area_lights, area.name, area.channels.size)
            else area.name
            val b = Button(activity).apply {
                text = label
                isAllCaps = false
                textSize = 13f
                setTextColor(ContextCompat.getColorStateList(activity, R.color.btn_text))
                setBackgroundResource(R.drawable.pill_button_bg)
                stateListAnimator = null
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, h.toInt()
                ).apply { bottomMargin = (activity.resources.displayMetrics.density * 8).toInt() }
                setOnClickListener { selectHueArea(area) }
            }
            hueAreaContainer.addView(b)
        }
        val saved = areas.firstOrNull { it.id == hueStore.selectedAreaId }
        if (saved != null) {
            selectHueArea(saved)
            hueStatus.text = activity.getString(R.string.hue_status_ready)
        } else {
            hueStatus.setText(R.string.hue_select_area)
            updateHueSections()
        }
    }

    private fun selectHueArea(area: HueEntertainmentArea) {
        selectedArea = area
        hueStore.selectedAreaId = area.id
        for (i in 0 until hueAreaContainer.childCount) {
            hueAreaContainer.getChildAt(i).isSelected = (hueAreas.getOrNull(i)?.id == area.id)
        }
        btnHueSync.isEnabled = true
        updateHueSections()
    }

    /**
     * Returning to the foreground: a light-sync stream that was running may have
     * silently died while away (Wi-Fi sleep ends the bridge's entertainment
     * session; UDP sends still "succeed" so the UI stays "synced"). If we were
     * away long enough for that to happen, rebuild the stream so the lights
     * actually respond again — and the UI reflects the true result.
     */
    private fun refreshHueAfterResume() {
        if (!::hueController.isInitialized || !hueController.isEnabled) return
        val area = selectedArea ?: return
        val away = SystemClock.elapsedRealtime() - backgroundedAtMs()
        if (backgroundedAtMs() == 0L || away < HUE_RESYNC_AWAY_MS) return  // quick glance: keepalive held

        btnHueSync.isEnabled = false
        hueStatus.setText(R.string.hue_status_ready)
        hueController.restart(area) { ok, err ->
            btnHueSync.isEnabled = true
            updateHueSyncButton(ok)
            updateHueConn(if (ok) HueConn.STREAMING else HueConn.REACHABLE)
            hueStatus.text = if (ok) activity.getString(R.string.hue_status_synced) else (err ?: activity.getString(R.string.hue_status_ready))
            updateHueSections()
        }
    }

    private fun onHueSyncToggle() {
        if (hueController.isEnabled) {
            hueController.disable(turnOff = true)
            updateHueSyncButton(false)
            updateHueConn(HueConn.REACHABLE)
            hueStatus.setText(R.string.hue_status_ready)
            updateHueSections()
            return
        }
        val area = selectedArea ?: run {
            hueStatus.setText(R.string.hue_select_area)
            return
        }
        btnHueSync.isEnabled = false
        hueController.enable(area) { ok, err ->
            btnHueSync.isEnabled = true
            updateHueSyncButton(ok)
            updateHueConn(if (ok) HueConn.STREAMING else HueConn.REACHABLE)
            hueStatus.text = if (ok) activity.getString(R.string.hue_status_synced) else (err ?: "Failed to sync.")
            updateHueSections()
        }
    }

    private fun updateHueSyncButton(on: Boolean) {
        btnHueSync.isSelected = on
        btnHueSync.setText(if (on) R.string.hue_sync_on else R.string.hue_sync_off)
        updateAdvancedVisibility()
    }

    /**
     * The Advanced panel tunes the lights (audio mode and Link mode both), so show
     * it whenever a brand is actively syncing. The panel adapts to whichever mode is on.
     */
    private fun updateAdvancedVisibility() {
        val hueRelevant = ::hueController.isInitialized && hueController.isEnabled
        btnAdvanced.visibility = if (hueRelevant) View.VISIBLE else View.GONE

        val lifxRelevant = ::lifxController.isInitialized && lifxController.isStreaming
        btnLifxAdvanced.visibility = if (lifxRelevant) View.VISIBLE else View.GONE

        val nanoRelevant = ::nanoleafController.isInitialized && nanoleafController.currentState == NanoleafController.State.STREAMING
        btnNanoleafAdvanced.visibility = if (nanoRelevant) View.VISIBLE else View.GONE

        wledPanel.advancedButton?.visibility = if (wledPanel.isSyncing) View.VISIBLE else View.GONE

        // The shared reactivity presets apply to whichever brand is live, so show
        // them whenever *any* integration is syncing.
        if (::reactivitySection.isInitialized) {
            reactivitySection.visibility = if (anySyncing()) View.VISIBLE else View.GONE
        }
    }

    /** True if any lighting integration is actively streaming to its hardware. */
    private fun anySyncing(): Boolean =
        (::hueController.isInitialized && hueController.isEnabled) ||
            (::lifxController.isInitialized && lifxController.isStreaming) ||
            (::nanoleafController.isInitialized &&
                nanoleafController.currentState == NanoleafController.State.STREAMING) ||
            wledPanel.isSyncing

    // ----- Shared reactivity presets -----

    private fun wireReactivityPresets() {
        reactivitySection = activity.findViewById(R.id.reactivity_section)
        btnReactSmooth = activity.findViewById(R.id.btn_react_smooth)
        btnReactReactive = activity.findViewById(R.id.btn_react_reactive)
        btnReactStrobe = activity.findViewById(R.id.btn_react_strobe)
        btnReactSmooth.setOnClickListener { setPreset(LightingSettings.Preset.SMOOTH) }
        btnReactReactive.setOnClickListener { setPreset(LightingSettings.Preset.REACTIVE) }
        btnReactStrobe.setOnClickListener { setPreset(LightingSettings.Preset.STROBE) }
        refreshPresetSelection()
    }

    /** Restore the saved preset (or CUSTOM's tuned values) into LightingSettings. */
    private fun restorePreset() {
        val saved = prefs.getString(KEY_LIGHTING_PRESET, LightingSettings.DEFAULT_PRESET.name)
        val preset = runCatching { LightingSettings.Preset.valueOf(saved ?: "") }
            .getOrDefault(LightingSettings.DEFAULT_PRESET)
        if (preset == LightingSettings.Preset.CUSTOM) {
            LightingSettings.restingGlow = prefs.getFloat(KEY_ADV_GLOW, LightingSettings.restingGlow)
            LightingSettings.audioBrightness = prefs.getFloat(KEY_ADV_AUDIO_BRIGHT, LightingSettings.audioBrightness)
            LightingSettings.audioFlash = prefs.getFloat(KEY_ADV_AUDIO_FLASH, LightingSettings.audioFlash)
            LightingSettings.markCustom()
        } else {
            LightingSettings.applyPreset(preset)
        }
    }

    private fun setPreset(p: LightingSettings.Preset) {
        LightingSettings.applyPreset(p)
        persistDynamics()
        refreshPresetSelection()
    }

    /** Persist the active preset + the dynamics values it resolves to (for CUSTOM). */
    private fun persistDynamics() {
        prefs.edit()
            .putString(KEY_LIGHTING_PRESET, LightingSettings.preset.name)
            .putFloat(KEY_ADV_GLOW, LightingSettings.restingGlow)
            .putFloat(KEY_ADV_AUDIO_BRIGHT, LightingSettings.audioBrightness)
            .putFloat(KEY_ADV_AUDIO_FLASH, LightingSettings.audioFlash)
            .apply()
    }

    /** Highlight the active preset button (none if the user has gone CUSTOM). */
    private fun refreshPresetSelection() {
        if (!::btnReactSmooth.isInitialized) return
        btnReactSmooth.isSelected = LightingSettings.preset == LightingSettings.Preset.SMOOTH
        btnReactReactive.isSelected = LightingSettings.preset == LightingSettings.Preset.REACTIVE
        btnReactStrobe.isSelected = LightingSettings.preset == LightingSettings.Preset.STROBE
    }

    /** An Advanced dynamics slider moved → keep the value, drop to CUSTOM. */
    private fun onDynamicsSliderChanged(key: String, value: Float) {
        prefs.edit().putFloat(key, value).putString(KEY_LIGHTING_PRESET, LightingSettings.Preset.CUSTOM.name).apply()
        LightingSettings.markCustom()
        refreshPresetSelection()
    }

    // ----- Light control (scene presets + brightness) -----

    private data class LightScene(
        val label: String, val dotColor: Int, val on: Boolean,
        val mirek: Int? = null, val x: Double? = null, val y: Double? = null,
    )

    private val lightScenes = listOf(
        LightScene("Off",        0xFF666666.toInt(), on = false),
        LightScene("Cool White", 0xFFD4E4FF.toInt(), on = true, mirek = 250),
        LightScene("Warm White", 0xFFFFDEA0.toInt(), on = true, mirek = 370),
        LightScene("Candlelight",0xFFFFB050.toInt(), on = true, mirek = 454),
        LightScene("Red",        0xFFFF3030.toInt(), on = true, x = 0.675, y = 0.322),
        LightScene("Blue",       0xFF3060FF.toInt(), on = true, x = 0.167, y = 0.04),
        LightScene("Green",      0xFF30DD60.toInt(), on = true, x = 0.2, y = 0.68),
        LightScene("Purple",     0xFF9030FF.toInt(), on = true, x = 0.27, y = 0.12),
    )

    private fun buildLightControlUI() {
        lightControlSection = activity.findViewById(R.id.hue_light_control)
        sceneGrid = activity.findViewById(R.id.hue_scene_grid)
        brightnessSlider = activity.findViewById(R.id.hue_brightness)

        val dp = activity.resources.displayMetrics.density
        val h = (dp * 48).toInt()
        val gap = (dp * 6).toInt()

        for (i in lightScenes.indices step 2) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = gap }
            }
            for (j in i until minOf(i + 2, lightScenes.size)) {
                val scene = lightScenes[j]
                val dot = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setSize((dp * 10).toInt(), (dp * 10).toInt())
                    // Derive a bright secondary color for the gradient to give a premium 3D/specular look
                    val hsv = FloatArray(3)
                    Color.colorToHSV(scene.dotColor, hsv)
                    hsv[1] = (hsv[1] * 0.6f).coerceAtLeast(0f)  // desaturate
                    hsv[2] = (hsv[2] * 1.2f).coerceAtMost(1f)   // brighten
                    val brightColor = Color.HSVToColor(hsv)
                    orientation = GradientDrawable.Orientation.TL_BR
                    colors = intArrayOf(brightColor, scene.dotColor)
                }
                val btn = Button(activity).apply {
                    text = scene.label
                    isAllCaps = false
                    textSize = 12f
                    setTextColor(ContextCompat.getColorStateList(activity, R.color.btn_text))
                    setBackgroundResource(R.drawable.pill_button_bg)
                    stateListAnimator = null
                    setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
                    compoundDrawablePadding = (dp * 8).toInt()
                    setPaddingRelative((dp * 16).toInt(), 0, (dp * 12).toInt(), 0)
                    layoutParams = LinearLayout.LayoutParams(0, h, 1f).apply {
                        if (j == i) marginEnd = gap / 2 else marginStart = gap / 2
                    }
                    setOnClickListener { applyLightScene(scene) }
                }
                row.addView(btn)
            }
            sceneGrid.addView(row)
        }

        brightnessSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {
                val area = selectedArea ?: return
                val creds = hueStore.loadCredentials() ?: return
                val bri = s.progress.coerceIn(1, 100)
                hueController.setup.controlLights(creds, area.lightIds, on = true, brightness = bri)
            }
        })
    }

    private fun applyLightScene(scene: LightScene) {
        val area = selectedArea ?: return
        val creds = hueStore.loadCredentials() ?: return
        if (!scene.on) {
            hueController.setup.controlLights(creds, area.lightIds, on = false)
        } else {
            hueController.setup.controlLights(
                creds, area.lightIds,
                on = true,
                brightness = brightnessSlider.progress.coerceIn(1, 100),
                mirek = scene.mirek, x = scene.x, y = scene.y,
            )
        }
    }

    private fun setViewGroupEnabled(viewGroup: ViewGroup, enabled: Boolean) {
        viewGroup.isEnabled = enabled
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            child.isEnabled = enabled
            if (child is ViewGroup) {
                setViewGroupEnabled(child, enabled)
            }
        }
    }

    private fun updateHueSections() {
        if (!::lightControlSection.isInitialized) return
        val reachable = currentHueState == HueConn.REACHABLE || currentHueState == HueConn.STREAMING
        val isStreaming = currentHueState == HueConn.STREAMING
        val hasSelected = reachable && selectedArea != null

        // Areas section is hidden entirely during sync; otherwise visible (ghosted if unreachable)
        hueAreaSection.visibility = if (isStreaming) View.GONE else View.VISIBLE
        lightControlSection.visibility = View.VISIBLE
        hueSyncSection.visibility = View.VISIBLE

        hueAreaSection.alpha = if (reachable) 1.0f else 0.3f
        setViewGroupEnabled(hueAreaSection, reachable)

        // Light control is enabled if an area is selected AND we're not currently streaming
        val controlEnabled = hasSelected && currentHueState == HueConn.REACHABLE
        lightControlSection.alpha = if (controlEnabled) 1.0f else 0.3f
        setViewGroupEnabled(lightControlSection, controlEnabled)

        hueSyncSection.alpha = if (hasSelected) 1.0f else 0.3f
        setViewGroupEnabled(hueSyncSection, hasSelected)
    }

    /** Update the colored connection-state dot + label in the Lighting tab. */
    private fun updateHueConn(state: HueConn) {
        currentHueState = state
        val colorRes: Int
        when (state) {
            HueConn.DISCONNECTED -> {
                hueConn.setText(R.string.hue_conn_disconnected)
                colorRes = R.color.hue_disconnected
            }
            HueConn.SEARCHING -> {
                hueConn.setText(R.string.hue_conn_searching)
                colorRes = R.color.hue_pending
            }
            HueConn.CHECKING -> {
                hueConn.setText(R.string.hue_conn_checking)
                colorRes = R.color.hue_pending
            }
            HueConn.PAIRED -> {
                hueConn.setText(R.string.hue_conn_paired)
                colorRes = R.color.hue_pending
            }
            HueConn.REACHABLE -> {
                hueConn.setText(R.string.hue_conn_reachable)
                colorRes = R.color.hue_connected
            }
            HueConn.STREAMING -> {
                hueConn.setText(R.string.hue_conn_streaming)
                colorRes = R.color.hue_connected
            }
        }
        val resolvedColor = ContextCompat.getColor(activity, colorRes)
        hueConn.setTextColor(resolvedColor)
        imgHueState.imageTintList = android.content.res.ColorStateList.valueOf(resolvedColor)

        when (state) {
            HueConn.DISCONNECTED -> {
                btnHueConnect.visibility = View.VISIBLE
                btnHueConnect.isEnabled = true
                hueStatusSpinner.visibility = View.GONE
            }
            HueConn.SEARCHING, HueConn.CHECKING -> {
                btnHueConnect.visibility = View.VISIBLE
                btnHueConnect.isEnabled = false
                hueStatusSpinner.visibility = View.VISIBLE
            }
            HueConn.PAIRED -> {
                btnHueConnect.visibility = View.VISIBLE
                btnHueConnect.isEnabled = true
                // Keep spinning if we are in the "Press button" countdown
                hueStatusSpinner.visibility = if (hueStatus.text.contains("Press")) View.VISIBLE else View.GONE
            }
            HueConn.REACHABLE, HueConn.STREAMING -> {
                btnHueConnect.visibility = View.GONE
                hueStatusSpinner.visibility = View.GONE
            }
        }

        updateHueSections()
    }

    private fun startHuePingPoller() {
        if (huePingPollerRunning) return
        huePingPollerRunning = true
        huePingHandler.postDelayed(huePingPoller, 5000L)
    }

    private fun stopHuePingPoller() {
        huePingPollerRunning = false
        huePingHandler.removeCallbacks(huePingPoller)
    }

    private fun startLifxPingPoller() {
        if (lifxPingPollerRunning) return
        lifxPingPollerRunning = true
        huePingHandler.post(lifxPingPoller)
    }

    private fun stopLifxPingPoller() {
        lifxPingPollerRunning = false
        huePingHandler.removeCallbacks(lifxPingPoller)
    }

    private fun switchBrand(brand: LightingBrand) {
        activeBrand = brand
        if (::lifxScanSpinner.isInitialized) lifxScanSpinner.visibility = View.GONE

        val btnBrandNanoleaf = activity.findViewById<Button>(R.id.btn_brand_nanoleaf)
        val containerNanoleaf = activity.findViewById<View>(R.id.container_nanoleaf)

        btnBrandHue.isSelected = (brand == LightingBrand.HUE)
        btnBrandLifx.isSelected = (brand == LightingBrand.LIFX)
        btnBrandNanoleaf.isSelected = (brand == LightingBrand.NANOLEAF)
        wledPanel.selectorButton.isSelected = (brand == LightingBrand.WLED)

        containerHue.visibility = if (brand == LightingBrand.HUE) View.VISIBLE else View.GONE
        containerLifx.visibility = if (brand == LightingBrand.LIFX) View.VISIBLE else View.GONE
        containerNanoleaf.visibility = if (brand == LightingBrand.NANOLEAF) View.VISIBLE else View.GONE
        wledPanel.container.visibility = if (brand == LightingBrand.WLED) View.VISIBLE else View.GONE

        if (brand == LightingBrand.NANOLEAF) {
            nanoleafController.startReachabilityPoller()
        } else {
            nanoleafController.stopReachabilityPoller()
        }
        
        if (brand == LightingBrand.LIFX) {
            startLifxPingPoller()
        } else {
            stopLifxPingPoller()
        }
    }

    // ----- Advanced tuning dialog -----

    private fun showAdvancedDialog() {
        val view = activity.layoutInflater.inflate(R.layout.dialog_advanced, null)
        val linkMode = LinkSync.enabled

        view.findViewById<TextView>(R.id.adv_title_text)
            .setText(if (linkMode) R.string.adv_title_link else R.string.adv_title_audio)
        view.findViewById<TextView>(R.id.adv_hint_text)
            .setText(if (linkMode) R.string.adv_hint_link else R.string.adv_hint_audio)
        view.findViewById<View>(R.id.group_link).visibility = if (linkMode) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.group_audio).visibility = if (linkMode) View.GONE else View.VISIBLE

        val btnLinkBeatFlash = view.findViewById<Button>(R.id.btn_link_beat_flash)
        val seekColour = view.findViewById<SeekBar>(R.id.seek_colour)
        val seekGlow = view.findViewById<SeekBar>(R.id.seek_glow)
        val seekAudioBright = view.findViewById<SeekBar>(R.id.seek_audio_brightness)
        val seekAudioFlash = view.findViewById<SeekBar>(R.id.seek_audio_flash)

        val seekLookahead = view.findViewById<SeekBar>(R.id.seek_lookahead)
        val labelLookahead = view.findViewById<TextView>(R.id.label_lookahead_value)

        fun applyLinkBeatFlashLabel() {
            btnLinkBeatFlash.setText(
                if (LightingSettings.linkBeatFlashEnabled) R.string.adv_link_beat_on
                else R.string.adv_link_beat_off
            )
        }

        fun applyPositions() {
            applyLinkBeatFlashLabel()
            seekColour.progress = (LightingSettings.colourSplit * 100f).toInt()
            seekGlow.progress = (LightingSettings.restingGlow * 100f).toInt()
            seekAudioBright.progress = (LightingSettings.audioBrightness * 100f).toInt()
            seekAudioFlash.progress = (LightingSettings.audioFlash * 100f).toInt()
            seekLookahead.progress = LightingSettings.hueLookaheadMs.toInt()
            labelLookahead.text = if (seekLookahead.progress == 0) "Off" else "-${seekLookahead.progress} ms"
        }
        applyPositions()

        btnLinkBeatFlash.setOnClickListener {
            val enabled = !LightingSettings.linkBeatFlashEnabled
            LightingSettings.linkBeatFlashEnabled = enabled
            prefs.edit().putBoolean(KEY_ADV_LINK_BEAT_FLASH, enabled).apply()
            applyLinkBeatFlashLabel()
        }
        // colourSplit is orthogonal to the reactivity dynamics — it doesn't flip the
        // preset. The three dynamics sliders do (they make the bundle CUSTOM).
        seekColour.onProgress { v -> LightingSettings.colourSplit = v; prefs.edit().putFloat(KEY_ADV_COLOUR, v).apply() }
        seekGlow.onProgress { v -> LightingSettings.restingGlow = v; onDynamicsSliderChanged(KEY_ADV_GLOW, v) }
        seekAudioBright.onProgress { v -> LightingSettings.audioBrightness = v; onDynamicsSliderChanged(KEY_ADV_AUDIO_BRIGHT, v) }
        seekAudioFlash.onProgress { v -> LightingSettings.audioFlash = v; onDynamicsSliderChanged(KEY_ADV_AUDIO_FLASH, v) }
        seekLookahead.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    LightingSettings.hueLookaheadMs = progress.toFloat()
                    labelLookahead.text = if (progress == 0) "Off" else "-${progress} ms"
                    prefs.edit().putFloat(KEY_ADV_HUE_LOOKAHEAD, progress.toFloat()).apply()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        val dialog = AlertDialog.Builder(activity).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        view.findViewById<Button>(R.id.btn_adv_done).setOnClickListener { dialog.dismiss() }
        view.findViewById<Button>(R.id.btn_adv_reset).setOnClickListener {
            LightingSettings.resetToDefaults()
            persistAdvanced()
            applyPositions()
            refreshPresetSelection()
        }

        // Live diagnostics poll. The "Beat → lights" dot flashes whenever a beat
        // is actually pushed to the bulbs (either mode); the mic/bass meters read
        // the shared BeatBus and apply in both modes. The bass marker is the Link
        // colour-split point, so it's hidden in audio mode.
        val lightsDot = view.findViewById<View>(R.id.lights_dot)
        val meterLevel = view.findViewById<ProgressBar>(R.id.meter_level)
        val meterBass = view.findViewById<ProgressBar>(R.id.meter_bass)
        val markerLevel = view.findViewById<View>(R.id.marker_level)
        val markerBass = view.findViewById<View>(R.id.marker_bass)
        markerBass.visibility = if (linkMode) View.VISIBLE else View.INVISIBLE
        var lastLightBeat = hueController.lightBeatCount
        val poll = object : Runnable {
            override fun run() {
                val lc = hueController.lightBeatCount
                if (lc != lastLightBeat) { lastLightBeat = lc; flashDot(lightsDot) }
                // Scale the level bar so the mic peak that reaches FULL brightness
                // sits at the top; the trigger point then sits at levelBase/levelFull,
                // marked by the line. The gate is the global Beat Sensitivity
                // (shared with the visuals), so it applies in both modes.
                val full = BeatSettings.levelFull.coerceAtLeast(1e-4f)
                meterLevel.progress =
                    (hueController.currentMicLevel / full * 100f).toInt().coerceIn(0, 100)
                meterBass.progress = (hueController.currentBassRatio * 100f).toInt().coerceIn(0, 100)
                val triggerFrac = (BeatSettings.levelBase / full).coerceIn(0f, 1f)
                markerLevel.translationX = triggerFrac * (meterLevel.width - markerLevel.width)
                if (linkMode) {
                    val splitMid = ((LightingSettings.bassLo + LightingSettings.bassHi) * 0.5f).coerceIn(0f, 1f)
                    markerBass.translationX = splitMid * (meterBass.width - markerBass.width)
                }
                uiHandler.postDelayed(this, 50L)
            }
        }
        dialog.setOnDismissListener { uiHandler.removeCallbacks(poll) }
        dialog.show()
        uiHandler.post(poll)
    }

    /** Persist every Advanced lighting value (used after a reset). */
    private fun persistAdvanced() {
        prefs.edit()
            .putBoolean(KEY_ADV_LINK_BEAT_FLASH, LightingSettings.linkBeatFlashEnabled)
            .putFloat(KEY_ADV_COLOUR, LightingSettings.colourSplit)
            .putFloat(KEY_ADV_GLOW, LightingSettings.restingGlow)
            .putFloat(KEY_ADV_AUDIO_BRIGHT, LightingSettings.audioBrightness)
            .putFloat(KEY_ADV_AUDIO_FLASH, LightingSettings.audioFlash)
            .putFloat(KEY_ADV_HUE_LOOKAHEAD, LightingSettings.hueLookaheadMs)
            .putString(KEY_LIGHTING_PRESET, LightingSettings.preset.name)
            .apply()
    }

    /** Pulse a diagnostic indicator light: snap bright + slightly larger, then ease back. */
    private fun flashDot(dot: View) {
        dot.animate().cancel()
        dot.alpha = 1f
        dot.scaleX = 1.35f
        dot.scaleY = 1.35f
        dot.animate().alpha(0.18f).scaleX(1f).scaleY(1f).setDuration(170L).start()
    }

    /** Concise SeekBar listener that reports the 0..1 position on user changes. */
    private inline fun SeekBar.onProgress(crossinline action: (Float) -> Unit) {
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) action(p / 100f)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    enum class LightingBrand { HUE, LIFX, NANOLEAF, WLED }
    private enum class HueConn { DISCONNECTED, SEARCHING, CHECKING, PAIRED, REACHABLE, STREAMING }

    companion object {
        private const val KEY_ADV_LINK_BEAT_FLASH = "adv_link_beat_flash"
        private const val KEY_ADV_COLOUR = "adv_colour_split"
        private const val KEY_ADV_GLOW = "adv_resting_glow"
        private const val KEY_ADV_AUDIO_BRIGHT = "adv_audio_brightness"
        private const val KEY_ADV_AUDIO_FLASH = "adv_audio_flash"
        private const val KEY_ADV_HUE_LOOKAHEAD = "adv_hue_lookahead"
        private const val KEY_LIGHTING_PRESET = "lighting_preset"
        private const val HUE_RESYNC_AWAY_MS = 3000L
    }
}
