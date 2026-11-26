package me.srishanth.opentrack360.tracking

import java.util.Locale

/** In-memory only trip details that may be shown after the member opts in to lock-screen progress. */
data class TripProgressNotificationContent(
    val destinationLabel: String?,
    val etaLabel: String?,
    val distanceRemainingMeters: Int?,
)

data class FormattedTripProgressNotification(
    val title: String,
    val body: String,
    val publicTitle: String = "OpenTrack360 trip progress",
    val publicBody: String = "Trip is active. Details are hidden.",
)

object TripProgressNotificationFormatter {
    fun format(content: TripProgressNotificationContent?): FormattedTripProgressNotification? {
        content ?: return null
        val destination = content.destinationLabel?.trim()?.takeIf(String::isNotEmpty)
        val title = destination?.let { "Trip to $it" } ?: "Trip progress"
        val details = buildList {
            content.etaLabel?.trim()?.takeIf(String::isNotEmpty)?.let { add("ETA $it") }
            content.distanceRemainingMeters
                ?.takeIf { it >= 0 }
                ?.let { meters -> add(String.format(Locale.getDefault(), "%.1f km remaining", meters / 1000.0)) }
        }
        return FormattedTripProgressNotification(
            title = title,
            body = details.takeIf { it.isNotEmpty() }?.joinToString(" · ") ?: "Trip progress is active.",
        )
    }
}
