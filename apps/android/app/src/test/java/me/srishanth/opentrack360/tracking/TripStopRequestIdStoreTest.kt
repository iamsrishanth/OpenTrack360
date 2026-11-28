package me.srishanth.opentrack360.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripStopRequestIdStoreTest {
    @Test
    fun retryReusesPersistedRequestIdUntilSuccessfulCompletion() {
        val storage = InMemoryStorage()
        val firstStore = TripStopRequestIdStore(storage)
        val firstRequestId = firstStore.clientRequestIdFor(TRIP_ID)

        val afterProcessRestart = TripStopRequestIdStore(storage)
        assertEquals(firstRequestId, afterProcessRestart.clientRequestIdFor(TRIP_ID))

        assertTrue(afterProcessRestart.markCompleted(TRIP_ID))
        assertNotEquals(firstRequestId, afterProcessRestart.clientRequestIdFor(TRIP_ID))
    }

    @Test
    fun requestIdsAreScopedPerTrip() {
        val store = TripStopRequestIdStore(InMemoryStorage())

        assertNotEquals(store.clientRequestIdFor(TRIP_ID), store.clientRequestIdFor(OTHER_TRIP_ID))
    }

    private class InMemoryStorage : TripStopRequestIdStorage {
        private val values = mutableMapOf<String, String>()
        override fun read(tripId: String): String? = values[tripId]
        override fun write(tripId: String, clientRequestId: String): Boolean {
            values[tripId] = clientRequestId
            return true
        }
        override fun remove(tripId: String): Boolean = values.remove(tripId) != null
    }

    private companion object {
        const val TRIP_ID = "33d697a7-1a47-471d-875d-e5957e92e709"
        const val OTHER_TRIP_ID = "5fdd0900-4f52-4662-985f-d0f49caa1880"
    }
}
