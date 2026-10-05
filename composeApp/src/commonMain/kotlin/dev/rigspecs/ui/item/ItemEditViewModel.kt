package dev.rigspecs.ui.item

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rigspecs.data.DocumentEntity
import dev.rigspecs.data.PartEntity
import dev.rigspecs.data.RigRepository
import dev.rigspecs.data.SpecItemEntity
import dev.rigspecs.domain.DocumentKind
import dev.rigspecs.domain.FormErrors
import dev.rigspecs.domain.PartSystem
import dev.rigspecs.domain.SpecCategory
import dev.rigspecs.domain.parseOptionalInt
import dev.rigspecs.domain.validatePart
import dev.rigspecs.domain.validateSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ItemKind { SPEC, PART }

/** One form for both specs and parts; fields the current [kind] doesn't use are ignored. */
data class ItemForm(
    val category: SpecCategory = SpecCategory.OTHER,
    val system: PartSystem = PartSystem.ENGINE,
    val component: String = "",
    val name: String = "",
    val value: String = "",
    val unit: String = "",
    val oemNumber: String = "",
    val aftermarketNumbers: String = "",
    val supplier: String = "",
    val qty: String = "",
    val notes: String = "",
    val sourceDocId: String? = null,
    val safetyCritical: Boolean = false,
)

data class ItemEditState(
    val kind: ItemKind,
    val isNew: Boolean,
    val loading: Boolean = true,
    val form: ItemForm = ItemForm(),
    val documents: List<DocumentEntity> = emptyList(),
    val errors: FormErrors = emptyMap(),
    val lastChangedAt: Long? = null,
    val lastChangedBy: String? = null,
    val saving: Boolean = false,
    /** Saved or deleted: the screen closes. */
    val done: Boolean = false,
)

class ItemEditViewModel(
    private val kind: ItemKind,
    private val vehicleId: String,
    private val itemId: String?,
    initialCategory: SpecCategory?,
    private val repository: RigRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(
        ItemEditState(
            kind = kind,
            isNew = itemId == null,
            form = ItemForm(category = initialCategory ?: SpecCategory.OTHER),
        ),
    )
    val state: StateFlow<ItemEditState> = _state.asStateFlow()

    private var originalSpec: SpecItemEntity? = null
    private var originalPart: PartEntity? = null

    init {
        viewModelScope.launch {
            repository.observeDocuments(vehicleId).collectLatest { docs -> _state.update { it.copy(documents = docs) } }
        }
        viewModelScope.launch {
            if (itemId != null) when (kind) {
                ItemKind.SPEC -> repository.spec(itemId)?.let { s ->
                    originalSpec = s
                    _state.update { it.copy(form = s.toForm(), lastChangedAt = s.updatedAt, lastChangedBy = s.updatedBy) }
                }
                ItemKind.PART -> repository.part(itemId)?.let { p ->
                    originalPart = p
                    _state.update { it.copy(form = p.toForm(), lastChangedAt = p.updatedAt, lastChangedBy = p.updatedBy) }
                }
            }
            _state.update { it.copy(loading = false) }
        }
    }

    fun edit(change: (ItemForm) -> ItemForm) = _state.update {
        val form = change(it.form)
        val stillWrong = it.errors.filterKeys { k -> it.form.field(k) == form.field(k) }
        it.copy(form = form, errors = stillWrong)
    }

    /** Adds a document entry and selects it as this item's source. */
    fun addDocument(title: String, kind: DocumentKind, pageHint: String) = viewModelScope.launch {
        val id = repository.saveDocument(
            DocumentEntity(
                id = "", departmentId = "", vehicleId = vehicleId, title = title.trim(), kind = kind,
                pageHint = pageHint.trim(), updatedAt = 0, updatedBy = "",
            ),
        )
        edit { it.copy(sourceDocId = id) }
    }

    fun save() {
        val s = _state.value
        if (s.saving) return
        when (kind) {
            ItemKind.SPEC -> {
                val draft = s.form.toSpec(originalSpec, vehicleId)
                val errors = validateSpec(draft)
                if (errors.isNotEmpty()) return _state.update { it.copy(errors = errors) }
                persist { repository.saveSpec(draft) }
            }
            ItemKind.PART -> {
                val qty = parseOptionalInt(s.form.qty)
                val draft = s.form.toPart(originalPart, vehicleId, qty.getOrNull())
                val errors = validatePart(draft).toMutableMap()
                if (qty.isFailure) errors["qty"] = "Quantity must be a whole number"
                if (errors.isNotEmpty()) return _state.update { it.copy(errors = errors) }
                persist { repository.savePart(draft) }
            }
        }
    }

    fun delete() {
        val id = itemId ?: return
        persist {
            when (kind) {
                ItemKind.SPEC -> repository.deleteSpec(id)
                ItemKind.PART -> repository.deletePart(id)
            }
        }
    }

    private fun persist(block: suspend () -> Unit) {
        _state.update { it.copy(saving = true, errors = emptyMap()) }
        viewModelScope.launch {
            block()
            _state.update { it.copy(saving = false, done = true) }
        }
    }
}

private fun SpecItemEntity.toForm() = ItemForm(
    category = category, component = component, name = name, value = value, unit = unit,
    notes = notes, sourceDocId = sourceDocId, safetyCritical = safetyCritical,
)

private fun PartEntity.toForm() = ItemForm(
    system = system, component = component, oemNumber = oemNumber, aftermarketNumbers = aftermarketNumbers,
    supplier = supplier, qty = qtyPerVehicle?.toString().orEmpty(), notes = notes, sourceDocId = sourceDocId,
)

private fun ItemForm.toSpec(original: SpecItemEntity?, vehicleId: String): SpecItemEntity {
    val base = original ?: SpecItemEntity(
        id = "", departmentId = "", vehicleId = vehicleId, category = category,
        component = "", name = "", value = "", updatedAt = 0, updatedBy = "",
    )
    return base.copy(
        category = category, component = component.trim(), name = name.trim(), value = value.trim(),
        unit = unit.trim(), notes = notes.trim(), sourceDocId = sourceDocId, safetyCritical = safetyCritical,
    )
}

private fun ItemForm.toPart(original: PartEntity?, vehicleId: String, qty: Int?): PartEntity {
    val base = original ?: PartEntity(
        id = "", departmentId = "", vehicleId = vehicleId, system = system, component = "", updatedAt = 0, updatedBy = "",
    )
    val crossRefs = aftermarketNumbers.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    return base.copy(
        system = system, component = component.trim(), oemNumber = oemNumber.trim(), aftermarketNumbers = crossRefs,
        supplier = supplier.trim(), qtyPerVehicle = qty, notes = notes.trim(), sourceDocId = sourceDocId,
    )
}

private fun ItemForm.field(key: String): Any? = when (key) {
    "component" -> component
    "name" -> this.name
    "value" -> value
    "qty" -> qty
    "source" -> sourceDocId to safetyCritical
    else -> Unit
}
