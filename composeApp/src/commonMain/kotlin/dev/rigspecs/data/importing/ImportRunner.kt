package dev.rigspecs.data.importing

import dev.rigspecs.data.ImportRepository
import dev.rigspecs.data.RigRepository
import dev.rigspecs.domain.ImportJobStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reads a job's pages in small batches: each batch goes to the extraction service and its rows
 * are staged for review. Progress is saved after every batch, so a job stopped by lost signal,
 * an error or the app closing resumes from the next unread page.
 */
class ImportRunner(
    private val imports: ImportRepository,
    private val rigs: RigRepository,
    private val extractor: ExtractionService,
    private val pages: DocumentPages,
    private val files: DocumentFiles,
    private val scope: CoroutineScope,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    // Updated with compare-and-set, so starts on the main thread and finishes on workers can't race.
    private val active = MutableStateFlow<Map<String, Job>>(emptyMap())
    private val _running = MutableStateFlow<Set<String>>(emptySet())

    /** Jobs reading pages right now in this process. A RUNNING job not in here was interrupted. */
    val running: StateFlow<Set<String>> = _running.asStateFlow()

    fun start(jobId: String) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                run(jobId)
            } finally {
                active.update { it - jobId }
                _running.update { it - jobId }
            }
        }
        var added = false
        active.update { current ->
            added = jobId !in current
            if (added) current + (jobId to job) else current
        }
        if (!added) return job.cancel()
        _running.update { it + jobId }
        job.start()
    }

    fun cancel(jobId: String) {
        active.value[jobId]?.cancel()
        scope.launch { imports.updateJob(jobId) { it.copy(status = ImportJobStatus.CANCELLED) } }
    }

    internal suspend fun run(jobId: String) {
        val job = imports.job(jobId) ?: return
        try {
            val doc = rigs.document(job.documentId) ?: return fail(jobId, "The document was deleted.")
            val path = doc.localPath
            if (path.isBlank() || !files.exists(path)) return fail(jobId, "The file isn't on this device.")
            val vehicle = rigs.vehicle(job.vehicleId) ?: return fail(jobId, "The rig was deleted.")
            val pdf = isPdfPath(path)
            val count = if (pdf) pages.pageCount(path) else 1
            val wanted = parsePageRanges(job.pages, count) ?: return fail(jobId, "The page list doesn't fit this document.")
            imports.updateJob(jobId) { it.copy(status = ImportJobStatus.RUNNING, pagesTotal = wanted.size, error = "") }

            val context = ExtractVehicle(
                unit = vehicle.unitNumber, type = vehicle.type.label, year = vehicle.year, make = vehicle.make,
                model = vehicle.model, engine = vehicle.engine, chassis = vehicle.chassisMake, body = vehicle.bodyBuilder,
                fields = rigs.fieldNames(vehicle.id).take(MAX_FIELDS),
            )
            var done = job.pagesDone.coerceIn(0, wanted.size)
            var batchSize = if (job.dense) DENSE_BATCH else BATCH
            while (done < wanted.size) {
                val batch = wanted.subList(done, minOf(done + batchSize, wanted.size))
                val input = if (pdf) {
                    val bytes = pages.pdfSubset(path, batch)
                    when {
                        bytes.size <= MAX_PDF_BYTES -> ExtractInput.Pdf(bytes, batch)
                        batch.size > 1 -> {
                            batchSize = maxOf(1, batch.size / 2)
                            continue
                        }
                        // One huge scanned page: send it as a picture instead.
                        else -> ExtractInput.Images(listOf(batch[0] to pages.renderJpeg(path, batch[0], IMAGE_EDGE)))
                    }
                } else {
                    ExtractInput.Images(batch.map { it to pages.renderJpeg(path, it, IMAGE_EDGE) })
                }
                val output = try {
                    withRetries { extractor.extract(context, input, job.dense) }
                } catch (e: ExtractionException.TooMuch) {
                    if (batch.size > 1) {
                        batchSize = maxOf(1, batch.size / 2)
                        continue
                    }
                    return fail(jobId, "Page ${batch[0]} holds more than one pass can read. Import it with the dense option, or skip it.")
                }
                imports.stageRows(jobId, output.rows)
                done += batch.size
                imports.updateJob(jobId) { it.copy(pagesDone = done, model = output.model.ifBlank { it.model }) }
            }
            imports.markRead(jobId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ExtractionException) {
            fail(jobId, e.message ?: "Import stopped.")
        } catch (e: Exception) {
            fail(jobId, "Couldn't read the document: ${e.message ?: e::class.simpleName}")
        }
    }

    private suspend fun <T> withRetries(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: ExtractionException.Temporary) {
                if (++attempt >= RETRY_DELAYS_MS.size + 1) throw e
                pause(RETRY_DELAYS_MS[attempt - 1])
            }
        }
    }

    private suspend fun fail(jobId: String, message: String) = withContext(NonCancellable) {
        imports.updateJob(jobId) { it.copy(status = ImportJobStatus.FAILED, error = message) }
    }

    companion object {
        /** Pages per call: small enough to finish well inside the server function's time limit. */
        const val BATCH = 3
        const val DENSE_BATCH = 2
        const val MAX_PDF_BYTES = 8 * 1024 * 1024

        /** The server reads at most this many profile field names; templates have about 40. */
        const val MAX_FIELDS = 200

        /** Long side for page pictures; larger images are scaled down by the model service anyway. */
        const val IMAGE_EDGE = 1568
        val RETRY_DELAYS_MS = listOf(2_000L, 8_000L)
    }
}
