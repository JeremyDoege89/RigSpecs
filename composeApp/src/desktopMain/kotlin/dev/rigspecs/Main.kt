package dev.rigspecs

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.rigspecs.data.AppDatabase
import dev.rigspecs.data.buildDatabase
import dev.rigspecs.di.sharedModule
import org.koin.core.context.startKoin
import org.koin.dsl.module

fun main() {
    startKoin {
        modules(sharedModule, module { single<AppDatabase> { buildDatabase() } })
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
