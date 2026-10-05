package dev.rigspecs.data

import androidx.room.Room
import dev.rigspecs.data.importing.DocumentFiles
import dev.rigspecs.data.importing.DocumentPages
import dev.rigspecs.data.importing.ExtractInput
import dev.rigspecs.data.importing.ExtractOutput
import dev.rigspecs.data.importing.ExtractVehicle
import dev.rigspecs.data.importing.ExtractionException
import dev.rigspecs.data.importing.ExtractionService
import dev.rigspecs.data.importing.ImportRunner
import dev.rigspecs.data.importing.PingResult
import dev.rigspecs.domain.CandidateRow
import dev.rigspecs.domain.DocumentKind
import dev.rigspecs.domain.Ids
import dev.rigspecs.domain.ImportJobStatus
import dev.rigspecs.domain.Session
import dev.rigspecs.domain.SpecCategory
import dev.rigspecs.domain.VehicleType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImportRunnerTest {
    private object Editor : Session {
        override val departmentId = "d"
        override val userId = "mech"
        override val canEdit = true
    }

    private class SeqIds : Ids {
        var n = 0
        override fun newId() = "id-${++n}"
        override fun now() = 1_000L + n
    }

    /** A 10-page "PDF" whose subsets are just the page numbers, so tests can see what was sent. */
    private class FakePages(private val count: Int = 10, private val hugePages: Set<Int> = emptySet()) : DocumentPages {
        override suspend fun pageCount(path: String) = count
        override suspend fun pdfSubset(path: String, pages: List<Int>): ByteArray =
            if (pages.any { it in hugePages }) ByteArray(ImportRunner.MAX_PDF_BYTES + 1) else pages.joinToString(",").encodeToByteArray()
        override suspend fun renderJpeg(path: String, page: Int, maxEdge: Int) = byteArrayOf(page.toByte())
    }

    private object AllFiles : DocumentFiles {
        override fun newPath(extension: String) = "/tmp/x.$extension"
        override fun delete(path: String) = Unit
        override fun exists(path: String) = true
    }

    /** Answers each call with one torque row per page; [script] can throw for a given call. */
    private class FakeExtractor(private val script: (call: Int, pages: List<Int>) -> Unit = { _, _ -> }) : ExtractionService {
        val calls = mutableListOf<List<Int>>()
        val kinds = mutableListOf<String>()
        override suspend fun extract(vehicle: ExtractVehicle, input: ExtractInput, dense: Boolean): ExtractOutput {
            calls += input.pageNumbers
            kinds += if (input is ExtractInput.Pdf) "pdf" else "images"
            script(calls.size, input.pageNumbers)
            return ExtractOutput(
                input.pageNumbers.map { CandidateRow(ImportRowKind.SPEC, "Bolt $it", "Torque", "$it", "ft-lb", category = SpecCategory.TORQUE, page = it) },
                model = "test-model",
            )
        }
        override suspend fun ping() = PingResult(true, "ok")
    }

    private lateinit var db: AppDatabase
    private lateinit var rigs: RigRepository
    private lateinit var imports: ImportRepository
    private lateinit var vehicleId: String
    private lateinit var docId: String
    private val waits = mutableListOf<Long>()

    @BeforeTest
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder<AppDatabase>().configure(Dispatchers.IO)
        val ids = SeqIds()
        rigs = RigRepository(db, Editor, ids)
        imports = ImportRepository(db, rigs, Editor, ids)
        vehicleId = rigs.saveVehicle(VehicleEntity(id = "", departmentId = "", unitNumber = "E2", type = VehicleType.ENGINE, updatedAt = 0, updatedBy = ""))
        docId = rigs.saveDocument(DocumentEntity(id = "", departmentId = "", vehicleId = vehicleId, title = "Manual", kind = DocumentKind.MANUAL, localPath = "/m.pdf", updatedAt = 0, updatedBy = ""))
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun runner(extractor: ExtractionService, pages: DocumentPages = FakePages()) =
        ImportRunner(imports, rigs, extractor, pages, AllFiles, CoroutineScope(Dispatchers.Unconfined)) { waits += it }

    @Test
    fun readsSelectedPagesInBatchesAndStagesRows() = runTest {
        val extractor = FakeExtractor()
        val job = imports.startJob(vehicleId, docId, "2-6, 9", pagesTotal = 6, dense = false)
        runner(extractor).run(job)
        assertEquals(listOf(listOf(2, 3, 4), listOf(5, 6, 9)), extractor.calls)
        assertEquals(listOf("pdf", "pdf"), extractor.kinds)
        val done = imports.job(job)!!
        assertEquals(ImportJobStatus.REVIEW, done.status)
        assertEquals(6, done.pagesDone)
        assertEquals("test-model", done.model)
        assertEquals(6, imports.observePending(vehicleId).first().size)
    }

    @Test
    fun tooMuchOutputHalvesTheBatch() = runTest {
        val extractor = FakeExtractor { _, pages -> if (pages.size > 1) throw ExtractionException.TooMuch() }
        val job = imports.startJob(vehicleId, docId, "1-3", 3, dense = true)
        runner(extractor).run(job)
        assertEquals(listOf(listOf(1, 2), listOf(1), listOf(2), listOf(3)), extractor.calls)
        assertEquals(ImportJobStatus.REVIEW, imports.job(job)!!.status)
    }

    @Test
    fun hugePagesShrinkTheBatchThenGoAsPictures() = runTest {
        val extractor = FakeExtractor()
        val job = imports.startJob(vehicleId, docId, "1-3", 3, dense = false)
        runner(extractor, FakePages(hugePages = setOf(2))).run(job)
        assertEquals(listOf(listOf(1), listOf(2), listOf(3)), extractor.calls)
        assertEquals(listOf("pdf", "images", "pdf"), extractor.kinds)
    }

    @Test
    fun temporaryErrorsRetryThenFailResumably() = runTest {
        var failures = 0
        val flaky = FakeExtractor { call, _ -> if (call == 2 && failures++ < 1) throw ExtractionException.Temporary("busy") }
        val job = imports.startJob(vehicleId, docId, "1-6", 6, false)
        runner(flaky).run(job)
        assertEquals(listOf(2_000L), waits)
        assertEquals(ImportJobStatus.REVIEW, imports.job(job)!!.status)

        // Always failing: three attempts, then the job stops after the pages already read.
        val down = FakeExtractor { call, _ -> if (call >= 2) throw ExtractionException.Temporary("Service overloaded") }
        val job2 = imports.startJob(vehicleId, docId, "1-6", 6, false)
        runner(down).run(job2)
        val stopped = imports.job(job2)!!
        assertEquals(ImportJobStatus.FAILED, stopped.status)
        assertEquals(3, stopped.pagesDone)
        assertEquals("Service overloaded", stopped.error)
        assertEquals(4, down.calls.size)

        // Resuming reads only what's left.
        val resumed = FakeExtractor()
        runner(resumed).run(job2)
        assertEquals(listOf(listOf(4, 5, 6)), resumed.calls)
        assertEquals(ImportJobStatus.REVIEW, imports.job(job2)!!.status)
    }

    @Test
    fun permanentErrorsStopAtOnce() = runTest {
        val refused = FakeExtractor { _, _ -> throw ExtractionException.Permanent("The server refused this device's key (401). Check Settings.") }
        val job = imports.startJob(vehicleId, docId, "", 10, false)
        runner(refused).run(job)
        assertEquals(1, refused.calls.size)
        assertTrue(imports.job(job)!!.error.contains("401"))
    }

    @Test
    fun badPageListsAndMissingFilesFail() = runTest {
        val job = imports.startJob(vehicleId, docId, "40-50", 11, false)
        runner(FakeExtractor()).run(job)
        assertEquals(ImportJobStatus.FAILED, imports.job(job)!!.status)

        val noFile = object : DocumentFiles by AllFiles {
            override fun exists(path: String) = false
        }
        val job2 = imports.startJob(vehicleId, docId, "", 10, false)
        ImportRunner(imports, rigs, FakeExtractor(), FakePages(), noFile, CoroutineScope(Dispatchers.Unconfined)).run(job2)
        assertEquals("The file isn't on this device.", imports.job(job2)!!.error)
    }

    @Test
    fun photosGoAsOnePicture() = runTest {
        val photoId = rigs.saveDocument(DocumentEntity(id = "", departmentId = "", vehicleId = vehicleId, title = "Label", kind = DocumentKind.PHOTO, localPath = "/label.jpg", updatedAt = 0, updatedBy = ""))
        val extractor = FakeExtractor()
        val job = imports.startJob(vehicleId, photoId, "", 1, false)
        runner(extractor).run(job)
        assertEquals(listOf(listOf(1)), extractor.calls)
        assertEquals(listOf("images"), extractor.kinds)
    }
}
