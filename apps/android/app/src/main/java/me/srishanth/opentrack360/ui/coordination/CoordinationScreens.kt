package me.srishanth.opentrack360.ui.coordination

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import java.util.UUID
import java.util.Locale
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
import me.srishanth.opentrack360.ui.model.DataCondition
import me.srishanth.opentrack360.ui.model.DestructiveAction
import me.srishanth.opentrack360.ui.model.DestructiveActionKind
import me.srishanth.opentrack360.ui.model.AudienceChoiceUi
import me.srishanth.opentrack360.ui.model.NoShowRuleUi
import me.srishanth.opentrack360.ui.model.PlaceArrivalUi
import me.srishanth.opentrack360.ui.model.PlaceUi
import me.srishanth.opentrack360.ui.safety.FeatureAvailabilityNotice
import me.srishanth.opentrack360.tracking.DriveTrip
import me.srishanth.opentrack360.tracking.TripRole

data class PlacesUiState(
    val places: List<PlaceUi> = emptyList(),
    val condition: DataCondition = DataCondition.UNKNOWN,
    val message: String = "Place data has not loaded.",
)

@Composable
fun PlacesScreen(
    state: PlacesUiState,
    onCreatePlace: () -> Unit,
    onOpenPlace: (placeId: String) -> Unit,
) {
    MemberScreen(title = "Places", subtitle = "Circle members choose what location sharing they allow.") {
        StatusBanner(state.condition, "Place list", state.message)
        if (state.places.isEmpty()) EmptyStateCard("No saved Places", "A Place can have arrival and departure alerts or a No Show rule.")
        state.places.forEach { place ->
            MemberCard {
                SectionHeading(place.name)
                LabeledValue("Geofence", "${place.radiusMeters} m")
                PlaceOutcomeBanner(place.arrival)
                PrimaryAction("Open Place map and rules", { onOpenPlace(place.id) }, Modifier.fillMaxWidth())
            }
        }
        PrimaryAction("Add a Place", onCreatePlace, Modifier.fillMaxWidth())
    }
}

data class PlaceDetailUiState(
    val place: PlaceUi,
    val noShowRules: List<NoShowRuleUi> = emptyList(),
    val ruleCondition: DataCondition = DataCondition.UNKNOWN,
    val ruleMessage: String = "Place rules have not loaded.",
)

data class PlaceFormUiState(
    val name: String = "",
    val latitude: String = "",
    val longitude: String = "",
    val radiusMeters: String = "150",
    val alertOnArrival: Boolean = true,
    val alertOnDeparture: Boolean = false,
    val saving: Boolean = false,
    val errorMessage: String? = null,
)

