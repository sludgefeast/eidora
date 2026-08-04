// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ml.container

import org.yaml.snakeyaml.Yaml
import java.io.InputStream

/**
 * Parsed form of a model container's `manifest.yml`
 * (see docs/model-container.md). Only fields the app needs are modelled;
 * unknown fields are ignored so newer manifests stay loadable.
 *
 * This is data only — no TFLite interpreters, no decoding. It describes a set
 * of models so the rest of the app can decide what to load.
 */
data class ContainerManifest(
    val schemaVersion: Int,
    val container: ContainerInfo,
    val models: List<ModelEntry>,
) {
    data class ContainerInfo(
        val id: String,
        val name: String,
        val description: String?,
        val free: Boolean,
        // Monotonic container version, for update comparison. Optional so pre-v1
        // manifests still parse; treated as 0 (oldest) when absent.
        val version: Int,
        // Opaque identifier of the embedding vector space this container's
        // embedder produces. Two containers with the SAME id share embeddings
        // only if this matches; a different value means stored embeddings are
        // incompatible and must be recomputed. Null when the container has no
        // embedder or the author didn't declare one.
        val embeddingSpace: String?,
    )

    data class License(
        val name: String,
        val free: Boolean,
        val reason: String?,
        val url: String?,
    )

    data class InputSpec(
        val color: String, // "RGB" | "BGR"
        val normalization: String, // raw_0_255 | signed_127_127 | signed_127_128 | zero_to_one
        val resize: String?, // detection only: letterbox | stretch
        val padColor: Int, // detection only, default 0
    )

    data class OutputSpec(
        val type: String, // multistride_scrfd | multistride_yunet | single_vector
        // detection:
        val scoreThreshold: Float?,
        val nmsIouThreshold: Float?,
        val rotationFromEyes: Boolean?,
        val scoreIsClsTimesObj: Boolean?,
        // embedding:
        val distance: String?, // "cosine"
        val l2Normalized: Boolean?,
    )

    data class Clustering(
        val edge: Float,
        val clusterMatch: Float,
        val individualMatch: Float,
    )

    data class ModelEntry(
        val id: String,
        val task: String, // "detection" | "embedding"
        val file: String,
        val sha256: String?, // optional hex SHA-256 of the .tflite; checked on import
        val name: String?,
        val description: String?,
        val version: String?,
        val sourceUrl: String?,
        val license: License?,
        val input: InputSpec,
        val output: OutputSpec,
        val clustering: Clustering?, // embedding only
    )

    companion object {
        const val TASK_DETECTION = "detection"
        const val TASK_EMBEDDING = "embedding"

        val KNOWN_TYPES = setOf("multistride_scrfd", "multistride_yunet", "single_vector")
        val KNOWN_NORMALIZATIONS =
            setOf("raw_0_255", "signed_127_127", "signed_127_128", "zero_to_one")
        val KNOWN_RESIZE = setOf("letterbox", "stretch")
        val SUPPORTED_SCHEMA = setOf(1)
    }
}

/**
 * Parses a container manifest from YAML. Throws [ManifestException] with a
 * specific message on any structural problem (Class-1 validation), so callers
 * can show the user why a container was rejected.
 *
 * This performs Class-1 checks only (well-formedness). Class-2 (does output.type
 * fit the tflite?) and Class-3 (on-device self-test) happen elsewhere.
 */
object ContainerManifestParser {
    class ManifestException(message: String) : Exception(message)

    fun parse(input: InputStream): ContainerManifest {
        val root =
            try {
                @Suppress("UNCHECKED_CAST")
                Yaml().load<Any?>(input) as? Map<String, Any?>
                    ?: throw ManifestException("manifest.yml is empty or not a mapping")
            } catch (e: ManifestException) {
                throw e
            } catch (e: Throwable) {
                throw ManifestException("manifest.yml is not valid YAML: ${e.message}")
            }

        val schema = (root["schema_version"] as? Int)
            ?: throw ManifestException("schema_version is missing or not an integer")
        if (schema !in ContainerManifest.SUPPORTED_SCHEMA) {
            throw ManifestException(
                "unsupported schema_version $schema (this app supports " +
                    "${ContainerManifest.SUPPORTED_SCHEMA}); update Eidora",
            )
        }

        val container = parseContainer(root["container"])
        val modelsRaw = root["models"] as? List<*>
            ?: throw ManifestException("models: must be a non-empty list")
        if (modelsRaw.isEmpty()) throw ManifestException("models: must be a non-empty list")

        val models = modelsRaw.mapIndexed { i, m -> parseModel(i, m) }

        return ContainerManifest(schema, container, models)
    }

    private fun parseContainer(raw: Any?): ContainerManifest.ContainerInfo {
        @Suppress("UNCHECKED_CAST")
        val m = raw as? Map<String, Any?>
            ?: throw ManifestException("container: section is missing")
        val id = (m["id"] as? String)?.takeIf { it.isNotBlank() }
            ?: throw ManifestException("container.id is required")
        val name = (m["name"] as? String) ?: id
        return ContainerManifest.ContainerInfo(
            id = id,
            name = name,
            description = m["description"] as? String,
            free = m["free"] as? Boolean ?: false,
            version = (m["version"] as? Int) ?: 0,
            embeddingSpace = (m["embedding_space"] as? String)?.takeIf { it.isNotBlank() },
        )
    }

