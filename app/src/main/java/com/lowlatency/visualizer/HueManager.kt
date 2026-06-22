package com.lowlatency.visualizer

import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.lowlatency.visualizer.hue.HueCredentialStore
import com.lowlatency.visualizer.hue.HueCredentials
import com.lowlatency.visualizer.hue.HueEntertainmentArea
import com.lowlatency.visualizer.hue.HueLightController

class HueManager(private val activity: MainActivity) {

    private lateinit var btnHueConnect: Button
    private lateinit var hueAreaContainer: LinearLayout
    private lateinit var btnHueSync: Button
    private lateinit var btnHueForget: Button
    private lateinit var huePrerequisites: View
    private lateinit var hueAreaSection: LinearLayout
    private lateinit var hueSyncSection: LinearLayout
    private lateinit var hueStatus: TextView
    private lateinit var hueConn: TextView
    private lateinit var lightControlSection: LinearLayout
    private lateinit var sceneGrid: LinearLayout
    private lateinit var brightnessSlider: SeekBar

    lateinit var hueController: HueLightController
    private lateinit var hueStore: HueCredentialStore
    private var hueAreas: List<HueEntertainmentArea> = emptyList()
    var selectedArea: HueEntertainmentArea? = null
    var currentHueState = HueConn.DISCONNECTED
    
    private val huePingHandler = Handler(Looper.getMainLooper())
    private var huePingPollerRunning = false
    private val huePingPoller = object : Runnable {
        override fun run() {
            val creds = hueStore.loadCredentials() ?: return
            hueController.setup.pingBridge(creds) { rtt ->
                if (!huePingPollerRunning) return@pingBridge
                if (rtt != null) {
                    val state = if (hueController.isEnabled) HueConn.STREAMING else HueConn.REACHABLE
                    updateHueConn(state)
                } else {
                    if (!hueController.isEnabled) {
                        updateHueConn(HueConn.PAIRED)
                        stopHuePingPoller()
                    }
                }
                if (huePingPollerRunning) huePingHandler.postDelayed(this, 5000L)
            }
        }
    }

    fun bindViews(root: View) {
        hueController = HueLightController(activity)
        hueStore = HueCredentialStore(activity)

        btnHueConnect = root.findViewById(R.id.btn_hue_connect)
        hueAreaContainer = root.findViewById(R.id.hue_area_container)
        btnHueSync = root.findViewById(R.id.btn_hue_sync)
        hueStatus = root.findViewById(R.id.hue_status)
        hueConn = root.findViewById(R.id.hue_conn)
        btnHueForget = root.findViewById(R.id.btn_hue_forget)
        huePrerequisites = root.findViewById(R.id.hue_prerequisites)
        hueAreaSection = root.findViewById(R.id.hue_section_areas)
        hueSyncSection = root.findViewById(R.id.hue_section_sync)
        lightControlSection = root.findViewById(R.id.hue_light_control)
        sceneGrid = root.findViewById(R.id.hue_scene_grid)
        brightnessSlider = root.findViewById(R.id.hue_brightness)
    }

    fun setup() {
        btnHueConnect.setOnClickListener { onHueConnectClicked() }
        btnHueSync.setOnClickListener { onHueSyncToggle() }
        btnHueForget.setOnClickListener { confirmForgetBridge() }
        buildLightControlUI()

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
    }

