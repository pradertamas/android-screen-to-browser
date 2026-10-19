package com.pradertamas.uicapturetest

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.webrtc.SurfaceViewRenderer

/** Real native peer/EGL lifetime, without remote media, capture, HTTP or signaling. */
class ReceiverRendererSessionTest {
    @get:Rule val activity = createAndroidComposeRule<ComponentActivity>()
    @Test fun replacingAndReleasingActivityRenderersKeepsNativePeerUntilExplicitClose() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val session = WebRtcReceiverSession(activity.activity.application, {}, {}, {}, {}, { _, _ -> }, {})
            fun field(name: String) = session.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(session)
            try {
                val peer = field("peer"); assertNotNull(peer)
                val a = SurfaceViewRenderer(activity.activity)
                val b = SurfaceViewRenderer(activity.activity)
                session.attach(a); session.detach(a)
                assertSame(peer, field("peer")); assertNull(field("renderer"))
                session.attach(b); session.detach(a) // Old Activity's late release must be inert.
                assertSame(b, field("renderer")); assertSame(peer, field("peer"))
                session.detach(b); assertSame(peer, field("peer"))
            } finally { session.close() }
            assertNull(field("peer")); assertNull(field("renderer"))
        }
    }
}
