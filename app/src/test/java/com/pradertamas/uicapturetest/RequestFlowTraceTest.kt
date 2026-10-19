package com.pradertamas.uicapturetest

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class RequestFlowTraceTest {
    @Test fun correlationIsStableAcrossSidesWithoutRenderingRequestOrSessionSecrets() {
        val metadata = ConnectionRequestMetadata("synthetic-request-private", ConnectionRequestState.CONNECTING,
            Instant.EPOCH, MediaRole.SEND, assignment = ConnectionAssignment("ABC234", MediaRole.RECEIVE, PairingIntent.JOIN),
            failureReason = "sensitive-untrusted-server-text", initiatorReady = true, recipientReady = true)
        val a = RequestFlowTrace.format(RequestFlowTrace.Event.HTTP_COMPLETE,
            state = ConnectionRequestUiState(ConnectionRequestSide.INITIATOR, metadata, ConnectionRequestOperation.STATUS))
        val b = RequestFlowTrace.format(RequestFlowTrace.Event.HTTP_COMPLETE,
            state = ConnectionRequestUiState(ConnectionRequestSide.RECIPIENT, metadata, ConnectionRequestOperation.ACCEPT))
        val tag = Regex("request=([0-9a-f]{12})").find(a)!!.groupValues[1]
        assertTrue(b.contains("request=$tag")); assertTrue(a.contains("role=SEND")); assertTrue(b.contains("role=RECEIVE"))
        assertTrue(b.contains("operation=ACCEPT")); assertTrue(b.contains("assignment=JOIN"))
        for (text in listOf(a, b)) {
            assertFalse(text.contains(metadata.requestId)); assertFalse(text.contains("ABC234"))
            assertFalse(text.contains("sensitive-untrusted-server-text")); assertFalse(text.contains("https://"))
        }
    }
}
