package dev.rigspecs

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.rigspecs.data.AppDatabase
import dev.rigspecs.data.buildDatabase
import dev.rigspecs.data.importing.DesktopDocumentFiles
import dev.rigspecs.data.importing.DesktopDocumentPages
import dev.rigspecs.data.importing.DocumentFiles
import dev.rigspecs.data.importing.DocumentPages
import dev.rigspecs.di.sharedModule
import org.koin.core.context.startKoin
import org.koin.dsl.module

val desktopModule = module {
    single<AppDatabase> { buildDatabase() }
    single<DocumentPages> { DesktopDocumentPages() }
    single<DocumentFiles> { DesktopDocumentFiles() }
}

fun main() {
    startKoin {
        modules(sharedModule, desktopModule)
    }
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Rig Specs",
            state = rememberWindowState(width = 480.dp, height = 860.dp),
        ) {
            App()
        }
    }
}
