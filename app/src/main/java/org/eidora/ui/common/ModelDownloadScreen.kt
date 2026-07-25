// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eidora.R
import org.eidora.ml.ModelDownloader
import org.eidora.worker.SyncPipeline

/**
 * Full-screen gate shown while the ML models are missing. Explains what will
 * be downloaded (purpose, size, license), checks availability first, and only
 * starts the download after an explicit user tap. No automatic download.
 */
@Composable
fun ModelDownloadScreen(onModelsReady: () -> Unit) {
    val context = LocalContext.current

    // null = availability check in progress
    var availability by remember { mutableStateOf<List<ModelDownloader.ModelAvailability>?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var checkTrigger by remember { mutableStateOf(0) }

    val spec by produceState(org.eidora.ml.EmbeddingModelSpec.DEFAULT) {
        value =
            org.eidora.ml.EmbeddingModelSpec.byId(
                org.eidora.data.settings.SettingsProvider
                    .get(context)
                    .getEmbeddingModelId(),
            )
    }
    val detectionSpec by produceState(org.eidora.ml.DetectionModelSpec.DEFAULT) {
        value =
            org.eidora.ml.DetectionModelSpec.byId(
                org.eidora.data.settings.SettingsProvider
                    .get(context)
                    .getDetectionModelId(),
            )
    }

    LaunchedEffect(checkTrigger, spec, detectionSpec) {
        availability = null
        availability =
            withContext(Dispatchers.IO) {
                ModelDownloader.checkAvailability(context, detectionSpec, spec)
            }
    }

    // While downloading, poll until all models are present, then leave the gate.
    LaunchedEffect(downloading) {
        if (!downloading) return@LaunchedEffect
        while (true) {
            val ready =
                withContext(Dispatchers.IO) {
                    ModelDownloader.allModelsReady(context, detectionSpec, spec)
                }
            if (ready) {
                onModelsReady()
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(2_000)
        }
    }

    val avail = availability
    val allAvailable = avail != null && avail.all { it.available }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.weight(0.15f))

        Box(
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Icon(
                imageVector = Icons.Default.CloudDownload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp),
            )
        }

        Spacer(Modifier.height(24.dp))

        Text(
            stringResource(R.string.model_gate_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))

        Text(
            stringResource(R.string.model_gate_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(24.dp))

        when {
            avail == null -> {
                // Availability check running
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Text(
                        stringResource(R.string.model_size_checking),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
            !allAvailable -> {
                // At least one model missing on the server → likely outdated app
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.model_unavailable_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            stringResource(R.string.model_unavailable_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
            else -> {
                avail.forEachIndexed { index, item ->
                    ModelCard(item)
                    if (index < avail.lastIndex) Spacer(Modifier.height(12.dp))
                }
            }
        }

        Spacer(Modifier.weight(1f))

        when {
            downloading -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(bottom = 24.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Text(
                        stringResource(R.string.model_downloading),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
            avail != null && !allAvailable -> {
                Button(
                    onClick = { checkTrigger++ },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                ) {
                    Text(stringResource(R.string.model_check_retry), style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(24.dp))
            }
            avail != null -> {
                Button(
                    onClick = {
                        downloading = true
                        SyncPipeline.enqueueModelDownload(context)
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                ) {
                    Text(stringResource(R.string.model_download_button), style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ModelCard(item: ModelDownloader.ModelAvailability) {
    // Icon by purpose, not filename: detection → face, embedding → fingerprint.
    // (Filename-based checks broke when the default detector became YuNet.)
    val icon =
        if (item.info.purposeRes == R.string.model_purpose_detection) {
            Icons.Default.Face
        } else {
            Icons.Default.Fingerprint
        }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(start = 16.dp),
            ) {
                Text(item.info.filename, style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(item.info.purposeRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text =
                        buildString {
                            append(formatSize(item.sizeBytes))
                            append(" · ")
                            append(stringResource(R.string.model_license_label))
                            append(": ")
                            append(item.info.license)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun formatSize(bytes: Long?): String =
    when {
        bytes == null -> stringResource(R.string.model_size_unknown)
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        else -> "%d KB".format(bytes / 1_000)
    }
