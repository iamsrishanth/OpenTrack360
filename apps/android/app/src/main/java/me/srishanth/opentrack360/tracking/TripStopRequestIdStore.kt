package me.srishanth.opentrack360.tracking

import android.content.Context
import java.util.UUID

interface TripStopRequestIdStorage {
    fun read(tripId: String): String?
    fun write(tripId: String, clientRequestId: String): Boolean
    fun remove(tripId: String): Boolean
}

/** Keeps one retry key per active trip so an ambiguous stop response can be replayed safely. */
class TripStopRequestIdStore(private val storage: TripStopRequestIdStorage) {
    private val lock = Any()

    fun clientRequestIdFor(tripId: String): String = synchronized(lock) {
        val normalizedTripId = UUID.fromString(tripId).toString()
        val saved = storage.read(normalizedTripId)?.takeIf(::isUuid)
        if (saved != null) return saved

        val requestId = UUID.randomUUID().toString()
        check(storage.write(normalizedTripId, requestId)) { "Could not persist the trip stop retry key." }
        requestId
    }

    /** Call only after the stop endpoint has returned its successful terminal result. */
    fun markCompleted(tripId: String): Boolean = synchronized(lock) {
        storage.remove(UUID.fromString(tripId).toString())
    }

    private fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess
}

class SharedPreferencesTripStopRequestIdStorage(context: Context) : TripStopRequestIdStorage {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(tripId: String): String? = preferences.getString(key(UUID.fromString(tripId).toString()), null)

    override fun write(tripId: String, clientRequestId: String): Boolean = preferences.edit()
        .putString(key(UUID.fromString(tripId).toString()), UUID.fromString(clientRequestId).toString())
        .commit()

    override fun remove(tripId: String): Boolean = preferences.edit()
        .remove(key(UUID.fromString(tripId).toString()))
        .commit()

    private fun key(tripId: String) = "$KEY_PREFIX$tripId"

    private companion object {
        const val PREFERENCES_NAME = "opentrack360_trip_stop_commands"
        const val KEY_PREFIX = "trip_stop_request_"
    }
}
