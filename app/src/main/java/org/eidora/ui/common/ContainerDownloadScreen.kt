// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eidora.R
import org.eidora.ml.container.ContainerDownloader
import org.eidora.ml.container.ContainerManifest

/**
 * First-run screen that downloads the free model container (YuNet + SFace).
 * Deliberately minimal: one set, one button, clear progress and errors. Other
 * models are added later via "bring your own model", not here.
 */
@Composable
fun ContainerDownloadScreen(onReady: () -> Unit) {
    val context = LocalContext.current

    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var start by remember { mutableStateOf(false) }

    LaunchedEffect(start) {
        if (!start) return@LaunchedEffect
        downloading = true
        error = null
        val result =
            withContext(Dispatchers.IO) {
                ContainerDownloader.downloadFreeContainer(context) { p -> progress = p }
            }
        downloading = false
        when (result) {
            is ContainerDownloader.Result.Success -> {
                // Record which detector/embedder to use: the first of each task
                // in the freshly downloaded container.
                val manifest = result.manifest
                val detection =
                    manifest.models.firstOrNull { it.task == ContainerManifest.TASK_DETECTION }
                val embedding =
                    manifest.models.firstOrNull { it.task == ContainerManifest.TASK_EMBEDDING }
                withContext(Dispatchers.IO) {
                    val settings = org.eidora.data.settings.SettingsProvider.get(context)
                    if (detection != null) {
                        settings.setSelectedDetection(manifest.container.id, detection.id)
                    }
                    if (embedding != null) {
                        settings.setSelectedEmbedding(manifest.container.id, embedding.id)
                    }
                }
                onReady()
            }
            is ContainerDownloader.Result.NetworkError ->
                error = context.getString(R.string.container_error_network)
            is ContainerDownloader.Result.HashMismatch ->
                error = context.getString(R.string.container_error_hash)
            is ContainerDownloader.Result.Invalid ->
                error = context.getString(R.string.container_error_invalid)
        }
        start = false
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(0.2f))

        Image(
            painter = painterResource(R.drawable.ic_eidora_logo),
            contentDescription = null,
            modifier = Modifier.size(96.dp),
        )
        Spacer(Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.container_download_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.container_download_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(32.dp))

        when {
            downloading -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(
                    text =
                        if (progress in 1..99) {
                            "${stringResource(R.string.container_downloading)} $progress%"
                        } else {
                            stringResource(R.string.container_downloading)
                        },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            else -> {
                Button(
                    onClick = { start = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (error != null) R.string.container_retry else R.string.container_download_button,
                        ),
                    )
                }
                error?.let {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        Spacer(Modifier.weight(0.4f))
    }
}
