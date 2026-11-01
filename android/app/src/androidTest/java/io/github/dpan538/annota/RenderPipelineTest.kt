package io.github.dpan538.annota

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.Assert.*
import org.junit.Test

class RenderPipelineTest {
    @Test
    fun manyPagesShareOneParsedPdfAndTrimReleasesIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        val file = java.io.File(context.cacheDir, "raster-pool-${uid()}.pdf")
        PDDocument().use { doc ->
            repeat(417) { doc.addPage(PDPage()) }
            doc.save(file)
        }
        val pool = PdfRasterPool()
        try {
            for (index in listOf(16, 17, 18, 19, 25, 26, 200, 416, 25)) {
                pool.page(file, index) { page ->
                    val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap.recycle()
                }
            }
            assertEquals(1, pool.opens)
            pool.trim()
            pool.page(file, 0) { assertTrue(it.width > 0) }
            assertEquals(2, pool.opens)
        } finally {
            pool.trim()
            file.delete()
        }
    }

    @Test
    fun tabletBudgetGrowsButStaysBoundedAndLowRamIsReduced() {
        val large = RenderMemory.forHeap(512)
        val small = RenderMemory.forHeap(128)
        assertTrue(large.pages > small.pages)
        assertTrue(large.covers > small.covers)
        assertTrue(large.pages + large.covers + large.images + large.words <= 192 * 1024 * 1024)
        assertTrue(RenderMemory.forHeap(128, true).pages < small.pages)
    }
}
