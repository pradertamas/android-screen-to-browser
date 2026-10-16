package com.pradertamas.uicapturetest

// Worker-thread confined. All timestamps are SystemClock.elapsedRealtimeNanos().
data class CaptureMetricsSnapshot(
    val durationMs: Double = 0.0,
    val frames: Long = 0,
    val recentFps: Double = 0.0,
    val averageFps: Double = 0.0,
    val lastGapMs: Double = 0.0,
    val averageGapMs: Double = 0.0,
    val maxGapMs: Double = 0.0,
    val gaps: List<Long> = List(5) { 0L },
    val changed: Long = 0,
    val unchanged: Long = 0,
    val baselines: Long = 0,
    val averageProcessingMs: Double = 0.0,
    val maxProcessingMs: Double = 0.0,
    val visibilityChanges: Long = 0,
    val visibleMs: Double = 0.0,
    val hiddenMs: Double = 0.0,
    val unknownMs: Double = 0.0,
    val lastFrameAgeMs: Double = 0.0,
    val sampleSaves: Long = 0
) {
    val changedPercent: Double
        get() = if (changed + unchanged == 0L) 0.0 else changed * 100.0 / (changed + unchanged)
}

class CaptureMetrics(private val startNs: Long) {
    var frames = 0L
        private set
    private var lastFrameNs: Long? = null
    private var lastGapNs = 0L
    private var maxGapNs = 0L
    private var gapSumNs = 0.0
    private val gaps = LongArray(5)
    private val thresholdsNs = longArrayOf(50_000_000, 100_000_000, 250_000_000, 500_000_000, 1_000_000_000)
    private var changed = 0L
    private var unchanged = 0L
    private var baselines = 0L
    private var processingSumNs = 0.0
    private var processingCount = 0L
    private var maxProcessingNs = 0L
    private var windowStartNs = startNs
    private var windowFrames = 0L
    private var recentFps = 0.0
    private var visibility: Boolean? = null
    private var visibilitySinceNs = startNs
    private var visibleNs = 0L
    private var hiddenNs = 0L
    private var unknownNs = 0L
    private var visibilityChanges = 0L
    var sampleSaves = 0L

    fun frame(arrivalNs: Long, changedSample: Boolean?) {
        lastFrameNs?.let { previous ->
            lastGapNs = arrivalNs - previous
            gapSumNs += lastGapNs.toDouble()
            maxGapNs = maxOf(maxGapNs, lastGapNs)
            for (i in gaps.indices) if (lastGapNs > thresholdsNs[i]) gaps[i]++
        }
        lastFrameNs = arrivalNs
        frames++
        when (changedSample) {
            true -> changed++
            false -> unchanged++
            null -> baselines++
        }
    }

    fun processing(durationNs: Long) {
        processingSumNs += durationNs.toDouble()
        processingCount++
        maxProcessingNs = maxOf(maxProcessingNs, durationNs)
    }

    fun visibility(nowNs: Long, visible: Boolean) {
        if (visibility == visible) return
        if (visibility != null) visibilityChanges++
        accountVisibility(nowNs)
        visibility = visible
    }

    private fun accountVisibility(nowNs: Long) {
        val elapsed = nowNs - visibilitySinceNs
        when (visibility) {
            true -> visibleNs += elapsed
            false -> hiddenNs += elapsed
            null -> unknownNs += elapsed
        }
        visibilitySinceNs = nowNs
    }

    fun snapshot(nowNs: Long): CaptureMetricsSnapshot {
        val elapsed = nowNs - startNs
        val windowNs = nowNs - windowStartNs
        if (windowNs >= 1_000_000_000L) {
            recentFps = (frames - windowFrames) * 1e9 / windowNs
            windowFrames = frames
            windowStartNs = nowNs
        }
        accountVisibility(nowNs)
        return CaptureMetricsSnapshot(
            durationMs = elapsed / 1e6, frames = frames, recentFps = recentFps,
            averageFps = if (elapsed > 0) frames * 1e9 / elapsed else 0.0,
            lastGapMs = lastGapNs / 1e6,
            averageGapMs = if (frames > 1) gapSumNs / (frames - 1) / 1e6 else 0.0,
            maxGapMs = maxGapNs / 1e6, gaps = gaps.toList(),
            changed = changed, unchanged = unchanged, baselines = baselines,
            averageProcessingMs = if (processingCount > 0) processingSumNs / processingCount / 1e6 else 0.0,
            maxProcessingMs = maxProcessingNs / 1e6,
            visibilityChanges = visibilityChanges,
            visibleMs = visibleNs / 1e6, hiddenMs = hiddenNs / 1e6, unknownMs = unknownNs / 1e6,
            lastFrameAgeMs = (nowNs - (lastFrameNs ?: startNs)) / 1e6, sampleSaves = sampleSaves
        )
    }
}
