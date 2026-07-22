// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.Locale

@DisplayName("TemperatureUnit")
class TemperatureUnitTest {
    private val tolerance = 1e-3f

    @Nested
    @DisplayName("conversion")
    inner class Conversion {
        @ParameterizedTest(name = "{0} °C = {1} °F")
        @CsvSource(
            "0, 32",
            "100, 212",
            "-40, -40",
            "37, 98.6",
            "40, 104",
        )
        fun celsiusToFahrenheit(
            celsius: Float,
            fahrenheit: Float,
        ) {
            assertEquals(fahrenheit, TemperatureUnit.celsiusToFahrenheit(celsius), tolerance)
        }

        @ParameterizedTest(name = "{1} °F = {0} °C")
        @CsvSource(
            "0, 32",
            "100, 212",
            "-40, -40",
            "40, 104",
        )
        fun fahrenheitToCelsius(
            celsius: Float,
            fahrenheit: Float,
        ) {
            assertEquals(celsius, TemperatureUnit.fahrenheitToCelsius(fahrenheit), tolerance)
        }

        @ParameterizedTest(name = "round trip at {0} °C")
        @ValueSource(floats = [-10f, 0f, 20.5f, 37.2f, 40f, 45.9f])
        fun roundTrip(celsius: Float) {
            val back = TemperatureUnit.fahrenheitToCelsius(TemperatureUnit.celsiusToFahrenheit(celsius))
            assertEquals(celsius, back, tolerance)
        }
    }

    @Nested
    @DisplayName("display rounding")
    inner class Display {
        @Test
        fun `celsius keeps one decimal`() {
            assertEquals(40.0f, TemperatureUnit.forDisplay(40.04f, fahrenheit = false), tolerance)
            assertEquals(40.1f, TemperatureUnit.forDisplay(40.06f, fahrenheit = false), tolerance)
        }

        @Test
        fun `fahrenheit rounds to whole degrees`() {
            // 40.0 °C = 104.0 °F
            assertEquals(104f, TemperatureUnit.forDisplay(40.0f, fahrenheit = true), tolerance)
            // 37.2 °C = 98.96 °F -> 99
            assertEquals(99f, TemperatureUnit.forDisplay(37.2f, fahrenheit = true), tolerance)
        }
    }

    @Nested
    @DisplayName("input handling")
    inner class Input {
        @Test
        fun `celsius input is rounded to one decimal`() {
            assertEquals(40.1f, TemperatureUnit.fromInput(40.06f, fahrenheit = false), tolerance)
        }

        @Test
        fun `fahrenheit input converts and rounds to one decimal`() {
            // 105 °F = 40.5555... °C -> 40.6
            assertEquals(40.6f, TemperatureUnit.fromInput(105f, fahrenheit = true), tolerance)
            // 104 °F = exactly 40 °C
            assertEquals(40.0f, TemperatureUnit.fromInput(104f, fahrenheit = true), tolerance)
        }

        @ParameterizedTest(name = "display->input->display is stable at {0} °F")
        @ValueSource(floats = [95f, 99f, 104f, 105f, 110f])
        fun displayInputRoundTripStable(shownFahrenheit: Float) {
            val storedCelsius = TemperatureUnit.fromInput(shownFahrenheit, fahrenheit = true)
            val shownAgain = TemperatureUnit.forDisplay(storedCelsius, fahrenheit = true)
            assertEquals(shownFahrenheit, shownAgain, tolerance)
        }
    }

    @Nested
    @DisplayName("region detection")
    inner class Region {
        @ParameterizedTest(name = "{0} uses Fahrenheit")
        @ValueSource(strings = ["US", "LR", "MM", "KY"])
        fun fahrenheitRegions(country: String) {
            assertTrue(TemperatureUnit.usesFahrenheitByRegion(Locale("en", country)))
        }

        @ParameterizedTest(name = "{0} uses Celsius")
        @ValueSource(strings = ["DE", "GB", "FR", "AT", "CH", "JP", "CN"])
        fun celsiusRegions(country: String) {
            assertFalse(TemperatureUnit.usesFahrenheitByRegion(Locale("de", country)))
        }

        @Test
        fun `country code is matched case-insensitively`() {
            assertTrue(TemperatureUnit.usesFahrenheitByRegion(Locale("en", "us")))
        }
    }

    @Nested
    @DisplayName("formatting")
    inner class Formatting {
        @Test
        fun `celsius format has one decimal and unit`() {
            val s = TemperatureUnit.format(40.0f, fahrenheit = false)
            assertTrue(s.contains("°C"), s)
            // decimal separator depends on locale (40.0 / 40,0) - accept both
            assertTrue(s.contains("40.0") || s.contains("40,0"), s)
        }

        @Test
        fun `fahrenheit format has no decimals and unit`() {
            val s = TemperatureUnit.format(40.0f, fahrenheit = true)
            assertTrue(s.contains("°F"), s)
            assertTrue(s.contains("104"), s)
            assertFalse(s.contains(".") || s.contains(","), s)
        }
    }
}
