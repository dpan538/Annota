package io.github.dpan538.annota

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.json.JSONObject

/** The original bytes remain authoritative, even when an unlocked viewing copy is required. */
data class PdfInfo(
    val viewingSource: String = "",
    val annotate: Boolean = true,
    val extract: Boolean = true,
    val signatures: Int = 0,
    val inspected: Boolean = false,
    val pageCount: Int = 0,
) {
    fun json() =
        JSONObject()
            .put("viewingSource", viewingSource)
            .put("annotate", annotate)
            .put("extract", extract)
            .put("signatures", signatures)
            .put("inspected", inspected)
            .put("pageCount", pageCount)

    companion object {
        fun from(j: JSONObject?) =
            if (j == null) PdfInfo()
            else
                PdfInfo(
                    j.optString("viewingSource"),
                    j.optBoolean("annotate", true),
                    j.optBoolean("extract", true),
                    j.optInt("signatures"),
                    j.optBoolean("inspected"),
                    j.optInt("pageCount"),
                )

        fun inspect(doc: PDDocument): PdfInfo {
            val access = doc.currentAccessPermission
            val signatures = doc.signatureDictionaries.size
            val permissions = doc.documentCatalog.cosObject.getCOSDictionary(COSName.PERMS)
            val certification = permissions?.getCOSDictionary(COSName.getPDFName("DocMDP"))
            val refs = certification?.getCOSArray(COSName.getPDFName("Reference"))
            var certificationAllowsAnnotations = true
            if (refs != null)
                for (i in 0 until refs.size()) {
                    val ref = refs.getObject(i) as? COSDictionary ?: continue
                    if (ref.getNameAsString(COSName.getPDFName("TransformMethod")) == "DocMDP") {
                        val p =
                            ref.getCOSDictionary(COSName.getPDFName("TransformParams"))
                                ?.getInt(COSName.P, 2) ?: 2
                        if (p < 3) certificationAllowsAnnotations = false
                    }
                }
            return PdfInfo(
                annotate =
                    (access.canModify() || access.canModifyAnnotations()) &&
                        certificationAllowsAnnotations,
                extract = access.canExtractContent(),
                signatures = signatures,
                inspected = true,
                pageCount = doc.numberOfPages,
            )
        }
    }
}
