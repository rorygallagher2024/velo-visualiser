package com.lowlatency.visualizer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

/**
 * UI shell. Hosts the OpenGL canvas plus a translucent overlay layer.
 * Refactored to delegate responsibilities to manager classes.
 */
class MainActivity : AppCompatActivity() {

    lateinit var glView: VisualizerSurfaceView
    private lateinit var splashOverlay: View
    private lateinit var introHint: View
    private lateinit var firstBootOverlay: View
    
    // Managers
    lateinit var settingsManager: SettingsManager
    lateinit var menuManager: MenuManager
    lateinit var linkManager: LinkManager
    lateinit var hueManager: HueManager
    lateinit var audioManager: AudioManager

    private var lastHuePackets = 0L
    val linkHandler = Handler(Looper.getMainLooper())
    private val perfHandler = Handler(Looper.getMainLooper())
    private val perfPoller = object : Runnable {
        override fun run() {
            updatePerfOverlay()
            perfHandler.postDelayed(this, 500L)
        }
    }

    private val captureStopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (audioManager.systemAudioMode) {
                audioManager.selectMicrophone()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        configureHdrWindow()
        selectHighestRefreshRate()
        setContentView(R.layout.activity_main)

        // Initialize Managers
        settingsManager = SettingsManager(this)
        menuManager = MenuManager(this)
        linkManager = LinkManager(this)
        hueManager = HueManager(this)
        audioManager = AudioManager(this)

        bindViews()
        
        // Setup Managers
        hapticController = HapticController(this)
        settingsManager.setup(glView, hapticController)
        hueManager.setup()
        menuManager.setup()
        linkManager.setup()
        audioManager.setup()
        
        wireSinks()

        wireSplash()
        wireFirstBoot()
        checkHdrSupport()

        ContextCompat.registerReceiver(
            this,
            captureStopReceiver,
            IntentFilter(AudioCaptureService.ACTION_STOPPED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Initial permission handling
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hasMic = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (glView.willPlayIntro && !hasMic) {
            audioManager.deferredPermissionRequest = true
            glView.postDelayed({ audioManager.fireDeferredPermissionRequest() }, PERMISSION_FALLBACK_MS)
        } else {
            audioManager.requestPermissions.launch(audioManager.buildPermissionList())
        }
    }

    private fun bindViews() {
        glView = findViewById(R.id.gl_view)
        splashOverlay = findViewById(R.id.splash_overlay)
        introHint = findViewById(R.id.intro_hint)
        firstBootOverlay = findViewById(R.id.first_boot_overlay)

        settingsManager.bindViews(window.decorView)
        menuManager.bindViews(window.decorView)
        linkManager.bindViews(window.decorView)
        hueManager.bindViews(window.decorView)
        audioManager.bindViews(window.decorView)
    }

    private fun wireSplash() {
        glView.introEnabled = !isReducedMotion()
        splashOverlay.postDelayed({
            splashOverlay.animate().alpha(0f).setDuration(SPLASH_FADE_MS)
                .withEndAction { splashOverlay.visibility = View.GONE }
                .start()
        }, SPLASH_MASK_MS)

        if (glView.introEnabled) {
            glView.onIntroFinished = {
                showIntroHint()
                audioManager.fireDeferredPermissionRequest()
            }
        } else {
            splashOverlay.postDelayed({ showIntroHint() }, SPLASH_MASK_MS + SPLASH_FADE_MS)
        }
    }

    private fun isReducedMotion(): Boolean = try {
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (_: Exception) {
        false
    }

    private fun showIntroHint() {
        introHint.visibility = View.VISIBLE
        introHint.animate().alpha(1f).setDuration(600).start()
        introHint.postDelayed({
            if (menuManager.menuOpen) {
                introHint.visibility = View.GONE
            } else {
                introHint.animate().alpha(0f).setDuration(1000)
                    .withEndAction { introHint.visibility = View.GONE }
                    .start()
            }
        }, INTRO_HINT_DURATION_MS)
    }

    private fun wireFirstBoot() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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

    fun syncMenuState() {
        updateSourceSelection()
        menuManager.updateVisualizerSelection()
        updateStatus()
    }

    fun updateSourceSelection() {
        audioManager.updateSourceSelection()
    }

    fun updateStatus() {
        BeatSettings.systemAudio = audioManager.systemAudioMode
        if (::hapticController.isInitialized) {
            hapticController.setSystemAudio(audioManager.systemAudioMode)
            val available = hapticController.isSupported && !audioManager.systemAudioMode
            val btnHaptics = findViewById<Button>(R.id.btn_haptics)
            btnHaptics.isEnabled = available
            btnHaptics.alpha = if (available) 1f else 0.4f
        }
    }

    lateinit var hapticController: HapticController

    fun updateAdvancedVisibility() {
        val relevant = hueManager.hueController.isEnabled
        settingsManager.updateAdvancedVisibility(relevant)
    }

    fun startPerfPoller() {
        perfHandler.removeCallbacks(perfPoller)
        perfHandler.post(perfPoller)
    }

    fun stopPerfPoller() {
        perfHandler.removeCallbacks(perfPoller)
    }

    private fun updatePerfOverlay() {
        if (!settingsManager.perfOverlayEnabled) return

        val fps = glView.rendererFps
        val frameMs = glView.rendererFrameTimeMs
        val audioMs = NativeBridge.nativeGetAudioCallbackMs()
        val rate = NativeBridge.nativeGetSampleRate()

        val sb = android.text.SpannableStringBuilder()

        fun appendSection(label: String, value: String, color: Int = 0) {
            val start = sb.length
            sb.append(label.uppercase() + " ")
            sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, sb.length, 0)
            sb.setSpan(android.text.style.ForegroundColorSpan(getColor(R.color.text_dim)), start, sb.length, 0)
            
            val valStart = sb.length
            sb.append(value)
            if (color != 0) {
                sb.setSpan(android.text.style.ForegroundColorSpan(color), valStart, sb.length, 0)
            }
            sb.append("\n")
        }

        val fpsColor = when {
            fps < 30f -> 0xFFFF4444.toInt()
            fps < 55f -> 0xFFFFBB33.toInt()
            else -> 0xFF26FF8C.toInt()
        }
        appendSection("Engine", "%.1f fps · %.1fms".format(fps, frameMs), fpsColor)

        val load = NativeBridge.nativeGetHardwareLoad()
        val cpuMs = load[0] / 1000f
        val cpuPercent = if (frameMs > 0) (cpuMs / frameMs * 100f).coerceIn(0f, 100f) else 0f
        appendSection("CPU   ", "%.1fms (%.0f%%) load".format(cpuMs, cpuPercent))
        appendSection("Audio ", "%dHz · %.1fms".format(rate, audioMs))

        if (audioManager.systemAudioMode) {
            val metrics = NativeBridge.nativeGetSystemAudioMetrics()
            val jitter = metrics[1]
            val status = if (jitter > 80f) "[BURSTY]" else "[BUFFERED]"
            appendSection("Shared", "%.0fµs conv · %.1fms jitter %s".format(metrics[0], jitter, status))
        }

        if (LinkSync.enabled) {
            val bpm = NativeBridge.nativeLinkTempo()
            val peers = NativeBridge.nativeLinkPeers()
            appendSection("Link  ", "%.0f bpm · %d peer%s".format(bpm, peers, if (peers == 1) "" else "s"))
        }

        if (hueManager.hueController.isEnabled) {
            val sent = hueManager.hueController.huePacketsSent
            val pps = ((sent - lastHuePackets) * 2).coerceAtLeast(0)
            lastHuePackets = sent
            val drops = hueManager.hueController.huePacketsFailed
            val hueColor = if (drops > 0) 0xFFFFBB33.toInt() else 0xFF26FF8C.toInt()
            appendSection("Hue   ", "%d pps · %d drops".format(pps, drops), hueColor)
        }

        if (sb.isNotEmpty()) sb.delete(sb.length - 1, sb.length)
        settingsManager.updatePerfOverlay(sb)
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        settingsManager.updatePeakLuminance(prefs.getBoolean(SettingsManager.KEY_PEAK_LUMINANCE, false))
        if (!audioManager.systemAudioMode) audioManager.ensureMicAndStart()
        hueManager.onResume()
        linkManager.onResume()
        if (settingsManager.perfOverlayEnabled) startPerfPoller()
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
        if (!audioManager.systemAudioMode) NativeBridge.nativeStop()
        hueManager.onPause()
        stopPerfPoller()
        linkManager.onPause()
    }

    override fun onDestroy() {
        stopPerfPoller()
        hueManager.onDestroy()
        if (::hapticController.isInitialized) hapticController.release()
        linkManager.onDestroy()
        NativeBridge.nativeStop()
        unregisterReceiver(captureStopReceiver)
        super.onDestroy()
    }

    // Wiring up Hue/Haptic sinks
    fun wireSinks() {
        glView.bandsSink = { low, mid, high -> hueManager.hueController.onBands(low, mid, high) }
        glView.pcmBeatSink = { pcm -> if (::hapticController.isInitialized) hapticController.onPcm(pcm) }
        glView.onLinkBeat = {
            hueManager.hueController.onLinkBeat()
            if (menuManager.menuOpen) {
                val cell = (Math.round(BeatPulse.barPhase * 4f) % 4 + 4) % 4
                runOnUiThread { linkManager.flashBeatDot(); linkManager.updateBarCells(cell) }
            }
        }
        glView.onDrop = { if (menuManager.menuOpen) runOnUiThread { linkManager.flashDropDot() } }
    }

    private fun configureHdrWindow() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            window.colorMode = ActivityInfo.COLOR_MODE_HDR
        }
    }

    @Suppress("DEPRECATION")
    private fun selectHighestRefreshRate() {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay
        val current = display?.mode ?: return
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: current
        val lp = window.attributes
        lp.preferredDisplayModeId = best.modeId
        window.attributes = lp
    }

    private fun checkHdrSupport() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        settingsManager.updatePeakLuminance(prefs.getBoolean(SettingsManager.KEY_PEAK_LUMINANCE, false))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.i(TAG, "Config changed: ${newConfig.screenWidthDp}x${newConfig.screenHeightDp} dp")
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS = "visualizer_prefs"
        private const val KEY_FIRST_BOOT_DONE = "first_boot_done"
        private const val PERMISSION_FALLBACK_MS = 6000L
        private const val SPLASH_MASK_MS = 120L
        private const val SPLASH_FADE_MS = 350L
        private const val INTRO_HINT_DURATION_MS = 5000L
    }
}
