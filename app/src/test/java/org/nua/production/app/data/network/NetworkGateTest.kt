package org.nua.production.app.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

class NetworkGateTest {

    private lateinit var mockContext: Context
    private lateinit var mockConnectivityManager: ConnectivityManager
    private lateinit var mockCapabilities: NetworkCapabilities
    private lateinit var networkGate: NetworkGate

    @Before
    fun setUp() {
        mockContext = mock(Context::class.java)
        mockConnectivityManager = mock(ConnectivityManager::class.java)
        mockCapabilities = mock(NetworkCapabilities::class.java)

        // Force system service lookups to resolve to our testing doubles safely
        `when`(mockContext.applicationContext).thenReturn(mockContext)
        `when`(mockContext.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(mockConnectivityManager)

        networkGate = NetworkGate(mockContext)
    }

    /**
     * Test Scenario 1: Total Offline State
     * Confirms that if no network interface is returned by the OS, the gate shuts immediately.
     */
    @Test
    fun verifyOfflineStateReturnsFalse() {
        `when`(mockConnectivityManager.activeNetwork).thenReturn(null)
        assertFalse("Network gate remained open despite zero active network hardware links.", networkGate.isCloudReachable())
    }

    /**
     * Test Scenario 2: Connected but Local-Only (Captures the Captive Portal/Mesh state)
     * Verifies that having an active network link without internet clearance evaluates to false.
     */
    @Test
    fun verifyLocalNetworkWithoutInternetReturnsFalse() {
        val mockNetwork = mock(android.net.Network::class.java)
        `when`(mockConnectivityManager.activeNetwork).thenReturn(mockNetwork)
        `when`(mockConnectivityManager.getNetworkCapabilities(mockNetwork)).thenReturn(mockCapabilities)
        
        // Simulates being connected to a local mesh router that lacks an active internet uplink
        `when`(mockCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(false)

        assertFalse("Network gate erroneously allowed cloud connection routines over a local-only link.", networkGate.isCloudReachable())
    }

    /**
     * Test Scenario 3: Validated Cloud Internet Clearance (The Happy Path)
     * Asserts that when the OS reports internet capabilities, the cloud path activates successfully.
     */
    @Test
    fun verifyValidatedInternetConnectionReturnsTrue() {
        val mockNetwork = mock(android.net.Network::class.java)
        `when`(mockConnectivityManager.activeNetwork).thenReturn(mockNetwork)
        `when`(mockConnectivityManager.getNetworkCapabilities(mockNetwork)).thenReturn(mockCapabilities)
        
        `when`(mockCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)
        `when`(mockCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).thenReturn(true)

        assertTrue("Network gate failed to open despite a fully validated internet connection vector.", networkGate.isCloudReachable())
    }
}
