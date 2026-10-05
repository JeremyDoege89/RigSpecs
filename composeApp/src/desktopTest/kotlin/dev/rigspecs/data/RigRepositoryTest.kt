package dev.rigspecs.data

import androidx.room.Room
import dev.rigspecs.domain.DocumentKind
import dev.rigspecs.domain.Ids
import dev.rigspecs.domain.PartSystem
import dev.rigspecs.domain.Session
import dev.rigspecs.domain.SpecCategory
import dev.rigspecs.domain.VehicleType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeIds : Ids {
    var n = 0
    var clock = 1_000L
    override fun newId() = "id-${++n}"
    override fun now() = clock++
}

private object TestSession : Session {
    override val departmentId = "dept-1"
    override val userId = "mechanic-1"
    override val canEdit = true
}

class RigRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: RigRepository
    private val ids = FakeIds()

    @BeforeTest
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder<AppDatabase>().configure(Dispatchers.IO)
        repo = RigRepository(db, TestSession, ids)
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun newVehicle(unit: String) =
        VehicleEntity(id = "", departmentId = "", unitNumber = unit, type = VehicleType.ENGINE, updatedAt = 0, updatedBy = "")

    private fun newSpec(vehicleId: String, value: String = "450") = SpecItemEntity(
        id = "", departmentId = "", vehicleId = vehicleId, category = SpecCategory.TORQUE,
        component = "Lug nuts", name = "Torque", value = value, unit = "ft-lb", updatedAt = 0, updatedBy = "",
    )

    @Test
    fun saveStampsSyncColumnsAndLogsCreation() = runTest {
        val id = repo.saveVehicle(newVehicle("Engine 2").copy(make = "Pierce"))
        val v = repo.vehicle(id)!!
        assertEquals("dept-1", v.departmentId)
        assertEquals("mechanic-1", v.updatedBy)
        assertTrue(v.dirty)
        val log = db.changeLogDao().listFor(id).associateBy { it.field }
        assertEquals(setOf("unitNumber", "type", "make"), log.keys)
        assertEquals("Pierce", log.getValue("make").newValue)
    }

    @Test
    fun editKeepsDepartmentAndSortOrderAndLogsOnlyTheChange() = runTest {
        val vid = repo.saveVehicle(newVehicle("Engine 2"))
        val first = repo.saveSpec(newSpec(vid))
        val second = repo.saveSpec(newSpec(vid).copy(component = "Wheel bearings"))
        assertEquals(0, repo.spec(first)!!.sortOrder)
        assertEquals(1, repo.spec(second)!!.sortOrder)

        val before = db.changeLogDao().listFor(first).size
        repo.saveSpec(repo.spec(first)!!.copy(value = "475"))
        val log = db.changeLogDao().listFor(first)
        assertEquals(before + 1, log.size)
        assertEquals(Triple("value", "450", "475"), log.first().let { Triple(it.field, it.oldValue, it.newValue) })
        assertEquals(0, repo.spec(first)!!.sortOrder)
    }

    @Test
    fun savingUnchangedRowLogsNothing() = runTest {
        val vid = repo.saveVehicle(newVehicle("Engine 2"))
        val before = db.changeLogDao().listFor(vid).size
        repo.saveVehicle(repo.vehicle(vid)!!)
        assertEquals(before, db.changeLogDao().listFor(vid).size)
    }

    @Test
    fun deleteVehicleTombstonesEverythingOnIt() = runTest {
        val vid = repo.saveVehicle(newVehicle("Medic 3"))
        val sid = repo.saveSpec(newSpec(vid))
        val pid = repo.savePart(
            PartEntity(id = "", departmentId = "", vehicleId = vid, system = PartSystem.ENGINE, component = "Oil filter", updatedAt = 0, updatedBy = ""),
        )
        val did = repo.saveDocument(
            DocumentEntity(id = "", departmentId = "", vehicleId = vid, title = "Manual", kind = DocumentKind.MANUAL, updatedAt = 0, updatedBy = ""),
        )
        repo.deleteVehicle(vid)

        assertTrue(repo.observeVehicles().first().isEmpty())
        assertNull(repo.observeVehicle(vid).first())
        assertTrue(repo.vehicle(vid)!!.deleted)
        assertTrue(repo.spec(sid)!!.deleted)
        assertTrue(repo.part(pid)!!.deleted)
        assertTrue(repo.document(did)!!.deleted)
        assertTrue(repo.observeSpecs(vid).first().isEmpty())
    }

    @Test
    fun deleteDocumentClearsCitations() = runTest {
        val vid = repo.saveVehicle(newVehicle("Engine 2"))
        val did = repo.saveDocument(
            DocumentEntity(id = "", departmentId = "", vehicleId = vid, title = "Manual", kind = DocumentKind.MANUAL, pageHint = "p. 4", updatedAt = 0, updatedBy = ""),
        )
        val sid = repo.saveSpec(newSpec(vid).copy(sourceDocId = did, safetyCritical = true))
        repo.deleteDocument(did)

        val spec = repo.spec(sid)!!
        assertNull(spec.sourceDocId)
        assertEquals("450", spec.value)
        assertEquals("sourceDocId", db.changeLogDao().listFor(sid).first().field)
        assertTrue(repo.documents(vid).isEmpty())
    }

    @Test
    fun fleetIsSortedAndExcludesTombstones() = runTest {
        repo.saveVehicle(newVehicle("medic 3"))
        repo.saveVehicle(newVehicle("Engine 2"))
        val gone = repo.saveVehicle(newVehicle("Old Car"))
        repo.deleteVehicle(gone)
        assertEquals(listOf("Engine 2", "medic 3"), repo.observeVehicles().first().map { it.unitNumber })
    }
}
