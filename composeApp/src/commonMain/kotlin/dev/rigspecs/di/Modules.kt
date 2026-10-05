package dev.rigspecs.di

import dev.rigspecs.data.ImportRepository
import dev.rigspecs.data.RigRepository
import dev.rigspecs.data.SettingsRepository
import dev.rigspecs.data.importing.ExtractionService
import dev.rigspecs.data.importing.ImportRunner
import dev.rigspecs.data.importing.ServerExtractionService
import dev.rigspecs.data.importing.importHttpClient
import dev.rigspecs.data.vin.KtorVpicRemote
import dev.rigspecs.data.vin.VinDecoder
import dev.rigspecs.data.vin.VpicRemote
import dev.rigspecs.data.vin.vpicHttpClient
import dev.rigspecs.domain.Ids
import dev.rigspecs.domain.ProfileTab
import dev.rigspecs.domain.LocalSession
import dev.rigspecs.domain.Session
import dev.rigspecs.domain.SpecCategory
import dev.rigspecs.domain.SystemIds
import dev.rigspecs.ui.csv.CsvImportViewModel
import dev.rigspecs.ui.fleet.FleetViewModel
import dev.rigspecs.ui.importing.ImportSetupViewModel
import dev.rigspecs.ui.review.ReviewViewModel
import dev.rigspecs.ui.settings.SettingsViewModel
import dev.rigspecs.ui.item.ItemEditViewModel
import dev.rigspecs.ui.item.ItemKind
import dev.rigspecs.ui.profile.ProfileViewModel
import dev.rigspecs.ui.vehicle.VehicleEditViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Everything shared. Each platform adds a module that provides the [dev.rigspecs.data.AppDatabase]. */
val sharedModule: Module = module {
    single<Session> { LocalSession }
    single<Ids> { SystemIds }
    single { RigRepository(get(), get(), get()) }
    single<VpicRemote> { KtorVpicRemote(vpicHttpClient()) }
    single { VinDecoder(get(), storedDecode = get<RigRepository>()::storedDecode) }
    single { SettingsRepository(get(), get(), get(), get()) }
    single { ImportRepository(get(), get(), get(), get()) }
    single<ExtractionService> { ServerExtractionService(importHttpClient(), settings = get<SettingsRepository>()::current) }
    // Imports outlive the screen that started them, so they run in their own app-wide scope.
    single { ImportRunner(get(), get(), get(), get(), get(), CoroutineScope(SupervisorJob() + Dispatchers.Default)) }

    viewModel { FleetViewModel(get(), get()) }
    viewModel { p -> VehicleEditViewModel(p.getOrNull<String>(), get(), get()) }
    viewModel { p -> ProfileViewModel(p.get(), p.getOrNull<ProfileTab>() ?: ProfileTab.OVERVIEW, get(), get(), get(), get(), get(), get()) }
    viewModel { p -> CsvImportViewModel(p.get(), get()) }
    viewModel { p -> ReviewViewModel(p.get(), get(), get(), get()) }
    viewModel { p -> ImportSetupViewModel(p.get(), get(), get(), get(), get(), get(), get()) }
    viewModel { SettingsViewModel(get(), get()) }
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
