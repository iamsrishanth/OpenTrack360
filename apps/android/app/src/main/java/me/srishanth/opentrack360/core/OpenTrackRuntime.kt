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
import me.srishanth.opentrack360.tracking.ArrivalShareTarget
import me.srishanth.opentrack360.tracking.ArrivalShareTargetStore
import me.srishanth.opentrack360.tracking.DriveEndShareTarget
import me.srishanth.opentrack360.tracking.DriveUpdate
import me.srishanth.opentrack360.tracking.OfflineTrackingQueue
import me.srishanth.opentrack360.tracking.SharedPreferencesTrackingBlobStorage
import me.srishanth.opentrack360.tracking.SharedPreferencesTripStopRequestIdStorage
import me.srishanth.opentrack360.tracking.TrackingRuntime
import me.srishanth.opentrack360.tracking.TrackingRuntimeProvider
import me.srishanth.opentrack360.tracking.TrackingScope
import me.srishanth.opentrack360.tracking.TrackingStatus
import me.srishanth.opentrack360.tracking.TrackingEngine
import me.srishanth.opentrack360.tracking.TripProgressNotificationContent
import me.srishanth.opentrack360.tracking.TripStopRequestIdStore
import me.srishanth.opentrack360.sharing.CreateShareRequest
import me.srishanth.opentrack360.sharing.CreatedShare
import me.srishanth.opentrack360.sharing.ShareMode
import me.srishanth.opentrack360.sharing.SenderShareStatus
import java.util.UUID

