package me.srishanth.opentrack360

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.srishanth.opentrack360.core.OpenTrackRuntime
import me.srishanth.opentrack360.core.api.ApiException
import me.srishanth.opentrack360.core.api.MemberProfile
import me.srishanth.opentrack360.core.auth.MemberSession
import me.srishanth.opentrack360.sharing.GuestShareRequest
import me.srishanth.opentrack360.sharing.GuestShareScreen
import me.srishanth.opentrack360.sharing.CircleInviteLinkParser
import me.srishanth.opentrack360.sharing.CircleInviteRequest
import me.srishanth.opentrack360.sharing.PetRecoveryRequest
import me.srishanth.opentrack360.sharing.PetRecoveryLinkParser
import me.srishanth.opentrack360.sharing.ShareManagementScreen
import me.srishanth.opentrack360.tracking.AndroidPermissionProbe
import me.srishanth.opentrack360.tracking.LocationPrecision
import me.srishanth.opentrack360.tracking.TrackingServiceController
import me.srishanth.opentrack360.tracking.TrackingStartResult
import me.srishanth.opentrack360.tracking.TrackingStatus
import me.srishanth.opentrack360.ui.accounts.AccountRecoveryHelpScreen
import me.srishanth.opentrack360.ui.accounts.PasswordChangeSettingsScreen
import me.srishanth.opentrack360.ui.accounts.PasswordFormUiState
import me.srishanth.opentrack360.ui.accounts.SignInScreen
import me.srishanth.opentrack360.ui.accounts.SignInUiState
import me.srishanth.opentrack360.ui.accounts.TemporaryPasswordChangeScreen
import me.srishanth.opentrack360.ui.circles.CircleHomeScreen
import me.srishanth.opentrack360.ui.circles.CircleHomeUiState
import me.srishanth.opentrack360.ui.circles.CreateCircleScreen
import me.srishanth.opentrack360.ui.circles.CircleManagementScreen
import me.srishanth.opentrack360.ui.circles.CircleManagementUiState
import me.srishanth.opentrack360.ui.coordination.ActivitySharingScreen
import me.srishanth.opentrack360.ui.coordination.ActivitySharingUiState
import me.srishanth.opentrack360.ui.coordination.BubbleScreen
import me.srishanth.opentrack360.ui.coordination.BubbleUi
import me.srishanth.opentrack360.ui.coordination.CheckInScreen
import me.srishanth.opentrack360.ui.coordination.CheckInUiState
import me.srishanth.opentrack360.ui.coordination.CircleEventUi
import me.srishanth.opentrack360.ui.coordination.DrivingReportUi
import me.srishanth.opentrack360.ui.coordination.DrivingReportsScreen
import me.srishanth.opentrack360.ui.coordination.EventsScreen
import me.srishanth.opentrack360.ui.coordination.HistoryScreen
import me.srishanth.opentrack360.ui.coordination.HistoryStopUi
import me.srishanth.opentrack360.ui.coordination.HistoryUiState
import me.srishanth.opentrack360.ui.coordination.InboxItemUi
import me.srishanth.opentrack360.ui.coordination.MorningCheckInScreen
import me.srishanth.opentrack360.ui.coordination.MorningCheckInUi
import me.srishanth.opentrack360.ui.coordination.NotificationInboxScreen
import me.srishanth.opentrack360.ui.coordination.PlaceDetailUiState
import me.srishanth.opentrack360.ui.coordination.PlaceEditorScreen
import me.srishanth.opentrack360.ui.coordination.PlaceFormUiState
import me.srishanth.opentrack360.ui.coordination.PlaceMapDetailScreen
import me.srishanth.opentrack360.ui.coordination.PlaceRuleEditorScreen
import me.srishanth.opentrack360.ui.coordination.PlaceRuleFormUiState
import me.srishanth.opentrack360.ui.coordination.PlaceRuleRecurrenceUi
import me.srishanth.opentrack360.ui.coordination.PlacesScreen
import me.srishanth.opentrack360.ui.coordination.PlacesUiState
import me.srishanth.opentrack360.ui.coordination.QuickNoteUi
import me.srishanth.opentrack360.ui.coordination.QuickNotesScreen
import me.srishanth.opentrack360.ui.coordination.TripProgressScreen
import me.srishanth.opentrack360.ui.coordination.TripProgressUiState
import me.srishanth.opentrack360.ui.components.EmptyStateCard
import me.srishanth.opentrack360.ui.components.MemberCard
import me.srishanth.opentrack360.ui.components.MemberScreen
import me.srishanth.opentrack360.ui.components.PrimaryAction
import me.srishanth.opentrack360.ui.components.StatusBanner
import me.srishanth.opentrack360.ui.map.GeoPoint
import me.srishanth.opentrack360.ui.map.MapMarker
import me.srishanth.opentrack360.ui.map.OpenTrackMapSurface
import me.srishanth.opentrack360.ui.model.CircleMemberUi
import me.srishanth.opentrack360.ui.model.CircleChoiceUi
import me.srishanth.opentrack360.ui.model.DataCondition
import me.srishanth.opentrack360.ui.model.MemberAccessState
import me.srishanth.opentrack360.ui.model.MemberLocationUi
import me.srishanth.opentrack360.ui.model.MemberAccessPolicy
import me.srishanth.opentrack360.ui.model.AudienceChoiceUi
import me.srishanth.opentrack360.ui.model.NoShowRuleUi
import me.srishanth.opentrack360.ui.model.PetRoutineUi
import me.srishanth.opentrack360.ui.model.PetUi
import me.srishanth.opentrack360.ui.model.PlaceArrivalUi
import me.srishanth.opentrack360.ui.model.PlaceUi
import me.srishanth.opentrack360.ui.pets.LostPetReportScreen
import me.srishanth.opentrack360.ui.pets.PetActivityUi
import me.srishanth.opentrack360.ui.pets.PetDetailScreen
import me.srishanth.opentrack360.ui.pets.PetDetailUiState
import me.srishanth.opentrack360.ui.pets.PetProfilesScreen
import me.srishanth.opentrack360.ui.pets.PublicPetRecoveryScreen
import me.srishanth.opentrack360.ui.pets.PublicPetRecoveryUiState
import me.srishanth.opentrack360.ui.safety.AccountPrivacyScreen
import me.srishanth.opentrack360.ui.safety.AccountPrivacyUiState
import me.srishanth.opentrack360.ui.safety.PermissionDiagnosticsScreen
import me.srishanth.opentrack360.ui.safety.SafetyAvailabilityScreen
import me.srishanth.opentrack360.ui.safety.SosRecipientUi
import me.srishanth.opentrack360.ui.safety.SosScreen
import me.srishanth.opentrack360.ui.safety.SosUiState
import me.srishanth.opentrack360.ui.model.PermissionDiagnosticUi
import org.json.JSONArray
import org.json.JSONObject
import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.URLEncoder
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class AppPage(val label: String) {
    CIRCLES("Circles"), SHARES("Share trip"), TOOLS("Tools"), ACCOUNT("Account"),
    CREATE_CIRCLE("Create Circle"), RECOVERY("Recovery help"), CHANGE_PASSWORD("Change password"),
    CIRCLE_MANAGEMENT("Manage Circle"), PLACES("Places"), PLACE_EDITOR("Save Place"), PLACE_DETAIL("Place details"), PLACE_RULE("Place alerts"),
    HISTORY("History"), COORDINATION("Coordination"), BUBBLES("Bubbles"), CHECK_INS("Check in"), QUICK_NOTES("Quick Notes"), EVENTS("Events"), INBOX("Inbox"),
    TRIPS("Trip progress"), MORNING("Morning check-in"), ACTIVITY("Activity sharing"), DRIVING("Driving reports"),
    PETS("Pets"), PET_DETAIL("Pet details"), LOST_PET("Lost pet"), SOS("Circle SOS"), SAFETY("Safety"), PERMISSIONS("Permissions"),
}

