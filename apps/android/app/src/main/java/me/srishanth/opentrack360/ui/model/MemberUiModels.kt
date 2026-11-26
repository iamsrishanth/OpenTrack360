package me.srishanth.opentrack360.ui.model

/** UI-only state. Network and persistence code belongs in the app layer. */
sealed interface MemberAccessState {
    data object SignedOut : MemberAccessState
    data class RotationRequired(val username: String) : MemberAccessState
    data object ReauthenticationRequired : MemberAccessState
    data class Authenticated(val userId: String, val username: String) : MemberAccessState
    data object Disabled : MemberAccessState
}

object MemberAccessPolicy {
    fun afterLogin(userId: String, username: String, mustRotatePassword: Boolean): MemberAccessState =
        if (mustRotatePassword) MemberAccessState.RotationRequired(username)
        else MemberAccessState.Authenticated(userId, username)

    /** Password changes revoke the old session. The new session must be established by login. */
    fun afterPasswordChange(): MemberAccessState = MemberAccessState.ReauthenticationRequired

    fun afterLogout(): MemberAccessState = MemberAccessState.SignedOut

    fun afterFreshLogin(userId: String, username: String, mustRotatePassword: Boolean): MemberAccessState =
        afterLogin(userId, username, mustRotatePassword)

    fun canNavigateToMemberData(state: MemberAccessState): Boolean = state is MemberAccessState.Authenticated
}

enum class DataCondition { CONFIRMED, PENDING, OFFLINE, UNKNOWN, UNAVAILABLE, ERROR }

enum class DestructiveActionKind {
    PAUSE_CIRCLE_SHARING,
    PAUSE_ACTIVITY_SHARING,
    STOP_ALL_SHARING,
    REMOVE_CIRCLE_MEMBER,
    LEAVE_CIRCLE,
    STOP_TRIP,
    REVOKE_PET_RECOVERY_LINK,
    REVOKE_LOST_PET_REPORT,
    DELETE_PLACE,
    DELETE_PLACE_RULE,
    END_BUBBLE,
    DELETE_PET_AND_CARE_RECORDS,
    DELETE_ACCOUNT,
}

data class DestructiveAction(val kind: DestructiveActionKind, val targetName: String? = null)

data class DestructiveConfirmation(
    val action: DestructiveAction,
    val title: String,
    val scope: String,
    val confirmLabel: String,
)

sealed interface DestructiveConfirmationState {
    data object Idle : DestructiveConfirmationState
    data class Awaiting(val confirmation: DestructiveConfirmation) : DestructiveConfirmationState
    data class Confirmed(val action: DestructiveAction) : DestructiveConfirmationState
}

