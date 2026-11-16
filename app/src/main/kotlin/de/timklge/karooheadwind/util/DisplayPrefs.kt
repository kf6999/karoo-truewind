package de.timklge.karooheadwind.util

import java.util.Locale
import java.util.TimeZone

/** Process-wide display conventions for code that has no Context or profile at hand. */
object DisplayPrefs {
    /** Karoo profile uses imperial distance units; set when the profile is read. */
    @Volatile
    var imperial: Boolean = false

    /**
     * US conventions (month/day dates). The Karoo's locale is plain `en` with no country, so the
     * time zone decides when the locale does not say.
     */
    fun isUsRegion(locale: Locale = Locale.getDefault(), zoneId: String = TimeZone.getDefault().id): Boolean =
        when (locale.country) {
            "US" -> true
            "" -> zoneId.startsWith("US/") || zoneId == "Pacific/Honolulu" || zoneId in US_ZONES ||
                US_ZONE_PREFIXES.any { zoneId.startsWith(it) }
            else -> false
        }

    private val US_ZONES = setOf(
        "America/New_York", "America/Detroit", "America/Chicago", "America/Menominee", "America/Denver", "America/Boise",
        "America/Phoenix", "America/Los_Angeles", "America/Anchorage", "America/Juneau", "America/Sitka", "America/Metlakatla",
        "America/Yakutat", "America/Nome", "America/Adak",
    )
    private val US_ZONE_PREFIXES = listOf("America/Indiana/", "America/Kentucky/", "America/North_Dakota/")

    /** Short date: `11/16` in the US, `16.11` elsewhere. */
    fun shortDatePattern(us: Boolean = isUsRegion()): String = if (us) "M/d" else "dd.MM"
}
