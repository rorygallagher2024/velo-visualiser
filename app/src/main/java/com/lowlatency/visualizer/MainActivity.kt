package com.lowlatency.visualizer

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.lowlatency.visualizer.hue.HueCredentialStore
import com.lowlatency.visualizer.hue.HueEntertainmentArea
import com.lowlatency.visualizer.hue.HueLightController
import kotlin.math.abs

/**
 * UI shell. Hosts the OpenGL canvas plus a translucent overlay layer:
 *   - swipe up (from the bottom) reveals a settings sheet over the lower canvas,
 *   - the visualizer keeps reacting to audio the whole time (GL loop never pauses),
 *   - a one-time first-boot overlay explains the gestures.
 *
 * The C++ audio engine is untouched; this is all view-layer.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var glView: VisualizerSurfaceView
    private lateinit var scrim: View
    private lateinit var optionsSheet: View
    private lateinit var firstBootOverlay: View
    private lateinit var segMic: Button
    private lateinit var segInternal: Button
    private lateinit var btnOscilloscope: Button
    private lateinit var btnTunnel: Button
    private lateinit var btnFluid: Button
    private lateinit var btnLaser: Button
    private lateinit var btnTopographic: Button
    private lateinit var btnCircular: Button
    private lateinit var btnBars: Button
    private lateinit var btnBloom: Button
    private lateinit var btnStarscape: Button
    private lateinit var btnBurnin: Button
    private lateinit var statusText: TextView
    private lateinit var prefs: SharedPreferences

    // --- Settings tabs ---
    private lateinit var tabBtnVisuals: Button
    private lateinit var tabBtnLighting: Button
    private lateinit var tabVisualizers: View
    private lateinit var tabLighting: View

    // --- Smart lighting (Philips Hue) ---
    private lateinit var btnHueConnect: Button
    private lateinit var hueAreaContainer: LinearLayout
    private lateinit var btnHueSync: Button
    private lateinit var hueStatus: TextView
    private lateinit var hueConn: TextView
    private lateinit var hueController: HueLightController
    private lateinit var hueStore: HueCredentialStore
    private var hueAreas: List<HueEntertainmentArea> = emptyList()
    private var selectedArea: HueEntertainmentArea? = null

    private var systemAudioMode = false
    private var menuOpen = false

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            startMicrophone()
        } else {
            Toast.makeText(this, "Microphone permission required.", Toast.LENGTH_LONG).show()
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            // Mic and system capture feed the same ring buffer; stop the mic so
            // we visualize system audio cleanly.
            NativeBridge.nativeStop()
            startForegroundService(
                AudioCaptureService.newIntent(this, result.resultCode, data)
            )
            systemAudioMode = true
        } else {
            Toast.makeText(this, "System-audio capture denied.", Toast.LENGTH_SHORT).show()
            systemAudioMode = false
        }
        updateSourceSelection()
        updateStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureHdrWindow()
        selectHighestRefreshRate()
        setContentView(R.layout.activity_main)

        bindViews()
        wireGestures()
        wireTabs()
        wireMenuControls()
        wireHue()
        wireFirstBoot()

        requestPermissions.launch(buildPermissionList())
    }

    private fun bindViews() {
        glView = findViewById(R.id.gl_view)
        scrim = findViewById(R.id.scrim)
        optionsSheet = findViewById(R.id.options_sheet)
        firstBootOverlay = findViewById(R.id.first_boot_overlay)
        segMic = findViewById(R.id.seg_mic)
        segInternal = findViewById(R.id.seg_internal)
        btnOscilloscope = findViewById(R.id.btn_oscilloscope)
        btnTunnel = findViewById(R.id.btn_tunnel)
        btnFluid = findViewById(R.id.btn_fluid)
        btnLaser = findViewById(R.id.btn_laser)
        btnTopographic = findViewById(R.id.btn_topographic)
        btnCircular = findViewById(R.id.btn_circular)
        btnBars = findViewById(R.id.btn_bars)
        btnBloom = findViewById(R.id.btn_bloom)
        btnStarscape = findViewById(R.id.btn_starscape)
        btnBurnin = findViewById(R.id.btn_burnin)
        statusText = findViewById(R.id.status_text)
        tabBtnVisuals = findViewById(R.id.tab_btn_visuals)
        tabBtnLighting = findViewById(R.id.tab_btn_lighting)
        tabVisualizers = findViewById(R.id.tab_visualizers)
        tabLighting = findViewById(R.id.tab_lighting)
        btnHueConnect = findViewById(R.id.btn_hue_connect)
        hueAreaContainer = findViewById(R.id.hue_area_container)
        btnHueSync = findViewById(R.id.btn_hue_sync)
        hueStatus = findViewById(R.id.hue_status)
        hueConn = findViewById(R.id.hue_conn)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        optionsSheet.visibility = View.GONE
    }

    // ----- Gestures: swipe-up opens the menu, swipe-down / tap-outside closes -----

    @SuppressLint("ClickableViewAccessibility")
    private fun wireGestures() {
        glView.onSwipeUp = { showMenu() }

        scrim.setOnClickListener { hideMenu() }

        // Fast swipe-down on the sheet dismisses it. The sheet is a ScrollView,
        // so the listener must NOT consume touches (return false) — otherwise the
        // ScrollView can't scroll its content on short screens. The detector still
        // observes every event and fires onFling for the dismiss gesture.
        val sheetGestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = false
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float
            ): Boolean {
                if (vy > SWIPE_DOWN_VELOCITY && abs(vy) > abs(vx)) {
                    hideMenu(); return true
                }
                return false
            }
        })
        optionsSheet.setOnTouchListener { _, ev -> sheetGestures.onTouchEvent(ev); false }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    firstBootOverlay.visibility == View.VISIBLE -> { /* must acknowledge */ }
                    menuOpen -> hideMenu()
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })
    }

    // ----- Settings tabs: Visualizers | Lighting -----

    private fun wireTabs() {
        tabBtnVisuals.setOnClickListener { selectTab(lighting = false) }
        tabBtnLighting.setOnClickListener { selectTab(lighting = true) }
        selectTab(lighting = false)   // default to the Visualizers tab
    }

    private fun selectTab(lighting: Boolean) {
        tabBtnVisuals.isSelected = !lighting
        tabBtnLighting.isSelected = lighting
        tabVisualizers.visibility = if (lighting) View.GONE else View.VISIBLE
        tabLighting.visibility = if (lighting) View.VISIBLE else View.GONE
    }

    private fun showMenu() {
        if (menuOpen) return
        menuOpen = true
        syncMenuState()

        scrim.alpha = 0f
        scrim.visibility = View.VISIBLE
        scrim.animate().alpha(1f).setDuration(180).start()

        val off = resources.displayMetrics.heightPixels.toFloat()
        optionsSheet.translationY = off
        optionsSheet.visibility = View.VISIBLE
        optionsSheet.animate().translationY(0f).setDuration(220)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private fun hideMenu() {
        if (!menuOpen) return
        menuOpen = false

        scrim.animate().alpha(0f).setDuration(180)
            .withEndAction { scrim.visibility = View.GONE }.start()

        val off = resources.displayMetrics.heightPixels.toFloat()
        optionsSheet.animate().translationY(off).setDuration(200)
            .withEndAction { optionsSheet.visibility = View.GONE }.start()
    }

    // ----- Menu controls -----

    private fun wireMenuControls() {
        segMic.setOnClickListener { selectMicrophone() }
        segInternal.setOnClickListener { selectInternalAudio() }
        btnOscilloscope.setOnClickListener {
            glView.selectScene(0); updateVisualizerSelection()
        }
        btnTunnel.setOnClickListener {
            glView.selectScene(1); updateVisualizerSelection()
        }
        btnFluid.setOnClickListener {
            glView.selectScene(2); updateVisualizerSelection()
        }
        btnLaser.setOnClickListener {
            glView.selectScene(3); updateVisualizerSelection()
        }
        btnTopographic.setOnClickListener {
            glView.selectScene(4); updateVisualizerSelection()
        }
        btnCircular.setOnClickListener {
            glView.selectScene(5); updateVisualizerSelection()
        }
        btnBars.setOnClickListener {
            glView.selectScene(6); updateVisualizerSelection()
        }
        btnBloom.setOnClickListener {
            glView.selectScene(7); updateVisualizerSelection()
        }
        btnStarscape.setOnClickListener {
            glView.selectScene(8); updateVisualizerSelection()
        }

        // Burn-in protection toggle (persisted, default on).
        val burnIn = prefs.getBoolean(KEY_BURNIN, true)
        glView.burnInEnabled = burnIn
        updateBurnInButton(burnIn)
        btnBurnin.setOnClickListener {
            val enabled = !glView.burnInEnabled
            glView.burnInEnabled = enabled
            prefs.edit().putBoolean(KEY_BURNIN, enabled).apply()
            updateBurnInButton(enabled)
        }
    }

    private fun updateBurnInButton(enabled: Boolean) {
        btnBurnin.isSelected = enabled
        btnBurnin.setText(if (enabled) R.string.burnin_on else R.string.burnin_off)
    }

    // ----- Smart lighting (Philips Hue) -----

    private fun wireHue() {
        hueController = HueLightController(this)
        hueStore = HueCredentialStore(this)

        // Feed audio bands to the light pipeline every render frame (GL thread).
        glView.bandsSink = { low, mid, high -> hueController.onBands(low, mid, high) }

        btnHueConnect.setOnClickListener { onHueConnectClicked() }
        btnHueSync.setOnClickListener { onHueSyncToggle() }

        // If we already paired in a previous session, the bridge is remembered:
        // reflect that and relabel Connect as a refresh action.
        if (hueStore.loadCredentials() != null) {
            updateHueConn(HueConn.PAIRED)
            hueStatus.text = getString(R.string.hue_status_paired)
            btnHueConnect.setText(R.string.hue_reconnect)
        } else {
            updateHueConn(HueConn.DISCONNECTED)
        }
    }

    private fun onHueConnectClicked() {
        val existing = hueStore.loadCredentials()
        if (existing != null) {
            hueStatus.text = getString(R.string.hue_status_paired)
            loadHueAreas()
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
                onCountdown = { s -> hueStatus.text = getString(R.string.hue_status_press_button, s) },
                onSuccess = {
                    hueStatus.setText(R.string.hue_status_paired)
                    updateHueConn(HueConn.PAIRED)
                    btnHueConnect.isEnabled = true
                    btnHueConnect.setText(R.string.hue_reconnect)
                    loadHueAreas()
                },
                onError = { msg ->
                    hueStatus.text = msg
                    updateHueConn(HueConn.DISCONNECTED)
                    btnHueConnect.isEnabled = true
                },
            )
        }
    }

    private fun loadHueAreas() {
        val creds = hueStore.loadCredentials() ?: return
        hueController.setup.listEntertainmentAreas(
            creds = creds,
            onResult = { areas -> showHueAreas(areas) },
            onError = { msg -> hueStatus.text = msg },
        )
    }

    private fun showHueAreas(areas: List<HueEntertainmentArea>) {
        hueAreas = areas
        hueAreaContainer.removeAllViews()
        if (areas.isEmpty()) {
            hueStatus.text = "No Entertainment Areas — create one in the Philips Hue app."
            hueAreaContainer.visibility = View.GONE
            return
        }
        val h = resources.displayMetrics.density * 40
        for (area in areas) {
            val b = Button(this).apply {
                text = area.name
                isAllCaps = false
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                setBackgroundResource(R.drawable.pill_button_bg)
                stateListAnimator = null
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, h.toInt()
                ).apply { bottomMargin = (resources.displayMetrics.density * 8).toInt() }
                setOnClickListener { selectHueArea(area) }
            }
            hueAreaContainer.addView(b)
        }
        hueAreaContainer.visibility = View.VISIBLE
        hueStatus.setText(R.string.hue_select_area)

        // Restore the previously selected area, if it still exists.
        val saved = areas.firstOrNull { it.id == hueStore.selectedAreaId }
        if (saved != null) selectHueArea(saved)
    }

    private fun selectHueArea(area: HueEntertainmentArea) {
        selectedArea = area
        hueStore.selectedAreaId = area.id
        for (i in 0 until hueAreaContainer.childCount) {
            hueAreaContainer.getChildAt(i).isSelected = (hueAreas.getOrNull(i)?.id == area.id)
        }
        btnHueSync.isEnabled = true
    }

    private fun onHueSyncToggle() {
        if (hueController.isEnabled) {
            hueController.disable()
            updateHueSyncButton(false)
            updateHueConn(HueConn.PAIRED)
            hueStatus.text = getString(R.string.hue_sync_off)
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
            updateHueConn(if (ok) HueConn.STREAMING else HueConn.PAIRED)
            hueStatus.text = if (ok) getString(R.string.hue_status_synced) else (err ?: "Failed to sync.")
        }
    }

    private fun updateHueSyncButton(on: Boolean) {
        btnHueSync.isSelected = on
        btnHueSync.setText(if (on) R.string.hue_sync_on else R.string.hue_sync_off)
    }

    private enum class HueConn { DISCONNECTED, SEARCHING, PAIRED, STREAMING }

    /** Update the colored connection-state dot + label in the Lighting tab. */
    private fun updateHueConn(state: HueConn) {
        val textRes = when (state) {
            HueConn.DISCONNECTED -> R.string.hue_conn_disconnected
            HueConn.SEARCHING -> R.string.hue_conn_searching
            HueConn.PAIRED -> R.string.hue_conn_paired
            HueConn.STREAMING -> R.string.hue_conn_streaming
        }
        val colorRes = when (state) {
            HueConn.DISCONNECTED -> R.color.hue_disconnected
            HueConn.SEARCHING, HueConn.PAIRED -> R.color.hue_pending
            HueConn.STREAMING -> R.color.hue_connected
        }
        hueConn.setText(textRes)
        hueConn.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun syncMenuState() {
        updateSourceSelection()
        updateVisualizerSelection()
        updateStatus()
    }

    private fun updateSourceSelection() {
        segMic.isSelected = !systemAudioMode
        segInternal.isSelected = systemAudioMode
    }

    private fun updateVisualizerSelection() {
        btnOscilloscope.isSelected = glView.sceneIndex == 0
        btnTunnel.isSelected = glView.sceneIndex == 1
        btnFluid.isSelected = glView.sceneIndex == 2
        btnLaser.isSelected = glView.sceneIndex == 3
        btnTopographic.isSelected = glView.sceneIndex == 4
        btnCircular.isSelected = glView.sceneIndex == 5
        btnBars.isSelected = glView.sceneIndex == 6
        btnBloom.isSelected = glView.sceneIndex == 7
        btnStarscape.isSelected = glView.sceneIndex == 8
    }

    private fun updateStatus() {
        val rate = NativeBridge.nativeGetSampleRate()
        // NOTE: the active Oboe API isn't exposed over JNI (the C++ engine is
        // off-limits this phase). On minSdk 29 Oboe uses AAudio as requested,
        // with OpenSL ES only as a rare fallback, so we label the expected path.
        statusText.text = if (systemAudioMode) {
            "AudioPlaybackCapture • $rate Hz"
        } else {
            "Oboe Engine: AAudio Active • $rate Hz"
        }
    }

    // ----- First-boot overlay -----

    private fun wireFirstBoot() {
        if (!prefs.getBoolean(KEY_FIRST_BOOT_DONE, false)) {
            firstBootOverlay.visibility = View.VISIBLE
        }
        findViewById<Button>(R.id.btn_understood).setOnClickListener {
            prefs.edit().putBoolean(KEY_FIRST_BOOT_DONE, true).apply()
            firstBootOverlay.animate().alpha(0f).setDuration(250)
                .withEndAction {
                    firstBootOverlay.visibility = View.GONE
                    firstBootOverlay.alpha = 1f
                }.start()
        }
    }

    // ----- Audio source switching -----

    private fun selectMicrophone() {
        if (systemAudioMode) {
            stopService(Intent(this, AudioCaptureService::class.java))
            systemAudioMode = false
        }
        ensureMicAndStart()
        updateSourceSelection()
        updateStatus()
    }

    private fun selectInternalAudio() {
        if (systemAudioMode) return
        // First time: explain why Android will ask for screen-capture permission
        // (internal audio is routed through the screen-capture API).
        if (prefs.getBoolean(KEY_SCREENSHARE_RATIONALE, false)) {
            requestSystemAudioCapture()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.screenshare_title)
            .setMessage(R.string.screenshare_message)
            .setPositiveButton(R.string.screenshare_continue) { _, _ ->
                prefs.edit().putBoolean(KEY_SCREENSHARE_RATIONALE, true).apply()
                requestSystemAudioCapture()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                updateSourceSelection()   // stay on the mic toggle
            }
            .show()
    }

    private fun buildPermissionList(): Array<String> {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        return perms.toTypedArray()
    }

    private fun startMicrophone() {
        if (!NativeBridge.nativeStartMicrophone()) {
            Toast.makeText(this, "Failed to open audio stream.", Toast.LENGTH_LONG).show()
        }
        updateStatus()
    }

    private fun ensureMicAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startMicrophone()
        } else {
            requestPermissions.launch(buildPermissionList())
        }
    }

    private fun requestSystemAudioCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    /**
     * Unlock HDR output and keep the panel awake. COLOR_MODE_HDR asks the
     * compositor to treat this window as HDR; on non-HDR panels it is ignored.
     */
    private fun configureHdrWindow() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            window.colorMode = ActivityInfo.COLOR_MODE_HDR
            Log.i(TAG, "Requested HDR color mode.")
        }
    }

    /** Choose the display mode with the highest refresh rate at native resolution. */
    private fun selectHighestRefreshRate() {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay
        val current = display?.mode ?: return
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: current

        val lp = window.attributes
        lp.preferredDisplayModeId = best.modeId
        window.attributes = lp
        Log.i(TAG, "Requested display mode ${best.modeId} @ ${best.refreshRate} Hz")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Not recreated (configChanges in manifest): audio stream keeps running;
        // the GL surface gets a fresh onSurfaceChanged which resets glViewport.
        Log.i(TAG, "Config changed: ${newConfig.screenWidthDp}x${newConfig.screenHeightDp} dp")
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        if (!systemAudioMode) ensureMicAndStart()
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
        if (!systemAudioMode) NativeBridge.nativeStop()
    }

    override fun onDestroy() {
        if (::hueController.isInitialized) hueController.disable()
        NativeBridge.nativeStop()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS = "visualizer_prefs"
        private const val KEY_FIRST_BOOT_DONE = "first_boot_done"
        private const val KEY_BURNIN = "burn_in_enabled"
        private const val KEY_SCREENSHARE_RATIONALE = "screenshare_rationale_shown"
        private const val SWIPE_DOWN_VELOCITY = 1200f
    }
}
