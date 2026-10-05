package dev.rigspecs

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.rigspecs.di.ItemId
import dev.rigspecs.domain.SpecCategory
import dev.rigspecs.domain.defaultCategory
import dev.rigspecs.ui.fleet.FleetScreen
import dev.rigspecs.ui.importing.ImportSetupScreen
import dev.rigspecs.ui.review.ReviewScreen
import dev.rigspecs.ui.settings.SettingsScreen
import dev.rigspecs.ui.item.ItemEditScreen
import dev.rigspecs.ui.item.ItemKind
import dev.rigspecs.ui.profile.ProfileScreen
import dev.rigspecs.ui.theme.RigSpecsTheme
import dev.rigspecs.ui.vehicle.VehicleEditScreen
import kotlinx.serialization.Serializable
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Serializable data object FleetRoute
@Serializable data class VehicleEditRoute(val vehicleId: String? = null)
@Serializable data class ProfileRoute(val vehicleId: String)
@Serializable data class ReviewRoute(val vehicleId: String)
@Serializable data class ImportSetupRoute(val documentId: String, val vehicleId: String)
@Serializable data object SettingsRoute
// Enums travel as names: only Android's navigation maps enum arguments on its own.
@Serializable data class ItemEditRoute(
    val kind: String,
    val vehicleId: String,
    val itemId: String? = null,
    val category: String? = null,
) {
    constructor(kind: ItemKind, vehicleId: String, itemId: String? = null, category: SpecCategory? = null) :
        this(kind.name, vehicleId, itemId, category?.name)
}

@Composable
fun App() {
    RigSpecsTheme {
        val nav = rememberNavController()
        NavHost(nav, startDestination = FleetRoute) {
            composable<FleetRoute> {
                FleetScreen(
                    viewModel = koinViewModel(),
                    onOpenVehicle = { nav.navigate(ProfileRoute(it)) },
                    onAddVehicle = { nav.navigate(VehicleEditRoute()) },
                    onOpenSettings = { nav.navigate(SettingsRoute) },
                )
            }
            composable<VehicleEditRoute> { entry ->
                val route = entry.toRoute<VehicleEditRoute>()
                VehicleEditScreen(
                    viewModel = koinViewModel { parametersOf(route.vehicleId) },
                    onBack = { nav.popBackStack() },
                    onSaved = { id, isNew ->
                        // A new rig opens on its profile; an edit returns to the profile it came from.
                        if (isNew) {
                            nav.navigate(ProfileRoute(id)) { popUpTo<VehicleEditRoute> { inclusive = true } }
                        } else {
                            nav.popBackStack()
                        }
                    },
                )
            }
            composable<ProfileRoute> { entry ->
                val id = entry.toRoute<ProfileRoute>().vehicleId
                ProfileScreen(
                    viewModel = koinViewModel { parametersOf(id) },
                    onBack = { nav.popBackStack(FleetRoute, inclusive = false) },
                    onEditVehicle = { nav.navigate(VehicleEditRoute(id)) },
                    onAddSpec = { tab -> nav.navigate(ItemEditRoute(ItemKind.SPEC, id, category = tab.defaultCategory())) },
                    onEditSpec = { nav.navigate(ItemEditRoute(ItemKind.SPEC, id, itemId = it)) },
                    onAddPart = { nav.navigate(ItemEditRoute(ItemKind.PART, id)) },
                    onEditPart = { nav.navigate(ItemEditRoute(ItemKind.PART, id, itemId = it)) },
                    onReview = { nav.navigate(ReviewRoute(id)) },
                    onImport = { docId -> nav.navigate(ImportSetupRoute(docId, id)) },
                )
            }
            composable<ReviewRoute> { entry ->
                val id = entry.toRoute<ReviewRoute>().vehicleId
                ReviewScreen(
                    viewModel = koinViewModel { parametersOf(id) },
                    onBack = { nav.popBackStack() },
                    onEditSpec = { nav.navigate(ItemEditRoute(ItemKind.SPEC, id, itemId = it)) },
                    onEditPart = { nav.navigate(ItemEditRoute(ItemKind.PART, id, itemId = it)) },
                )
            }
            composable<ImportSetupRoute> { entry ->
                val r = entry.toRoute<ImportSetupRoute>()
                ImportSetupScreen(
                    viewModel = koinViewModel { parametersOf(r.documentId) },
                    onBack = { nav.popBackStack() },
                    onStarted = { nav.navigate(ReviewRoute(r.vehicleId)) { popUpTo<ImportSetupRoute> { inclusive = true } } },
                    onOpenSettings = { nav.navigate(SettingsRoute) },
                )
            }
            composable<SettingsRoute> {
                SettingsScreen(viewModel = koinViewModel(), onBack = { nav.popBackStack() })
            }
            composable<ItemEditRoute> { entry ->
                val r = entry.toRoute<ItemEditRoute>()
                ItemEditScreen(
                    viewModel = koinViewModel {
                        val args = listOfNotNull(
                            ItemKind.valueOf(r.kind),
                            r.vehicleId,
                            r.itemId?.let(::ItemId),
                            r.category?.let(SpecCategory::valueOf),
                        )
                        parametersOf(*args.toTypedArray())
                    },
                    onClose = { nav.popBackStack() },
                )
            }
        }
    }
}
