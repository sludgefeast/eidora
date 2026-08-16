// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.util

import org.eidora.util.EidoraLog
import androidx.exifinterface.media.ExifInterface
import com.ashampoo.xmp.XMPMeta
import com.ashampoo.xmp.XMPMetaFactory
import com.ashampoo.xmp.options.PropertyOptions
import com.ashampoo.xmp.options.SerializeOptions
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.eidora.domain.model.FaceRegionCoords
import java.io.File

private const val TAG = "XmpHelper"

private const val NS_MWG_RS = "http://www.metadataworkinggroup.com/schemas/regions/"
private const val NS_ST_AREA = "http://ns.adobe.com/xmp/sType/Area#"
private const val NS_IPTC_EXT = "http://iptc.org/std/Iptc4xmpExt/2008-02-29/"
private const val NS_DC = "http://purl.adobe.com/dc/elements/1.1/"
private const val NS_DIGIKAM = "http://www.digikam.org/ns/1.0/"
private const val NS_LR = "http://ns.adobe.com/lightroom/1.0/"
private const val NS_MWG_KW = "http://www.metadataworkinggroup.com/schemas/keywords/"

data class XmpFaceRegion(
    val name: String?,
    val coords: FaceRegionCoords,
)

object XmpHelper {
    init {
        try {
            XMPMetaFactory.schemaRegistry.registerNamespace(NS_MWG_RS, "mwg-rs")
            XMPMetaFactory.schemaRegistry.registerNamespace(NS_ST_AREA, "stArea")
            XMPMetaFactory.schemaRegistry.registerNamespace(NS_IPTC_EXT, "Iptc4xmpExt")
            XMPMetaFactory.schemaRegistry.registerNamespace(NS_DC, "dc")
            XMPMetaFactory.schemaRegistry.registerNamespace(NS_DIGIKAM, "digiKam")
            XMPMetaFactory.schemaRegistry.registerNamespace(NS_LR, "lr")
        } catch (t: Throwable) {
            EidoraLog.e(TAG, "Failed to register XMP namespaces", t)
        }
    }

