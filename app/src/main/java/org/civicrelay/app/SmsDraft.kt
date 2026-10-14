package org.civicrelay.app

/** Generates an explicit, user-reviewed SMS draft. Never sends a message directly. */
object SmsDraft {
    fun build(message: String, latitude: Double?, longitude: Double?,
              accuracyMeters: Float?, locationLabel: String, includeLocation: Boolean): String {
        val location = if (!includeLocation) "" else {
            if (latitude != null && longitude != null &&
                latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
                latitude.isFinite() && longitude.isFinite()) {
                val accuracy = if (accuracyMeters != null && accuracyMeters.isFinite() && accuracyMeters >= 0)
                    ", approx. ${accuracyMeters.toInt()}m accuracy" else ""
                "\nLocation ($locationLabel$accuracy): $latitude,$longitude" +
                    "\nhttps://maps.google.com/?q=$latitude,$longitude"
            } else "\nLocation unavailable. Ask the sender for an updated location."
        }
        return "CivicRelay emergency: ${message.trim().take(500)}$location\n" +
            "Sent manually from an experimental app; location accuracy/delivery is not guaranteed."
    }
}