@Composable
fun OpenTrackAppRoot(
    activity: ComponentActivity,
    runtime: OpenTrackRuntime,
    guestRequest: GuestShareRequest?,
    petRecoveryRequest: PetRecoveryRequest?,
    circleInviteRequest: CircleInviteRequest?,
    onCloseGuest: () -> Unit,
    onClosePetRecovery: () -> Unit,
    onCloseCircleInvite: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val trackingStatus by runtime.trackingStatus.collectAsState()
    var session by remember { mutableStateOf<MemberSession?>(null) }
    var profile by remember { mutableStateOf<MemberProfile?>(null) }
    var access by remember { mutableStateOf<MemberAccessState>(MemberAccessState.SignedOut) }
    var page by remember { mutableStateOf(AppPage.CIRCLES) }
    var booting by remember { mutableStateOf(true) }
    var signIn by remember { mutableStateOf(SignInUiState()) }
    var inviteAcceptBusy by remember { mutableStateOf(false) }
    var inviteAcceptError by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf(PasswordFormUiState()) }
    var accountMessage by remember { mutableStateOf<String?>(null) }
    var circleState by remember { mutableStateOf(CircleHomeUiState()) }
    var selectedCircleId by remember { mutableStateOf<String?>(null) }
    var reloadHome by remember { mutableStateOf(0) }
    var circleName by remember { mutableStateOf("") }
    var circleKind by remember { mutableStateOf("standard") }
    var temporaryCircleExpiry by remember { mutableStateOf("") }
    var createCircleError by remember { mutableStateOf<String?>(null) }
    var createCircleBusy by remember { mutableStateOf(false) }
    var pendingStartAfterConsent by remember { mutableStateOf(false) }
    var permissionSummary by remember { mutableStateOf<String?>(null) }
    var circleManagementState by remember { mutableStateOf<CircleManagementUiState?>(null) }
    var selectedPlaceId by remember { mutableStateOf<String?>(null) }
    var selectedRuleId by remember { mutableStateOf<String?>(null) }
    var editingPlace by remember { mutableStateOf(false) }
    var placeRuleObjects by remember { mutableStateOf<Map<String, JSONObject>>(emptyMap()) }
    var placesState by remember { mutableStateOf(PlacesUiState()) }
    var bubbles by remember { mutableStateOf<List<BubbleUi>>(emptyList()) }
    var bubbleLatitude by remember { mutableStateOf("") }
    var bubbleLongitude by remember { mutableStateOf("") }
    var bubbleRadius by remember { mutableStateOf("300") }
    var bubbleDuration by remember { mutableStateOf("60") }
    var selectedPlace by remember { mutableStateOf<PlaceUi?>(null) }
    var placeRules by remember { mutableStateOf<List<NoShowRuleUi>>(emptyList()) }
    var placeForm by remember { mutableStateOf(PlaceFormUiState()) }
    var placeRuleForm by remember { mutableStateOf(PlaceRuleFormUiState()) }
    var historyState by remember { mutableStateOf(HistoryUiState()) }
    var historyPoints by remember { mutableStateOf(emptyList<MapMarker>()) }
    var historyPlaybackIndex by remember { mutableStateOf(0) }
    var historyFrom by remember { mutableStateOf(Instant.now().minusSeconds(86_400).toString()) }
    var historyTo by remember { mutableStateOf(Instant.now().toString()) }
    var noteItems by remember { mutableStateOf<List<QuickNoteUi>>(emptyList()) }
    var noteDraft by remember { mutableStateOf("") }
    var eventItems by remember { mutableStateOf<List<CircleEventUi>>(emptyList()) }
    var eventTitle by remember { mutableStateOf("") }
    var eventStartsAt by remember { mutableStateOf("") }
    var eventDetails by remember { mutableStateOf("") }
    var selectedEventInvitees by remember { mutableStateOf<Set<String>>(emptySet()) }
    var inboxItems by remember { mutableStateOf<List<InboxItemUi>>(emptyList()) }
    var tripState by remember { mutableStateOf(TripProgressUiState()) }
    var tripDestination by remember { mutableStateOf("") }
    var tripLatitude by remember { mutableStateOf("") }
    var tripLongitude by remember { mutableStateOf("") }
    var tripRecipients by remember { mutableStateOf<Set<String>>(emptySet()) }
    var tripLockScreen by remember { mutableStateOf(false) }
    var morningItems by remember { mutableStateOf<List<MorningCheckInUi>>(emptyList()) }
    var morningGreeting by remember { mutableStateOf("") }
    var selectedCheckinRecipients by remember { mutableStateOf<Set<String>>(emptySet()) }
    var checkinNote by remember { mutableStateOf("") }
    var activitySharingEnabled by remember { mutableStateOf(false) }
    var drivingReports by remember { mutableStateOf<List<DrivingReportUi>>(emptyList()) }
    var pets by remember { mutableStateOf<List<PetUi>>(emptyList()) }
    var selectedPetId by remember { mutableStateOf<String?>(null) }
    var activeLostPetReportIds by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var petDetail by remember { mutableStateOf<PetDetailUiState?>(null) }
    var petRecoveryUrl by remember { mutableStateOf<String?>(null) }
    var petName by remember { mutableStateOf("") }
    var petSpecies by remember { mutableStateOf("") }
    var petCircles by remember { mutableStateOf<Set<String>>(emptySet()) }
    var routineTitle by remember { mutableStateOf("") }
    var routineSchedule by remember { mutableStateOf("") }
    var routineKind by remember { mutableStateOf("other") }
    var routineDueAt by remember { mutableStateOf("") }
    var lostPetExpiresAt by remember { mutableStateOf(Instant.now().plusSeconds(86_400).toString()) }
    var lostPetMessage by remember { mutableStateOf("") }
    var lostPetCircles by remember { mutableStateOf<Set<String>>(emptySet()) }
    var sosState by remember { mutableStateOf(SosUiState()) }
    var sosCountdownGeneration by remember { mutableStateOf(0) }
    var showEmergencyInviteDialog by remember { mutableStateOf(false) }
    var emergencyContactUsername by remember { mutableStateOf("") }
    var publicPetState by remember { mutableStateOf(PublicPetRecoveryUiState()) }
    var petSightingMessage by remember { mutableStateOf("") }
    var petSightingLatitude by remember { mutableStateOf("") }
    var petSightingLongitude by remember { mutableStateOf("") }
    var petSightingAccuracy by remember { mutableStateOf("") }
    var petSightingContactRoute by remember { mutableStateOf("") }

    val petPhotoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val petId = selectedPetId ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val (contentType, photoBytes) = withContext(Dispatchers.IO) {
                    val type = activity.contentResolver.getType(uri)?.lowercase(Locale.ROOT)
                    require(type in PET_PHOTO_CONTENT_TYPES) { "Choose a JPEG, PNG, or WebP image." }
                    val bytes = activity.contentResolver.openInputStream(uri)?.use { it.readNBytes(MAX_PET_PHOTO_BYTES + 1) }
                        ?: throw IllegalArgumentException("The selected image could not be read.")
                    require(bytes.isNotEmpty()) { "The selected image is empty." }
                    require(bytes.size <= MAX_PET_PHOTO_BYTES) { "Choose an image smaller than 5 MiB." }
                    type!! to bytes
                }
                val petPath = "pets/${petId.uuidSegment()}"
                val uploadResponse = runtime.api.memberPost(
                    "$petPath/photo",
                    JSONObject().put("contentType", contentType).put("sizeBytes", photoBytes.size),
                )
                val upload = uploadResponse.optJSONObject("data") ?: uploadResponse
                runtime.api.putSignedUpload(upload.getString("uploadUrl"), contentType, photoBytes)
                runtime.api.memberPatch(petPath, JSONObject().put("photoObjectPath", upload.getString("objectPath")))
            }.onSuccess {
                accountMessage = "Pet photo uploaded and saved privately."
                reloadHome++
            }.onFailure { error ->
                accountMessage = error.message ?: "Pet photo could not be uploaded."
            }
        }
    }

    fun authenticateAndLoad(member: MemberSession) {
        session = member
        runtime.startMemberSession(member.memberId)
        if (member.mustRotatePassword) {
            access = MemberAccessState.RotationRequired(member.username)
            page = AppPage.CIRCLES
            return
        }
        scope.launch {
            try {
                val me = runtime.api.loadMemberProfile()
                profile = me
                runtime.syncSharingEpoch(member.memberId, me.sharingEpoch)
                access = MemberAccessPolicy.afterFreshLogin(member.memberId, member.username, me.mustRotatePassword)
                TrackingServiceController.syncPendingCommandsFromUserAction(activity)
                reloadHome++
            } catch (error: Exception) {
                access = MemberAccessPolicy.afterFreshLogin(member.memberId, member.username, member.mustRotatePassword)
                accountMessage = error.message ?: "Could not refresh account details."
                reloadHome++
            }
        }
    }

    val launchLocationPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        val current = session
        pendingStartAfterConsent = false
        if (current != null) {
            val hasLocation = activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (hasLocation) startLocationService(activity, runtime, current.memberId)
            else {
                runtime.setSharingConsent(current.memberId, false)
                runtime.setSharingEnabled(current.memberId, false)
                permissionSummary = "Location permission was not granted. No location is being collected. You can change the permission in Android settings."
            }
        }
    }

    fun startFromConsent() {
        val current = session ?: return
        runtime.setSharingConsent(current.memberId, true)
        val missing = buildList {
            if (activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 29 && activity.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != android.content.pm.PackageManager.PERMISSION_GRANTED) add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isNotEmpty()) {
            pendingStartAfterConsent = true
            launchLocationPermissions.launch(missing.toTypedArray())
        } else startLocationService(activity, runtime, current.memberId)
    }

    fun createCircleInvite(circleId: String) {
        val validCircleId = runCatching { java.util.UUID.fromString(circleId).toString() }.getOrNull()
        if (validCircleId == null) {
            circleManagementState = circleManagementState?.copy(errorMessage = "The selected Circle could not be verified.")
            return
        }
        scope.launch {
            circleManagementState = circleManagementState?.copy(inviteStatus = "Creating a single-use invitation…", errorMessage = null)
            runCatching {
                val response = runtime.api.memberPost(
                    "circles/$validCircleId/invites",
                    JSONObject().put("clientRequestId", java.util.UUID.randomUUID().toString()),
                )
                val invite = response.optJSONObject("data") ?: response
                val url = invite.optString("url")
                require(CircleInviteLinkParser.parse(url) != null) { "The API returned an invalid invitation link." }
                invite.optNullableString("expiresAt")?.let(::formatLocalTime) to url
            }.onSuccess { (expiresAt, url) ->
                circleManagementState = circleManagementState?.copy(
                    inviteStatus = "Invitation ready. It expires ${expiresAt ?: "within 72 hours"}; anyone with the link can join once.",
                    errorMessage = null,
                )
                shareText(activity, url)
            }.onFailure { error ->
                circleManagementState = circleManagementState?.copy(
                    inviteStatus = null,
                    errorMessage = error.message ?: "Invitation link could not be created.",
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        val saved = runtime.sessions.load()
        session = saved
        if (saved == null) {
            access = MemberAccessState.SignedOut
        } else {
            runtime.ensureMemberSession(saved.memberId)
            if (saved.mustRotatePassword) {
                access = MemberAccessState.RotationRequired(saved.username)
                password = PasswordFormUiState()
            } else {
                access = MemberAccessState.Authenticated(saved.memberId, saved.username)
                runCatching { runtime.api.loadMemberProfile() }.onSuccess { me ->
                    profile = me
                    runtime.syncSharingEpoch(saved.memberId, me.sharingEpoch)
                }
                TrackingServiceController.syncPendingCommandsFromUserAction(activity)
                reloadHome++
            }
        }
        booting = false
    }

    LaunchedEffect(reloadHome, session?.memberId) {
        val current = session ?: return@LaunchedEffect
        if (!MemberAccessPolicy.canNavigateToMemberData(access)) return@LaunchedEffect
        try {
            val circles = runtime.api.memberGet("circles").optJSONArray("data") ?: JSONArray()
            val choices = buildList {
                for (index in 0 until circles.length()) {
                    val item = circles.optJSONObject(index) ?: continue
                    add(
                        CircleChoiceUi(
                            id = item.optString("id"),
                            name = item.optString("name", "Circle"),
                            kindLabel = item.optString("kind", "standard").replaceFirstChar(Char::uppercase),
                            roleLabel = item.optString("role", "member").replaceFirstChar(Char::uppercase),
                            expiresAtLabel = item.optNullableString("expiresAt")?.let(::formatLocalTime),
                        ),
                    )
                }
            }
            val activeId = selectedCircleId?.takeIf { id -> choices.any { it.id == id } } ?: choices.firstOrNull()?.id
            selectedCircleId = activeId
            val selected = choices.firstOrNull { it.id == activeId }
            val members = mutableListOf<CircleMemberUi>()
            var sharingPaused = false
            if (activeId != null) {
                val map = runtime.api.memberGet("circles/${activeId.uuidSegment()}/map")
                val rows = map.optJSONArray("data") ?: JSONArray()
                for (index in 0 until rows.length()) {
                    val item = rows.optJSONObject(index) ?: continue
                    val stateName = item.optString("sharingState", "hidden")
                    val location = item.optJSONObject("location")?.let { loc ->
                        MemberLocationUi(
                            latitude = loc.optDouble("latitude"),
                            longitude = loc.optDouble("longitude"),
                            accuracyMeters = loc.optDouble("accuracyMeters"),
                            capturedAtLabel = loc.optString("capturedAt").takeIf(String::isNotBlank)?.let(::formatLocalTime) ?: "Unknown",
                            precisionLabel = loc.optString("precision", "unknown").replace('_', ' '),
                            capturedAtIso = loc.optString("capturedAt"),
                        )
                    }
                    val memberId = item.optString("memberId")
                    if (memberId == current.memberId) sharingPaused = stateName == "paused"
                    members += CircleMemberUi(
                        id = memberId,
                        displayName = item.optString("displayName", "Circle member"),
                        roleLabel = if (memberId == current.memberId) selected?.roleLabel ?: "Member" else "Member",
                        sharingLabel = stateName.replace('_', ' '),
                        location = location,
                        batteryPercent = item.optNullableInt("batteryPercent"),
                        activityLabel = item.optNullableString("activity")?.replace('_', ' '),
                    )
                }
            }
            val activeTracking = runtime.sharingIsEnabled(current.memberId) && trackingStatus.isActiveTrackingStatus()
            circleState = CircleHomeUiState(
                circles = choices,
                selectedCircleId = activeId,
                selectedCircleName = selected?.name,
                members = members,
                mapCondition = if (activeId == null) DataCondition.UNKNOWN else DataCondition.CONFIRMED,
                mapMessage = when {
                    activeId == null -> "Create a Circle or accept an invitation to see a private map."
                    members.isEmpty() -> "No consenting member location is available. Missing data does not confirm an arrival."
                    else -> "Updates are available from consenting members."
                },
                sharingPaused = sharingPaused,
                sharingActive = activeTracking,
                canManage = selected?.roleLabel?.equals("Admin", ignoreCase = true) == true,
            )
        } catch (error: Exception) {
            circleState = circleState.copy(mapCondition = DataCondition.ERROR, mapMessage = error.message ?: "Circle data could not be loaded.")
        }
    }

    LaunchedEffect(page, reloadHome, selectedCircleId, selectedPlaceId, selectedPetId) {
        val circleId = selectedCircleId
        val circlePath = circleId?.let { "circles/${it.uuidSegment()}" }
        suspend fun list(path: String) = runtime.api.memberGet(path).optJSONArray("data") ?: JSONArray()
        suspend fun loadCircleMembers(): List<CircleMemberUi> = buildList {
            val rows = list("$circlePath/members")
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                add(
                    CircleMemberUi(
                        id = row.optString("memberId", row.optString("id")),
                        displayName = row.optString("displayName", row.optString("username", "Circle member")),
                        roleLabel = row.optString("role", "member").replaceFirstChar(Char::uppercase),
                        sharingLabel = row.optString("sharingState", "unknown").replace('_', ' '),
                        precision = row.optString("precision", "unknown"),
                    ),
                )
            }
        }
        fun unknownPlaceArrival() = PlaceArrivalUi(DataCondition.UNKNOWN, "No current data confirms an arrival.")
        try {
            when (page) {
                AppPage.CIRCLE_MANAGEMENT -> {
                    val base = circlePath ?: return@LaunchedEffect
                    val circle = circleState.circles.firstOrNull { it.id == circleId }
                    circleManagementState = CircleManagementUiState(
                        circleId = circleId,
                        circleName = circle?.name ?: "Circle",
                        roleLabel = circle?.roleLabel ?: "Member",
                        canManage = circle?.roleLabel?.equals("Admin", true) == true,
                        currentMemberId = session?.memberId.orEmpty(),
                        members = loadCircleMembers(),
                    )
                }
                AppPage.PLACES -> {
                    val base = circlePath ?: return@LaunchedEffect
                    val rows = list("$base/places")
                    val loaded = buildList {
                        for (index in 0 until rows.length()) {
                            val row = rows.optJSONObject(index) ?: continue
                            add(
                                PlaceUi(
                                    id = row.getString("id"),
                                    name = row.optString("name", "Place"),
                                    latitude = row.optDouble("latitude"),
                                    longitude = row.optDouble("longitude"),
                                    radiusMeters = row.optInt("radiusMeters", 150),
                                    arrival = unknownPlaceArrival(),
                                ),
                            )
                        }
                    }
                    placesState = PlacesUiState(loaded, DataCondition.CONFIRMED, "Saved Places are private to this Circle.")
                }
                AppPage.BUBBLES -> {
                    val base = circlePath ?: return@LaunchedEffect
                    val rows = list("$base/bubbles")
                    bubbles = buildList {
                        for (index in 0 until rows.length()) {
                            val row = rows.optJSONObject(index) ?: continue
                            add(BubbleUi(row.optString("id"), row.optDouble("latitude"), row.optDouble("longitude"), row.optInt("radiusMeters", 300), row.optString("expiresAt").let(::formatLocalTime), !row.isNull("endedAt")))
                        }
                    }
                }
                AppPage.PLACE_DETAIL -> {
                    val base = circlePath ?: return@LaunchedEffect
                    val place = placesState.places.firstOrNull { it.id == selectedPlaceId } ?: return@LaunchedEffect
                    selectedPlace = place
                    val rows = list("$base/places/${place.id.uuidSegment()}/rules")
                    placeRuleObjects = buildMap {
                        for (index in 0 until rows.length()) {
                            val row = rows.optJSONObject(index) ?: continue
                            row.optString("id").takeIf(String::isNotBlank)?.let { put(it, JSONObject(row.toString())) }
                        }
                    }
                    placeRules = buildList {
                        for (index in 0 until rows.length()) {
                            val row = rows.optJSONObject(index) ?: continue
                            if (row.optString("kind") != "no_show") continue
                            val recurrence = row.optJSONObject("recurrence")?.let {
                                "${it.optString("frequency")} · ${it.optInt("interval", 1)} interval${if (it.optInt("interval", 1) == 1) "" else "s"}"
                            }
                            add(
                                NoShowRuleUi(
                                    id = row.optString("id"),
                                    expectedAtLabel = row.optString("expectedAt", "Not scheduled").takeIf(String::isNotBlank)?.let(::formatLocalTime) ?: "Not scheduled",
                                    graceMinutes = row.optInt("graceMinutes", 15),
                                    recurrenceLabel = recurrence,
                                    outcome = PlaceArrivalUi(DataCondition.UNKNOWN, "Arrival data is not available yet."),
                                ),
                            )
                        }
                    }
                }
                AppPage.HISTORY -> {
                    val base = circlePath ?: return@LaunchedEffect
                    val from = runCatching { Instant.parse(historyFrom).toString() }.getOrNull()
                    val to = runCatching { Instant.parse(historyTo).toString() }.getOrNull()
                    if (from != null && to != null && Instant.parse(from).isBefore(Instant.parse(to))) {
                        val json = runtime.api.memberGet(
                            "$base/history",
                            mapOf("memberId" to session!!.memberId, "from" to from, "to" to to, "limit" to "500"),
                        )
                        val rows = json.optJSONArray("data") ?: JSONArray()
                        historyPoints = buildList {
                            for (index in 0 until rows.length()) {
                                val row = rows.optJSONObject(index) ?: continue
                                add(MapMarker(GeoPoint(row.optDouble("latitude"), row.optDouble("longitude")), stale = false))
                            }
                        }
                        historyState = HistoryUiState(
                            condition = DataCondition.CONFIRMED,
                            message = "History is limited to locations available under current Circle access and consent.",
                            memberLabel = "You",
                            rangeLabel = "${formatLocalTime(from)} to ${formatLocalTime(to)}",
                            routePointCount = rows.length(),
                        )
                    } else if (historyState.condition == DataCondition.UNKNOWN) {
                        historyState = HistoryUiState(DataCondition.PENDING, "Enter a valid UTC ISO 8601 range.")
                    }
                }
                AppPage.QUICK_NOTES -> {
                    val base = circlePath ?: return@LaunchedEffect
                    val rows = list("$base/quick-notes")
                    noteItems = buildList {
                        for (index in 0 until rows.length()) {
                            val row = rows.optJSONObject(index) ?: continue
                            val senderId = row.optString("senderMemberId")
                            val senderName = circleState.members.firstOrNull { it.id == senderId }?.displayName ?: "Circle member"
                            add(QuickNoteUi(row.optString("id"), senderName, row.optString("text"), row.optString("createdAt").let(::formatLocalTime), row.optString("deliveryState", "unknown")))
                        }
                    }
                }
                AppPage.EVENTS -> {
                    val base = circlePath ?: return@LaunchedEffect
                    val rows = list("$base/events")
                    eventItems = buildList {
                        for (index in 0 until rows.length()) {
                            val row = rows.optJSONObject(index) ?: continue
                            add(CircleEventUi(row.optString("id"), row.optString("title", "Circle event"), row.optString("startsAt", "").let(::formatLocalTime), row.optString("notes"), row.optNullableString("myRsvp")))
                        }
                    }
                }
                AppPage.INBOX -> {
                    val rows = runtime.api.memberGet("notifications", mapOf("limit" to "50")).optJSONArray("data") ?: JSONArray()
                    inboxItems = buildList {
                        for (index in 0 until rows.length()) {
                            val row = rows.optJSONObject(index) ?: continue
                            add(InboxItemUi(row.optString("id"), row.optString("kind"), row.optString("title", "OpenTrack360 update"), row.optString("body"), row.optString("createdAt").let(::formatLocalTime), row.optString("deliveryState", "unknown"), !row.isNull("acknowledgedAt")))
                        }
                    }
                }
                AppPage.TRIPS -> {
                    val base = circlePath ?: return@LaunchedEffect
                    val rows = list("$base/trips")
                    val active = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.firstOrNull { it.optString("status") == "active" }
                    tripState = if (active == null) TripProgressUiState(condition = DataCondition.CONFIRMED, message = "No active trip is shared with this Circle.") else TripProgressUiState(
                        active = true,
                        tripId = active.optString("id").takeIf(String::isNotBlank),
                        condition = DataCondition.CONFIRMED,
                        message = "Trip progress is available to its selected Circle recipients.",
                        destinationLabel = active.optNullableString("destinationLabel"),
                        etaLabel = active.optNullableString("estimatedArrivalAt")?.let(::formatLocalTime),
                        distanceRemainingMeters = active.optNullableInt("distanceRemainingMeters"),
                    )
                }
                AppPage.MORNING -> {
                    val base = circlePath ?: return@LaunchedEffect
                    val rows = list("$base/morning-check-ins")
                    morningItems = buildList {
                        for (index in 0 until rows.length()) {
                            val row = rows.optJSONObject(index) ?: continue
                            add(MorningCheckInUi(row.optString("memberId"), row.optString("displayName", "Circle member"), row.optString("activityState", "unknown"), row.optNullableString("lastActivityAt")?.let(::formatLocalTime)))
                        }
                    }
                }
                AppPage.ACTIVITY -> {
                    val memberId = session?.memberId ?: return@LaunchedEffect
                    val selectedId = circleId ?: return@LaunchedEffect
                    val result = runtime.api.memberGet("circles/${selectedId.uuidSegment()}/activity-sharing")
                    activitySharingEnabled = result.getBoolean("activitySharingEnabled")
                    runtime.setActivitySharingEnabled(memberId, selectedId, activitySharingEnabled)
                }
                AppPage.DRIVING -> {
                    val base = circlePath ?: return@LaunchedEffect
                    val rows = list("$base/driving/reports")
                    drivingReports = buildList {
                        for (index in 0 until rows.length()) {
                            val row = rows.optJSONObject(index) ?: continue
                            add(DrivingReportUi(row.optString("tripId", row.optString("id")), row.optString("startedAt", "").let(::formatLocalTime), row.optNullableString("endedAt")?.let(::formatLocalTime), row.optString("classification", "unknown"), if (row.isNull("distanceMeters")) null else row.optDouble("distanceMeters"), row.optNullableInt("durationMinutes"), row.optNullableInt("harshEventCount")))
                        }
                    }
                }
                AppPage.PETS -> {
                    val rows = runtime.api.memberGet("pets").optJSONArray("data") ?: JSONArray()
                    pets = buildList {
                        for (index in 0 until rows.length()) {
                            val row = rows.optJSONObject(index) ?: continue
                            val names = circleState.circles.filter { it.id in (0 until row.optJSONArray("circleIds").let { ids -> ids?.length() ?: 0 }).mapNotNull { idx -> row.optJSONArray("circleIds")?.optString(idx) } }.map { it.name }
                            add(PetUi(row.getString("id"), row.optString("name", "Pet"), row.optString("species", "Unknown"), row.optNullableString("photoUrl"), names, row.optBoolean("recoveryEnabled")))
                        }
                    }
                }
                AppPage.PET_DETAIL -> {
                    val pet = pets.firstOrNull { it.id == selectedPetId } ?: return@LaunchedEffect
                    val petPath = "pets/${pet.id.uuidSegment()}"
                    val routines = list("$petPath/routines")
                    val activity = list("$petPath/activity")
                    val detail = JSONObject()
                    runCatching { runtime.api.memberGet(petPath) }.onSuccess { detail.put("profile", it.optJSONObject("data") ?: it) }
                    val profileJson = detail.optJSONObject("profile") ?: JSONObject()
                    petDetail = PetDetailUiState(
                        pet = pet.copy(photoUrl = profileJson.optNullableString("photoUrl")),
                        routines = buildList { for (index in 0 until routines.length()) routines.optJSONObject(index)?.let { row ->
                            val schedule = row.optNullableString("dueAt")?.let(::formatLocalTime) ?: row.optNullableString("notes") ?: "No reminder time"
                            add(PetRoutineUi(row.optString("id"), row.optString("name", "Care reminder"), "${row.optString("kind", "other").replaceFirstChar(Char::uppercase)} · $schedule", row.optNullableString("lastCompletedAt")?.let(::formatLocalTime)))
                        } },
                        activity = buildList { for (index in 0 until activity.length()) activity.optJSONObject(index)?.let { add(PetActivityUi(it.optString("label", it.optString("kind", "Care update")), it.optString("createdAt", "").let(::formatLocalTime), it.optNullableString("memberDisplayName"))) } },
                        condition = DataCondition.CONFIRMED,
                        message = "Pet profile, routines and activity are loaded for your account.",
                        recoveryFields = (profileJson.optJSONArray("recoveryFields") ?: JSONArray()).let { fields -> (0 until fields.length()).map { fields.optString(it) }.toSet() },
                        ownerMessage = profileJson.optString("ownerMessage", ""),
                        contactRoute = profileJson.optString("contactRoute", ""),
                        recoveryLinkCreatedAt = profileJson.optNullableString("recoveryLinkCreatedAt")?.let(::formatLocalTime),
                        activeLostPetReportId = activeLostPetReportIds[pet.id],
                    )
                }
                AppPage.SOS -> {
                    val base = circlePath ?: return@LaunchedEffect
                    val rows = list("$base/emergency-contacts")
                    sosState = sosState.copy(
                        condition = DataCondition.CONFIRMED,
                        recipients = buildList { for (index in 0 until rows.length()) rows.optJSONObject(index)?.let { add(SosRecipientUi(it.optString("memberId"), it.optString("username", "Emergency contact"), true, "in-app")) } },
                    )
                }
                else -> Unit
            }
        } catch (error: Exception) {
            val message = error.message ?: "Data could not be loaded."
            when (page) {
                AppPage.PLACES -> placesState = PlacesUiState(condition = DataCondition.ERROR, message = message)
                AppPage.HISTORY -> historyState = HistoryUiState(condition = DataCondition.ERROR, message = message)
                AppPage.PETS -> accountMessage = message
                AppPage.SOS -> sosState = sosState.copy(condition = DataCondition.ERROR, errorMessage = message)
                else -> accountMessage = message
            }
        }
    }

    fun submitSos(countdownSeconds: Int) {
        val circleId = selectedCircleId ?: return
        scope.launch {
            runCatching {
                runtime.api.memberPost(
                    "circles/${circleId.uuidSegment()}/sos",
                    JSONObject()
                        .put("clientEventId", java.util.UUID.randomUUID().toString())
                        .put("cancelCountdownSeconds", countdownSeconds)
                        .put("latitude", JSONObject.NULL)
                        .put("longitude", JSONObject.NULL)
                        .put("accuracyMeters", JSONObject.NULL),
                )
            }.onSuccess { response ->
                val result = response.optJSONObject("data") ?: response
                val recipients = result.optJSONArray("recipients") ?: JSONArray()
                sosState = sosState.copy(
                    condition = DataCondition.CONFIRMED,
                    alertId = result.optString("id").takeIf(String::isNotBlank),
                    deliveryState = result.optString("status", "queued"),
                    countdownSecondsRemaining = null,
                    statusMessage = "The alert was accepted by the configured in-app channel. It does not contact or dispatch public emergency services.",
                    errorMessage = null,
                )
            }.onFailure { error ->
                sosState = sosState.copy(condition = DataCondition.ERROR, countdownSecondsRemaining = null, errorMessage = error.message ?: "SOS was not submitted.")
            }
        }
    }

    LaunchedEffect(sosCountdownGeneration) {
        val generation = sosCountdownGeneration
        if (generation == 0) return@LaunchedEffect
        for (remaining in 5 downTo 1) {
            delay(1_000)
            if (sosCountdownGeneration != generation) return@LaunchedEffect
            sosState = sosState.copy(countdownSecondsRemaining = remaining - 1)
        }
        if (sosCountdownGeneration == generation) submitSos(sosState.cancelCountdownSeconds)
    }

    LaunchedEffect(historyState.playbackRunning, historyPlaybackIndex, historyPoints.size) {
        if (!historyState.playbackRunning || historyPoints.isEmpty()) return@LaunchedEffect
        delay(700)
        if (historyPlaybackIndex + 1 >= historyPoints.size) historyState = historyState.copy(playbackRunning = false)
        else historyPlaybackIndex += 1
    }

    LaunchedEffect(petRecoveryRequest) {
        val request = petRecoveryRequest ?: return@LaunchedEffect
        publicPetState = PublicPetRecoveryUiState(condition = DataCondition.PENDING, message = "Validating this owner-approved recovery link…")
        val result = runCatching { runtime.api.publicGet("public/pets/${request.petId}", request.authorizationHeader()) }
        result.onSuccess { response ->
            if (response.statusCode in 200..299) {
                runCatching { JSONObject(response.body).let { it.optJSONObject("data") ?: it } }
                    .onSuccess { card ->
                        publicPetState = PublicPetRecoveryUiState(
                            petName = card.optNullableString("petName"), species = card.optNullableString("species"),
                            ownerMessage = card.optNullableString("ownerMessage"), photoUrl = card.optNullableString("photoUrl"),
                            contactRoute = card.optNullableString("contactRoute"), condition = DataCondition.CONFIRMED,
                            message = "Showing only fields the pet owner selected for this recovery link.",
                        )
                    }
                    .onFailure { publicPetState = PublicPetRecoveryUiState(condition = DataCondition.ERROR, message = "Recovery details could not be read.") }
            } else {
                publicPetState = PublicPetRecoveryUiState(condition = DataCondition.UNAVAILABLE, message = if (response.statusCode == 404 || response.statusCode == 410) "This recovery link is invalid or no longer active." else "Recovery details are temporarily unavailable.")
            }
        }.onFailure { publicPetState = PublicPetRecoveryUiState(condition = DataCondition.OFFLINE, message = it.message ?: "Connect to validate this recovery link.") }
    }

    LaunchedEffect(page, selectedCircleId, sosState.alertId) {
        val circleId = selectedCircleId ?: return@LaunchedEffect
        val sosId = sosState.alertId ?: return@LaunchedEffect
        if (page != AppPage.SOS) return@LaunchedEffect
        while (page == AppPage.SOS && sosState.alertId == sosId) {
            runCatching { runtime.api.memberGet("circles/${circleId.uuidSegment()}/sos/${sosId.uuidSegment()}") }
                .onSuccess { result ->
                    val recipients = result.optJSONArray("recipients") ?: JSONArray()
                    sosState = sosState.copy(
                        deliveryState = result.optString("status", sosState.deliveryState ?: "queued"),
                        recipients = buildList { for (index in 0 until recipients.length()) recipients.optJSONObject(index)?.let { row ->
                            val existing = sosState.recipients.firstOrNull { it.id == row.optString("recipientId") }
                            add(SosRecipientUi(row.optString("recipientId"), existing?.name ?: "Circle contact", true, "in-app · ${row.optString("state", "queued")}"))
                        } },
                    )
                }
            delay(10_000)
        }
    }

    if (petRecoveryRequest != null) {
        BackHandler { onClosePetRecovery() }
        PublicPetRecoveryScreen(
            state = publicPetState,
            sightingMessage = petSightingMessage,
            latitude = petSightingLatitude,
            longitude = petSightingLongitude,
            accuracyMeters = petSightingAccuracy,
            contactRoute = petSightingContactRoute,
            onSightingMessageChange = { petSightingMessage = it },
            onLatitudeChange = { petSightingLatitude = it },
            onLongitudeChange = { petSightingLongitude = it },
            onAccuracyChange = { petSightingAccuracy = it },
            onContactRouteChange = { petSightingContactRoute = it },
            onSubmitSighting = { message, latitude, longitude, accuracy, contactRoute ->
                val request = petRecoveryRequest
                scope.launch {
                    publicPetState = publicPetState.copy(submitting = true, errorMessage = null, submissionMessage = null)
                    val body = JSONObject()
                        .put("message", message)
                        .put("latitude", latitude ?: JSONObject.NULL)
                        .put("longitude", longitude ?: JSONObject.NULL)
                        .put("accuracyMeters", accuracy ?: JSONObject.NULL)
                        .put("contactRoute", contactRoute ?: JSONObject.NULL)
                    runCatching { runtime.api.publicPost("public/pets/${request.petId}/sightings", body, request.authorizationHeader()) }
                        .onSuccess { response ->
                            publicPetState = if (response.statusCode in 200..299) publicPetState.copy(submitting = false, submissionMessage = "Sighting submitted to the owner's selected recipients.")
                            else publicPetState.copy(submitting = false, errorMessage = if (response.statusCode == 404 || response.statusCode == 410) "This recovery link is no longer active." else "Sighting could not be submitted.")
                        }
                        .onFailure { publicPetState = publicPetState.copy(submitting = false, errorMessage = it.message ?: "Sighting could not be submitted.") }
                }
            },
        )
        return
    }

    if (guestRequest != null) {
        BackHandler { onCloseGuest() }
        GuestShareScreen(request = guestRequest, gateway = runtime.shareGateway, modifier = Modifier.fillMaxSize())
        return
    }

    when {
        booting -> Surface(Modifier.fillMaxSize()) { MemberScreen(title = "OpenTrack360", subtitle = "Loading your account…") {} }
        access is MemberAccessState.RotationRequired -> {
            val username = (access as MemberAccessState.RotationRequired).username
            TemporaryPasswordChangeScreen(
                username = username,
                state = password,
                onCurrentPasswordChange = { password = password.copy(currentPassword = it, errorMessage = null) },
                onNewPasswordChange = { password = password.copy(newPassword = it, errorMessage = null) },
                onConfirmationChange = { password = password.copy(confirmation = it, errorMessage = null) },
                onChangePassword = { current, replacement ->
                    scope.launch {
                        password = password.copy(submitting = true, errorMessage = null)
                        runCatching { runtime.api.changePassword(current, replacement) }
                            .onSuccess {
                                session?.let { runtime.endMemberSession(it.memberId) }
                                session = null
                                access = MemberAccessState.ReauthenticationRequired
                                signIn = SignInUiState(username = username, errorMessage = "Password changed. Sign in again with your new password.")
                                page = AppPage.CIRCLES
                            }
                            .onFailure { password = password.copy(submitting = false, errorMessage = it.message ?: "Password change failed.") }
                    }
                },
                onSignOut = {
                    TrackingServiceController.stopForLogout(activity)
                    scope.launch { runCatching { runtime.api.logout() } }
                    session?.let { runtime.endMemberSession(it.memberId) }
                    session = null
                    access = MemberAccessState.SignedOut
                },
            )
        }
        !MemberAccessPolicy.canNavigateToMemberData(access) -> {
            when (page) {
                AppPage.RECOVERY -> AccountRecoveryHelpScreen(
                    helpMessage = "Contact the administrator who provisioned your account. Self-service password reset is not available.",
                    onOpenHelp = { openSystemAppSettings(activity) },
                    onBackToSignIn = { page = AppPage.CIRCLES },
                )
                else -> SignInScreen(
                    state = signIn,
                    invitationPending = circleInviteRequest != null,
                    onUsernameChange = { signIn = signIn.copy(username = it, errorMessage = null) },
                    onPasswordChange = { signIn = signIn.copy(password = it, errorMessage = null) },
                    onSignIn = { username, pass ->
                        scope.launch {
                            signIn = signIn.copy(submitting = true, errorMessage = null)
                            runCatching { runtime.api.signIn(username, pass) }
                                .onSuccess { authenticateAndLoad(it); signIn = SignInUiState(username = username) }
                                .onFailure { error ->
                                    signIn = SignInUiState(
                                        username = username,
                                        errorMessage = if (error is ApiException && error.statusCode == 401) "Username or password could not be verified. Contact your account administrator if you need help." else error.message ?: "Could not sign in.",
                                    )
                                }
                        }
                    },
                    onRecoveryHelp = { page = AppPage.RECOVERY },
                    onOpenGuestLink = { accountMessage = "Open the original HTTPS trip link from your message app to use the signed-out guest map." },
                )
            }
        }
        else -> Scaffold(
            bottomBar = {
                NavigationBar {
                    AppPage.entries.take(4).forEach { destination ->
                        NavigationBarItem(
                            selected = page == destination,
                            onClick = { page = destination },
                            icon = {},
                            label = { Text(destination.label) },
                        )
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (accountMessage != null) {
                    StatusBanner(DataCondition.ERROR, "Account status", accountMessage!!)
                }
                if (permissionSummary != null) {
                    StatusBanner(DataCondition.PENDING, "Location sharing", permissionSummary!!)
                }
                when (page) {
                    AppPage.CIRCLES -> CircleHomeScreen(
                        state = circleState,
                        mapContent = {
                            val markers = circleState.members.mapNotNull { member ->
                                member.location?.let { MapMarker(GeoPoint(it.latitude, it.longitude), stale = false) }
                            }
                            OpenTrackMapSurface(Modifier.fillMaxSize(), markers = markers)
                        },
                        onSelectCircle = { selectedCircleId = it; reloadHome++ },
                        onCreateCircle = { page = AppPage.CREATE_CIRCLE },
                        onInvite = { page = AppPage.CIRCLE_MANAGEMENT },
                        onOpenManagement = { page = AppPage.CIRCLE_MANAGEMENT },
                        onOpenHistory = { page = AppPage.HISTORY },
                        onOpenPlaces = { page = AppPage.PLACES },
                        onOpenCoordination = { page = AppPage.COORDINATION },
                        onOpenInbox = { page = AppPage.INBOX },
                        onOpenPets = { page = AppPage.PETS },
                        onSetCircleSharingEnabled = { circleId, sharingEnabled ->
                            scope.launch {
                                runCatching {
                                    runtime.api.memberPut(
                                        "circles/${circleId.uuidSegment()}/sharing",
                                        JSONObject().put("sharingEnabled", sharingEnabled),
                                    )
                                }.onSuccess {
                                    if (sharingEnabled) session?.let { member ->
                                        runtime.setSharingConsent(member.memberId, true)
                                        runtime.setSharingEnabled(member.memberId, true)
                                        val permission = AndroidPermissionProbe.snapshot(activity)
                                        if (permission.canCollect) startLocationService(activity, runtime, member.memberId)
                                        else {
                                            val missing = buildList {
                                                if (activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) add(Manifest.permission.ACCESS_COARSE_LOCATION)
                                                if (activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) add(Manifest.permission.ACCESS_FINE_LOCATION)
                                                if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
                                            }
                                            if (missing.isNotEmpty()) launchLocationPermissions.launch(missing.toTypedArray())
                                            else permissionSummary = "Circle sharing is enabled, but Android currently blocks location collection. Review permission settings."
                                        }
                                    }
                                    reloadHome++
                                }.onFailure { accountMessage = it.message ?: "Circle sharing could not be updated." }
                            }
                        },
                        onStartSharing = { startFromConsent() },
                        onStopAllSharing = {
                            val current = session ?: return@CircleHomeScreen
                            runtime.setSharingEnabled(current.memberId, false)
                            TrackingServiceController.stopFromUserAction(activity)
                            permissionSummary = "Sharing stopped on this phone. Server revocation is pending until the stop command is confirmed."
                            reloadHome++
                        },
                    )
                    AppPage.SHARES -> ShareManagementScreen(
                        gateway = runtime.shareGateway,
                        isLocationSharing = runtime.sharingIsEnabled(session!!.memberId) && trackingStatus.isActiveTrackingStatus(),
                        hasCurrentLocationFix = trackingStatus.isActiveTrackingStatus(),
                        onStartLocationSharing = { pendingStartAfterConsent = true },
                    )
                    AppPage.TOOLS -> AppToolsScreen(
                        onOpenPlaces = { page = AppPage.PLACES },
                        onOpenHistory = { page = AppPage.HISTORY },
                        onOpenCoordination = { page = AppPage.COORDINATION },
                        onOpenSafety = { page = AppPage.SAFETY },
                        onOpenPermissions = { page = AppPage.PERMISSIONS },
                        onOpenAccount = { page = AppPage.ACCOUNT },
                        onOpenPets = { page = AppPage.PETS },
                        onOpenInbox = { page = AppPage.INBOX },
                        onOpenTrips = { page = AppPage.TRIPS },
                        onOpenSos = { page = AppPage.SOS },
                        onOpenDriving = { page = AppPage.DRIVING },
                        onOpenPasswordChange = { page = AppPage.CHANGE_PASSWORD },
                        onOpenBubbles = { page = AppPage.BUBBLES },
                    )
                    AppPage.ACCOUNT -> AccountPrivacyScreen(
                        state = AccountPrivacyUiState(
                            username = session!!.username,
                            sessionStateLabel = "Signed in",
                            sharingSummary = if (runtime.sharingIsEnabled(session!!.memberId)) "Location collection enabled on this device" else "Location collection off",
                        ),
                        onRequestDataExport = {
                            scope.launch { runCatching { runtime.api.memberRequest("POST", "privacy/export") }
                                .onSuccess { accountMessage = "Your data export request was accepted." }
                                .onFailure { accountMessage = it.message ?: "Export request failed." } }
                        },
                        onDeletionReasonChange = {},
                        onDeletionDetailsChange = {},
                        onRequestAccountDeletion = { reason, details, phrase ->
                            scope.launch {
                                runCatching {
                                    runtime.api.memberPost(
                                        "privacy/deletion",
                                        JSONObject().put("reason", reason).put("details", details).put("confirmationPhrase", phrase),
                                    )
                                }.onSuccess { accountMessage = "Deletion request accepted. This request does not mean deletion has completed." }
                                    .onFailure { accountMessage = it.message ?: "Deletion request failed." }
                            }
                        },
                        onGuardianChildIdChange = {},
                        onGuardianUsernameChange = {},
                        onGuardianRelationshipChange = {},
                        onRequestGuardianConsent = { child, guardian, relation ->
                            scope.launch {
                                runCatching { runtime.api.memberPost("guardian-consents", JSONObject().put("childMemberId", child).put("guardianUsername", guardian).put("relationship", relation)) }
                                    .onSuccess { accountMessage = "Guardian consent request submitted." }
                                    .onFailure { accountMessage = it.message ?: "Guardian consent request failed." }
                            }
                        },
                        onOpenHelp = { page = AppPage.RECOVERY },
                        onLogout = {
                            val memberId = session?.memberId
                            TrackingServiceController.stopForLogout(activity)
                            memberId?.let { runtime.setSharingEnabled(it, false) }
                            runtime.endMemberSession(memberId)
                            scope.launch { runCatching { runtime.api.logout() } }
                            session = null
                            profile = null
                            access = MemberAccessState.SignedOut
                            page = AppPage.CIRCLES
                        },
                    )
                    AppPage.CHANGE_PASSWORD -> PasswordChangeSettingsScreen(
                        state = password,
                        onCurrentPasswordChange = { password = password.copy(currentPassword = it) },
                        onNewPasswordChange = { password = password.copy(newPassword = it) },
                        onConfirmationChange = { password = password.copy(confirmation = it) },
                        onChangePassword = { current, next ->
                            scope.launch {
                                password = password.copy(submitting = true, errorMessage = null)
                                runCatching { runtime.api.changePassword(current, next) }
                                    .onSuccess {
                                        TrackingServiceController.stopForLogout(activity)
                                        runtime.endMemberSession(session?.memberId)
                                        session = null
                                        access = MemberAccessState.ReauthenticationRequired
                                        signIn = SignInUiState(username = profile?.username.orEmpty(), errorMessage = "Password changed. Sign in again.")
                                        page = AppPage.CIRCLES
                                    }.onFailure { password = password.copy(submitting = false, errorMessage = it.message ?: "Password change failed.") }
                            }
                        },
                    )
                    AppPage.CREATE_CIRCLE -> CreateCircleScreen(
                        name = circleName,
                        kind = circleKind,
                        expiresAt = temporaryCircleExpiry,
                        submitting = createCircleBusy,
                        errorMessage = createCircleError,
                        onNameChange = { circleName = it },
                        onKindChange = { circleKind = it },
                        onExpiresAtChange = { temporaryCircleExpiry = it },
                        onCreateCircle = { name, kind, expiry ->
                            scope.launch {
                                createCircleBusy = true
                                runCatching {
                                    runtime.api.memberPost("circles", JSONObject().put("name", name).put("kind", kind).put("expiresAt", expiry ?: JSONObject.NULL))
                                }.onSuccess {
                                    circleName = ""; circleKind = "standard"; temporaryCircleExpiry = ""
                                    page = AppPage.CIRCLES; reloadHome++
                                }.onFailure { createCircleError = it.message ?: "Circle could not be created." }
                                createCircleBusy = false
                            }
                        },
                        onBack = { page = AppPage.CIRCLES },
                    )
                    AppPage.CIRCLE_MANAGEMENT -> {
                        val state = circleManagementState
                        if (state == null) MemberScreen("Manage Circle", "Loading members…") {}
                        else CircleManagementScreen(
                            state = state,
                            onCreateInvite = { circleId -> createCircleInvite(circleId) },
                            onUpdateMember = { id, memberId, field, value ->
                                val currentMemberId = session?.memberId ?: return@CircleManagementScreen
                                val allowedValue = when (field) {
                                    "role" -> memberId != currentMemberId && state.canManage && value in setOf("admin", "member")
                                    "precision" -> memberId == currentMemberId && value in setOf("precise", "approximate", "hidden")
                                    else -> false
                                }
                                if (!allowedValue) return@CircleManagementScreen
                                scope.launch {
                                    runCatching {
                                        runtime.api.memberPatch(
                                            "circles/${id.uuidSegment()}/members/${memberId.uuidSegment()}",
                                            JSONObject().put(field, value),
                                        )
                                    }.onSuccess { reloadHome++ }
                                        .onFailure { error ->
                                            circleManagementState = circleManagementState?.copy(
                                                errorMessage = error.message ?: "Circle member settings could not be saved.",
                                            )
                                        }
                                }
                            },
                            onRemoveMember = { id, memberId ->
                                scope.launch { runCatching { runtime.api.memberDelete("circles/${id.uuidSegment()}/members/${memberId.uuidSegment()}") }
                                    .onSuccess { reloadHome++; page = AppPage.CIRCLE_MANAGEMENT }
                                    .onFailure { accountMessage = it.message ?: "Member could not be removed." } }
                            },
                            onLeaveCircle = { id ->
                                scope.launch { runCatching { runtime.api.memberDelete("circles/${id.uuidSegment()}") }
                                    .onSuccess { selectedCircleId = null; reloadHome++; page = AppPage.CIRCLES }
                                    .onFailure { accountMessage = it.message ?: "Could not leave this Circle." } }
                            },
                            onBack = { page = AppPage.CIRCLES },
                        )
                    }
                    AppPage.PLACES -> PlacesScreen(
                        state = placesState,
                        onCreatePlace = {
                            editingPlace = false
                            selectedPlaceId = null
                            placeForm = PlaceFormUiState(
                                latitude = circleState.members.firstOrNull { it.id == session!!.memberId }?.location?.latitude?.toString().orEmpty(),
                                longitude = circleState.members.firstOrNull { it.id == session!!.memberId }?.location?.longitude?.toString().orEmpty(),
                            )
                            page = AppPage.PLACE_EDITOR
                        },
                        onOpenPlace = { selectedPlaceId = it; page = AppPage.PLACE_DETAIL },
                    )
                    AppPage.PLACE_EDITOR -> PlaceEditorScreen(
                        state = placeForm,
                        mapContent = {
                            val lat = placeForm.latitude.toDoubleOrNull()
                            val lon = placeForm.longitude.toDoubleOrNull()
                            val markers = if (lat != null && lon != null) listOf(MapMarker(GeoPoint(lat, lon), stale = false)) else emptyList()
                            OpenTrackMapSurface(Modifier.fillMaxSize(), markers = markers)
                        },
                        onNameChange = { placeForm = placeForm.copy(name = it) },
                        onLatitudeChange = { placeForm = placeForm.copy(latitude = it) },
                        onLongitudeChange = { placeForm = placeForm.copy(longitude = it) },
                        onRadiusChange = { placeForm = placeForm.copy(radiusMeters = it) },
                        onArrivalAlertChange = { placeForm = placeForm.copy(alertOnArrival = it) },
                        onDepartureAlertChange = { placeForm = placeForm.copy(alertOnDeparture = it) },
                        onSavePlace = { name, latitude, longitude, radius, arrival, departure ->
                            val circleId = selectedCircleId ?: return@PlaceEditorScreen
                            val body = JSONObject().put("name", name).put("latitude", latitude).put("longitude", longitude).put("radiusMeters", radius).put("alertOnArrival", arrival).put("alertOnDeparture", departure)
                            scope.launch {
                                placeForm = placeForm.copy(saving = true, errorMessage = null)
                                runCatching {
                                    val path = "circles/${circleId.uuidSegment()}/places" + (if (editingPlace) "/${selectedPlaceId!!.uuidSegment()}" else "")
                                    if (editingPlace) runtime.api.memberPatch(path, body) else runtime.api.memberPost(path, body)
                                }.onSuccess { reloadHome++; page = if (editingPlace) AppPage.PLACE_DETAIL else AppPage.PLACES }
                                    .onFailure { placeForm = placeForm.copy(errorMessage = it.message ?: "Place could not be saved.") }
                                placeForm = placeForm.copy(saving = false)
                            }
                        },
                        onCancel = { page = if (editingPlace) AppPage.PLACE_DETAIL else AppPage.PLACES },
                    )
                    AppPage.PLACE_DETAIL -> {
                        val place = selectedPlace
                        if (place == null) MemberScreen("Place", "Loading Place details…") {}
                        else PlaceMapDetailScreen(
                            state = PlaceDetailUiState(place, placeRules, DataCondition.CONFIRMED, "Only the current Circle’s configured alert rules are shown."),
                            mapContent = { OpenTrackMapSurface(Modifier.fillMaxSize(), markers = listOf(MapMarker(GeoPoint(place.latitude, place.longitude), stale = false)), circles = listOf(me.srishanth.opentrack360.ui.map.MapCircle(GeoPoint(place.latitude, place.longitude), place.radiusMeters.toDouble()))) },
                            onUpdatePlace = {
                                editingPlace = true
                                selectedPlaceId = it
                                placeForm = PlaceFormUiState(place.name, place.latitude.toString(), place.longitude.toString(), place.radiusMeters.toString())
                                page = AppPage.PLACE_EDITOR
                            },
                            onDeletePlace = { id ->
                                val circleId = selectedCircleId ?: return@PlaceMapDetailScreen
                                scope.launch { runCatching { runtime.api.memberDelete("circles/${circleId.uuidSegment()}/places/${id.uuidSegment()}") }
                                    .onSuccess { selectedPlaceId = null; reloadHome++; page = AppPage.PLACES }
                                    .onFailure { accountMessage = it.message ?: "Place could not be deleted." } }
                            },
                            onUpdatePlaceRule = { id, ruleId ->
                                val raw = placeRuleObjects[ruleId]
                                selectedRuleId = ruleId
                                val recurrence = raw?.optJSONObject("recurrence")
                                placeRuleForm = PlaceRuleFormUiState(
                                    kind = raw?.optString("kind", "no_show") ?: "no_show",
                                    expectedAt = raw?.optNullableString("expectedAt").orEmpty(),
                                    graceMinutes = raw?.optInt("graceMinutes", 15)?.toString() ?: "15",
                                    recurrenceEnabled = recurrence != null,
                                    frequency = recurrence?.optString("frequency", "weekly") ?: "weekly",
                                    interval = recurrence?.optInt("interval", 1)?.toString() ?: "1",
                                    weekdays = recurrence?.optJSONArray("weekdays")?.let { days -> (0 until days.length()).map { days.optInt(it) }.toSet() } ?: emptySet(),
                                    localTime = recurrence?.optString("localTime", "08:00") ?: "08:00",
                                    timeZone = recurrence?.optString("timeZone", "Asia/Kolkata") ?: "Asia/Kolkata",
                                    selectedMemberIds = raw?.optJSONArray("memberIds")?.let { ids -> (0 until ids.length()).map { ids.optString(it) }.toSet() } ?: emptySet(),
                                )
                                page = AppPage.PLACE_RULE
                            },
                            onDeletePlaceRule = { id, ruleId ->
                                val circleId = selectedCircleId ?: return@PlaceMapDetailScreen
                                scope.launch { runCatching { runtime.api.memberDelete("circles/${circleId.uuidSegment()}/places/${id.uuidSegment()}/rules/${ruleId.uuidSegment()}") }
                                    .onSuccess { reloadHome++ }
                                    .onFailure { accountMessage = it.message ?: "Alert rule could not be deleted." } }
                            },
                            onCreateArrivalDepartureRule = { selectedRuleId = null; placeRuleForm = PlaceRuleFormUiState(kind = "arrival"); page = AppPage.PLACE_RULE },
                            onCreateNoShowRule = { selectedRuleId = null; placeRuleForm = PlaceRuleFormUiState(); page = AppPage.PLACE_RULE },
                            onBack = { page = AppPage.PLACES },
                        )
                    }
                    AppPage.PLACE_RULE -> {
                        val place = selectedPlace
                        if (place == null) MemberScreen("Place alerts", "Select a Place first.") {}
                        else PlaceRuleEditorScreen(
                            placeName = place.name,
                            state = placeRuleForm,
                            recipients = circleState.members.map { AudienceChoiceUi(it.id, it.displayName, it.id in placeRuleForm.selectedMemberIds) },
                            onKindChange = { placeRuleForm = placeRuleForm.copy(kind = it) },
                            onExpectedAtChange = { placeRuleForm = placeRuleForm.copy(expectedAt = it) },
                            onGraceMinutesChange = { placeRuleForm = placeRuleForm.copy(graceMinutes = it) },
                            onRecurrenceEnabledChange = { placeRuleForm = placeRuleForm.copy(recurrenceEnabled = it) },
                            onFrequencyChange = { placeRuleForm = placeRuleForm.copy(frequency = it) },
                            onIntervalChange = { placeRuleForm = placeRuleForm.copy(interval = it) },
                            onWeekdayChange = { day, selected -> placeRuleForm = placeRuleForm.copy(weekdays = if (selected) placeRuleForm.weekdays + day else placeRuleForm.weekdays - day) },
                            onLocalTimeChange = { placeRuleForm = placeRuleForm.copy(localTime = it) },
                            onTimeZoneChange = { placeRuleForm = placeRuleForm.copy(timeZone = it) },
                            onMemberSelected = { id, selected -> placeRuleForm = placeRuleForm.copy(selectedMemberIds = if (selected) placeRuleForm.selectedMemberIds + id else placeRuleForm.selectedMemberIds - id) },
                            onSaveRule = { kind, memberIds, expectedAt, grace, recurrence ->
                                val circleId = selectedCircleId ?: return@PlaceRuleEditorScreen
                                val recurrenceJson = recurrence?.let { JSONObject().put("frequency", it.frequency).put("interval", it.interval).put("weekdays", JSONArray(it.weekdays)).put("localTime", it.localTime).put("timeZone", it.timeZone) } ?: JSONObject.NULL
                                val body = JSONObject().put("kind", kind).put("memberIds", JSONArray(memberIds)).put("expectedAt", expectedAt ?: JSONObject.NULL).put("graceMinutes", grace).put("recurrence", recurrenceJson)
                                scope.launch {
                                    runCatching {
                                        val path = "circles/${circleId.uuidSegment()}/places/${place.id.uuidSegment()}/rules" + (selectedRuleId?.let { "/${it.uuidSegment()}" } ?: "")
                                        if (selectedRuleId != null) runtime.api.memberPatch(path, body) else runtime.api.memberPost(path, body)
                                    }.onSuccess { selectedRuleId = null; page = AppPage.PLACE_DETAIL; reloadHome++ }
                                        .onFailure { placeRuleForm = placeRuleForm.copy(errorMessage = it.message ?: "Alert rule could not be saved.") }
                                }
                            },
                            onCancel = { selectedRuleId = null; page = AppPage.PLACE_DETAIL },
                        )
                    }
                    AppPage.HISTORY -> HistoryScreen(
                        state = historyState.copy(playbackRunning = historyState.playbackRunning && historyPlaybackIndex < historyPoints.size),
                        mapContent = {
                            val visible = if (historyState.playbackRunning) historyPoints.take(historyPlaybackIndex + 1) else historyPoints
                            OpenTrackMapSurface(Modifier.fillMaxSize(), markers = visible)
                        },
                        fromDateTime = historyFrom,
                        toDateTime = historyTo,
                        onFromDateTimeChange = { historyFrom = it },
                        onToDateTimeChange = { historyTo = it },
                        onLoadHistory = { _, _ -> historyState = historyState.copy(condition = DataCondition.PENDING); reloadHome++ },
                        onPlayRoute = { historyPlaybackIndex = 0; historyState = historyState.copy(playbackRunning = historyPoints.isNotEmpty()) },
                        onStopPlayback = { historyState = historyState.copy(playbackRunning = false) },
                    )
                    AppPage.COORDINATION -> CoordinationToolsScreen(
                        onBubbles = { page = AppPage.BUBBLES }, onCheckIn = { page = AppPage.CHECK_INS }, onNotes = { page = AppPage.QUICK_NOTES }, onEvents = { page = AppPage.EVENTS },
                        onTrips = { page = AppPage.TRIPS }, onMorning = { page = AppPage.MORNING }, onActivity = { page = AppPage.ACTIVITY }, onInbox = { page = AppPage.INBOX },
                    )
                    AppPage.BUBBLES -> BubbleScreen(
                        bubbles = bubbles, latitude = bubbleLatitude, longitude = bubbleLongitude, radiusMeters = bubbleRadius, durationMinutes = bubbleDuration,
                        condition = DataCondition.CONFIRMED, message = "Bubble centers are approximate and active only until their selected expiry.",
                        mapContent = {
                            OpenTrackMapSurface(
                                Modifier.fillMaxSize(),
                                circles = bubbles.filterNot { it.ended }.map { me.srishanth.opentrack360.ui.map.MapCircle(GeoPoint(it.latitude, it.longitude), it.radiusMeters.toDouble()) },
                            )
                        },
                        onLatitudeChange = { bubbleLatitude = it }, onLongitudeChange = { bubbleLongitude = it }, onRadiusChange = { bubbleRadius = it }, onDurationChange = { bubbleDuration = it },
                        onCreateBubble = { latitude, longitude, radius, duration ->
                            val circleId = selectedCircleId ?: return@BubbleScreen
                            scope.launch { runCatching { runtime.api.memberPost("circles/${circleId.uuidSegment()}/bubbles", JSONObject()
                                .put("clientEventId", java.util.UUID.randomUUID().toString())
                                .put("latitude", latitude).put("longitude", longitude).put("radiusMeters", radius).put("durationMinutes", duration)) }
                                .onSuccess { bubbleLatitude = ""; bubbleLongitude = ""; reloadHome++ }
                                .onFailure { accountMessage = it.message ?: "Bubble could not be created." } }
                        },
                        onEndBubble = { id ->
                            val circleId = selectedCircleId ?: return@BubbleScreen
                            scope.launch { runCatching { runtime.api.memberDelete("circles/${circleId.uuidSegment()}/bubbles/${id.uuidSegment()}") }
                                .onSuccess { reloadHome++ }
                                .onFailure { accountMessage = it.message ?: "Bubble could not be ended." } }
                        },
                    )
                    AppPage.CHECK_INS -> CheckInScreen(
                        state = CheckInUiState(note = checkinNote),
                        onNoteChange = { checkinNote = it },
                        onCreateCheckIn = { label ->
                            val circleId = selectedCircleId ?: return@CheckInScreen
                            val location = circleState.members.firstOrNull { it.id == session?.memberId }?.location
                            val capturedAt = location?.capturedAtIso?.let { runCatching { Instant.parse(it) }.getOrNull() }
                            if (location == null || capturedAt == null || capturedAt.isBefore(Instant.now().minusSeconds(120))) {
                                accountMessage = "A recent location fix is required before sending a check-in. Start location sharing and try again."
                            } else {
                                scope.launch { runCatching { runtime.api.memberPost("circles/${circleId.uuidSegment()}/checkins", JSONObject()
                                    .put("clientEventId", java.util.UUID.randomUUID().toString())
                                    .put("label", label.take(80))
                                    .put("latitude", location.latitude)
                                    .put("longitude", location.longitude)
                                    .put("accuracyMeters", location.accuracyMeters)
                                    .put("capturedAt", location.capturedAtIso)) }
                                    .onSuccess { checkinNote = ""; accountMessage = "Check-in submitted to this Circle." }
                                    .onFailure { accountMessage = it.message ?: "Check-in could not be sent." } }
                            }
                        },
                    )
                    AppPage.QUICK_NOTES -> QuickNotesScreen(
                        notes = noteItems,
                        condition = DataCondition.CONFIRMED,
                        message = "Quick Notes available to your current Circle are shown here.",
                        draft = noteDraft,
                        submitting = false,
                        onDraftChange = { noteDraft = it },
                        onSend = { body ->
                            val circleId = selectedCircleId ?: return@QuickNotesScreen
                            scope.launch { runCatching { runtime.api.memberPost("circles/${circleId.uuidSegment()}/quick-notes", JSONObject().put("clientEventId", java.util.UUID.randomUUID().toString()).put("text", body)) }
                                .onSuccess { noteDraft = ""; reloadHome++ }
                                .onFailure { accountMessage = it.message ?: "Quick Note could not be sent." } }
                        },
                    )
                    AppPage.EVENTS -> EventsScreen(
                        events = eventItems, condition = DataCondition.CONFIRMED, message = "Events visible to this Circle.",
                        draftTitle = eventTitle, draftStartsAt = eventStartsAt, draftDetails = eventDetails,
                        invitees = circleState.members.map { AudienceChoiceUi(it.id, it.displayName, it.id in selectedEventInvitees) },
                        onInviteeSelected = { id, selected -> selectedEventInvitees = if (selected) selectedEventInvitees + id else selectedEventInvitees - id },
                        onDraftTitleChange = { eventTitle = it }, onDraftStartsAtChange = { eventStartsAt = it }, onDraftDetailsChange = { eventDetails = it },
                        onCreateEvent = { title, startsAt, details ->
                            val circleId = selectedCircleId ?: return@EventsScreen
                            scope.launch { runCatching { runtime.api.memberPost("circles/${circleId.uuidSegment()}/events", JSONObject()
                                .put("clientEventId", java.util.UUID.randomUUID().toString())
                                .put("title", title)
                                .put("startsAt", startsAt)
                                .put("endsAt", JSONObject.NULL)
                                .put("notes", details.takeIf(String::isNotBlank) ?: JSONObject.NULL)
                                .put("inviteeMemberIds", JSONArray(selectedEventInvitees.toList()))) }
                                .onSuccess { eventTitle = ""; eventDetails = ""; selectedEventInvitees = emptySet(); reloadHome++ }
                                .onFailure { accountMessage = it.message ?: "Event could not be created." } }
                        },
                        onSetRsvp = { id, response ->
                            val circleId = selectedCircleId ?: return@EventsScreen
                            scope.launch { runCatching { runtime.api.memberPut("circles/${circleId.uuidSegment()}/events/${id.uuidSegment()}/rsvp", JSONObject().put("response", response)) }
                                .onSuccess { reloadHome++ }
                                .onFailure { accountMessage = it.message ?: "RSVP could not be updated." } }
                        },
                    )
                    AppPage.INBOX -> NotificationInboxScreen(
                        items = inboxItems, condition = DataCondition.CONFIRMED, message = "Notification delivery state is shown as received from the configured channel.",
                        onAcknowledge = { ids -> scope.launch { runCatching { runtime.api.memberPatch("notifications", JSONObject().put("notificationIds", JSONArray(ids))) }
                            .onSuccess { reloadHome++ }
                            .onFailure { accountMessage = it.message ?: "Notifications could not be acknowledged." } } },
                    )
                    AppPage.TRIPS -> TripProgressScreen(
                        state = tripState,
                        destination = tripDestination, destinationLatitude = tripLatitude, destinationLongitude = tripLongitude,
                        selectedRecipientIds = tripRecipients,
                        availableRecipients = circleState.members.map { AudienceChoiceUi(it.id, it.displayName, it.id in tripRecipients) },
                        shareLockScreenProgress = tripLockScreen,
                        mapContent = {
                            val markers = circleState.members.mapNotNull { member -> member.location?.let { MapMarker(GeoPoint(it.latitude, it.longitude), stale = false) } }
                            OpenTrackMapSurface(Modifier.fillMaxSize(), markers = markers)
                        },
                        onDestinationChange = { tripDestination = it }, onDestinationLatitudeChange = { tripLatitude = it }, onDestinationLongitudeChange = { tripLongitude = it },
                        onRecipientSelected = { id, selected -> tripRecipients = if (selected) tripRecipients + id else tripRecipients - id },
                        onShareLockScreenProgressChange = { tripLockScreen = it },
                        onStartTrip = { label, latitude, longitude, ids, lockScreen ->
                            val circleId = selectedCircleId ?: return@TripProgressScreen
                            scope.launch {
                                runCatching {
                                    runtime.api.memberPost("circles/${circleId.uuidSegment()}/trips", JSONObject()
                                        .put("recipientMemberIds", JSONArray(ids))
                                        .put("destinationLabel", label ?: JSONObject.NULL)
                                        .put("destinationLatitude", latitude ?: JSONObject.NULL)
                                        .put("destinationLongitude", longitude ?: JSONObject.NULL)
                                        .put("shareLockScreenProgress", lockScreen))
                                }.onSuccess {
                                    tripState = tripState.copy(active = true, condition = DataCondition.CONFIRMED, message = "Trip started. To end all location sharing, use Stop all sharing on the Circles page; a per-trip stop route is not yet defined.", destinationLabel = label)
                                    if (!trackingStatus.isActiveTrackingStatus()) {
                                        runtime.setSharingConsent(session!!.memberId, true)
                                        val permission = AndroidPermissionProbe.snapshot(activity)
                                        if (permission.canCollect) startLocationService(activity, runtime, session!!.memberId)
                                        else {
                                            val missing = buildList {
                                                if (activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) add(Manifest.permission.ACCESS_COARSE_LOCATION)
                                                if (activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) add(Manifest.permission.ACCESS_FINE_LOCATION)
                                                if (Build.VERSION.SDK_INT >= 29 && activity.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != android.content.pm.PackageManager.PERMISSION_GRANTED) add(Manifest.permission.ACTIVITY_RECOGNITION)
                                                if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
                                            }
                                            if (missing.isNotEmpty()) launchLocationPermissions.launch(missing.toTypedArray())
                                            else permissionSummary = "Trip is active, but location permission is unavailable. Enable location to send progress."
                                        }
                                    }
                                }.onFailure { accountMessage = it.message ?: "Trip could not be started." }
                            }
                        },
                        onStopTrip = { id, reason ->
                            val circleId = selectedCircleId ?: return@TripProgressScreen
                            scope.launch { runCatching { runtime.api.memberPost("circles/${circleId.uuidSegment()}/trips/${id.uuidSegment()}/stop", JSONObject()
                                .put("clientRequestId", java.util.UUID.randomUUID().toString())
                                .put("endReason", reason)) }
                                .onSuccess { tripState = tripState.copy(active = false, tripId = null, condition = DataCondition.CONFIRMED, message = "This trip has ended. Circle sharing and guest links remain active.") }
                                .onFailure { accountMessage = it.message ?: "Trip could not be ended." } }
                        },
                    )
                    AppPage.MORNING -> MorningCheckInScreen(
                        items = morningItems, condition = DataCondition.CONFIRMED, message = "Morning check-in status shared within this Circle.",
                        greeting = morningGreeting, submitting = false, onGreetingChange = { morningGreeting = it },
                        onSendMorningCheckIn = { greeting ->
                            val circleId = selectedCircleId ?: return@MorningCheckInScreen
                            scope.launch { runCatching { runtime.api.memberPost("circles/${circleId.uuidSegment()}/morning-check-ins", JSONObject()
                                .put("clientEventId", java.util.UUID.randomUUID().toString())
                                .put("greeting", greeting.takeIf(String::isNotBlank) ?: JSONObject.NULL)) }
                                .onSuccess { morningGreeting = ""; reloadHome++ }
                                .onFailure { accountMessage = it.message ?: "Morning check-in could not be sent." } }
                        },
                    )
                    AppPage.ACTIVITY -> ActivitySharingScreen(
                        state = ActivitySharingUiState(activitySharingEnabled, if (activitySharingEnabled) "On" else "Off", circleState.selectedCircleName ?: "Current Circle", null),
                        onSetActivitySharing = { enabled ->
                            val circleId = selectedCircleId ?: return@ActivitySharingScreen
                            scope.launch { runCatching { runtime.api.memberPut("circles/${circleId.uuidSegment()}/activity-sharing", JSONObject().put("activitySharingEnabled", enabled)) }
                                .onSuccess { response ->
                                    val current = response.optJSONObject("data") ?: response
                                    activitySharingEnabled = current.optBoolean("activitySharingEnabled", enabled)
                                    session?.memberId?.let { memberId -> runtime.setActivitySharingEnabled(memberId, circleId, activitySharingEnabled) }
                                }
                                .onFailure { accountMessage = it.message ?: "Activity sharing could not be updated." } }
                        },
                    )
                    AppPage.DRIVING -> DrivingReportsScreen(
                        reports = drivingReports, condition = DataCondition.CONFIRMED, message = "Reports use only measurements available from your phone.",
                        onCorrectClassification = { id, classification ->
                            val circleId = selectedCircleId ?: return@DrivingReportsScreen
                            scope.launch { runCatching { runtime.api.memberPatch("circles/${circleId.uuidSegment()}/driving/trips/${id.uuidSegment()}", JSONObject().put("classification", classification)) }
                                .onSuccess { reloadHome++ }
                                .onFailure { accountMessage = it.message ?: "Drive classification could not be updated." } }
                        },
                    )
                    AppPage.PETS -> PetProfilesScreen(
                        pets = pets, condition = DataCondition.CONFIRMED, message = "Pet data is private to your account and the Circles you select.",
                        circleChoices = circleState.circles.map { it.id to it.name }, name = petName, species = petSpecies, selectedCircleIds = petCircles,
                        onNameChange = { petName = it }, onSpeciesChange = { petSpecies = it },
                        onCircleSelected = { id, selected -> petCircles = if (selected) petCircles + id else petCircles - id },
                        onCreatePet = { name, species, ids ->
                            scope.launch { runCatching { runtime.api.memberPost("pets", JSONObject()
                                .put("name", name)
                                .put("species", species)
                                .put("circleIds", JSONArray(ids))
                                .put("recoveryEnabled", false)
                                .put("recoveryFields", JSONArray())) }
                                .onSuccess { petName = ""; petSpecies = ""; petCircles = emptySet(); reloadHome++ }
                                .onFailure { accountMessage = it.message ?: "Pet profile could not be created." } }
                        },
                        onOpenPet = { selectedPetId = it; petRecoveryUrl = null; page = AppPage.PET_DETAIL },
                    )
                    AppPage.PET_DETAIL -> petDetail?.let { state -> PetDetailScreen(
                        state = state,
                        recoveryQrBitmap = remember(petRecoveryUrl) { petRecoveryUrl?.let(::createRecoveryQrBitmap) },
                        routineTitle = routineTitle, routineSchedule = routineSchedule, routineKind = routineKind, routineDueAt = routineDueAt,
                        onRoutineTitleChange = { routineTitle = it }, onRoutineScheduleChange = { routineSchedule = it },
                        onRoutineKindChange = { routineKind = it }, onRoutineDueAtChange = { routineDueAt = it },
                        onRecoveryFieldSelected = { field, selected -> petDetail = state.copy(recoveryFields = if (selected) state.recoveryFields + field else state.recoveryFields - field) },
                        onOwnerMessageChange = { value -> petDetail = petDetail?.copy(ownerMessage = value.take(500)) },
                        onContactRouteChange = { value -> petDetail = petDetail?.copy(contactRoute = value.take(200)) },
                        onUpdateRecoverySettings = { id, enabled, fields, ownerMessage, contactRoute ->
                            scope.launch { runCatching { runtime.api.memberPatch("pets/${id.uuidSegment()}", JSONObject()
                                .put("recoveryEnabled", enabled)
                                .put("recoveryFields", JSONArray(fields))
                                .put("ownerMessage", ownerMessage.take(500))
                                .put("contactRoute", contactRoute.takeIf(String::isNotBlank)?.take(200) ?: JSONObject.NULL)) }
                                .onSuccess {
                                    petDetail = petDetail?.copy(
                                        pet = petDetail!!.pet.copy(recoveryEnabled = enabled),
                                        recoveryFields = fields.toSet(),
                                        ownerMessage = ownerMessage.take(500),
                                        contactRoute = contactRoute.take(200),
                                    )
                                    pets = pets.map { if (it.id == id) it.copy(recoveryEnabled = enabled) else it }
                                    reloadHome++
                                }
                                .onFailure { accountMessage = it.message ?: "Recovery privacy settings could not be saved." } }
                        },
                        onOpenPhotoPicker = { petPhotoPicker.launch("image/*") },
                        onCreateRoutine = { id, title, kind, notes, dueAt ->
                            scope.launch { runCatching { runtime.api.memberPost("pets/${id.uuidSegment()}/routines", JSONObject()
                                .put("clientRequestId", java.util.UUID.randomUUID().toString())
                                .put("name", title)
                                .put("kind", kind)
                                .put("notes", notes.takeIf(String::isNotBlank) ?: JSONObject.NULL)
                                .put("dueAt", dueAt ?: JSONObject.NULL)
                                .put("recurrence", JSONObject.NULL)) }
                                .onSuccess { routineTitle = ""; routineSchedule = ""; routineDueAt = ""; reloadHome++ }
                                .onFailure { accountMessage = it.message ?: "Care routine could not be created." } }
                        },
                        onCompleteRoutine = { id, routineId ->
                            scope.launch { runCatching { runtime.api.memberPost("pets/${id.uuidSegment()}/routines/${routineId.uuidSegment()}/complete", JSONObject().put("clientEventId", java.util.UUID.randomUUID().toString())) }
                                .onSuccess { reloadHome++ }
                                .onFailure { accountMessage = it.message ?: "Care routine could not be marked complete." } }
                        },
                        onCreateRecoveryLink = { id ->
                            scope.launch { runCatching { runtime.api.memberRequest("POST", "pets/${id.uuidSegment()}/recovery-link") }
                                .onSuccess { response ->
                                    val url = response.optString("url")
                                    val parsed = PetRecoveryLinkParser.parse(url)
                                    if (parsed?.petId == id) { petRecoveryUrl = url; shareText(activity, url) }
                                    petDetail = petDetail?.copy(recoveryLinkCreatedAt = response.optString("createdAt").takeIf(String::isNotBlank)?.let(::formatLocalTime))
                                    accountMessage = if (parsed?.petId != id) "Recovery link response was invalid; no link was shared." else "Recovery link is ready to share. Anyone holding it can view the pet owner’s selected recovery fields."
                                }
                                .onFailure { accountMessage = it.message ?: "Recovery link could not be created." } }
                        },
                        onRevokeRecoveryLink = { id ->
                            scope.launch { runCatching { runtime.api.memberDelete("pets/${id.uuidSegment()}/recovery-link") }
                                .onSuccess { petDetail = petDetail?.copy(recoveryLinkCreatedAt = null); petRecoveryUrl = null; accountMessage = "Recovery link revoked." }
                                .onFailure { accountMessage = it.message ?: "Recovery link could not be revoked." } }
                        },
                        onCreateLostPetReport = { page = AppPage.LOST_PET; lostPetCircles = emptySet() },
                        onRevokeLostPetReport = { reportId ->
                            scope.launch { runCatching { runtime.api.memberDelete("lost-pets/${reportId.uuidSegment()}") }
                                .onSuccess { activeLostPetReportIds = activeLostPetReportIds - (selectedPetId ?: ""); petDetail = petDetail?.copy(activeLostPetReportId = null); accountMessage = "Lost-pet report revoked." }
                                .onFailure { accountMessage = it.message ?: "Lost-pet report could not be revoked." } }
                        },
                        onDeletePet = { id ->
                            scope.launch { runCatching { runtime.api.memberDelete("pets/${id.uuidSegment()}") }
                                .onSuccess { selectedPetId = null; petDetail = null; page = AppPage.PETS; reloadHome++ }
                                .onFailure { accountMessage = it.message ?: "Pet profile could not be deleted." } }
                        },
                        onBack = { page = AppPage.PETS },
                    ) } ?: MemberScreen("Pet details", "Loading pet information…") {}
                    AppPage.LOST_PET -> {
                        val pet = pets.firstOrNull { it.id == selectedPetId }
                        if (pet == null) MemberScreen("Lost pet report", "Select a pet first.") {}
                        else LostPetReportScreen(
                            petName = pet.name,
                            audience = circleState.circles.map { AudienceChoiceUi(it.id, it.name, it.id in lostPetCircles) },
                            expiresAt = lostPetExpiresAt,
                            reportMessage = lostPetMessage,
                            statusMessage = null,
                            onAudienceSelected = { id, selected -> lostPetCircles = if (selected) lostPetCircles + id else lostPetCircles - id },
                            onExpiresAtChange = { lostPetExpiresAt = it }, onReportMessageChange = { lostPetMessage = it },
                            onSubmitReport = { ids, message, expiresAt ->
                                scope.launch { runCatching { runtime.api.memberPost("lost-pets", JSONObject().put("petId", pet.id).put("recipientCircleIds", JSONArray(ids)).put("message", message.takeIf(String::isNotBlank) ?: JSONObject.NULL).put("expiresAt", expiresAt)) }
                                    .onSuccess { result -> val data = result.optJSONObject("data") ?: result; val reportId = data.optString("id"); if (reportId.isNotBlank()) activeLostPetReportIds = activeLostPetReportIds + (pet.id to reportId); petDetail = petDetail?.copy(activeLostPetReportId = reportId.takeIf(String::isNotBlank)); accountMessage = "Lost-pet report sent to the selected Circles. You can revoke it from the pet profile."; page = AppPage.PET_DETAIL }
                                    .onFailure { accountMessage = it.message ?: "Lost-pet report could not be submitted." } }
                            },
                            onCancel = { page = AppPage.PET_DETAIL },
                        )
                    }
                    AppPage.SOS -> SosScreen(
                        state = sosState,
                        onStartCountdown = { if (sosState.recipients.any { it.accepted }) { sosState = sosState.copy(countdownSecondsRemaining = 5, statusMessage = null, errorMessage = null); sosCountdownGeneration++ } },
                        onCancelCountdown = { sosCountdownGeneration++; sosState = sosState.copy(countdownSecondsRemaining = null, statusMessage = "SOS canceled before submission.", alertId = null) },
                        onCreateSos = { submitSos(it) },
                        onAcknowledgeSos = { accountMessage = "Only an accepted SOS recipient can acknowledge an alert." },
                        onInviteEmergencyContact = { showEmergencyInviteDialog = true },
                    )
                    AppPage.SAFETY -> SafetyAvailabilityScreen(
                        permissions = permissionDiagnostics(activity),
                        onOpenPermissionDiagnostics = { page = AppPage.PERMISSIONS },
                        onOpenCircleMap = { page = AppPage.CIRCLES },
                    )
                    AppPage.PERMISSIONS -> PermissionDiagnosticsScreen(
                        permissions = permissionDiagnostics(activity),
                        overallCondition = if (AndroidPermissionProbe.snapshot(activity).canCollect) DataCondition.CONFIRMED else DataCondition.UNAVAILABLE,
                        summary = permissionSummary ?: "Review Android permissions before turning on location sharing.",
                        onOpenSystemSettings = { openSystemAppSettings(activity) },
                        onRetry = { reloadHome++ },
                    )
                    else -> Unit
                }
            }
        }
    }

    if (pendingStartAfterConsent) {
        AlertDialog(
            onDismissRequest = { pendingStartAfterConsent = false },
            title = { Text("Start location sharing?") },
            text = { Text("OpenTrack360 will collect phone location while sharing is enabled. Circle members with your consent and any active guest trip links can see the latest location, accuracy, update time and reliable speed. Android may ask for location, activity and notification permissions. A foreground notification will show when sharing is active. You can stop sharing at any time.") },
            confirmButton = {
                TextButton(onClick = { pendingStartAfterConsent = false; startFromConsent() }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { pendingStartAfterConsent = false }) { Text("Cancel") } },
        )
    }

    if (showEmergencyInviteDialog) {
        AlertDialog(
            onDismissRequest = { showEmergencyInviteDialog = false; emergencyContactUsername = "" },
            title = { Text("Invite an emergency contact") },
            text = {
                Column {
                    Text("Invite an existing provisioned OpenTrack360 account. They must accept before SOS alerts can be relied on.")
                    OutlinedTextField(
                        value = emergencyContactUsername,
                        onValueChange = { emergencyContactUsername = it },
                        label = { Text("Member username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val circleId = selectedCircleId
                    val username = emergencyContactUsername.trim()
                    if (circleId == null || username.length < 3) return@TextButton
                    showEmergencyInviteDialog = false
                    emergencyContactUsername = ""
                    scope.launch {
                        runCatching { runtime.api.memberPost("circles/${circleId.uuidSegment()}/emergency-contacts", JSONObject()
                            .put("clientRequestId", java.util.UUID.randomUUID().toString())
                            .put("recipientUsername", username)) }
                            .onSuccess { result ->
                                val item = result.optJSONObject("data") ?: result
                                sosState = sosState.copy(recipients = sosState.recipients + SosRecipientUi(item.optString("recipientMemberId"), item.optString("recipientUsername", username), false, "in-app · pending acceptance"))
                                accountMessage = "Invitation sent. The contact must accept before receiving SOS alerts."
                            }
                            .onFailure { accountMessage = it.message ?: "Emergency contact invitation failed." }
                    }
                }) { Text("Send invitation") }
            },
            dismissButton = { TextButton(onClick = { showEmergencyInviteDialog = false }) { Text("Cancel") } },
        )
    }

    if (circleInviteRequest != null && session != null && !booting &&
        MemberAccessPolicy.canNavigateToMemberData(access)) {
        val currentUsername = session?.username.orEmpty()
        AlertDialog(
            onDismissRequest = { if (!inviteAcceptBusy) { inviteAcceptError = null; onCloseCircleInvite() } },
            title = { Text("Accept Circle invitation?") },
            text = {
                Column {
                    Text("Join the invited Circle as $currentUsername? This adds Circle members and location features to this signed-in account.")
                    inviteAcceptError?.let { StatusBanner(DataCondition.ERROR, "Invitation not accepted", it) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !inviteAcceptBusy,
                    onClick = {
                        val request = circleInviteRequest
                        inviteAcceptBusy = true
                        inviteAcceptError = null
                        scope.launch {
                            runCatching {
                                val response = runtime.api.memberPost("circle-invites/accept", request.acceptanceBody())
                                val result = response.optJSONObject("data") ?: response
                                require(result.optBoolean("joined")) { "The invitation could not be accepted." }
                                java.util.UUID.fromString(result.getString("circleId")).toString()
                            }.onSuccess { joinedCircleId ->
                                selectedCircleId = joinedCircleId
                                page = AppPage.CIRCLES
                                reloadHome++
                                accountMessage = "You joined the Circle. Review its sharing controls before starting location sharing."
                                onCloseCircleInvite()
                            }.onFailure { error ->
                                inviteAcceptError = error.message ?: "This invitation could not be accepted. It may have expired or already been used."
                            }
                            inviteAcceptBusy = false
                        }
                    },
                ) { Text(if (inviteAcceptBusy) "Joining…" else "Accept as $currentUsername") }
            },
            dismissButton = {
                TextButton(
                    enabled = !inviteAcceptBusy,
                    onClick = { inviteAcceptError = null; onCloseCircleInvite() },
                ) { Text("Not now") }
            },
        )
    }
}

@Composable
private fun AppToolsScreen(
    onOpenPlaces: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenCoordination: () -> Unit,
    onOpenSafety: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenPets: () -> Unit,
    onOpenInbox: () -> Unit,
    onOpenTrips: () -> Unit,
    onOpenSos: () -> Unit,
    onOpenDriving: () -> Unit,
    onOpenPasswordChange: () -> Unit,
    onOpenBubbles: () -> Unit,
) {
    MemberScreen(title = "Member tools", subtitle = "Open a Circle first for Circle-specific features.") {
        MemberCard {
            PrimaryAction("Places and No Show", onOpenPlaces, Modifier.fillMaxWidth())
            PrimaryAction("Approximate location Bubble", onOpenBubbles, Modifier.fillMaxWidth())
            PrimaryAction("History and route playback", onOpenHistory, Modifier.fillMaxWidth())
            PrimaryAction("Check-ins, Quick Notes, events and trips", onOpenCoordination, Modifier.fillMaxWidth())
            PrimaryAction("Pets and care", onOpenPets, Modifier.fillMaxWidth())
            PrimaryAction("Trip progress", onOpenTrips, Modifier.fillMaxWidth())
            PrimaryAction("Notification inbox", onOpenInbox, Modifier.fillMaxWidth())
            PrimaryAction("Circle SOS", onOpenSos, Modifier.fillMaxWidth())
            PrimaryAction("Driving reports", onOpenDriving, Modifier.fillMaxWidth())
            PrimaryAction("Safety and driving", onOpenSafety, Modifier.fillMaxWidth())
            PrimaryAction("Permission diagnostics", onOpenPermissions, Modifier.fillMaxWidth())
            PrimaryAction("Account and privacy", onOpenAccount, Modifier.fillMaxWidth())
            PrimaryAction("Change password", onOpenPasswordChange, Modifier.fillMaxWidth())
        }
        EmptyStateCard("Provider-dependent feeds", "Weather, flight and ride provider feeds are unavailable until a suitable free India source is verified. Use Circle events, check-ins and Quick Notes for coordination.")
    }
}

@Composable
private fun CoordinationToolsScreen(
    onBubbles: () -> Unit,
    onCheckIn: () -> Unit,
    onNotes: () -> Unit,
    onEvents: () -> Unit,
    onTrips: () -> Unit,
    onMorning: () -> Unit,
    onActivity: () -> Unit,
    onInbox: () -> Unit,
) {
    MemberScreen(title = "Circle coordination", subtitle = "These tools use only the selected private Circle.") {
        MemberCard {
            PrimaryAction("Approximate location Bubble", onBubbles, Modifier.fillMaxWidth())
            PrimaryAction("Check in", onCheckIn, Modifier.fillMaxWidth())
            PrimaryAction("Quick Notes", onNotes, Modifier.fillMaxWidth())
            PrimaryAction("Events and RSVPs", onEvents, Modifier.fillMaxWidth())
            PrimaryAction("Trip progress", onTrips, Modifier.fillMaxWidth())
            PrimaryAction("Morning check-in", onMorning, Modifier.fillMaxWidth())
            PrimaryAction("Activity sharing", onActivity, Modifier.fillMaxWidth())
            PrimaryAction("Notification inbox", onInbox, Modifier.fillMaxWidth())
        }
    }
}

private fun startLocationService(activity: ComponentActivity, runtime: OpenTrackRuntime, memberId: String) {
    runtime.setSharingEnabled(memberId, true)
    when (TrackingServiceController.startFromUserAction(activity)) {
        TrackingStartResult.START_REQUESTED -> Unit
        TrackingStartResult.LOCATION_PERMISSION_REQUIRED -> runtime.setSharingEnabled(memberId, false)
        TrackingStartResult.SERVICE_UNAVAILABLE -> runtime.setSharingEnabled(memberId, false)
    }
}

private const val MAX_PET_PHOTO_BYTES = 5 * 1024 * 1024
private val PET_PHOTO_CONTENT_TYPES = setOf("image/jpeg", "image/png", "image/webp")

private fun permissionDiagnostics(activity: ComponentActivity): List<PermissionDiagnosticUi> {
    val state = AndroidPermissionProbe.snapshot(activity)
    return listOf(
        PermissionDiagnosticUi(
            name = "Location",
            stateLabel = when (state.precision) { LocationPrecision.PRECISE -> "Precise"; LocationPrecision.APPROXIMATE -> "Approximate"; null -> "Denied" },
            explanation = "The app uses phone location only when you start sharing.",
            requiredForLabel = "Circle maps, trips and guest links",
        ),
        PermissionDiagnosticUi(
            name = "Background location",
            stateLabel = if (state.hasBackgroundLocation) "Allowed" else "Limited",
            explanation = "Background access may be needed for updates after the app is no longer visible.",
            requiredForLabel = "Background trip updates",
        ),
        PermissionDiagnosticUi(
            name = "Activity recognition",
            stateLabel = if (activity.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == android.content.pm.PackageManager.PERMISSION_GRANTED) "Allowed" else "Not allowed",
            explanation = "Motion classification helps distinguish a drive from a parked start.",
            requiredForLabel = "Drive reports",
        ),
        PermissionDiagnosticUi(
            name = "Notifications",
            stateLabel = if (state.hasNotifications) "Allowed" else "Not allowed",
            explanation = "Notifications show the ongoing location service and configured app alerts.",
            requiredForLabel = "Foreground status and alerts",
        ),
        PermissionDiagnosticUi(
            name = "Battery saver",
            stateLabel = if (state.batterySaverOn) "On" else "Off",
            explanation = if (state.batterySaverOn) "Battery saver may reduce update frequency." else "Battery saver is not limiting updates.",
            requiredForLabel = "Location freshness",
        ),
    )
}

private fun openSystemAppSettings(activity: ComponentActivity) {
    activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:${activity.packageName}")
    })
}

private fun shareText(activity: ComponentActivity, text: String) {
    activity.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            },
            "Share OpenTrack360 link",
        ),
    )
}

private fun createRecoveryQrBitmap(url: String): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
    val size = 600
    val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until size) for (x in 0 until size) {
            setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }.asImageBitmap()
}.getOrNull()

private fun org.json.JSONObject.optNullableString(name: String): String? =
    if (!has(name) || isNull(name)) null else optString(name).takeIf(String::isNotBlank)

private fun org.json.JSONObject.optNullableInt(name: String): Int? =
    if (!has(name) || isNull(name)) null else optInt(name)

private fun String.uuidSegment(): String = java.util.UUID.fromString(this).toString()

private fun formatLocalTime(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(Instant.parse(value))
}.getOrDefault("Unknown")

private fun TrackingStatus?.isActiveTrackingStatus(): Boolean = this in setOf(
    TrackingStatus.TRACKING_PRECISE,
    TrackingStatus.TRACKING_APPROXIMATE,
    TrackingStatus.TRACKING_APPROXIMATE_BACKGROUND_LIMITED,
    TrackingStatus.BACKGROUND_ACCESS_LIMITED,
    TrackingStatus.BATTERY_SAVER_LIMITED,
)

