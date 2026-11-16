package de.timklge.karooheadwind.util

import io.github.kf6999.truewind.core.alerts.AlertText
import java.time.ZoneId
import java.util.Locale
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisplayPrefsTest {
    @Test
    fun usRegion_fromLocaleOrTimeZone() {
        assertTrue(DisplayPrefs.isUsRegion(Locale.US, "Europe/Berlin"))
        assertTrue(DisplayPrefs.isUsRegion(Locale("en"), "America/Los_Angeles"), "Karoo: locale en, no country")
        assertTrue(DisplayPrefs.isUsRegion(Locale("en"), "America/Indiana/Knox"))
        assertFalse(DisplayPrefs.isUsRegion(Locale("en"), "America/Toronto"))
        assertFalse(DisplayPrefs.isUsRegion(Locale.GERMANY, "America/Los_Angeles"))
        assertEquals("M/d", DisplayPrefs.shortDatePattern(true))
        assertEquals("dd.MM", DisplayPrefs.shortDatePattern(false))
    }

    @Test
    fun clock_12hAnd24h() {
        val t = 1_794_697_500_000L // 2026-11-14 23:05 UTC = 3:05 PM PST
        val la = ZoneId.of("America/Los_Angeles")
        try {
            AlertText.twelveHour = true
            assertEquals("3:05 PM", AlertText.clock(t, la))
            AlertText.twelveHour = false
            assertEquals("15:05", AlertText.clock(t, la))
        } finally {
            AlertText.twelveHour = false
        }
    }
}
