package me.srishanth.opentrack360.pets.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import me.srishanth.opentrack360.tracking.AndroidKeystoreTrackingQueueCipher
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.UUID

data class PetReminderRecurrence(
    val frequency: Frequency,
    val interval: Int,
    val weekdays: Set<Int>,
    val localTime: LocalTime,
    val timeZone: ZoneId,
) {
    enum class Frequency { DAILY, WEEKLY }

    init {
        require(interval in 1..52)
        require(weekdays.all { it in 1..7 })
    }
}

data class PetRoutineReminderSpec(
    val ownerMemberId: String,
    val petId: String,
    val routineId: String,
    val dueAt: Instant?,
    val anchorAt: Instant?,
    val recurrence: PetReminderRecurrence?,
) {
    companion object {
        /** Builds a reminder from a contract routine without copying any user-authored text. */
        fun fromApi(ownerMemberId: String, petId: String, row: JSONObject): PetRoutineReminderSpec? = runCatching {
            if (!row.optBoolean("active", true)) return null
            if (row.optString("createdByMemberId") != ownerMemberId) return null
            UUID.fromString(ownerMemberId)
            UUID.fromString(petId)
            val routineId = row.optString("id")
            UUID.fromString(routineId)
            val dueAt = row.optString("dueAt").takeIf { it.isNotBlank() && it != "null" }?.let(Instant::parse)
            val createdAt = row.optString("createdAt").takeIf { it.isNotBlank() && it != "null" }?.let(Instant::parse)
            val recurrence = row.optJSONObject("recurrence")?.let { value ->
                val frequency = when (value.optString("frequency")) {
                    "daily" -> PetReminderRecurrence.Frequency.DAILY
                    "weekly" -> PetReminderRecurrence.Frequency.WEEKLY
                    else -> return null
                }
                val timeZone = runCatching { ZoneId.of(value.optString("timeZone")) }.getOrDefault(ZoneId.systemDefault())
                val localTime = value.optString("localTime").takeIf(String::isNotBlank)
                    ?.let { LocalTime.parse(it) }
                    ?: dueAt?.atZone(timeZone)?.toLocalTime()
                    ?: return null
                val weekdaysArray = value.optJSONArray("weekdays")
                val weekdays = buildSet {
                    if (weekdaysArray != null) for (index in 0 until weekdaysArray.length()) {
                        val day = weekdaysArray.optInt(index, -1)
                        if (day in 1..7) add(day)
                    }
                }.ifEmpty {
                    if (frequency == PetReminderRecurrence.Frequency.WEEKLY) {
                        setOf(dueAt?.atZone(timeZone)?.dayOfWeek?.value ?: LocalDate.now(timeZone).dayOfWeek.value)
                    } else emptySet()
                }
                PetReminderRecurrence(
                    frequency = frequency,
                    interval = value.optInt("interval", 1).coerceIn(1, 52),
                    weekdays = weekdays,
                    localTime = localTime,
                    timeZone = timeZone,
                )
            }
            PetRoutineReminderSpec(ownerMemberId, petId, routineId, dueAt, createdAt, recurrence)
        }.getOrNull()
    }
}

object PetReminderScheduleCalculator {
    /** Returns the next recurrence strictly after [after], anchored to the original due date. */
    fun nextAfter(after: Instant, anchor: Instant, recurrence: PetReminderRecurrence): Instant? {
        val zone = recurrence.timeZone
        val anchorDate = anchor.atZone(zone).toLocalDate()
        val afterDate = after.atZone(zone).toLocalDate()
        val anchorWeek = anchorDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val startOffset = ChronoUnit.DAYS.between(anchorDate, afterDate).coerceAtLeast(0).toInt()
        val maxDays = 366 * 53

        for (offset in startOffset..maxDays) {
            val date = anchorDate.plusDays(offset.toLong())
            val daysFromAnchor = ChronoUnit.DAYS.between(anchorDate, date).toInt()
            val matches = when (recurrence.frequency) {
                PetReminderRecurrence.Frequency.DAILY -> daysFromAnchor % recurrence.interval == 0
                PetReminderRecurrence.Frequency.WEEKLY -> {
                    val week = ChronoUnit.WEEKS.between(anchorWeek, date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))).toInt()
                    week >= 0 && week % recurrence.interval == 0 && date.dayOfWeek.value in recurrence.weekdays
                }
            }
            if (!matches) continue
            val candidate = LocalDateTime.of(date, recurrence.localTime).atZone(zone).toInstant()
            if (candidate.isAfter(after)) return candidate
        }
        return null
    }
}

