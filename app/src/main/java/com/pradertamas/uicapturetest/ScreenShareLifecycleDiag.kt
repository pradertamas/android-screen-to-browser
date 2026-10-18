package com.pradertamas.uicapturetest

import android.util.Log

/** Temporary debug-only diagnostics. Callers pass enums, counters and flags, never bearer data. */
internal object ScreenShareLifecycleDiag {
    fun write(message: String) { if (BuildConfig.DEBUG) runCatching { Log.d("ScreenShareLifecycleDiag", message) } }
}
