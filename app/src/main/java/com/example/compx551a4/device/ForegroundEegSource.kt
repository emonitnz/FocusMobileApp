package com.example.compx551a4.device

import com.example.compx551a4.core.EegSource

/**
 * Keeps [inner] connected for as long as the app is in the foreground, so the headset is ready
 * (and its status visible) before calibration or a session starts.
 *
 * Calibration and FocusService each call [stop] when they finish; while the app is held those calls
 * are ignored, otherwise leaving calibration would drop the headset with the app still open.
 */
class ForegroundEegSource(private val inner: EegSource) : EegSource by inner {

    @Volatile
    private var held = false

    /** Called when the app comes to the foreground. */
    suspend fun hold() {
        held = true
        inner.start()
    }

    /**
     * Called when the app leaves the foreground. Disconnects unless [keepConnected], i.e. a session
     * is running and FocusService still needs the signal.
     */
    fun release(keepConnected: Boolean) {
        held = false
        if (!keepConnected) inner.stop()
    }

    override fun stop() {
        if (!held) inner.stop()
    }
}