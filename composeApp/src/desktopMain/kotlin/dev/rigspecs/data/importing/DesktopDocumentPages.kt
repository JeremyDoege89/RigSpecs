package dev.rigspecs.data.importing

import dev.rigspecs.data.appDataDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.roundToInt

/** PDFBox does all of it on the desktop: page counts, page subsets and rendering. */
class DesktopDocumentPages : DocumentPages {
    override suspend fun pageCount(path: String): Int = withContext(Dispatchers.IO) {
        if (!isPdfPath(path)) 1 else Loader.loadPDF(File(path)).use { it.numberOfPages }
    }

    override suspend fun pdfSubset(path: String, pages: List<Int>): ByteArray = withContext(Dispatchers.IO) {
        Loader.loadPDF(File(path)).use { source ->
            PDDocument().use { out ->
                pages.forEach { out.importPage(source.getPage(it - 1)) }
                ByteArrayOutputStream().also { out.save(it) }.toByteArray()
            }
        }
    }

    override suspend fun renderJpeg(path: String, page: Int, maxEdge: Int): ByteArray = withContext(Dispatchers.IO) {
        val image = if (isPdfPath(path)) {
            Loader.loadPDF(File(path)).use { doc ->
                val box = doc.getPage(page - 1).cropBox
                val scale = maxEdge / max(box.width, box.height)
                PDFRenderer(doc).renderImage(page - 1, scale, ImageType.RGB)
            }
        } else {
            val src = ImageIO.read(File(path)) ?: error("Not a picture this computer can read")
            fitWithin(src, maxEdge)
        }
        ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }

    private fun fitWithin(src: BufferedImage, maxEdge: Int): BufferedImage {
        val scale = minOf(1.0, maxEdge.toDouble() / max(src.width, src.height))
        val w = (src.width * scale).roundToInt().coerceAtLeast(1)
        val h = (src.height * scale).roundToInt().coerceAtLeast(1)
        return BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { out ->
            val g = out.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.color = Color.WHITE
            g.fillRect(0, 0, w, h)
            g.drawImage(src, 0, 0, w, h, null)
            g.dispose()
        }
    }
}

class DesktopDocumentFiles(private val dir: File = File(appDataDir(), "documents").apply { mkdirs() }) : DocumentFiles {
    override fun newPath(extension: String): String = File(dir, "${UUID.randomUUID()}.$extension").absolutePath
    override fun delete(path: String) {
        File(path).takeIf { it.parentFile == dir }?.delete()
    }
    override fun exists(path: String) = File(path).isFile
}
