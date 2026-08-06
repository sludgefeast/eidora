// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ml.container

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Manages the set of model containers unpacked on disk (list / import / delete).
 *
 * Downloading the bundled free container is [ContainerDownloader]'s job; this
 * store is about everything already on disk: enumerating them for the Models
 * settings screen, importing a user-supplied `.eidoramodel`, and deleting
 * containers or individual models — with the rule that the free container can
 * never be removed.
 *
 * On-disk layout (one dir per container id):
 *
 *     filesDir/containers/<container-id>/manifest.yml
 *     filesDir/containers/<container-id>/<model>.tflite
 */
object ContainerStore {
    private const val TAG = "ContainerStore"

    /** A container present on disk, with its parsed manifest. */
    data class InstalledContainer(
        val id: String,
        val dir: File,
        val manifest: ContainerManifest,
        /** True for the built-in free container, which cannot be deleted. */
        val isProtected: Boolean,
    )

    /**
     * Lists every unpacked container, the free one first, then the rest by
     * display name. Containers whose manifest fails to parse are skipped (they
     * shouldn't exist, but we don't want one bad dir to break the screen).
     */
    fun listContainers(context: Context): List<InstalledContainer> {
        val root = ContainerDownloader.containersRoot(context)
        if (!root.isDirectory) return emptyList()

        val found =
            root.listFiles { f -> f.isDirectory }?.mapNotNull { dir ->
                val manifestFile = File(dir, "manifest.yml")
                if (!manifestFile.isFile) return@mapNotNull null
                val manifest =
                    try {
                        manifestFile.inputStream().use { ContainerManifestParser.parse(it) }
                    } catch (t: Throwable) {
                        Log.w(TAG, "Skipping unreadable container in ${dir.name}: ${t.message}")
                        return@mapNotNull null
                    }
                InstalledContainer(
                    id = manifest.container.id,
                    dir = dir,
                    manifest = manifest,
                    isProtected = manifest.container.id == ContainerDownloader.FREE_CONTAINER_ID,
                )
            }.orEmpty()

        return found.sortedWith(
            compareByDescending<InstalledContainer> { it.isProtected }
                .thenBy { it.manifest.container.name.lowercase() },
        )
    }

    sealed interface ImportResult {
        data class Success(val container: InstalledContainer) : ImportResult

        data class Invalid(val detail: String) : ImportResult

        data class Duplicate(val existingId: String) : ImportResult
    }

    /**
     * Imports a user-supplied container from an open [InputStream] (e.g. a
     * document the user picked). Validates the manifest (Class-1), unpacks into
     * a fresh dir named by container id, and returns the installed container.
     *
     * If a container with the same id already exists, returns [Duplicate]
     * without touching what's on disk — the caller decides whether to replace
     * (via [replaceExisting] = true) or keep both / cancel.
     */
    fun importContainer(
        context: Context,
        input: InputStream,
        replaceExisting: Boolean = false,
    ): ImportResult {
        // Buffer to a temp file so we can read the manifest, then unpack.
        val tmp = File(context.filesDir, "container-import.part")
        try {
            tmp.outputStream().use { input.copyTo(it) }

            val manifest =
                try {
                    readManifestFromZip(tmp)
                } catch (e: ContainerManifestParser.ManifestException) {
                    return ImportResult.Invalid(e.message ?: "invalid manifest")
                } catch (t: Throwable) {
                    return ImportResult.Invalid("could not read manifest: ${t.message}")
                }

            val dir = ContainerDownloader.containerDir(context, manifest.container.id)
            if (dir.exists() && !replaceExisting) {
                return ImportResult.Duplicate(manifest.container.id)
            }
            if (dir.exists()) dir.deleteRecursively()
            dir.mkdirs()

            try {
                unpackInto(tmp, dir, manifest)
            } catch (t: Throwable) {
                dir.deleteRecursively()
                return ImportResult.Invalid("could not unpack container: ${t.message}")
            }

            // Verify each model file's SHA-256 where the manifest declares one.
            // On import we don't trust the source (unlike the repo download,
            // whose whole-container hash we already checked), so a declared hash
            // that doesn't match means the container is corrupt or tampered.
            for (model in manifest.models) {
                val expected = model.sha256 ?: continue
                val actual = sha256Hex(File(dir, model.file))
                if (!actual.equals(expected, ignoreCase = true)) {
                    dir.deleteRecursively()
                    return ImportResult.Invalid(
                        "${model.file}: SHA-256 does not match the manifest",
                    )
                }
            }

            // Class-2 validation: does each output.type fit its tflite structure?
            when (val v = ContainerValidator.validate(dir, manifest)) {
                is ContainerValidator.Result.Ok -> Unit
                is ContainerValidator.Result.Failed -> {
                    dir.deleteRecursively()
                    return ImportResult.Invalid(v.detail)
                }
            }

            Log.i(TAG, "Imported container '${manifest.container.id}'")
            return ImportResult.Success(
                InstalledContainer(
                    id = manifest.container.id,
                    dir = dir,
                    manifest = manifest,
                    isProtected = manifest.container.id == ContainerDownloader.FREE_CONTAINER_ID,
                ),
            )
        } finally {
            tmp.delete()
        }
    }