    private fun onHueConnectClicked() {
        val existing = hueStore.loadCredentials()
        if (existing != null) {
            btnHueConnect.isEnabled = false
            updateHueConn(HueConn.CHECKING)
            hueController.setup.pingBridge(existing) { rtt ->
                if (rtt != null) {
                    btnHueConnect.isEnabled = true
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
        btnHueConnect.isEnabled = false
        hueController.setup.discoverBridges { bridges ->
            val bridge = bridges.firstOrNull()
            if (bridge == null) {
                hueStatus.setText(R.string.hue_status_no_bridge)
                updateHueConn(HueConn.DISCONNECTED)
                btnHueConnect.isEnabled = true
                return@discoverBridges
            }
            hueController.setup.pair(
                bridgeIp = bridge.ip,
                onCountdown = { s -> hueStatus.text = activity.getString(R.string.hue_status_press_button, s) },
                onSuccess = {
                    huePrerequisites.visibility = View.GONE
                    hueStatus.setText(R.string.hue_status_ready)
                    updateHueConn(HueConn.REACHABLE)
                    btnHueConnect.isEnabled = true
                    btnHueConnect.setText(R.string.hue_reconnect)
                    btnHueForget.visibility = View.VISIBLE
                    startHuePingPoller()
                    loadHueAreas()
                },
                onError = { msg ->
                    hueStatus.text = msg
                    updateHueConn(HueConn.DISCONNECTED)
                    btnHueConnect.isEnabled = true
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
                btnHueConnect.isEnabled = true
                updateHueConn(HueConn.PAIRED)
                hueStatus.text = activity.getString(R.string.hue_status_unreachable)
                return@discoverBridges
            }
            val updatedCreds = HueCredentials(bridge.ip, oldCreds.username, oldCreds.clientKey)
            hueStore.saveCredentials(updatedCreds)
            hueController.setup.pingBridge(updatedCreds) { rtt ->
                btnHueConnect.isEnabled = true
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

    fun forgetHueBridge() {
        if (hueController.isEnabled) {
            hueController.disable()
            updateHueSyncButton(false)
        }
        stopHuePingPoller()
        hueStore.clear()
        selectedArea = null
        hueAreas = emptyList()
        hueAreaContainer.removeAllViews()
        huePrerequisites.visibility = View.VISIBLE
        btnHueConnect.setText(R.string.hue_connect)
        btnHueConnect.isEnabled = true
        btnHueSync.isEnabled = false
        btnHueForget.visibility = View.GONE
        updateHueConn(HueConn.DISCONNECTED)
        hueStatus.setText(R.string.hue_status_idle)
    }

    fun loadHueAreas() {
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
            hueStatus.text = activity.getString(R.string.hue_status_no_bridge)
            updateHueSections()
            return
        }
        val h = activity.resources.displayMetrics.density * 40
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

    private fun onHueSyncToggle() {
        if (hueController.isEnabled) {
            hueController.disable()
            updateHueSyncButton(false)
            updateHueConn(HueConn.REACHABLE)
            hueStatus.setText(R.string.hue_status_ready)
            updateHueSections()
            return
        }
        if (activity.audioManager.systemAudioMode) {
            hueStatus.setText(R.string.hue_mic_only)
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

    fun updateHueSyncButton(on: Boolean) {
        btnHueSync.isSelected = on
        btnHueSync.setText(if (on) R.string.hue_sync_on else R.string.hue_sync_off)
        activity.updateAdvancedVisibility()
    }

    private fun buildLightControlUI() {
        val dp = activity.resources.displayMetrics.density
        val h = (dp * 40).toInt()
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
                    setColor(scene.dotColor)
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
        val area = selectedArea ?: run { return }
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

    fun updateHueSections() {
        val reachable = currentHueState == HueConn.REACHABLE || currentHueState == HueConn.STREAMING
        hueAreaSection.visibility = if (reachable && hueAreas.isNotEmpty()) View.VISIBLE else View.GONE
        lightControlSection.visibility = if (currentHueState == HueConn.REACHABLE && selectedArea != null) View.VISIBLE else View.GONE
        hueSyncSection.visibility = if (reachable && selectedArea != null) View.VISIBLE else View.GONE
    }

    enum class HueConn { DISCONNECTED, SEARCHING, CHECKING, PAIRED, REACHABLE, STREAMING }

    fun updateHueConn(state: HueConn) {
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
        hueConn.setTextColor(ContextCompat.getColor(activity, colorRes))
        updateHueSections()
    }

    fun startHuePingPoller() {
        if (huePingPollerRunning) return
        huePingPollerRunning = true
        huePingHandler.postDelayed(huePingPoller, 5000L)
    }

    fun stopHuePingPoller() {
        huePingPollerRunning = false
        huePingHandler.removeCallbacks(huePingPoller)
    }

    fun onResume() {
        hueController.paused = false
        if (huePingPollerRunning) {
            huePingHandler.removeCallbacks(huePingPoller)
            huePingHandler.post(huePingPoller)
        } else if (hueStore.loadCredentials() != null && !hueController.isEnabled) {
            val creds = hueStore.loadCredentials() ?: return
            hueController.setup.pingBridge(creds) { rtt ->
                if (rtt != null) {
                    updateHueConn(HueConn.REACHABLE)
                    hueStatus.text = activity.getString(R.string.hue_status_ready)
                    startHuePingPoller()
                    if (hueAreas.isEmpty()) loadHueAreas()
                }
            }
        }
    }

    fun onPause() {
        hueController.paused = true
        huePingHandler.removeCallbacks(huePingPoller)
    }

    fun onDestroy() {
        stopHuePingPoller()
        if (hueController.isEnabled) hueController.disable()
    }

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
}
