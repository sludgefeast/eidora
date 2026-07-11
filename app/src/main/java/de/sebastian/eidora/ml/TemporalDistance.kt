package de.sebastian.eidora.ml

import kotlin.math.exp

/**
 * Models how much a person's face changes over time. Used to inflate the
 * effective cosine-distance threshold so that faces taken far apart in time
 * need a stronger embedding similarity to be clustered together.
 *
 * Human ageing is not linear: babies change very quickly, adults slowly.
 * Without a known birthdate we cannot adapt per-person, so we use a fixed
 * exponential model with a half-life of ~3 years (a compromise that errs on
 * the cautious side for children while still grouping adult photos sensibly).
 *
 * The returned **temporalPenalty** is added to the cosine distance before
 * comparison with the threshold:
 *
 *   effectiveDistance = cosineDistance + timeWeight × temporalPenalty(Δt)
 *
 * A penalty of 0 means "same moment → no extra distance".
 * A penalty of ~edgeThreshold at Δt = half-life means "faces 3 years apart
 * must be roughly twice as similar in embedding space to be linked".
 */
object TemporalDistance {

    /** Half-life in years: after this interval the penalty equals the reference. */
    private const val HALF_LIFE_YEARS = 3.0f

    /**
     * Returns a penalty in [0..1) that increases with temporal distance.
     *
     * @param takenAtA  epoch-millis of the first photo (0 / null → ignored)
     * @param takenAtB  epoch-millis of the second photo
     * @param weight    0 = disable; 1 = default; >1 = stronger time influence
     * @param reference the base cosine threshold (used to scale the penalty so
     *                  it is expressed in the same unit as cosine distance)
     */
    fun penalty(
        takenAtA: Long?,
        takenAtB: Long?,
        weight: Float,
        reference: Float
    ): Float {
        if (weight <= 0f || takenAtA == null || takenAtB == null) return 0f
        if (takenAtA <= 0L || takenAtB <= 0L) return 0f

        val deltaMs = kotlin.math.abs(takenAtA - takenAtB)
        val deltaYears = deltaMs / (365.25 * 24 * 3600 * 1000).toFloat()

        // Exponential decay: penalty = reference × (1 − e^(−λ × Δt))
        // λ = ln(2) / HALF_LIFE_YEARS so that at HALF_LIFE_YEARS the penalty = reference/2
        val lambda = 0.6931f / HALF_LIFE_YEARS
        val rawPenalty = reference * (1f - exp(-lambda * deltaYears))

        return (rawPenalty * weight).coerceIn(0f, reference)
    }
}
