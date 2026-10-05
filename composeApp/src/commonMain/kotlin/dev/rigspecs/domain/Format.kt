package dev.rigspecs.domain

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

private val Months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** "Oct 5, 2026 14:07" in the device's time zone. */
fun formatTimestamp(epochMillis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val t = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone)
    val hh = t.hour.toString().padStart(2, '0')
    val mm = t.minute.toString().padStart(2, '0')
    return "${Months[t.month.ordinal]} ${t.day}, ${t.year} $hh:$mm"
}

/** Common units offered as one-tap picks on the spec form. */
val CommonUnits = listOf("qt", "gal", "L", "ft-lb", "in-lb", "N·m", "psi", "V", "A", "in", "mm")
