package de.sebastian.faces.util

import android.util.Log
import androidx.exifinterface.media.ExifInterface
import de.sebastian.faces.domain.model.FaceRegionCoords
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.xmpcore.XmpCore
import org.apache.xmpcore.options.PropertyOptions
import org.apache.xmpcore.options.SerializeOptions
import java.io.File

private const val TAG = "XmpHelper"

// XMP namespaces
private const val NS_MWG_RS = "http://www.metadataworkinggroup.com/schemas/regions/"
private const val NS_IPTC_EXT = "http://iptc.org/std/Iptc4xmpExt/2008-02-29/"
private const val NS_DC = "http://purl.adobe.com/dc/elements/1.1/"

data class XmpFaceRegion(
    val name: String?,
    val coords: FaceRegionCoords
)

object XmpHelper {

    private val xmpMeta by lazy { XmpCore.newXMPMeta() }

    // -----------------------------------------------------------------------
    // Read
    // -----------------------------------------------------------------------

    fun readFaceRegions(file: File): List<XmpFaceRegion> {
        return try {
            val exif = ExifInterface(file.absolutePath)
            val xmpString = exif.getAttribute(ExifInterface.TAG_XMP) ?: return emptyList()
            val xmp = XmpCore.parseFromString(xmpString)

            val regions = mutableListOf<XmpFaceRegion>()
            val count = xmp.countArrayItems(NS_MWG_RS, "mwg-rs:RegionList") 

            for (i in 1..count) {
                val prefix = "mwg-rs:RegionList[$i]/mwg-rs:RegionExtensions"
                val type = xmp.getPropertyString(NS_MWG_RS, "$prefix/mwg-rs:Type") ?: continue
                if (type.lowercase() != "face") continue

                val x = xmp.getPropertyString(NS_MWG_RS, "$prefix/mwg-rs:Area/stArea:x")?.toFloatOrNull() ?: continue
                val y = xmp.getPropertyString(NS_MWG_RS, "$prefix/mwg-rs:Area/stArea:y")?.toFloatOrNull() ?: continue
                val w = xmp.getPropertyString(NS_MWG_RS, "$prefix/mwg-rs:Area/stArea:w")?.toFloatOrNull() ?: continue
                val h = xmp.getPropertyString(NS_MWG_RS, "$prefix/mwg-rs:Area/stArea:h")?.toFloatOrNull() ?: continue
                val name = xmp.getPropertyString(NS_MWG_RS, "$prefix/mwg-rs:Name")

                regions.add(XmpFaceRegion(name = name?.takeIf { it.isNotBlank() }, coords = FaceRegionCoords(x, y, w, h)))
            }
            regions
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read face regions from ${file.name}", e)
            emptyList()
        }
    }

    // -----------------------------------------------------------------------
    // Write
    // -----------------------------------------------------------------------

