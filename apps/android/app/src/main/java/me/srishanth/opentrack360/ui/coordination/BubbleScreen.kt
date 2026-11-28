package me.srishanth.opentrack360.ui.coordination

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.srishanth.opentrack360.ui.components.ConsentActionButton
import me.srishanth.opentrack360.ui.components.DestructiveActionButton
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

data class BubbleUi(val id: String, val latitude: Double, val longitude: Double, val radiusMeters: Int, val expiresAtLabel: String, val ended: Boolean)

@Composable
fun BubbleScreen(
    bubbles: List<BubbleUi>,
    latitude: String,
    longitude: String,
    radiusMeters: String,
    durationMinutes: String,
    condition: DataCondition,
    message: String,
    mapContent: @Composable () -> Unit,
    onLatitudeChange: (String) -> Unit,
    onLongitudeChange: (String) -> Unit,
    onRadiusChange: (String) -> Unit,
    onDurationChange: (String) -> Unit,
    onCreateBubble: (Double, Double, Int, Int) -> Unit,
    onEndBubble: (String) -> Unit,
) {
    val lat = latitude.toDoubleOrNull()
    val lon = longitude.toDoubleOrNull()
    val radius = radiusMeters.toIntOrNull()
    val duration = durationMinutes.toIntOrNull()
    MemberScreen(title = "Approximate location Bubble", subtitle = "A Circle Bubble shows an approximate area, not a precise point.") {
        StatusBanner(condition, "Bubble sharing", message)
        MapSlot(mapContent, Modifier.fillMaxWidth().height(270.dp))
        MemberCard {
            OutlinedTextField(latitude, onLatitudeChange, label = { Text("Approximate latitude") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(longitude, onLongitudeChange, label = { Text("Approximate longitude") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(radiusMeters, onRadiusChange, label = { Text("Radius in metres · 50–10,000") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(durationMinutes, onDurationChange, label = { Text("Duration in minutes · 1–360") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ConsentActionButton(
                label = "Share approximate area",
                title = "Share this approximate area?",
                explanation = "Circle members will see an approximate area and its expiry time. This does not share the hidden precise position through this Bubble.",
                onConsent = { if (lat != null && lon != null && radius != null && duration != null) onCreateBubble(lat, lon, radius, duration) },
                modifier = Modifier.fillMaxWidth(),
                enabled = lat != null && lat in -90.0..90.0 && lon != null && lon in -180.0..180.0 && radius != null && radius in 50..10_000 && duration != null && duration in 1..360,
            )
        }
        SectionHeading("Active Bubbles")
        val active = bubbles.filterNot { it.ended }
        if (active.isEmpty()) EmptyStateCard("No active Bubble", "A Bubble ends at its chosen expiry or when you end it here.")
        active.forEach { bubble ->
            MemberCard {
                LabeledValue("Approximate area", "${bubble.latitude}, ${bubble.longitude}")
                LabeledValue("Radius", "${bubble.radiusMeters} m")
                LabeledValue("Expires", bubble.expiresAtLabel)
                DestructiveActionButton(
                    action = DestructiveAction(DestructiveActionKind.END_BUBBLE, "this approximate area"),
                    label = "End Bubble",
                    onConfirmed = { onEndBubble(bubble.id) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
