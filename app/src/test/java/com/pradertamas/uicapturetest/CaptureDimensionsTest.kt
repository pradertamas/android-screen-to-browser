package com.pradertamas.uicapturetest

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class CaptureDimensionsTest {
    @Test fun profilesAndNoUpscale() {
        val cases = listOf(
            CaptureSize(1080, 2340) to CaptureSize(720, 1560),
            CaptureSize(2340, 1080) to CaptureSize(1560, 720),
            CaptureSize(1920, 1080) to CaptureSize(1280, 720),
            CaptureSize(1080, 1920) to CaptureSize(720, 1280),
            CaptureSize(1200, 1200) to CaptureSize(720, 720),
            CaptureSize(640, 360) to CaptureSize(640, 360),
            CaptureSize(321, 241) to CaptureSize(320, 240))
        for ((input, expected) in cases) {
            val out = checkNotNull(CaptureDimensions.fit(input.width, input.height))
            assertEquals(expected, out)
            assertEquals(0, out.width % 2); assertEquals(0, out.height % 2)
            assertTrue(out.width <= input.width && out.height <= input.height)
            assertTrue(out.width * out.height <= CaptureDimensions.MAX_PIXELS)
            val relativeError = abs(out.width.toDouble() / out.height / (input.width.toDouble() / input.height) - 1)
            assertTrue(relativeError < 0.01)
        }
        for (invalid in listOf(CaptureSize(0, 1080), CaptureSize(1080, -1), CaptureSize(1, 1), CaptureSize(2, Int.MAX_VALUE)))
            assertNull(CaptureDimensions.fit(invalid.width, invalid.height))
    }
    private class Clock {
        data class Task(val run: () -> Unit, var cancelled: Boolean = false)
        val tasks = mutableListOf<Task>()
        fun later(delay: Long, action: () -> Unit): () -> Unit {
            assertEquals(100L, delay)
            val task = Task(action); tasks.add(task); return { task.cancelled = true }
        }
        fun flush() { val pending = tasks.toList(); tasks.clear(); pending.filterNot { it.cancelled }.forEach { it.run() } }
    }
    @Test fun duplicatesCoalesceLatestAndReturnToCurrent() {
        val clock = Clock(); val applied = mutableListOf<CaptureSize>()
        val controller = CaptureResizeController(clock::later) { applied.add(it) }
        controller.start(); controller.resized(1080, 2340); clock.flush(); assertTrue(applied.isEmpty())
        controller.resized(2340, 1080); controller.resized(1920, 1080); controller.resized(2340, 1080)
        clock.flush(); assertEquals(listOf(CaptureSize(1560, 720)), applied)
        controller.resized(1080, 2340); controller.resized(2340, 1080); clock.flush()
        assertEquals(1, applied.size) // Last event returned to already applied dimensions.
        controller.resized(1080, 2340); clock.flush(); assertEquals(CaptureSize(720, 1560), controller.output)
    }
    @Test fun stopIgnoresLateTasksAndCleanRestart() {
        val clock = Clock(); var count = 0
        val controller = CaptureResizeController(clock::later) { count++ }
        controller.start(); controller.resized(2340, 1080)
        val stale = clock.tasks.last().run
        controller.stop(); controller.stop(); controller.resized(1920, 1080); stale(); clock.flush()
        assertEquals(0, count); assertNull(controller.output); assertNull(controller.content)
        controller.start(); stale(); assertEquals(0, count)
        controller.resized(2340, 1080); clock.flush(); assertEquals(1, count)
    }
    @Test fun stopDuringApplyCannotRestoreDisposedSize() {
        val clock = Clock(); lateinit var controller: CaptureResizeController
        controller = CaptureResizeController(clock::later) { controller.stop() }
        controller.start(); controller.resized(2340, 1080); clock.flush()
        assertNull(controller.output)
    }
}
