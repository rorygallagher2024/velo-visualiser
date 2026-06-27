package com.lowlatency.visualizer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * A dedicated Activity that runs purely on a secondary display via ActivityOptions.
 * It contains a single, clean [VisualizerSurfaceView] with no UI overlays.
 * It synchronizes its scene index with MainActivity via SharedPreferences.
 */
class SecondaryVisualizerActivity : AppCompatActivity(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var glView: VisualizerSurfaceView
    private lateinit var prefs: SharedPreferences

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.lowlatency.visualizer.ACTION_STOP_CASTING") {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Enable immersive full-screen mode
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        
        glView = VisualizerSurfaceView(this).apply {
            isEnabled = false 
            isClickable = false
            introEnabled = false // Skip intro on the secondary screen
        }
        setContentView(glView)

        prefs = getSharedPreferences("visualizer_prefs", Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(this)

        val currentScene = prefs.getInt(KEY_ACTIVE_SCENE, 0)
        glView.selectScene(currentScene, 0f)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopReceiver, IntentFilter("com.lowlatency.visualizer.ACTION_STOP_CASTING"), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stopReceiver, IntentFilter("com.lowlatency.visualizer.ACTION_STOP_CASTING"))
        }
    }

    override fun onDestroy() {
        unregisterReceiver(stopReceiver)
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == KEY_ACTIVE_SCENE) {
            val scene = prefs.getInt(KEY_ACTIVE_SCENE, 0)
            glView.selectScene(scene, 0f)
        }
    }

    companion object {
        const val KEY_ACTIVE_SCENE = "active_scene_index"
    }
}
