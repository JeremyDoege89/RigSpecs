package dev.rigspecs.ui.vehicle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rigspecs.data.RigRepository
import dev.rigspecs.data.VehicleEntity
import dev.rigspecs.domain.FormErrors
import dev.rigspecs.domain.VehicleType
import dev.rigspecs.domain.parseOptionalInt
import dev.rigspecs.domain.stations
import dev.rigspecs.domain.validateVehicle
import dev.rigspecs.domain.vehicleWarnings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/** Text exactly as typed; converted to a [VehicleEntity] on save. */
data class VehicleForm(
    val unitNumber: String = "",
    val type: VehicleType = VehicleType.ENGINE,
    val vin: String = "",
    val year: String = "",
    val make: String = "",
    val model: String = "",
    val chassisMake: String = "",
    val bodyBuilder: String = "",
    val builderJobNumber: String = "",
    val engine: String = "",
    val station: String = "",
    val notes: String = "",
)

data class VehicleEditState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val form: VehicleForm = VehicleForm(),
    val errors: FormErrors = emptyMap(),
    val warnings: List<String> = emptyList(),
    /** Stations already in use, offered as one-tap picks so filter chips don't split on typos. */
    val knownStations: List<String> = emptyList(),
    val saving: Boolean = false,
    /** Set once saved; the screen navigates away with it. */
    val savedId: String? = null,
)

class VehicleEditViewModel(
    private val vehicleId: String?,
    private val repository: RigRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(VehicleEditState(isNew = vehicleId == null, loading = vehicleId != null))
    val state: StateFlow<VehicleEditState> = _state.asStateFlow()

    /** The stored row, so fields this form doesn't show (photo, sync columns) survive a save. */
    private var original: VehicleEntity? = null

    init {
        viewModelScope.launch {
            val stations = repository.observeVehicles().first().stations()
            _state.update { it.copy(knownStations = stations) }
        }
        if (vehicleId != null) viewModelScope.launch {
            val v = repository.vehicle(vehicleId)
            original = v
            _state.update {
                it.copy(loading = false, form = v?.toForm() ?: VehicleForm())
            }
        }
    }

    fun edit(change: (VehicleForm) -> VehicleForm) = _state.update {
        val form = change(it.form)
        // Clear an error as soon as its field is touched again.
        it.copy(form = form, errors = it.errors.filterKeys { k -> it.form.field(k) == form.field(k) })
    }

    fun save() {
        val s = _state.value
        if (s.saving) return
        val year = parseOptionalInt(s.form.year)
        val draft = s.form.toEntity(original, year.getOrNull())
        val errors = validateVehicle(draft, currentYear()).toMutableMap()
        if (year.isFailure) errors["year"] = "Year must be a number"
        val warnings = vehicleWarnings(draft)
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors, warnings = warnings) }
            return
        }
        _state.update { it.copy(saving = true, errors = emptyMap()) }
        viewModelScope.launch {
            val id = repository.saveVehicle(draft)
            _state.update { it.copy(saving = false, savedId = id) }
        }
    }

    fun refreshWarnings() = _state.update {
        it.copy(warnings = vehicleWarnings(it.form.toEntity(original, null)))
    }

    private fun currentYear() = Clock.System.todayIn(TimeZone.currentSystemDefault()).year
}

private fun VehicleEntity.toForm() = VehicleForm(
    unitNumber = unitNumber,
    type = type,
    vin = vin,
    year = year?.toString().orEmpty(),
    make = make,
    model = model,
    chassisMake = chassisMake,
    bodyBuilder = bodyBuilder,
    builderJobNumber = builderJobNumber,
    engine = engine,
    station = station,
    notes = notes,
)

private fun VehicleForm.toEntity(original: VehicleEntity?, parsedYear: Int?): VehicleEntity {
    val base = original ?: VehicleEntity(id = "", departmentId = "", unitNumber = "", type = type, updatedAt = 0, updatedBy = "")
    return base.copy(
        unitNumber = unitNumber.trim(),
        type = type,
        vin = vin.trim().uppercase(),
        year = parsedYear,
        make = make.trim(),
        model = model.trim(),
        chassisMake = chassisMake.trim(),
        bodyBuilder = bodyBuilder.trim(),
        builderJobNumber = builderJobNumber.trim(),
        engine = engine.trim(),
        station = station.trim(),
        notes = notes.trim(),
    )
}

private fun VehicleForm.field(key: String): Any = when (key) {
    "unitNumber" -> unitNumber
    "year" -> year
    else -> Unit
}
