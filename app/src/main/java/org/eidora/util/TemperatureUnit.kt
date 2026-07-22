// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.util

import android.content.Context
import android.os.Build
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Temperature display handling.
 *
 * Celsius is Eidora's internal unit: everything is stored and compared in °C.
 * Only the presentation layer converts, based on the user's regional
 * preference.
 *
 * Android exposes an explicit temperature-unit preference from API 34
 * (Settings > System > Languages > Regional preferences > Temperature). Below
 * that there is no system-wide setting, so we fall back to the small set of
 * regions that use Fahrenheit.
 */
object TemperatureUnit {
    /** True when temperatures should be presented in Fahrenheit. */
    fun useFahrenheit(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 34) {
            val pref =
                try {
                    androidx.core.text.util.LocalePreferences.getTemperatureUnit(
                        context.resources.configuration.locales[0],
                    )
                } catch (t: Throwable) {
                    null
                }
            if (pref != null) {
                return pref == androidx.core.text.util.LocalePreferences.TemperatureUnit.FAHRENHEIT
            }
        }
        return usesFahrenheitByRegion(context.resources.configuration.locales[0])
    }

    /**
     * Regions that use Fahrenheit for everyday temperatures: the United States
     * (incl. its territories), Liberia, Myanmar, and the Cayman Islands.
     */
    fun usesFahrenheitByRegion(locale: Locale): Boolean =
        locale.country.uppercase(Locale.ROOT) in
            setOf("US", "LR", "MM", "KY", "PW", "FM", "MH", "PR", "GU", "VI", "AS")

    fun celsiusToFahrenheit(celsius: Float): Float = celsius * 9f / 5f + 32f

    fun fahrenheitToCelsius(fahrenheit: Float): Float = (fahrenheit - 32f) * 5f / 9f

    /**
     * Converts a stored Celsius value into the unit shown to the user.
     * Fahrenheit is rounded to whole degrees (battery sensors offer no
     * meaningful precision beyond that once converted); Celsius keeps one
     * decimal, matching the sensor's tenth-of-a-degree resolution.
     */
    fun forDisplay(
        celsius: Float,
        fahrenheit: Boolean,
    ): Float =
        if (fahrenheit) {
            celsiusToFahrenheit(celsius).roundToInt().toFloat()
        } else {
            (celsius * 10f).roundToInt() / 10f
        }

    /**
     * Converts a value the user entered (in the displayed unit) back into the
     * stored Celsius value, rounded to one decimal so that a
     * display -> input -> display round trip stays stable.
     */
    fun fromInput(
        value: Float,
        fahrenheit: Boolean,
    ): Float {
        val celsius = if (fahrenheit) fahrenheitToCelsius(value) else value
        return (celsius * 10f).roundToInt() / 10f
    }

    /** Formats a stored Celsius value with its unit suffix, e.g. "40.0 °C" / "104 °F". */
    fun format(
        celsius: Float,
        fahrenheit: Boolean,
    ): String =
        if (fahrenheit) {
            "${celsiusToFahrenheit(celsius).roundToInt()} °F"
        } else {
            "%.1f °C".format(celsius)
        }
}
