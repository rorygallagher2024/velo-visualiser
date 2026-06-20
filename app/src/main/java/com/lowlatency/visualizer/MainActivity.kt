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
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.text.HtmlCompat
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
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
import kotlin.math.sqrt

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
    private lateinit var splashOverlay: View
    private lateinit var splashLogo: TextView
    private lateinit var introHint: View
    private lateinit var segMic: Button
    private lateinit var segInternal: Button
    private lateinit var btnOscilloscope: Button
    private lateinit var btnTunnel: Button
    private lateinit var btnFluid: Button
    private lateinit var btnLaser: Button
    private lateinit var btnCircular: Button
    private lateinit var btnBars: Button
    private lateinit var btnBloom: Button
    private lateinit var btnStarscape: Button
    private lateinit var btnRawScope: Button
    private lateinit var btnSpectrogram: Button
    private lateinit var btnFireworks: Button
    private lateinit var btnPhyllotaxis: Button
    private lateinit var btnElectricIris: Button
    private lateinit var btnMandala: Button
    private lateinit var btnAudioWeb: Button
    private lateinit var btnTopoRidge: Button
    private lateinit var btnLedMatrix: Button
    private lateinit var btnMechanicalMeter: Button
    private lateinit var btnBeatPulse: Button
    private lateinit var btnMandelbox: Button
    private lateinit var btnReactionDiffusion: Button
    private lateinit var btnCymatics: Button
    private lateinit var btnStrangeAttractor: Button
    private lateinit var btnBurnin: Button
    private lateinit var btnGlowOff: Button
    private lateinit var btnGlowSubtle: Button
    private lateinit var btnGlowStandard: Button
    private lateinit var btnGlowIntense: Button
    private lateinit var btnHaptics: Button
    private lateinit var btnSensLow: Button
    private lateinit var btnSensStandard: Button
    private lateinit var btnSensHigh: Button
    private lateinit var btnLinkSync: Button
    private lateinit var btnLinkNotify: Button
    private lateinit var linkStatus: TextView
    private lateinit var linkNotification: TextView
    private lateinit var beatDot: View
    private lateinit var btnAdvanced: Button
    private lateinit var btnThemeSpectrum: Button
    private lateinit var btnThemeNeon: Button
    private lateinit var btnThemeWarm: Button
    private lateinit var btnThemeCool: Button
    private lateinit var btnThemeMono: Button
    private lateinit var btnPrivacyPolicy: Button
    private lateinit var btnAbout: Button
    private lateinit var btnPerfOverlay: Button
    private lateinit var perfOverlay: TextView
    private lateinit var hapticController: HapticController
    private lateinit var statusText: TextView
    private lateinit var prefs: SharedPreferences

    // Visualizer buttons paired with their scene index + base label (for the
    // favourite ★ prefix). Built in wireMenuControls.
    private lateinit var visButtons: List<Triple<Button, Int, String>>
    private val favourites = linkedSetOf<Int>()

    // --- Settings tabs ---
    private lateinit var tabBtnVisuals: Button
    private lateinit var tabBtnLighting: Button
    private lateinit var tabBtnSettings: Button
    private lateinit var tabVisualizers: View
    private lateinit var tabLighting: View
    private lateinit var tabSettings: View

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
    private var bassLpState = 0f   // one-pole low-pass state for the bass/treble split
    private var perfOverlayEnabled = false
    private var lastHuePackets = 0L
    private var lastPeerCount = 0
    private var linkNotifyRunnable: Runnable? = null

    // Ableton Link: a multicast lock is mandatory or Android's Wi-Fi chip filters
    // out Link's UDP discovery packets. The status poller refreshes peer/BPM text
    // while sync is on.
    private var multicastLock: WifiManager.MulticastLock? = null
    private val linkHandler = Handler(Looper.getMainLooper())
    private val linkStatusPoller = object : Runnable {
        override fun run() {
            updateLinkStatus()
            linkHandler.postDelayed(this, 1000L)
        }
    }

    private val perfHandler = Handler(Looper.getMainLooper())
    private val perfPoller = object : Runnable {
        override fun run() {
            updatePerfOverlay()
            perfHandler.postDelayed(this, 500L)
        }
    }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        // 1. Handle Microphone (existing logic)
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            startMicrophone()
        } else {
            Toast.makeText(this, "Microphone permission required.", Toast.LENGTH_LONG).show()
        }

        // 2. Log Network Status for debugging
        if (Build.VERSION.SDK_INT >= 36) {
            if (grants["android.permission.ACCESS_LOCAL_NETWORK"] == true) {
                Log.d(TAG, "Android 17 Local Network permission GRANTED.")
            } else {
                Log.e(TAG, "Android 17 Local Network permission DENIED. Hue will timeout.")
            }
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
            // Light sync is mic-only; stop it when moving to internal audio.
            if (::hueController.isInitialized && hueController.isEnabled) {
                hueController.disable()
                updateHueSyncButton(false)
                updateHueConn(HueConn.PAIRED)
                hueStatus.setText(R.string.hue_mic_only)
            }
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
        wireSplash()
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
        splashOverlay = findViewById(R.id.splash_overlay)
        splashLogo = findViewById(R.id.splash_logo)
        introHint = findViewById(R.id.intro_hint)
        segMic = findViewById(R.id.seg_mic)
        segInternal = findViewById(R.id.seg_internal)
        btnOscilloscope = findViewById(R.id.btn_oscilloscope)
        btnTunnel = findViewById(R.id.btn_tunnel)
        btnFluid = findViewById(R.id.btn_fluid)
        btnLaser = findViewById(R.id.btn_laser)
        btnCircular = findViewById(R.id.btn_circular)
        btnBars = findViewById(R.id.btn_bars)
        btnBloom = findViewById(R.id.btn_bloom)
        btnStarscape = findViewById(R.id.btn_starscape)
        btnRawScope = findViewById(R.id.btn_rawscope)
        btnSpectrogram = findViewById(R.id.btn_spectrogram)
        btnFireworks = findViewById(R.id.btn_fireworks)
        btnPhyllotaxis = findViewById(R.id.btn_phyllotaxis)
        btnElectricIris = findViewById(R.id.btn_electric_iris)
        btnMandala = findViewById(R.id.btn_mandala)
        btnAudioWeb = findViewById(R.id.btn_audio_web)
        btnTopoRidge = findViewById(R.id.btn_topo_ridge)
        btnLedMatrix = findViewById(R.id.btn_led_matrix)
        btnMechanicalMeter = findViewById(R.id.btn_mechanical_meter)
        btnBeatPulse = findViewById(R.id.btn_beat_pulse)
        btnMandelbox = findViewById(R.id.btn_mandelbox)
        btnReactionDiffusion = findViewById(R.id.btn_reaction_diffusion)
        btnCymatics = findViewById(R.id.btn_cymatics)
        btnStrangeAttractor = findViewById(R.id.btn_strange_attractor)
        btnBurnin = findViewById(R.id.btn_burnin)
        btnGlowOff = findViewById(R.id.btn_glow_off)
        btnGlowSubtle = findViewById(R.id.btn_glow_subtle)
        btnGlowStandard = findViewById(R.id.btn_glow_standard)
        btnGlowIntense = findViewById(R.id.btn_glow_intense)
        btnHaptics = findViewById(R.id.btn_haptics)
        btnSensLow = findViewById(R.id.btn_sens_low)
        btnSensStandard = findViewById(R.id.btn_sens_standard)
        btnSensHigh = findViewById(R.id.btn_sens_high)
        btnLinkSync = findViewById(R.id.btn_link_sync)
        btnLinkNotify = findViewById(R.id.btn_link_notify)
        linkStatus = findViewById(R.id.link_status)
        linkNotification = findViewById(R.id.link_notification)
        beatDot = findViewById(R.id.beat_dot)
        btnAdvanced = findViewById(R.id.btn_advanced)
        btnThemeSpectrum = findViewById(R.id.btn_theme_spectrum)
        btnThemeNeon = findViewById(R.id.btn_theme_neon)
        btnThemeWarm = findViewById(R.id.btn_theme_warm)
        btnThemeCool = findViewById(R.id.btn_theme_cool)
        btnThemeMono = findViewById(R.id.btn_theme_mono)
        btnPrivacyPolicy = findViewById(R.id.btn_privacy_policy)
        btnAbout = findViewById(R.id.btn_about)
        btnPerfOverlay = findViewById(R.id.btn_perf_overlay)
        perfOverlay = findViewById(R.id.perf_overlay)
        statusText = findViewById(R.id.status_text)
        tabBtnVisuals = findViewById(R.id.tab_btn_visuals)
        tabBtnLighting = findViewById(R.id.tab_btn_lighting)
        tabBtnSettings = findViewById(R.id.tab_btn_settings)
        tabVisualizers = findViewById(R.id.tab_visualizers)
        tabLighting = findViewById(R.id.tab_lighting)
        tabSettings = findViewById(R.id.tab_settings)
        btnHueConnect = findViewById(R.id.btn_hue_connect)
        hueAreaContainer = findViewById(R.id.hue_area_container)
        btnHueSync = findViewById(R.id.btn_hue_sync)
        hueStatus = findViewById(R.id.hue_status)
        hueConn = findViewById(R.id.hue_conn)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        optionsSheet.visibility = View.GONE
    }

    /** The app's versionName from the manifest/Gradle (e.g. "1.1"). */
    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    /** Show the ASCII "VELO" logo on startup, then fade it out. */
    private fun wireSplash() {
        splashLogo.text = LOGO_ASCII
        splashOverlay.postDelayed({
            splashOverlay.animate().alpha(0f).setDuration(SPLASH_FADE_MS)
                .withEndAction { 
                    splashOverlay.visibility = View.GONE 
                    showIntroHint()
                }.start()
        }, SPLASH_HOLD_MS)
    }

    /** Show a brief, non-blocking gesture hint after the splash fades. */
    private fun showIntroHint() {
        introHint.visibility = View.VISIBLE
        introHint.animate().alpha(1f).setDuration(600).start()
        
        introHint.postDelayed({
            if (menuOpen) {
                // If the user already opened the menu, just hide it immediately
                introHint.visibility = View.GONE
            } else {
                introHint.animate().alpha(0f).setDuration(1000)
                    .withEndAction { introHint.visibility = View.GONE }
                    .start()
            }
        }, INTRO_HINT_DURATION_MS)
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

    // ----- Settings tabs: Visuals | Lighting | Settings -----

    private fun wireTabs() {
        tabBtnVisuals.setOnClickListener { selectTab(TAB_VISUALS) }
        tabBtnLighting.setOnClickListener { selectTab(TAB_LIGHTING) }
        tabBtnSettings.setOnClickListener { selectTab(TAB_SETTINGS) }
        selectTab(TAB_VISUALS)   // default to the Visuals tab
    }

    private fun selectTab(tab: Int) {
        tabBtnVisuals.isSelected = tab == TAB_VISUALS
        tabBtnLighting.isSelected = tab == TAB_LIGHTING
        tabBtnSettings.isSelected = tab == TAB_SETTINGS
        tabVisualizers.visibility = if (tab == TAB_VISUALS) View.VISIBLE else View.GONE
        tabLighting.visibility = if (tab == TAB_LIGHTING) View.VISIBLE else View.GONE
        tabSettings.visibility = if (tab == TAB_SETTINGS) View.VISIBLE else View.GONE
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

        // Visualizer buttons: tap = select, long-press = toggle favourite.
        visButtons = listOf(
            // Instruments
            Triple(btnOscilloscope, 0, btnOscilloscope.text.toString()),
            Triple(btnRawScope, 8, btnRawScope.text.toString()),
            Triple(btnBars, 5, btnBars.text.toString()),
            Triple(btnCircular, 4, btnCircular.text.toString()),
            Triple(btnSpectrogram, 9, btnSpectrogram.text.toString()),
            Triple(btnLedMatrix, 16, btnLedMatrix.text.toString()),
            Triple(btnMechanicalMeter, 17, btnMechanicalMeter.text.toString()),
            Triple(btnCymatics, 21, btnCymatics.text.toString()),
            // Reactive
            Triple(btnBeatPulse, 18, btnBeatPulse.text.toString()),
            Triple(btnFireworks, 10, btnFireworks.text.toString()),
            Triple(btnStarscape, 7, btnStarscape.text.toString()),
            Triple(btnBloom, 6, btnBloom.text.toString()),
            Triple(btnElectricIris, 12, btnElectricIris.text.toString()),
            // Immersive
            Triple(btnTunnel, 1, btnTunnel.text.toString()),
            Triple(btnFluid, 2, btnFluid.text.toString()),
            Triple(btnLaser, 3, btnLaser.text.toString()),
            Triple(btnPhyllotaxis, 11, btnPhyllotaxis.text.toString()),
            Triple(btnMandala, 13, btnMandala.text.toString()),
            Triple(btnAudioWeb, 14, btnAudioWeb.text.toString()),
            Triple(btnTopoRidge, 15, btnTopoRidge.text.toString()),
            Triple(btnMandelbox, 19, btnMandelbox.text.toString()),
            Triple(btnReactionDiffusion, 20, btnReactionDiffusion.text.toString()),
            Triple(btnStrangeAttractor, 22, btnStrangeAttractor.text.toString()),
        )
        glView.sceneOrder = visButtons.map { it.second }
        glView.onSceneChanged = { updateVisualizerSelection() }

        prefs.getStringSet(KEY_FAVOURITES, emptySet())?.forEach {
            it.toIntOrNull()?.let { idx -> favourites.add(idx) }
        }
        updateFavouritesOrder()
        visButtons.forEach { (b, idx, _) ->
            b.setOnClickListener { glView.selectScene(idx); updateVisualizerSelection() }
            b.setOnLongClickListener { toggleFavourite(idx); true }
        }
        updateVisualizerSelection()

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

        // Performance overlay toggle (persisted, default off).
        setPerfOverlay(prefs.getBoolean(KEY_PERF_OVERLAY, false))
        btnPerfOverlay.setOnClickListener { setPerfOverlay(!perfOverlayEnabled) }

        // HDR bloom / glow strength (persisted).
        GlowSettings.strength = GlowSettings.Strength.fromKey(prefs.getString(KEY_GLOW, null))
        updateGlowSelection()
        btnGlowOff.setOnClickListener { setGlow(GlowSettings.Strength.OFF) }
        btnGlowSubtle.setOnClickListener { setGlow(GlowSettings.Strength.SUBTLE) }
        btnGlowStandard.setOnClickListener { setGlow(GlowSettings.Strength.STANDARD) }
        btnGlowIntense.setOnClickListener { setGlow(GlowSettings.Strength.INTENSE) }

        // Colour theme (persisted, applied as a global grade in the composite).
        ThemeSettings.preset = ThemeSettings.Theme.fromKey(prefs.getString(KEY_THEME, null))
        updateThemeSelection()
        btnThemeSpectrum.setOnClickListener { setTheme(ThemeSettings.Theme.SPECTRUM) }
        btnThemeNeon.setOnClickListener { setTheme(ThemeSettings.Theme.NEON) }
        btnThemeWarm.setOnClickListener { setTheme(ThemeSettings.Theme.WARM) }
        btnThemeCool.setOnClickListener { setTheme(ThemeSettings.Theme.COOL) }
        btnThemeMono.setOnClickListener { setTheme(ThemeSettings.Theme.MONO) }

        btnPrivacyPolicy.setOnClickListener {
            showPrivacyPolicy()
        }

        btnAbout.setOnClickListener {
            showAboutDialog()
        }

        // Vibrate-on-beat haptics (persisted, default off). Disabled if the
        // device has no vibrator. Created here, before wireHue() wires the sink.
        hapticController = HapticController(this)
        if (hapticController.isSupported) {
            val haptics = prefs.getBoolean(KEY_HAPTICS, false)
            hapticController.enabled = haptics
            updateHapticsButton(haptics)
            btnHaptics.setOnClickListener {
                val enabled = !hapticController.enabled
                hapticController.enabled = enabled
                prefs.edit().putBoolean(KEY_HAPTICS, enabled).apply()
                updateHapticsButton(enabled)
                // Confirmation buzz so you can tell vibration works at all,
                // independent of whether a beat has been detected yet.
                if (enabled) hapticController.previewPulse()
            }
        } else {
            btnHaptics.isEnabled = false
            btnHaptics.alpha = 0.4f
            updateHapticsButton(false)
        }

        // Beat sensitivity presets (persisted). Drives every BeatDetector
        // (fireworks, haptics, starscape flashes), mapped per audio source.
        BeatSettings.preset = BeatSettings.Sensitivity.fromKey(prefs.getString(KEY_BEAT_SENS, null))
        updateBeatSensSelection()
        btnSensLow.setOnClickListener { setBeatSensitivity(BeatSettings.Sensitivity.LOW) }
        btnSensStandard.setOnClickListener { setBeatSensitivity(BeatSettings.Sensitivity.STANDARD) }
        btnSensHigh.setOnClickListener { setBeatSensitivity(BeatSettings.Sensitivity.HIGH) }

        // Ableton Link wireless tempo/beat sync (persisted, default off).
        setLinkSync(prefs.getBoolean(KEY_LINK, false), persist = false)
        btnLinkSync.setOnClickListener { setLinkSync(!LinkSync.enabled, persist = true) }

        // Link session notifications toggle (persisted, default on).
        updateLinkNotifyButton(prefs.getBoolean(KEY_LINK_NOTIFY, true))
        btnLinkNotify.setOnClickListener {
            val enabled = !prefs.getBoolean(KEY_LINK_NOTIFY, true)
            prefs.edit().putBoolean(KEY_LINK_NOTIFY, enabled).apply()
            updateLinkNotifyButton(enabled)
        }

        // Advanced light-sync tuning (persisted). Restore saved slider positions
        // into the strobe settings, then open the panel on tap.
        HueStrobeSettings.micSensitivity = prefs.getFloat(KEY_ADV_SENS, HueStrobeSettings.micSensitivity)
        HueStrobeSettings.colourSplit = prefs.getFloat(KEY_ADV_COLOUR, HueStrobeSettings.colourSplit)
        HueStrobeSettings.restingGlow = prefs.getFloat(KEY_ADV_GLOW, HueStrobeSettings.restingGlow)
        HueStrobeSettings.audioBrightness = prefs.getFloat(KEY_ADV_AUDIO_BRIGHT, HueStrobeSettings.audioBrightness)
        HueStrobeSettings.audioFlash = prefs.getFloat(KEY_ADV_AUDIO_FLASH, HueStrobeSettings.audioFlash)
        HueStrobeSettings.audioSensitivity = prefs.getFloat(KEY_ADV_AUDIO_SENS, HueStrobeSettings.audioSensitivity)
        HueStrobeSettings.hueLookaheadMs = prefs.getFloat(KEY_ADV_HUE_LOOKAHEAD, HueStrobeSettings.hueLookaheadMs)
        btnAdvanced.setOnClickListener { showAdvancedDialog() }
    }

    /**
     * Advanced lighting panel. Adapts to the mode: with Ableton Link on it shows
     * the beat-strobe controls (live meters + sensitivity/colour/glow); with Link
     * off it shows the audio-reactive light-show controls (brightness/flash/sens).
     */
    private fun showAdvancedDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_advanced, null)
        val linkMode = LinkSync.enabled

        view.findViewById<TextView>(R.id.adv_title_text)
            .setText(if (linkMode) R.string.adv_title_link else R.string.adv_title_audio)
        view.findViewById<TextView>(R.id.adv_hint_text)
            .setText(if (linkMode) R.string.adv_hint_link else R.string.adv_hint_audio)
        view.findViewById<View>(R.id.group_link).visibility = if (linkMode) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.group_audio).visibility = if (linkMode) View.GONE else View.VISIBLE

        val seekSens = view.findViewById<SeekBar>(R.id.seek_sensitivity)
        val seekColour = view.findViewById<SeekBar>(R.id.seek_colour)
        val seekGlow = view.findViewById<SeekBar>(R.id.seek_glow)
        val seekAudioBright = view.findViewById<SeekBar>(R.id.seek_audio_brightness)
        val seekAudioFlash = view.findViewById<SeekBar>(R.id.seek_audio_flash)
        val seekAudioSens = view.findViewById<SeekBar>(R.id.seek_audio_sens)

        val seekLookahead = view.findViewById<SeekBar>(R.id.seek_lookahead)
        val labelLookahead = view.findViewById<TextView>(R.id.label_lookahead_value)

        fun applyPositions() {
            seekSens.progress = (HueStrobeSettings.micSensitivity * 100f).toInt()
            seekColour.progress = (HueStrobeSettings.colourSplit * 100f).toInt()
            seekGlow.progress = (HueStrobeSettings.restingGlow * 100f).toInt()
            seekAudioBright.progress = (HueStrobeSettings.audioBrightness * 100f).toInt()
            seekAudioFlash.progress = (HueStrobeSettings.audioFlash * 100f).toInt()
            seekAudioSens.progress = (HueStrobeSettings.audioSensitivity * 100f).toInt()
            seekLookahead.progress = HueStrobeSettings.hueLookaheadMs.toInt()
            labelLookahead.text = if (seekLookahead.progress == 0) "Off" else "-${seekLookahead.progress} ms"
        }
        applyPositions()

        seekSens.onProgress { v -> HueStrobeSettings.micSensitivity = v; prefs.edit().putFloat(KEY_ADV_SENS, v).apply() }
        seekColour.onProgress { v -> HueStrobeSettings.colourSplit = v; prefs.edit().putFloat(KEY_ADV_COLOUR, v).apply() }
        seekGlow.onProgress { v -> HueStrobeSettings.restingGlow = v; prefs.edit().putFloat(KEY_ADV_GLOW, v).apply() }
        seekAudioBright.onProgress { v -> HueStrobeSettings.audioBrightness = v; prefs.edit().putFloat(KEY_ADV_AUDIO_BRIGHT, v).apply() }
        seekAudioFlash.onProgress { v -> HueStrobeSettings.audioFlash = v; prefs.edit().putFloat(KEY_ADV_AUDIO_FLASH, v).apply() }
        seekAudioSens.onProgress { v -> HueStrobeSettings.audioSensitivity = v; prefs.edit().putFloat(KEY_ADV_AUDIO_SENS, v).apply() }
        seekLookahead.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    HueStrobeSettings.hueLookaheadMs = progress.toFloat()
                    labelLookahead.text = if (progress == 0) "Off" else "$progress ms"
                    prefs.edit().putFloat(KEY_ADV_HUE_LOOKAHEAD, progress.toFloat()).apply()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        view.findViewById<Button>(R.id.btn_adv_done).setOnClickListener { dialog.dismiss() }
        view.findViewById<Button>(R.id.btn_adv_reset).setOnClickListener {
            HueStrobeSettings.resetToDefaults()
            persistAdvanced()
            applyPositions()
        }

        // Live meters + trigger lines only apply to the Link beat-strobe.
        if (linkMode) {
            val meterLevel = view.findViewById<ProgressBar>(R.id.meter_level)
            val meterBass = view.findViewById<ProgressBar>(R.id.meter_bass)
            val markerLevel = view.findViewById<View>(R.id.marker_level)
            val markerBass = view.findViewById<View>(R.id.marker_bass)
            val poll = object : Runnable {
                override fun run() {
                    // Scale the level bar so the mic peak that reaches FULL
                    // brightness sits at the top; the trigger point then sits at a
                    // fixed 40% (levelBase / levelFull), marked by the line.
                    val full = HueStrobeSettings.levelFull.coerceAtLeast(1e-4f)
                    meterLevel.progress =
                        (hueController.currentMicLevel / full * 100f).toInt().coerceIn(0, 100)
                    meterBass.progress = (hueController.currentBassRatio * 100f).toInt().coerceIn(0, 100)
                    val triggerFrac = (HueStrobeSettings.levelBase / full).coerceIn(0f, 1f)
                    markerLevel.translationX = triggerFrac * (meterLevel.width - markerLevel.width)
                    val splitMid = ((HueStrobeSettings.bassLo + HueStrobeSettings.bassHi) * 0.5f).coerceIn(0f, 1f)
                    markerBass.translationX = splitMid * (meterBass.width - markerBass.width)
                    linkHandler.postDelayed(this, 50L)
                }
            }
            dialog.setOnDismissListener { linkHandler.removeCallbacks(poll) }
            dialog.show()
            linkHandler.post(poll)
        } else {
            dialog.show()
        }
    }

    /** Persist every Advanced slider value (used after a reset). */
    private fun persistAdvanced() {
        prefs.edit()
            .putFloat(KEY_ADV_SENS, HueStrobeSettings.micSensitivity)
            .putFloat(KEY_ADV_COLOUR, HueStrobeSettings.colourSplit)
            .putFloat(KEY_ADV_GLOW, HueStrobeSettings.restingGlow)
            .putFloat(KEY_ADV_AUDIO_BRIGHT, HueStrobeSettings.audioBrightness)
            .putFloat(KEY_ADV_AUDIO_FLASH, HueStrobeSettings.audioFlash)
            .putFloat(KEY_ADV_AUDIO_SENS, HueStrobeSettings.audioSensitivity)
            .putFloat(KEY_ADV_HUE_LOOKAHEAD, HueStrobeSettings.hueLookaheadMs)
            .apply()
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

    /** Enable/disable Ableton Link: native session + multicast lock + status poll. */
    private fun setLinkSync(enabled: Boolean, persist: Boolean) {
        LinkSync.enabled = enabled
        NativeBridge.nativeLinkSetEnabled(enabled)
        if (enabled) acquireMulticastLock() else releaseMulticastLock()
        if (persist) prefs.edit().putBoolean(KEY_LINK, enabled).apply()

        btnLinkSync.isSelected = enabled
        btnLinkSync.setText(if (enabled) R.string.link_sync_on else R.string.link_sync_off)
        updateAdvancedVisibility()

        linkHandler.removeCallbacks(linkStatusPoller)
        if (enabled) {
            lastPeerCount = NativeBridge.nativeLinkPeers() // initialize without notifying
            linkHandler.post(linkStatusPoller)            // poll peers/BPM ~1 Hz
        } else {
            lastPeerCount = 0
            linkStatus.setText(R.string.link_status_off)
            beatDot.animate().cancel()
            beatDot.alpha = 0.18f
            beatDot.scaleX = 1f
            beatDot.scaleY = 1f
        }
    }

    /** Pulse the diagnostic beat light: snap bright + slightly larger, then ease back. */
    private fun updateLinkNotifyButton(enabled: Boolean) {
        btnLinkNotify.isSelected = enabled
        btnLinkNotify.setText(if (enabled) R.string.link_notifications_on else R.string.link_notifications_off)
    }

    private fun flashBeatDot() {
        beatDot.animate().cancel()
        beatDot.alpha = 1f
        beatDot.scaleX = 1.35f
        beatDot.scaleY = 1.35f
        beatDot.animate().alpha(0.18f).scaleX(1f).scaleY(1f).setDuration(170L).start()
    }


    private fun updateLinkStatus() {
        if (!LinkSync.enabled) return
        val peers = NativeBridge.nativeLinkPeers()
        if (peers != lastPeerCount) {
            if (peers > lastPeerCount) {
                showLinkNotification(getString(R.string.link_notification_joined))
            } else {
                showLinkNotification(getString(R.string.link_notification_left))
            }
            lastPeerCount = peers
        }
        if (peers <= 0) {
            linkStatus.setText(R.string.link_status_searching)
        } else {
            linkStatus.text = getString(R.string.link_status_connected, peers, NativeBridge.nativeLinkTempo())
        }
    }

    private fun showLinkNotification(message: String) {
        if (!prefs.getBoolean(KEY_LINK_NOTIFY, true)) return
        
        linkNotifyRunnable?.let { linkNotification.removeCallbacks(it) }
        linkNotification.animate().cancel()

        linkNotification.text = message
        linkNotification.visibility = View.VISIBLE
        linkNotification.alpha = 0f
        linkNotification.animate().alpha(1f).setDuration(300).start()

        val hide = Runnable {
            linkNotification.animate().alpha(0f).setDuration(300).withEndAction {
                linkNotification.visibility = View.GONE
            }.start()
        }
        linkNotifyRunnable = hide
        linkNotification.postDelayed(hide, 3500L)
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wifi.createMulticastLock("velo-ableton-link").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    private fun setBeatSensitivity(s: BeatSettings.Sensitivity) {
        BeatSettings.preset = s
        prefs.edit().putString(KEY_BEAT_SENS, s.key).apply()
        updateBeatSensSelection()
    }

    private fun updateBeatSensSelection() {
        btnSensLow.isSelected = BeatSettings.preset == BeatSettings.Sensitivity.LOW
        btnSensStandard.isSelected = BeatSettings.preset == BeatSettings.Sensitivity.STANDARD
        btnSensHigh.isSelected = BeatSettings.preset == BeatSettings.Sensitivity.HIGH
    }

    private fun updateBurnInButton(enabled: Boolean) {
        btnBurnin.isSelected = enabled
        btnBurnin.setText(if (enabled) R.string.burnin_on else R.string.burnin_off)
    }

    private fun setPerfOverlay(enabled: Boolean) {
        perfOverlayEnabled = enabled
        prefs.edit().putBoolean(KEY_PERF_OVERLAY, enabled).apply()
        btnPerfOverlay.isSelected = enabled
        btnPerfOverlay.setText(if (enabled) R.string.perf_overlay_on else R.string.perf_overlay_off)
        if (enabled) {
            perfOverlay.visibility = View.VISIBLE
            lastHuePackets = if (::hueController.isInitialized) hueController.huePacketsSent else 0L
            perfHandler.removeCallbacks(perfPoller)
            perfHandler.post(perfPoller)
        } else {
            perfOverlay.visibility = View.GONE
            perfHandler.removeCallbacks(perfPoller)
        }
    }

    private fun updatePerfOverlay() {
        if (!perfOverlayEnabled) return
        val sb = StringBuilder()

        val fps = glView.rendererFps
        val frameMs = glView.rendererFrameTimeMs
        sb.append("Render: %.1f fps · %.1fms/frame".format(fps, frameMs))

        val audioMs = NativeBridge.nativeGetAudioCallbackMs()
        val rate = NativeBridge.nativeGetSampleRate()
        sb.append("\nAudio: %.1fms callback · %d Hz".format(audioMs, rate))

        if (LinkSync.enabled) {
            val bpm = NativeBridge.nativeLinkTempo()
            val peers = NativeBridge.nativeLinkPeers()
            sb.append("\nLink: %.0f bpm · %d peer%s".format(bpm, peers, if (peers == 1) "" else "s"))
        }

        if (::hueController.isInitialized && hueController.isEnabled) {
            val sent = hueController.huePacketsSent
            val pps = ((sent - lastHuePackets) * 2).coerceAtLeast(0)
            lastHuePackets = sent
            val drops = hueController.huePacketsFailed
            sb.append("\nHue: %d pps · %d drop%s".format(pps, drops, if (drops == 1L) "" else "s"))
        }

        perfOverlay.text = sb.toString()
    }

    private fun setGlow(s: GlowSettings.Strength) {
        GlowSettings.strength = s
        prefs.edit().putString(KEY_GLOW, s.key).apply()
        updateGlowSelection()
    }

    private fun updateGlowSelection() {
        val s = GlowSettings.strength
        btnGlowOff.isSelected = s == GlowSettings.Strength.OFF
        btnGlowSubtle.isSelected = s == GlowSettings.Strength.SUBTLE
        btnGlowStandard.isSelected = s == GlowSettings.Strength.STANDARD
        btnGlowIntense.isSelected = s == GlowSettings.Strength.INTENSE
    }

    private fun setTheme(t: ThemeSettings.Theme) {
        ThemeSettings.preset = t
        prefs.edit().putString(KEY_THEME, t.key).apply()
        updateThemeSelection()
    }

    private fun updateThemeSelection() {
        val t = ThemeSettings.preset
        btnThemeSpectrum.isSelected = t == ThemeSettings.Theme.SPECTRUM
        btnThemeNeon.isSelected = t == ThemeSettings.Theme.NEON
        btnThemeWarm.isSelected = t == ThemeSettings.Theme.WARM
        btnThemeCool.isSelected = t == ThemeSettings.Theme.COOL
        btnThemeMono.isSelected = t == ThemeSettings.Theme.MONO
    }

    /** Long-press a visualizer to add/remove it from the swipe favourites. */
    private fun toggleFavourite(index: Int) {
        if (!favourites.add(index)) favourites.remove(index)
        prefs.edit().putStringSet(KEY_FAVOURITES, favourites.map { it.toString() }.toSet()).apply()
        updateFavouritesOrder()
        updateVisualizerSelection()
        val fav = favourites.contains(index)
        Toast.makeText(
            this,
            if (fav) "Added to swipe favourites" else "Removed from favourites",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun updateFavouritesOrder() {
        val order = glView.sceneOrder
        glView.favourites = favourites.toList().sortedBy { order.indexOf(it) }
    }

    private fun updateHapticsButton(enabled: Boolean) {
        btnHaptics.isSelected = enabled
        btnHaptics.setText(if (enabled) R.string.haptics_on else R.string.haptics_off)
    }

    // ----- Smart lighting (Philips Hue) -----

    private fun wireHue() {
        hueController = HueLightController(this)
        hueStore = HueCredentialStore(this)

        // Hue light sync reads the FFT bands; haptics runs bass-onset detection
        // on the raw PCM (a separate tap, so it can't affect any visual tuning).
        glView.bandsSink = { low, mid, high -> hueController.onBands(low, mid, high) }
        glView.pcmBeatSink = { pcm ->
            hapticController.onPcm(pcm)
            // Feed the Hue Link strobe from raw PCM: absolute peak (brightness) +
            // a volume-independent bass/treble balance (colour). We compute the
            // balance here rather than from the FFT bands because the low band
            // saturates at loud volume (pegs at 1.0) and can't tell bass from a
            // bass-filtered breakdown.
            var peak = 0f
            var lp = bassLpState
            var bassAcc = 0f
            var trebAcc = 0f
            for (s in pcm) {
                val a = abs(s); if (a > peak) peak = a
                lp += BASS_LP_A * (s - lp)          // one-pole low-pass (~230 Hz)
                bassAcc += lp * lp
                val tre = s - lp
                trebAcc += tre * tre
            }
            bassLpState = lp
            val n = pcm.size.toFloat()
            val bassRms = sqrt(bassAcc / n)
            val trebRms = sqrt(trebAcc / n)
            hueController.onMicLevel(peak)
            // The bass ratio is meaningless in silence (a ratio of two ~0 noise
            // floors hovers around 0.5), so report 0 below a small noise floor.
            val ratio = if (peak < MIC_NOISE_FLOOR) 0f else bassRms / (bassRms + trebRms + 1e-6f)
            hueController.onBassRatio(ratio)
        }
        // When Ableton Link is on, the beat comes from Link's clock (mic still
        // drives the visuals). Link beats fire haptics regardless of audio source,
        // and flash the diagnostic beat light when the menu is open. The callback
        // runs on the GL thread, so the light update hops to the UI thread.
        glView.onLinkBeat = {
            hapticController.onLinkBeat()
            hueController.onLinkBeat()
            if (menuOpen) beatDot.post { flashBeatDot() }
        }

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
                }
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
            hueStatus.text = getString(R.string.hue_status_no_bridge)
            hueAreaContainer.visibility = View.GONE
            return
        }
        val h = resources.displayMetrics.density * 40
        for (area in areas) {
            val b = Button(this).apply {
                text = area.name
                isAllCaps = false
                textSize = 13f
                setTextColor(ContextCompat.getColorStateList(this@MainActivity, R.color.btn_text))
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
        // Light sync is currently microphone-only: internal (system) audio drives
        // the C++ 3-band FFT into saturation, which washes the lights to white.
        if (systemAudioMode) {
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
            updateHueConn(if (ok) HueConn.STREAMING else HueConn.PAIRED)
            hueStatus.text = if (ok) getString(R.string.hue_status_synced) else (err ?: "Failed to sync.")
        }
    }

    private fun updateHueSyncButton(on: Boolean) {
        btnHueSync.isSelected = on
        btnHueSync.setText(if (on) R.string.hue_sync_on else R.string.hue_sync_off)
        updateAdvancedVisibility()
    }

    /**
     * The Advanced panel tunes the Hue lights (audio mode and Link mode both), so
     * show it whenever Hue sync is active. The panel adapts to whichever mode is on.
     */
    private fun updateAdvancedVisibility() {
        val relevant = ::hueController.isInitialized && hueController.isEnabled
        btnAdvanced.visibility = if (relevant) View.VISIBLE else View.GONE
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
        val current = glView.sceneIndex
        for ((b, idx, base) in visButtons) {
            b.isSelected = idx == current
            b.text = if (favourites.contains(idx)) "★ $base" else base
        }
    }

    private fun updateStatus() {
        // Beat detection is hotter on internal audio — tell the shared sensitivity.
        BeatSettings.systemAudio = systemAudioMode

        // Beat-haptics are mic-only (system-audio capture is buffered → off-beat).
        // Gate the controller and grey the toggle when on internal audio.
        if (::hapticController.isInitialized) {
            hapticController.setSystemAudio(systemAudioMode)
            val available = hapticController.isSupported && !systemAudioMode
            btnHaptics.isEnabled = available
            btnHaptics.alpha = if (available) 1f else 0.4f
        }

        val rate = NativeBridge.nativeGetSampleRate()
        val version = getString(R.string.version_fmt, appVersionName())
        // NOTE: the active Oboe API isn't exposed over JNI (the C++ engine is
        // off-limits this phase). On minSdk 29 Oboe uses AAudio as requested,
        // with OpenSL ES only as a rare fallback, so we label the expected path.
        val engine = if (systemAudioMode) {
            "AudioPlaybackCapture • $rate Hz"
        } else {
            "Oboe Engine: AAudio Active • $rate Hz"
        }
        statusText.text = getString(R.string.version_fmt, "$engine   ·   $version")
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
            perms += Manifest.permission.NEARBY_WIFI_DEVICES // Fallback for Android 13-16
        }
        if (Build.VERSION.SDK_INT >= 36) {
            // Android 17+ strict requirement
            perms += "android.permission.ACCESS_LOCAL_NETWORK"
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

    private fun showPrivacyPolicy() {
        val message = HtmlCompat.fromHtml(getString(R.string.privacy_policy_text), HtmlCompat.FROM_HTML_MODE_LEGACY)
        AlertDialog.Builder(this, R.style.Theme_LowLatencyVisualizer_Dialog)
            .setTitle(R.string.btn_privacy_policy)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showAboutDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_about, null)
        
        view.findViewById<TextView>(R.id.about_logo).text = LOGO_ASCII
        view.findViewById<TextView>(R.id.about_version).text = getString(R.string.version_fmt, appVersionName())
        
        val licenses = HtmlCompat.fromHtml(getString(R.string.about_licenses_text), HtmlCompat.FROM_HTML_MODE_LEGACY)
        view.findViewById<TextView>(R.id.about_licenses).text = licenses

        val trademarks = HtmlCompat.fromHtml(getString(R.string.about_trademarks_text), HtmlCompat.FROM_HTML_MODE_LEGACY)
        view.findViewById<TextView>(R.id.about_trademarks).text = trademarks

        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        view.findViewById<Button>(R.id.btn_about_ok).setOnClickListener { dialog.dismiss() }
        
        dialog.show()
    }

    private fun requestSystemAudioCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    /**
     * Unlock HDR output, hide system bars (immersive), and keep the panel awake.
     */
    @Suppress("DEPRECATION")   // windowManager.defaultDisplay is the pre-R fallback only
    private fun configureHdrWindow() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Full immersive mode: hide status bar + navigation handle.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            window.colorMode = ActivityInfo.COLOR_MODE_HDR
            Log.i(TAG, "Requested HDR color mode.")
        }
        val d = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay
        val isHdr = d?.isHdr == true
        val types = d?.hdrCapabilities?.supportedHdrTypes?.joinToString() ?: "none"
        Log.i(TAG, "Display HDR capable=$isHdr (types: $types)")
    }

    /** Choose the display mode with the highest refresh rate at native resolution. */
    @Suppress("DEPRECATION")   // windowManager.defaultDisplay is the pre-R fallback only
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
        perfHandler.removeCallbacks(perfPoller)
        if (::hueController.isInitialized) hueController.disable()
        if (::hapticController.isInitialized) hapticController.release()
        linkHandler.removeCallbacks(linkStatusPoller)
        NativeBridge.nativeLinkSetEnabled(false)
        releaseMulticastLock()
        NativeBridge.nativeStop()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS = "visualizer_prefs"
        private const val KEY_FIRST_BOOT_DONE = "first_boot_done"
        private const val KEY_BURNIN = "burn_in_enabled"
        private const val KEY_GLOW = "glow_strength"   // string preset (was a boolean key)
        private const val KEY_HAPTICS = "haptics_enabled"
        private const val KEY_BEAT_SENS = "beat_sensitivity"
        private const val KEY_THEME = "color_theme"
        private const val KEY_LINK = "ableton_link_enabled"
        private const val KEY_LINK_NOTIFY = "ableton_link_notifications"
        private const val KEY_ADV_SENS = "adv_mic_sensitivity"
        private const val KEY_ADV_COLOUR = "adv_colour_split"
        private const val KEY_ADV_GLOW = "adv_resting_glow"
        private const val KEY_ADV_AUDIO_BRIGHT = "adv_audio_brightness"
        private const val KEY_ADV_AUDIO_FLASH = "adv_audio_flash"
        private const val KEY_ADV_AUDIO_SENS = "adv_audio_sensitivity"
        private const val KEY_ADV_HUE_LOOKAHEAD = "adv_hue_lookahead"
        private const val BASS_LP_A = 0.03f       // one-pole low-pass coeff (~230 Hz @ 48 kHz)
        private const val MIC_NOISE_FLOOR = 0.006f // below this mic peak, treat as silence
        private const val KEY_PERF_OVERLAY = "perf_overlay_enabled"
        private const val KEY_FAVOURITES = "favourite_scenes"
        private const val KEY_SCREENSHARE_RATIONALE = "screenshare_rationale_shown"
        private const val SWIPE_DOWN_VELOCITY = 1200f
        private const val TAB_VISUALS = 0
        private const val TAB_LIGHTING = 1
        private const val TAB_SETTINGS = 2
        private const val SPLASH_HOLD_MS = 3300L
        private const val SPLASH_FADE_MS = 700L
        private const val INTRO_HINT_DURATION_MS = 5000L

        private val LOGO_ASCII = listOf(
            " _     _  _______  _        _______ ",
            "| |   | ||  _____|| |      |  ___  |",
            "| |   | || |____  | |      | |   | |",
            "| |   | ||  ____| | |      | |   | |",
            " \\ \\ / / | |____  | |_____ | |___| |",
            "  \\___/  |_______||_______||_______|",
        ).joinToString("\n")
    }
}
