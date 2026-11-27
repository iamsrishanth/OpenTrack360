package me.srishanth.opentrack360.ui.safety

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import me.srishanth.opentrack360.ui.components.DestructiveActionButton
import me.srishanth.opentrack360.ui.components.EmptyStateCard
import me.srishanth.opentrack360.ui.components.LabeledValue
import me.srishanth.opentrack360.ui.components.MemberCard
import me.srishanth.opentrack360.ui.components.MemberScreen
import me.srishanth.opentrack360.ui.components.PrimaryAction
import me.srishanth.opentrack360.ui.components.SectionHeading
import me.srishanth.opentrack360.ui.components.StatusBanner
import me.srishanth.opentrack360.ui.model.DataCondition
import me.srishanth.opentrack360.ui.model.DestructiveAction
import me.srishanth.opentrack360.ui.model.DestructiveActionKind
import me.srishanth.opentrack360.ui.model.PermissionDiagnosticUi

data class SosRecipientUi(val id: String, val name: String, val accepted: Boolean, val channelLabel: String)

object SosAcknowledgementPolicy {
    fun canAcknowledge(currentMemberId: String?, recipientStates: Map<String, String>): Boolean {
        val state = currentMemberId?.let(recipientStates::get) ?: return false
        return state != "acknowledged" && state != "failed"
    }
}

object EmergencyContactInviteIdPolicy {
    private val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    fun normalize(value: String): String? {
        val trimmed = value.trim()
        if (!UUID_PATTERN.matches(trimmed)) return null
        return runCatching { java.util.UUID.fromString(trimmed).toString() }.getOrNull()
    }
}

data class SosUiState(
    val recipients: List<SosRecipientUi> = emptyList(),
    val pendingInviteIdToShare: String? = null,
    val pendingInviteRecipientUsername: String? = null,
    val inviteIdToAccept: String = "",
    val inviteAcceptanceMessage: String? = null,
    val condition: DataCondition = DataCondition.UNKNOWN,
    val deliveryState: String? = null,
    val alertId: String? = null,
    val canAcknowledge: Boolean = false,
    val cancelCountdownSeconds: Int = 5,
    val countdownSecondsRemaining: Int? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)

