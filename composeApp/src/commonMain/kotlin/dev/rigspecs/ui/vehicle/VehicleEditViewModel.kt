package dev.rigspecs.ui.vehicle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rigspecs.data.RigRepository
import dev.rigspecs.data.VehicleEntity
import dev.rigspecs.data.vin.VinDecoder
import dev.rigspecs.domain.FormErrors
import dev.rigspecs.domain.VinFill
import dev.rigspecs.domain.VinValidator
import dev.rigspecs.domain.extractVin
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
    val engineSerial: String = "",
    val station: String = "",
    val notes: String = "",
)

/** The VIN decode card under the VIN field. */
sealed interface DecodeUi {
    data object Idle : DecodeUi
    data object Working : DecodeUi

    /** Waiting for the user to confirm; [fill] is what "Use these" would change. */
    data class Ready(val vin: String, val summary: VinFill.Summary, val fill: VinFill.Fill, val checkDigitWarning: Boolean) : DecodeUi

    /** Confirmed; [filled] names the fields that changed. */
    data class Applied(val filled: List<String>) : DecodeUi

    data class Failed(val message: String) : DecodeUi
}

data class VehicleEditState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val form: VehicleForm = VehicleForm(),
    val errors: FormErrors = emptyMap(),
    val warnings: List<String> = emptyList(),
    /** Stations already in use, offered as one-tap picks so filter chips don't split on typos. */
    val knownStations: List<String> = emptyList(),
    val decode: DecodeUi = DecodeUi.Idle,
    /** Why the VIN can't be decoded, shown under the field after Decode or a scan. */
    val vinError: String? = null,
    /** New rigs only: start with the type's template. */
    val applyTemplate: Boolean = true,
    val templateSize: Int = 0,
    val saving: Boolean = false,
    /** Set once saved; the screen navigates away with it. */
    val savedId: String? = null,
)

