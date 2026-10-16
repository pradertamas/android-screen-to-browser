package com.pradertamas.uicapturetest

import org.junit.Assert.*
import org.junit.Test

class NetworkMetricsTest {
    @Test fun ratesAndCostEdgeCases() {
        assertNull(NetworkMetrics.gbPerHour(1000.0, 0.0))
        assertNull(NetworkMetrics.gbPerHour(null, 120.0))
        assertNull(NetworkMetrics.gbPerHour(Double.NaN, 120.0))
        assertNull(NetworkMetrics.gbPerHour(-1.0, 120.0))
        assertEquals(0.0, NetworkMetrics.gbPerHour(0.0, 120.0)!!, 0.0)
        assertEquals(0.0, NetworkMetrics.costPerHour(NetworkMetrics.gbPerHour(0.0, 120.0), 0.05, true)!!, 0.0)
        assertNull(NetworkMetrics.costPerHour(NetworkMetrics.gbPerHour(100.0, 0.0), 0.05, true))
        val rate = NetworkMetrics.gbPerHour(25_972_571.0, 124.15)!!
        assertEquals(0.7531313379, rate, 0.00000001)
        assertEquals(rate * 0.05, NetworkMetrics.costPerHour(rate, 0.05, true)!!, 0.00000001)
        assertNull(NetworkMetrics.costPerHour(rate, 0.05, false))
        assertNull(NetworkMetrics.costPerHour(rate, Double.NaN, true))
        assertEquals(3600.0, NetworkMetrics.gbPerHour(1e12, 1000.0)!!, 0.00001)
        assertNull(NetworkMetrics.gbPerHour(Double.MAX_VALUE, 0.001))
    }
    @Test fun strictRelayProofAndModes() {
        assertEquals("all", ConnectionMode.AUTO.policy)
        assertEquals("relay", ConnectionMode.FORCE_TURN.policy)
        assertTrue(NetworkMetrics.relayPair("relay", "relay", "succeeded"))
        assertFalse(NetworkMetrics.relayPair("relay", "srflx", "succeeded"))
        assertFalse(NetworkMetrics.relayPair("relay", "relay", "in-progress"))
        assertFalse(NetworkMetrics.relayPair(null, null, null))
        assertEquals("IPv4", NetworkMetrics.addressFamily("192.0.2.1"))
        assertEquals("IPv6", NetworkMetrics.addressFamily("2001:db8::1"))
        assertEquals("N/A / obfuscated", NetworkMetrics.addressFamily("example.local"))
    }
}
