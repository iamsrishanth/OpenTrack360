package me.srishanth.opentrack360.ui.circles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.srishanth.opentrack360.ui.components.DestructiveActionButton
import me.srishanth.opentrack360.ui.components.ConsentActionButton
import me.srishanth.opentrack360.ui.components.EmptyStateCard
import me.srishanth.opentrack360.ui.components.LabeledValue
import me.srishanth.opentrack360.ui.components.MapSlot
import me.srishanth.opentrack360.ui.components.MemberCard
import me.srishanth.opentrack360.ui.components.MemberScreen
import me.srishanth.opentrack360.ui.components.PrimaryAction
import me.srishanth.opentrack360.ui.components.SectionHeading
import me.srishanth.opentrack360.ui.components.StatusBanner
import me.srishanth.opentrack360.ui.model.CircleChoiceUi
import me.srishanth.opentrack360.ui.model.CircleMemberUi
import me.srishanth.opentrack360.ui.model.DataCondition
import me.srishanth.opentrack360.ui.model.DestructiveAction
import me.srishanth.opentrack360.ui.model.DestructiveActionKind

data class CircleHomeUiState(
    val circles: List<CircleChoiceUi> = emptyList(),
    val selectedCircleId: String? = null,
    val selectedCircleName: String? = null,
    val members: List<CircleMemberUi> = emptyList(),
    val mapCondition: DataCondition = DataCondition.UNKNOWN,
    val mapMessage: String = "Member locations have not loaded.",
    val sharingPaused: Boolean = false,
    val sharingActive: Boolean = false,
    val canManage: Boolean = false,
    val offline: Boolean = false,
)

