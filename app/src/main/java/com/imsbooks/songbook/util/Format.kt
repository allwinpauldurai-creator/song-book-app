package com.imsbooks.songbook.util

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val DATE_FORMAT = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

/**
 * Formats an ISO date ("2026-11-16") or instant ("2026-11-16T10:00:00Z").
 * Plain dates are calendar dates in the business timezone, so they are not shifted through the device
 * timezone (which could show the previous day) — same rule as the web app's formatDate.
 */
fun formatDate(value: String?): String {
    if (value.isNullOrBlank()) return ""
    return try {
        if (value.length == 10) {
            LocalDate.parse(value).format(DATE_FORMAT)
        } else {
            val instant = try {
                Instant.parse(value)
            } catch (_: Exception) {
                OffsetDateTime.parse(value).toInstant()
            }
            instant.atZone(ZoneId.systemDefault()).toLocalDate().format(DATE_FORMAT)
        }
    } catch (_: Exception) {
        value
    }
}

/** "SUSPENDED" -> "Suspended". */
fun titleCase(value: String): String = value.lowercase().replaceFirstChar { it.uppercase() }
