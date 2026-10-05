package dev.rigspecs.data.importing

/** Reads document files kept on this device: PDFs and photos. Platform code implements it. */
interface DocumentPages {
    /** Page count of a PDF; 1 for a photo. Throws for files it can't open. */
    suspend fun pageCount(path: String): Int

    /** The given 1-based pages as a standalone PDF, text layer included, for extraction. */
    suspend fun pdfSubset(path: String, pages: List<Int>): ByteArray

    /** One page (or the photo) as a JPEG whose long side is at most [maxEdge] pixels. */
    suspend fun renderJpeg(path: String, page: Int, maxEdge: Int): ByteArray
}

/** Where picked documents are copied on this device. */
interface DocumentFiles {
    /** A new, unused path in app storage for a file with [extension] ("pdf", "jpg"). */
    fun newPath(extension: String): String
    fun delete(path: String)
    fun exists(path: String): Boolean
}

fun isPdfPath(path: String) = path.lowercase().endsWith(".pdf")

/**
 * Parses "1-5, 210-230, 7" into sorted, distinct 1-based pages within [pageCount]. Blank means
 * every page. Returns null when the text isn't a page list.
 */
fun parsePageRanges(text: String, pageCount: Int): List<Int>? {
    val t = text.trim()
    if (t.isEmpty()) return (1..pageCount).toList()
    val pages = mutableSetOf<Int>()
    for (part in t.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }) {
        val m = Regex("""^(\d+)\s*(?:[-–]\s*(\d+))?$""").matchEntire(part) ?: return null
        val a = m.groupValues[1].toIntOrNull() ?: return null
        val b = m.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: a
        if (a < 1 || b < a || b > pageCount) return null
        pages.addAll(a..b)
    }
    return pages.sorted().takeIf { it.isNotEmpty() }
}

/** "1-5, 7" for [1,2,3,4,5,7]. */
fun formatPageRanges(pages: List<Int>): String {
    if (pages.isEmpty()) return ""
    val out = mutableListOf<String>()
    var start = pages[0]
    var prev = pages[0]
    for (p in pages.drop(1) + Int.MIN_VALUE) {
        if (p == prev + 1) {
            prev = p
            continue
        }
        out += if (start == prev) "$start" else "$start-$prev"
        start = p
        prev = p
    }
    return out.joinToString(", ")
}
