// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat

/**
 * Central permission checks used by background workers.
 * Workers must verify permissions at runtime because the user can revoke
 * them at any time via the Android settings.
 */
object PermissionChecker {

    /** The media-read permission appropriate for the running Android version. */
    fun mediaPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    /** True if the app may read images from the MediaStore. */
    fun hasMediaAccess(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, mediaPermission()) ==
            PackageManager.PERMISSION_GRANTED

    /** True if the app has All-Files access (needed to write XMP back to files). */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()


    /**
     * The core permissions every worker needs to do useful work:
     * media read access (to load photos) and all-files access (to write XMP).
     * Notification access is NOT required – workers run without it, just silently.
     */
    fun hasWorkerPermissions(context: Context): Boolean =
        hasMediaAccess(context) && hasAllFilesAccess()
}
