package de.sebastian.faces.util

import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.ashampoo.xmp.XMPMeta
import com.ashampoo.xmp.XMPMetaFactory
import com.ashampoo.xmp.options.PropertyOptions
import com.ashampoo.xmp.options.SerializeOptions
import de.sebastian.faces.domain.model.FaceRegionCoords
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

private const val TAG = "XmpHelper"

private const val NS_MWG_RS = "http://www.metadataworkinggroup.com/schemas/regions/"
private const val NS_IPTC_EXT = "http://iptc.org/std/Iptc4xmpExt/2008-02-29/"
private const val NS_DC = "http://purl.adobe.com/dc/elements/1.1/"

data class XmpFaceRegion(
    val name: String?,
    val coords: FaceRegionCoords
)

object XmpHelper {

    init {
        try {
            XMPMetaFactory.schemaRegistry.registerNamespace(NS_MWG_RS, "mwg-rs")
            XMPMetaFactory.schemaRegistry.registerNamespace(NS_IPTC_EXT, "Iptc4xmpExt")
            XMPMetaFactory.schemaRegistry.registerNamespace(NS_DC, "dc")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to register XMP namespaces", t)
        }
    }

    fun readFaceRegions(file: File): List<XmpFaceRegion> {
        return try {
            val exif = ExifInterface(file.absolutePath)
            val xmpString = exif.getAttribute(ExifInterface.TAG_XMP) ?: return emptyList()
            val xmp: XMPMeta = XMPMetaFactory.parseFromString(xmpString)
            val regions = mutableListOf<XmpFaceRegion>()
            val count = xmp.countArrayItems(NS_MWG_RS, "mwg-rs:Regions/mwg-rs:RegionList")

            for (i in 1..count) {
                try {
                    val base = "mwg-rs:Regions/mwg-rs:RegionList[$i]/mwg-rs:RegionExtensions"
                    val type = xmp.getPropertyString(NS_MWG_RS, "$base/mwg-rs:Type") ?: continue
                    if (type.lowercase() != "face") continue
                    val x = xmp.getPropertyString(NS_MWG_RS, "$base/mwg-rs:Area/stArea:x")?.toFloatOrNull() ?: continue
                    val y = xmp.getPropertyString(NS_MWG_RS, "$base/mwg-rs:Area/stArea:y")?.toFloatOrNull() ?: continue
                    val w = xmp.getPropertyString(NS_MWG_RS, "$base/mwg-rs:Area/stArea:w")?.toFloatOrNull() ?: continue
                    val h = xmp.getPropertyString(NS_MWG_RS, "$base/mwg-rs:Area/stArea:h")?.toFloatOrNull() ?: continue
                    val name = xmp.getPropertyString(NS_MWG_RS, "$base/mwg-rs:Name")
                    regions.add(XmpFaceRegion(
                        name = name?.takeIf { n -> n.isNotBlank() },
                        coords = FaceRegionCoords(x, y, w, h)
                    ))
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to parse region $i in ${file.name}, skipping", t)
                }
            }
            regions
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to read face regions from ${file.name}", t)
            emptyList()
        }
    }