    /**
     * Deletes an entire container. Refuses to delete the free container.
     * Returns true if something was removed.
     */
    fun deleteContainer(context: Context, containerId: String): Boolean {
        if (containerId == ContainerDownloader.FREE_CONTAINER_ID) {
            Log.w(TAG, "Refusing to delete the protected free container")
            return false
        }
        val dir = ContainerDownloader.containerDir(context, containerId)
        return if (dir.isDirectory) dir.deleteRecursively() else false
    }

    /**
     * Deletes a single model file from a container, rewriting the manifest so
     * the entry is gone. Refuses on the free container. Returns true on success.
     *
     * If removing the model would empty the container, the whole container dir
     * is removed instead.
     */
    fun deleteModel(context: Context, containerId: String, modelId: String): Boolean {
        if (containerId == ContainerDownloader.FREE_CONTAINER_ID) {
            Log.w(TAG, "Refusing to modify the protected free container")
            return false
        }
        val dir = ContainerDownloader.containerDir(context, containerId)
        val manifestFile = File(dir, "manifest.yml")
        if (!manifestFile.isFile) return false

        val manifest =
            try {
                manifestFile.inputStream().use { ContainerManifestParser.parse(it) }
            } catch (t: Throwable) {
                Log.w(TAG, "Cannot parse manifest to delete model: ${t.message}")
                return false
            }

        val target = manifest.models.firstOrNull { it.id == modelId } ?: return false
        val remaining = manifest.models.filter { it.id != modelId }

        if (remaining.isEmpty()) {
            // Nothing left — drop the whole container.
            return dir.deleteRecursively()
        }

        // Remove the model's tflite (only if no other entry references it).
        val stillReferenced = remaining.any { it.file == target.file }
        if (!stillReferenced) File(dir, target.file).delete()

        // Rewrite manifest.yml without the removed entry.
        return rewriteManifestWithoutModel(manifestFile, modelId)
    }

    // --- internals ---

    /**
     * Rewrites manifest.yml, dropping the given model entry. Uses snakeyaml to
     * load, filter, and dump, so the result stays valid YAML. Comments are not
     * preserved (acceptable for a user-imported container we're editing).
     */
    private fun rewriteManifestWithoutModel(manifestFile: File, modelId: String): Boolean {
        return try {
            val yaml = org.yaml.snakeyaml.Yaml()

            @Suppress("UNCHECKED_CAST")
            val root =
                manifestFile.inputStream().use { yaml.load<Any?>(it) } as? MutableMap<String, Any?>
                    ?: return false

            @Suppress("UNCHECKED_CAST")
            val models = root["models"] as? List<Map<String, Any?>> ?: return false
            root["models"] = models.filter { it["id"] != modelId }
            manifestFile.writer().use { yaml.dump(root, it) }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to rewrite manifest", t)
            false
        }
    }

    private fun readManifestFromZip(zip: File): ContainerManifest {
        zip.inputStream().use { fileIn ->
            ZipInputStream(fileIn).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    if (entry.name == "manifest.yml") {
                        val bytes = zin.readBytes()
                        return ContainerManifestParser.parse(bytes.inputStream())
                    }
                    zin.closeEntry()
                }
            }
        }
        throw ContainerManifestParser.ManifestException("manifest.yml not found in container")
    }

    private fun unpackInto(zip: File, dir: File, manifest: ContainerManifest) {
        val wanted = manifest.models.map { it.file }.toSet() + "manifest.yml"
        val canonicalDir = dir.canonicalPath
        zip.inputStream().use { fileIn ->
            ZipInputStream(fileIn).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    val name = entry.name
                    if (entry.isDirectory || name !in wanted) {
                        zin.closeEntry()
                        continue
                    }
                    val out = File(dir, name)
                    if (!out.canonicalPath.startsWith(canonicalDir + File.separator)) {
                        throw SecurityException("zip entry escapes target dir: $name")
                    }
                    out.outputStream().use { zin.copyTo(it) }
                    zin.closeEntry()
                }
            }
        }
        val missing = manifest.models.map { it.file }.filter { !File(dir, it).exists() }
        if (missing.isNotEmpty()) {
            throw IllegalStateException("container missing model file(s): $missing")
        }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