class VehicleEditViewModel(
    private val vehicleId: String?,
    private val repository: RigRepository,
    private val decoder: VinDecoder,
) : ViewModel() {
    private val _state = MutableStateFlow(VehicleEditState(isNew = vehicleId == null, loading = vehicleId != null))
    val state: StateFlow<VehicleEditState> = _state.asStateFlow()

    /** The stored row, so fields this form doesn't show (photo, sync columns) survive a save. */
    private var original: VehicleEntity? = null

    /** (VIN, raw JSON) of the decode on the card, not yet confirmed. */
    private var pendingDecode: Pair<String, String>? = null

    /** The raw decode confirmed in this form, saved onto the rig if the VIN still matches. */
    private var confirmedDecode: Pair<String, String>? = null

    /** Once the user picks a type, a decode no longer changes it. Editing an existing rig counts. */
    private var typeChosen = vehicleId != null

    init {
        viewModelScope.launch {
            val stations = repository.observeVehicles().first().stations()
            _state.update { it.copy(knownStations = stations) }
        }
        if (vehicleId == null) loadTemplateSize(VehicleType.ENGINE)
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

    fun setType(type: VehicleType) {
        typeChosen = true
        edit { it.copy(type = type) }
        if (_state.value.isNew) loadTemplateSize(type)
    }

    fun setApplyTemplate(on: Boolean) = _state.update { it.copy(applyTemplate = on) }

    /** The VIN last decoded without being asked, so typing doesn't call NHTSA twice for it. */
    private var autoDecoded: String? = null

    fun setVin(text: String) {
        edit { it.copy(vin = text) }
        _state.update { s ->
            // A decode for a different VIN no longer applies.
            val stale = (s.decode as? DecodeUi.Ready)?.vin?.let { it != VinValidator.normalize(text) } ?: false
            s.copy(vinError = null, decode = if (stale || s.decode is DecodeUi.Failed) DecodeUi.Idle else s.decode)
        }
        // Every new rig gets a vPIC lookup as soon as a complete VIN is in; the result still waits for "Use these".
        val vin = VinValidator.normalize(text)
        if (_state.value.isNew && vin != autoDecoded && VinValidator.isWellFormed(vin) && _state.value.decode == DecodeUi.Idle) {
            autoDecoded = vin
            decode()
        }
    }

    /** A barcode's raw text: pull the VIN out and decode it straight away. */
    fun onScanned(raw: String) {
        val vin = extractVin(raw)
        if (vin == null) {
            _state.update { it.copy(vinError = "That barcode isn't a VIN. Try the one on the door-jamb sticker.") }
            return
        }
        setVin(vin)
        // New rigs decode on their own in setVin; an edited rig decodes because the scan asked for it.
        if (_state.value.decode == DecodeUi.Idle) decode()
    }

    fun decode() {
        val s = _state.value
        val result = VinValidator.validate(s.form.vin)
        if (result is VinValidator.Result.BadFormat) {
            _state.update { it.copy(vinError = result.reason, decode = DecodeUi.Idle) }
            return
        }
        val vin = result.vin
        _state.update { it.copy(vinError = null, decode = DecodeUi.Working, form = it.form.copy(vin = vin)) }
        viewModelScope.launch {
            val typedYear = parseOptionalInt(_state.value.form.year).getOrNull()
            val ui = when (val outcome = decoder.decode(vin, typedYear)) {
                is VinDecoder.Outcome.Decoded -> {
                    val f = _state.value.form
                    val fill = VinFill.fill(outcome.decode, f.type, typeChosen, f.year, f.make, f.model, f.engine, f.chassisMake)
                    pendingDecode = vin to outcome.decode.rawJson
                    DecodeUi.Ready(vin, VinFill.summarize(outcome.decode), fill, result is VinValidator.Result.BadCheckDigit)
                }
                is VinDecoder.Outcome.Failed -> DecodeUi.Failed(failureMessage(outcome.failure))
            }
            _state.update { it.copy(decode = ui) }
        }
    }

    /** "Use these": fills the blank fields from the decode. */
    fun applyDecode() {
        val ready = _state.value.decode as? DecodeUi.Ready ?: return
        val fill = ready.fill
        confirmedDecode = pendingDecode
        fill.type?.let {
            edit { f -> f.copy(type = it) }
            if (_state.value.isNew) loadTemplateSize(it)
        }
        edit { f ->
            f.copy(
                year = fill.year?.toString() ?: f.year,
                make = fill.make ?: f.make,
                model = fill.model ?: f.model,
                engine = fill.engine ?: f.engine,
                chassisMake = fill.chassisMake ?: f.chassisMake,
            )
        }
        val filled = listOfNotNull(
            fill.type?.let { "type" }, fill.year?.let { "year" }, fill.make?.let { "make" }, fill.model?.let { "model" },
            fill.engine?.let { "engine" }, fill.chassisMake?.let { "chassis make" },
        )
        _state.update { it.copy(decode = DecodeUi.Applied(filled)) }
    }

    fun dismissDecode() = _state.update { it.copy(decode = DecodeUi.Idle) }

    private fun loadTemplateSize(type: VehicleType) = viewModelScope.launch {
        val size = repository.templateItems(type).size
        _state.update { it.copy(templateSize = size) }
    }

    private fun failureMessage(f: VinDecoder.Failure) = when (f) {
        VinDecoder.Failure.OFFLINE -> "No signal. Fill the details in by hand, or decode later from Edit."
        VinDecoder.Failure.TIMEOUT -> "The NHTSA decoder didn't answer. Try again in a minute."
        VinDecoder.Failure.SERVER -> "The NHTSA decoder had a problem. Try again later."
        VinDecoder.Failure.EMPTY -> "NHTSA doesn't recognize this VIN. Check it against the sticker."
    }

    fun save() {
        val s = _state.value
        if (s.saving) return
        val year = parseOptionalInt(s.form.year)
        val draft = s.form.toEntity(original, year.getOrNull()).let { d ->
            val decoded = confirmedDecode?.takeIf { it.first == d.vin }?.second
            when {
                decoded != null -> d.copy(vinDecodeJson = decoded)
                d.vin != original?.vin -> d.copy(vinDecodeJson = "") // the stored decode was for another VIN
                else -> d
            }
        }
        val errors = validateVehicle(draft, currentYear()).toMutableMap()
        if (year.isFailure) errors["year"] = "Year must be a number"
        val warnings = vehicleWarnings(draft)
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors, warnings = warnings) }
            return
        }
        _state.update { it.copy(saving = true, errors = emptyMap()) }
        viewModelScope.launch {
            val template = if (s.isNew && s.applyTemplate) repository.templateItems(draft.type) else emptyList()
            val id = repository.saveVehicle(draft, template)
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
    engineSerial = engineSerial,
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
        engineSerial = engineSerial.trim().uppercase(),
        station = station.trim(),
        notes = notes.trim(),
    )
}

private fun VehicleForm.field(key: String): Any = when (key) {
    "unitNumber" -> unitNumber
    "year" -> year
    else -> Unit
}
