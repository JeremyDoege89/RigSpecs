package dev.rigspecs.data.importing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** PdfRenderer draws pages; pdfbox-android cuts page subsets with their text layer intact. */
class AndroidDocumentPages(context: Context) : DocumentPages {
    private val app = context.applicationContext
    private val pdfboxReady by lazy { PDFBoxResourceLoader.init(app) }

    override suspend fun pageCount(path: String): Int = withContext(Dispatchers.IO) {
        if (!isPdfPath(path)) return@withContext 1
        ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { it.pageCount }
        }
    }

    override suspend fun pdfSubset(path: String, pages: List<Int>): ByteArray = withContext(Dispatchers.IO) {
        pdfboxReady
        PDDocument.load(File(path)).use { source ->
            PDDocument().use { out ->
                pages.forEach { out.importPage(source.getPage(it - 1)) }
                ByteArrayOutputStream().also { out.save(it) }.toByteArray()
            }
        }
    }

    override suspend fun renderJpeg(path: String, page: Int, maxEdge: Int): ByteArray = withContext(Dispatchers.IO) {
        val bitmap = if (isPdfPath(path)) renderPdfPage(path, page - 1, maxEdge) else decodePhoto(path, maxEdge)
        try {
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun renderPdfPage(path: String, index: Int, maxEdge: Int): Bitmap =
        ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                renderer.openPage(index).use { page ->
                    val scale = maxEdge.toFloat() / max(page.width, page.height)
                    val w = (page.width * scale).roundToInt().coerceAtLeast(1)
                    val h = (page.height * scale).roundToInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    // Pages are transparent where nothing is printed; JPEG would turn that black.
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }

    /** Decodes at roughly the target size, then applies the camera's EXIF rotation. */
    private fun decodePhoto(path: String, maxEdge: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        require(bounds.outWidth > 0) { "Not a picture this phone can read" }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val decoded = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Not a picture this phone can read")
        val rotation = when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val scale = minOf(1f, maxEdge.toFloat() / max(decoded.width, decoded.height))
        if (rotation == 0f && scale == 1f) return decoded
        val m = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotation)
        }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true).also {
            if (it !== decoded) decoded.recycle()
        }
    }
}

class AndroidDocumentFiles(context: Context) : DocumentFiles {
    private val dir = File(context.applicationContext.filesDir, "documents").apply { mkdirs() }
    override fun newPath(extension: String): String = File(dir, "${UUID.randomUUID()}.$extension").absolutePath
    override fun delete(path: String) {
        File(path).takeIf { it.parentFile == dir }?.delete()
    }
    override fun exists(path: String) = File(path).isFile
}