@Composable
fun SosScreen(
    state: SosUiState,
    onStartCountdown: () -> Unit,
    onCancelCountdown: () -> Unit,
    onCreateSos: (cancelCountdownSeconds: Int) -> Unit,
    onAcknowledgeSos: (sosId: String) -> Unit,
    onInviteEmergencyContact: () -> Unit,
    onShareInviteId: (inviteId: String) -> Unit,
    onInviteIdToAcceptChange: (String) -> Unit,
    onAcceptEmergencyContactInvite: () -> Unit,
) {
    MemberScreen(title = "Circle SOS", subtitle = "An in-app request for help from people you choose.") {
        StatusBanner(
            DataCondition.UNAVAILABLE,
            "No public dispatch",
            "SOS alerts the configured Circle recipients shown below. OpenTrack360 does not contact emergency services or dispatch a responder.",
        )
        MemberCard {
            SectionHeading("Accepted recipients")
            val accepted = state.recipients.filter { it.accepted }
            if (accepted.isEmpty()) EmptyStateCard("No accepted recipients", "Invite an emergency contact and wait for their acceptance before relying on them.")
            accepted.forEach { person ->
                LabeledValue(person.name, "Accepted · ${person.channelLabel}")
            }
            state.recipients.filterNot { it.accepted }.forEach { person ->
                LabeledValue(person.name, "Invitation pending")
            }
            PrimaryAction("Invite an emergency contact", onInviteEmergencyContact, Modifier.fillMaxWidth())
        }

        state.pendingInviteIdToShare?.let { inviteId ->
            MemberCard {
                SectionHeading("Share the invitation ID")
                Text("Send this ID to ${state.pendingInviteRecipientUsername ?: "the invited recipient"}. It is not a credential; only the invited signed-in account can accept it.")
                LabeledValue("Invitation ID", inviteId)
                PrimaryAction("Share invitation ID", { onShareInviteId(inviteId) }, Modifier.fillMaxWidth())
            }
        }

        MemberCard {
            SectionHeading("Accept an invitation")
            Text("Paste the invitation ID from the person who invited you. Acceptance is checked against your signed-in account.")
            OutlinedTextField(
                value = state.inviteIdToAccept,
                onValueChange = onInviteIdToAcceptChange,
                label = { Text("Invitation ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            state.inviteAcceptanceMessage?.let { Text(it) }
            PrimaryAction(
                "Accept emergency-contact invitation",
                onAcceptEmergencyContactInvite,
                Modifier.fillMaxWidth(),
                enabled = state.inviteIdToAccept.isNotBlank(),
            )
        }

        state.errorMessage?.let {
            StatusBanner(
                if (state.alertId == null) DataCondition.ERROR else DataCondition.OFFLINE,
                if (state.alertId == null) "SOS not submitted" else "SOS status unavailable",
                it,
            )
        }
        state.statusMessage?.let { StatusBanner(state.condition, "SOS status", it) }
        state.deliveryState?.let { delivery ->
            MemberCard {
                LabeledValue("Delivery state", delivery)
                Text("A submitted alert is not proof that a person has read it. The app reports the configured delivery state only.")
                if (state.canAcknowledge) state.alertId?.let { PrimaryAction("Acknowledge this SOS", { onAcknowledgeSos(it) }, Modifier.fillMaxWidth()) }
            }
        }

        val remaining = state.countdownSecondsRemaining
        if (remaining != null) {
            MemberCard {
                Text("Sending SOS in $remaining seconds", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                Text("You can cancel during this countdown. If it completes, the app submits an alert to the accepted Circle recipients above.")
                PrimaryAction("Cancel SOS", onCancelCountdown, Modifier.fillMaxWidth())
                if (remaining == 0) PrimaryAction("Submit alert now", { onCreateSos(state.cancelCountdownSeconds) }, Modifier.fillMaxWidth())
            }
        } else if (state.alertId == null) {
            MemberCard {
                Text("You will get a ${state.cancelCountdownSeconds}-second cancel countdown before the alert is submitted.")
                PrimaryAction("Start SOS countdown", onStartCountdown, Modifier.fillMaxWidth(), state.recipients.any { it.accepted })
            }
        }
    }
}

@Composable
fun PermissionDiagnosticsScreen(
    permissions: List<PermissionDiagnosticUi>,
    overallCondition: DataCondition,
    summary: String,
    onOpenSystemSettings: (permissionName: String?) -> Unit,
    onRetry: () -> Unit,
) {
    MemberScreen(title = "Permission diagnostics", subtitle = "Review what Android allows before enabling a feature.") {
        StatusBanner(overallCondition, "Device permissions", summary)
        if (permissions.isEmpty()) EmptyStateCard("Permission status unavailable", "Android permission diagnostics have not loaded.")
        permissions.forEach { permission ->
            MemberCard {
                SectionHeading(permission.name)
                LabeledValue("Status", permission.stateLabel)
                Text(permission.explanation)
                LabeledValue("Used for", permission.requiredForLabel)
                PrimaryAction("Open Android settings", { onOpenSystemSettings(permission.name) }, Modifier.fillMaxWidth())
            }
        }
        PrimaryAction("Retry diagnostics", onRetry, Modifier.fillMaxWidth())
    }
}

data class GuardianUiState(
    val childMemberId: String = "",
    val guardianUsername: String = "",
    val relationship: String = "parent",
    val requestCondition: DataCondition = DataCondition.UNKNOWN,
    val requestMessage: String = "",
    val verifiedGuardianAccountAvailable: Boolean = false,
)

data class AccountPrivacyUiState(
    val username: String,
    val sessionStateLabel: String,
    val sharingSummary: String,
    val guardian: GuardianUiState = GuardianUiState(),
    val exportCondition: DataCondition = DataCondition.UNKNOWN,
    val exportMessage: String = "No export request is pending.",
    val deletionCondition: DataCondition = DataCondition.UNKNOWN,
    val deletionMessage: String = "No deletion request is pending.",
    val deletionReason: String = "privacy_concern",
    val deletionDetails: String = "",
)

@Composable
fun AccountPrivacyScreen(
    state: AccountPrivacyUiState,
    onRequestDataExport: () -> Unit,
    onDeletionReasonChange: (String) -> Unit,
    onDeletionDetailsChange: (String) -> Unit,
    onRequestAccountDeletion: (reason: String, details: String, confirmationPhrase: String) -> Unit,
    onGuardianChildIdChange: (String) -> Unit,
    onGuardianUsernameChange: (String) -> Unit,
    onGuardianRelationshipChange: (String) -> Unit,
    onRequestGuardianConsent: (childMemberId: String, guardianUsername: String, relationship: String) -> Unit,
    onOpenHelp: () -> Unit,
    onLogout: () -> Unit,
) {
    MemberScreen(title = "Account and privacy", subtitle = "Control your data, sharing and help options.") {
        MemberCard {
            SectionHeading("Account")
            LabeledValue("Username", state.username)
            LabeledValue("Session", state.sessionStateLabel)
            LabeledValue("Sharing", state.sharingSummary)
        }
        MemberCard {
            SectionHeading("Your data")
            StatusBanner(state.exportCondition, "Data export", state.exportMessage)
            PrimaryAction("Request data export", onRequestDataExport, Modifier.fillMaxWidth())
        }
        MemberCard {
            SectionHeading("Delete account")
            StatusBanner(state.deletionCondition, "Deletion request", state.deletionMessage)
            Text("A request does not mean deletion has completed. Stop sharing separately if you need sharing to end immediately.")
            Text("Choose a reason")
            listOf(
                "no_longer_needed" to "No longer needed",
                "privacy_concern" to "Privacy concern",
                "switching_service" to "Switching service",
                "other" to "Other",
            ).forEach { (value, label) ->
                FilterChip(
                    selected = state.deletionReason == value,
                    onClick = { onDeletionReasonChange(value) },
                    label = { Text(label) },
                )
            }
            OutlinedTextField(
                state.deletionDetails,
                onDeletionDetailsChange,
                label = { Text("Optional details") },
                supportingText = { Text("Up to 500 characters. Avoid sensitive information.") },
                modifier = Modifier.fillMaxWidth(),
            )
            DestructiveActionButton(
                action = DestructiveAction(DestructiveActionKind.DELETE_ACCOUNT),
                label = "Request account deletion",
                onConfirmed = { onRequestAccountDeletion(state.deletionReason, state.deletionDetails.take(500), "DELETE MY ACCOUNT") },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.deletionReason in setOf("no_longer_needed", "privacy_concern", "switching_service", "other"),
            )
        }
        MemberCard {
            SectionHeading("Guardian consent")
            if (!state.guardian.verifiedGuardianAccountAvailable) {
                StatusBanner(
                    DataCondition.UNAVAILABLE,
                    "Guardian consent unavailable",
                    "The API does not yet provide a verified guardian identity and recipient approval flow.",
                )
            } else {
                Text("This flow supports a child account and an already-provisioned, verified guardian account.")
                OutlinedTextField(
                    state.guardian.childMemberId,
                    onGuardianChildIdChange,
                    label = { Text("Child member ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    state.guardian.guardianUsername,
                    onGuardianUsernameChange,
                    label = { Text("Guardian username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Relationship")
                listOf("parent", "guardian").forEach { relationship ->
                    FilterChip(
                        selected = state.guardian.relationship == relationship,
                        onClick = { onGuardianRelationshipChange(relationship) },
                        label = { Text(relationship.replaceFirstChar { it.uppercase() }) },
                    )
                }
                state.guardian.requestMessage.takeIf(String::isNotBlank)?.let {
                    StatusBanner(state.guardian.requestCondition, "Consent request", it)
                }
                PrimaryAction(
                    "Request guardian consent",
                    { onRequestGuardianConsent(state.guardian.childMemberId, state.guardian.guardianUsername, state.guardian.relationship) },
                    Modifier.fillMaxWidth(),
                    state.guardian.childMemberId.isNotBlank() && state.guardian.guardianUsername.isNotBlank(),
                )
            }
        }
        PrimaryAction("Open help", onOpenHelp, Modifier.fillMaxWidth())
        TextButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
    }
}

@Composable
fun FeatureAvailabilityNotice(
    featureName: String,
    condition: DataCondition,
    reason: String,
    alternative: String? = null,
    onLearnMore: (() -> Unit)? = null,
) {
    MemberCard {
        SectionHeading(featureName)
        StatusBanner(condition, "Currently unavailable", reason)
        alternative?.let { Text("Available option: $it") }
        onLearnMore?.let { TextButton(onClick = it) { Text("Learn more") } }
    }
}

@Composable
fun SafetyAvailabilityScreen(
    permissions: List<PermissionDiagnosticUi>,
    onOpenPermissionDiagnostics: () -> Unit,
    onOpenCircleMap: () -> Unit,
) {
    MemberScreen(title = "Safety features", subtitle = "Safety tools describe only what the app can verify.") {
        FeatureAvailabilityNotice(
            featureName = "Possible-crash alerts",
            condition = DataCondition.UNAVAILABLE,
            reason = "Detection is disabled until private testing and validation are complete. No crash has been detected by this screen.",
            alternative = "Use Circle SOS if you want to send an in-app alert to your accepted recipients.",
        )
        FeatureAvailabilityNotice(
            featureName = "Weather, flight and ride provider feeds",
            condition = DataCondition.UNAVAILABLE,
            reason = "A suitable free India provider integration has not been verified for these feeds.",
            alternative = "Coordinate a ride with Circle events, check-ins and Quick Notes.",
        )
        FeatureAvailabilityNotice(
            featureName = "Dedicated lost-phone lookup",
            condition = DataCondition.UNAVAILABLE,
            reason = "The current API has no dedicated lost-phone lookup action or device-finding network integration.",
            alternative = "Open a Circle map to see a consenting member’s latest shared location when it is available.",
        )
        PrimaryAction("Open Circle map", onOpenCircleMap, Modifier.fillMaxWidth())
        if (permissions.isNotEmpty()) {
            SectionHeading("Permission overview")
            permissions.forEach { LabeledValue(it.name, it.stateLabel) }
        }
        PrimaryAction("Review permissions", onOpenPermissionDiagnostics, Modifier.fillMaxWidth())
    }
}
