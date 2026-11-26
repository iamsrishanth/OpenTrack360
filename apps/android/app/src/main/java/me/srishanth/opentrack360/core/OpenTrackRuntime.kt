package me.srishanth.opentrack360.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.srishanth.opentrack360.core.api.ApiShareGateway
import me.srishanth.opentrack360.core.api.ApiTrackingTransport
import me.srishanth.opentrack360.core.api.OpenTrackApi
import me.srishanth.opentrack360.sharing.SenderShare
import me.srishanth.opentrack360.core.auth.KeystoreSessionStore
import me.srishanth.opentrack360.tracking.AndroidKeystoreTrackingQueueCipher
import me.srishanth.opentrack360.tracking.AndroidPermissionProbe
import me.srishanth.opentrack360.tracking.DriveUpdate
import me.srishanth.opentrack360.tracking.OfflineTrackingQueue
import me.srishanth.opentrack360.tracking.SharedPreferencesTrackingBlobStorage
import me.srishanth.opentrack360.tracking.TrackingRuntime
import me.srishanth.opentrack360.tracking.TrackingRuntimeProvider
import me.srishanth.opentrack360.tracking.TrackingScope
import me.srishanth.opentrack360.tracking.TrackingStatus
import me.srishanth.opentrack360.tracking.TrackingEngine
import me.srishanth.opentrack360.tracking.TripProgressNotificationContent
import java.util.UUID

/** App-scoped clients. No backend service credential is shipped in the APK. */
class OpenTrackRuntime(context: Context) : TrackingRuntimeProvider {
    private val appContext = context.applicationContext
    private val sharingPreferences = appContext.getSharedPreferences(SHARING_PREFERENCES, Context.MODE_PRIVATE)
    val sessions = KeystoreSessionStore(appContext)
    val api = OpenTrackApi(sessions)
    private val mutableDriveEndShares = MutableStateFlow<Set<String>>(emptySet())
    val shareGateway = ApiShareGateway(api, ::cacheShares)
    val trackingTransport = ApiTrackingTransport(api)
    val trackingEngine = TrackingEngine()
    val trackingQueue = OfflineTrackingQueue(
        SharedPreferencesTrackingBlobStorage(appContext),
        AndroidKeystoreTrackingQueueCipher(),
    )

    private val mutableTrackingStatus = MutableStateFlow<TrackingStatus?>(null)
    val trackingStatus = mutableTrackingStatus.asStateFlow()
    private val mutableDriveUpdate = MutableStateFlow<DriveUpdate?>(null)
    val latestDriveUpdate = mutableDriveUpdate.asStateFlow()
    @Volatile private var liveTripProgressNotification: TripProgressNotificationContent? = null
    @Volatile private var liveTripProgressMemberId: String? = null
    @Volatile private var liveTripProgressTripId: String? = null

    fun startMemberSession(memberId: String): String {
        val sessionId = UUID.randomUUID().toString()
        sharingPreferences.edit().putString(sessionKey(memberId), sessionId).commit()
        return sessionId
    }

    fun ensureMemberSession(memberId: String): String {
        sharingPreferences.getString(sessionKey(memberId), null)?.let { return it }
        return startMemberSession(memberId)
    }

    private fun cacheShares(shares: List<SenderShare>) {
        mutableDriveEndShares.value = shares.asSequence()
            .filter { it.status.wireValue == "active" && it.mode.wireValue == "drive_end" }
            .map { it.id }
            .toSet()
    }

    fun endMemberSession(memberId: String?) {
        if (memberId != null) sharingPreferences.edit().remove(sessionKey(memberId)).commit()
    }

    fun setSharingConsent(memberId: String, accepted: Boolean) {
        val editor = sharingPreferences.edit()
            .putBoolean(consentKey(memberId), accepted)
            .putLong(generationKey(memberId), generation(memberId) + 1)
        if (!accepted) editor.putBoolean(sharingEnabledKey(memberId), false)
        editor.commit()
    }

    fun setSharingEnabled(memberId: String, enabled: Boolean) {
        sharingPreferences.edit().putBoolean(sharingEnabledKey(memberId), enabled).commit()
    }

    fun sharingIsEnabled(memberId: String): Boolean = sharingPreferences.getBoolean(sharingEnabledKey(memberId), false)

    fun activitySharingEnabled(memberId: String, circleId: String): Boolean =
        sharingPreferences.getBoolean(activityConsentKey(memberId, circleId), false)

    fun setActivitySharingEnabled(memberId: String, circleId: String, enabled: Boolean) {
        sharingPreferences.edit().putBoolean(activityConsentKey(memberId, circleId), enabled).commit()
    }

    fun updateSharingEpoch(memberId: String?, epoch: Long) {
        if (memberId == null || epoch < 1) return
        sharingPreferences.edit().putLong(epochKey(memberId), epoch).commit()
    }

    fun syncSharingEpoch(memberId: String, epoch: Long) = updateSharingEpoch(memberId, epoch)

    fun sharingEpoch(memberId: String): Long = sharingPreferences.getLong(epochKey(memberId), 1)

