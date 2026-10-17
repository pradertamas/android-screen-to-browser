package com.pradertamas.uicapturetest

import kotlin.math.floor
import kotlin.math.min

data class CaptureSize(val width: Int, val height: Int) {
    override fun toString() = "$width × $height"
}

object CaptureDimensions {
    const val FPS = 30
    const val MAX_PIXELS = 720 * 1560
    val INITIAL = CaptureSize(720, 1560)
    /** Fit actual captured content into an orientation-dependent box, without upscaling. */
    fun fit(width: Int, height: Int): CaptureSize? {
        if (width < 2 || height < 2) return null
        val box = if (width > height) CaptureSize(1560, 720) else INITIAL
        val scale = min(1.0, min(box.width.toDouble() / width, box.height.toDouble() / height))
        fun even(value: Int) = floor(value * scale / 2).toInt() * 2
        return CaptureSize(even(width), even(height)).takeIf { it.width >= 2 && it.height >= 2 }
    }
}

/** Called only on the service control thread. Cancel + generation guards protect delayed work. */
class CaptureResizeController(private val later: (Long, () -> Unit) -> (() -> Unit),
    private val apply: (CaptureSize) -> Unit) {
    companion object { const val COALESCE_MS = 100L }
    var content: CaptureSize? = null; private set
    var output: CaptureSize? = null; private set
    private var running = false
    private var generation = 0L
    private var cancel: (() -> Unit)? = null
    fun start(initial: CaptureSize = CaptureDimensions.INITIAL) {
        stop(); running = true; output = initial
    }
    fun resized(width: Int, height: Int) {
        if (!running) return
        val target = CaptureDimensions.fit(width, height) ?: return
        content = CaptureSize(width, height)
        cancel?.invoke(); cancel = null
        val version = ++generation
        if (target == output) return
        cancel = later(COALESCE_MS) {
            if (running && generation == version) {
                cancel = null
                apply(target)
                if (running && generation == version) output = target
            }
        }
    }
    fun stop() {
        running = false; generation++; cancel?.invoke(); cancel = null
        content = null; output = null
    }
}
