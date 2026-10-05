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
}
