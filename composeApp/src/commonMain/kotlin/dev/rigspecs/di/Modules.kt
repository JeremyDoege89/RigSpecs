package dev.rigspecs.di

import dev.rigspecs.data.RigRepository
import dev.rigspecs.domain.Ids
import dev.rigspecs.domain.LocalSession
import dev.rigspecs.domain.Session
import dev.rigspecs.domain.SpecCategory
import dev.rigspecs.domain.SystemIds
import dev.rigspecs.ui.fleet.FleetViewModel
import dev.rigspecs.ui.item.ItemEditViewModel
import dev.rigspecs.ui.item.ItemKind
import dev.rigspecs.ui.profile.ProfileViewModel
import dev.rigspecs.ui.vehicle.VehicleEditViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Everything shared. Each platform adds a module that provides the [dev.rigspecs.data.AppDatabase]. */
val sharedModule: Module = module {
    single<Session> { LocalSession }
    single<Ids> { SystemIds }
    single { RigRepository(get(), get(), get()) }

    viewModel { FleetViewModel(get(), get()) }
    viewModel { p -> VehicleEditViewModel(p.getOrNull<String>(), get()) }
    viewModel { p -> ProfileViewModel(p.get(), get(), get()) }
    viewModel { p ->
        ItemEditViewModel(
            kind = p.get<ItemKind>(),
            vehicleId = p.get<String>(),
            itemId = p.getOrNull<ItemId>()?.value,
            initialCategory = p.getOrNull<SpecCategory>(),
            repository = get(),
        )
    }
}

/** Wraps the optional item id so Koin can tell it apart from the vehicle id by type. */
data class ItemId(val value: String)
