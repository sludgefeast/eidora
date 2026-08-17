// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ui.settings

import org.eidora.util.EidoraLog
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eidora.R
import org.eidora.ml.container.ContainerManifest
import org.eidora.ml.container.ContainerModelRunner
import org.eidora.ml.container.ContainerStore
import org.eidora.ml.container.SelfTest

private sealed interface TestState {
    data object Running : TestState

    data class Detection(val result: SelfTest.DetectionResult) : TestState

    data class Embedding(val result: SelfTest.EmbeddingResult) : TestState

    data class Failed(val message: String) : TestState
}

/**
 * Runs and shows the self-test for one model of one container. Detection →
 * boxes drawn over the test scene; embedding → a distance table with the
 * manifest thresholds. Optional and non-gating: it only informs.
 */
@Composable
fun SelfTestScreen(
    containerId: String,
    modelId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    val state by produceState<TestState>(TestState.Running, containerId, modelId) {
        value =
            withContext(Dispatchers.IO) {
                runCatching { runTest(context, containerId, modelId) }
                    .getOrElse { e ->
                        when (e) {
                            is ContainerModelRunner.UnsupportedModelException ->
                                TestState.Failed(
                                    context.getString(R.string.selftest_unsupported),
                                )
                            else ->
                                TestState.Failed(
                                    context.getString(
                                        R.string.selftest_error,
                                        e.message ?: e.javaClass.simpleName,
                                    ),
                                )
                        }
                    }
            }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
    ) {
        ScreenHeader(
            title = stringResource(R.string.selftest_title),
            onBack = onBack,
        )
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 16.dp),
        ) {
            when (val s = state) {
                is TestState.Running -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Spacer(Modifier.fillMaxWidth(0.05f))
                        Text(stringResource(R.string.selftest_running))
                    }
                }
                is TestState.Detection -> DetectionView(s.result)
                is TestState.Embedding -> EmbeddingView(s.result)
                is TestState.Failed ->
                    Text(
                        s.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
            }
        }
    }
}

private suspend fun runTest(
    context: android.content.Context,
    containerId: String,
    modelId: String,
): TestState {
    val container =
        ContainerStore.listContainers(context).firstOrNull { it.id == containerId }
            ?: return TestState.Failed("container not found")
    val model =
        container.manifest.models.firstOrNull { it.id == modelId }
            ?: return TestState.Failed("model not found")

    return when (model.task) {
        ContainerManifest.TASK_DETECTION -> {
            val detector = ContainerModelRunner.openDetector(context, container.dir, model)
            try {
                TestState.Detection(SelfTest.runDetection(context, detector))
            } finally {
                detector.close()
            }
        }
        else -> {
            val embedder = ContainerModelRunner.openEmbedder(context, container.dir, model)
            // Also open a detector so the embedding test can recover landmarks and
            // compare aligned vs. un-aligned embeddings. The detector needs the
            // container's DETECTION model (not this embedding model). Best-effort:
            // if none is available, the test still runs the un-aligned comparison.
            val detectionModel =
                container.manifest.models.firstOrNull {
                    it.task == ContainerManifest.TASK_DETECTION
                }
            val detector =
                detectionModel?.let {
                    try {
                        ContainerModelRunner.openDetector(context, container.dir, it)
                    } catch (t: Throwable) {
                        EidoraLog.w("SelfTestScreen", "ContainerModelRunner.openDetector(cont failed: ${t.message}")
                        null
                    }
                }
            try {
                TestState.Embedding(
                    SelfTest.runEmbedding(context, embedder, model.clustering, detector),
                )
            } finally {
                embedder.close()
                detector?.close()
            }
        }
    }
}

@Composable
private fun DetectionView(result: SelfTest.DetectionResult) {
    Text(
        stringResource(R.string.selftest_detection_intro),
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(12.dp))

    result.photos.forEach { photo ->
        val bmp = photo.photo
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat())
                    .drawFaceBoxes(photo.faces, bmp.width, bmp.height),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(
                R.string.selftest_detection_count,
                photo.faces.size,
                photo.expected,
            ),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color =
                if (photo.faces.size == photo.expected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
        )
        Spacer(Modifier.height(16.dp))
    }
    HintLine(result.looksReasonable)
}

/** Draws detection boxes over an Image of known source dimensions. */
private fun Modifier.drawFaceBoxes(
    faces: List<org.eidora.ml.DetectedFace>,
    srcW: Int,
    srcH: Int,
) = this.then(
    Modifier.drawWithContent {
        drawContent()
        val sx = size.width / srcW
        val sy = size.height / srcH
        faces.forEach { f ->
            drawRect(
                color = Color(0xFF00E676),
                topLeft = androidx.compose.ui.geometry.Offset(f.xMin * sx, f.yMin * sy),
                size = androidx.compose.ui.geometry.Size(f.width * sx, f.height * sy),
                style = Stroke(width = 3f),
            )
        }
    },
)

@Composable
private fun EmbeddingView(result: SelfTest.EmbeddingResult) {
    Text(
        stringResource(R.string.selftest_embedding_intro),
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(12.dp))

    result.pairs.forEach { p ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                bitmap = p.thumbA.asImageBitmap(),
                contentDescription = p.personA,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(6.dp)),
            )
            Spacer(Modifier.width(6.dp))
            Image(
                bitmap = p.thumbB.asImageBitmap(),
                contentDescription = p.personB,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(6.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${p.personA} · ${p.personB}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                val kind =
                    if (p.samePerson) {
                        stringResource(R.string.selftest_embedding_same)
                    } else {
                        stringResource(R.string.selftest_embedding_diff)
                    }
                Text(
                    kind,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (p.samePerson) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "%.3f".format(p.distance),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.selftest_embedding_distance),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    if (result.edge != null) {
        Text(
            stringResource(
                R.string.selftest_thresholds,
                "%.2f".format(result.edge),
                result.clusterMatch?.let { "%.2f".format(it) } ?: "—",
                result.individualMatch?.let { "%.2f".format(it) } ?: "—",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Text(
            stringResource(R.string.selftest_thresholds_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(12.dp))
    // Side-by-side: un-aligned vs. aligned same/different spread. A smaller
    // same-max and a larger gap to diff-min means alignment tightened
    // same-person embeddings — the whole point of the change.
    Text(
        "Un-aligned:  same≤ ${result.sameMax?.let { "%.3f".format(it) } ?: "—"}   " +
            "diff≥ ${result.diffMin?.let { "%.3f".format(it) } ?: "—"}",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (result.alignedPairCount > 0) {
        Text(
            "Aligned:     same≤ ${result.alignedSameMax?.let { "%.3f".format(it) } ?: "—"}   " +
                "diff≥ ${result.alignedDiffMin?.let { "%.3f".format(it) } ?: "—"}   " +
                "(${result.alignedPairCount} pairs)",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color =
                if (result.alignedLooksReasonable) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    } else {
        Text(
            "Aligned: no landmark matches (detector found no matching faces)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(12.dp))
    HintLine(result.looksReasonable)
}

@Composable
private fun HintLine(ok: Boolean) {
    Text(
        text =
            if (ok) {
                stringResource(R.string.selftest_hint_ok)
            } else {
                stringResource(R.string.selftest_hint_bad)
            },
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color =
            if (ok) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
    )
}
