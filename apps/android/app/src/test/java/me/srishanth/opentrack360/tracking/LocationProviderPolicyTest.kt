package me.srishanth.opentrack360.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class LocationProviderPolicyTest {
    @Test
    fun preciseLocationRegistersGpsAndNetworkWhenBothAreEnabled() {
        assertEquals(
            listOf(LocationProviderPolicy.GPS_PROVIDER, LocationProviderPolicy.NETWORK_PROVIDER),
            LocationProviderPolicy.select(setOf("gps", "network", "passive"), precisePermission = true),
        )
    }

    @Test
    fun approximateLocationNeverRegistersGpsAndCanFallBackToPassive() {
        assertEquals(
            listOf(LocationProviderPolicy.NETWORK_PROVIDER, LocationProviderPolicy.PASSIVE_PROVIDER),
            LocationProviderPolicy.select(setOf("gps", "network", "passive"), precisePermission = false),
        )
        assertEquals(
            listOf(LocationProviderPolicy.PASSIVE_PROVIDER),
            LocationProviderPolicy.select(setOf("gps", "passive"), precisePermission = false),
        )
    }

    @Test
    fun reportsNoCandidateWhenNoPermittedProviderIsEnabled() {
        assertEquals(
            emptyList<String>(),
            LocationProviderPolicy.select(setOf("gps"), precisePermission = false),
        )
    }
}