    fun isTripLockScreenProgressEnabled(memberId: String, tripId: String): Boolean =
        sharingPreferences.getBoolean(tripLockScreenKey(memberId, tripId), false)

    /** Persist only the opt-in bit; destination and ETA stay in memory and are never written to disk. */
    fun setTripLockScreenProgress(
        memberId: String,
        tripId: String,
        enabled: Boolean,
        destinationLabel: String?,
        etaLabel: String?,
        distanceRemainingMeters: Int?,
    ) {
        val editor = sharingPreferences.edit()
        if (enabled) editor.putBoolean(tripLockScreenKey(memberId, tripId), true)
        else editor.remove(tripLockScreenKey(memberId, tripId))
        editor.commit()
        liveTripProgressMemberId = memberId
        liveTripProgressTripId = tripId
        liveTripProgressNotification = if (enabled) TripProgressNotificationContent(
            destinationLabel = destinationLabel,
            etaLabel = etaLabel,
            distanceRemainingMeters = distanceRemainingMeters,
        ) else null
    }

    fun clearTripLockScreenProgress(memberId: String, tripId: String?) {
        if (tripId != null) sharingPreferences.edit().remove(tripLockScreenKey(memberId, tripId)).commit()
        if (liveTripProgressMemberId == memberId && (tripId == null || liveTripProgressTripId == tripId)) {
            clearLiveTripProgressNotification()
        }
    }

    private fun clearLiveTripProgressNotification() {
        liveTripProgressNotification = null
        liveTripProgressMemberId = null
        liveTripProgressTripId = null
    }

    override fun create(context: Context): TrackingRuntime = TrackingRuntime(
        queue = trackingQueue,
        transport = trackingTransport,
        engine = trackingEngine,
        scopeProvider = {
            val session = sessions.load()?.takeUnless { it.mustRotatePassword }
            if (session == null) null else {
                val sessionId = sharingPreferences.getString(sessionKey(session.memberId), null)
                if (sessionId == null) null else {
                    val currentPrecision = AndroidPermissionProbe.snapshot(appContext).precision
                    val precision = currentPrecision ?: sharingPreferences.getString(lastPrecisionKey(session.memberId), "APPROXIMATE")
                        ?.let { stored -> runCatching { me.srishanth.opentrack360.tracking.LocationPrecision.valueOf(stored) }.getOrNull() }
                        ?: me.srishanth.opentrack360.tracking.LocationPrecision.APPROXIMATE
                    if (currentPrecision != null) sharingPreferences.edit()
                        .putString(lastPrecisionKey(session.memberId), currentPrecision.name)
                        .commit()
                    TrackingScope(
                        userId = session.memberId,
                        sessionId = sessionId,
                        consentGeneration = generation(session.memberId),
                        sharingEpoch = sharingEpoch(session.memberId),
                        precision = precision,
                    )
                }
            }
        },
        sharingConsentEnabled = {
            sessions.load()?.let { sharingPreferences.getBoolean(consentKey(it.memberId), false) } ?: false
        },
        sharingEnabled = {
            sessions.load()?.let { sharingIsEnabled(it.memberId) } ?: false
        },
        activeDriveEndShareIds = { mutableDriveEndShares.value.toList() },
        onDriveUpdate = { mutableDriveUpdate.value = it },
        onSharingEpoch = { memberId, epoch -> updateSharingEpoch(memberId, epoch) },
        onTrackingStatus = { status ->
            mutableTrackingStatus.value = status
            if (status in setOf(TrackingStatus.STOPPED, TrackingStatus.PERMISSION_LOST, TrackingStatus.CONSENT_REVOKED)) {
                clearLiveTripProgressNotification()
            }
        },
        tripProgressNotification = { liveTripProgressNotification },
        onGlobalStop = {
            val memberId = sessions.load()?.memberId
            if (memberId != null) {
                val editor = sharingPreferences.edit()
                sharingPreferences.all.keys
                    .filter { it.startsWith("trip_lock_screen_${memberId}_") }
                    .forEach(editor::remove)
                editor.commit()
            }
            clearLiveTripProgressNotification()
        },
    )

    private fun generation(memberId: String): Long = sharingPreferences.getLong(generationKey(memberId), 0)

    private fun sessionKey(memberId: String) = "session_$memberId"
    private fun consentKey(memberId: String) = "consent_$memberId"
    private fun generationKey(memberId: String) = "consent_generation_$memberId"
    private fun epochKey(memberId: String) = "sharing_epoch_$memberId"
    private fun lastPrecisionKey(memberId: String) = "last_precision_$memberId"
    private fun sharingEnabledKey(memberId: String) = "sharing_enabled_$memberId"
    private fun activityConsentKey(memberId: String, circleId: String) = "activity_consent_${memberId}_$circleId"
    private fun tripLockScreenKey(memberId: String, tripId: String) = "trip_lock_screen_${memberId}_$tripId"

    private companion object {
        const val SHARING_PREFERENCES = "sharing_controls"
    }
}
