package me.srishanth.opentrack360.tracking

/** Selects every enabled provider permitted by the member's current location precision. */
internal object LocationProviderPolicy {
    const val GPS_PROVIDER = "gps"
    const val NETWORK_PROVIDER = "network"
    const val PASSIVE_PROVIDER = "passive"

    fun select(enabledProviders: Set<String>, precisePermission: Boolean): List<String> {
        val permitted = if (precisePermission) {
            listOf(GPS_PROVIDER, NETWORK_PROVIDER)
        } else {
            listOf(NETWORK_PROVIDER, PASSIVE_PROVIDER)
        }
        return permitted.filter(enabledProviders::contains)
    }
}
