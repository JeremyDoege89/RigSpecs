package dev.rigspecs.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.rigspecs.domain.DocumentKind
import dev.rigspecs.domain.ImportJobStatus
import dev.rigspecs.domain.ImportRowState
import dev.rigspecs.domain.PartSystem
import dev.rigspecs.domain.SourceKind
import dev.rigspecs.domain.SpecCategory
import dev.rigspecs.domain.VehicleType
import dev.rigspecs.domain.VerificationStatus

// Every synced row carries departmentId (tenancy / row-level security), updatedAt + updatedBy
// (last write wins), deleted (a tombstone, so deletes sync) and dirty (local only: not pushed yet).
// Ids are UUID strings so phones can create rows offline without colliding.

@Entity(tableName = "vehicle", indices = [Index("departmentId"), Index("updatedAt")])
data class VehicleEntity(
    @PrimaryKey val id: String,
    val departmentId: String,
    /** What the crew calls it: "Engine 2", "Medic 3". */
    val unitNumber: String,
    val type: VehicleType,
    val vin: String = "",
    val year: Int? = null,
    val make: String = "",
    val model: String = "",
    val chassisMake: String = "",
    val bodyBuilder: String = "",
    /** Pierce / E-One job number or the ambulance builder's serial: what their parts desks ask for. */
    val builderJobNumber: String = "",
    val engine: String = "",
    /** Engine serial number (Cummins ESN, Detroit serial): what engine parts catalogs search by. Added in v3. */
    @ColumnInfo(defaultValue = "")
    val engineSerial: String = "",
    val station: String = "",
    val photoPath: String = "",
    val notes: String = "",
    /** vPIC DecodeVinValues Results[0] for [vin], verbatim; empty when never decoded. Added in v2. */
    @ColumnInfo(defaultValue = "")
    val vinDecodeJson: String = "",
    val updatedAt: Long,
    val updatedBy: String,
    val deleted: Boolean = false,
    val dirty: Boolean = true,
)

@Entity(tableName = "spec_item", indices = [Index("vehicleId"), Index("updatedAt")])
data class SpecItemEntity(
    @PrimaryKey val id: String,
    val departmentId: String,
    val vehicleId: String,
    val category: SpecCategory,
    /** What it belongs to: "Engine oil", "Lug nuts", "Front axle". */
    val component: String,
    /** Which property: "Capacity", "Torque", "Type". */
    val name: String,
    val value: String,
    val unit: String = "",
    val notes: String = "",
    val sourceDocId: String? = null,
    /** Brakes, wheels, aerial: shown with a verify-against-the-manual warning and needs a source. */
    val safetyCritical: Boolean = false,
    /** UNVERIFIED until an editor confirms it. Added in v3, like the four fields after it. */
    @ColumnInfo(defaultValue = "VERIFIED")
    val status: VerificationStatus = VerificationStatus.VERIFIED,
    @ColumnInfo(defaultValue = "MANUAL")
    val sourceKind: SourceKind = SourceKind.MANUAL,
    /** The web page or API call the value came from, for sourced rows. */
    @ColumnInfo(defaultValue = "")
    val sourceUrl: String = "",
    /** Who confirmed the current value, and when; empty for blanks nobody has filled. */
    @ColumnInfo(defaultValue = "")
    val verifiedBy: String = "",
    val verifiedAt: Long? = null,
    /** Where in [sourceDocId] the value is printed: "p. 212". */
    @ColumnInfo(defaultValue = "")
    val pageHint: String = "",
    val sortOrder: Int = 0,
    val updatedAt: Long,
    val updatedBy: String,
    val deleted: Boolean = false,
    val dirty: Boolean = true,
)

@Entity(tableName = "part", indices = [Index("vehicleId"), Index("updatedAt")])
data class PartEntity(
    @PrimaryKey val id: String,
    val departmentId: String,
    val vehicleId: String,
    val system: PartSystem,
    /** "Oil filter", "Front brake pads". */
    val component: String,
    val oemNumber: String = "",
    /** One cross-reference per line: "NAPA 1792", "Wix 51792". */
    val aftermarketNumbers: String = "",
    val supplier: String = "",
    val qtyPerVehicle: Int? = null,
    val notes: String = "",
    val sourceDocId: String? = null,
    /** UNVERIFIED until an editor confirms it. Added in v3, like the four fields after it. */
    @ColumnInfo(defaultValue = "VERIFIED")
    val status: VerificationStatus = VerificationStatus.VERIFIED,
    @ColumnInfo(defaultValue = "MANUAL")
    val sourceKind: SourceKind = SourceKind.MANUAL,
    /** The web page or API call the value came from, for sourced rows. */
    @ColumnInfo(defaultValue = "")
    val sourceUrl: String = "",
    /** Who confirmed the current value, and when; empty for blanks nobody has filled. */
    @ColumnInfo(defaultValue = "")
    val verifiedBy: String = "",
    val verifiedAt: Long? = null,
    /** Where in [sourceDocId] the value is printed: "p. 212". */
    @ColumnInfo(defaultValue = "")
    val pageHint: String = "",
    val sortOrder: Int = 0,
    val updatedAt: Long,
    val updatedBy: String,
    val deleted: Boolean = false,
    val dirty: Boolean = true,
)