    /**
     * Reads the raw XMP bytes from the JPEG and decodes them as UTF-8.
     * XMP is defined by Adobe as always UTF-8; we enforce this explicitly
     * rather than relying on the system default charset.
     */
    private fun readXmpString(exif: ExifInterface): String? {
        // ExifInterface exposes XMP either via getAttributeBytes (raw) or
        // getAttribute (String). We prefer the raw bytes so we can decode
        // with an explicit charset and detect the BOM if present.
        return try {
            val bytes: ByteArray? = exif.getAttributeBytes(ExifInterface.TAG_XMP)
            if (bytes != null && bytes.isNotEmpty()) {
                // Strip UTF-8 BOM (EF BB BF) if present – some writers add it
                val start =
                    if (bytes.size >= 3 &&
                        bytes[0] == 0xEF.toByte() &&
                        bytes[1] == 0xBB.toByte() &&
                        bytes[2] == 0xBF.toByte()
                    ) {
                        3
                    } else {
                        0
                    }
                String(bytes, start, bytes.size - start, Charsets.UTF_8)
            } else {
                // Fallback: getAttribute() returns a String already decoded by
                // ExifInterface; force re-encode + decode to guarantee UTF-8.
                val s = exif.getAttribute(ExifInterface.TAG_XMP) ?: return null
                String(s.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
            }
        } catch (t: Throwable) {
            EidoraLog.w(TAG, "Could not read raw XMP bytes, falling back to string API", t)
            exif.getAttribute(ExifInterface.TAG_XMP)
        }
    }

    /**
     * Serialises XMP to a UTF-8 string and writes it back via ExifInterface.
     * We set the attribute as a UTF-8 string; ExifInterface.saveAttributes()
     * writes the JPEG APP1 segment as-is.
     */
    private fun writeXmpString(
        exif: ExifInterface,
        xmp: XMPMeta,
    ) {
        val serialized =
            XMPMetaFactory.serializeToString(
                xmp,
                SerializeOptions().setOmitXmpMetaElement(false).setUseCompactFormat(true),
            )
        // Verify round-trip: ensure the string contains no characters that
        // would be lost if encoded as UTF-8.
        val utf8Bytes = serialized.toByteArray(Charsets.UTF_8)
        val roundTripped = String(utf8Bytes, Charsets.UTF_8)
        if (roundTripped != serialized) {
            EidoraLog.w(TAG, "XMP UTF-8 round-trip mismatch – data may be corrupted")
        }
        exif.setAttribute(ExifInterface.TAG_XMP, serialized)
    }

    fun readFaceRegions(file: File): List<XmpFaceRegion> {
        return try {
            val exif = ExifInterface(file.absolutePath)
            val xmpString = readXmpString(exif) ?: return emptyList()
            val xmp: XMPMeta = XMPMetaFactory.parseFromString(xmpString)
            val regions = mutableListOf<XmpFaceRegion>()
            val count = xmp.countArrayItems(NS_MWG_RS, "mwg-rs:Regions/mwg-rs:RegionList")

            for (i in 1..count) {
                try {
                    val item = "mwg-rs:Regions/mwg-rs:RegionList[$i]"
                    // Correct MWG layout: Type/Name/Area directly in the item.
                    // Fall back to the old (incorrect) RegionExtensions layout so
                    // photos written by earlier Eidora versions still read.
                    val directType = xmp.getPropertyString(NS_MWG_RS, "$item/mwg-rs:Type")
                    val base =
                        if (directType != null) {
                            item
                        } else {
                            "$item/mwg-rs:RegionExtensions"
                        }
                    val type = xmp.getPropertyString(NS_MWG_RS, "$base/mwg-rs:Type") ?: continue
                    if (type.lowercase() != "face") continue
                    val x = xmp.getPropertyString(NS_MWG_RS, "$base/mwg-rs:Area/stArea:x")?.toFloatOrNull() ?: continue
                    val y = xmp.getPropertyString(NS_MWG_RS, "$base/mwg-rs:Area/stArea:y")?.toFloatOrNull() ?: continue
                    val w = xmp.getPropertyString(NS_MWG_RS, "$base/mwg-rs:Area/stArea:w")?.toFloatOrNull() ?: continue
                    val h = xmp.getPropertyString(NS_MWG_RS, "$base/mwg-rs:Area/stArea:h")?.toFloatOrNull() ?: continue
                    val name = xmp.getPropertyString(NS_MWG_RS, "$base/mwg-rs:Name")
                    regions.add(
                        XmpFaceRegion(
                            name = name?.takeIf { n -> n.isNotBlank() },
                            coords = FaceRegionCoords(x, y, w, h),
                        ),
                    )
                } catch (t: Throwable) {
                    EidoraLog.w(TAG, "Failed to parse region $i in ${file.name}, skipping", t)
                }
            }
            regions
        } catch (t: Throwable) {
            EidoraLog.e(TAG, "Failed to read face regions from ${file.name}", t)
            emptyList()
        }
    }

    fun writeFaceRegions(
        file: File,
        regions: List<XmpFaceRegion>,
        // If non-null and the file has no capture date yet, write this epoch-millis
        // as EXIF DateTimeOriginal in the same save, anchoring chronological order
        // in metadata. Callers pass the file's modification time here.
        fillDateIfMissingMillis: Long? = null,
    ) {
        try {
            val exif = ExifInterface(file.absolutePath)
            // Fill a missing capture date first, so it's part of the single
            // saveAttributes() below (no second write, no race).
            if (fillDateIfMissingMillis != null &&
                exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) == null
            ) {
                val sdf = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
                exif.setAttribute(
                    ExifInterface.TAG_DATETIME_ORIGINAL,
                    sdf.format(java.util.Date(fillDateIfMissingMillis)),
                )
            }
            val xmpString = readXmpString(exif)
            val xmp: XMPMeta =
                if (xmpString != null) {
                    XMPMetaFactory.parseFromString(xmpString)
                } else {
                    XMPMetaFactory.create()
                }

            xmp.deleteProperty(NS_MWG_RS, "mwg-rs:Regions")

            if (regions.isNotEmpty()) {
                val structOpts = PropertyOptions().setStruct(true)
                // MWG defines RegionList as an unordered rdf:Bag (all the
                // real-world digiKam/Picasa/Lightroom samples use Bag, not Seq).
                val arrayOpts = PropertyOptions().setArray(true)
                val emptyOpts = PropertyOptions()

                xmp.setProperty(NS_MWG_RS, "mwg-rs:Regions", null, structOpts)
                xmp.setProperty(NS_MWG_RS, "mwg-rs:Regions/mwg-rs:RegionList", null, arrayOpts)

                regions.forEachIndexed { index, region ->
                    try {
                        // Correct MWG structure: Type/Name/Area sit directly in
                        // the RegionList item — there is NO RegionExtensions
                        // level (that field is only for vendor extensions).
                        val item = "mwg-rs:Regions/mwg-rs:RegionList[${index + 1}]"
                        xmp.setProperty(NS_MWG_RS, item, null, structOpts)
                        xmp.setProperty(NS_MWG_RS, "$item/mwg-rs:Type", "Face", emptyOpts)
                        region.name?.let { n -> xmp.setProperty(NS_MWG_RS, "$item/mwg-rs:Name", n, emptyOpts) }
                        xmp.setProperty(NS_MWG_RS, "$item/mwg-rs:Area", null, structOpts)
                        xmp.setProperty(NS_MWG_RS, "$item/mwg-rs:Area/stArea:x", region.coords.x.toString(), emptyOpts)
                        xmp.setProperty(NS_MWG_RS, "$item/mwg-rs:Area/stArea:y", region.coords.y.toString(), emptyOpts)
                        xmp.setProperty(NS_MWG_RS, "$item/mwg-rs:Area/stArea:w", region.coords.w.toString(), emptyOpts)
                        xmp.setProperty(NS_MWG_RS, "$item/mwg-rs:Area/stArea:h", region.coords.h.toString(), emptyOpts)
                        xmp.setProperty(NS_MWG_RS, "$item/mwg-rs:Area/stArea:unit", "normalized", emptyOpts)
                    } catch (t: Throwable) {
                        EidoraLog.w(TAG, "Failed to write region $index, skipping", t)
                    }
                }
            }

            val confirmedNames = regions.mapNotNull { it.name }.distinct()
            try {
                writePersonTags(xmp, confirmedNames)
            } catch (t: Throwable) {
                EidoraLog.e(TAG, "Failed to write person tags", t)
            }

            writeXmpString(exif, xmp)
            exif.saveAttributes()
        } catch (t: Throwable) {
            EidoraLog.e(TAG, "Failed to write face regions to ${file.name}", t)
        }
    }

