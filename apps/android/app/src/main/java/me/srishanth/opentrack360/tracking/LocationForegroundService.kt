package me.srishanth.opentrack360.tracking

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Instant
import me.srishanth.opentrack360.tracking.PendingShareEvent
import me.srishanth.opentrack360.tracking.ShareEventType

/** Only the app process can install this provider; the service has no hard-coded API credentials. */
object TrackingRuntimeRegistry {
    @Volatile var provider: TrackingRuntimeProvider? = null
}

enum class TrackingStartResult { START_REQUESTED, LOCATION_PERMISSION_REQUIRED, SERVICE_UNAVAILABLE }

/** Permission policy helpers shared with the UI. This class never launches a permission prompt. */
object TrackingPermissionPolicy {
    fun mayRequestBackgroundLocation(
        permissions: PermissionSnapshot,
        memberExplicitlyChoseBackgroundSharing: Boolean,
    ): Boolean = permissions.canCollect && !permissions.hasBackgroundLocation && memberExplicitlyChoseBackgroundSharing

    fun backgroundSettingsIntent(context: Context, memberExplicitlyChoseBackgroundSharing: Boolean): Intent? {
        if (!memberExplicitlyChoseBackgroundSharing || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}

/**
 * Start entry point is intentionally an Activity method called after a visible member action.
 * There is no boot receiver, guest-link action, or sticky restart path.
 */
object TrackingServiceController {
    fun startFromUserAction(activity: Activity): TrackingStartResult {
        val permissions = AndroidPermissionProbe.snapshot(activity)
        if (!permissions.canCollect) return TrackingStartResult.LOCATION_PERMISSION_REQUIRED
        return runCatching {
            val intent = Intent(activity, LocationForegroundService::class.java)
                .setAction(LocationForegroundService.ACTION_START)
                .putExtra(LocationForegroundService.EXTRA_USER_INITIATED, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) activity.startForegroundService(intent)
            else activity.startService(intent)
            TrackingStartResult.START_REQUESTED
        }.getOrDefault(TrackingStartResult.SERVICE_UNAVAILABLE)
    }

    fun stopFromUserAction(context: Context) {
        context.startService(Intent(context, LocationForegroundService::class.java).setAction(LocationForegroundService.ACTION_GLOBAL_STOP))
    }

    /** Logout drops all queued private fixes/events and stops the foreground service without a revoke API call. */
    fun stopForLogout(context: Context) {
        context.startService(Intent(context, LocationForegroundService::class.java).setAction(LocationForegroundService.ACTION_LOGOUT))
    }

    /** Resume persisted revoke commands after sign-in/reconnect without starting location collection. */
    fun syncPendingCommandsFromUserAction(activity: Activity) {
        activity.startService(Intent(activity, LocationForegroundService::class.java).setAction(LocationForegroundService.ACTION_SYNC_PENDING))
    }

    fun refreshNotification(context: Context) {
        runCatching {
            context.startService(Intent(context, LocationForegroundService::class.java).setAction(LocationForegroundService.ACTION_REFRESH_NOTIFICATION))
        }
    }
}

object AndroidPermissionProbe {
    fun snapshot(context: Context): PermissionSnapshot {
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        val notifications = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val precision = when {
            fine -> LocationPrecision.PRECISE
            coarse -> LocationPrecision.APPROXIMATE
            else -> null
        }
        return PermissionSnapshot(
            hasLocation = fine || coarse,
            precision = precision,
            hasBackgroundLocation = background,
            hasNotifications = notifications,
            batterySaverOn = powerManager?.isPowerSaveMode == true,
        )
    }
}

class LocationForegroundService : Service(), LocationListener, SensorEventListener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var engine = TrackingEngine()
    private val arrivalDetector = DestinationArrivalDetector()
    private val motionTracker = AccelerometerMotionTracker()
    private var runtime: TrackingRuntime? = null
    private var sync: TrackingSyncCoordinator? = null
    private var locationManager: LocationManager? = null
    private var sensorManager: SensorManager? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var activeScope: TrackingScope? = null
    private var arrivalScope: TrackingScope? = null
    private var pendingStopScope: TrackingScope? = null
    private var currentPermission: PermissionSnapshot? = null
    private var started = false
    @Volatile private var waitingForStopSync = false
    private var lastRequestedDriveMode = false

    private val permissionWatch = object : Runnable {
        override fun run() {
            if (started) {
                refreshPermissionAndConsent()
                mainHandler.postDelayed(this, PERMISSION_WATCH_INTERVAL_MS)
            }
        }
    }

    private val stopRetry = object : Runnable {
        override fun run() {
            if (waitingForStopSync) {
                flushQueue()
                mainHandler.postDelayed(this, STOP_RETRY_INTERVAL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (intent.getBooleanExtra(EXTRA_USER_INITIATED, false)) beginTracking()
                else stopSelf(startId)
            }
            ACTION_GLOBAL_STOP -> stopLocallyAndPersistGlobalCommand()
            ACTION_LOGOUT -> stopForLogoutLocally()
            ACTION_SYNC_PENDING -> syncPendingCommands()
            ACTION_REFRESH_NOTIFICATION -> {
                val permission = currentPermission
                if (started && permission != null) updateNotification(statusFor(permission)) else stopSelf(startId)
            }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun syncPendingCommands() {
        val installed = runCatching { TrackingRuntimeRegistry.provider?.create(this) }.getOrNull()
        if (installed == null) {
            stopSelf()
            return
        }
        runtime = installed
        engine = installed.engine
        sync = TrackingSyncCoordinator(installed.queue, installed.transport, onSharingEpoch = installed.onSharingEpoch)
        val scope = runCatching { installed.scopeProvider() }.getOrNull()
        val permission = AndroidPermissionProbe.snapshot(this)
        val consent = runCatching { installed.sharingConsentEnabled() }.getOrDefault(false)
        serviceScope.launch {
            sync?.flush(
                scope = scope,
                hasLocationPermission = permission.canCollect,
                consentEnabled = consent,
                privateAuthorityStillValid = {
                    val live = runCatching { installed.scopeProvider() }.getOrNull()
                    val currentPermission = AndroidPermissionProbe.snapshot(this@LocationForegroundService)
                    scope != null && live?.sameCaptureScope(scope) == true && currentPermission.canCollect &&
                        runCatching { installed.sharingConsentEnabled() && installed.sharingEnabled() }.getOrDefault(false)
                },
                stopAuthorityStillValid = {
                    val live = runCatching { installed.scopeProvider() }.getOrNull()
                    scope != null && live?.userId == scope.userId
                },
            )
            stopSelf()
        }
    }

    private fun stopForLogoutLocally() {
        val installed = runtime ?: runCatching { TrackingRuntimeRegistry.provider?.create(this) }.getOrNull()
        stopLocationCollection()
        installed?.onGlobalStop?.invoke()
        installed?.queue?.invalidatePrivateRecords()
        waitingForStopSync = false
        pendingStopScope = null
        finishService()
    }

    private fun beginTracking() {
        if (started) return
        val permission = AndroidPermissionProbe.snapshot(this)
        if (!permission.canCollect) {
            stopSelf()
            return
        }
        startAsForeground(TrackingStatus.STARTING)
        val installed = runCatching { TrackingRuntimeRegistry.provider?.create(this) }.getOrNull()
        if (installed == null) {
            stopAndRemoveNotification()
            return
        }
        runtime = installed
        sync = TrackingSyncCoordinator(installed.queue, installed.transport, onSharingEpoch = installed.onSharingEpoch)
        val rawScope = runCatching { installed.scopeProvider() }.getOrNull()
        val scope = effectiveScope(rawScope, permission)
        if (rawScope != null && sync?.hasPendingGlobalStop(rawScope.userId) == true) {
            waitingForStopSync = true
            pendingStopScope = rawScope
            startAsForeground(TrackingStatus.STOPPING)
            registerNetworkCallback()
            flushQueue()
            mainHandler.postDelayed(stopRetry, STOP_RETRY_INTERVAL_MS)
            return
        }
        if (!permission.canCollect || !installed.sharingConsentEnabled() || !installed.sharingEnabled() || scope == null ||
            !engine.start(scope, permission, installed.sharingConsentEnabled(), explicitUserAction = true)
        ) {
            installed.queue.invalidatePrivateRecords()
            installed.onTrackingStatus(if (!permission.canCollect) TrackingStatus.PERMISSION_LOST else TrackingStatus.CONSENT_REVOKED)
            stopAndRemoveNotification()
            return
        }
        activeScope = scope
        currentPermission = permission
        started = true
        lastRequestedDriveMode = false
        installed.onTrackingStatus(statusFor(permission))
        startMotionSensor()
        requestLocationUpdates(permission)
        if (!started) return
        registerNetworkCallback()
        mainHandler.post(permissionWatch)
        flushQueue()
        updateNotification(statusFor(permission))
    }

    private fun refreshPermissionAndConsent() {
        val installed = runtime ?: return
        val permission = AndroidPermissionProbe.snapshot(this)
        val consentEnabled = runCatching { installed.sharingConsentEnabled() }.getOrDefault(false)
        val sharingEnabled = runCatching { installed.sharingEnabled() }.getOrDefault(false)
        if (!permission.canCollect) {
            invalidateAndStop(TrackingStatus.PERMISSION_LOST)
            return
        }
        if (!consentEnabled || !sharingEnabled) {
            invalidateAndStop(TrackingStatus.CONSENT_REVOKED)
            return
        }
        val latestScope = effectiveScope(runCatching { installed.scopeProvider() }.getOrNull(), permission)
        val previousScope = activeScope
        if (latestScope == null || previousScope == null || !sameAuthorityGeneration(previousScope, latestScope)) {
            // Logout, identity/session change, consent change, or a global stop requires a fresh start.
            invalidateAndStop(TrackingStatus.CONSENT_REVOKED)
            return
        }
        if (!previousScope.sameCaptureScope(latestScope)) {
            // A precision change discards earlier fixes before continuing with the new permission.
            installed.queue.invalidatePrivateRecords()
            removeLocationUpdates()
            engine.stop()
            activeScope = latestScope
            val restarted = engine.start(latestScope, permission, consentEnabled, explicitUserAction = true)
            if (!restarted) {
                invalidateAndStop(TrackingStatus.CONSENT_REVOKED)
                return
            }
            lastRequestedDriveMode = false
        }
        if (currentPermission?.precision != permission.precision || currentPermission?.batterySaverOn != permission.batterySaverOn) {
            removeLocationUpdates()
            requestLocationUpdates(permission)
        }
        currentPermission = permission
        installed.onTrackingStatus(statusFor(permission))
        updateNotification(statusFor(permission))
    }

    private fun sameAuthorityGeneration(first: TrackingScope, second: TrackingScope): Boolean =
        first.userId == second.userId && first.sessionId == second.sessionId &&
            first.consentGeneration == second.consentGeneration && first.sharingEpoch == second.sharingEpoch

    private fun effectiveScope(scope: TrackingScope?, permission: PermissionSnapshot): TrackingScope? =
        scope?.let { current -> permission.precision?.let { current.copy(precision = it) } }

    private fun onLocation(location: Location) {
        if (!started) return
        refreshPermissionAndConsent()
        if (!started) return
        val installed = runtime ?: return
        val scope = activeScope ?: return
        val permission = currentPermission ?: return
        val sample = LocationSample.create(
            capturedAt = Instant.ofEpochMilli(location.time.takeIf { it > 0L } ?: System.currentTimeMillis()),
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy.toDouble(),
            speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
        )
        val result = engine.onLocation(scope, permission, installed.sharingConsentEnabled(), sample, motionTracker.current(), Instant.now())
        if (result !is LocationProcessResult.Accepted) return
        if (arrivalScope?.sameCaptureScope(scope) != true) {
            arrivalDetector.reset()
            arrivalScope = scope
        }
        val driveUpdate = result.driveUpdate
        if (driveUpdate is DriveUpdate.Ended) {
            val observedAt = driveUpdate.trip.endedAt ?: sample.capturedAt
            runCatching { installed.activeDriveEndShareTargets(scope) }.getOrDefault(emptyList()).forEach { target ->
                if (sync?.hasPendingTerminalEvent(scope, target.shareId, ShareEventType.DRIVE_END) == true) {
                    installed.onDriveEndShareEventQueued(target.shareId)
                    return@forEach
                }
                val evidence = DriveEndEvidencePolicy.forLinkedTrip(
                    evidenceFixes = driveUpdate.evidenceFixes,
                    shareStartedAt = target.shareStartedAt,
                    tripStartedAt = target.tripStartedAt,
                    tripSessionMatchesCurrent = target.tripSessionMatchesCurrent,
                    currentSharingEpoch = scope.sharingEpoch,
                    shareSharingEpoch = target.sharingEpoch,
                )
                if (evidence.isEmpty()) return@forEach
                val event = runCatching {
                    PendingShareEvent(
                        eventType = ShareEventType.DRIVE_END,
                        observedAt = observedAt,
                        evidenceFixes = evidence,
                    )
                }.getOrNull() ?: return@forEach
                if (sync?.enqueueTerminalEvent(scope, target.shareId, event) == true) {
                    installed.onDriveEndShareEventQueued(target.shareId)
                }
            }
        }
        runCatching { installed.activeArrivalShareTargets(scope) }.getOrDefault(emptyList()).forEach { target ->
            if (sync?.hasPendingTerminalEvent(scope, target.shareId, ShareEventType.ARRIVAL) == true) {
                installed.onArrivalShareEventQueued(scope, target.shareId)
                return@forEach
            }
            val arrivalEvent = arrivalDetector.observe(target, sample, Instant.now()) ?: return@forEach
            if (sync?.enqueueTerminalEvent(scope, target.shareId, arrivalEvent) == true) {
                installed.onArrivalShareEventQueued(scope, target.shareId)
            }
        }
        driveUpdate?.let(installed.onDriveUpdate)
        // Keep this terminal fix and all later fixes behind the event. The sync coordinator first
        // uploads the event's evidence samples idempotently, then submits the event, so every
        // evidence ID is canonical before the server evaluates the terminal condition.
        val rejection = sync?.enqueueFix(scope, sample)
        if (rejection != null) return
        val drivingNow = engine.isDriveActive
        if (drivingNow != lastRequestedDriveMode) {
            lastRequestedDriveMode = drivingNow
            removeLocationUpdates()
            requestLocationUpdates(permission)
        }
        if (isNetworkAvailable()) flushQueue()
        updateNotification(statusFor(permission))
    }

    private fun stopLocallyAndPersistGlobalCommand() {
        val hadForegroundTracking = started || waitingForStopSync
        val installed = runtime ?: run {
            // Service may be invoked after process recreation; load just enough runtime to persist.
            runCatching { TrackingRuntimeRegistry.provider?.create(this) }.getOrNull()?.let {
                runtime = it
                engine = it.engine
                sync = TrackingSyncCoordinator(it.queue, it.transport, onSharingEpoch = it.onSharingEpoch)
            }
            runtime
        }
        val scope = activeScope ?: runCatching { installed?.scopeProvider() }.getOrNull()
        stopLocationCollection()
        installed?.onGlobalStop?.invoke()
        waitingForStopSync = false
        val persisted = scope?.let { sync?.stopAllLocally(it) } ?: false
        if (!persisted) {
            installed?.queue?.invalidatePrivateRecords()
            finishService()
            return
        }
        waitingForStopSync = true
        pendingStopScope = scope
        if (hadForegroundTracking) {
            startAsForeground(TrackingStatus.STOPPING)
            updateNotification(TrackingStatus.STOPPING)
            registerNetworkCallback()
            flushQueue(scopeOverride = scope)
            if (waitingForStopSync) mainHandler.postDelayed(stopRetry, STOP_RETRY_INTERVAL_MS)
        } else {
            flushQueue(scopeOverride = scope, stopAfterOneAttempt = true)
        }
    }

    private fun invalidateAndStop(status: TrackingStatus) {
        runtime?.queue?.invalidatePrivateRecords()
        runtime?.onTrackingStatus?.invoke(status)
        stopLocationCollection()
        finishService(finalStatus = status)
    }

    private fun stopLocationCollection() {
        started = false
        engine.stop()
        arrivalDetector.reset()
        arrivalScope = null
        removeLocationUpdates()
        stopMotionSensor()
        mainHandler.removeCallbacks(permissionWatch)
        activeScope = null
    }

    private fun flushQueue(scopeOverride: TrackingScope? = null, stopAfterOneAttempt: Boolean = false) {
        val installed = runtime ?: return
        val coordinator = sync ?: return
        val reportedScope = runCatching { installed.scopeProvider() }.getOrNull()
        val requestedStopScope = pendingStopScope ?: scopeOverride?.takeIf { coordinator.hasPendingGlobalStop(it.userId) }
        if (requestedStopScope != null &&
            (reportedScope == null || reportedScope.userId != requestedStopScope.userId)
        ) {
            // A persisted revoke is never sent with another account's credentials.
            return
        }
        val scope = scopeOverride ?: pendingStopScope ?: activeScope ?: reportedScope
        val permission = AndroidPermissionProbe.snapshot(this)
        val consent = runCatching { installed.sharingConsentEnabled() }.getOrDefault(false)
        serviceScope.launch {
            val summary = coordinator.flush(
                scope = scope,
                hasLocationPermission = permission.canCollect,
                consentEnabled = consent,
                privateAuthorityStillValid = {
                    val liveScope = runCatching { installed.scopeProvider() }.getOrNull()
                    val livePermission = AndroidPermissionProbe.snapshot(this@LocationForegroundService)
                    val liveConsent = runCatching { installed.sharingConsentEnabled() && installed.sharingEnabled() }.getOrDefault(false)
                    scope != null && liveScope?.sameCaptureScope(scope) == true && livePermission.canCollect &&
                        livePermission.precision == scope.precision && liveConsent
                },
                stopAuthorityStillValid = {
                    val liveScope = runCatching { installed.scopeProvider() }.getOrNull()
                    requestedStopScope != null && liveScope?.userId == requestedStopScope.userId
                },
            )
            if (summary.stopReason == FlushStopReason.SHARING_EPOCH_MISMATCH && started) {
                mainHandler.post { invalidateAndStop(TrackingStatus.CONSENT_REVOKED) }
                return@launch
            }
            if (waitingForStopSync && installed.queue.pendingStopIds().none()) {
                mainHandler.post { pendingStopScope = null; finishService() }
            }
            if (stopAfterOneAttempt) mainHandler.post {
                pendingStopScope = null
                installed.onTrackingStatus(TrackingStatus.STOPPED)
                stopSelf()
            }
        }
    }

    private fun requestLocationUpdates(permission: PermissionSnapshot) {
        val manager = locationManager ?: return
        val hasFine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val candidates = if (hasFine) listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            else listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        val provider = candidates.firstOrNull { candidate -> runCatching { manager.isProviderEnabled(candidate) }.getOrDefault(false) }
        if (provider == null) {
            runtime?.onTrackingStatus?.invoke(TrackingStatus.LOCATION_UNAVAILABLE)
            updateNotification(TrackingStatus.LOCATION_UNAVAILABLE)
            return
        }
        val driving = engine.isDriveActive
        val interval = when {
            permission.batterySaverOn -> BATTERY_SAVER_INTERVAL_MS
            permission.precision == LocationPrecision.APPROXIMATE -> APPROXIMATE_INTERVAL_MS
            driving -> DRIVE_INTERVAL_MS
            else -> PARKED_INTERVAL_MS
        }
        val minDistance = if (permission.precision == LocationPrecision.APPROXIMATE) APPROXIMATE_MIN_DISTANCE_METERS else PRECISE_MIN_DISTANCE_METERS
        try {
            manager.requestLocationUpdates(provider, interval, minDistance, this, Looper.getMainLooper())
        } catch (_: SecurityException) {
            invalidateAndStop(TrackingStatus.PERMISSION_LOST)
        } catch (_: IllegalArgumentException) {
            runtime?.onTrackingStatus?.invoke(TrackingStatus.LOCATION_UNAVAILABLE)
        }
    }

    private fun removeLocationUpdates() {
        runCatching { locationManager?.removeUpdates(this) }
    }

    private fun startMotionSensor() {
        val manager = sensorManager ?: return
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        runCatching { manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, mainHandler) }
    }

    private fun stopMotionSensor() {
        runCatching { sensorManager?.unregisterListener(this) }
        motionTracker.reset()
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val manager = connectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { flushQueue() }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) flushQueue()
            }
        }
        networkCallback = callback
        runCatching { manager.registerDefaultNetworkCallback(callback) }.onFailure { networkCallback = null }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
        networkCallback = null
    }