@Composable
fun CircleHomeScreen(
    state: CircleHomeUiState,
    mapContent: @Composable () -> Unit,
    onSelectCircle: (circleId: String) -> Unit,
    onCreateCircle: () -> Unit,
    onInvite: (circleId: String) -> Unit,
    onOpenManagement: (circleId: String) -> Unit,
    onOpenHistory: (circleId: String) -> Unit,
    onOpenPlaces: (circleId: String) -> Unit,
    onOpenCoordination: (circleId: String) -> Unit,
    onOpenInbox: () -> Unit,
    onOpenPets: () -> Unit,
    onSetCircleSharingEnabled: (circleId: String, sharingEnabled: Boolean) -> Unit,
    onStartSharing: (circleId: String) -> Unit,
    onStopAllSharing: () -> Unit,
) {
    val selectedId = state.selectedCircleId
    MemberScreen(title = state.selectedCircleName ?: "Your Circles", subtitle = "Private location sharing is controlled by each member.") {
        if (state.circles.isEmpty()) {
            EmptyStateCard("No Circles yet", "Accept an invitation or create a Circle to get started.")
        } else {
            SectionHeading("Switch Circle")
            state.circles.forEach { circle ->
                MemberCard {
                    Text(circle.name, style = MaterialTheme.typography.titleSmall)
                    Text("${circle.kindLabel} · ${circle.roleLabel}", style = MaterialTheme.typography.bodyMedium)
                    circle.expiresAtLabel?.let { Text("Ends $it", style = MaterialTheme.typography.bodySmall) }
                    PrimaryAction(
                        label = if (circle.id == selectedId) "Current Circle" else "Open ${circle.name}",
                        onClick = { onSelectCircle(circle.id) },
                        enabled = circle.id != selectedId,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        MemberCard {
            SectionHeading("${state.selectedCircleName ?: "Circle"} map")
            MapSlot(mapContent, Modifier.fillMaxWidth().height(300.dp))
            StatusBanner(state.mapCondition, mapConditionTitle(state.mapCondition), state.mapMessage)
            if (state.offline) {
                StatusBanner(DataCondition.OFFLINE, "Offline", "Member updates may be out of date until the connection returns.")
            }
        }
        if (state.members.isEmpty()) {
            EmptyStateCard("No member updates", "There are no current consenting locations to show. This does not confirm an arrival.")
        } else {
            SectionHeading("Circle members")
            state.members.forEach { member -> CircleMemberCard(member) }
        }
        MemberCard {
            SectionHeading("Circle tools")
            PrimaryAction("Places and No Show", { selectedId?.let(onOpenPlaces) }, Modifier.fillMaxWidth(), selectedId != null)
            PrimaryAction("History", { selectedId?.let(onOpenHistory) }, Modifier.fillMaxWidth(), selectedId != null)
            PrimaryAction("Check-ins, notes and events", { selectedId?.let(onOpenCoordination) }, Modifier.fillMaxWidth(), selectedId != null)
            PrimaryAction("Inbox", onOpenInbox, Modifier.fillMaxWidth())
            PrimaryAction("Pet profiles", onOpenPets, Modifier.fillMaxWidth())
            if (selectedId != null && state.canManage) {
                PrimaryAction("Manage Circle", { onOpenManagement(selectedId) }, Modifier.fillMaxWidth())
                PrimaryAction("Invite a member", { onInvite(selectedId) }, Modifier.fillMaxWidth())
            }
            if (selectedId != null) {
                if (state.sharingPaused) {
                    ConsentActionButton(
                        label = "Resume sharing in this Circle",
                        title = "Resume Circle sharing?",
                        explanation = "Your location will be shared with ${state.selectedCircleName ?: "this Circle"} again. Android may ask for location permissions if they are missing. You can pause sharing again at any time.",
                        onConsent = { onSetCircleSharingEnabled(selectedId, true) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (!state.sharingActive) {
                    ConsentActionButton(
                        label = "Start sharing in this Circle",
                        title = "Start location sharing?",
                        explanation = "Your location will be shared only with ${state.selectedCircleName ?: "this Circle"} while sharing is enabled. Android may ask for location permissions. Opening or creating a guest link does not start location collection.",
                        onConsent = { onStartSharing(selectedId) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    DestructiveActionButton(
                        action = DestructiveAction(DestructiveActionKind.PAUSE_CIRCLE_SHARING, state.selectedCircleName),
                        label = "Pause sharing in this Circle",
                        onConfirmed = { onSetCircleSharingEnabled(selectedId, false) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            DestructiveActionButton(
                action = DestructiveAction(DestructiveActionKind.STOP_ALL_SHARING),
                label = "Stop all sharing",
                onConfirmed = onStopAllSharing,
                modifier = Modifier.fillMaxWidth(),
            )
            PrimaryAction("Create a Circle", onCreateCircle, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun CircleMemberCard(member: CircleMemberUi) {
    MemberCard {
        SectionHeading(member.displayName)
        Text("${member.roleLabel} · ${member.sharingLabel}", style = MaterialTheme.typography.bodyMedium)
        val location = member.location
        if (location == null) {
            StatusBanner(DataCondition.UNKNOWN, "Location unknown", "There is no current location update. An arrival cannot be confirmed from missing data.")
        } else {
            LabeledValue("Last update", location.capturedAtLabel)
            LabeledValue("Accuracy", "${location.accuracyMeters.toInt()} m · ${location.precisionLabel}")
        }
        member.activityLabel?.let { LabeledValue("Activity", it) }
        member.batteryPercent?.let { LabeledValue("Battery", "$it%") }
        LabeledValue("Directions and ETA", member.directionsEtaLabel ?: "Unavailable for this update")
    }
}

private fun mapConditionTitle(condition: DataCondition): String = when (condition) {
    DataCondition.CONFIRMED -> "Current map data"
    DataCondition.PENDING -> "Map update pending"
    DataCondition.OFFLINE -> "Map offline"
    DataCondition.UNKNOWN -> "Map status unknown"
    DataCondition.UNAVAILABLE -> "Map unavailable"
    DataCondition.ERROR -> "Map could not load"
}

data class CircleManagementUiState(
    val circleId: String,
    val circleName: String,
    val roleLabel: String,
    val canManage: Boolean,
    val currentMemberId: String = "",
    val members: List<CircleMemberUi> = emptyList(),
    val inviteStatus: String? = null,
    val errorMessage: String? = null,
)

@Composable
fun CircleManagementScreen(
    state: CircleManagementUiState,
    inviteLinkAvailable: Boolean,
    onCreateInvite: (circleId: String) -> Unit,
    onCopyInvite: () -> Unit,
    onShareInvite: () -> Unit,
    onUpdateMember: (circleId: String, memberId: String, field: String, value: String) -> Unit,
    onRemoveMember: (circleId: String, memberId: String) -> Unit,
    onLeaveCircle: (circleId: String) -> Unit,
    onBack: () -> Unit,
) {
    var showInviteWarning by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    MemberScreen(title = state.circleName, subtitle = "Circle role: ${state.roleLabel}") {
        state.errorMessage?.let { StatusBanner(DataCondition.ERROR, "Could not update Circle", it) }
        state.inviteStatus?.let { StatusBanner(DataCondition.PENDING, "Invitation status", it) }
        if (state.canManage) {
            MemberCard {
                Text("Create a single-use invitation link for an existing provisioned account. It expires within 72 hours; anyone holding it can join this Circle.")
                PrimaryAction(
                    "Create invitation link",
                    { showInviteWarning = true },
                    Modifier.fillMaxWidth(),
                )
                if (inviteLinkAvailable) {
                    Text("The one-use link is ready. If you closed the share sheet, copy it or open the share sheet again.")
                    TextButton(onClick = onCopyInvite, modifier = Modifier.fillMaxWidth()) { Text("Copy invitation link") }
                    TextButton(onClick = onShareInvite, modifier = Modifier.fillMaxWidth()) { Text("Share invitation link again") }
                }
            }
        }
        SectionHeading("Members")
        if (state.members.isEmpty()) EmptyStateCard("Members unavailable", "Circle member data has not loaded.")
        state.members.forEach { member ->
            MemberCard {
                SectionHeading(member.displayName)
                Text("${member.roleLabel} · ${member.sharingLabel} · ${member.precision} location")
                if (member.id == state.currentMemberId) {
                    Text("Your location precision in this Circle")
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("precise", "approximate", "hidden").forEach { option ->
                            FilterChip(
                                selected = member.precision.equals(option, ignoreCase = true),
                                onClick = { onUpdateMember(state.circleId, member.id, "precision", option) },
                                label = { Text(option.replaceFirstChar(Char::uppercase)) },
                            )
                        }
                    }
                } else if (state.canManage) {
                    val nextRole = if (member.roleLabel.equals("Admin", ignoreCase = true)) "member" else "admin"
                    PrimaryAction(
                        if (nextRole == "admin") "Make ${member.displayName} an admin" else "Change ${member.displayName} to member",
                        { onUpdateMember(state.circleId, member.id, "role", nextRole) },
                        Modifier.fillMaxWidth(),
                    )
                    DestructiveActionButton(
                        action = DestructiveAction(DestructiveActionKind.REMOVE_CIRCLE_MEMBER, member.displayName),
                        label = "Remove ${member.displayName}",
                        onConfirmed = { onRemoveMember(state.circleId, member.id) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        MemberCard {
            Text("Leaving removes your Circle access and history access immediately.")
            DestructiveActionButton(
                action = DestructiveAction(DestructiveActionKind.LEAVE_CIRCLE, state.circleName),
                label = "Leave Circle",
                onConfirmed = { onLeaveCircle(state.circleId) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        TextButton(onClick = onBack) { Text("Back") }
    }
    if (showInviteWarning) {
        AlertDialog(
            onDismissRequest = { showInviteWarning = false },
            title = { Text("Create an invitation link?") },
            text = {
                Text("This link can be used once by an existing provisioned OpenTrack360 account and expires within 72 hours. Anyone who receives a forwarded copy can join this Circle. Continue to the Android share sheet?")
            },
            confirmButton = {
                TextButton(onClick = { showInviteWarning = false; onCreateInvite(state.circleId) }) {
                    Text("Create and share")
                }
            },
            dismissButton = {
                TextButton(onClick = { showInviteWarning = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
fun CreateCircleScreen(
    name: String,
    kind: String,
    expiresAt: String,
    submitting: Boolean,
    errorMessage: String?,
    onNameChange: (String) -> Unit,
    onKindChange: (String) -> Unit,
    onExpiresAtChange: (String) -> Unit,
    onCreateCircle: (name: String, kind: String, expiresAt: String?) -> Unit,
    onBack: () -> Unit,
) {
    MemberScreen(title = "Create a Circle", subtitle = "Only invited, provisioned accounts can join.") {
        MemberCard {
            OutlinedTextField(name, onNameChange, label = { Text("Circle name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Circle type")
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("standard", "temporary", "lightweight").forEach { option ->
                    FilterChip(
                        selected = kind == option,
                        onClick = { onKindChange(option) },
                        label = { Text(option.replaceFirstChar { it.uppercase() }) },
                    )
                }
            }
            if (kind == "temporary") {
                OutlinedTextField(
                    expiresAt,
                    onExpiresAtChange,
                    label = { Text("End time (ISO 8601)") },
                    supportingText = { Text("Temporary Circles require an expiry time.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            errorMessage?.let { StatusBanner(DataCondition.ERROR, "Circle not created", it) }
            PrimaryAction(
                if (submitting) "Creating…" else "Create Circle",
                onClick = { onCreateCircle(name.trim(), kind, expiresAt.takeIf { kind == "temporary" && it.isNotBlank() }) },
                modifier = Modifier.fillMaxWidth(),
                enabled = name.isNotBlank() && !submitting && (kind != "temporary" || expiresAt.isNotBlank()),
            )
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}

@Composable
fun AcceptCircleInviteScreen(
    circleName: String?,
    expiresAtLabel: String?,
    signedIn: Boolean,
    stateMessage: String,
    onAccept: () -> Unit,
    onSignIn: () -> Unit,
) {
    MemberScreen(title = "Circle invitation", subtitle = "Invitations are for existing OpenTrack360 accounts.") {
        MemberCard {
            Text(circleName ?: "Circle details will appear after invitation validation.", style = MaterialTheme.typography.titleMedium)
            expiresAtLabel?.let { Text("Expires $it") }
            StatusBanner(DataCondition.UNKNOWN, "Access not confirmed", stateMessage)
            if (signedIn) PrimaryAction("Accept invitation", onAccept, Modifier.fillMaxWidth())
            else PrimaryAction("Sign in to continue", onSignIn, Modifier.fillMaxWidth())
        }
    }
}
