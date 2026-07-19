package com.lowlatency.visualizer

import android.app.Dialog
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.text.HtmlCompat
import com.lowlatency.visualizer.ui.AudioSourceController
import com.lowlatency.visualizer.ui.InputDeviceController
import com.lowlatency.visualizer.ui.ToneController
import com.lowlatency.visualizer.ui.DisplayModeController
import com.lowlatency.visualizer.ui.FeelTheSpeedController
import com.lowlatency.visualizer.ui.LightingController
import com.lowlatency.visualizer.ui.LinkSyncController
import com.lowlatency.visualizer.ui.LocalPlaybackController
import com.lowlatency.visualizer.ui.MenuDiscoveryController
import com.lowlatency.visualizer.ui.MenuSheetController
import com.lowlatency.visualizer.ui.OverlayMetrics
import com.lowlatency.visualizer.ui.PerfOverlayController
import com.lowlatency.visualizer.ui.ScenesController
import com.lowlatency.visualizer.ui.ShuffleController

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
    private lateinit var splashOverlay: View
    private lateinit var introHint: View
    private lateinit var btnBurnin: Button
    private lateinit var btnGlowOff: Button
    private lateinit var btnGlowSubtle: Button
    private lateinit var btnGlowStandard: Button
    private lateinit var btnGlowIntense: Button
    private lateinit var btnBeatDetection: Button
    private lateinit var btnHaptics: Button
    private lateinit var btnFourFour: Button
    private lateinit var btnSensLow: Button
    private lateinit var btnSensStandard: Button
    private lateinit var btnSensHigh: Button
    private lateinit var btnThemeDefault: Button
    private lateinit var btnThemeNeon: Button
    private lateinit var btnThemeWarm: Button
    private lateinit var btnThemeCool: Button
    private lateinit var btnThemeMono: Button
    private lateinit var btnPrivacyPolicy: Button
    private lateinit var btnAbout: Button
    private lateinit var btnFeedback: Button
    private var activeDialog: android.app.Dialog? = null
    private lateinit var btnPeakLuminance: Button
    private lateinit var groupPeakLuminance: View
    private lateinit var perfOverlayController: PerfOverlayController
    private lateinit var menuSheetController: MenuSheetController
    private lateinit var menuDiscoveryController: MenuDiscoveryController
    private lateinit var displayModeController: DisplayModeController
    private lateinit var shuffleController: ShuffleController
    private lateinit var secondaryDisplayController: com.lowlatency.visualizer.ui.SecondaryDisplayController
    private lateinit var scenesController: ScenesController
    private lateinit var linkSyncController: LinkSyncController
    private lateinit var audioSourceController: AudioSourceController
    private lateinit var inputDeviceController: InputDeviceController
    private lateinit var toneController: ToneController
    private lateinit var feelTheSpeedController: FeelTheSpeedController
    private lateinit var hapticController: HapticController
    private lateinit var prefs: SharedPreferences

    // --- Settings tabs ---
    private lateinit var sectionTabs: com.lowlatency.visualizer.ui.SectionTabsView
    private lateinit var tabVisualizers: View
    private lateinit var tabLighting: View
    private lateinit var tabSettings: View
    private var currentTab = TAB_VISUALS
    private lateinit var btnCastDisplay: Button
    private lateinit var castOverlay: TextView

    // --- Smart lighting: all brand UI/state lives in LightingController ---
    private lateinit var lightingController: LightingController

    private var backgroundedAtMs = 0L
    private var micStarted = false          // has the mic stream gone live this session?
    private var introSequenceDone = false   // intro/hint finished — safe to show the menu cue
    
    // Local playback lives in its own controller; the *source* state lives in
    // AudioSourceController — single source of truth.
    private lateinit var localPlaybackController: LocalPlaybackController

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
        checkHdrSupport()
        initControllers()

        // Hold the runtime-permission dialog back until the intro has finished, but
        // only when it would actually appear — i.e. a first launch (mic not yet
        // granted) where the intro is going to play. If the mic is already granted
        // the request is invisible, so fire it now and let audio be live behind the
        // shatter. wireSplash() fires the deferred request from onIntroFinished.
        if (glView.willPlayIntro && !audioSourceController.hasMicPermission()) {
            audioSourceController.deferPermissionRequest()
            // Safety net: if the intro never reports finished (e.g. surface never
            // created), still ask after a delay so the app isn't stuck without audio.
            glView.postDelayed({ audioSourceController.fireDeferredPermissionRequest() }, PERMISSION_FALLBACK_MS)
        } else {
            audioSourceController.requestPermissionsNow()
        }
    }

    private fun bindViews() {
        glView = findViewById(R.id.gl_view)
        splashOverlay = findViewById(R.id.splash_overlay)
        introHint = findViewById(R.id.intro_hint)
        btnBurnin = findViewById(R.id.btn_burnin)
        btnGlowOff = findViewById(R.id.btn_glow_off)
        btnGlowSubtle = findViewById(R.id.btn_glow_subtle)
        btnGlowStandard = findViewById(R.id.btn_glow_standard)
        btnGlowIntense = findViewById(R.id.btn_glow_intense)
        btnBeatDetection = findViewById(R.id.btn_beat_detection)
        btnHaptics = findViewById(R.id.btn_haptics)
        btnFourFour = findViewById(R.id.btn_four_four)
        btnSensLow = findViewById(R.id.btn_sens_low)
        btnSensStandard = findViewById(R.id.btn_sens_standard)
        btnSensHigh = findViewById(R.id.btn_sens_high)
        btnThemeDefault = findViewById(R.id.btn_theme_default)
        btnThemeNeon = findViewById(R.id.btn_theme_neon)
        btnThemeWarm = findViewById(R.id.btn_theme_warm)
        btnThemeCool = findViewById(R.id.btn_theme_cool)
        btnThemeMono = findViewById(R.id.btn_theme_mono)
        btnPrivacyPolicy = findViewById(R.id.btn_privacy_policy)
        btnAbout = findViewById(R.id.btn_about)
        btnFeedback = findViewById(R.id.btn_feedback)
        btnPeakLuminance = findViewById(R.id.btn_peak_luminance)
        groupPeakLuminance = findViewById(R.id.group_peak_luminance)
        sectionTabs = findViewById(R.id.section_tabs)
        tabVisualizers = findViewById(R.id.tab_visualizers)
        tabLighting = findViewById(R.id.tab_lighting)
        tabSettings = findViewById(R.id.tab_settings)
        btnCastDisplay = findViewById(R.id.btn_cast_display)
        castOverlay = findViewById(R.id.cast_overlay)
        
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
    }

    /** Audio-source switching + the first-run "feel the speed" reveal (built first). */
    /** The two floating canvas overlays (tone controls, media bar). */
    private fun initCanvasOverlayControllers() {
        toneController = ToneController(
            activity = this,
            isToneMode = { audioSourceController.isToneMode },
            onEnterTone = { audioSourceController.enterTone() },
            isMenuOpen = { menuSheetController.isOpen },
            closeMenu = { menuSheetController.close() },
            onOverlayLayoutChanged = {
                if (::scenesController.isInitialized) scenesController.repositionSceneLabel()
            },
        )
        toneController.bind()

        localPlaybackController = LocalPlaybackController(
            activity = this,
            prefs = prefs,
            audioSource = audioSourceController,
            isMenuOpen = { menuSheetController.isOpen },
            closeMenu = { menuSheetController.close() },
            onBarLayoutChanged = {
                if (::scenesController.isInitialized) scenesController.repositionSceneLabel()
            },
        )
        localPlaybackController.bind()
    }

    /** Fan-out after every source transition (guards: controllers init in order). */
    private fun handleSourceChanged() {
        if (::scenesController.isInitialized) onAudioSourceChanged()
        if (::linkSyncController.isInitialized) {
            linkSyncController.setLocalPlaybackActive(audioSourceController.isLocalPlayback)
        }
        if (::inputDeviceController.isInitialized) inputDeviceController.refreshRow()
        if (::toneController.isInitialized) toneController.refresh()
    }

    private fun initAudioControllers() {
        audioSourceController = AudioSourceController(
            activity = this,
            prefs = prefs,
            onSourceChanged = { handleSourceChanged() },
            onMicStarted = {
                micStarted = true
                if (::feelTheSpeedController.isInitialized) feelTheSpeedController.onMicStarted()
            },
            onLocalFileRequested = { localPlaybackController.openFilePicker() },
            onExternalSourceRequested = {
                // Switching to mic/system: silence the player before the new
                // source starts producing (the controller flips the state).
                localPlaybackController.stopSession()
            },
            inputDeviceId = {
                if (::inputDeviceController.isInitialized) {
                    inputDeviceController.selectedDeviceId
                } else {
                    0
                }
            },
            onToneRequested = { if (::toneController.isInitialized) toneController.enter() },
            stopTone = { if (::toneController.isInitialized) toneController.stop() },
        )
        audioSourceController.bind()

        inputDeviceController = InputDeviceController(
            activity = this,
            isMicMode = {
                !audioSourceController.systemAudioMode && !audioSourceController.isLocalPlayback &&
                    !audioSourceController.isToneMode
            },
            onSelectionChanged = { audioSourceController.onInputDeviceChanged() },
        )
        inputDeviceController.bind()

        initCanvasOverlayControllers()

        feelTheSpeedController = FeelTheSpeedController(
            activity = this,
            prefs = prefs,
            vibrate = {
                if (::hapticController.isInitialized && hapticController.isSupported) {
                    hapticController.previewPulse()
                }
            },
            onComplete = { showIntroHint() },
        )
        feelTheSpeedController.bind()
    }

    /** Bottom edge of whichever top overlay is visible — transient labels
     *  (scene name flash) stack themselves below it. */
    private fun transientLabelAnchor(): Int {
        var maxBottom = 0
        if (perfOverlayController.enabled && perfOverlayController.overlayView.height > 0)
            maxBottom = perfOverlayController.overlayView.bottom
        if (::localPlaybackController.isInitialized)
            maxBottom = kotlin.math.max(maxBottom, localPlaybackController.barBottom())
        if (::toneController.isInitialized)
            maxBottom = kotlin.math.max(maxBottom, toneController.overlayBottom())
        return maxBottom
    }

    /** True when the live source carries real L/R for the stereo scope scenes:
     *  system capture, local playback, the tone generator (X/Y drives true
     *  stereo), or a genuine stereo external input (USB interface / line-in). */
    private fun hasStereoSource(): Boolean =
        audioSourceController.systemAudioMode ||
            audioSourceController.isLocalPlayback ||
            audioSourceController.isToneMode ||
            NativeBridge.nativeGetInputChannels() >= 2

    private fun initControllers() {
        initAudioControllers()

        lightingController = LightingController(
            activity = this,
            prefs = prefs,
            backgroundedAtMs = { backgroundedAtMs },
        )
        lightingController.bind()

        linkSyncController = LinkSyncController(
            activity = this,
            prefs = prefs,
            onLinkEnabledChanged = {
                if (::lightingController.isInitialized) lightingController.refreshAdvancedVisibility()
            },
        )
        linkSyncController.bind()
        wireGlAudioSinks()

        perfOverlayController = PerfOverlayController(
            activity = this,
            glView = glView,
            prefs = prefs,
            isSystemAudioMode = { audioSourceController.systemAudioMode },
            hueStats = {
                if (::lightingController.isInitialized) lightingController.huePerfStats()
                else PerfOverlayController.HueStats(false, 0L, 0L, -1L)
            },
            sceneName = {
                if (::scenesController.isInitialized) scenesController.currentSceneName() else ""
            },
        )
        perfOverlayController.bind()

        initMenuControllers()

        displayModeController = DisplayModeController(
            this,
            prefs,
            onSwipeScene = { dir -> glView.cycleScene(dir) },
            onOpenMenu = { menuSheetController.openOverlay() },
            onCloseMenu = { menuSheetController.close() },
        )
        displayModeController.bind()

        shuffleController = ShuffleController(this, prefs, advanceScene = { glView.shuffleScene() })
        shuffleController.bind()

        scenesController = ScenesController(
            activity = this,
            glView = glView,
            prefs = prefs,
            isMenuOpen = { menuSheetController.isOpen },
            perfOverlayBottom = { transientLabelAnchor() },
            onManualSceneChange = { shuffleController.onSceneChanged() },
            isStereoAudio = { hasStereoSource() },
            onScrubPreview = { active -> menuSheetController.setScrubPreview(active) },
            onCloseMenu = { menuSheetController.close() },
        )
        scenesController.bind()

        secondaryDisplayController = com.lowlatency.visualizer.ui.SecondaryDisplayController(this, glView, btnCastDisplay, castOverlay)
        secondaryDisplayController.bind()
    }

    /** The settings sheet plus its first-run "swipe up" discovery cue. */
    private fun initMenuControllers() {
        menuSheetController = MenuSheetController(
            activity = this,
            glView = glView,
            onBeforeOpen = {
                syncMenuState()
                hideIntroHintNow()
                if (::localPlaybackController.isInitialized) localPlaybackController.hideBar()
                if (::toneController.isInitialized) toneController.hideOverlay()
                if (::menuDiscoveryController.isInitialized) menuDiscoveryController.onMenuOpened()
                if (::scenesController.isInitialized) scenesController.onMenuOpened()
            },
        )
        menuSheetController.bind()

        menuDiscoveryController =
            MenuDiscoveryController(this, prefs, isMenuOpen = { menuSheetController.isOpen })
        menuDiscoveryController.bind()
    }

    /** The app's versionName from the manifest/Gradle (e.g. "1.1"). */
    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    /**
     * The VELO wordmark is an HDR particle cloud rendered by the GL engine
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
                audioSourceController.fireDeferredPermissionRequest()
                afterIntro()
            }
        } else {
            splashOverlay.postDelayed({ afterIntro() }, SPLASH_MASK_MS + SPLASH_FADE_MS)
        }
    }

    /** First launch plays the "feel the speed" moment (which chains to the hint); after that, just the hint. */
    private fun afterIntro() {
        if (feelTheSpeedController.shouldPlay()) feelTheSpeedController.start(micStarted)
        else showIntroHint()
    }

    /** True when the OS "remove animations" setting is on (animator scale 0). */
    private fun isReducedMotion(): Boolean = try {
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (_: Exception) {
        false
    }

    /** Show a brief, non-blocking gesture hint after the splash fades. */
    private fun showIntroHint() {
        // Same width family as the media bar / tone overlay, fit to this screen.
        introHint.layoutParams = introHint.layoutParams.apply {
            width = OverlayMetrics.widthPx(resources)
        }
        introHint.visibility = View.VISIBLE
        introHint.animate().alpha(1f).setDuration(600).start()
        
        introHint.postDelayed({
            if (menuSheetController.isOpen) {
                // If the user already opened the menu, just hide it immediately
                introHint.visibility = View.GONE
                onIntroHintDone()
            } else {
                introHint.animate().alpha(0f).setDuration(1000)
                    .withEndAction { introHint.visibility = View.GONE; onIntroHintDone() }
                    .start()
            }
        }, INTRO_HINT_DURATION_MS)
    }

    /** The menu owns the screen: opening it dismisses the gesture hint early
     *  (whoever opened the menu has already discovered the gestures). */
    private fun hideIntroHintNow() {
        if (introSequenceDone || introHint.visibility != View.VISIBLE) return
        introHint.animate().alpha(0f).setDuration(200)
            .withEndAction {
                introHint.visibility = View.GONE
                onIntroHintDone()
            }
            .start()
    }

    /** Gesture hint has run its course — now it's safe to surface the persistent menu cue. */
    private fun onIntroHintDone() {
        if (introSequenceDone) return   // timeout + early-dismiss can both land here
        introSequenceDone = true
        if (::menuDiscoveryController.isInitialized) menuDiscoveryController.reveal()
    }

    // ----- Gestures: swipe-up opens the menu, swipe-down / tap-outside closes -----

    private fun wireGestures() {
        // The settings sheet (interactive swipe-up drag, scrim, swipe-down dismiss,
        // blur) is owned by menuSheetController, bound in onCreate.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    // A sheet opened over Ambient Mode closes first (back to ambient);
                    // a second back then exits ambient itself.
                    menuSheetController.isOpen -> menuSheetController.close()
                    displayModeController.isActive -> displayModeController.exit()
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })
        
        glView.onTap = {
            // Route the canvas tap to whichever source owns an overlay; each
            // guards on its own mode, so the other is a no-op.
            if (::toneController.isInitialized && audioSourceController.isToneMode) {
                toneController.onCanvasTap()
            } else {
                localPlaybackController.onCanvasTap()
            }
        }
    }

    // ----- Settings tabs: Visuals | Lighting | Settings -----

    private fun wireTabs() {
        sectionTabs.setItems(
            listOf(
                getString(R.string.tab_visualizers),
                getString(R.string.tab_lighting),
                getString(R.string.tab_settings),
            ),
            TAB_VISUALS,
        )
        sectionTabs.onSelect = { selectTab(it) }
        selectTab(TAB_VISUALS)   // default to the Visuals tab
    }

    private fun selectTab(tab: Int) {
        currentTab = tab
        sectionTabs.setActive(tab)
        for ((view, id) in listOf(
            tabVisualizers to TAB_VISUALS,
            tabLighting to TAB_LIGHTING,
            tabSettings to TAB_SETTINGS,
        )) {
            val active = tab == id
            if (active && view.visibility != View.VISIBLE) {
                view.visibility = View.VISIBLE
                view.alpha = 0f
                view.animate().alpha(1f).setDuration(180L).start()
            } else if (!active) {
                view.animate().cancel()
                view.alpha = 1f
                view.visibility = View.GONE
            }
        }
    }

    // ----- Menu controls -----

    private fun wireMenuControls() {
        // Burn-in protection toggle (persisted, default off).
        val burnIn = prefs.getBoolean(KEY_BURNIN, false)
        glView.burnInEnabled = burnIn
        updateBurnInButton(burnIn)
        btnBurnin.setOnClickListener {
            val enabled = !glView.burnInEnabled
            glView.burnInEnabled = enabled
            prefs.edit().putBoolean(KEY_BURNIN, enabled).apply()
            updateBurnInButton(enabled)
        }

        // Performance overlay toggle is owned by perfOverlayController (bound in onCreate).

        // Peak luminance (HDR+) toggle (persisted, default off).
        val peak = prefs.getBoolean(KEY_PEAK_LUMINANCE, true)
        updatePeakLuminance(peak)
        btnPeakLuminance.setOnClickListener {
            val enabled = !prefs.getBoolean(KEY_PEAK_LUMINANCE, true)
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
        btnThemeDefault.setOnClickListener { setTheme(ThemeSettings.Theme.DEFAULT) }
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
        
        btnFeedback.setOnClickListener {
            val subject = android.net.Uri.encode("Feedback: Velo Visualiser ${appVersionName()}")
            val bodyText = "\n\n\n--- Debug Info ---\n" +
                           "App Version: ${appVersionName()}\n" +
                           "OS Version: Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n" +
                           "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n"
            val body = android.net.Uri.encode(bodyText)
            
            val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                data = android.net.Uri.parse("mailto:velovisualiser.support@gmail.com?subject=$subject&body=$body")
            }
            try {
                startActivity(intent)
            } catch (e: android.content.ActivityNotFoundException) {
                android.widget.Toast.makeText(this, "No email app found.", android.widget.Toast.LENGTH_SHORT).show()
            }
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

        // Master Beat Detection switch (persisted, default on). Governs the whole
        // audio beat-reactive layer; greys out its sub-controls when off and hides
        // beat-only scenes from the picker. The renderer and picker read
        // BeatSettings.detectionEnabled directly. Ableton Link is independent.
        BeatSettings.detectionEnabled = prefs.getBoolean(KEY_BEAT_DETECTION, true)
        updateBeatDetectionButton(BeatSettings.detectionEnabled)
        setBeatControlsEnabled(BeatSettings.detectionEnabled)
        btnBeatDetection.setOnClickListener {
            val enabled = !BeatSettings.detectionEnabled
            BeatSettings.detectionEnabled = enabled
            prefs.edit().putBoolean(KEY_BEAT_DETECTION, enabled).apply()
            updateBeatDetectionButton(enabled)
            setBeatControlsEnabled(enabled)
            // Beat-only scenes (Beat Pulse, Beat Fireworks) appear/disappear with it.
            if (::scenesController.isInitialized) scenesController.refreshAvailableScenes()
        }

        // 4/4 Music Mode: grid-lock the beat to a steady four-to-the-floor
        // signature (persisted, default off). Audio-only alternative to Link; the
        // renderer falls back to the reactive detector whenever it isn't confident.
        FourFourSync.enabled = prefs.getBoolean(KEY_FOUR_FOUR, false)
        updateFourFourButton(FourFourSync.enabled)
        btnFourFour.setOnClickListener {
            val enabled = !FourFourSync.enabled
            FourFourSync.enabled = enabled
            prefs.edit().putBoolean(KEY_FOUR_FOUR, enabled).apply()
            updateFourFourButton(enabled)
        }

        // Beat sensitivity presets (persisted). Drives every BeatDetector
        // (fireworks, haptics, starscape flashes), mapped per audio source.
        BeatSettings.preset = BeatSettings.Sensitivity.fromKey(prefs.getString(KEY_BEAT_SENS, null))
        updateBeatSensSelection()
        btnSensLow.setOnClickListener { setBeatSensitivity(BeatSettings.Sensitivity.LOW) }
        btnSensStandard.setOnClickListener { setBeatSensitivity(BeatSettings.Sensitivity.STANDARD) }
        btnSensHigh.setOnClickListener { setBeatSensitivity(BeatSettings.Sensitivity.HIGH) }
    }

    private fun updateFourFourButton(enabled: Boolean) {
        btnFourFour.isSelected = enabled
        btnFourFour.setText(if (enabled) R.string.four_four_on else R.string.four_four_off)
    }

    private fun updateBeatDetectionButton(enabled: Boolean) {
        btnBeatDetection.isSelected = enabled
        btnBeatDetection.setText(if (enabled) R.string.beat_detection_on else R.string.beat_detection_off)
    }

    /** Grey out the beat sub-controls (sensitivity, haptics, 4/4) when detection
     *  is off, since they're all downstream of it. Haptics also needs device support. */
    private fun setBeatControlsEnabled(enabled: Boolean) {
        val dim = if (enabled) 1f else 0.4f
        for (b in listOf(btnSensLow, btnSensStandard, btnSensHigh, btnFourFour)) {
            b.isEnabled = enabled
            b.alpha = dim
        }
        val hapticsOn = enabled && hapticController.isSupported
        btnHaptics.isEnabled = hapticsOn
        btnHaptics.alpha = if (hapticsOn) 1f else 0.4f
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
            findViewById<TextView>(R.id.peak_luminance_hint_text).setText(R.string.peak_luminance_hint)
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
        btnThemeDefault.isSelected = t == ThemeSettings.Theme.DEFAULT
        btnThemeNeon.isSelected = t == ThemeSettings.Theme.NEON
        btnThemeWarm.isSelected = t == ThemeSettings.Theme.WARM
        btnThemeCool.isSelected = t == ThemeSettings.Theme.COOL
        btnThemeMono.isSelected = t == ThemeSettings.Theme.MONO
    }

    private fun updateHapticsButton(enabled: Boolean) {
        btnHaptics.isSelected = enabled
        btnHaptics.setText(if (enabled) R.string.haptics_on else R.string.haptics_off)
    }

    /**
     * Route the GL render-thread audio taps. Bands and Link beats fan out to the
     * lighting brands via [lightingController]; haptics and the on-screen beat/drop
     * diagnostic dots stay here (they're not lighting concerns).
     */
    private fun wireGlAudioSinks() {
        glView.bandsSink = { low, mid, high -> lightingController.onBands(low, mid, high) }
        glView.pcmBeatSink = { pcm ->
            hapticController.onPcm(pcm)
            feelTheSpeedController.onPcm(pcm)
        }
        glView.onLinkBeat = {
            lightingController.onLinkBeat()
            // Step the virtual bar + pulse the beat dot — only while the menu is
            // open (the diagnostic readout is hidden otherwise), to stay cheap.
            if (menuSheetController.isOpen) linkSyncController.pulseBeat()
        }
        glView.onDrop = { if (menuSheetController.isOpen) linkSyncController.pulseDrop() }
    }

    private fun syncMenuState() {
        if (::audioSourceController.isInitialized) audioSourceController.refreshSelection()
        if (::scenesController.isInitialized) scenesController.updateSelection()
    }

    /**
     * React to an audio-source change (mic ⇄ internal). The seg toggle itself is
     * owned by [AudioSourceController]; here we refresh everything that merely
     * *depends* on the source — lighting-tab availability, beat haptics, the shared
     * beat sensitivity. Light sync + haptics are mic-only.
     */
    private fun onAudioSourceChanged() {
        val systemAudio = audioSourceController.systemAudioMode
        sectionTabs.disabled = if (systemAudio) setOf(TAB_LIGHTING) else emptySet()
        
        glView.onAudioSourceChanged()
        scenesController.onAudioSourceChanged()

        if (systemAudio) {
            if (::lightingController.isInitialized) lightingController.onSystemAudioEngaged()
            if (currentTab == TAB_LIGHTING) {
                selectTab(TAB_VISUALS)
            }
        }
        updateStatus()
    }

    private fun updateStatus() {
        val systemAudio = audioSourceController.systemAudioMode
        // Beat detection is hotter on digitally mastered sources — local files,
        // system capture and the tone generator all arrive full-scale, so the
        // shared sensitivity uses the raised floor for all of them.
        BeatSettings.systemAudio = systemAudio ||
            audioSourceController.isLocalPlayback || audioSourceController.isToneMode

        // Beat-haptics are mic-only (system-audio capture is buffered → off-beat).
        // Gate the controller and grey the toggle when on internal audio.
        if (::hapticController.isInitialized) {
            hapticController.setSystemAudio(systemAudio)
            val available = hapticController.isSupported && !systemAudio
            btnHaptics.isEnabled = available
            btnHaptics.alpha = if (available) 1f else 0.4f
        }
    }

    /** Forwarded to [AudioSourceController]; called by SecondaryDisplayController. */
    fun evaluateMicState() {
        if (::audioSourceController.isInitialized) audioSourceController.evaluateMicState()
    }

    private fun showPrivacyPolicy() {
        val dialog = Dialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_privacy, null)
        val rawText = getString(R.string.privacy_policy_text).replace("\n", "<br>")
        val message = HtmlCompat.fromHtml(rawText, HtmlCompat.FROM_HTML_MODE_LEGACY)
        view.findViewById<TextView>(R.id.privacy_text).text = message

        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        activeDialog = dialog
        dialog.setOnDismissListener { if (activeDialog === dialog) activeDialog = null }
        
        dialog.show()
        configureDialogWindow(dialog)

        view.findViewById<Button>(R.id.btn_privacy_ok).setOnClickListener { dialog.dismiss() }
    }

    private fun showAboutDialog() {
        val dialog = Dialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_about, null)

        view.findViewById<TextView>(R.id.about_version).text = getString(R.string.version_fmt, appVersionName())
        
        val rate = NativeBridge.nativeGetSampleRate()
        val engine = if (audioSourceController.systemAudioMode) {
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
        
        activeDialog = dialog
        dialog.setOnDismissListener { if (activeDialog === dialog) activeDialog = null }
        
        dialog.show()
        configureDialogWindow(dialog)
        
        view.findViewById<Button>(R.id.btn_about_ok).setOnClickListener { dialog.dismiss() }
    }

    /**
     * Enforce a maximum size for floating dialogs (90% width, 90% max-height) to
     * ensure they remain unclipped on small devices and in landscape mode. Width
     * is additionally capped to a fixed column on wide displays (tablets /
     * unfolded foldables), matching the settings sheet's content cap.
     */
    private fun configureDialogWindow(dialog: Dialog) {
        val metrics = resources.displayMetrics
        val capPx = (DIALOG_MAX_WIDTH_DP * metrics.density).toInt()
        val w = minOf((metrics.widthPixels * 0.9).toInt(), capPx)
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
        updatePeakLuminance(prefs.getBoolean(KEY_PEAK_LUMINANCE, true))
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
        // Ambient Mode re-fits its layout live (landscape sits data beside the clock).
        if (::displayModeController.isInitialized) displayModeController.onOrientationChanged()
        // The settings sheet re-fits its content column (width cap on wide displays).
        if (::menuSheetController.isInitialized) menuSheetController.onConfigurationChanged()
        if (::scenesController.isInitialized) scenesController.onConfigurationChanged()
        // The floating overlays re-fit to the live window width (fold/unfold, rotation).
        if (::localPlaybackController.isInitialized) localPlaybackController.onConfigurationChanged()
        if (::toneController.isInitialized) toneController.onConfigurationChanged()
        
        activeDialog?.let { configureDialogWindow(it) }
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        updatePeakLuminance(prefs.getBoolean(KEY_PEAK_LUMINANCE, true))
        if (::audioSourceController.isInitialized) audioSourceController.onResume()
        if (::toneController.isInitialized) toneController.onResume()
        if (::linkSyncController.isInitialized) linkSyncController.onResume()
        if (::perfOverlayController.isInitialized) perfOverlayController.onResume()
        if (::lightingController.isInitialized) lightingController.onResume()
        if (::displayModeController.isInitialized) displayModeController.onResume()
        if (::shuffleController.isInitialized) shuffleController.onResume()
        if (introSequenceDone && ::menuDiscoveryController.isInitialized) menuDiscoveryController.reveal()
    }

    override fun onPause() {
        super.onPause()
        if (::localPlaybackController.isInitialized) localPlaybackController.onPause()
        glView.onPause()
        if (::audioSourceController.isInitialized) audioSourceController.onPause()
        if (::toneController.isInitialized) toneController.onPause()
        backgroundedAtMs = SystemClock.elapsedRealtime()
        if (::perfOverlayController.isInitialized) perfOverlayController.onPause()
        if (::lightingController.isInitialized) lightingController.onPause()
        if (::displayModeController.isInitialized) displayModeController.onPause()
        if (::shuffleController.isInitialized) shuffleController.onPause()
        if (::linkSyncController.isInitialized) linkSyncController.onPause()
        if (::menuDiscoveryController.isInitialized) menuDiscoveryController.onPause()
    }

    override fun onDestroy() {
        if (::perfOverlayController.isInitialized) perfOverlayController.onDestroy()
        if (::lightingController.isInitialized) lightingController.onDestroy()
        if (::displayModeController.isInitialized) displayModeController.onDestroy()
        if (::shuffleController.isInitialized) shuffleController.onDestroy()
        if (::secondaryDisplayController.isInitialized) secondaryDisplayController.onDestroy()
        if (::hapticController.isInitialized) hapticController.release()
        if (::linkSyncController.isInitialized) linkSyncController.onDestroy()
        if (::audioSourceController.isInitialized) audioSourceController.onDestroy()
        if (::inputDeviceController.isInitialized) inputDeviceController.onDestroy()
        if (::toneController.isInitialized) toneController.onDestroy()
        if (::feelTheSpeedController.isInitialized) feelTheSpeedController.onDestroy()
        if (::menuDiscoveryController.isInitialized) menuDiscoveryController.onDestroy()
        // Join the decode thread before tearing the engine down — the playback
        // stream is owned by that thread and must not be closed under it.
        if (::localPlaybackController.isInitialized) localPlaybackController.onDestroy()
        NativeBridge.nativeStop()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS = "visualizer_prefs"
        private const val KEY_BURNIN = "burn_in_enabled"
        private const val KEY_GLOW = "glow_strength"   // string preset (was a boolean key)
        private const val KEY_HAPTICS = "haptics_enabled"
        private const val KEY_FOUR_FOUR = "four_four_mode_enabled"
        private const val KEY_BEAT_DETECTION = "beat_detection_enabled"
        private const val KEY_BEAT_SENS = "beat_sensitivity"
        private const val KEY_THEME = "color_theme"
        private const val KEY_PEAK_LUMINANCE = "peak_luminance_enabled"
        private const val TAB_VISUALS = 0
        private const val TAB_LIGHTING = 1
        private const val TAB_SETTINGS = 2
        private const val DIALOG_MAX_WIDTH_DP = 560f  // dialog column cap on wide displays
        private const val SPLASH_MASK_MS = 120L    // black mask over GL init, then fade
        private const val SPLASH_FADE_MS = 350L
        private const val INTRO_HINT_DURATION_MS = 5000L
        private const val PERMISSION_FALLBACK_MS = 6000L  // ask anyway if intro never finishes
    }
}
