package de.timklge.karooheadwind

import de.timklge.karooheadwind.weatherprovider.WeatherInterpretation
import de.timklge.karooheadwind.weatherprovider.WeatherInterpretation.*
import org.junit.Test
import kotlin.test.assertEquals

class WeatherInterpretationTest {
    @Test
    fun codes() {
        assertEquals(CLEAR, WeatherInterpretation.fromWeatherCode(1))
        assertEquals(PARTLY_CLOUDY, WeatherInterpretation.fromWeatherCode(2))
        assertEquals(CLOUDY, WeatherInterpretation.fromWeatherCode(3))
        assertEquals(FOG, WeatherInterpretation.fromWeatherCode(45))
        assertEquals(DRIZZLE, WeatherInterpretation.fromWeatherCode(61))
        assertEquals(RAINY, WeatherInterpretation.fromWeatherCode(63))
        WeatherInterpretation.getKnownWeatherCodes().forEach { assert(WeatherInterpretation.fromWeatherCode(it) != UNKNOWN) }
    }

    @Test
    fun measuredConditionsRefineADrySkyCode() {
        assertEquals(CLEAR, WeatherInterpretation.of(3, 10.0, 0.0), "overcast code, 10 % cloud")
        assertEquals(PARTLY_CLOUDY, WeatherInterpretation.of(0, 45.0, 0.0))
        assertEquals(CLOUDY, WeatherInterpretation.of(1, 90.0, 0.0))
        assertEquals(CLOUDY, WeatherInterpretation.of(3, 0.0, 0.0), "0 % under overcast = cloud cover missing")
        assertEquals(DRIZZLE, WeatherInterpretation.of(2, 80.0, 0.3))
        assertEquals(RAINY, WeatherInterpretation.of(3, 100.0, 2.0))
        assertEquals(SNOWY, WeatherInterpretation.of(3, 100.0, 0.5, -2.0))
        assertEquals(THUNDERSTORM, WeatherInterpretation.of(95, 20.0, 0.0), "wet codes are kept")
        assertEquals(FOG, WeatherInterpretation.of(45, 0.0, 0.0))
    }
}