/** App-scoped clients. No backend service credential is shipped in the APK. */
class OpenTrackRuntime(context: Context) : TrackingRuntimeProvider {
    private val appContext = context.applicationContext
    private val sharingPreferences = appContext.getSharedPreferences(SHARING_PREFERENCES, Context.MODE_PRIVATE)
    val sessions = KeystoreSessionStore(appContext)
    val api = OpenTrackApi(sessions)
    private val mutableDriveEndShares = MutableStateFlow<Map<String, BoundDriveEndShare>>(emptyMap())
    private val arrivalShareTargets = ArrivalShareTargetStore(
        SharedPreferencesTrackingBlobStorage(appContext, ARRIVAL_TARGETS_KEY),
        AndroidKeystoreTrackingQueueCipher(),
    )
    private val tripStopRequestIds = TripStopRequestIdStore(SharedPreferencesTripStopRequestIdStorage(appContext))
    val shareGateway = ApiShareGateway(
        api,
        ::cacheShares,
        onShareCreated = { created, request ->
            val memberId = sessions.load()?.memberId
            val sessionId = memberId?.let { sharingPreferences.getString(sessionKey(it), null) }
            if (memberId != null && sessionId != null && request is CreateShareRequest.UntilDestination) {
                arrivalShareTargets.put(
                    memberId,
                    sessionId,
                    ArrivalShareTarget(created.id, request.latitude, request.longitude, request.radiusMeters),
                )
            }
            if (request is CreateShareRequest.UntilDriveEnd && created.status.wireValue == "active") {
                val memberId = sessions.load()?.memberId
                val sessionId = memberId?.let { sharingPreferences.getString(sessionKey(it), null) }
                val target = created.asDriveEndTarget()
                if (memberId != null && sessionId != null && target?.sharingEpoch == sharingEpoch(memberId)) {
                    mutableDriveEndShares.value = mutableDriveEndShares.value +
                        (created.id to BoundDriveEndShare(memberId, sessionId, target))
                } else {
                    mutableDriveEndShares.value = mutableDriveEndShares.value - created.id
                }
            }
        },
        onShareRevoked = { shareId ->
            arrivalShareTargets.removeForShare(shareId)
            mutableDriveEndShares.value = mutableDriveEndShares.value - shareId
        },
    )
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
        arrivalShareTargets.clearExcept(memberId, sessionId)
        mutableDriveEndShares.value = mutableDriveEndShares.value.filterValues { it.memberId != memberId }
        sharingPreferences.edit().putString(sessionKey(memberId), sessionId).commit()
        return sessionId
    }

    fun ensureMemberSession(memberId: String): String {
        sharingPreferences.getString(sessionKey(memberId), null)?.let { return it }
        return startMemberSession(memberId)
    }

    private fun cacheShares(shares: List<SenderShare>) {
        val ownerId = sessions.load()?.memberId
        val sessionId = ownerId?.let { sharingPreferences.getString(sessionKey(it), null) }
        val epoch = ownerId?.let(::sharingEpoch)
        mutableDriveEndShares.value = if (ownerId != null && sessionId != null && epoch != null) {
            shares.asSequence()
                .filter { it.status == SenderShareStatus.ACTIVE && it.mode == ShareMode.DRIVE_END }
                .mapNotNull { share ->
                    val target = share.asDriveEndTarget() ?: return@mapNotNull null
                    if (target.sharingEpoch != epoch) return@mapNotNull null
                    share.id to BoundDriveEndShare(ownerId, sessionId, target)
                }
                .toMap()
        } else {
            emptyMap()
        }
        if (ownerId != null && sessionId != null) {
            shares.asSequence()
                .filter { it.mode.wireValue == "destination" && it.status.wireValue == "active" }
                .forEach { share ->
                    val destination = share.destination ?: return@forEach
                    arrivalShareTargets.put(
                        ownerId,
                        sessionId,
                        ArrivalShareTarget(
                            shareId = share.id,
                            latitude = destination.latitude,
                            longitude = destination.longitude,
                            radiusMeters = destination.radiusMeters,
                        ),
                    )
                }
        }
        shares.asSequence()
            .filter { it.mode.wireValue == "destination" && (it.status.wireValue != "active" || it.destination == null) }
            .forEach { arrivalShareTargets.removeForShare(it.id) }
    }

    fun endMemberSession(memberId: String?) {
        if (memberId != null) {
            arrivalShareTargets.clearFor(memberId)
            mutableDriveEndShares.value = mutableDriveEndShares.value.filterValues { it.memberId != memberId }
            mutableDriveUpdate.value = null
            sharingPreferences.edit().remove(sessionKey(memberId)).commit()
        }
    }

    fun setSharingConsent(memberId: String, accepted: Boolean) {
        val editor = sharingPreferences.edit()
            .putBoolean(consentKey(memberId), accepted)
            .putLong(generationKey(memberId), generation(memberId) + 1)
        if (!accepted) editor.putBoolean(sharingEnabledKey(memberId), false)
        editor.commit()
        if (!accepted) arrivalShareTargets.clearFor(memberId)
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
        if (sharingPreferences.getLong(epochKey(memberId), 1) != epoch) arrivalShareTargets.clearFor(memberId)
        sharingPreferences.edit().putLong(epochKey(memberId), epoch).commit()
    }

    fun syncSharingEpoch(memberId: String, epoch: Long) = updateSharingEpoch(memberId, epoch)

    fun sharingEpoch(memberId: String): Long = sharingPreferences.getLong(epochKey(memberId), 1)

    fun tripStopClientRequestId(tripId: String): String = tripStopRequestIds.clientRequestIdFor(tripId)

    fun confirmTripStop(tripId: String) {
        tripStopRequestIds.markCompleted(tripId)
    }

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
        activeDriveEndShareTargets = { scope ->
            mutableDriveEndShares.value.values
                .filter { it.memberId == scope.userId && it.sessionId == scope.sessionId && it.target.sharingEpoch == scope.sharingEpoch }
                .map { it.target }
        },
        onDriveEndShareEventQueued = { shareId -> mutableDriveEndShares.value = mutableDriveEndShares.value - shareId },
        activeArrivalShareTargets = { scope -> arrivalShareTargets.activeFor(scope.userId, scope.sessionId) },
        onArrivalShareEventQueued = { scope, shareId -> arrivalShareTargets.remove(scope.userId, scope.sessionId, shareId) },
        onDriveUpdate = { mutableDriveUpdate.value = it },
        onSharingEpoch = { memberId, epoch -> updateSharingEpoch(memberId, epoch) },
        onTrackingStatus = { status ->
            mutableTrackingStatus.value = status
            if (status == TrackingStatus.CONSENT_REVOKED) {
                sessions.load()?.memberId?.let(arrivalShareTargets::clearFor)
            }
            if (status in setOf(TrackingStatus.STOPPED, TrackingStatus.PERMISSION_LOST, TrackingStatus.CONSENT_REVOKED)) {
                clearLiveTripProgressNotification()
            }
        },
        tripProgressNotification = { liveTripProgressNotification },
        onGlobalStop = {
            arrivalShareTargets.clearAll()
            mutableDriveEndShares.value = emptyMap()
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

    private fun CreatedShare.asDriveEndTarget(): DriveEndShareTarget? = buildDriveEndTarget(
        shareId = id,
        mode = mode,
        status = status,
        shareStartedAt = startedAt,
        tripId = tripId,
        tripStartedAt = tripStartedAt,
        tripSessionMatchesCurrent = tripSessionMatchesCurrent,
        sharingEpoch = sharingEpoch,
    )

    private fun SenderShare.asDriveEndTarget(): DriveEndShareTarget? = buildDriveEndTarget(
        shareId = id,
        mode = mode,
        status = status,
        shareStartedAt = startedAt,
        tripId = tripId,
        tripStartedAt = tripStartedAt,
        tripSessionMatchesCurrent = tripSessionMatchesCurrent,
        sharingEpoch = sharingEpoch,
    )

    private fun buildDriveEndTarget(
        shareId: String,
        mode: ShareMode,
        status: SenderShareStatus,
        shareStartedAt: java.time.Instant,
        tripId: String?,
        tripStartedAt: java.time.Instant?,
        tripSessionMatchesCurrent: Boolean?,
        sharingEpoch: Long,
    ): DriveEndShareTarget? {
        if (mode != ShareMode.DRIVE_END || status != SenderShareStatus.ACTIVE || tripSessionMatchesCurrent != true) return null
        val linkedTripId = tripId ?: return null
        val linkedTripStartedAt = tripStartedAt ?: return null
        return runCatching {
            DriveEndShareTarget(
                shareId = shareId,
                tripId = linkedTripId,
                shareStartedAt = shareStartedAt,
                tripStartedAt = linkedTripStartedAt,
                tripSessionMatchesCurrent = true,
                sharingEpoch = sharingEpoch,
            )
        }.getOrNull()
    }

    private data class BoundDriveEndShare(
        val memberId: String,
        val sessionId: String,
        val target: DriveEndShareTarget,
    )

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
        const val ARRIVAL_TARGETS_KEY = "encrypted_arrival_targets_v1"
    }
}