    private fun isNetworkAvailable(): Boolean {
        val manager = connectivityManager ?: return false
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun statusFor(permission: PermissionSnapshot): TrackingStatus = when {
        permission.batterySaverOn -> TrackingStatus.BATTERY_SAVER_LIMITED
        permission.precision == LocationPrecision.APPROXIMATE && !permission.hasBackgroundLocation -> TrackingStatus.TRACKING_APPROXIMATE_BACKGROUND_LIMITED
        !permission.hasBackgroundLocation -> TrackingStatus.BACKGROUND_ACCESS_LIMITED
        permission.precision == LocationPrecision.APPROXIMATE -> TrackingStatus.TRACKING_APPROXIMATE
        else -> TrackingStatus.TRACKING_PRECISE
    }

    private fun startAsForeground(status: TrackingStatus) {
        val notification = buildNotification(status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(status: TrackingStatus) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun buildNotification(status: TrackingStatus): Notification {
        val statusText = when (status) {
            TrackingStatus.STARTING -> "Preparing location sharing"
            TrackingStatus.TRACKING_PRECISE -> "Precise location is being shared"
            TrackingStatus.TRACKING_APPROXIMATE -> "Approximate location is being shared"
            TrackingStatus.TRACKING_APPROXIMATE_BACKGROUND_LIMITED -> "Approximate sharing; background updates may pause"
            TrackingStatus.BATTERY_SAVER_LIMITED -> "Battery saver may reduce location updates"
            TrackingStatus.BACKGROUND_ACCESS_LIMITED -> "Background access is limited; updates may pause"
            TrackingStatus.LOCATION_UNAVAILABLE -> "Location is unavailable; check device settings"
            TrackingStatus.PERMISSION_LOST -> "Location permission was removed; tracking stopped"
            TrackingStatus.CONSENT_REVOKED -> "Sharing consent changed; tracking stopped"
            TrackingStatus.STOPPING -> "Location stopped; syncing the stop request"
           TrackingStatus.STOPPED -> "Location sharing is stopped"
        }
        val stopIntent = PendingIntent.getService(
            this, STOP_PENDING_INTENT_REQUEST,
            Intent(this, LocationForegroundService::class.java).setAction(ACTION_GLOBAL_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val trip = TripProgressNotificationFormatter.format(runCatching { runtime?.tripProgressNotification?.invoke() }.getOrNull())
        val publicVersion = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(trip?.publicTitle ?: "OpenTrack360 location sharing")
            .setContentText(trip?.publicBody ?: statusText)
            .setCategory(if (trip != null) NotificationCompat.CATEGORY_NAVIGATION else NotificationCompat.CATEGORY_SERVICE)
            .build()
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(trip?.title ?: "OpenTrack360 location sharing")
            .setContentText(trip?.body ?: statusText)
            .setSubText(if (trip != null) statusText else null)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setStyle(NotificationCompat.BigTextStyle().bigText(trip?.body ?: statusText))
            .setCategory(if (trip != null) NotificationCompat.CATEGORY_NAVIGATION else NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop sharing", stopIntent)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.createNotificationChannel(
            NotificationChannel(NOTIFICATION_CHANNEL_ID, "Location sharing", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows when OpenTrack360 is sharing this phone's location"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
    }

    private fun stopAndRemoveNotification() {
        stopLocationCollection()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun finishService(finalStatus: TrackingStatus = TrackingStatus.STOPPED) {
        waitingForStopSync = false
        mainHandler.removeCallbacks(stopRetry)
        unregisterNetworkCallback()
        stopLocationCollection()
        stopForeground(STOP_FOREGROUND_REMOVE)
        runtime?.onTrackingStatus?.invoke(finalStatus)
        stopSelf()
    }

    override fun onLocationChanged(location: Location) = onLocation(location)
    override fun onLocationChanged(locations: MutableList<Location>) { locations.forEach(::onLocation) }
    override fun onProviderDisabled(provider: String) {
        runtime?.onTrackingStatus?.invoke(TrackingStatus.LOCATION_UNAVAILABLE)
        updateNotification(TrackingStatus.LOCATION_UNAVAILABLE)
    }
    override fun onProviderEnabled(provider: String) {
        currentPermission?.let(::requestLocationUpdates)
    }
    @Deprecated("Deprecated by Android API; kept for platform callback compatibility")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER || event.values.size < 3) return
        motionTracker.addAcceleration(event.values[0], event.values[1], event.values[2])
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        unregisterNetworkCallback()
        stopLocationCollection()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "me.srishanth.opentrack360.tracking.START"
        const val ACTION_GLOBAL_STOP = "me.srishanth.opentrack360.tracking.GLOBAL_STOP"
        const val ACTION_LOGOUT = "me.srishanth.opentrack360.tracking.LOGOUT"
        const val ACTION_SYNC_PENDING = "me.srishanth.opentrack360.tracking.SYNC_PENDING"
        const val ACTION_REFRESH_NOTIFICATION = "me.srishanth.opentrack360.tracking.REFRESH_NOTIFICATION"
        const val EXTRA_USER_INITIATED = "user_initiated"
        private const val NOTIFICATION_ID = 3601
        private const val STOP_PENDING_INTENT_REQUEST = 3602
        private const val NOTIFICATION_CHANNEL_ID = "location_sharing"
        private const val DRIVE_INTERVAL_MS = 15_000L
        private const val PARKED_INTERVAL_MS = 45_000L
        private const val APPROXIMATE_INTERVAL_MS = 45_000L
        private const val BATTERY_SAVER_INTERVAL_MS = 60_000L
        private const val PRECISE_MIN_DISTANCE_METERS = 10f
        private const val APPROXIMATE_MIN_DISTANCE_METERS = 25f
        private const val PERMISSION_WATCH_INTERVAL_MS = 15_000L
        private const val STOP_RETRY_INTERVAL_MS = 30_000L
    }
}

