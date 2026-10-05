package dev.rigspecs.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import org.junit.Rule
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class MigrationTest {
    private val dbFile: Path = Files.createTempDirectory("rigspecs-migration").resolve("test.db")

    @get:Rule
    val helper = MigrationTestHelper(
        schemaDirectoryPath = Path("schemas"),
        databasePath = dbFile,
        driver = BundledSQLiteDriver(),
        databaseClass = AppDatabase::class,
    )

    @Test
    fun migrate1To2KeepsRigsAndAddsBlankDecode() {
        helper.createDatabase(1).apply {
            execSQL(
                "INSERT INTO vehicle (id, departmentId, unitNumber, type, vin, make, model, chassisMake, bodyBuilder, " +
                    "builderJobNumber, engine, station, photoPath, notes, updatedAt, updatedBy, deleted, dirty) " +
                    "VALUES ('v1', 'd', 'Engine 2', 'ENGINE', '4P1', 'Pierce', '', '', '', '', '', '', '', '', 1, 'u', 0, 1)",
            )
            close()
        }
        val conn = helper.runMigrationsAndValidate(2)
        conn.prepare("SELECT unitNumber, vinDecodeJson FROM vehicle WHERE id = 'v1'").use { st ->
            st.step()
            assertEquals("Engine 2", st.getText(0))
            assertEquals("", st.getText(1))
        }
        conn.close()
    }

    @Test
    fun migrate2To3AttributesTypedValuesToTheirAuthor() {
        helper.createDatabase(2).apply {
            execSQL(
                "INSERT INTO spec_item (id, departmentId, vehicleId, category, component, name, value, unit, notes, sourceDocId, " +
                    "safetyCritical, sortOrder, updatedAt, updatedBy, deleted, dirty) VALUES " +
                    "('s1', 'd', 'v1', 'TORQUE', 'Lug nuts', 'Torque', '450', 'ft-lb', '', NULL, 1, 0, 7, 'mech', 0, 1), " +
                    "('s2', 'd', 'v1', 'FLUID', 'Coolant', 'Type', '', '', '', NULL, 0, 1, 8, 'mech', 0, 1)",
            )
            execSQL(
                "INSERT INTO part (id, departmentId, vehicleId, system, component, oemNumber, aftermarketNumbers, supplier, " +
                    "qtyPerVehicle, notes, sourceDocId, sortOrder, updatedAt, updatedBy, deleted, dirty) VALUES " +
                    "('p1', 'd', 'v1', 'ENGINE', 'Oil filter', 'LF9009', '', '', NULL, '', NULL, 0, 9, 'mech', 0, 1)",
            )
            close()
        }
        val conn = helper.runMigrationsAndValidate(3)
        conn.prepare("SELECT id, status, sourceKind, verifiedBy, verifiedAt, pageHint FROM spec_item ORDER BY id").use { st ->
            st.step()
            assertEquals(listOf("s1", "VERIFIED", "MANUAL", "mech"), (0..3).map { st.getText(it) })
            assertEquals(7L, st.getLong(4))
            assertEquals("", st.getText(5))
            st.step()
            assertEquals("", st.getText(3))
            assertEquals(true, st.isNull(4))
        }
        conn.prepare("SELECT verifiedBy, verifiedAt FROM part WHERE id = 'p1'").use { st ->
            st.step()
            assertEquals("mech", st.getText(0))
            assertEquals(9L, st.getLong(1))
        }
        conn.prepare("SELECT COUNT(*) FROM import_job").use { st ->
            st.step()
            assertEquals(0L, st.getLong(0))
        }
        conn.close()
    }
}
