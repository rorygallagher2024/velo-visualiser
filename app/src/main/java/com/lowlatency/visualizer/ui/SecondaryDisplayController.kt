package com.lowlatency.visualizer.ui

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.lowlatency.visualizer.R
import com.lowlatency.visualizer.SecondaryVisualizerActivity
import com.lowlatency.visualizer.VisualizerSurfaceView

/**
 * Manages detection of secondary displays and the "Cast to Display" UI button.
 * Encapsulates the display listener and launch logic to keep MainActivity clean.
 */
class SecondaryDisplayController(
    private val activity: AppCompatActivity,
    private val glView: VisualizerSurfaceView,
    private val castButton: Button,
    private val castOverlay: android.widget.TextView
) : DisplayManager.DisplayListener {

    private val displayManager = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    var isCasting = false
        private set

    fun bind() {
        displayManager.registerDisplayListener(this, null)
        updateCastButtonVisibility()

        castButton.setOnClickListener {
            if (!isCasting) {
                startCasting()
            } else {
                stopCasting()
            }
        }
    }

    fun onDestroy() {
        displayManager.unregisterDisplayListener(this)
    }

    private fun updateCastButtonVisibility() {
        val presentationDisplays = displayManager.displays.filter { it.displayId != Display.DEFAULT_DISPLAY }
        if (presentationDisplays.isNotEmpty()) {
            castButton.visibility = View.VISIBLE
        } else {
            castButton.visibility = View.GONE
            if (isCasting) {
                stopCasting()
            }
        }
    }

    private fun startCasting() {
        val presentationDisplays = displayManager.displays.filter { it.displayId != Display.DEFAULT_DISPLAY }
        val secondaryDisplay = presentationDisplays.firstOrNull()
        
        if (secondaryDisplay != null) {
            val intent = Intent(activity, SecondaryVisualizerActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            val options = ActivityOptions.makeBasic()
            options.launchDisplayId = secondaryDisplay.displayId
            
            activity.startActivity(intent, options.toBundle())
            isCasting = true
            castButton.text = "Stop Casting"
            
            // Pause local rendering to save battery, but keep the view visible so it can receive touch events (swipes)
            glView.onPause() 
            castOverlay.visibility = View.VISIBLE
        }
    }

    private fun stopCasting() {
        isCasting = false
        castButton.text = "Cast to Display"
        
        // Resume local rendering
        glView.onResume()
        castOverlay.visibility = View.GONE
        
        if (activity is com.lowlatency.visualizer.MainActivity) {
            activity.evaluateMicState()
        }
        
        // The activity on the secondary display will need to be closed. 
        // We can send a broadcast, or rely on the user to unplug the display.
        // For a robust implementation, sending a broadcast to finish() it would be ideal.
        val intent = Intent("com.lowlatency.visualizer.ACTION_STOP_CASTING")
        intent.setPackage(activity.packageName)
        activity.sendBroadcast(intent)
    }

    override fun onDisplayAdded(displayId: Int) {
        updateCastButtonVisibility()
    }

    override fun onDisplayRemoved(displayId: Int) {
        updateCastButtonVisibility()
    }

    override fun onDisplayChanged(displayId: Int) {
        // Not needed
    }
}
