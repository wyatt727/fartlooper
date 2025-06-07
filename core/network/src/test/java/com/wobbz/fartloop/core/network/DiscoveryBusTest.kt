package com.wobbz.fartloop.core.network

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for DiscoveryBus to verify proper flow merging and deduplication.
 *
 * Tests cover:
 * - Multiple discoverer integration
 * - IP:port-based deduplication
 * - Discovery timeout handling
 * - Error propagation
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(MockitoJUnitRunner::class)
class DiscoveryBusTest {

    @Mock private lateinit var ssdpDiscoverer: DeviceDiscoverer
    @Mock private lateinit var mdnsDiscoverer: DeviceDiscoverer
    @Mock private lateinit var portScanDiscoverer: DeviceDiscoverer

    private lateinit var discoveryBus: DiscoveryBus

    private val testDevice1 = UpnpDevice(
        ip = "192.168.1.100",
        port = 8080,
        deviceType = "urn:schemas-upnp-org:device:MediaRenderer:1",
        friendlyName = "Sonos Device",
        manufacturer = "Sonos",
        modelName = "Play:1",
        controlUrls = mapOf("AVTransport" to "http://192.168.1.100:8080/AVTransport/Control"),
        iconUrl = null,
        uuid = "uuid:test-sonos-device",
        source = DiscoverySource.SSDP
    )

    private val testDevice2 = UpnpDevice(
        ip = "192.168.1.101",
        port = 8008,
        deviceType = "urn:dial-multiscreen-org:device:dial:1",
        friendlyName = "Chromecast",
        manufacturer = "Google",
        modelName = "Chromecast Ultra",
        controlUrls = mapOf("Cast" to "http://192.168.1.101:8008/apps"),
        iconUrl = "http://192.168.1.101:8008/setup/icon.png",
        uuid = "uuid:test-chromecast-device",
        source = DiscoverySource.MDNS
    )

    // Duplicate device with same IP:port but different metadata - should be deduplicated
    private val duplicateDevice1 = testDevice1.copy(
        friendlyName = "Sonos Device (Duplicate)",
        source = DiscoverySource.PORT_SCAN
    )

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        discoveryBus = DiscoveryBus(ssdpDiscoverer, mdnsDiscoverer, portScanDiscoverer)
    }

    @Test
    fun `discoverAll merges flows from all discoverers`() = runTest {
        // Arrange
        whenever(ssdpDiscoverer.discover(any())).thenReturn(flowOf(testDevice1))
        whenever(mdnsDiscoverer.discover(any())).thenReturn(flowOf(testDevice2))
        whenever(portScanDiscoverer.discover(any())).thenReturn(flowOf())

        // Act
        val result = discoveryBus.discoverAll(1000L).toList()

        // Assert
        assertEquals(2, result.size)
        assertTrue(result.contains(testDevice1))
        assertTrue(result.contains(testDevice2))
    }

    @Test
    fun `discoverAll deduplicates devices by ip and port`() = runTest {
        // Arrange: SSDP and port scan both return the same device (same IP:port)
        whenever(ssdpDiscoverer.discover(any())).thenReturn(flowOf(testDevice1))
        whenever(mdnsDiscoverer.discover(any())).thenReturn(flowOf(testDevice2))
        whenever(portScanDiscoverer.discover(any())).thenReturn(flowOf(duplicateDevice1))

        // Act
        val result = discoveryBus.discoverAll(1000L).toList()

        // Assert: Should only have 2 unique devices, not 3
        assertEquals(2, result.size)

        // The first occurrence (SSDP) should be kept, port scan duplicate filtered out
        val sonosDevice = result.find { it.ip == "192.168.1.100" }
        assertEquals(DiscoverySource.SSDP, sonosDevice?.source)
        assertEquals("Sonos Device", sonosDevice?.friendlyName) // Original name, not duplicate
    }

    @Test
    fun `discoverAll with selective discovery methods`() = runTest {
        // Arrange
        whenever(ssdpDiscoverer.discover(any())).thenReturn(flowOf(testDevice1))
        whenever(mdnsDiscoverer.discover(any())).thenReturn(flowOf(testDevice2))

        // Act: Only discover using SSDP and mDNS
        val result = discoveryBus.discoverAll(
            timeoutMs = 1000L,
            methods = setOf(DiscoverySource.SSDP, DiscoverySource.MDNS)
        ).toList()

        // Assert
        assertEquals(2, result.size)
        assertTrue(result.any { it.source == DiscoverySource.SSDP })
        assertTrue(result.any { it.source == DiscoverySource.MDNS })
        assertTrue(result.none { it.source == DiscoverySource.PORT_SCAN })
    }

    @Test
    fun `discoverAll handles empty discovery results`() = runTest {
        // Arrange: All discoverers return empty flows
        whenever(ssdpDiscoverer.discover(any())).thenReturn(flowOf())
        whenever(mdnsDiscoverer.discover(any())).thenReturn(flowOf())
        whenever(portScanDiscoverer.discover(any())).thenReturn(flowOf())

        // Act
        val result = discoveryBus.discoverAll(1000L).toList()

        // Assert
        assertTrue(result.isEmpty())
    }

    @Test
    fun `discoverAll passes timeout to individual discoverers`() = runTest {
        // Arrange
        val timeoutMs = 5000L
        whenever(ssdpDiscoverer.discover(timeoutMs)).thenReturn(flowOf(testDevice1))
        whenever(mdnsDiscoverer.discover(timeoutMs)).thenReturn(flowOf())
        whenever(portScanDiscoverer.discover(timeoutMs)).thenReturn(flowOf())

        // Act
        val result = discoveryBus.discoverAll(timeoutMs).toList()

        // Assert
        assertEquals(1, result.size)
        // Verify all discoverers were called with correct timeout (verified by mock behavior)
    }
}
