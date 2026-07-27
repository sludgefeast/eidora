// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ml.container

import android.content.Context
import android.util.Log
import org.eidora.data.settings.SettingsProvider
import org.eidora.ml.EmbeddingModel
import org.eidora.ml.FaceDetector

/**
 * Resolves the model the user has selected in settings into a running detector
 * or embedder, going through the installed containers and [ContainerModelRunner].
 *
 * This is what the pipeline workers call instead of hard-coding YuNet/SFace: the
 * selection is a (containerId, modelId) pair persisted in settings and set when
 * the free container is first downloaded. If no valid selection is stored (e.g.
 * an upgrade from before containers, or the selected model was deleted), it
 * falls back to the first model of the right task in the free container, so the
 * pipeline keeps working rather than stalling.
 */
object SelectedModelResolver {
    private const val TAG = "SelectedModelResolver"

    /** Opens the selected detector, or null if none could be resolved/loaded. */
    suspend fun openDetector(context: Context): FaceDetector? {
        val (container, model) =
            resolve(context, ContainerManifest.TASK_DETECTION) ?: return null
        return try {
            ContainerModelRunner.openDetector(context, container.dir, model).also {
                Log.i(TAG, "Detector: ${container.id}/${model.id}")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to open detector ${container.id}/${model.id}", t)
            null
        }
    }

    /** Opens the selected embedder plus its clustering thresholds, or null. */
    suspend fun openEmbedder(context: Context): EmbedderHandle? {
        val (container, model) =
            resolve(context, ContainerManifest.TASK_EMBEDDING) ?: return null
        return try {
            val embedder = ContainerModelRunner.openEmbedder(context, container.dir, model)
            Log.i(TAG, "Embedder: ${container.id}/${model.id}")
            EmbedderHandle(embedder, model.clustering, container.id, model.id)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to open embedder ${container.id}/${model.id}", t)
            null
        }
    }

    data class EmbedderHandle(
        val embedder: EmbeddingModel,
        val clustering: ContainerManifest.Clustering?,
        val containerId: String,
        val modelId: String,
    )

    /**
     * Finds the container + model entry to use for [task]: the stored selection
     * if valid, else the first model of that task in the free container.
     */
    private suspend fun resolve(
        context: Context,
        task: String,
    ): Pair<ContainerStore.InstalledContainer, ContainerManifest.ModelEntry>? {
        val containers = ContainerStore.listContainers(context)
        if (containers.isEmpty()) return null

        val settings = SettingsProvider.get(context)
        val selected =
            if (task == ContainerManifest.TASK_DETECTION) {
                settings.getSelectedDetection()
            } else {
                settings.getSelectedEmbedding()
            }

        // Try the stored selection first.
        if (selected != null) {
            val c = containers.firstOrNull { it.id == selected.containerId }
            val m = c?.manifest?.models?.firstOrNull {
                it.id == selected.modelId && it.task == task
            }
            if (c != null && m != null) return c to m
            Log.w(TAG, "Stored $task selection ${selected.containerId}/${selected.modelId} " +
                "not found; falling back to free container")
        }

        // Fall back to the first model of this task in the free container.
        val free = containers.firstOrNull { it.id == ContainerDownloader.FREE_CONTAINER_ID }
            ?: containers.first()
        val model = free.manifest.models.firstOrNull { it.task == task } ?: return null
        return free to model
    }
}
