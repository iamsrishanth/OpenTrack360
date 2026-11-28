package me.srishanth.opentrack360.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingLocationProviderPolicyTest {
    @Test
    fun preciseLocationUsesEveryEnabledPermittedProviderIncludingPassiveFallback() {
        assertEquals(
            listOf(TrackingLocationProvider.GPS, TrackingLocationProvider.NETWORK, TrackingLocationProvider.PASSIVE),
            TrackingLocationProviderPolicy.enabledProviders(
                LocationPrecision.PRECISE,
                setOf(TrackingLocationProvider.GPS, TrackingLocationProvider.NETWORK, TrackingLocationProvider.PASSIVE),
            ),
        )
    }

    @Test
    fun approximateLocationNeverRegistersGpsAndCanFallBackToPassive() {
        assertEquals(
            listOf(TrackingLocationProvider.NETWORK, TrackingLocationProvider.PASSIVE),
            TrackingLocationProviderPolicy.enabledProviders(
                LocationPrecision.APPROXIMATE,
                setOf(TrackingLocationProvider.GPS, TrackingLocationProvider.NETWORK, TrackingLocationProvider.PASSIVE),
            ),
        )
        assertEquals(
            listOf(TrackingLocationProvider.PASSIVE),
            TrackingLocationProviderPolicy.enabledProviders(
                LocationPrecision.APPROXIMATE,
                setOf(TrackingLocationProvider.GPS, TrackingLocationProvider.PASSIVE),
            ),
        )
    }

    @Test
    fun preciseLocationFallsBackToAnyEnabledPermittedProvider() {
        assertEquals(
            listOf(TrackingLocationProvider.NETWORK),
            TrackingLocationProviderPolicy.enabledProviders(
                LocationPrecision.PRECISE,
                setOf(TrackingLocationProvider.NETWORK),
            ),
        )
        assertEquals(
            emptyList<TrackingLocationProvider>(),
            TrackingLocationProviderPolicy.enabledProviders(
                LocationPrecision.APPROXIMATE,
                setOf(TrackingLocationProvider.GPS),
            ),
        )
    }
}
