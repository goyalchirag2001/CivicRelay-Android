package org.civicrelay.app.policy

import kotlin.math.floor

/**
 * Privacy gate for FUTURE, moderated area-level reports. Not a realtime location tracker.
 * No device identifiers, personally identifying coordinates or witness details are emitted.
 * A backend must additionally counter fake/sybil accounts; five keys are not necessarily five people.
 */
object DetentionMapPolicy {
    const val MIN_INDEPENDENT_REPORTS = 5
    const val PUBLICATION_DELAY_MS = 30L * 60 * 1000
    const val MAX_AGE_MS = 6L * 60 * 60 * 1000
    private const val CELL_DEGREES = 0.10 // Roughly 11 km in latitude; smaller longitudinally in India.

    data class Report(
        val signingKeyFingerprint: String,
        val latitude: Double,
        val longitude: Double,
        val reportedAt: Long,
        val moderatorApproved: Boolean
    )

    data class AreaSummary(val latitude: Double, val longitude: Double, val reportCount: Int)

    fun aggregate(reports: List<Report>, now: Long): List<AreaSummary> {
        return reports.asSequence()
            .filter { it.moderatorApproved }
            .filter { it.latitude.isFinite() && it.longitude.isFinite() &&
                it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 }
            .filter { it.signingKeyFingerprint.isNotBlank() }
            .filter { it.reportedAt <= now - PUBLICATION_DELAY_MS &&
                it.reportedAt >= now - MAX_AGE_MS }
            .groupBy {
                floor(it.latitude / CELL_DEGREES).toInt() to floor(it.longitude / CELL_DEGREES).toInt()
            }
            .mapNotNull { (cell, members) ->
                val count = members.map { it.signingKeyFingerprint }.distinct().size
                if (count < MIN_INDEPENDENT_REPORTS) null
                else AreaSummary((cell.first + 0.5) * CELL_DEGREES,
                    (cell.second + 0.5) * CELL_DEGREES, count)
            }.sortedByDescending { it.reportCount }
            .toList()
    }
}
