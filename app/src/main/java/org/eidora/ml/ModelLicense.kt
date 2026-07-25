// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ml

/**
 * The licensing of an ML model, made explicit for the user.
 *
 * ML models carry TWO licenses that are easy to confuse:
 *  - the code/architecture license (the network structure), and
 *  - the WEIGHTS license (the trained numbers), which is usually tied to the
 *    training dataset.
 *
 * In practice the more restrictive of the two governs what a user may do: a
 * permissive code license cannot lift a research-only restriction on the
 * weights. So the app shows the EFFECTIVE license — the restrictive one — plus
 * a short reason, rather than listing two licenses and leaving the user to work
 * out which one binds. All of Eidora's models happen to be gated (if at all) by
 * their weights, not their code.
 *
 * @param isFree           true if the effective license is free/open (usable in
 *                         F-Droid and for any purpose); false if research-only
 *                         or otherwise restricted.
 * @param effectiveNameRes short license name shown to the user (e.g. "Apache-2.0"
 *                         or "Non-commercial (research only)").
 * @param reasonRes        one line explaining WHY — typically what the weights
 *                         were trained on — so the user understands the limit.
 */
data class ModelLicense(
    val isFree: Boolean,
    val effectiveNameRes: Int,
    val reasonRes: Int,
)
