package com.lowlatency.visualizer

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView

class LinkManager(private val activity: MainActivity) {

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

    private var multicastLock: WifiManager.MulticastLock? = null
    private var lastPeerCount = 0
    private var linkNotifyRunnable: Runnable? = null
    private val linkHandler = Handler(Looper.getMainLooper())
    
    val linkStatusPoller = object : Runnable {
        override fun run() {
            updateLinkStatus()
            linkHandler.postDelayed(this, 1000L)
        }
    }

    fun bindViews(root: View) {
        btnLinkSync = root.findViewById(R.id.btn_link_sync)
        linkSettingsGroup = root.findViewById(R.id.link_settings_group)
        btnLinkNotify = root.findViewById(R.id.btn_link_notify)
        btnLinkAnticipate = root.findViewById(R.id.btn_link_anticipate)
        btnLinkDownbeat = root.findViewById(R.id.btn_link_downbeat)
        btnLinkExtras = root.findViewById(R.id.btn_link_extras)
        linkStatus = root.findViewById(R.id.link_status)
        linkNotification = root.findViewById(R.id.link_notification)
        beatDot = root.findViewById(R.id.beat_dot)
        dropDot = root.findViewById(R.id.drop_dot)
        barCells = listOf(
            root.findViewById(R.id.bar_cell_1),
            root.findViewById(R.id.bar_cell_2),
            root.findViewById(R.id.bar_cell_3),
            root.findViewById(R.id.bar_cell_4),
        )
    }

    fun setup() {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        
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

        // Experimental bar + drop enrichment (persisted, default off).
        LinkSync.experimentalEnrich = prefs.getBoolean(KEY_LINK_EXTRAS, false)
        updateLinkExtrasButton(LinkSync.experimentalEnrich)
        btnLinkExtras.setOnClickListener {
            val enabled = !LinkSync.experimentalEnrich
            LinkSync.experimentalEnrich = enabled
            prefs.edit().putBoolean(KEY_LINK_EXTRAS, enabled).apply()
            updateLinkExtrasButton(enabled)
        }

        // Manual downbeat alignment (persisted, default 0).
        LinkSync.barOffsetBeats = prefs.getInt(KEY_LINK_BAR_OFFSET, 0).coerceIn(0, 3)
        updateLinkDownbeatButton()
        btnLinkDownbeat.setOnClickListener {
            LinkSync.barOffsetBeats = (LinkSync.barOffsetBeats + 1) % 4
            prefs.edit().putInt(KEY_LINK_BAR_OFFSET, LinkSync.barOffsetBeats).apply()
            updateLinkDownbeatButton()
        }
    }

    fun setLinkSync(enabled: Boolean, persist: Boolean) {
        LinkSync.enabled = enabled
        NativeBridge.nativeLinkSetEnabled(enabled)
        if (enabled) acquireMulticastLock() else releaseMulticastLock()
        if (persist) {
            val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_LINK, enabled).apply()
        }

        btnLinkSync.isSelected = enabled
        btnLinkSync.setText(if (enabled) R.string.link_sync_on else R.string.link_sync_off)
        linkSettingsGroup.visibility = if (enabled) View.VISIBLE else View.GONE
        activity.updateAdvancedVisibility()

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

    fun flashBeatDot() = flashDot(beatDot)
    
    fun flashDropDot() = flashDot(dropDot)

    fun flashDot(dot: View) {
        dot.animate().cancel()
        dot.alpha = 1f
        dot.scaleX = 1.35f
        dot.scaleY = 1.35f
        dot.animate().alpha(0.18f).scaleX(1f).scaleY(1f).setDuration(170L).start()
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
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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

    fun onResume() {
        if (LinkSync.enabled) {
            NativeBridge.nativeLinkSetEnabled(true)
            acquireMulticastLock()
            linkHandler.post(linkStatusPoller)
        }
    }

    fun onPause() {
        linkHandler.removeCallbacks(linkStatusPoller)
        if (LinkSync.enabled) {
            NativeBridge.nativeLinkSetEnabled(false)
            releaseMulticastLock()
        }
    }

    fun onDestroy() {
        linkHandler.removeCallbacks(linkStatusPoller)
        NativeBridge.nativeLinkSetEnabled(false)
        releaseMulticastLock()
    }

    companion object {
        const val PREFS = "visualizer_prefs"
        const val KEY_LINK = "ableton_link_enabled"
        const val KEY_LINK_NOTIFY = "ableton_link_notifications"
        const val KEY_LINK_ANTICIPATE = "ableton_link_anticipate"
        const val KEY_LINK_BAR_OFFSET = "ableton_link_bar_offset"
        const val KEY_LINK_EXTRAS = "ableton_link_experimental_extras"
    }
}
