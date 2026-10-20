package com.pradertamas.uicapturetest

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ReceiverTraceTest {
    @Test fun traceIsReceiveOnlyAndLateSocketCallbackRetainsOriginalSessionCorrelation() {
        val lines = mutableListOf<String>(); val trace = ReceiverTrace(lines::add)
        trace.begin(); val oldSocket = trace.snapshot()
        oldSocket("socket-open"); val oldSession = lines.last().substringBefore(" socket-open")
        trace.begin(); oldSocket("socket-closed")
        assertTrue(lines.all { it.startsWith("role=RECEIVE process=") })
        assertEquals(oldSession, lines.last().substringBefore(" socket-closed"))
        assertNotEquals(lines[2].substringBefore(" session-start"), oldSession)
    }
    @Test fun socketExceptionIncludesTypeAndUsefulMessageWithoutUrlOrCredentials() {
        val text = ReceiverTrace.exception(IOException("connection reset wss://host/ws?session=ABC234 secret=private-value token=abcdefghijklmnopqrstuvwxyz"))
        assertTrue(text.contains("IOException")); assertTrue(text.contains("connection reset"))
        assertFalse(text.contains("wss://")); assertFalse(text.contains("ABC234"))
        assertFalse(text.contains("private-value")); assertFalse(text.contains("abcdefghijklmnopqrstuvwxyz"))
    }
}