/** Persists only encrypted identifiers and schedule metadata; pet/routine names and notes are excluded. */
class PetRoutineReminderScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val store = PetReminderStore(appContext)

    fun activateMember(memberId: String?) {
        val reminders = store.read()
        val retained = reminders.filterValues { it.ownerMemberId == memberId }
        reminders.values.filter { it.ownerMemberId != memberId }.forEach(::cancelAlarm)
        store.write(retained)
        restore()
    }

    fun syncPet(memberId: String, petId: String, specs: List<PetRoutineReminderSpec>) {
        val now = Instant.now()
        val reminders = store.read().toMutableMap()
        val removed = reminders.values.filter { it.ownerMemberId == memberId && it.petId == petId }
        val previous = removed.associateBy { it.routineId }
        removed.forEach { reminders.remove(it.routineId); if (!it.completed) cancelAlarm(it) }
        specs.asSequence()
            .filter { it.ownerMemberId == memberId && it.petId == petId }
            .take(MAX_REMINDERS)
            .forEach { spec ->
                val old = previous[spec.routineId]
                val currentAnchor = (spec.dueAt ?: spec.anchorAt)?.toEpochMilli()
                if (old?.completed == true && spec.recurrence == null && currentAnchor == old.anchorEpochMillis) {
                    reminders[spec.routineId] = old
                } else {
                    toStoredReminder(spec, now)?.let { reminders[it.routineId] = it }
                }
            }
        persistAndSchedule(reminders)
    }

    fun upsert(spec: PetRoutineReminderSpec) {
        val reminders = store.read().toMutableMap()
        reminders[spec.routineId]?.let(::cancelAlarm)
        toStoredReminder(spec, Instant.now())?.let { reminders[spec.routineId] = it }
            ?: reminders.remove(spec.routineId)
        persistAndSchedule(reminders)
    }

    fun onRoutineCompleted(memberId: String, routineId: String) {
        val reminders = store.read().toMutableMap()
        val reminder = reminders[routineId]?.takeIf { it.ownerMemberId == memberId } ?: return
        cancelNotification(routineId)
        if (reminder.recurrence == null) {
            cancelAlarm(reminder)
            reminders[routineId] = reminder.copy(completed = true)
            store.write(reminders)
        }
    }

    fun removePet(memberId: String, petId: String) {
        val reminders = store.read().toMutableMap()
        reminders.values.filter { it.ownerMemberId == memberId && it.petId == petId }.forEach {
            if (!it.completed) cancelAlarm(it)
            cancelNotification(it.routineId)
            reminders.remove(it.routineId)
        }
        store.write(reminders)
    }

    fun clearAll() {
        store.read().values.forEach {
            if (!it.completed) cancelAlarm(it)
            cancelNotification(it.routineId)
        }
        store.clear()
    }

    /** Reinstalls alarms after reboot, dropping missed one-time reminders and advancing repeats. */
    fun restore() {
        val now = Instant.now()
        val reminders = store.read().toMutableMap()
        reminders.values.toList().forEach { reminder ->
            if (!reminder.completed) {
                if (reminder.recurrence == null && reminder.triggerAtEpochMillis <= now.toEpochMilli()) {
                    cancelAlarm(reminder)
                    reminders.remove(reminder.routineId)
                } else {
                    val refreshed = if (reminder.recurrence != null && reminder.triggerAtEpochMillis <= now.toEpochMilli()) {
                        cancelAlarm(reminder)
                        nextRecurrence(reminder, now)
                    } else reminder
                    if (refreshed == null) reminders.remove(reminder.routineId)
                    else reminders[reminder.routineId] = refreshed
                }
            }
        }
        store.write(reminders)
        reminders.values.filterNot { it.completed }.forEach(::scheduleAlarm)
    }

    /** Called by the manifest receiver when a reminder alarm fires. */
    fun onAlarm(routineId: String, expectedAtEpochMillis: Long) {
        val reminders = store.read().toMutableMap()
        val reminder = reminders[routineId] ?: return
        if (reminder.completed || reminder.triggerAtEpochMillis != expectedAtEpochMillis) return
        val now = Instant.now()
        if (expectedAtEpochMillis > now.plusSeconds(120).toEpochMilli()) {
            scheduleAlarm(reminder)
            return
        }
        PetRoutineReminderNotifier.show(appContext, routineId)
        val next = if (reminder.recurrence == null) null else nextRecurrence(reminder, now)
        if (next == null) reminders.remove(routineId) else reminders[routineId] = next
        store.write(reminders)
        next?.let(::scheduleAlarm)
    }

    private fun toStoredReminder(spec: PetRoutineReminderSpec, now: Instant): StoredPetReminder? {
        val recurrence = spec.recurrence
        val anchor = spec.dueAt ?: spec.anchorAt ?: now
        val trigger = when {
            spec.dueAt != null && spec.dueAt.isAfter(now) -> spec.dueAt
            recurrence != null -> PetReminderScheduleCalculator.nextAfter(now, anchor, recurrence)
            else -> null
        } ?: return null
        return StoredPetReminder(
            ownerMemberId = spec.ownerMemberId,
            petId = spec.petId,
            routineId = spec.routineId,
            anchorEpochMillis = anchor.toEpochMilli(),
            triggerAtEpochMillis = trigger.toEpochMilli(),
            recurrence = recurrence,
            completed = false,
        )
    }

    private fun nextRecurrence(reminder: StoredPetReminder, after: Instant): StoredPetReminder? {
        val recurrence = reminder.recurrence ?: return null
        val next = PetReminderScheduleCalculator.nextAfter(
            after = after,
            anchor = Instant.ofEpochMilli(reminder.anchorEpochMillis),
            recurrence = recurrence,
        ) ?: return null
        return reminder.copy(triggerAtEpochMillis = next.toEpochMilli())
    }

    private fun persistAndSchedule(reminders: MutableMap<String, StoredPetReminder>) {
        val bounded = reminders.values.toList().takeLast(MAX_REMINDERS).associateBy { it.routineId }
        reminders.values.filter { it.routineId !in bounded }.forEach { cancelAlarm(it); cancelNotification(it.routineId) }
        store.write(bounded)
        bounded.values.filterNot { it.completed }.forEach(::scheduleAlarm)
    }

    private fun scheduleAlarm(reminder: StoredPetReminder) {
        val intent = reminderIntent(reminder.routineId, reminder.triggerAtEpochMillis)
        val pending = PendingIntent.getBroadcast(
            appContext,
            reminder.routineId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAtEpochMillis, pending)
    }

    private fun cancelAlarm(reminder: StoredPetReminder) {
        val pending = PendingIntent.getBroadcast(
            appContext,
            reminder.routineId.hashCode(),
            reminderIntent(reminder.routineId, reminder.triggerAtEpochMillis),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarmManager.cancel(pending)
        pending.cancel()
    }

    private fun reminderIntent(routineId: String, atEpochMillis: Long) = Intent(appContext, PetRoutineReminderReceiver::class.java)
        .setAction(PetRoutineReminderReceiver.ACTION_REMINDER)
        .setData(Uri.parse("opentrack360://pet-reminder/$routineId/$atEpochMillis"))

    private fun cancelNotification(routineId: String) {
        (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager)
            ?.cancel(routineId.hashCode())
    }

    private data class StoredPetReminder(
        val ownerMemberId: String,
        val petId: String,
        val routineId: String,
        val anchorEpochMillis: Long,
        val triggerAtEpochMillis: Long,
        val recurrence: PetReminderRecurrence?,
        val completed: Boolean,
    )

    private class PetReminderStore(context: Context) {
        private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        // The generic reminder message must be deliverable while the phone is locked.
        private val cipher = AndroidKeystoreTrackingQueueCipher(KEY_ALIAS, unlockedDeviceRequired = false)

        fun read(): LinkedHashMap<String, StoredPetReminder> {
            val encoded = preferences.getString(KEY, null) ?: return linkedMapOf()
            return runCatching {
                val encrypted = Base64.decode(encoded, Base64.NO_WRAP)
                DataInputStream(ByteArrayInputStream(cipher.decrypt(encrypted))).use { input ->
                    require(input.readInt() == VERSION)
                    val count = input.readInt()
                    require(count in 0..MAX_REMINDERS)
                    buildMap {
                        repeat(count) {
                            val memberId = input.readUTF()
                            val petId = input.readUTF()
                            val routineId = input.readUTF()
                            val anchor = input.readLong()
                            val trigger = input.readLong()
                            val completed = input.readBoolean()
                            val recurrence = if (input.readBoolean()) {
                                val frequency = PetReminderRecurrence.Frequency.valueOf(input.readUTF())
                                val interval = input.readInt()
                                val dayCount = input.readInt().also { require(it in 0..7) }
                                val days = buildSet { repeat(dayCount) { add(input.readInt()) } }
                                val localTime = LocalTime.parse(input.readUTF())
                                val zone = ZoneId.of(input.readUTF())
                                PetReminderRecurrence(frequency, interval, days, localTime, zone)
                            } else null
                            UUID.fromString(memberId)
                            UUID.fromString(petId)
                            UUID.fromString(routineId)
                            put(routineId, StoredPetReminder(memberId, petId, routineId, anchor, trigger, recurrence, completed))
                        }
                        require(input.available() == 0)
                    }.toMap(LinkedHashMap())
                }
            }.getOrElse {
                clear()
                linkedMapOf()
            }
        }

        fun write(reminders: Map<String, StoredPetReminder>) {
            val encoded = ByteArrayOutputStream().use { buffer ->
                DataOutputStream(buffer).use { output ->
                    output.writeInt(VERSION)
                    output.writeInt(reminders.size.coerceAtMost(MAX_REMINDERS))
                    reminders.values.take(MAX_REMINDERS).forEach { reminder ->
                        output.writeUTF(reminder.ownerMemberId)
                        output.writeUTF(reminder.petId)
                        output.writeUTF(reminder.routineId)
                        output.writeLong(reminder.anchorEpochMillis)
                        output.writeLong(reminder.triggerAtEpochMillis)
                        output.writeBoolean(reminder.completed)
                        output.writeBoolean(reminder.recurrence != null)
                        reminder.recurrence?.let { recurrence ->
                            output.writeUTF(recurrence.frequency.name)
                            output.writeInt(recurrence.interval)
                            output.writeInt(recurrence.weekdays.size)
                            recurrence.weekdays.sorted().forEach(output::writeInt)
                            output.writeUTF(recurrence.localTime.toString())
                            output.writeUTF(recurrence.timeZone.id)
                        }
                    }
                }
                buffer.toByteArray()
            }
            val encrypted = cipher.encrypt(encoded)
            check(preferences.edit().putString(KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) {
                "Unable to persist encrypted pet reminder schedules"
            }
        }

        fun clear() {
            preferences.edit().remove(KEY).commit()
        }

        private companion object {
            const val PREFERENCES = "pet_care_reminders_secure"
            const val KEY = "encrypted_schedules_v1"
            const val KEY_ALIAS = "opentrack360.pet.reminders.v1"
            const val VERSION = 2
        }
    }

    private companion object {
        const val MAX_REMINDERS = 128
    }
}
