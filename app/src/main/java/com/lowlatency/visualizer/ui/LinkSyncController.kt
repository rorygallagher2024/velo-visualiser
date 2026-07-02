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
import androidx.core.content.edit
import com.lowlatency.visualizer.BeatPulse
import com.lowlatency.visualizer.LinkSync
import com.lowlatency.visualizer.NativeBridge
import com.lowlatency.visualizer.R

/**
 * Ableton Link wireless tempo/beat sync — all of its settings-sheet UI, network
 * plumbing and live status polling.
 *
 * Link needs two things the rest of the app doesn't: a Wi-Fi **multicast lock**
 * (Android otherwise filters out Link's UDP discovery packets), and a ~1 Hz
 * **status poller** that refreshes the peer/BPM readout and emits join/leave
 * notifications while sync is on. Both are acquired when Link turns on and
 * released when it turns off or the activity backgrounds, so the lock is never
 * held needlessly.
 *
 * The diagnostic beat/drop dots and the virtual bar cells live here too: they're
 * driven from the GL render thread via [pulseBeat]/[pulseDrop], which the host
 * calls (gated on the menu being open) from its audio sinks.
 *
 * @param onLinkEnabledChanged invoked whenever sync toggles, so the host can
 *   refresh anything that depends on Link being live (e.g. the lighting sheet's
 *   advanced controls).
 */