object DestructiveActionPolicy {
    fun confirmationFor(action: DestructiveAction): DestructiveConfirmation {
        val target = action.targetName?.takeIf(String::isNotBlank)
        return when (action.kind) {
            DestructiveActionKind.PAUSE_CIRCLE_SHARING -> DestructiveConfirmation(
                action, "Pause Circle sharing?", "Your location will stop updating for ${target ?: "this Circle"}. Other Circles and active guest links are not included.", "Pause sharing"
            )
            DestructiveActionKind.PAUSE_ACTIVITY_SHARING -> DestructiveConfirmation(
                action, "Pause activity sharing?", "Your activity labels will stop being shared with ${target ?: "this Circle"}. Location sharing is controlled separately.", "Pause activity sharing"
            )
            DestructiveActionKind.STOP_ALL_SHARING -> DestructiveConfirmation(
                action, "Stop all sharing?", "Location sharing will stop across every Circle, active trip, and guest link on this account.", "Stop all sharing"
            )
            DestructiveActionKind.REMOVE_CIRCLE_MEMBER -> DestructiveConfirmation(
                action, "Remove this member?", "${target ?: "This member"} will lose access to this Circle and its history immediately.", "Remove member"
            )
            DestructiveActionKind.LEAVE_CIRCLE -> DestructiveConfirmation(
                action, "Leave this Circle?", "You will lose access to ${target ?: "this Circle"} and its history immediately.", "Leave Circle"
            )
            DestructiveActionKind.STOP_TRIP -> DestructiveConfirmation(
                action, "End this trip?", "Trip progress will stop for ${target ?: "this trip"}. Circle sharing and guest links remain active.", "End trip"
            )
            DestructiveActionKind.REVOKE_PET_RECOVERY_LINK -> DestructiveConfirmation(
                action, "Revoke recovery link?", "Anyone holding the recovery link for ${target ?: "this pet"} will lose access.", "Revoke link"
            )
            DestructiveActionKind.REVOKE_LOST_PET_REPORT -> DestructiveConfirmation(
                action, "Revoke lost-pet report?", "The report for ${target ?: "this pet"} will stop being visible to the selected Circles.", "Revoke report"
            )
            DestructiveActionKind.DELETE_PLACE -> DestructiveConfirmation(
                action, "Delete this Place?", "${target ?: "This Place"} and its arrival, departure, and No Show rules will be removed from this Circle.", "Delete Place"
            )
            DestructiveActionKind.DELETE_PLACE_RULE -> DestructiveConfirmation(
                action, "Delete this alert rule?", "${target ?: "This rule"} will stop sending alerts for this Place.", "Delete rule"
            )
            DestructiveActionKind.END_BUBBLE -> DestructiveConfirmation(
                action, "End this Bubble?", "The approximate area for ${target ?: "this Circle"} will stop being shared.", "End Bubble"
            )
            DestructiveActionKind.DELETE_PET_AND_CARE_RECORDS -> DestructiveConfirmation(
                action, "Delete this pet profile?", "${target ?: "This pet profile"}, its care routines and activity records will be deleted. Recovery access will end.", "Delete profile"
            )
            DestructiveActionKind.DELETE_ACCOUNT -> DestructiveConfirmation(
                action, "Request account deletion?", "A deletion request will be sent for your account and personal data. Confirming sends the exact phrase DELETE MY ACCOUNT. This request may take time to complete; sharing should be stopped separately if it needs to end now.", "Request deletion"
            )
        }
    }

    fun request(action: DestructiveAction): DestructiveConfirmationState =
        DestructiveConfirmationState.Awaiting(confirmationFor(action))

    fun cancel(): DestructiveConfirmationState = DestructiveConfirmationState.Idle

    fun confirm(state: DestructiveConfirmationState): DestructiveConfirmationState = when (state) {
        is DestructiveConfirmationState.Awaiting -> DestructiveConfirmationState.Confirmed(state.confirmation.action)
        else -> state
    }

    fun actionReadyForCallback(state: DestructiveConfirmationState): DestructiveAction? =
        (state as? DestructiveConfirmationState.Confirmed)?.action
}

data class MemberLocationUi(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val capturedAtLabel: String,
    val precisionLabel: String,
    val capturedAtIso: String = "",
)

data class CircleMemberUi(
    val id: String,
    val displayName: String,
    val roleLabel: String,
    val sharingLabel: String,
    val precision: String = "unknown",
    val location: MemberLocationUi? = null,
    val batteryPercent: Int? = null,
    val activityLabel: String? = null,
    val directionsEtaLabel: String? = null,
)

data class CircleChoiceUi(
    val id: String,
    val name: String,
    val kindLabel: String,
    val roleLabel: String,
    val expiresAtLabel: String? = null,
)

data class PlaceArrivalUi(val status: DataCondition, val detail: String)

data class PlaceUi(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int,
    val arrival: PlaceArrivalUi,
)

data class NoShowRuleUi(
    val id: String,
    val expectedAtLabel: String,
    val graceMinutes: Int,
    val recurrenceLabel: String?,
    val outcome: PlaceArrivalUi,
)

data class PetUi(
    val id: String,
    val name: String,
    val speciesLabel: String,
    val photoUrl: String? = null,
    val sharedCircleNames: List<String> = emptyList(),
    val recoveryEnabled: Boolean = false,
)

data class PetRoutineUi(val id: String, val title: String, val scheduleLabel: String, val lastCompletedLabel: String?)

data class PermissionDiagnosticUi(val name: String, val stateLabel: String, val explanation: String, val requiredForLabel: String)

data class AudienceChoiceUi(val id: String, val label: String, val selected: Boolean)