    fun clearFaceData(file: File) {
        try {
            val exif = ExifInterface(file.absolutePath)
            val xmpString = readXmpString(exif) ?: return
            val xmp: XMPMeta = XMPMetaFactory.parseFromString(xmpString)
            xmp.deleteProperty(NS_MWG_RS, "mwg-rs:Regions")
            xmp.deleteProperty(NS_IPTC_EXT, "Iptc4xmpExt:PersonInImage")
            xmp.deleteProperty(NS_DIGIKAM, "digiKam:TagsList")
            xmp.deleteProperty(NS_LR, "lr:hierarchicalSubject")
            try {
                clearPeopleSubjects(xmp)
            } catch (t: Throwable) {
                EidoraLog.w(TAG, "clearPeopleSubjects failed", t)
            }
            writeXmpString(exif, xmp)
            exif.saveAttributes()
        } catch (t: Throwable) {
            EidoraLog.e(TAG, "Failed to clear face data from ${file.name}", t)
        }
    }

    private fun writePersonTags(
        xmp: XMPMeta,
        names: List<String>,
    ) {
        val bagOpts = PropertyOptions().setArray(true)
        val seqOpts = PropertyOptions().setArray(true).setArrayOrdered(true)
        val emptyOpts = PropertyOptions()

        // 1. Iptc4xmpExt:PersonInImage – full names
        xmp.deleteProperty(NS_IPTC_EXT, "Iptc4xmpExt:PersonInImage")
        names.forEach { name ->
            xmp.appendArrayItem(NS_IPTC_EXT, "Iptc4xmpExt:PersonInImage", bagOpts, name, emptyOpts)
        }

        // 2. dc:subject – leaf name only (DigiKam style: no prefix, just the name)
        clearPeopleSubjects(xmp)
        names.forEach { name ->
            xmp.appendArrayItem(NS_DC, "dc:subject", bagOpts, name, emptyOpts)
        }

        // 3. digiKam:TagsList – full hierarchical path with /
        xmp.deleteProperty(NS_DIGIKAM, "digiKam:TagsList")
        names.forEach { name ->
            xmp.appendArrayItem(NS_DIGIKAM, "digiKam:TagsList", seqOpts, "People/$name", emptyOpts)
        }

        // 4. lr:hierarchicalSubject – full hierarchical path with |
        xmp.deleteProperty(NS_LR, "lr:hierarchicalSubject")
        names.forEach { name ->
            xmp.appendArrayItem(NS_LR, "lr:hierarchicalSubject", bagOpts, "People|$name", emptyOpts)
        }
    }

    /**
     * Removes person-related entries from dc:subject.
     * Since DigiKam writes only the leaf name (no prefix), we remove entries
     * that match any name in digiKam:TagsList (stripping the "People/" prefix).
     * If TagsList is not available, we cannot distinguish person tags from other
     * subjects, so we leave dc:subject untouched.
     */
    private fun clearPeopleSubjects(xmp: XMPMeta) {
        // Collect current person names from TagsList
        val personNames = mutableSetOf<String>()
        try {
            val count = xmp.countArrayItems(NS_DIGIKAM, "digiKam:TagsList")
            for (i in 1..count) {
                val value = xmp.getArrayItem(NS_DIGIKAM, "digiKam:TagsList", i)?.getValue() ?: continue
                if (value.startsWith("People/")) {
                    personNames.add(value.removePrefix("People/"))
                }
            }
        } catch (t: Throwable) {
            // TagsList may not exist yet
        }

        if (personNames.isEmpty()) return

        val count = xmp.countArrayItems(NS_DC, "dc:subject")
        val toRemove = mutableListOf<Int>()
        for (i in 1..count) {
            val value = xmp.getArrayItem(NS_DC, "dc:subject", i)?.getValue() ?: continue
            if (value in personNames) toRemove.add(i)
        }
        toRemove.reversed().forEach { xmp.deleteArrayItem(NS_DC, "dc:subject", it) }
    }
}

fun FaceRegionCoords.toJson(): String = Json.encodeToString(this)

fun String.toFaceRegionCoords(): FaceRegionCoords = Json.decodeFromString(this)
