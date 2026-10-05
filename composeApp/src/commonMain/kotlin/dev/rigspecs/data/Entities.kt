package dev.rigspecs.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.rigspecs.domain.DocumentKind
import dev.rigspecs.domain.PartSystem
import dev.rigspecs.domain.SpecCategory
import dev.rigspecs.domain.VehicleType

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
    val station: String = "",
    val photoPath: String = "",
    val notes: String = "",
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
