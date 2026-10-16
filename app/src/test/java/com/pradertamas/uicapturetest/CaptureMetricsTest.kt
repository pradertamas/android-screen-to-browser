package com.pradertamas.uicapturetest

import org.junit.Assert.*
import org.junit.Test

class CaptureMetricsTest {
    @Test fun ratesGapsAndReferenceFrames() {
        val m = CaptureMetrics(0)
        m.frame(0, null)
        m.frame(20_000_000, false)
        m.frame(120_000_000, true) // Exactly 100ms: not >100ms.
        m.frame(1_220_000_000, true)
        val s = m.snapshot(2_000_000_000)
        assertEquals(4L, s.frames)
        assertEquals(2.0, s.averageFps, 0.00001)
        assertEquals(2.0, s.recentFps, 0.00001)
        assertEquals(1220.0 / 3, s.averageGapMs, 0.00001)
        assertEquals(1100.0, s.maxGapMs, 0.00001)
        assertEquals(listOf(2L, 1L, 1L, 1L, 1L), s.gaps)
        assertEquals(1L, s.baselines)
        assertEquals(2L, s.changed)
        assertEquals(1L, s.unchanged)
        assertEquals(200.0 / 3, s.changedPercent, 0.00001)
        val silent = m.snapshot(3_000_000_000)
        assertEquals(0.0, silent.recentFps, 0.0)
        assertEquals(1780.0, silent.lastFrameAgeMs, 0.00001)
        assertEquals(1100.0, silent.maxGapMs, 0.00001) // Completed gaps only.
    }

    @Test fun visibilityProcessingAndFreshSession() {
        val m = CaptureMetrics(100)
        m.visibility(100_000_100, true)
        m.visibility(200_000_100, true) // Duplicate notification is not a transition.
        m.visibility(400_000_100, false)
        m.processing(200_000)
        m.processing(600_000)
        val s = m.snapshot(1_000_000_100)
        assertEquals(100.0, s.unknownMs, 0.00001)
        assertEquals(300.0, s.visibleMs, 0.00001)
        assertEquals(600.0, s.hiddenMs, 0.00001)
        assertEquals(1L, s.visibilityChanges)
        assertEquals(0.4, s.averageProcessingMs, 0.00001)
        assertEquals(0.6, s.maxProcessingMs, 0.00001)
        assertEquals(0.0, s.averageFps, 0.0)
        val fresh = CaptureMetrics(2_000_000_000).snapshot(2_000_000_000)
        assertEquals(CaptureMetricsSnapshot(), fresh)
    }

    @Test fun largeMonotonicTimestampsAndRepeatedSnapshots() {
        val start = 8_000_000_000_000_000L
        val m = CaptureMetrics(start)
        m.frame(start + 10_000_000, null)
        m.frame(start + 30_000_000, null) // New reference after a resize.
        m.visibility(start, true)
        val first = m.snapshot(start + 1_000_000_000)
        assertEquals(first, m.snapshot(start + 1_000_000_000))
        assertEquals(2L, first.baselines)
        assertEquals(0.0, first.changedPercent, 0.0)
        assertEquals(20.0, first.averageGapMs, 0.00001)
        assertEquals(1000.0, first.visibleMs, 0.00001)
    }
}
