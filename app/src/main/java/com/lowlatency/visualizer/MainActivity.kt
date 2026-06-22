package com.lowlatency.visualizer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.text.HtmlCompat
import android.graphics.drawable.GradientDrawable
import com.lowlatency.visualizer.hue.HueCredentialStore
import com.lowlatency.visualizer.hue.HueCredentials
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
    private lateinit var splashOverlay: View
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
    private lateinit var btnPlasmaStorm: Button
    private lateinit var btnAuroraDrift: Button
    private lateinit var btnOdyssey: Button
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
    private lateinit var linkSettingsGroup: View
    private lateinit var btnLinkNotify: Button
    private lateinit var btnLinkAnticipate: Button
    private lateinit var btnLinkDownbeat: Button
    private lateinit var btnLinkExtras: Button
    private lateinit var linkStatus: TextView
    private lateinit var linkNotification: TextView
    private lateinit var beatDot: View
    private lateinit var dropDot: View
    private lateinit var barCells: List<View>
    private lateinit var btnAdvanced: Button
    private lateinit var btnThemeSpectrum: Button
    private lateinit var btnThemeNeon: Button
    private lateinit var btnThemeWarm: Button
    private lateinit var btnThemeCool: Button
    private lateinit var btnThemeMono: Button
    private lateinit var btnPrivacyPolicy: Button
    private lateinit var btnAbout: Button
    private lateinit var btnPerfOverlay: Button
    private lateinit var btnPeakLuminance: Button
    private lateinit var groupPeakLuminance: View
    private lateinit var perfOverlay: View
    private lateinit var perfFpsValue: TextView
    private lateinit var perfFrameMs: TextView
    private lateinit var perfDetail: TextView
    private var displayedFps = 0f
    private var shownFps = -1          // last integer drawn (snap/hysteresis state)
    private lateinit var heroVisName: TextView
    private lateinit var btnSceneLabel: Button
    private lateinit var sceneLabel: TextView
    private var sceneLabelRunnable: Runnable? = null
    private var sceneLabelEnabled = true
    private lateinit var hapticController: HapticController
    private lateinit var prefs: SharedPreferences

    // Visualizer buttons paired with their scene index + base label (for the
    // favourite ★ prefix). Built in wireMenuControls.
    private lateinit var visButtons: List<Triple<Button, Int, String>>
    private val favourites = linkedSetOf<Int>()

    // --- Settings tabs ---
    private lateinit var tabBtnVisuals: Button
    private lateinit var tabBtnLighting: Button
    private lateinit var tabBtnSettings: Button
    private lateinit var tabVisualizers: LinearLayout
    private lateinit var tabLighting: LinearLayout
    private lateinit var tabSettings: LinearLayout
    private lateinit var internalAudioWarning: TextView

    // --- Smart lighting (Philips Hue) ---
    private lateinit var btnHueConnect: Button
    private lateinit var hueAreaContainer: LinearLayout
    private lateinit var btnHueSync: Button
    private lateinit var btnHueForget: Button
    private lateinit var huePrerequisites: View
    private lateinit var hueAreaSection: LinearLayout
    private lateinit var hueSyncSection: LinearLayout
    private lateinit var hueStatus: TextView
    private lateinit var hueConn: TextView
    private lateinit var hueController: HueLightController
    private lateinit var hueStore: HueCredentialStore
    private var hueAreas: List<HueEntertainmentArea> = emptyList()
    private var selectedArea: HueEntertainmentArea? = null

    private var systemAudioMode = false
    private var menuOpen = false
    private var deferredPermissionRequest = false
    private var perfOverlayEnabled = false
    private var lastHuePackets = 0L
    private var lastPeerCount = 0
    private var linkNotifyRunnable: Runnable? = null
    private var backgroundedAtMs = 0L

    private val captureStopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (systemAudioMode) {
                selectMicrophone()
            }
        }
    }

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
    // Smoothly eases the hero FPS readout toward the live value (~20 Hz) so the
    // giant number counts fluidly rather than snapping with the 500 ms data poll.
    private val perfFpsTicker = object : Runnable {
        override fun run() {
            displayedFps += (glView.rendererFps - displayedFps) * 0.22f
            renderHeroFps()
            perfHandler.postDelayed(this, 50L)
        }
    }

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
                updateHueConn(HueConn.REACHABLE)
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
        installSplashScreen()
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
        checkHdrSupport()

        ContextCompat.registerReceiver(
            this,
            captureStopReceiver,
            IntentFilter(AudioCaptureService.ACTION_STOPPED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Hold the runtime-permission dialog back until the intro has finished, but
        // only when it would actually appear — i.e. a first launch (mic not yet
        // granted) where the intro is going to play. If the mic is already granted
        // the request is invisible, so fire it now and let audio be live behind the
        // shatter. wireSplash() fires the deferred request from onIntroFinished.
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (glView.willPlayIntro && !hasMic) {
            deferredPermissionRequest = true
            // Safety net: if the intro never reports finished (e.g. surface never
            // created), still ask after a delay so the app isn't stuck without audio.
            glView.postDelayed({ fireDeferredPermissionRequest() }, PERMISSION_FALLBACK_MS)
        } else {
            requestPermissions.launch(buildPermissionList())
        }
    }

    /** Launch the deferred permission request exactly once. */
    private fun fireDeferredPermissionRequest() {
        if (!deferredPermissionRequest) return
        deferredPermissionRequest = false
        requestPermissions.launch(buildPermissionList())
    }

    private fun bindViews() {
        glView = findViewById(R.id.gl_view)
        scrim = findViewById(R.id.scrim)
        optionsSheet = findViewById(R.id.options_sheet)
        splashOverlay = findViewById(R.id.splash_overlay)
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
        btnPlasmaStorm = findViewById(R.id.btn_plasma_storm)
        btnAuroraDrift = findViewById(R.id.btn_aurora_drift)
        btnOdyssey = findViewById(R.id.btn_odyssey)
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
        linkSettingsGroup = findViewById(R.id.link_settings_group)
        btnLinkNotify = findViewById(R.id.btn_link_notify)
        btnLinkAnticipate = findViewById(R.id.btn_link_anticipate)
        btnLinkDownbeat = findViewById(R.id.btn_link_downbeat)
        btnLinkExtras = findViewById(R.id.btn_link_extras)
        linkStatus = findViewById(R.id.link_status)
        linkNotification = findViewById(R.id.link_notification)
        beatDot = findViewById(R.id.beat_dot)
        dropDot = findViewById(R.id.drop_dot)
        barCells = listOf(
            findViewById(R.id.bar_cell_1),
            findViewById(R.id.bar_cell_2),
            findViewById(R.id.bar_cell_3),
            findViewById(R.id.bar_cell_4),
        )
        btnAdvanced = findViewById(R.id.btn_advanced)
        btnThemeSpectrum = findViewById(R.id.btn_theme_spectrum)
        btnThemeNeon = findViewById(R.id.btn_theme_neon)
        btnThemeWarm = findViewById(R.id.btn_theme_warm)
        btnThemeCool = findViewById(R.id.btn_theme_cool)
        btnThemeMono = findViewById(R.id.btn_theme_mono)
        btnPrivacyPolicy = findViewById(R.id.btn_privacy_policy)
        btnAbout = findViewById(R.id.btn_about)
        btnPerfOverlay = findViewById(R.id.btn_perf_overlay)
        btnSceneLabel = findViewById(R.id.btn_scene_label)
        heroVisName = findViewById(R.id.hero_vis_name)
        sceneLabel = findViewById(R.id.scene_label)
        btnPeakLuminance = findViewById(R.id.btn_peak_luminance)
        groupPeakLuminance = findViewById(R.id.group_peak_luminance)
        perfOverlay = findViewById(R.id.perf_overlay)
        perfFpsValue = findViewById(R.id.perf_fps_value)
        perfFrameMs = findViewById(R.id.perf_frame_ms)
        perfDetail = findViewById(R.id.perf_detail)
        tabBtnVisuals = findViewById(R.id.tab_btn_visuals)
        tabBtnLighting = findViewById(R.id.tab_btn_lighting)
        tabBtnSettings = findViewById(R.id.tab_btn_settings)
        tabVisualizers = findViewById(R.id.tab_visualizers)
        tabLighting = findViewById(R.id.tab_lighting)
        tabSettings = findViewById(R.id.tab_settings)
        internalAudioWarning = findViewById(R.id.internal_audio_warning)
        btnHueConnect = findViewById(R.id.btn_hue_connect)
        hueAreaContainer = findViewById(R.id.hue_area_container)
        btnHueSync = findViewById(R.id.btn_hue_sync)
        hueStatus = findViewById(R.id.hue_status)
        hueConn = findViewById(R.id.hue_conn)
        btnHueForget = findViewById(R.id.btn_hue_forget)
        huePrerequisites = findViewById(R.id.hue_prerequisites)
        hueAreaSection = findViewById(R.id.hue_section_areas)
        hueSyncSection = findViewById(R.id.hue_section_sync)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        optionsSheet.visibility = View.GONE
    }

    /** The app's versionName from the manifest/Gradle (e.g. "1.1"). */
    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    /**
     * The VELO wordmark is now an HDR particle cloud rendered by the GL engine
     * itself (see [VisualizerSurfaceView] / IntroLogoScene). This overlay is just
     * a brief black mask over GL initialisation — fade it out fast, then let the
     * GL intro play and surface the gesture hint once it dissolves.
     */
    private fun wireSplash() {
        // Honour the system "remove animations" accessibility setting.
        glView.introEnabled = !isReducedMotion()

        splashOverlay.postDelayed({
            splashOverlay.animate().alpha(0f).setDuration(SPLASH_FADE_MS)
                .withEndAction { splashOverlay.visibility = View.GONE }
                .start()
        }, SPLASH_MASK_MS)

        if (glView.introEnabled) {
            glView.onIntroFinished = {
                showIntroHint()
                fireDeferredPermissionRequest()
            }
        } else {
            splashOverlay.postDelayed({ showIntroHint() }, SPLASH_MASK_MS + SPLASH_FADE_MS)
        }
    }

    /** True when the OS "remove animations" setting is on (animator scale 0). */
    private fun isReducedMotion(): Boolean = try {
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (_: Exception) {
        false
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
                    // Only dismiss if the ScrollView is already at the top.
                    // This prevents accidental closure while scrolling up.
                    if (optionsSheet.scrollY == 0) {
                        hideMenu()
                        return true
                    }
                }
                return false
            }
        })
        optionsSheet.setOnTouchListener { _, ev -> sheetGestures.onTouchEvent(ev); false }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
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
        for ((view, id) in listOf(
            tabVisualizers to TAB_VISUALS,
            tabLighting to TAB_LIGHTING,
            tabSettings to TAB_SETTINGS,
        )) {
            val active = tab == id
            view.visibility = if (active) View.VISIBLE else View.GONE
            (view.layoutParams as LinearLayout.LayoutParams).apply {
                weight = if (active) 1f else 0f
                height = if (active) 0 else LinearLayout.LayoutParams.WRAP_CONTENT
            }
        }
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
            // Reactive
            Triple(btnCymatics, 21, btnCymatics.text.toString()),
            Triple(btnBeatPulse, 18, btnBeatPulse.text.toString()),
            Triple(btnFireworks, 10, btnFireworks.text.toString()),
            Triple(btnStarscape, 7, btnStarscape.text.toString()),
            Triple(btnBloom, 6, btnBloom.text.toString()),
            Triple(btnElectricIris, 12, btnElectricIris.text.toString()),
            Triple(btnAuroraDrift, 24, btnAuroraDrift.text.toString()),
            Triple(btnTunnel, 1, btnTunnel.text.toString()),
            Triple(btnLaser, 3, btnLaser.text.toString()),
            Triple(btnPhyllotaxis, 11, btnPhyllotaxis.text.toString()),
            Triple(btnMandala, 13, btnMandala.text.toString()),
            Triple(btnAudioWeb, 14, btnAudioWeb.text.toString()),
            Triple(btnTopoRidge, 15, btnTopoRidge.text.toString()),
            Triple(btnStrangeAttractor, 22, btnStrangeAttractor.text.toString()),
            // Immersive
            Triple(btnFluid, 2, btnFluid.text.toString()),
            Triple(btnMandelbox, 19, btnMandelbox.text.toString()),
            Triple(btnReactionDiffusion, 20, btnReactionDiffusion.text.toString()),
            Triple(btnPlasmaStorm, 23, btnPlasmaStorm.text.toString()),
            Triple(btnOdyssey, 25, btnOdyssey.text.toString()),
        )
        glView.sceneOrder = visButtons.map { it.second }
        glView.onSceneChanged = {
            updateVisualizerSelection()
            // Flash the name over the canvas on a swipe (menu closed); when the
            // menu is open the hero header already names the active scene.
            if (!menuOpen) showSceneLabel()
        }

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

        // On-swipe visualiser-name label toggle (persisted, default on).
        setSceneLabelEnabled(prefs.getBoolean(KEY_SCENE_LABEL, true))
        btnSceneLabel.setOnClickListener { setSceneLabelEnabled(!sceneLabelEnabled) }

        // Peak luminance (HDR+) toggle (persisted, default off).
        val peak = prefs.getBoolean(KEY_PEAK_LUMINANCE, false)
        updatePeakLuminance(peak)
        btnPeakLuminance.setOnClickListener {
            val enabled = !prefs.getBoolean(KEY_PEAK_LUMINANCE, false)
            prefs.edit().putBoolean(KEY_PEAK_LUMINANCE, enabled).apply()
            updatePeakLuminance(enabled)
        }

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

        // Anticipatory beat-swell for the visuals (persisted, default on).
        LinkSync.anticipateBeat = prefs.getBoolean(KEY_LINK_ANTICIPATE, true)
        updateLinkAnticipateButton(LinkSync.anticipateBeat)
        btnLinkAnticipate.setOnClickListener {
            val enabled = !LinkSync.anticipateBeat
            LinkSync.anticipateBeat = enabled
            prefs.edit().putBoolean(KEY_LINK_ANTICIPATE, enabled).apply()
            updateLinkAnticipateButton(enabled)
        }

        // Experimental bar + drop enrichment (persisted, default off so the
        // out-of-the-box visuals only ride the reliable beat).
        LinkSync.experimentalEnrich = prefs.getBoolean(KEY_LINK_EXTRAS, false)
        updateLinkExtrasButton(LinkSync.experimentalEnrich)
        btnLinkExtras.setOnClickListener {
            val enabled = !LinkSync.experimentalEnrich
            LinkSync.experimentalEnrich = enabled
            prefs.edit().putBoolean(KEY_LINK_EXTRAS, enabled).apply()
            updateLinkExtrasButton(enabled)
        }

        // Manual downbeat alignment (persisted, default 0). Cycles 0→1→2→3 beats.
        LinkSync.barOffsetBeats = prefs.getInt(KEY_LINK_BAR_OFFSET, 0).coerceIn(0, 3)
        updateLinkDownbeatButton()
        btnLinkDownbeat.setOnClickListener {
            LinkSync.barOffsetBeats = (LinkSync.barOffsetBeats + 1) % 4
            prefs.edit().putInt(KEY_LINK_BAR_OFFSET, LinkSync.barOffsetBeats).apply()
            updateLinkDownbeatButton()
        }

        // Advanced light-sync tuning (persisted). Restore saved slider positions
        // into the strobe settings, then open the panel on tap. Beat sensitivity is
        // no longer here — it's the global Beat Sensitivity preset (BeatSettings).
        HueStrobeSettings.linkBeatFlashEnabled = prefs.getBoolean(KEY_ADV_LINK_BEAT_FLASH, HueStrobeSettings.linkBeatFlashEnabled)
        HueStrobeSettings.colourSplit = prefs.getFloat(KEY_ADV_COLOUR, HueStrobeSettings.colourSplit)
        HueStrobeSettings.restingGlow = prefs.getFloat(KEY_ADV_GLOW, HueStrobeSettings.restingGlow)
        HueStrobeSettings.audioBrightness = prefs.getFloat(KEY_ADV_AUDIO_BRIGHT, HueStrobeSettings.audioBrightness)
        HueStrobeSettings.audioFlash = prefs.getFloat(KEY_ADV_AUDIO_FLASH, HueStrobeSettings.audioFlash)
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

        val btnLinkBeatFlash = view.findViewById<Button>(R.id.btn_link_beat_flash)
        val seekColour = view.findViewById<SeekBar>(R.id.seek_colour)
        val seekGlow = view.findViewById<SeekBar>(R.id.seek_glow)
        val seekAudioBright = view.findViewById<SeekBar>(R.id.seek_audio_brightness)
        val seekAudioFlash = view.findViewById<SeekBar>(R.id.seek_audio_flash)

        val seekLookahead = view.findViewById<SeekBar>(R.id.seek_lookahead)
        val labelLookahead = view.findViewById<TextView>(R.id.label_lookahead_value)

        fun applyLinkBeatFlashLabel() {
            btnLinkBeatFlash.setText(
                if (HueStrobeSettings.linkBeatFlashEnabled) R.string.adv_link_beat_on
                else R.string.adv_link_beat_off
            )
        }

        fun applyPositions() {
            applyLinkBeatFlashLabel()
            seekColour.progress = (HueStrobeSettings.colourSplit * 100f).toInt()
            seekGlow.progress = (HueStrobeSettings.restingGlow * 100f).toInt()
            seekAudioBright.progress = (HueStrobeSettings.audioBrightness * 100f).toInt()
            seekAudioFlash.progress = (HueStrobeSettings.audioFlash * 100f).toInt()
            seekLookahead.progress = HueStrobeSettings.hueLookaheadMs.toInt()
            labelLookahead.text = if (seekLookahead.progress == 0) "Off" else "-${seekLookahead.progress} ms"
        }
        applyPositions()

        btnLinkBeatFlash.setOnClickListener {
            val enabled = !HueStrobeSettings.linkBeatFlashEnabled
            HueStrobeSettings.linkBeatFlashEnabled = enabled
            prefs.edit().putBoolean(KEY_ADV_LINK_BEAT_FLASH, enabled).apply()
            applyLinkBeatFlashLabel()
        }
        seekColour.onProgress { v -> HueStrobeSettings.colourSplit = v; prefs.edit().putFloat(KEY_ADV_COLOUR, v).apply() }
        seekGlow.onProgress { v -> HueStrobeSettings.restingGlow = v; prefs.edit().putFloat(KEY_ADV_GLOW, v).apply() }
        seekAudioBright.onProgress { v -> HueStrobeSettings.audioBrightness = v; prefs.edit().putFloat(KEY_ADV_AUDIO_BRIGHT, v).apply() }
        seekAudioFlash.onProgress { v -> HueStrobeSettings.audioFlash = v; prefs.edit().putFloat(KEY_ADV_AUDIO_FLASH, v).apply() }
        seekLookahead.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    HueStrobeSettings.hueLookaheadMs = progress.toFloat()
                    labelLookahead.text = if (progress == 0) "Off" else "-${progress} ms"
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
                    val splitMid = ((HueStrobeSettings.bassLo + HueStrobeSettings.bassHi) * 0.5f).coerceIn(0f, 1f)
                    markerBass.translationX = splitMid * (meterBass.width - markerBass.width)
                }
                linkHandler.postDelayed(this, 50L)
            }
        }
        dialog.setOnDismissListener { linkHandler.removeCallbacks(poll) }
        dialog.show()
        linkHandler.post(poll)
    }

    /** Persist every Advanced lighting value (used after a reset). */
    private fun persistAdvanced() {
        prefs.edit()
            .putBoolean(KEY_ADV_LINK_BEAT_FLASH, HueStrobeSettings.linkBeatFlashEnabled)
            .putFloat(KEY_ADV_COLOUR, HueStrobeSettings.colourSplit)
            .putFloat(KEY_ADV_GLOW, HueStrobeSettings.restingGlow)
            .putFloat(KEY_ADV_AUDIO_BRIGHT, HueStrobeSettings.audioBrightness)
            .putFloat(KEY_ADV_AUDIO_FLASH, HueStrobeSettings.audioFlash)
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
        linkSettingsGroup.visibility = if (enabled) View.VISIBLE else View.GONE
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
            for (cell in barCells) { cell.animate().cancel(); cell.alpha = 0.18f }
        }
    }

    /** Pulse the diagnostic beat light: snap bright + slightly larger, then ease back. */
    private fun updateLinkNotifyButton(enabled: Boolean) {
        btnLinkNotify.isSelected = enabled
        btnLinkNotify.setText(if (enabled) R.string.link_notifications_on else R.string.link_notifications_off)
    }

    private fun updateLinkAnticipateButton(enabled: Boolean) {
        btnLinkAnticipate.isSelected = enabled
        btnLinkAnticipate.setText(if (enabled) R.string.link_anticipate_on else R.string.link_anticipate_off)
    }

    private fun updateLinkDownbeatButton() {
        btnLinkDownbeat.isSelected = LinkSync.barOffsetBeats != 0
        btnLinkDownbeat.text = getString(R.string.link_downbeat_nudge, LinkSync.barOffsetBeats)
    }

    private fun updateLinkExtrasButton(enabled: Boolean) {
        btnLinkExtras.isSelected = enabled
        btnLinkExtras.setText(if (enabled) R.string.link_extras_on else R.string.link_extras_off)
    }

    private fun flashBeatDot() = flashDot(beatDot)

    /** Pulse a diagnostic indicator light: snap bright + slightly larger, then ease back. */
    private fun flashDot(dot: View) {
        dot.animate().cancel()
        dot.alpha = 1f
        dot.scaleX = 1.35f
        dot.scaleY = 1.35f
        dot.animate().alpha(0.18f).scaleX(1f).scaleY(1f).setDuration(170L).start()
    }

    /** Light the active cell of the virtual bar (Link's beat-in-bar, 0..3). */
    private fun updateBarCells(active: Int) {
        barCells.forEachIndexed { i, cell ->
            cell.animate().cancel()
            cell.alpha = if (i == active) 1f else 0.18f
        }
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

    private fun updatePeakLuminance(enabled: Boolean) {
        val d = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay
        val isHdr = d?.isHdr == true

        if (isHdr) {
            btnPeakLuminance.setText(if (enabled) R.string.peak_luminance_on else R.string.peak_luminance_off)
            findViewById<TextView>(R.id.peak_luminance_hint_text).setText(R.string.peak_luminance_hint)
        } else {
            btnPeakLuminance.setText(if (enabled) R.string.max_brightness_on else R.string.max_brightness_off)
            findViewById<TextView>(R.id.peak_luminance_hint_text).setText(R.string.max_brightness_hint)
        }
        btnPeakLuminance.isSelected = enabled

        val lp = window.attributes
        lp.screenBrightness = if (enabled) 1.0f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp

        if (Build.VERSION.SDK_INT >= 35) {
            // Android 15+ HDR headroom: 1.0 = no headroom, >1.0 = extra range.
            // 10.0 is a safe "maximum" request for most OLED panels.
            window.setDesiredHdrHeadroom(if (enabled) 10.0f else 1.0f)
        }
    }

    private fun setPerfOverlay(enabled: Boolean) {
        perfOverlayEnabled = enabled
        prefs.edit().putBoolean(KEY_PERF_OVERLAY, enabled).apply()
        btnPerfOverlay.isSelected = enabled
        btnPerfOverlay.setText(if (enabled) R.string.perf_overlay_on else R.string.perf_overlay_off)
        if (enabled) {
            perfOverlay.visibility = View.VISIBLE
            lastHuePackets = if (::hueController.isInitialized) hueController.huePacketsSent else 0L
            displayedFps = 0f          // count up from zero — a small flourish on open
            shownFps = -1
            perfHandler.removeCallbacks(perfPoller)
            perfHandler.removeCallbacks(perfFpsTicker)
            perfHandler.post(perfPoller)
            perfHandler.post(perfFpsTicker)
        } else {
            perfOverlay.visibility = View.GONE
            perfHandler.removeCallbacks(perfPoller)
            perfHandler.removeCallbacks(perfFpsTicker)
        }
    }

    /** Enable/disable the on-swipe visualiser-name label over the canvas. */
    private fun setSceneLabelEnabled(enabled: Boolean) {
        sceneLabelEnabled = enabled
        prefs.edit().putBoolean(KEY_SCENE_LABEL, enabled).apply()
        btnSceneLabel.isSelected = enabled
        btnSceneLabel.setText(if (enabled) R.string.scene_label_on else R.string.scene_label_off)
        if (!enabled) {
            sceneLabelRunnable?.let { sceneLabel.removeCallbacks(it) }
            sceneLabel.animate().cancel()
            sceneLabel.visibility = View.GONE
        }
    }

    /** Briefly flash the active-visualiser name over the canvas on a swipe. */
    private fun showSceneLabel() {
        if (!sceneLabelEnabled) return
        val name = heroVisName.text
        if (name.isNullOrBlank()) return
        sceneLabel.text = name
        // Drop below the performance overlay when it's showing, so they never clash.
        val d = resources.displayMetrics.density
        val lp = sceneLabel.layoutParams as android.view.ViewGroup.MarginLayoutParams
        lp.topMargin = if (perfOverlayEnabled && perfOverlay.height > 0)
            perfOverlay.bottom + (d * 12).toInt()
        else
            (d * 72).toInt()
        sceneLabel.layoutParams = lp
        sceneLabelRunnable?.let { sceneLabel.removeCallbacks(it) }
        sceneLabel.animate().cancel()
        sceneLabel.visibility = View.VISIBLE
        sceneLabel.alpha = 0f
        sceneLabel.animate().alpha(1f).setDuration(180L).start()
        val hide = Runnable {
            sceneLabel.animate().alpha(0f).setDuration(450L)
                .withEndAction { sceneLabel.visibility = View.GONE }.start()
        }
        sceneLabelRunnable = hide
        sceneLabel.postDelayed(hide, 1100L)
    }

    private fun updatePerfOverlay() {
        if (!perfOverlayEnabled) return

        val audioMs = NativeBridge.nativeGetAudioCallbackMs()
        val rate = NativeBridge.nativeGetSampleRate()
        // Derive frame time from the (already EMA-smoothed) fps so the readout is
        // calm and consistent with the hero number, instead of the raw per-frame
        // dt which flickers every frame. Updated on the slow 500 ms cadence.
        val frameMs = if (displayedFps > 1f) 1000f / displayedFps else glView.rendererFrameTimeMs
        perfFrameMs.text = "%.1f ms".format(frameMs)

        val sb = android.text.SpannableStringBuilder()
        val dim = getColor(R.color.text_dim)
        val amber = 0xFFFFBB33.toInt()

        // Each row: a dim mono label padded to a fixed column, then the value.
        fun appendRow(label: String, value: String, valueColor: Int = 0) {
            val start = sb.length
            sb.append(label.uppercase().padEnd(7))
            sb.setSpan(android.text.style.ForegroundColorSpan(dim), start, sb.length, 0)
            val vStart = sb.length
            sb.append(value)
            if (valueColor != 0) {
                sb.setSpan(android.text.style.ForegroundColorSpan(valueColor), vStart, sb.length, 0)
            }
            sb.append("\n")
        }

        // Hardware load → CPU time within the GL frame.
        val load = NativeBridge.nativeGetHardwareLoad()
        val cpuMs = load[0] / 1000f
        val cpuPercent = if (frameMs > 0) (cpuMs / frameMs * 100f).coerceIn(0f, 100f) else 0f
        appendRow("CPU", "%.1f ms · %.0f%%".format(cpuMs, cpuPercent))

        // Audio capture.
        appendRow("Audio", "%d Hz · %.1f ms".format(rate, audioMs))

        // System audio / jitter (internal-audio mode only).
        if (systemAudioMode) {
            val metrics = NativeBridge.nativeGetSystemAudioMetrics()
            val jitter = metrics[1]
            // System audio is inherently buffered by Android (often ~40ms blocks),
            // so we describe the state rather than raise an alarmist colour.
            val status = if (jitter > 80f) "bursty" else "buffered"
            appendRow("Shared", "%.1f ms · %s".format(jitter, status))
        }

        // Ableton Link.
        if (LinkSync.enabled) {
            val bpm = NativeBridge.nativeLinkTempo()
            val peers = NativeBridge.nativeLinkPeers()
            appendRow("Link", "%.0f bpm · %d peer%s".format(bpm, peers, if (peers == 1) "" else "s"))
        }

        // Philips Hue.
        if (::hueController.isInitialized && hueController.isEnabled) {
            val sent = hueController.huePacketsSent
            val pps = ((sent - lastHuePackets) * 2).coerceAtLeast(0)  // poll is 500ms → ×2
            lastHuePackets = sent
            val drops = hueController.huePacketsFailed
            appendRow("Hue", "%d pps · %d drop".format(pps, drops), if (drops > 0) amber else 0)
        }

        // Trim the trailing newline.
        if (sb.isNotEmpty()) sb.delete(sb.length - 1, sb.length)

        perfDetail.text = sb
    }

    /** Render the giant eased FPS number: brand white when healthy, amber/red when struggling. */
    private fun renderHeroFps() {
        val v = displayedFps
        // When we're essentially locked to a vsync rate (60/90/120/144), show that
        // exact number — otherwise a ~60.5 reading flickers between 60 and 61. Off
        // a locked rate, a small deadband stops noise from twitching the integer.
        val snapped = LOCKED_RATES.firstOrNull { Math.abs(v - it) <= FPS_SNAP_TOL }
        val target = snapped ?: Math.round(v)
        val changed = when {
            shownFps < 0 -> true
            snapped != null -> shownFps != snapped
            else -> Math.abs(v - shownFps) >= FPS_HYSTERESIS
        }
        if (changed) {
            shownFps = target
            perfFpsValue.text = target.toString()
        }
        perfFpsValue.setTextColor(
            when {
                v < 30f -> 0xFFFF4444.toInt()
                v < 55f -> 0xFFFFBB33.toInt()
                else -> getColor(R.color.text_primary)
            }
        )
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
        // Haptics fire off the shared BeatBus, which the renderer updates just
        // before this per-frame tap. Audio presence + bass balance (for the Hue
        // strobe) are measured in the renderer too, so this tap is just the tick.
        glView.pcmBeatSink = { pcm -> hapticController.onPcm(pcm) }
        // Raw (ungated) Link beat: drives the Hue strobe timing (with lookahead)
        // and flashes the diagnostic beat light when the menu is open. The visuals
        // and haptics react to the *gated* beat via BeatBus instead. Runs on the
        // GL thread, so the light update hops to the UI thread.
        glView.onLinkBeat = {
            hueController.onLinkBeat()
            // Step the virtual bar to Link's current beat-in-bar (post-nudge) and
            // pulse the beat dot. Only while the menu is open, to stay cheap.
            if (menuOpen) {
                val cell = (Math.round(BeatPulse.barPhase * 4f) % 4 + 4) % 4
                beatDot.post { flashBeatDot(); updateBarCells(cell) }
            }
        }
        // Drop diagnostic light (detected energy surge); flashed while menu open.
        glView.onDrop = { if (menuOpen) dropDot.post { flashDot(dropDot) } }

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
                    hueStatus.text = getString(R.string.hue_status_ready)
                    startHuePingPoller()
                    loadHueAreas()
                } else {
                    updateHueConn(HueConn.PAIRED)
                    hueStatus.text = getString(R.string.hue_status_unreachable)
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
                    hueStatus.text = getString(R.string.hue_status_ready)
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
                onCountdown = { s -> hueStatus.text = getString(R.string.hue_status_press_button, s) },
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
        AlertDialog.Builder(this)
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
                hueStatus.text = getString(R.string.hue_status_unreachable)
                return@discoverBridges
            }
            val updatedCreds = HueCredentials(bridge.ip, oldCreds.username, oldCreds.clientKey)
            hueStore.saveCredentials(updatedCreds)
            hueController.setup.pingBridge(updatedCreds) { rtt ->
                btnHueConnect.isEnabled = true
                if (rtt != null) {
                    updateHueConn(HueConn.REACHABLE)
                    hueStatus.text = getString(R.string.hue_status_ready)
                    startHuePingPoller()
                    loadHueAreas()
                } else {
                    updateHueConn(HueConn.PAIRED)
                    hueStatus.text = getString(R.string.hue_status_unreachable)
                }
            }
        }
    }

    private fun forgetHueBridge() {
        if (::hueController.isInitialized && hueController.isEnabled) {
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
            hueStatus.text = getString(R.string.hue_status_no_bridge)
            updateHueSections()
            return
        }
        val h = resources.displayMetrics.density * 40
        for (area in areas) {
            val label = if (area.channels.isNotEmpty())
                getString(R.string.hue_area_lights, area.name, area.channels.size)
            else area.name
            val b = Button(this).apply {
                text = label
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
        val saved = areas.firstOrNull { it.id == hueStore.selectedAreaId }
        if (saved != null) {
            selectHueArea(saved)
            hueStatus.text = getString(R.string.hue_status_ready)
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
        val awayMs = SystemClock.elapsedRealtime() - backgroundedAtMs
        if (backgroundedAtMs == 0L || awayMs < HUE_RESYNC_AWAY_MS) return  // quick glance: keepalive held

        btnHueSync.isEnabled = false
        hueStatus.setText(R.string.hue_status_ready)
        hueController.restart(area) { ok, err ->
            btnHueSync.isEnabled = true
            updateHueSyncButton(ok)
            updateHueConn(if (ok) HueConn.STREAMING else HueConn.REACHABLE)
            hueStatus.text = if (ok) getString(R.string.hue_status_synced) else (err ?: getString(R.string.hue_status_ready))
            updateHueSections()
        }
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
            updateHueConn(if (ok) HueConn.STREAMING else HueConn.REACHABLE)
            hueStatus.text = if (ok) getString(R.string.hue_status_synced) else (err ?: "Failed to sync.")
            updateHueSections()
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

    private lateinit var lightControlSection: LinearLayout
    private lateinit var sceneGrid: LinearLayout
    private lateinit var brightnessSlider: SeekBar
    private var currentHueState = HueConn.DISCONNECTED

    private fun buildLightControlUI() {
        lightControlSection = findViewById(R.id.hue_light_control)
        sceneGrid = findViewById(R.id.hue_scene_grid)
        brightnessSlider = findViewById(R.id.hue_brightness)

        val dp = resources.displayMetrics.density
        val h = (dp * 40).toInt()
        val gap = (dp * 6).toInt()

        for (i in lightScenes.indices step 2) {
            val row = LinearLayout(this).apply {
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
                val btn = Button(this).apply {
                    text = scene.label
                    isAllCaps = false
                    textSize = 12f
                    setTextColor(ContextCompat.getColorStateList(this@MainActivity, R.color.btn_text))
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

    private fun updateHueSections() {
        if (!::lightControlSection.isInitialized) return
        val reachable = currentHueState == HueConn.REACHABLE || currentHueState == HueConn.STREAMING
        hueAreaSection.visibility = if (reachable && hueAreas.isNotEmpty()) View.VISIBLE else View.GONE
        lightControlSection.visibility = if (currentHueState == HueConn.REACHABLE && selectedArea != null) View.VISIBLE else View.GONE
        hueSyncSection.visibility = if (reachable && selectedArea != null) View.VISIBLE else View.GONE
    }

    private enum class HueConn { DISCONNECTED, SEARCHING, CHECKING, PAIRED, REACHABLE, STREAMING }

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
        hueConn.setTextColor(ContextCompat.getColor(this, colorRes))
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

    private fun syncMenuState() {
        updateSourceSelection()
        updateVisualizerSelection()
        updateStatus()
    }

    private fun updateSourceSelection() {
        segMic.isSelected = !systemAudioMode
        segInternal.isSelected = systemAudioMode
        internalAudioWarning.visibility = if (systemAudioMode) View.VISIBLE else View.GONE
    }

    private fun updateVisualizerSelection() {
        val current = glView.sceneIndex
        for ((b, idx, base) in visButtons) {
            b.isSelected = idx == current
            b.text = if (favourites.contains(idx)) "★ $base" else base
            if (idx == current) heroVisName.text = base
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
        val dialog = Dialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_privacy, null)
        val message = HtmlCompat.fromHtml(getString(R.string.privacy_policy_text), HtmlCompat.FROM_HTML_MODE_LEGACY)
        view.findViewById<TextView>(R.id.privacy_text).text = message

        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        dialog.show()
        configureDialogWindow(dialog)

        view.findViewById<Button>(R.id.btn_privacy_ok).setOnClickListener { dialog.dismiss() }
    }

    private fun showAboutDialog() {
        val dialog = Dialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_about, null)

        view.findViewById<TextView>(R.id.about_version).text = getString(R.string.version_fmt, appVersionName())
        
        val rate = NativeBridge.nativeGetSampleRate()
        val engine = if (systemAudioMode) {
            "AudioPlaybackCapture • $rate Hz"
        } else {
            "Oboe Engine: AAudio Active • $rate Hz"
        }
        view.findViewById<TextView>(R.id.about_engine_status).text = engine

        val licenses = HtmlCompat.fromHtml(getString(R.string.about_licenses_text), HtmlCompat.FROM_HTML_MODE_LEGACY)
        view.findViewById<TextView>(R.id.about_licenses).text = licenses

        val trademarks = HtmlCompat.fromHtml(getString(R.string.about_trademarks_text), HtmlCompat.FROM_HTML_MODE_LEGACY)
        view.findViewById<TextView>(R.id.about_trademarks).text = trademarks

        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        dialog.show()
        configureDialogWindow(dialog)
        
        view.findViewById<Button>(R.id.btn_about_ok).setOnClickListener { dialog.dismiss() }
    }

    /**
     * Enforce a maximum size for floating dialogs (90% width, 90% max-height) to
     * ensure they remain unclipped on small devices and in landscape mode.
     */
    private fun configureDialogWindow(dialog: Dialog) {
        val metrics = resources.displayMetrics
        val w = (metrics.widthPixels * 0.9).toInt()
        val h = (metrics.heightPixels * 0.9).toInt()
        
        dialog.window?.let { window ->
            val params = window.attributes
            params.width = w
            // Set height to WRAP_CONTENT but cap it at the calculated max height
            window.attributes = params
            
            // To properly cap the height while still allowing wrap_content for 
            // shorter text, we need to set the layout params on the decor view 
            // after the window is shown.
            window.setLayout(w, WindowManager.LayoutParams.WRAP_CONTENT)
            
            // Re-fetch params to apply the height cap if the wrapped height is too large.
            window.decorView.post {
                if (window.decorView.height > h) {
                    window.setLayout(w, h)
                }
            }
        }
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

    private fun checkHdrSupport() {
        // The toggle is now always visible, but its text is initialized based on support.
        updatePeakLuminance(prefs.getBoolean(KEY_PEAK_LUMINANCE, false))
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
        updatePeakLuminance(prefs.getBoolean(KEY_PEAK_LUMINANCE, false))
        if (!systemAudioMode) ensureMicAndStart()
        if (::hueController.isInitialized) hueController.paused = false
        refreshHueAfterResume()
        if (LinkSync.enabled) {
            NativeBridge.nativeLinkSetEnabled(true)
            acquireMulticastLock()
            linkHandler.post(linkStatusPoller)
        }
        if (perfOverlayEnabled) {
            perfHandler.post(perfPoller)
            perfHandler.post(perfFpsTicker)
        }
        if (huePingPollerRunning) {
            huePingHandler.removeCallbacks(huePingPoller)
            huePingHandler.post(huePingPoller)
        } else if (::hueStore.isInitialized && hueStore.loadCredentials() != null
            && !hueController.isEnabled) {
            val creds = hueStore.loadCredentials() ?: return
            hueController.setup.pingBridge(creds) { rtt ->
                if (rtt != null) {
                    updateHueConn(HueConn.REACHABLE)
                    hueStatus.text = getString(R.string.hue_status_ready)
                    startHuePingPoller()
                    if (hueAreas.isEmpty()) loadHueAreas()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
        if (!systemAudioMode) NativeBridge.nativeStop()
        if (::hueController.isInitialized) hueController.paused = true
        backgroundedAtMs = SystemClock.elapsedRealtime()
        perfHandler.removeCallbacks(perfPoller)
        perfHandler.removeCallbacks(perfFpsTicker)
        huePingHandler.removeCallbacks(huePingPoller)
        linkHandler.removeCallbacks(linkStatusPoller)
        if (LinkSync.enabled) {
            NativeBridge.nativeLinkSetEnabled(false)
            releaseMulticastLock()
        }
    }

    override fun onDestroy() {
        stopHuePingPoller()
        perfHandler.removeCallbacks(perfPoller)
        perfHandler.removeCallbacks(perfFpsTicker)
        if (::hueController.isInitialized) hueController.disable()
        if (::hapticController.isInitialized) hapticController.release()
        linkHandler.removeCallbacks(linkStatusPoller)
        NativeBridge.nativeLinkSetEnabled(false)
        releaseMulticastLock()
        NativeBridge.nativeStop()
        unregisterReceiver(captureStopReceiver)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS = "visualizer_prefs"
        private const val KEY_BURNIN = "burn_in_enabled"
        private const val KEY_GLOW = "glow_strength"   // string preset (was a boolean key)
        private const val KEY_HAPTICS = "haptics_enabled"
        private const val KEY_BEAT_SENS = "beat_sensitivity"
        private const val KEY_THEME = "color_theme"
        private const val KEY_LINK = "ableton_link_enabled"
        private const val KEY_LINK_NOTIFY = "ableton_link_notifications"
        private const val KEY_LINK_ANTICIPATE = "ableton_link_anticipate"
        private const val KEY_LINK_BAR_OFFSET = "ableton_link_bar_offset"
        private const val KEY_LINK_EXTRAS = "ableton_link_experimental_extras"
        private const val KEY_ADV_LINK_BEAT_FLASH = "adv_link_beat_flash"
        private const val KEY_ADV_COLOUR = "adv_colour_split"
        private const val KEY_ADV_GLOW = "adv_resting_glow"
        private const val KEY_ADV_AUDIO_BRIGHT = "adv_audio_brightness"
        private const val KEY_ADV_AUDIO_FLASH = "adv_audio_flash"
        private const val KEY_ADV_HUE_LOOKAHEAD = "adv_hue_lookahead"
        private const val KEY_PERF_OVERLAY = "perf_overlay_enabled"
        private const val KEY_SCENE_LABEL = "scene_label_enabled"
        // Background longer than this and a Hue entertainment stream has likely
        // timed out on the bridge, so we rebuild it on resume rather than trust it.
        private const val HUE_RESYNC_AWAY_MS = 3000L
        // FPS readout: snap to a vsync rate within this many fps (kills 60↔61
        // flicker); off a locked rate, require this much change before re-drawing.
        private val LOCKED_RATES = intArrayOf(60, 90, 120, 144)
        private const val FPS_SNAP_TOL = 1.0f
        private const val FPS_HYSTERESIS = 0.7f
        private const val KEY_PEAK_LUMINANCE = "peak_luminance_enabled"
        private const val KEY_FAVOURITES = "favourite_scenes"
        private const val KEY_SCREENSHARE_RATIONALE = "screenshare_rationale_shown"
        private const val SWIPE_DOWN_VELOCITY = 1200f
        private const val TAB_VISUALS = 0
        private const val TAB_LIGHTING = 1
        private const val TAB_SETTINGS = 2
        private const val SPLASH_MASK_MS = 120L    // black mask over GL init, then fade
        private const val SPLASH_FADE_MS = 350L
        private const val INTRO_HINT_DURATION_MS = 5000L
        private const val PERMISSION_FALLBACK_MS = 6000L  // ask anyway if intro never finishes
    }
}