    private fun parseModel(index: Int, raw: Any?): ContainerManifest.ModelEntry {
        val where = "models[$index]"
        @Suppress("UNCHECKED_CAST")
        val m = raw as? Map<String, Any?>
            ?: throw ManifestException("$where is not a mapping")

        val id = (m["id"] as? String)?.takeIf { it.isNotBlank() }
            ?: throw ManifestException("$where: id is required")
        val task = (m["task"] as? String)
            ?: throw ManifestException("$where: task is required")
        if (task != ContainerManifest.TASK_DETECTION && task != ContainerManifest.TASK_EMBEDDING) {
            throw ManifestException("$where: unknown task '$task'")
        }
        val file = (m["file"] as? String)?.takeIf { it.isNotBlank() }
            ?: throw ManifestException("$where: file is required")

        val input = parseInput(where, task, m["input"])
        val output = parseOutput(where, task, m["output"])
        val clustering = parseClustering(m["clustering"])

        return ContainerManifest.ModelEntry(
            id = id,
            task = task,
            file = file,
            sha256 = (m["sha256"] as? String)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
            name = m["name"] as? String,
            description = m["description"] as? String,
            version = m["version"] as? String,
            sourceUrl = m["source_url"] as? String,
            license = parseLicense(m["license"]),
            input = input,
            output = output,
            clustering = clustering,
        )
    }

    private fun parseLicense(raw: Any?): ContainerManifest.License? {
        @Suppress("UNCHECKED_CAST")
        val m = raw as? Map<String, Any?> ?: return null
        val name = m["name"] as? String ?: return null
        return ContainerManifest.License(
            name = name,
            free = m["free"] as? Boolean ?: false,
            reason = m["reason"] as? String,
            url = m["url"] as? String,
        )
    }

    private fun parseInput(where: String, task: String, raw: Any?): ContainerManifest.InputSpec {
        @Suppress("UNCHECKED_CAST")
        val m = raw as? Map<String, Any?>
            ?: throw ManifestException("$where: input section is missing")
        val norm = (m["normalization"] as? String)
            ?: throw ManifestException("$where: input.normalization is required")
        if (norm !in ContainerManifest.KNOWN_NORMALIZATIONS) {
            throw ManifestException("$where: unknown input.normalization '$norm'")
        }
        val resize = m["resize"] as? String
        if (task == ContainerManifest.TASK_DETECTION) {
            if (resize == null || resize !in ContainerManifest.KNOWN_RESIZE) {
                throw ManifestException(
                    "$where: detection needs input.resize (letterbox|stretch)",
                )
            }
        }
        return ContainerManifest.InputSpec(
            color = m["color"] as? String ?: "RGB",
            normalization = norm,
            resize = resize,
            padColor = (m["pad_color"] as? Int) ?: 0,
        )
    }

    private fun parseOutput(where: String, task: String, raw: Any?): ContainerManifest.OutputSpec {
        @Suppress("UNCHECKED_CAST")
        val m = raw as? Map<String, Any?>
            ?: throw ManifestException("$where: output section is missing")
        val type = (m["type"] as? String)
            ?: throw ManifestException("$where: output.type is required")
        if (type !in ContainerManifest.KNOWN_TYPES) {
            throw ManifestException("$where: unknown output.type '$type'")
        }
        // task/type coherence
        if (task == ContainerManifest.TASK_EMBEDDING && type != "single_vector") {
            throw ManifestException("$where: embedding model must use output.type single_vector")
        }
        if (task == ContainerManifest.TASK_DETECTION && type == "single_vector") {
            throw ManifestException("$where: detection model cannot use output.type single_vector")
        }
        return ContainerManifest.OutputSpec(
            type = type,
            scoreThreshold = asFloat(m["score_threshold"]),
            nmsIouThreshold = asFloat(m["nms_iou_threshold"]),
            rotationFromEyes = m["rotation_from_eyes"] as? Boolean,
            scoreIsClsTimesObj = m["score_is_cls_times_obj"] as? Boolean,
            distance = m["distance"] as? String,
            l2Normalized = m["l2_normalized"] as? Boolean,
        )
    }

    private fun parseClustering(raw: Any?): ContainerManifest.Clustering? {
        @Suppress("UNCHECKED_CAST")
        val m = raw as? Map<String, Any?> ?: return null
        val edge = asFloat(m["edge"]) ?: return null
        val clusterMatch = asFloat(m["cluster_match"]) ?: return null
        val individualMatch = asFloat(m["individual_match"]) ?: return null
        return ContainerManifest.Clustering(edge, clusterMatch, individualMatch)
    }

    private fun asFloat(v: Any?): Float? =
        when (v) {
            is Number -> v.toFloat()
            is String -> v.toFloatOrNull()
            else -> null
        }
}