class LinkSyncController(
    private val activity: AppCompatActivity,
    private val prefs: SharedPreferences,
    private val onLinkEnabledChanged: () -> Unit,
) {
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

    private var lastPeerCount = 0
    private var linkNotifyRunnable: Runnable? = null

    // A multicast lock is mandatory or Android's Wi-Fi chip filters out Link's UDP
    // discovery packets. The poller refreshes peer/BPM text while sync is on.
    private var multicastLock: WifiManager.MulticastLock? = null
    private val linkHandler = Handler(Looper.getMainLooper())
    private val linkStatusPoller = object : Runnable {
        override fun run() {
            updateLinkStatus()
            linkHandler.postDelayed(this, 1000L)
        }
    }

    fun bind() {
        bindViews()
        wireControls()
    }

    private fun bindViews() {
        btnLinkSync = activity.findViewById(R.id.btn_link_sync)
        linkSettingsGroup = activity.findViewById(R.id.link_settings_group)
        btnLinkNotify = activity.findViewById(R.id.btn_link_notify)
        btnLinkAnticipate = activity.findViewById(R.id.btn_link_anticipate)
        btnLinkDownbeat = activity.findViewById(R.id.btn_link_downbeat)
        btnLinkExtras = activity.findViewById(R.id.btn_link_extras)
        linkStatus = activity.findViewById(R.id.link_status)
        linkNotification = activity.findViewById(R.id.link_notification)
        beatDot = activity.findViewById(R.id.beat_dot)
        dropDot = activity.findViewById(R.id.drop_dot)
        barCells = listOf(
            activity.findViewById(R.id.bar_cell_1),
            activity.findViewById(R.id.bar_cell_2),
            activity.findViewById(R.id.bar_cell_3),
            activity.findViewById(R.id.bar_cell_4),
        )
    }

    private fun wireControls() {
        // Ableton Link wireless tempo/beat sync (persisted, default off).
        setLinkSync(prefs.getBoolean(KEY_LINK, false), persist = false)
        btnLinkSync.setOnClickListener { setLinkSync(!LinkSync.enabled, persist = true) }

        // Link session notifications toggle (persisted, default on).
        updateLinkNotifyButton(prefs.getBoolean(KEY_LINK_NOTIFY, true))
        btnLinkNotify.setOnClickListener {
            val enabled = !prefs.getBoolean(KEY_LINK_NOTIFY, true)
            prefs.edit { putBoolean(KEY_LINK_NOTIFY, enabled) }
            updateLinkNotifyButton(enabled)
        }

        // Anticipatory beat-swell for the visuals (persisted, default on).
        LinkSync.anticipateBeat = prefs.getBoolean(KEY_LINK_ANTICIPATE, true)
        updateLinkAnticipateButton(LinkSync.anticipateBeat)
        btnLinkAnticipate.setOnClickListener {
            val enabled = !LinkSync.anticipateBeat
            LinkSync.anticipateBeat = enabled
            prefs.edit { putBoolean(KEY_LINK_ANTICIPATE, enabled) }
            updateLinkAnticipateButton(enabled)
        }

        // Experimental bar + drop enrichment (persisted, default off so the
        // out-of-the-box visuals only ride the reliable beat).
        LinkSync.experimentalEnrich = prefs.getBoolean(KEY_LINK_EXTRAS, false)
        updateLinkExtrasButton(LinkSync.experimentalEnrich)
        btnLinkExtras.setOnClickListener {
            val enabled = !LinkSync.experimentalEnrich
            LinkSync.experimentalEnrich = enabled
            prefs.edit { putBoolean(KEY_LINK_EXTRAS, enabled) }
            updateLinkExtrasButton(enabled)
        }

        // Manual downbeat alignment (persisted, default 0). Cycles 0→1→2→3 beats.
        LinkSync.barOffsetBeats = prefs.getInt(KEY_LINK_BAR_OFFSET, 0).coerceIn(0, 3)
        updateLinkDownbeatButton()
        btnLinkDownbeat.setOnClickListener {
            LinkSync.barOffsetBeats = (LinkSync.barOffsetBeats + 1) % 4
            prefs.edit { putInt(KEY_LINK_BAR_OFFSET, LinkSync.barOffsetBeats) }
            updateLinkDownbeatButton()
        }
    }

    /** Enable/disable Ableton Link: native session + multicast lock + status poll. */
    private fun setLinkSync(enabled: Boolean, persist: Boolean) {
        LinkSync.enabled = enabled
        NativeBridge.nativeLinkSetEnabled(enabled)
        if (enabled) acquireMulticastLock() else releaseMulticastLock()
        if (persist) prefs.edit { putBoolean(KEY_LINK, enabled) }

        btnLinkSync.isSelected = enabled
        btnLinkSync.setText(if (enabled) R.string.link_sync_on else R.string.link_sync_off)
        linkSettingsGroup.visibility = if (enabled) View.VISIBLE else View.GONE
        onLinkEnabledChanged()

        linkHandler.removeCallbacks(linkStatusPoller)
        if (enabled) {
            lastPeerCount = NativeBridge.nativeLinkPeers() // initialize without notifying
            linkHandler.post(linkStatusPoller)             // poll peers/BPM ~1 Hz
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

    /** Step the virtual bar to Link's current beat-in-bar and pulse the beat dot. */
    fun pulseBeat() {
        val cell = (Math.round(BeatPulse.barPhase * 4f) % 4 + 4) % 4
        beatDot.post { flashBeatDot(); updateBarCells(cell) }
    }

    /** Pulse the diagnostic drop dot. */
    fun pulseDrop() {
        dropDot.post { flashDot(dropDot) }
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
                showLinkNotification(activity.getString(R.string.link_notification_joined))
            } else {
                showLinkNotification(activity.getString(R.string.link_notification_left))
            }
            lastPeerCount = peers
        }
        if (peers <= 0) {
            linkStatus.setText(R.string.link_status_searching)
        } else {
            linkStatus.text =
                activity.getString(R.string.link_status_connected, peers, NativeBridge.nativeLinkTempo())
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

    private fun releaseMulticastLock() {
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
        private const val KEY_LINK = "ableton_link_enabled"
        private const val KEY_LINK_NOTIFY = "ableton_link_notifications"
        private const val KEY_LINK_ANTICIPATE = "ableton_link_anticipate"
        private const val KEY_LINK_BAR_OFFSET = "ableton_link_bar_offset"
        private const val KEY_LINK_EXTRAS = "ableton_link_experimental_extras"
    }
}
