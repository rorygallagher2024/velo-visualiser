package com.lowlatency.visualizer.gl

import android.opengl.GLSurfaceView
import android.util.Log
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/**
 * EGLConfigChooser that prefers an HDR-capable framebuffer over the default
 * 8-bit SDR surface, in descending order of dynamic range:
 *
 *   1. FP16  — 16-bit floating-point RGBA (EGL_EXT_pixel_format_float). This is
 *              the real prize: it stores values >1.0, so the shaders' unbounded
 *              HDR output survives to the compositor untouched.
 *   2. RGBA_1010102 — 10-bit per channel + 2-bit alpha (wide-gamut SDR / HDR10
 *              transport). More headroom than 8-bit when FP16 is unavailable.
 *   3. RGBA8888 — universal SDR fallback so we always get a valid surface.
 *
 * eglChooseConfig returns configs sorted best-first per the EGL spec, so the
 * first match for each request is the strongest one.
 */
class HdrEGLConfigChooser : GLSurfaceView.EGLConfigChooser {

    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        chooseFirst(egl, display, FP16_ATTRS)?.let {
            Log.i(TAG, "Selected FP16 (16-bit float) HDR framebuffer.")
            return it
        }
        chooseFirst(egl, display, RGB10_ATTRS)?.let {
            Log.i(TAG, "Selected RGBA_1010102 (10-bit) framebuffer.")
            return it
        }
        chooseFirst(egl, display, RGB8_ATTRS)?.let {
            Log.i(TAG, "Selected RGBA8888 (8-bit SDR) framebuffer.")
            return it
        }
        throw IllegalArgumentException("No suitable EGL config found")
    }

    private fun chooseFirst(egl: EGL10, display: EGLDisplay, attrs: IntArray): EGLConfig? {
        val num = IntArray(1)
        if (!egl.eglChooseConfig(display, attrs, null, 0, num) || num[0] <= 0) return null
        val configs = arrayOfNulls<EGLConfig>(num[0])
        if (!egl.eglChooseConfig(display, attrs, configs, num[0], num) || num[0] <= 0) return null
        return configs[0]
    }

    companion object {
        private const val TAG = "HdrEGLConfigChooser"

        // EGL constants not exposed by EGL10 (from EGL 1.2 / the float extension).
        private const val EGL_RENDERABLE_TYPE = 0x3040
        private const val EGL_OPENGL_ES2_BIT = 0x0004
        private const val EGL_COLOR_COMPONENT_TYPE_EXT = 0x3339
        private const val EGL_COLOR_COMPONENT_TYPE_FLOAT_EXT = 0x333B

        private val FP16_ATTRS = intArrayOf(
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_COLOR_COMPONENT_TYPE_EXT, EGL_COLOR_COMPONENT_TYPE_FLOAT_EXT,
            EGL10.EGL_RED_SIZE, 16,
            EGL10.EGL_GREEN_SIZE, 16,
            EGL10.EGL_BLUE_SIZE, 16,
            EGL10.EGL_ALPHA_SIZE, 16,
            EGL10.EGL_DEPTH_SIZE, 0,
            EGL10.EGL_STENCIL_SIZE, 0,
            EGL10.EGL_NONE
        )

        private val RGB10_ATTRS = intArrayOf(
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL10.EGL_RED_SIZE, 10,
            EGL10.EGL_GREEN_SIZE, 10,
            EGL10.EGL_BLUE_SIZE, 10,
            EGL10.EGL_ALPHA_SIZE, 2,
            EGL10.EGL_DEPTH_SIZE, 0,
            EGL10.EGL_STENCIL_SIZE, 0,
            EGL10.EGL_NONE
        )

        private val RGB8_ATTRS = intArrayOf(
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL10.EGL_RED_SIZE, 8,
            EGL10.EGL_GREEN_SIZE, 8,
            EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_ALPHA_SIZE, 8,
            EGL10.EGL_NONE
        )
    }
}
