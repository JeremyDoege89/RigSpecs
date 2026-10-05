package dev.rigspecs.data

import androidx.room.Room
import dev.rigspecs.domain.CandidateRow
import dev.rigspecs.domain.DocumentKind
import dev.rigspecs.domain.Ids
import dev.rigspecs.domain.ImportJobStatus
import dev.rigspecs.domain.ImportRowState
import dev.rigspecs.domain.PartSystem
import dev.rigspecs.domain.Session
import dev.rigspecs.domain.SourceKind
import dev.rigspecs.domain.SpecCategory
import dev.rigspecs.domain.VehicleType
import dev.rigspecs.domain.VerificationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class CountingIds : Ids {
    var n = 0
    var clock = 1_000L
    override fun newId() = "id-${++n}"
    override fun now() = clock++
}

private object Editor : Session {
    override val departmentId = "dept-1"
    override val userId = "mechanic-1"
    override val canEdit = true
}

private fun specRow(component: String, name: String, value: String, unit: String = "", page: Int = 1, category: SpecCategory = SpecCategory.TORQUE) =
    CandidateRow(ImportRowKind.SPEC, component, name, value, unit, category = category, page = page, evidence = "$component $value $unit")

private fun partRow(component: String, oem: String, vararg refs: String, page: Int = 2) =
    CandidateRow(ImportRowKind.PART, component, oemNumber = oem, aftermarketNumbers = refs.toList(), system = PartSystem.ENGINE, page = page)

class ImportRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var rigs: RigRepository
    private lateinit var imports: ImportRepository
    private lateinit var vehicleId: String
    private lateinit var docId: String

    @BeforeTest
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder<AppDatabase>().configure(Dispatchers.IO)
        val ids = CountingIds()
        rigs = RigRepository(db, Editor, ids)
        imports = ImportRepository(db, rigs, Editor, ids)
        vehicleId = rigs.saveVehicle(
            VehicleEntity(id = "", departmentId = "", unitNumber = "Engine 2", type = VehicleType.ENGINE, updatedAt = 0, updatedBy = ""),
            rigs.templateItems(VehicleType.ENGINE),
        )
        docId = rigs.saveDocument(
            DocumentEntity(id = "", departmentId = "", vehicleId = vehicleId, title = "Delivery manual", kind = DocumentKind.MANUAL, localPath = "/m.pdf", updatedAt = 0, updatedBy = ""),
        )
    }

    @AfterTest
    fun tearDown() = db.close()

    private suspend fun pending() = imports.observePending(vehicleId).first()
    private suspend fun specs() = rigs.observeSpecs(vehicleId).first()

    @Test
    fun stagedRowsStayOutOfTheProfileUntilConfirmed() = runTest {
        val before = specs().size
        val job = imports.startJob(vehicleId, docId, pages = "", pagesTotal = 3, dense = false)
        val waiting = imports.stageRows(job, listOf(specRow("Lug nuts", "Torque", "450", "ft-lb", page = 212), specRow("Coolant", "Type", "ELC")))
        assertEquals(2, waiting)
        assertEquals(before, specs().size)
        assertTrue(specs().first { it.component == "Lug nuts" && it.name == "Torque" }.value.isEmpty())
        assertEquals(2, imports.job(job)!!.rowsExtracted)
    }

    @Test
    fun confirmingFillsTheTemplateBlankWithProvenance() = runTest {
        val job = imports.startJob(vehicleId, docId, "", 3, false)
        imports.stageRows(job, listOf(specRow("Lug nuts", "Torque", "450", "ft-lb", page = 212)))
        val row = pending().single()
        val result = imports.confirmRow(row.id)
        assertIs<ConfirmResult.Confirmed>(result)
        val lug = specs().first { it.component == "Lug nuts" && it.name == "Torque" }
        assertEquals(result.itemId, lug.id)
        assertEquals("450", lug.value)
        assertEquals(VerificationStatus.VERIFIED, lug.status)
        assertEquals(SourceKind.IMPORT, lug.sourceKind)
        assertEquals(docId, lug.sourceDocId)
        assertEquals("p. 212", lug.pageHint)
        assertEquals("mechanic-1", lug.verifiedBy)
        assertTrue(lug.safetyCritical)
        assertEquals(ImportRowState.CONFIRMED, imports.row(row.id)!!.state)
        assertEquals(1, imports.job(job)!!.rowsConfirmed)
    }

    @Test
    fun aDifferentValueNeedsAnExplicitReplace() = runTest {
        val lugId = specs().first { it.component == "Lug nuts" && it.name == "Torque" }.id
        rigs.saveSpec(rigs.spec(lugId)!!.copy(value = "475", unit = "ft-lb"))
        val job = imports.startJob(vehicleId, docId, "", 1, false)
        imports.stageRows(job, listOf(specRow("Lug nuts", "Torque", "450", "ft-lb")))
        val row = pending().single()

        assertEquals(ConfirmResult.NeedsChoice("475 ft-lb"), imports.confirmRow(row.id))
        assertEquals("475", rigs.spec(lugId)!!.value)
        assertEquals(ImportRowState.PENDING, imports.row(row.id)!!.state)

        assertIs<ConfirmResult.Confirmed>(imports.confirmRow(row.id, replace = true))
        assertEquals("450", rigs.spec(lugId)!!.value)
        // The old value is in the history.
        val log = db.changeLogDao().listFor(lugId).first { it.field == "value" }
        assertEquals("475" to "450", log.oldValue to log.newValue)
    }

    @Test
    fun editsAreAppliedAndCounted() = runTest {
        val job = imports.startJob(vehicleId, docId, "", 1, false)
        imports.stageRows(job, listOf(partRow("Oil filter", "LF9O09", "NAPA 1792")))
        val row = pending().single()
        imports.confirmRow(row.id, RowEdit(oemNumber = "LF9009"))
        val oil = rigs.observeParts(vehicleId).first().first { it.component == "Oil filter" }
        assertEquals("LF9009", oil.oemNumber)
        assertEquals("NAPA 1792", oil.aftermarketNumbers)
        assertEquals(true, imports.row(row.id)!!.edited)
    }

    @Test
    fun newRowsAreAddedAndRejectedRowsStayOut() = runTest {
        val job = imports.startJob(vehicleId, docId, "", 1, false)
        imports.stageRows(job, listOf(specRow("Front axle", "Hub oil", "SAE 80W-90", category = SpecCategory.FLUID), specRow("Mirror", "Torque", "9", "ft-lb")))
        val (axle, mirror) = pending().sortedBy { it.sortOrder }
        imports.confirmRow(axle.id)
        imports.rejectRow(mirror.id)
        val all = specs()
        assertTrue(all.any { it.component == "Front axle" && it.value == "SAE 80W-90" && it.sourceKind == SourceKind.IMPORT })
        assertTrue(all.none { it.component == "Mirror" })
        assertTrue(pending().isEmpty())
    }

    @Test
    fun aRerunReplacesPendingRowsButKeepsDecisions() = runTest {
        val first = imports.startJob(vehicleId, docId, "", 2, false)
        imports.stageRows(first, listOf(
            specRow("Lug nuts", "Torque", "450", "ft-lb"),
            specRow("Mirror", "Torque", "9", "ft-lb"),
            specRow("Coolant", "Type", "ELC"),
        ))
        imports.markRead(first)
        val rows = pending().associateBy { it.component }
        imports.confirmRow(rows.getValue("Lug nuts").id)
        imports.rejectRow(rows.getValue("Mirror").id)
        // Coolant is left waiting.

        val second = imports.startJob(vehicleId, docId, "", 2, false)
        assertEquals(ImportRowState.SUPERSEDED, imports.row(rows.getValue("Coolant").id)!!.state)
        val waiting = imports.stageRows(second, listOf(
            specRow("Lug nuts", "Torque", "450", "ft-lb"),
            specRow("Mirror", "Torque", "9", "ft-lb"),
            specRow("Coolant", "Type", "ELC"),
            specRow("Coolant", "Type", "ELC"),
        ))
        assertEquals(1, waiting)
        val states = db.importDao().rowsForJob(second).map { it.component to it.state }
        assertEquals(
            listOf(
                "Lug nuts" to ImportRowState.DUPLICATE,
                "Mirror" to ImportRowState.REJECTED,
                "Coolant" to ImportRowState.PENDING,
                "Coolant" to ImportRowState.DUPLICATE,
            ),
            states,
        )
        // The confirmed value is untouched.
        assertEquals("450", specs().first { it.component == "Lug nuts" && it.name == "Torque" }.value)
    }

    @Test
    fun jobsFinishWhenEveryRowIsDecided() = runTest {
        val job = imports.startJob(vehicleId, docId, "", 1, false)
        imports.stageRows(job, listOf(specRow("Coolant", "Type", "ELC")))
        imports.markRead(job)
        assertEquals(ImportJobStatus.REVIEW, imports.job(job)!!.status)
        imports.rejectRow(pending().single().id)
        assertEquals(ImportJobStatus.DONE, imports.job(job)!!.status)

        val empty = imports.startJob(vehicleId, docId, "", 1, false)
        imports.markRead(empty)
        assertEquals(ImportJobStatus.DONE, imports.job(empty)!!.status)
    }

    @Test
    fun deletingTheDocumentDropsItsPendingRows() = runTest {
        val job = imports.startJob(vehicleId, docId, "", 1, false)
        imports.stageRows(job, listOf(specRow("Coolant", "Type", "ELC")))
        rigs.deleteDocument(docId)
        assertTrue(pending().isEmpty())
    }

    @Test
    fun reviewCountsCoverUnverifiedAndPending() = runTest {
        rigs.addSourcedRows(vehicleId, SourceKind.OPENLABOR, listOf(specRow("Engine oil", "Capacity", "6", "qt", category = SpecCategory.CAPACITY)))
        val job = imports.startJob(vehicleId, docId, "", 1, false)
        imports.stageRows(job, listOf(specRow("Coolant", "Type", "ELC")))
        val count = rigs.observeReviewCounts().first().single { it.vehicleId == vehicleId }
        assertEquals(1, count.unverified)
        assertEquals(1, count.pendingImport)
        assertNull(rigs.observeReviewCounts().first().firstOrNull { it.vehicleId == "nope" })
    }
}
