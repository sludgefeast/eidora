// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.worker

import org.eidora.util.EidoraLog

import kotlinx.coroutines.CancellationException

/**
 * Re-throws [CancellationException] so a broad `catch (t: Throwable)` around
 * suspending code does not swallow coroutine cancellation.
 *
 * Swallowing cancellation is a well-known coroutine pitfall: when a worker is
 * stopped (the system reclaims resources, a folder change cancels the run,
 * WorkManager times it out), the suspending call throws CancellationException.
 * If a `catch (Throwable)` absorbs it and continues, the coroutine keeps
 * running in a half-cancelled state, the foreground notification is never
 * dismissed, and follow-up work never starts — the run appears "stuck".
 *
 * Call this first inside such catch blocks:
 * ```
 * } catch (t: Throwable) {
 *     t.rethrowIfCancellation()
 *     EidoraLog.w(TAG, "…", t)
 * }
 * ```
 */
internal fun Throwable.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}
