package com.lowlatency.visualizer.ui

import android.content.SharedPreferences
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.lowlatency.visualizer.HdrOutputSettings
import com.lowlatency.visualizer.R

/**
 * The Exynos-only "Experimental HDR output" switch (see [HdrOutputSettings]).
 * Hidden everywhere else. Turning it on asks for confirmation first, since the
 * failure mode it guards against is violent full-screen flashing.
 */
class HdrOutputController(
    private val activity: AppCompatActivity,
    private val prefs: SharedPreferences,
) {
    private val group: View = activity.findViewById(R.id.group_hdr_output)
    private val button: Button = activity.findViewById(R.id.btn_hdr_output)

    fun bind() {
        if (!HdrOutputSettings.isExynos) {
            group.visibility = View.GONE
            return
        }
        group.visibility = View.VISIBLE
        refreshLabel()
        button.setOnClickListener { onToggle() }
    }

    private fun onToggle() {
        if (isOptedIn()) {
            save(false)
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.hdr_output_warning_title)
            .setMessage(R.string.hdr_output_warning_message)
            .setPositiveButton(R.string.hdr_output_warning_confirm) { _, _ -> save(true) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun save(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_HDR_OUTPUT_OPT_IN, enabled) }
        refreshLabel()
    }

    private fun isOptedIn(): Boolean = prefs.getBoolean(KEY_HDR_OUTPUT_OPT_IN, false)

    private fun refreshLabel() {
        val on = isOptedIn()
        button.isSelected = on
        button.setText(if (on) R.string.hdr_output_on else R.string.hdr_output_off)
    }

    companion object {
        private const val KEY_HDR_OUTPUT_OPT_IN = "exynos_hdr_output_opt_in"

        /** Must run before the GL surface and window are configured in onCreate. */
        fun restore(prefs: SharedPreferences) {
            HdrOutputSettings.exynosOptIn = prefs.getBoolean(KEY_HDR_OUTPUT_OPT_IN, false)
        }
    }
}
