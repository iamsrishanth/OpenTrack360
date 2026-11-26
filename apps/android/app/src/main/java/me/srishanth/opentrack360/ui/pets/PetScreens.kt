package me.srishanth.opentrack360.ui.pets

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
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
import me.srishanth.opentrack360.ui.model.AudienceChoiceUi
import me.srishanth.opentrack360.ui.model.PetRoutineUi
import me.srishanth.opentrack360.ui.model.PetUi
import java.time.Instant
import java.time.LocalTime

data class PetActivityUi(val label: String, val timeLabel: String, val memberLabel: String?)

@Composable
fun PetProfilesScreen(
    pets: List<PetUi>,
    condition: DataCondition,
    message: String,
    circleChoices: List<Pair<String, String>>,
    name: String,
    species: String,
    selectedCircleIds: Set<String>,
    onNameChange: (String) -> Unit,
    onSpeciesChange: (String) -> Unit,
    onCircleSelected: (circleId: String, selected: Boolean) -> Unit,
    onCreatePet: (name: String, species: String, circleIds: List<String>) -> Unit,
    onOpenPet: (petId: String) -> Unit,
) {
    MemberScreen(title = "Pet profiles", subtitle = "Share pet details with Circles you choose.") {
        StatusBanner(condition, "Pet profiles", message)
        if (pets.isEmpty()) EmptyStateCard("No pet profiles", "Pet profiles and care routines are optional.")
        pets.forEach { pet ->
            MemberCard {
                SectionHeading(pet.name)
                LabeledValue("Species", pet.speciesLabel)
                LabeledValue("Shared with", pet.sharedCircleNames.takeIf { it.isNotEmpty() }?.joinToString() ?: "No Circles")
                LabeledValue("Software recovery link", if (pet.recoveryEnabled) "Enabled" else "Not enabled")
                PrimaryAction("Open ${pet.name}", { onOpenPet(pet.id) }, Modifier.fillMaxWidth())
            }
        }
        MemberCard {
            SectionHeading("Add a pet")
            OutlinedTextField(name, onNameChange, label = { Text("Pet name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(species, onSpeciesChange, label = { Text("Species") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Choose which Circles can see this profile.")
            circleChoices.forEach { (id, label) ->
                FilterChip(
                    selected = id in selectedCircleIds,
                    onClick = { onCircleSelected(id, id !in selectedCircleIds) },
                    label = { Text(label) },
                )
            }
            PrimaryAction(
                "Create pet profile",
                { onCreatePet(name.trim(), species.trim(), selectedCircleIds.toList()) },
                Modifier.fillMaxWidth(),
                name.isNotBlank() && species.isNotBlank() && selectedCircleIds.isNotEmpty(),
            )
        }
    }
}

data class PetDetailUiState(
    val pet: PetUi,
    val isOwner: Boolean = false,
    val routines: List<PetRoutineUi> = emptyList(),
    val activity: List<PetActivityUi> = emptyList(),
    val condition: DataCondition = DataCondition.UNKNOWN,
    val message: String = "Pet data has not loaded.",
    val recoveryFields: Set<String> = emptySet(),
    val ownerMessage: String = "",
    val contactRoute: String = "",
    val recoveryLinkCreatedAt: String? = null,
    val activeLostPetReportId: String? = null,
)

@Composable
fun PetDetailScreen(
    state: PetDetailUiState,
    recoveryQrBitmap: ImageBitmap? = null,
    routineTitle: String,
    routineSchedule: String,
    routineKind: String,
    routineDueAt: String,
    routineRepeat: String,
    routineLocalTime: String,
    notificationsEnabled: Boolean,
    onRoutineTitleChange: (String) -> Unit,
    onRoutineScheduleChange: (String) -> Unit,
    onRoutineKindChange: (String) -> Unit,
    onRoutineDueAtChange: (String) -> Unit,
    onRoutineRepeatChange: (String) -> Unit,
    onRoutineLocalTimeChange: (String) -> Unit,
    onRecoveryFieldSelected: (field: String, selected: Boolean) -> Unit,
    onOwnerMessageChange: (String) -> Unit,
    onContactRouteChange: (String) -> Unit,
    onUpdateRecoverySettings: (petId: String, enabled: Boolean, fields: List<String>, ownerMessage: String, contactRoute: String) -> Unit,
    onOpenPhotoPicker: () -> Unit,
    onCreateRoutine: (petId: String, title: String, kind: String, notes: String, dueAt: String?, repeat: String?, localTime: String?) -> Unit,
    onCompleteRoutine: (petId: String, routineId: String) -> Unit,
    onCreateRecoveryLink: (petId: String) -> Unit,
    onRevokeRecoveryLink: (petId: String) -> Unit,
    onCreateLostPetReport: (petId: String) -> Unit,
    onRevokeLostPetReport: (reportId: String) -> Unit,
    onDeletePet: (petId: String) -> Unit,
    onBack: () -> Unit,
) {
    val pet = state.pet
    MemberScreen(title = pet.name, subtitle = "${pet.speciesLabel} · shared with ${pet.sharedCircleNames.takeIf { it.isNotEmpty() }?.joinToString() ?: "no Circles"}") {
        StatusBanner(state.condition, "Pet profile", state.message)
        MemberCard {
            LabeledValue("Photo", if (pet.photoUrl.isNullOrBlank()) "No photo" else "Photo available")
            pet.photoUrl?.let { PetPhotoImage(it, "${pet.name} photo", Modifier.fillMaxWidth().height(220.dp)) }
            if (state.isOwner) {
                PrimaryAction("Add or update photo", onOpenPhotoPicker, Modifier.fillMaxWidth())
                LabeledValue("Recovery card", if (pet.recoveryEnabled) "Enabled" else "Not enabled")
                if (state.recoveryFields.isNotEmpty()) {
                    LabeledValue("Fields visible to link holders", state.recoveryFields.joinToString())
                } else {
                    Text("No recovery fields are selected. A recovery link should expose only owner-approved fields.")
                }
                state.recoveryLinkCreatedAt?.let { LabeledValue("Last link created", it) }
                Text("Anyone holding an active recovery link can view its approved pet details.")
                recoveryQrBitmap?.let { qr ->
                    SectionHeading("Software recovery QR")
                    Image(qr, contentDescription = "Owner-approved pet recovery QR code", modifier = Modifier.size(220.dp))
                    Text("Anyone who scans this code can access the pet fields selected above until you revoke the link.")
                }
                Text("Choose which fields the software-only recovery card can show.")
                listOf("petName", "species", "photo", "ownerMessage", "contactRoute").forEach { field ->
                    FilterChip(
                        selected = field in state.recoveryFields,
                        onClick = { onRecoveryFieldSelected(field, field !in state.recoveryFields) },
                        label = { Text(field) },
                    )
                }
                OutlinedTextField(
                    value = state.ownerMessage,
                    onValueChange = onOwnerMessageChange,
                    label = { Text("Message for a person who finds your pet") },
                    supportingText = { Text("Shown only when Owner message is selected above; max 500 characters.") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4,
                )
                OutlinedTextField(
                    value = state.contactRoute,
                    onValueChange = onContactRouteChange,
                    label = { Text("How someone can reach you") },
                    supportingText = { Text("Shown only when Contact route is selected above; max 200 characters.") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                PrimaryAction(
                    "Save recovery privacy settings",
                    { onUpdateRecoverySettings(pet.id, true, state.recoveryFields.toList(), state.ownerMessage.take(500), state.contactRoute.take(200)) },
                    Modifier.fillMaxWidth(),
                    state.recoveryFields.isNotEmpty(),
                )
                PrimaryAction(
                    if (pet.recoveryEnabled) "Create or rotate recovery link" else "Enable software recovery link",
                    { onCreateRecoveryLink(pet.id) },
                    Modifier.fillMaxWidth(),
                    pet.recoveryEnabled && state.recoveryFields.isNotEmpty(),
                )
                if (pet.recoveryEnabled) {
                    DestructiveActionButton(
                        action = DestructiveAction(DestructiveActionKind.REVOKE_PET_RECOVERY_LINK, pet.name),
                        label = "Revoke recovery link",
                        onConfirmed = { onRevokeRecoveryLink(pet.id) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                PrimaryAction("Report ${pet.name} lost", { onCreateLostPetReport(pet.id) }, Modifier.fillMaxWidth())
                state.activeLostPetReportId?.let { reportId ->
                    DestructiveActionButton(
                        action = DestructiveAction(DestructiveActionKind.REVOKE_LOST_PET_REPORT, pet.name),
                        label = "Revoke lost-pet report",
                        onConfirmed = { onRevokeLostPetReport(reportId) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Text("Only the pet owner can change recovery settings or create recovery and lost-pet links.")
            }
        }
        SectionHeading("Care routines")
        if (!notificationsEnabled) {
            StatusBanner(
                DataCondition.PENDING,
                "Notifications are off",
                "Android notification access is disabled. Scheduled care reminders cannot alert until notifications are enabled in system settings.",
            )
        }
        if (state.routines.isEmpty()) EmptyStateCard("No routines", "Add a feeding, walk, medication, grooming, or other care reminder.")
        state.routines.forEach { routine ->
            MemberCard {
                SectionHeading(routine.title)
                LabeledValue("Schedule", routine.scheduleLabel)
                routine.lastCompletedLabel?.let { LabeledValue("Last completed", it) }
                PrimaryAction("Mark complete", { onCompleteRoutine(pet.id, routine.id) }, Modifier.fillMaxWidth())
            }
        }
        MemberCard {
            OutlinedTextField(routineTitle, onRoutineTitleChange, label = { Text("Routine name") }, modifier = Modifier.fillMaxWidth())
            Text("Android may delay reminders during battery saver or Doze. Notifications must be enabled in system settings.")
            Text("Care type")
            listOf("feeding", "medication", "walk", "grooming", "other").forEach { kind ->
                FilterChip(routineKind == kind, { onRoutineKindChange(kind) }, label = { Text(kind.replaceFirstChar { it.uppercase() }) })
            }
            OutlinedTextField(routineSchedule, onRoutineScheduleChange, label = { Text("Notes (optional)") }, modifier = Modifier.fillMaxWidth())
            SectionHeading("Repeat")
            Row {
                listOf("none" to "Once", "daily" to "Daily", "weekly" to "Weekly").forEach { (value, label) ->
                    FilterChip(
                        selected = routineRepeat == value,
                        onClick = { onRoutineRepeatChange(value) },
                        label = { Text(label) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
            OutlinedTextField(
                routineDueAt,
                onRoutineDueAtChange,
                label = { Text(if (routineRepeat == "none") "Reminder time (UTC ISO 8601, optional)" else "First reminder time (UTC ISO 8601, optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val localTimeValid = routineRepeat == "none" || runCatching { LocalTime.parse(routineLocalTime) }.isSuccess
            if (routineRepeat != "none") {
                OutlinedTextField(
                    routineLocalTime,
                    onRoutineLocalTimeChange,
                    label = { Text("Repeat time (24-hour local time)") },
                    supportingText = { Text("Weekly reminders repeat on the weekday of the first reminder, or today when no first date is set.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val dueAtValid = routineDueAt.isBlank() || runCatching { Instant.parse(routineDueAt) }.isSuccess
            PrimaryAction(
                "Add care reminder",
                {
                    onCreateRoutine(
                        pet.id,
                        routineTitle.trim(),
                        routineKind,
                        routineSchedule.trim(),
                        routineDueAt.trim().takeIf(String::isNotBlank),
                        routineRepeat.takeIf { it != "none" },
                        routineLocalTime.takeIf { routineRepeat != "none" },
                    )
                },
                Modifier.fillMaxWidth(),
                routineTitle.isNotBlank() && dueAtValid && localTimeValid,
            )
        }
        SectionHeading("Activity")
        if (state.activity.isEmpty()) EmptyStateCard("No activity yet", "Routine completion history will appear when available.")
        state.activity.forEach { item ->
            MemberCard {
                Text(item.label)
                LabeledValue("Time", item.timeLabel)
                item.memberLabel?.let { LabeledValue("Member", it) }
            }
        }
        if (state.isOwner) {
            DestructiveActionButton(
                action = DestructiveAction(DestructiveActionKind.DELETE_PET_AND_CARE_RECORDS, pet.name),
                label = "Delete pet profile",
                onConfirmed = { onDeletePet(pet.id) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        androidx.compose.material3.TextButton(onClick = onBack) { Text("Back to pets") }
    }
}

@Composable
fun LostPetReportScreen(
    petName: String,
    audience: List<AudienceChoiceUi>,
    expiresAt: String,
    reportMessage: String,
    statusMessage: String?,
    onAudienceSelected: (recipientId: String, selected: Boolean) -> Unit,
    onExpiresAtChange: (String) -> Unit,
    onReportMessageChange: (String) -> Unit,
    onSubmitReport: (recipientCircleIds: List<String>, message: String, expiresAt: String) -> Unit,
    onCancel: () -> Unit,
) {
    MemberScreen(title = "Report $petName lost", subtitle = "This report is sent only to the recipients you select.") {
        StatusBanner(
            DataCondition.PENDING,
            "Limited audience",
            "OpenTrack360 does not contact a third-party pet search network. Review the recipient list before sending.",
        )
        MemberCard {
            SectionHeading("Recipients")
            if (audience.isEmpty()) EmptyStateCard("No recipients available", "Select Circle members before sending a lost-pet report.")
            audience.forEach { recipient ->
                FilterChip(
                    selected = recipient.selected,
                    onClick = { onAudienceSelected(recipient.id, !recipient.selected) },
                    label = { Text(recipient.label) },
                )
            }
            Text("Selected Circles: ${audience.filter { it.selected }.joinToString { it.label }.ifBlank { "None" }}")
            OutlinedTextField(reportMessage, onReportMessageChange, label = { Text("Message") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                expiresAt,
                onExpiresAtChange,
                label = { Text("Report expires (ISO 8601)") },
                supportingText = { Text("Lost-pet reports expire within 72 hours.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            statusMessage?.let { StatusBanner(DataCondition.PENDING, "Report status", it) }
            PrimaryAction(
                "Send report to selected Circles",
                { onSubmitReport(audience.filter { it.selected }.map { it.id }, reportMessage, expiresAt) },
                Modifier.fillMaxWidth(),
                audience.any { it.selected } && expiresAt.isNotBlank(),
            )
            androidx.compose.material3.TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}

data class PublicPetRecoveryUiState(
    val petName: String? = null,
    val species: String? = null,
    val ownerMessage: String? = null,
    val photoUrl: String? = null,
    val contactRoute: String? = null,
    val condition: DataCondition = DataCondition.UNKNOWN,
    val message: String = "Recovery details have not been validated.",
    val submitting: Boolean = false,
    val submissionMessage: String? = null,
    val errorMessage: String? = null,
)

@Composable
fun PublicPetRecoveryScreen(
    state: PublicPetRecoveryUiState,
    sightingMessage: String,
    latitude: String,
    longitude: String,
    accuracyMeters: String,
    contactRoute: String,
    onSightingMessageChange: (String) -> Unit,
    onLatitudeChange: (String) -> Unit,
    onLongitudeChange: (String) -> Unit,
    onAccuracyChange: (String) -> Unit,
    onContactRouteChange: (String) -> Unit,
    onSubmitSighting: (message: String, latitude: Double?, longitude: Double?, accuracyMeters: Double?, contactRoute: String?) -> Unit,
) {
    val latValue = latitude.toDoubleOrNull()
    val lonValue = longitude.toDoubleOrNull()
    val accuracyValue = accuracyMeters.toDoubleOrNull()
    val locationValid = (latitude.isBlank() && longitude.isBlank()) ||
        (latValue != null && latValue in -90.0..90.0 && lonValue != null && lonValue in -180.0..180.0 && (accuracyValue == null || accuracyValue >= 0.0))
    MemberScreen(title = state.petName ?: "Pet recovery", subtitle = "Limited details selected by the pet owner.") {
        StatusBanner(state.condition, "Recovery card", state.message)
        if (state.condition == DataCondition.CONFIRMED) {
            MemberCard {
                state.species?.let { LabeledValue("Species", it) }
                state.photoUrl?.let { PetPhotoImage(it, "Owner-approved pet photo", Modifier.fillMaxWidth().height(220.dp)) }
                state.ownerMessage?.let { Text(it) }
                state.contactRoute?.let { LabeledValue("Owner-selected contact", it) }
                Text("This link does not reveal Circle membership, private location history or a phone number by default.")
            }
        }
        MemberCard {
            SectionHeading("Send a sighting")
            Text("Your sighting is shared with the pet owner’s selected recipients.")
            OutlinedTextField(
                sightingMessage,
                onSightingMessageChange,
                label = { Text("What did you see?") },
                supportingText = { Text("A message is required. Do not include sensitive information.") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(latitude, onLatitudeChange, label = { Text("Latitude (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(longitude, onLongitudeChange, label = { Text("Longitude (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(accuracyMeters, onAccuracyChange, label = { Text("Accuracy in metres (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(contactRoute, onContactRouteChange, label = { Text("How the owner can reach you (optional)") }, modifier = Modifier.fillMaxWidth())
            state.submissionMessage?.let { StatusBanner(DataCondition.PENDING, "Sighting status", it) }
            state.errorMessage?.let { StatusBanner(DataCondition.ERROR, "Sighting not submitted", it) }
            PrimaryAction(
                if (state.submitting) "Submitting…" else "Send sighting",
                onClick = { onSubmitSighting(sightingMessage.trim(), latValue, lonValue, accuracyValue, contactRoute.trim().takeIf(String::isNotBlank)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.submitting && sightingMessage.isNotBlank() && locationValid,
            )
        }
    }
}
