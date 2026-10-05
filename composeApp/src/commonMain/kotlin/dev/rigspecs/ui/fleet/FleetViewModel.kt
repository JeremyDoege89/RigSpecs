package dev.rigspecs.ui.fleet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rigspecs.data.RigRepository
import dev.rigspecs.data.VehicleEntity
import dev.rigspecs.domain.FleetFilter
import dev.rigspecs.domain.SearchGroup
import dev.rigspecs.domain.SearchIndex
import dev.rigspecs.domain.Session
import dev.rigspecs.domain.VehicleType
import dev.rigspecs.domain.filterFleet
import dev.rigspecs.domain.naturalOrder
import dev.rigspecs.domain.stations
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class FleetState(
    val loading: Boolean = true,
    val vehicles: List<VehicleEntity> = emptyList(),
    val totalCount: Int = 0,
    val typesInFleet: List<VehicleType> = emptyList(),
    val stations: List<String> = emptyList(),
    val filter: FleetFilter = FleetFilter(),
    val canEdit: Boolean = false,
    /** Rows waiting for a person per rig: unverified values plus imported rows. */
    val toReview: Map<String, Int> = emptyMap(),
    /** Specs and parts matching the search box, grouped by rig; empty when the box is. */
    val searchGroups: List<SearchGroup> = emptyList(),
) {
    val searching: Boolean get() = filter.query.isNotBlank()
    val hitCount: Int get() = searchGroups.sumOf { it.hits.size }
}

class FleetViewModel(repository: RigRepository, session: Session) : ViewModel() {
    private val filter = MutableStateFlow(FleetFilter())

    /** Rebuilt when the data changes, not on every keystroke. */
    private val index = combine(repository.observeVehicles(), repository.observeSearchableSpecs(), repository.observeSearchableParts()) { v, s, p ->
        SearchIndex(v, s, p)
    }

    val state: StateFlow<FleetState> = combine(repository.observeVehicles(), filter, repository.observeReviewCounts(), index) { all, f, counts, idx ->
        val sorted = all.sortedWith(compareBy(naturalOrder) { it.unitNumber })
        FleetState(
            loading = false,
            vehicles = sorted.filterFleet(f),
            totalCount = all.size,
            typesInFleet = VehicleType.entries.filter { t -> all.any { it.type == t } },
            stations = all.stations(),
            filter = f,
            canEdit = session.canEdit,
            toReview = counts.filter { it.total > 0 }.associate { it.vehicleId to it.total },
            searchGroups = if (f.query.isBlank()) emptyList() else {
                // The type and station chips narrow the search too.
                val allowed = sorted.filterFleet(f.copy(query = "")).map { it.id }.toSet()
                idx.search(f.query).filter { it.vehicle.id in allowed && it.hits.isNotEmpty() }
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FleetState(canEdit = session.canEdit))

    fun setQuery(query: String) = filter.update { it.copy(query = query) }

    fun toggleType(type: VehicleType) = filter.update {
        it.copy(types = if (type in it.types) it.types - type else it.types + type)
    }

    fun toggleStation(station: String) = filter.update {
        it.copy(stations = if (station in it.stations) it.stations - station else it.stations + station)
    }
}