@Entity(tableName = "document", indices = [Index("vehicleId"), Index("updatedAt")])
data class DocumentEntity(
    @PrimaryKey val id: String,
    val departmentId: String,
    val vehicleId: String,
    val title: String,
    val kind: DocumentKind,
    /** Remote copy in file storage; empty until uploaded. */
    val fileUrl: String = "",
    /** Copy on this device; empty when not downloaded. Local only. */
    val localPath: String = "",
    /** "p. 212" or "Section 4": where in the document the specs live. */
    val pageHint: String = "",
    val updatedAt: Long,
    val updatedBy: String,
    val deleted: Boolean = false,
    val dirty: Boolean = true,
)

/** Starter fields a new rig of [vehicleType] gets; [itemsJson] is a JSON list of template items. */
@Entity(tableName = "template", indices = [Index("vehicleType")])
data class TemplateEntity(
    @PrimaryKey val id: String,
    val departmentId: String,
    val vehicleType: VehicleType,
    val name: String,
    val itemsJson: String,
    val updatedAt: Long,
    val updatedBy: String,
    val deleted: Boolean = false,
    val dirty: Boolean = true,
)

/**
 * One run of assisted import over a document. Pages go to the extraction service in small
 * batches; [pagesDone] lets an interrupted job resume where it stopped.
 */
@Entity(tableName = "import_job", indices = [Index("vehicleId"), Index("documentId")])
data class ImportJobEntity(
    @PrimaryKey val id: String,
    val departmentId: String,
    val vehicleId: String,
    val documentId: String,
    val status: ImportJobStatus,
    /** The extraction model the service used (or will use). */
    val model: String = "",
    /** Pages to read, 1-based: "1-5,210-230". Empty means every page. */
    val pages: String = "",
    val pagesTotal: Int = 0,
    val pagesDone: Int = 0,
    val rowsExtracted: Int = 0,
    val rowsConfirmed: Int = 0,
    /** Dense parts tables: use the slower, more careful model. */
    val dense: Boolean = false,
    val error: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val updatedBy: String,
    val deleted: Boolean = false,
    val dirty: Boolean = true,
)

/** A row the extraction returned, staged for review. Confirming copies it into the profile. */
@Entity(tableName = "import_row", indices = [Index("jobId"), Index("vehicleId")])
data class ImportRowEntity(
    @PrimaryKey val id: String,
    val departmentId: String,
    val jobId: String,
    val vehicleId: String,
    val kind: ImportRowKind,
    val category: SpecCategory? = null,
    val system: PartSystem? = null,
    val component: String,
    val name: String = "",
    val value: String = "",
    val unit: String = "",
    val oemNumber: String = "",
    /** One cross-reference per line. */
    val aftermarketNumbers: String = "",
    val qtyPerVehicle: Int? = null,
    val notes: String = "",
    /** 1-based page of the document the row was read from. */
    val page: Int? = null,
    /** The words on the page the value was read from, so a reviewer can check it at a glance. */
    val evidence: String = "",
    val safetyCritical: Boolean = false,
    val state: ImportRowState = ImportRowState.PENDING,
    /** The spec or part this row became (or filled) when confirmed. */
    val resultId: String? = null,
    /** Confirmed with changes: the pilot's "kept versus fixed" measure. */
    val edited: Boolean = false,
    val sortOrder: Int = 0,
    val updatedAt: Long,
    val updatedBy: String,
    val deleted: Boolean = false,
    val dirty: Boolean = true,
)

enum class ImportRowKind { SPEC, PART }

/**
 * Department-wide settings (portal URL templates, import on or off), synced like everything
 * else. Keys starting with "device." stay on this device and are never synced.
 */
@Entity(tableName = "setting")
data class SettingEntity(
    @PrimaryKey val key: String,
    val departmentId: String,
    val value: String,
    val updatedAt: Long,
    val updatedBy: String,
    val dirty: Boolean = true,
)

/** One field change: who changed the torque spec and when. Append-only. */
@Entity(tableName = "change_log", indices = [Index("entityId"), Index("timestamp")])
data class ChangeLogEntity(
    @PrimaryKey val id: String,
    val departmentId: String,
    val entityType: String,
    val entityId: String,
    val field: String,
    val oldValue: String?,
    val newValue: String?,
    val userId: String,
    val timestamp: Long,
    val dirty: Boolean = true,
)
