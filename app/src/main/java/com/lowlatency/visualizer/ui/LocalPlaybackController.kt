package com.lowlatency.visualizer.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.lowlatency.visualizer.NativeBridge
import com.lowlatency.visualizer.R
import com.lowlatency.visualizer.audio.LocalAudioPlayer
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * Local-file playback: the floating media bar (title · seek · transport · loop)
 * plus everything a well-behaved music-playing session needs — the document
 * picker (with persisted access, reopening at the last folder), audio focus,
 * pause-on-headset-unplug, and the hand-off back to the microphone when the
 * session ends.
 *
 * The *source* state itself lives in [AudioSourceController]; this controller
 * drives it via enter/exitLocalPlayback and never duplicates it. MediaSession /
 * lockscreen controls are deliberately absent: playback pauses whenever the
 * app leaves the foreground (it exists to be looked at), so remote transport
 * would only ever control a paused player.
 *
 * The bar follows the app's transient-chrome rule: it slides in on session
 * start or canvas tap, and slides away after a few seconds — but stays put
 * while paused or scrubbing, when hiding it would strand the user.
 */
class LocalPlaybackController(
    private val activity: AppCompatActivity,
    private val prefs: SharedPreferences,
    private val audioSource: AudioSourceController,
    private val isMenuOpen: () -> Boolean,
    private val closeMenu: () -> Unit,
    private val onBarLayoutChanged: () -> Unit = {},
) {

    private lateinit var bar: View
    private lateinit var title: TextView
    private lateinit var time: TextView
    private lateinit var duration: TextView
    private lateinit var btnPlayPause: ImageView
    private lateinit var btnLoop: ImageView
    private lateinit var btnClose: ImageView
    private lateinit var seek: SeekBar
    private lateinit var btnCurrentTrack: Button

    private var currentUri: Uri? = null
    private var scrubbing = false
    private var resumeOnFocusGain = false
    private var noisyRegistered = false
    private var focusRequest: AudioFocusRequest? = null
    private val audioManager = activity.getSystemService(AudioManager::class.java)

    private val player = LocalAudioPlayer(
        context = activity,
        onCompletion = { if (audioSource.isLocalPlayback) endSession() },
        onError = { message ->
            if (audioSource.isLocalPlayback) {
                endSession()
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
            }
        },
    )

    // Reopen the picker where the user found the last track.
    private val pickerContract = object : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: Context, input: Array<String>): Intent {
            return super.createIntent(context, input).apply {
                prefs.getString(KEY_LAST_URI, null)?.let {
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(it))
                }
            }
        }
    }

    private val filePicker = activity.registerForActivityResult(pickerContract) { uri ->
        if (uri != null) startSession(uri) else audioSource.refreshSelection()
    }

    fun bind() {
        bar = activity.findViewById(R.id.media_controls_overlay)
        title = activity.findViewById(R.id.media_title)
        time = activity.findViewById(R.id.media_time)
        duration = activity.findViewById(R.id.media_duration)
        btnPlayPause = activity.findViewById(R.id.btn_media_play_pause)
        btnLoop = activity.findViewById(R.id.btn_media_loop)
        btnClose = activity.findViewById(R.id.btn_media_close)
        seek = activity.findViewById(R.id.media_seek)

        btnCurrentTrack = activity.findViewById(R.id.btn_current_track)

        player.looping = prefs.getBoolean(KEY_LOOP, false)
        btnPlayPause.setOnClickListener { togglePlayPause() }
        btnLoop.setOnClickListener { toggleLoop() }
        btnClose.setOnClickListener { endSession() }
        btnCurrentTrack.setOnClickListener { openFilePicker() }
        seek.max = SEEK_MAX
        seek.setOnSeekBarChangeListener(seekListener)
        updateControls()
        onConfigurationChanged()
    }

    /**
     * Fits the bar to the live window: full width minus edge margins, capped
     * for tablets (see [OverlayMetrics]), with the hero type, time labels,
     * transport boxes and seek lane scaling along the same curve. Called on
     * fold/unfold and rotation — resource buckets alone can't do this because
     * the activity survives configuration changes without re-inflating.
     */
    fun onConfigurationChanged() {
        val density = activity.resources.displayMetrics.density
        val f = OverlayMetrics.scale(activity.resources)

        bar.layoutParams = bar.layoutParams.apply {
            width = OverlayMetrics.widthPx(activity.resources)
        }
        val heroMaxSp = (HERO_MIN_SP + (HERO_MAX_SP - HERO_MIN_SP) * f).toInt()
        title.setAutoSizeTextTypeUniformWithConfiguration(
            HERO_FLOOR_SP, heroMaxSp, 1, TypedValue.COMPLEX_UNIT_SP
        )
        val timeSp = TIME_MIN_SP + (TIME_MAX_SP - TIME_MIN_SP) * f
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, timeSp)
        duration.setTextSize(TypedValue.COMPLEX_UNIT_SP, timeSp)

        val box = ((BOX_MIN_DP + (BOX_MAX_DP - BOX_MIN_DP) * f) * density).toInt()
        sizeTransportButton(btnPlayPause, box, PLAY_PAD_RATIO)
        sizeTransportButton(btnLoop, box, GLYPH_PAD_RATIO)
        sizeTransportButton(btnClose, box, GLYPH_PAD_RATIO)
        seek.layoutParams = seek.layoutParams.apply { height = box }
    }

    private fun sizeTransportButton(button: ImageView, box: Int, padRatio: Float) {
        button.layoutParams = button.layoutParams.apply {
            width = box
            height = box
        }
        val pad = (box * padRatio).toInt()
        button.setPadding(pad, pad, pad, pad)
    }

    // ----- public surface (host wiring) -------------------------------------

    /** The "Local File" segment was tapped. */
    fun openFilePicker() = filePicker.launch(arrayOf("audio/*"))

    /** The user switched to mic/system: silence and drop the session UI. */
    fun stopSession() {
        player.stop()
        releaseSessionSideEffects()
        hideBar()
    }

    /** Menu is opening — the bar yields to it. */
    fun hideBar() {
        bar.removeCallbacks(hideBarRunnable)
        if (bar.visibility != View.VISIBLE) return
        bar.animate()
            .translationY(barOffscreenY())
            .setDuration(BAR_ANIM_MS)
            .withEndAction {
                bar.visibility = View.GONE
                onBarLayoutChanged()
            }
            .start()
    }

    /** Canvas tap toggles the bar (never over the open menu). */
    fun onCanvasTap() {
        if (!audioSource.isLocalPlayback || isMenuOpen()) return
        if (bar.visibility == View.VISIBLE) hideBar() else showBarTemporarily()
    }

    /** Bottom edge of the visible bar, for stacking transient labels below it. */
    fun barBottom(): Int =
        if (bar.visibility == View.VISIBLE && bar.height > 0) bar.bottom else 0

    fun onPause() {
        if (audioSource.isLocalPlayback && player.isActivePlaying) pausePlayback()
    }

    fun onDestroy() {
        player.stop()
        releaseSessionSideEffects()
    }

    // ----- session lifecycle -------------------------------------------------

    private fun startSession(uri: Uri) {
        if (!requestAudioFocus()) {
            Toast.makeText(activity, R.string.media_focus_denied, Toast.LENGTH_SHORT).show()
            audioSource.refreshSelection()
            return
        }
        persistAccess(uri)
        currentUri = uri
        audioSource.enterLocalPlayback()   // stops mic / system capture first
        player.play(uri)
        btnCurrentTrack.visibility = View.VISIBLE
        resolveTitle(uri)
        setNoisyReceiverEnabled(true)
        updateControls()
        showBarTemporarily()
        closeMenu()
    }

    /** ✕ / natural completion / error: tear down and hand back to the mic. */
    private fun endSession() {
        player.stop()
        releaseSessionSideEffects()
        hideBar()
        audioSource.exitLocalPlayback()
    }

    private fun releaseSessionSideEffects() {
        abandonAudioFocus()
        setNoisyReceiverEnabled(false)
        currentUri = null
        if (::btnCurrentTrack.isInitialized) btnCurrentTrack.visibility = View.GONE
    }

    private fun togglePlayPause() {
        if (!audioSource.isLocalPlayback) return
        if (player.isActivePlaying) pausePlayback() else resumePlayback()
        showBarTemporarily()
    }

    private fun pausePlayback() {
        player.pause()
        setNoisyReceiverEnabled(false)
        updateControls()
        bar.removeCallbacks(hideBarRunnable)   // stay visible while paused
    }

    private fun resumePlayback() {
        player.resume()
        setNoisyReceiverEnabled(true)
        updateControls()
        scheduleHide()
    }

    private fun toggleLoop() {
        player.looping = !player.looping
        prefs.edit { putBoolean(KEY_LOOP, player.looping) }
        updateControls()
        showBarTemporarily()
    }

    private fun updateControls() {
        if (!::bar.isInitialized) return
        btnPlayPause.setImageResource(
            if (player.isActivePlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
        btnLoop.alpha = if (player.looping) 1f else LOOP_OFF_ALPHA
    }

    // ----- media bar show/hide + progress -----------------------------------

    private val hideBarRunnable = Runnable { hideBar() }

    private val progressPoller = object : Runnable {
        override fun run() {
            if (bar.visibility != View.VISIBLE) return
            val durationUs = player.durationUs
            if (!scrubbing) {
                seek.isEnabled = durationUs > 0
                seek.progress = if (durationUs > 0) {
                    ((player.positionUs * SEEK_MAX) / max(durationUs, 1L)).toInt()
                } else {
                    0
                }
                time.text = formatClock(player.positionUs)
                duration.text = if (durationUs > 0) formatClock(durationUs) else ""
            }
            bar.postDelayed(this, PROGRESS_POLL_MS)
        }
    }

    private fun showBarTemporarily() {
        if (!audioSource.isLocalPlayback) return
        bar.removeCallbacks(hideBarRunnable)
        if (bar.visibility != View.VISIBLE) {
            bar.visibility = View.VISIBLE
            bar.translationY = barOffscreenY()
            bar.removeCallbacks(progressPoller)
            bar.post(progressPoller)
        }
        bar.animate().translationY(0f).setDuration(BAR_ANIM_MS).start()
        bar.post { onBarLayoutChanged() }
        scheduleHide()
    }

    private fun scheduleHide() {
        bar.removeCallbacks(hideBarRunnable)
        // Never auto-hide from under a paused player or a scrubbing finger.
        if (player.isActivePlaying && !scrubbing) {
            bar.postDelayed(hideBarRunnable, BAR_HIDE_DELAY_MS)
        }
    }

    /** Y offset that parks the bar fully above the screen edge. */
    private fun barOffscreenY(): Float {
        val bottom = bar.bottom
        return if (bottom > 0) -bottom.toFloat()
        else -BAR_FALLBACK_OFFSET_DP * activity.resources.displayMetrics.density
    }

    private val seekListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            if (fromUser) time.text = formatClock(progressToUs(progress))
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) {
            scrubbing = true
            bar.removeCallbacks(hideBarRunnable)
        }

        override fun onStopTrackingTouch(seekBar: SeekBar) {
            scrubbing = false
            player.seekTo(progressToUs(seekBar.progress))
            scheduleHide()
        }
    }

    private fun progressToUs(progress: Int): Long =
        (player.durationUs * progress) / SEEK_MAX

    private fun formatClock(us: Long): String {
        val totalSec = (us / 1_000_000L).coerceAtLeast(0L)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    // ----- track title -------------------------------------------------------

    private fun resolveTitle(uri: Uri) {
        title.text = ""
        btnCurrentTrack.setText(R.string.btn_current_track_unknown)
        thread(name = "MediaTitleQuery") {
            val name = queryDisplayName(uri)
            title.post {
                if (uri == currentUri && name != null) {
                    title.text = name
                    btnCurrentTrack.text = activity.getString(R.string.btn_current_track, name)
                }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        activity.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0)?.substringBeforeLast('.')
            } else {
                null
            }
        }
    }.getOrNull()

    private fun persistAccess(uri: Uri) {
        runCatching {
            activity.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        prefs.edit { putString(KEY_LAST_URI, uri.toString()) }
    }

    // ----- audio focus + becoming-noisy --------------------------------------

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                if (player.isActivePlaying) pausePlayback()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeOnFocusGain = player.isActivePlaying
                if (player.isActivePlaying) pausePlayback()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeOnFocusGain && audioSource.isLocalPlayback) resumePlayback()
                resumeOnFocusGain = false
            }
            // LOSS_TRANSIENT_CAN_DUCK: the system ducks our stream for us.
        }
    }

    private fun requestAudioFocus(): Boolean {
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
            .also { focusRequest = it }
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        resumeOnFocusGain = false
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY &&
                player.isActivePlaying
            ) {
                pausePlayback()
                showBarTemporarily()
            }
        }
    }

    private fun setNoisyReceiverEnabled(enabled: Boolean) {
        if (enabled == noisyRegistered) return
        if (enabled) {
            ContextCompat.registerReceiver(
                activity,
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } else {
            activity.unregisterReceiver(noisyReceiver)
        }
        noisyRegistered = enabled
    }

    companion object {
        private const val KEY_LAST_URI = "local_last_uri"
        private const val KEY_LOOP = "local_loop_enabled"
        private const val SEEK_MAX = 1000
        private const val BAR_ANIM_MS = 300L
        private const val BAR_HIDE_DELAY_MS = 3000L
        private const val PROGRESS_POLL_MS = 500L
        private const val BAR_FALLBACK_OFFSET_DP = 160f   // pre-layout park distance
        private const val LOOP_OFF_ALPHA = 0.4f

        // Responsive sizing curve (width comes from OverlayMetrics).
        private const val HERO_FLOOR_SP = 12            // autosize lower bound
        private const val HERO_MIN_SP = 21f
        private const val HERO_MAX_SP = 30f
        private const val TIME_MIN_SP = 12f
        private const val TIME_MAX_SP = 14f
        private const val BOX_MIN_DP = 48f               // transport touch boxes
        private const val BOX_MAX_DP = 58f
        private const val PLAY_PAD_RATIO = 11f / 48f     // glyph inset ratios
        private const val GLYPH_PAD_RATIO = 14f / 48f
    }
}
