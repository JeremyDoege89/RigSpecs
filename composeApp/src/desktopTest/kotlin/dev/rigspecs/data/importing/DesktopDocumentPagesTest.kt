package dev.rigspecs.data.importing

import kotlinx.coroutines.test.runTest
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.text.PDFTextStripper
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopDocumentPagesTest {
    private val dir: File = Files.createTempDirectory("rigspecs-docs").toFile()

    /** A 3-page manual whose page n says "Page n: LF900n". */
    private fun manual(): File = File(dir, "manual.pdf").also { file ->
        PDDocument().use { doc ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            for (n in 1..3) {
                val page = PDPage(PDRectangle.LETTER)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(font, 14f)
                    cs.newLineAtOffset(72f, 700f)
                    cs.showText("Page $n: Oil filter LF900$n")
                    cs.endText()
                }
            }
            doc.save(file)
        }
    }

    @Test
    fun subsetsKeepTheTextLayer() = runTest {
        val pages = DesktopDocumentPages()
        val pdf = manual().absolutePath
        assertEquals(3, pages.pageCount(pdf))
        val subset = pages.pdfSubset(pdf, listOf(2, 3))
        Loader.loadPDF(subset).use { doc ->
            assertEquals(2, doc.numberOfPages)
            val text = PDFTextStripper().getText(doc)
            assertTrue("LF9002" in text && "LF9003" in text && "LF9001" !in text, text)
        }
    }

    @Test
    fun rendersPagesAndPhotosWithinTheSizeLimit() = runTest {
        val pages = DesktopDocumentPages()
        val page = ImageIO.read(ByteArrayInputStream(pages.renderJpeg(manual().absolutePath, 1, 800)))
        // Never over the limit; PDFBox rounds the scaled size down by up to a pixel.
        assertTrue(maxOf(page.width, page.height) in 799..800)
        // Rendered pages are white where nothing is printed, not black.
        assertEquals(0xFFFFFF, page.getRGB(5, 5) and 0xFFFFFF)

        val photo = File(dir, "label.png")
        ImageIO.write(BufferedImage(3000, 1500, BufferedImage.TYPE_INT_RGB), "png", photo)
        assertEquals(1, pages.pageCount(photo.absolutePath))
        val scaled = ImageIO.read(ByteArrayInputStream(pages.renderJpeg(photo.absolutePath, 1, 1568)))
        assertEquals(1568 to 784, scaled.width to scaled.height)
    }
}