@Composable
fun PlaceEditorScreen(
    state: PlaceFormUiState,
    mapContent: @Composable () -> Unit,
    onNameChange: (String) -> Unit,
    onLatitudeChange: (String) -> Unit,
    onLongitudeChange: (String) -> Unit,
    onRadiusChange: (String) -> Unit,
    onArrivalAlertChange: (Boolean) -> Unit,
    onDepartureAlertChange: (Boolean) -> Unit,
    onSavePlace: (name: String, latitude: Double, longitude: Double, radiusMeters: Int, alertOnArrival: Boolean, alertOnDeparture: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    val latitude = state.latitude.toDoubleOrNull()
    val longitude = state.longitude.toDoubleOrNull()
    val radius = state.radiusMeters.toIntOrNull()
    MemberScreen(title = "Save a Place", subtitle = "Place coordinates and radius are private Circle data.") {
        MapSlot(mapContent, Modifier.fillMaxWidth().height(270.dp))
        MemberCard {
            OutlinedTextField(state.name, onNameChange, label = { Text("Place name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(state.latitude, onLatitudeChange, label = { Text("Latitude") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(state.longitude, onLongitudeChange, label = { Text("Longitude") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                state.radiusMeters,
                onRadiusChange,
                label = { Text("Radius in metres") },
                supportingText = { Text("Allowed range: 50–5,000 m") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Arrival and departure alerts")
            FilterChip(state.alertOnArrival, { onArrivalAlertChange(!state.alertOnArrival) }, label = { Text("Arrival alert") })
            FilterChip(state.alertOnDeparture, { onDepartureAlertChange(!state.alertOnDeparture) }, label = { Text("Departure alert") })
            state.errorMessage?.let { StatusBanner(DataCondition.ERROR, "Place not saved", it) }
            PrimaryAction(
                if (state.saving) "Saving…" else "Save Place",
                onClick = { onSavePlace(state.name.trim(), latitude!!, longitude!!, radius!!, state.alertOnArrival, state.alertOnDeparture) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.saving && state.name.isNotBlank() && latitude != null && latitude in -90.0..90.0 && longitude != null && longitude in -180.0..180.0 && radius != null && radius in 50..5000,
            )
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
        FeatureAvailabilityNotice(
            featureName = "Place suggestions",
            condition = DataCondition.UNAVAILABLE,
            reason = "No suitable free India provider has been verified for automatic Place suggestions.",
            alternative = "Save a Place by choosing its location on the map or entering its coordinates.",
        )
    }
}

data class PlaceRuleFormUiState(
    val kind: String = "no_show",
    val expectedAt: String = "",
    val graceMinutes: String = "15",
    val recurrenceEnabled: Boolean = false,
    val frequency: String = "weekly",
    val interval: String = "1",
    val weekdays: Set<Int> = emptySet(),
    val localTime: String = "08:00",
    val timeZone: String = "Asia/Kolkata",
    val selectedMemberIds: Set<String> = emptySet(),
    val saving: Boolean = false,
    val errorMessage: String? = null,
)

data class PlaceRuleRecurrenceUi(
    val frequency: String,
    val interval: Int,
    val weekdays: List<Int>,
    val localTime: String,
    val timeZone: String,
)

@Composable
fun PlaceRuleEditorScreen(
    placeName: String,
    state: PlaceRuleFormUiState,
    recipients: List<AudienceChoiceUi>,
    onKindChange: (String) -> Unit,
    onExpectedAtChange: (String) -> Unit,
    onGraceMinutesChange: (String) -> Unit,
    onRecurrenceEnabledChange: (Boolean) -> Unit,
    onFrequencyChange: (String) -> Unit,
    onIntervalChange: (String) -> Unit,
    onWeekdayChange: (Int, Boolean) -> Unit,
    onLocalTimeChange: (String) -> Unit,
    onTimeZoneChange: (String) -> Unit,
    onMemberSelected: (String, Boolean) -> Unit,
    onSaveRule: (kind: String, memberIds: List<String>, expectedAt: String?, graceMinutes: Int, recurrence: PlaceRuleRecurrenceUi?) -> Unit,
    onCancel: () -> Unit,
) {
    val grace = state.graceMinutes.toIntOrNull()
    val interval = state.interval.toIntOrNull()
    val kindValid = state.kind in setOf("arrival", "departure", "no_show")
    val recurrenceValid = !state.recurrenceEnabled || (state.frequency in setOf("daily", "weekly") && interval != null && interval in 1..52 && state.localTime.matches(Regex("^([01]?[0-9]|2[0-3]):[0-5][0-9]$")) && state.timeZone.isNotBlank() && (state.frequency != "weekly" || state.weekdays.isNotEmpty()))
    MemberScreen(title = "Place alerts", subtitle = "Rules for $placeName") {
        MemberCard {
            Text("Alert type")
            listOf("arrival", "departure", "no_show").forEach { kind ->
                FilterChip(state.kind == kind, { onKindChange(kind) }, label = { Text(kind.replace('_', ' ').replaceFirstChar { it.uppercase() }) })
            }
            if (state.kind == "no_show") {
                OutlinedTextField(state.expectedAt, onExpectedAtChange, label = { Text("Expected arrival (ISO 8601)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(state.graceMinutes, onGraceMinutesChange, label = { Text("Grace period in minutes") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("If arrival cannot be confirmed after the grace period, selected recipients get a No Show alert with the available reason.")
                FilterChip(state.recurrenceEnabled, { onRecurrenceEnabledChange(!state.recurrenceEnabled) }, label = { Text("Repeat this rule") })
                if (state.recurrenceEnabled) {
                    FilterChip(state.frequency == "daily", { onFrequencyChange("daily") }, label = { Text("Daily") })
                    FilterChip(state.frequency == "weekly", { onFrequencyChange("weekly") }, label = { Text("Weekly") })
                    OutlinedTextField(state.interval, onIntervalChange, label = { Text("Repeat interval") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (state.frequency == "weekly") {
                        Text("Days of week (1 Monday – 7 Sunday)")
                        (1..7).forEach { day ->
                            FilterChip(day in state.weekdays, { onWeekdayChange(day, day !in state.weekdays) }, label = { Text(day.toString()) })
                        }
                    }
                    OutlinedTextField(state.localTime, onLocalTimeChange, label = { Text("Local time (HH:mm)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(state.timeZone, onTimeZoneChange, label = { Text("Time zone") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
            Text("Recipients (leave all unselected to include all consenting Circle members)")
            recipients.forEach { recipient ->
                FilterChip(
                    recipient.id in state.selectedMemberIds,
                    { onMemberSelected(recipient.id, recipient.id !in state.selectedMemberIds) },
                    label = { Text(recipient.label) },
                )
            }
            state.errorMessage?.let { StatusBanner(DataCondition.ERROR, "Rule not saved", it) }
            PrimaryAction(
                if (state.saving) "Saving…" else "Save alert rule",
                onClick = {
                    val recurrence = if (state.recurrenceEnabled) PlaceRuleRecurrenceUi(
                        frequency = state.frequency,
                        interval = interval!!,
                        weekdays = state.weekdays.sorted(),
                        localTime = state.localTime,
                        timeZone = state.timeZone,
                    ) else null
                    onSaveRule(state.kind, state.selectedMemberIds.toList(), state.expectedAt.takeIf { state.kind == "no_show" }, grace ?: 15, recurrence)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.saving && kindValid && grace != null && grace in 0..1440 && (state.kind != "no_show" || state.expectedAt.isNotBlank()) && recurrenceValid,
            )
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}

@Composable
fun PlaceMapDetailScreen(
    state: PlaceDetailUiState,
    mapContent: @Composable () -> Unit,
    onUpdatePlace: (placeId: String) -> Unit,
    onDeletePlace: (placeId: String) -> Unit,
    onUpdatePlaceRule: (placeId: String, ruleId: String) -> Unit,
    onDeletePlaceRule: (placeId: String, ruleId: String) -> Unit,
    onCreateArrivalDepartureRule: (placeId: String) -> Unit,
    onCreateNoShowRule: (placeId: String) -> Unit,
    onBack: () -> Unit,
) {
    val place = state.place
    MemberScreen(title = place.name, subtitle = "${place.radiusMeters} m geofence · arrival and departure are confirmed only from valid location data.") {
        MapSlot(mapContent, Modifier.fillMaxWidth().height(270.dp))
        PlaceOutcomeBanner(place.arrival)
        MemberCard {
            LabeledValue("Radius", "${place.radiusMeters} m")
            LabeledValue("Center", "${place.latitude}, ${place.longitude}")
            PrimaryAction("Adjust Place", { onUpdatePlace(place.id) }, Modifier.fillMaxWidth())
            PrimaryAction("Arrival or departure alert", { onCreateArrivalDepartureRule(place.id) }, Modifier.fillMaxWidth())
            PrimaryAction("Add No Show rule", { onCreateNoShowRule(place.id) }, Modifier.fillMaxWidth())
            DestructiveActionButton(
                action = DestructiveAction(DestructiveActionKind.DELETE_PLACE, place.name),
                label = "Delete Place",
                onConfirmed = { onDeletePlace(place.id) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        StatusBanner(state.ruleCondition, "Place rules", state.ruleMessage)
        if (state.noShowRules.isEmpty()) EmptyStateCard("No No Show rules", "No scheduled arrival is set for this Place.")
        state.noShowRules.forEach { rule ->
            MemberCard {
                SectionHeading("Expected ${rule.expectedAtLabel}")
                LabeledValue("Grace period", "${rule.graceMinutes} minutes")
                rule.recurrenceLabel?.let { LabeledValue("Repeats", it) }
                PlaceOutcomeBanner(rule.outcome)
                PrimaryAction("Edit alert rule", { onUpdatePlaceRule(place.id, rule.id) }, Modifier.fillMaxWidth())
                DestructiveActionButton(
                    action = DestructiveAction(DestructiveActionKind.DELETE_PLACE_RULE, "Expected ${rule.expectedAtLabel}"),
                    label = "Delete alert rule",
                    onConfirmed = { onDeletePlaceRule(place.id, rule.id) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        TextButton(onClick = onBack) { Text("Back to Places") }
    }
}

@Composable
private fun PlaceOutcomeBanner(outcome: PlaceArrivalUi) {
    val (title, detail) = when (outcome.status) {
        DataCondition.CONFIRMED -> "Arrival confirmed" to outcome.detail
        DataCondition.PENDING -> "Arrival not confirmed yet" to outcome.detail
        DataCondition.OFFLINE -> "Arrival unknown while offline" to outcome.detail
        DataCondition.UNKNOWN -> "Arrival unknown" to (outcome.detail.ifBlank { "No current data is available to confirm arrival." })
        DataCondition.UNAVAILABLE -> "Arrival check unavailable" to outcome.detail
        DataCondition.ERROR -> "Arrival check failed" to outcome.detail
    }
    StatusBanner(outcome.status, title, detail)
}

data class HistoryStopUi(val label: String, val arrivalLabel: String?, val departureLabel: String?)

data class HistoryUiState(
    val condition: DataCondition = DataCondition.UNKNOWN,
    val message: String = "History has not loaded.",
    val memberLabel: String = "",
    val rangeLabel: String = "",
    val stops: List<HistoryStopUi> = emptyList(),
    val routePointCount: Int? = null,
    val playbackRunning: Boolean = false,
)

@Composable
fun HistoryScreen(
    state: HistoryUiState,
    mapContent: @Composable () -> Unit,
    fromDateTime: String,
    toDateTime: String,
    onFromDateTimeChange: (String) -> Unit,
    onToDateTimeChange: (String) -> Unit,
    onLoadHistory: (fromLabel: String, toLabel: String) -> Unit,
    onPlayRoute: () -> Unit,
    onStopPlayback: () -> Unit,
) {
    MemberScreen(title = "Circle history", subtitle = "History is visible only while you have Circle access and the member’s consent allows it.") {
        StatusBanner(state.condition, "History", state.message)
        MemberCard {
            if (state.memberLabel.isNotBlank()) LabeledValue("Member", state.memberLabel)
            if (state.rangeLabel.isNotBlank()) LabeledValue("Time range", state.rangeLabel)
            OutlinedTextField(fromDateTime, onFromDateTimeChange, label = { Text("From (ISO 8601)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(toDateTime, onToDateTimeChange, label = { Text("To (ISO 8601)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Choose a bounded time range. The service applies the maximum history limit.")
            PrimaryAction("Load history", { onLoadHistory(fromDateTime, toDateTime) }, Modifier.fillMaxWidth(), fromDateTime.isNotBlank() && toDateTime.isNotBlank())
        }
        MapSlot(mapContent, Modifier.fillMaxWidth().height(320.dp))
        state.routePointCount?.let { Text("$it route points in this range", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium) }
        if (state.routePointCount == null || state.routePointCount == 0) {
            EmptyStateCard("No route points", "No route data is available for this time range. A lack of data does not establish a stop or arrival.")
        }
        if (state.playbackRunning) PrimaryAction("Stop route playback", onStopPlayback, Modifier.fillMaxWidth())
        else PrimaryAction("Play route", onPlayRoute, Modifier.fillMaxWidth(), state.routePointCount != null && state.routePointCount > 0)
        SectionHeading("Stops")
        if (state.stops.isEmpty()) EmptyStateCard("No confirmed stops in this range", "A stop is listed only when precise, accurate fixes show continuous stationary time. Missing, approximate, or unreliable data cannot confirm a stop.")
        state.stops.forEach { stop ->
            MemberCard {
                Text(stop.label, style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
                stop.arrivalLabel?.let { LabeledValue("Arrival", it) }
                stop.departureLabel?.let { LabeledValue("Departure", it) }
            }
        }
    }
}

data class CheckInUiState(
    val note: String = "",
    val submitting: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)

@Composable
fun CheckInScreen(
    state: CheckInUiState,
    onNoteChange: (String) -> Unit,
    onCreateCheckIn: (label: String) -> Unit,
) {
    MemberScreen(title = "Check in", subtitle = "Send a one-time location check-in to this Circle.") {
        MemberCard {
            OutlinedTextField(
                state.note,
                onNoteChange,
                label = { Text("Message") },
                supportingText = { Text("Do not include sensitive information.") },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("The check-in includes your current location and capture time. A recent, accurate location fix is required.")
            state.statusMessage?.let { StatusBanner(DataCondition.PENDING, "Check-in status", it) }
            state.errorMessage?.let { StatusBanner(DataCondition.ERROR, "Check-in not sent", it) }
            PrimaryAction(
                if (state.submitting) "Sending…" else "Send check-in",
                onClick = { onCreateCheckIn(state.note) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.submitting && state.note.isNotBlank(),
            )
        }
    }
}

data class QuickNoteUi(val id: String, val senderLabel: String, val body: String, val createdAtLabel: String, val deliveryLabel: String)

@Composable
fun QuickNotesScreen(
    notes: List<QuickNoteUi>,
    condition: DataCondition,
    message: String,
    draft: String,
    submitting: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: (body: String) -> Unit,
) {
    MemberScreen(title = "Quick Notes", subtitle = "Short messages shared with this Circle.") {
        StatusBanner(condition, "Notes", message)
        if (notes.isEmpty()) EmptyStateCard("No notes", "There are no Quick Notes to show.")
        notes.forEach { note ->
            MemberCard {
                SectionHeading(note.senderLabel)
                Text(note.body)
                LabeledValue("Sent", note.createdAtLabel)
                LabeledValue("Status", note.deliveryLabel)
            }
        }
        MemberCard {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                label = { Text("Write a Quick Note") },
                supportingText = { Text("Keep messages brief and respectful of everyone in the Circle.") },
                modifier = Modifier.fillMaxWidth(),
            )
            PrimaryAction(if (submitting) "Sending…" else "Send note", { onSend(draft) }, Modifier.fillMaxWidth(), draft.isNotBlank() && !submitting)
        }
    }
}

data class CircleEventUi(
    val id: String,
    val title: String,
    val startsAtLabel: String,
    val details: String,
    val myRsvp: String?,
    val rsvpCountsLabel: String? = null,
)

@Composable
fun EventsScreen(
    events: List<CircleEventUi>,
    condition: DataCondition,
    message: String,
    draftTitle: String,
    draftStartsAt: String,
    draftDetails: String,
    invitees: List<AudienceChoiceUi> = emptyList(),
    onInviteeSelected: (memberId: String, selected: Boolean) -> Unit = { _, _ -> },
    onDraftTitleChange: (String) -> Unit,
    onDraftStartsAtChange: (String) -> Unit,
    onDraftDetailsChange: (String) -> Unit,
    onCreateEvent: (title: String, startsAt: String, details: String) -> Unit,
    onSetRsvp: (eventId: String, response: String) -> Unit,
) {
    MemberScreen(title = "Circle events", subtitle = "Coordinate plans with people in this Circle.") {
        StatusBanner(condition, "Events", message)
        if (events.isEmpty()) EmptyStateCard("No events yet", "Create an event for members you choose.")
        events.forEach { event ->
            MemberCard {
                SectionHeading(event.title)
                LabeledValue("Starts", event.startsAtLabel)
                if (event.details.isNotBlank()) Text(event.details)
                event.rsvpCountsLabel?.let { LabeledValue("Responses", it) }
                LabeledValue("Your response", event.myRsvp ?: "No response")
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("yes", "maybe", "no").forEach { response ->
                        FilterChip(
                            selected = event.myRsvp.equals(response, ignoreCase = true),
                            onClick = { onSetRsvp(event.id, response) },
                            label = { Text(response.replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
            }
        }
        MemberCard {
            SectionHeading("Create an event")
            OutlinedTextField(draftTitle, onDraftTitleChange, label = { Text("Event name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(draftStartsAt, onDraftStartsAtChange, label = { Text("Start time (ISO 8601)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(draftDetails, onDraftDetailsChange, label = { Text("Details") }, modifier = Modifier.fillMaxWidth())
            SectionHeading("Invite Circle members")
            invitees.forEach { invitee ->
                FilterChip(
                    selected = invitee.selected,
                    onClick = { onInviteeSelected(invitee.id, !invitee.selected) },
                    label = { Text(invitee.label) },
                )
            }
            PrimaryAction(
                "Create event",
                { onCreateEvent(draftTitle.trim(), draftStartsAt, draftDetails) },
                Modifier.fillMaxWidth(),
                draftTitle.isNotBlank() && draftStartsAt.isNotBlank() && invitees.any { it.selected },
            )
        }
    }
}

data class InboxSosTarget(val circleId: String, val sosId: String)

data class InboxItemUi(
    val id: String,
    val kind: String,
    val title: String,
    val body: String,
    val createdAtLabel: String,
    val deliveryState: String,
    val acknowledged: Boolean,
    val circleId: String? = null,
    val sosId: String? = null,
) {
    fun sosTargetOrNull(): InboxSosTarget? {
        val circle = circleId?.toCanonicalUuidOrNull() ?: return null
        val sos = sosId?.toCanonicalUuidOrNull() ?: return null
        return InboxSosTarget(circle, sos)
    }

    private fun String.toCanonicalUuidOrNull(): String? =
        runCatching { UUID.fromString(this).toString() }.getOrNull()
}

@Composable
fun NotificationInboxScreen(
    items: List<InboxItemUi>,
    condition: DataCondition,
    message: String,
    onAcknowledge: (notificationIds: List<String>) -> Unit,
    onOpenSos: (circleId: String, sosId: String) -> Unit,
) {
    MemberScreen(title = "Inbox", subtitle = "Delivery status reflects the configured app channel.") {
        StatusBanner(condition, "Notifications", message)
        val pending = items.filterNot { it.acknowledged }
        if (pending.isNotEmpty()) PrimaryAction("Mark visible as read", { onAcknowledge(pending.map { it.id }) }, Modifier.fillMaxWidth())
        if (items.isEmpty()) EmptyStateCard("Inbox is empty", "New Circle activity will appear here when available.")
        items.forEach { item ->
            MemberCard {
                SectionHeading(item.title)
                Text(item.body)
                LabeledValue("Type", item.kind)
                LabeledValue("Delivery", item.deliveryState)
                LabeledValue("Received", item.createdAtLabel)
                if (!item.acknowledged) Text("Unread", color = androidx.compose.material3.MaterialTheme.colorScheme.primary)
                item.sosTargetOrNull()?.let { target ->
                    PrimaryAction("Open SOS status", { onOpenSos(target.circleId, target.sosId) }, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

data class TripProgressUiState(
    val active: Boolean = false,
    val tripId: String? = null,
    val confirmedDriving: Boolean = false,
    val condition: DataCondition = DataCondition.UNKNOWN,
    val message: String = "Trip progress has not loaded.",
    val destinationLabel: String? = null,
    val etaLabel: String? = null,
    val distanceRemainingMeters: Int? = null,
)

@Composable
fun TripProgressScreen(
    state: TripProgressUiState,
    destination: String,
    destinationLatitude: String,
    destinationLongitude: String,
    selectedRecipientIds: Set<String>,
    availableRecipients: List<AudienceChoiceUi>,
    shareLockScreenProgress: Boolean,
    confirmDriving: Boolean,
    mapContent: @Composable () -> Unit,
    onDestinationChange: (String) -> Unit,
    onDestinationLatitudeChange: (String) -> Unit,
    onDestinationLongitudeChange: (String) -> Unit,
    onRecipientSelected: (memberId: String, selected: Boolean) -> Unit,
    onShareLockScreenProgressChange: (Boolean) -> Unit,
    onConfirmDrivingChange: (Boolean) -> Unit,
    onStartTrip: (destinationLabel: String?, latitude: Double?, longitude: Double?, recipientIds: List<String>, shareLockScreenProgress: Boolean, confirmedDriving: Boolean) -> Unit,
    onStopTrip: (tripId: String, endReason: String) -> Unit = { _, _ -> },
) {
    val latitude = destinationLatitude.toDoubleOrNull()
    val longitude = destinationLongitude.toDoubleOrNull()
    val coordinatesValid = (destinationLatitude.isBlank() && destinationLongitude.isBlank()) ||
        (latitude != null && latitude in -90.0..90.0 && longitude != null && longitude in -180.0..180.0)
    MemberScreen(title = "Trip progress", subtitle = "Share trip progress with selected Circle members.") {
        StatusBanner(state.condition, if (state.active) "Trip active" else "Trip status", state.message)
        MapSlot(mapContent, Modifier.fillMaxWidth().height(280.dp))
        if (state.active) {
            MemberCard {
                state.destinationLabel?.let { LabeledValue("Destination", it) }
                LabeledValue("Driver confirmation", if (state.confirmedDriving) "You confirmed you are driving" else "Not confirmed")
                state.etaLabel?.let { LabeledValue("Estimated arrival", it) }
                state.distanceRemainingMeters?.let { LabeledValue("Distance remaining", "${it / 1000.0} km") }
                Text("ETA is an estimate and may be unavailable when trip data is stale.")
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Show trip progress on the lock screen")
                        Text("Android may hide destination and ETA according to your lock-screen settings.")
                    }
                    Switch(checked = shareLockScreenProgress, onCheckedChange = onShareLockScreenProgressChange)
                }
                state.tripId?.let { tripId ->
                    DestructiveActionButton(
                        action = DestructiveAction(DestructiveActionKind.STOP_TRIP, state.destinationLabel ?: "this trip"),
                        label = "End this trip",
                        onConfirmed = { onStopTrip(tripId, "other") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else {
            MemberCard {
                OutlinedTextField(destination, onDestinationChange, label = { Text("Destination") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(destinationLatitude, onDestinationLatitudeChange, label = { Text("Destination latitude (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(destinationLongitude, onDestinationLongitudeChange, label = { Text("Destination longitude (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                availableRecipients.forEach { recipient ->
                    FilterChip(
                        selected = recipient.id in selectedRecipientIds,
                        onClick = { onRecipientSelected(recipient.id, recipient.id !in selectedRecipientIds) },
                        label = { Text(recipient.label) },
                    )
                }
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("I confirm I am driving")
                        Text("Leave this off when you are a passenger. The server will not infer a driver from speed.")
                    }
                    Switch(checked = confirmDriving, onCheckedChange = onConfirmDrivingChange)
                }
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Show progress on the lock screen", modifier = Modifier.weight(1f))
                    Switch(checked = shareLockScreenProgress, onCheckedChange = onShareLockScreenProgressChange)
                }
                Text("Destination and ETA appear in the ongoing location notification when enabled. Android may hide details on the lock screen.")
                Text("Members will see trip status and an estimated arrival when available.")
                ConsentActionButton(
                    label = "Start trip",
                    title = "Share this trip?",
                    explanation = "Trip status will be shared with the selected Circle members. Location collection begins only after this explicit action and any Android permission prompt. Opening or creating a guest link will never start collection.",
                    onConsent = {
                        onStartTrip(
                            destination.trim().takeIf(String::isNotBlank),
                            latitude,
                            longitude,
                            selectedRecipientIds.toList(),
                            shareLockScreenProgress,
                            confirmDriving,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = selectedRecipientIds.isNotEmpty() && coordinatesValid,
                )
            }
        }
    }
}

data class MorningCheckInUi(val memberId: String, val memberLabel: String, val statusLabel: String, val updatedAtLabel: String?)

@Composable
fun MorningCheckInScreen(
    items: List<MorningCheckInUi>,
    condition: DataCondition,
    message: String,
    greeting: String,
    submitting: Boolean,
    onGreetingChange: (String) -> Unit,
    onSendMorningCheckIn: (greeting: String) -> Unit,
) {
    MemberScreen(title = "Morning check-in", subtitle = "Share a brief morning greeting with this Circle.") {
        StatusBanner(condition, "Morning check-in", message)
        if (items.isEmpty()) EmptyStateCard("No check-ins", "No member check-in status is available yet.")
        items.forEach { item ->
            MemberCard {
                SectionHeading(item.memberLabel)
                LabeledValue("Status", item.statusLabel)
                item.updatedAtLabel?.let { LabeledValue("Updated", it) }
            }
        }
        MemberCard {
            OutlinedTextField(greeting, onGreetingChange, label = { Text("Morning message") }, modifier = Modifier.fillMaxWidth())
            PrimaryAction(
                if (submitting) "Sending…" else "Send morning check-in",
                { onSendMorningCheckIn(greeting.trim()) },
                Modifier.fillMaxWidth(),
                !submitting && greeting.isNotBlank(),
            )
        }
    }
}

data class ActivitySharingUiState(
    val enabled: Boolean,
    val statusLabel: String,
    val recipientCircleName: String,
    val lastUpdatedLabel: String?,
)

@Composable
fun ActivitySharingScreen(
    state: ActivitySharingUiState,
    onSetActivitySharing: (enabled: Boolean) -> Unit,
) {
    MemberScreen(title = "Activity sharing", subtitle = "Choose whether activity labels are visible to Circle members.") {
        MemberCard {
            LabeledValue("Sharing status", state.statusLabel)
            LabeledValue("Circle", state.recipientCircleName)
            state.lastUpdatedLabel?.let { LabeledValue("Last updated", it) }
            Text("Activity labels can be approximate. They do not prove location, arrival, or safety.")
            if (state.enabled) {
                DestructiveActionButton(
                    action = DestructiveAction(DestructiveActionKind.PAUSE_ACTIVITY_SHARING, state.recipientCircleName),
                    label = "Pause activity sharing",
                    onConfirmed = { onSetActivitySharing(false) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                ConsentActionButton(
                    label = "Share activity with this Circle",
                    title = "Enable activity sharing?",
                    explanation = "Activity labels will be shared with ${state.recipientCircleName}. You can pause them here at any time.",
                    onConsent = { onSetActivitySharing(true) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

data class DrivingReportUi(
    val tripId: String,
    val memberId: String,
    val memberLabel: String?,
    val startedAtLabel: String,
    val endedAtLabel: String?,
    val classification: String,
    val confirmedDrive: Boolean,
)

@Composable
fun DrivingReportsScreen(
    reports: List<DrivingReportUi>,
    latestLocalTrip: DriveTrip?,
    condition: DataCondition,
    message: String,
    onCorrectClassification: (tripId: String, classification: String) -> Unit,
) {
    MemberScreen(title = "Driving reports", subtitle = "Reports include only measurements available from this phone.") {
        StatusBanner(condition, "Drive history", message)
        latestLocalTrip?.let { trip ->
            MemberCard {
                SectionHeading(if (trip.endedAt == null) "Current phone-measured drive" else "Latest phone-measured drive")
                val role = when (trip.role) {
                    TripRole.DRIVER -> "Driver · confirmed by you"
                    TripRole.PASSENGER -> "Passenger · corrected by you"
                    TripRole.UNCONFIRMED -> "Unconfirmed driver status"
                }
                LabeledValue("Classification", role)
                LabeledValue("Duration", formatMeasuredDuration(trip.elapsedSeconds))
                LabeledValue("Distance", String.format(Locale.getDefault(), "%.1f km", trip.distanceMeters / 1_000.0))
                LabeledValue(
                    "Average measured speed",
                    trip.averageMeasuredSpeedMps?.let { String.format(Locale.getDefault(), "%.0f km/h", it * 3.6) } ?: "Unavailable",
                )
                LabeledValue(
                    "Maximum measured speed",
                    trip.maxMeasuredSpeedMps?.let { String.format(Locale.getDefault(), "%.0f km/h", it * 3.6) } ?: "Unavailable",
                )
                LabeledValue("Measured stops", trip.stopCount.toString())
                Text("This phone-only summary is not included in the Circle report and is cleared when you sign out.")
            }
        }
        if (reports.isEmpty()) EmptyStateCard("No drive reports", "No measured drive reports are available.")
        reports.forEach { trip ->
            MemberCard {
                SectionHeading("Trip · ${trip.startedAtLabel}")
                LabeledValue("Member", trip.memberLabel ?: "Circle member")
                trip.endedAtLabel?.let { LabeledValue("Ended", it) }
                LabeledValue("Classification", trip.classification)
                LabeledValue("Driver confirmation", if (trip.confirmedDrive) "Confirmed by member" else "No confirmation recorded")
                Text("Distance, duration, and driving-event metrics are not available in the current report data.")
                FilterChip(trip.classification.equals("driver", true), { onCorrectClassification(trip.tripId, "driver") }, label = { Text("I was driving") })
                FilterChip(trip.classification.equals("passenger", true), { onCorrectClassification(trip.tripId, "passenger") }, label = { Text("I was a passenger") })
            }
        }
    }
}

private fun formatMeasuredDuration(totalSeconds: Long): String {
    val hours = totalSeconds.coerceAtLeast(0) / 3_600
    val minutes = (totalSeconds.coerceAtLeast(0) % 3_600) / 60
    val seconds = totalSeconds.coerceAtLeast(0) % 60
    return if (hours > 0) "$hours hr $minutes min" else "$minutes min $seconds sec"
}
