package com.lowlatency.visualizer.ui

import android.content.Context
import android.content.SharedPreferences
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.lowlatency.visualizer.NativeBridge
import com.lowlatency.visualizer.R
import com.lowlatency.visualizer.LinkSync

class LinkMenuController(
    private val activity: AppCompatActivity,
    private val prefs: SharedPreferences,
    private val updateAdvancedVisibilityDelegate: () -> Unit
) {
    private val btnLinkSync: Button = activity.findViewById(R.id.btn_link_sync)
    private val linkSettingsGroup: View = activity.findViewById(R.id.link_settings_group)
    private val btnLinkNotify: Button = activity.findViewById(R.id.btn_link_notify)
    private val btnLinkAnticipate: Button = activity.findViewById(R.id.btn_link_anticipate)
    private val btnLinkDownbeat: Button = activity.findViewById(R.id.btn_link_downbeat)
    private val btnLinkExtras: Button = activity.findViewById(R.id.btn_link_extras)
    private val linkStatus: TextView = activity.findViewById(R.id.link_status)
    private val linkNotification: TextView = activity.findViewById(R.id.link_notification)
    private val beatDot: View = activity.findViewById(R.id.beat_dot)
    private val dropDot: View = activity.findViewById(R.id.drop_dot)
    private val barCells = listOf<View>(
        activity.findViewById(R.id.bar_cell_1),
        activity.findViewById(R.id.bar_cell_2),
        activity.findViewById(R.id.bar_cell_3),
        activity.findViewById(R.id.bar_cell_4)
    )

    private var multicastLock: WifiManager.MulticastLock? = null
    private val linkHandler = Handler(Looper.getMainLooper())
    private var lastPeerCount = 0
    private var linkNotifyRunnable: Runnable? = null

    private val linkStatusPoller = object : Runnable {
        override fun run() {
            updateLinkStatus()
            linkHandler.postDelayed(this, 1000L)
        }
    }

    init {
        btnLinkSync.setOnClickListener {
            setLinkSync(!LinkSync.enabled, persist = true)
        }

        btnLinkNotify.setOnClickListener {
            val enabled = !prefs.getBoolean(KEY_LINK_NOTIFY, true)
            prefs.edit().putBoolean(KEY_LINK_NOTIFY, enabled).apply()
            updateLinkNotifyButton(enabled)
            if (enabled) showLinkNotification(activity.getString(R.string.link_notifications_on))
        }

        btnLinkAnticipate.setOnClickListener {
            val enabled = !LinkSync.anticipateBeat
            LinkSync.anticipateBeat = enabled
            prefs.edit().putBoolean(KEY_LINK_ANTICIPATE, enabled).apply()
            updateLinkAnticipateButton(enabled)
        }

        btnLinkDownbeat.setOnClickListener {
            LinkSync.barOffsetBeats = (LinkSync.barOffsetBeats + 1) % 4
            prefs.edit().putInt(KEY_LINK_BAR_OFFSET, LinkSync.barOffsetBeats).apply()
            updateLinkDownbeatButton()
        }

        btnLinkExtras.setOnClickListener {
            val enabled = !LinkSync.experimentalEnrich
            LinkSync.experimentalEnrich = enabled
            prefs.edit().putBoolean(KEY_LINK_EXTRAS, enabled).apply()
            updateLinkExtrasButton(enabled)
        }

        // Initialize state
        LinkSync.anticipateBeat = prefs.getBoolean(KEY_LINK_ANTICIPATE, true)
        LinkSync.barOffsetBeats = prefs.getInt(KEY_LINK_BAR_OFFSET, 0)
        LinkSync.experimentalEnrich = prefs.getBoolean(KEY_LINK_EXTRAS, false)

        updateLinkNotifyButton(prefs.getBoolean(KEY_LINK_NOTIFY, true))
        updateLinkAnticipateButton(LinkSync.anticipateBeat)
        updateLinkDownbeatButton()
        updateLinkExtrasButton(LinkSync.experimentalEnrich)
        
        // Don't auto-start link here, it might be started by the main activity or onResume.
        // Wait, LinkSync might be saved in prefs.
        val linkEnabled = prefs.getBoolean(KEY_LINK, false)
        setLinkSync(linkEnabled, persist = false)
    }

    fun setLinkSync(enabled: Boolean, persist: Boolean) {
        LinkSync.enabled = enabled
        NativeBridge.nativeLinkSetEnabled(enabled)
        if (enabled) acquireMulticastLock() else releaseMulticastLock()
        if (persist) prefs.edit().putBoolean(KEY_LINK, enabled).apply()

        btnLinkSync.isSelected = enabled
        btnLinkSync.setText(if (enabled) R.string.link_sync_on else R.string.link_sync_off)
        linkSettingsGroup.visibility = if (enabled) View.VISIBLE else View.GONE
        updateAdvancedVisibilityDelegate()

        linkHandler.removeCallbacks(linkStatusPoller)
        if (enabled) {
            lastPeerCount = NativeBridge.nativeLinkPeers()
            linkHandler.post(linkStatusPoller)
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
        btnLinkDownbeat.text = activity.getString(R.string.link_downbeat_nudge, LinkSync.barOffsetBeats)
    }

    private fun updateLinkExtrasButton(enabled: Boolean) {
        btnLinkExtras.isSelected = enabled
        btnLinkExtras.setText(if (enabled) R.string.link_extras_on else R.string.link_extras_off)
    }

    fun flashBeatDot() {
        beatDot.animate().cancel()
        beatDot.alpha = 1f
        beatDot.scaleX = 1.35f
        beatDot.scaleY = 1.35f
        beatDot.animate().alpha(0.18f).scaleX(1f).scaleY(1f).setDuration(170L).start()
    }

    fun flashDot() {
        dropDot.animate().cancel()
        dropDot.alpha = 1f
        dropDot.scaleX = 1.35f
        dropDot.scaleY = 1.35f
        dropDot.animate().alpha(0.18f).scaleX(1f).scaleY(1f).setDuration(170L).start()
    }

    fun updateBarCells(active: Int) {
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
                showLinkNotification(activity.getString(R.string.link_notification_joined))
            } else {
                showLinkNotification(activity.getString(R.string.link_notification_left))
            }
            lastPeerCount = peers
        }
        if (peers <= 0) {
            linkStatus.setText(R.string.link_status_searching)
        } else {
            linkStatus.text = activity.getString(R.string.link_status_connected, peers, NativeBridge.nativeLinkTempo())
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
        val wifi = activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wifi.createMulticastLock("velo-ableton-link").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    fun releaseMulticastLock() {
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    fun onDestroy() {
        releaseMulticastLock()
        linkHandler.removeCallbacksAndMessages(null)
    }

    companion object {
        const val KEY_LINK = "ableton_link_enabled"
        const val KEY_LINK_NOTIFY = "ableton_link_notifications"
        const val KEY_LINK_ANTICIPATE = "ableton_link_anticipate"
        const val KEY_LINK_BAR_OFFSET = "ableton_link_bar_offset"
        const val KEY_LINK_EXTRAS = "ableton_link_experimental_extras"
    }
}