    fun writeFaceRegions(file: File, regions: List<XmpFaceRegion>) {
        try {
            val exif = ExifInterface(file.absolutePath)
            val xmpString = exif.getAttribute(ExifInterface.TAG_XMP)
            val xmp: XMPMeta = if (xmpString != null)
                XMPMetaFactory.parseFromString(xmpString)
            else
                XMPMetaFactory.create()

            xmp.deleteProperty(NS_MWG_RS, "mwg-rs:Regions")

            if (regions.isNotEmpty()) {
                val structOpts = PropertyOptions().setStruct(true)
                val arrayOpts = PropertyOptions().setArray(true).setArrayOrdered(true)
                val emptyOpts = PropertyOptions()

                xmp.setProperty(NS_MWG_RS, "mwg-rs:Regions", null, structOpts)
                xmp.setProperty(NS_MWG_RS, "mwg-rs:Regions/mwg-rs:RegionList", null, arrayOpts)

                regions.forEachIndexed { index, region ->
                    try {
                        val item = "mwg-rs:Regions/mwg-rs:RegionList[${index + 1}]"
                        val ext = "$item/mwg-rs:RegionExtensions"
                        xmp.setProperty(NS_MWG_RS, item, null, structOpts)
                        xmp.setProperty(NS_MWG_RS, "$ext/mwg-rs:Type", "Face", emptyOpts)
                        region.name?.let { n -> xmp.setProperty(NS_MWG_RS, "$ext/mwg-rs:Name", n, emptyOpts) }
                        xmp.setProperty(NS_MWG_RS, "$ext/mwg-rs:Area", null, structOpts)
                        xmp.setProperty(NS_MWG_RS, "$ext/mwg-rs:Area/stArea:x", region.coords.x.toString(), emptyOpts)
                        xmp.setProperty(NS_MWG_RS, "$ext/mwg-rs:Area/stArea:y", region.coords.y.toString(), emptyOpts)
                        xmp.setProperty(NS_MWG_RS, "$ext/mwg-rs:Area/stArea:w", region.coords.w.toString(), emptyOpts)
                        xmp.setProperty(NS_MWG_RS, "$ext/mwg-rs:Area/stArea:h", region.coords.h.toString(), emptyOpts)
                        xmp.setProperty(NS_MWG_RS, "$ext/mwg-rs:Area/stArea:unit", "normalized", emptyOpts)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Failed to write region $index, skipping", t)
                    }
                }
            }

            val confirmedNames = regions.mapNotNull { it.name }.distinct()
            try { writePersonTags(xmp, confirmedNames) } catch (t: Throwable) {
                Log.e(TAG, "Failed to write person tags", t)
            }

            val serialized = XMPMetaFactory.serializeToString(
                xmp, SerializeOptions().setOmitXmpMetaElement(false).setUseCompactFormat(true)
            )
            exif.setAttribute(ExifInterface.TAG_XMP, serialized)
            exif.saveAttributes()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to write face regions to ${file.name}", t)
        }
    }

    fun clearFaceData(file: File) {
        try {
            val exif = ExifInterface(file.absolutePath)
            val xmpString = exif.getAttribute(ExifInterface.TAG_XMP) ?: return
            val xmp: XMPMeta = XMPMetaFactory.parseFromString(xmpString)
            xmp.deleteProperty(NS_MWG_RS, "mwg-rs:Regions")
            xmp.deleteProperty(NS_IPTC_EXT, "Iptc4xmpExt:PersonInImage")
            try { clearPeopleSubjects(xmp) } catch (t: Throwable) { Log.w(TAG, "clearPeopleSubjects failed", t) }
            val serialized = XMPMetaFactory.serializeToString(
                xmp, SerializeOptions().setOmitXmpMetaElement(false).setUseCompactFormat(true)
            )
            exif.setAttribute(ExifInterface.TAG_XMP, serialized)
            exif.saveAttributes()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to clear face data from ${file.name}", t)
        }
    }

    private fun writePersonTags(xmp: XMPMeta, names: List<String>) {
        val bagOpts = PropertyOptions().setArray(true)
        val emptyOpts = PropertyOptions()
        xmp.deleteProperty(NS_IPTC_EXT, "Iptc4xmpExt:PersonInImage")
        names.forEach { name ->
            xmp.appendArrayItem(NS_IPTC_EXT, "Iptc4xmpExt:PersonInImage", bagOpts, name, emptyOpts)
        }
        clearPeopleSubjects(xmp)
        names.forEach { name ->
            xmp.appendArrayItem(NS_DC, "dc:subject", bagOpts, "People/$name", emptyOpts)
        }
    }

    private fun clearPeopleSubjects(xmp: XMPMeta) {
        val count = xmp.countArrayItems(NS_DC, "dc:subject")
        val toRemove = mutableListOf<Int>()
        for (i in 1..count) {
            val value = xmp.getArrayItem(NS_DC, "dc:subject", i)?.getValue() ?: continue
            if (value.startsWith("People/")) toRemove.add(i)
        }
        toRemove.reversed().forEach { xmp.deleteArrayItem(NS_DC, "dc:subject", it) }
    }
}

fun FaceRegionCoords.toJson(): String = Json.encodeToString(this)
fun String.toFaceRegionCoords(): FaceRegionCoords = Json.decodeFromString(this)
