package dev.rigspecs.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.room.Room
import dev.rigspecs.data.AppDatabase
import dev.rigspecs.data.RigRepository
import dev.rigspecs.data.VehicleEntity
import dev.rigspecs.data.configure
import dev.rigspecs.domain.LocalSession
import dev.rigspecs.domain.RigCsvColumns
import dev.rigspecs.domain.SourceKind
import dev.rigspecs.domain.SystemIds
import dev.rigspecs.domain.VehicleType
import dev.rigspecs.domain.VerificationStatus
import dev.rigspecs.domain.exportRigCsv
import dev.rigspecs.domain.parseCsv
import dev.rigspecs.domain.writeCsv
import dev.rigspecs.ui.UiTestKit.shot
import dev.rigspecs.ui.UiTestKit.waitForText
import dev.rigspecs.ui.csv.CsvImportScreen
import dev.rigspecs.ui.csv.CsvImportViewModel
import dev.rigspecs.ui.theme.RigSpecsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The fill-in workflow: export a rig to CSV, fill in blanks the way a spreadsheet would, import
 * it back. The screen is driven directly; the system file picker can't be driven in a test.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class CsvImportFlowTest {
    @Test
    fun exportFillInImportBack() {
        val db = Room.inMemoryDatabaseBuilder<AppDatabase>().configure(Dispatchers.IO)
        val rigs = RigRepository(db, LocalSession, SystemIds)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val vid = runBlocking {
                rigs.saveVehicle(
                    VehicleEntity(id = "", departmentId = "", unitNumber = "Medic 3", type = VehicleType.AMBULANCE, updatedAt = 0, updatedBy = ""),
                    rigs.templateItems(VehicleType.AMBULANCE),
                )
            }
            // Someone typed the oil capacity already, as 6 qt.
            runBlocking {
                val oil = rigs.observeSpecs(vid).first().first { it.component == "Engine oil" && it.name == "Capacity" }
                rigs.saveSpec(oil.copy(value = "6", unit = "qt"))
            }
            val exported = runBlocking { exportRigCsv(rigs.observeSpecs(vid).first(), rigs.observeParts(vid).first(), emptyMap()) }
            val table = parseCsv(exported).map { it.toMutableList() }.toMutableList()
            assertEquals(RigCsvColumns, table.first())
            fun fill(component: String, name: String, value: String) =
                table.first { it[3] == component && it[4] == name }.set(5, value)
            fill("Lug nuts", "Torque", "165")
            fill("Coolant", "Capacity", "16")
            fill("Engine oil", "Capacity", "7") // differs from the 6 typed in the app
            table.first { it[0] == "part" && it[3] == "Oil filter" }.set(7, "FL-2051S")
            table.add(mutableListOf("spec", "OTHER", "", "Stretcher mount", "Bolt torque", "35", "ft-lb", "", "", "", "", "", "", "", "", "", ""))
            table.add(mutableListOf("spec", "Bolts", "", "Bad row", "Torque", "1", "", "", "", "", "", "", "", "", "", "", ""))
            val filledIn = writeCsv(table)

            val vm = CsvImportViewModel(vid, rigs)
            vm.load("Medic-3-specs.csv", filledIn)

            runDesktopComposeUiTest(width = 412, height = 900) {
                setContent { RigSpecsTheme { CsvImportScreen(vm, onBack = {}, onReview = {}) } }
                waitForText("Medic-3-specs.csv")
                waitForText("5 rows: 3 fill blank fields, 1 is new, 1 differs from the profile.")
                waitForText("1 with an error")
                waitForText("Line ")
                shot("c1-csv-preview")
                // Leave the differing value alone: 4 rows go in.
                onNodeWithText("Import 4 rows").performClick()
                waitForText("Imported")
                waitForText("3 blank fields filled, 1 row added, 1 kept as it was.")
                shot("c2-csv-result")
                onNodeWithText("Review now").assertExists()
            }

            runBlocking {
                val specs = rigs.observeSpecs(vid).first()
                val lug = specs.single { it.component == "Lug nuts" && it.name == "Torque" }
                assertEquals("165", lug.value)
                assertEquals(VerificationStatus.UNVERIFIED, lug.status)
                assertEquals(SourceKind.CSV, lug.sourceKind)
                assertEquals("6", specs.single { it.component == "Engine oil" && it.name == "Capacity" }.value)
                assertTrue(specs.any { it.component == "Stretcher mount" && it.value == "35" })
                assertEquals("FL-2051S", rigs.observeParts(vid).first().single { it.component == "Oil filter" }.oemNumber)
            }
        } finally {
            Dispatchers.resetMain()
            db.close()
        }
    }
}