    /**
     * Writes all face regions to the file.
     * Existing mwg-rs:RegionList is replaced entirely.
     * Also updates PersonInImage and People/ subjects.
     */
    fun writeFaceRegions(file: File, regions: List<XmpFaceRegion>) {
        try {
            val exif = ExifInterface(file.absolutePath)
            val xmpString = exif.getAttribute(ExifInterface.TAG_XMP)
            val xmp = if (xmpString != null) XmpCore.parseFromString(xmpString) else XmpCore.newXMPMeta()

            // Register namespaces
            XmpCore.registerNamespace(NS_MWG_RS, "mwg-rs")
            XmpCore.registerNamespace(NS_IPTC_EXT, "Iptc4xmpExt")
            XmpCore.registerNamespace(NS_DC, "dc")

            // Clear existing regions
            xmp.deleteProperty(NS_MWG_RS, "mwg-rs:Regions")

            if (regions.isNotEmpty()) {
                xmp.setProperty(NS_MWG_RS, "mwg-rs:Regions", null,
                    PropertyOptions().setStruct(true))

                val arrayOptions = PropertyOptions().setArray(true).setArrayOrdered(true)
                xmp.setProperty(NS_MWG_RS, "mwg-rs:Regions/mwg-rs:RegionList", null, arrayOptions)

                regions.forEachIndexed { index, region ->
                    val prefix = "mwg-rs:Regions/mwg-rs:RegionList[${index + 1}]/mwg-rs:RegionExtensions"
                    val structOpts = PropertyOptions().setStruct(true)
                    xmp.setProperty(NS_MWG_RS, "mwg-rs:Regions/mwg-rs:RegionList[${index + 1}]", null, structOpts)
                    xmp.setProperty(NS_MWG_RS, "$prefix/mwg-rs:Type", "Face", null)
                    region.name?.let { xmp.setProperty(NS_MWG_RS, "$prefix/mwg-rs:Name", it, null) }
                    xmp.setProperty(NS_MWG_RS, "$prefix/mwg-rs:Area", null, structOpts)
                    xmp.setProperty(NS_MWG_RS, "$prefix/mwg-rs:Area/stArea:x", region.coords.x.toString(), null)
                    xmp.setProperty(NS_MWG_RS, "$prefix/mwg-rs:Area/stArea:y", region.coords.y.toString(), null)
                    xmp.setProperty(NS_MWG_RS, "$prefix/mwg-rs:Area/stArea:w", region.coords.w.toString(), null)
                    xmp.setProperty(NS_MWG_RS, "$prefix/mwg-rs:Area/stArea:h", region.coords.h.toString(), null)
                    xmp.setProperty(NS_MWG_RS, "$prefix/mwg-rs:Area/stArea:unit", "normalized", null)
                }
            }

            // PersonInImage + People/ subjects
            val confirmedNames = regions.mapNotNull { it.name }.distinct()
            writePersonTags(xmp, confirmedNames)

            val serialized = XmpCore.serializeToString(xmp, SerializeOptions().setOmitXmpMetaElement(false))
            exif.setAttribute(ExifInterface.TAG_XMP, serialized)
            exif.saveAttributes()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write face regions to ${file.name}", e)
        }
    }

    fun clearFaceData(file: File) {
        try {
            val exif = ExifInterface(file.absolutePath)
            val xmpString = exif.getAttribute(ExifInterface.TAG_XMP) ?: return
            val xmp = XmpCore.parseFromString(xmpString)
            xmp.deleteProperty(NS_MWG_RS, "mwg-rs:Regions")
            xmp.deleteProperty(NS_IPTC_EXT, "Iptc4xmpExt:PersonInImage")
            clearPeopleSubjects(xmp)
            val serialized = XmpCore.serializeToString(xmp, SerializeOptions())
            exif.setAttribute(ExifInterface.TAG_XMP, serialized)
            exif.saveAttributes()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear face data from ${file.name}", e)
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private fun writePersonTags(xmp: org.apache.xmpcore.XMPMeta, names: List<String>) {
        // PersonInImage
        xmp.deleteProperty(NS_IPTC_EXT, "Iptc4xmpExt:PersonInImage")
        names.forEach { name ->
            xmp.appendArrayItem(NS_IPTC_EXT, "Iptc4xmpExt:PersonInImage",
                PropertyOptions().setArray(true).setArrayUnordered(true),
                name, null)
        }

        // dc:subject People/ entries
        clearPeopleSubjects(xmp)
        names.forEach { name ->
            xmp.appendArrayItem(NS_DC, "dc:subject",
                PropertyOptions().setArray(true).setArrayUnordered(true).setArrayAlternate(false),
                "People/$name", null)
        }
    }

    private fun clearPeopleSubjects(xmp: org.apache.xmpcore.XMPMeta) {
        val count = xmp.countArrayItems(NS_DC, "dc:subject")
        val toRemove = mutableListOf<Int>()
        for (i in 1..count) {
            val value = xmp.getArrayItem(NS_DC, "dc:subject", i)?.value ?: continue
            if (value.startsWith("People/")) toRemove.add(i)
        }
        // Remove in reverse order to preserve indices
        toRemove.reversed().forEach { index ->
            xmp.deleteArrayItem(NS_DC, "dc:subject", index)
        }
    }
}

// Extension to encode/decode FaceRegionCoords as JSON
fun FaceRegionCoords.toJson(): String = Json.encodeToString(this)
fun String.toFaceRegionCoords(): FaceRegionCoords = Json.decodeFromString(this)
