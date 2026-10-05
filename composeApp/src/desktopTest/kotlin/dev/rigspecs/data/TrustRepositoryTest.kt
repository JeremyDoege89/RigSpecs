package dev.rigspecs.data

import androidx.room.Room
import dev.rigspecs.domain.CandidateRow
import dev.rigspecs.domain.Ids
import dev.rigspecs.domain.PartSystem
import dev.rigspecs.domain.Portal
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrustRepositoryTest {
    private object Editor : Session {
        override val departmentId = "dept-1"
        override val userId = "mechanic-1"
        override val canEdit = true
    }

    private class StepIds : Ids {
        var n = 0
        override fun newId() = "id-${++n}"
        override fun now() = 5_000L + n
    }

    private lateinit var db: AppDatabase
    private lateinit var rigs: RigRepository
    private lateinit var settings: SettingsRepository
    private lateinit var vehicleId: String

    @BeforeTest
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder<AppDatabase>().configure(Dispatchers.IO)
        rigs = RigRepository(db, Editor, StepIds())
        settings = SettingsRepository(db, rigs, Editor, StepIds())
        vehicleId = rigs.saveVehicle(
            VehicleEntity(id = "", departmentId = "", unitNumber = "Car 10", type = VehicleType.CAR, updatedAt = 0, updatedBy = ""),
            rigs.templateItems(VehicleType.CAR),
        )
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun olp(component: String, name: String, value: String, unit: String = "") =
        CandidateRow(ImportRowKind.SPEC, component, name, value, unit, category = SpecCategory.CAPACITY, sourceUrl = "https://olp.example/$component")

    @Test
    fun sourcedRowsFillBlanksAndLandUnverified() = runTest {
        val result = rigs.addSourcedRows(
            vehicleId, SourceKind.OPENLABOR,
            listOf(
                olp("Engine oil", "Capacity", "8", "qt"),
                olp("Rear differential", "Capacity", "3.4", "qt"),
                CandidateRow(ImportRowKind.PART, "Oil filter", oemNumber = "FL-500S", system = PartSystem.ENGINE, sourceUrl = "https://olp.example/f"),
                olp("", "Capacity", "1"), // unusable: no component
            ),
        )
        assertEquals(1, result.added)
        assertEquals(2, result.filled)
        val oil = rigs.observeSpecs(vehicleId).first().first { it.component == "Engine oil" && it.name == "Capacity" }
        assertEquals("8", oil.value)
        assertEquals(VerificationStatus.UNVERIFIED, oil.status)
        assertEquals(SourceKind.OPENLABOR, oil.sourceKind)
        assertEquals("https://olp.example/Engine oil", oil.sourceUrl)
        assertEquals(2, rigs.observeUnverifiedSpecs(vehicleId).first().size)
        assertEquals(1, rigs.observeUnverifiedParts(vehicleId).first().size)
    }

    @Test
    fun theProfileValueWinsAConflict() = runTest {
        val oilId = rigs.observeSpecs(vehicleId).first().first { it.component == "Engine oil" && it.name == "Capacity" }.id
        rigs.saveSpec(rigs.spec(oilId)!!.copy(value = "7.7", unit = "qt"))
        val result = rigs.addSourcedRows(vehicleId, SourceKind.OPENLABOR, listOf(olp("Engine oil", "Capacity", "8", "qt"), olp("Engine oil", "Capacity", "7.7", "qt")))
        assertEquals(1, result.conflicts.size)
        assertEquals("7.7 qt", result.conflicts.single().profileValue)
        assertEquals(1, result.alreadyThere)
        assertEquals("7.7", rigs.spec(oilId)!!.value)
        assertEquals(VerificationStatus.VERIFIED, rigs.spec(oilId)!!.status)
    }

    @Test
    fun confirmingStampsTheEditor() = runTest {
        rigs.addSourcedRows(vehicleId, SourceKind.OPENLABOR, listOf(olp("Engine oil", "Capacity", "8", "qt")))
        val row = rigs.observeUnverifiedSpecs(vehicleId).first().single()
        rigs.confirmSpec(row.id)
        val confirmed = rigs.spec(row.id)!!
        assertEquals(VerificationStatus.VERIFIED, confirmed.status)
        assertEquals("mechanic-1", confirmed.verifiedBy)
        assertTrue(confirmed.verifiedAt != null)
        assertTrue(rigs.observeUnverifiedSpecs(vehicleId).first().isEmpty())
        assertEquals("UNVERIFIED" to "VERIFIED", db.changeLogDao().listFor(row.id).first { it.field == "status" }.let { it.oldValue to it.newValue })
    }

    @Test
    fun saveWithoutConfirmingKeepsTheBadge() = runTest {
        rigs.addSourcedRows(vehicleId, SourceKind.OPENLABOR, listOf(olp("Engine oil", "Capacity", "8", "qt")))
        val row = rigs.observeUnverifiedSpecs(vehicleId).first().single()
        rigs.saveSpec(row.copy(notes = "check the dipstick"), confirm = false)
        assertEquals(VerificationStatus.UNVERIFIED, rigs.spec(row.id)!!.status)
        rigs.saveSpec(rigs.spec(row.id)!!, confirm = true)
        assertEquals(VerificationStatus.VERIFIED, rigs.spec(row.id)!!.status)
    }

    @Test
    fun settingsDefaultsAndDeviceKeys() = runTest {
        val start = settings.current()
        assertEquals(Portal.CUMMINS.defaultUrl, start.portalUrls[Portal.CUMMINS])
        assertTrue(start.importEnabled)
        assertNull(start.extractUrl)

        settings.set(Portal.CUMMINS.urlKey, "https://qs.example/esn/{esn}")
        settings.set(SettingsRepository.Keys.SERVER_URL, "https://abc.supabase.co/")
        settings.set(SettingsRepository.Keys.SERVER_KEY, "anon")
        assertTrue(!settings.current().importReady) // still needs the passphrase
        settings.set(SettingsRepository.Keys.IMPORT_SECRET, "hose")
        val s = settings.observe().first()
        assertEquals("https://qs.example/esn/{esn}", s.portalUrls[Portal.CUMMINS])
        assertEquals("https://abc.supabase.co/functions/v1/extract-specs", s.extractUrl)
        assertTrue(s.importReady)

        // Department settings are logged; device-only ones are not.
        assertEquals(1, db.changeLogDao().listFor(Portal.CUMMINS.urlKey).size)
        assertTrue(db.changeLogDao().listFor(SettingsRepository.Keys.SERVER_KEY).isEmpty())

        // Setting the default back removes the row.
        settings.set(Portal.CUMMINS.urlKey, Portal.CUMMINS.defaultUrl)
        assertNull(db.settingDao().get(Portal.CUMMINS.urlKey))
    }
}
