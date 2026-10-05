package dev.rigspecs.di

import dev.rigspecs.data.AppDatabase
import dev.rigspecs.data.buildDatabase
import dev.rigspecs.data.importing.AndroidDocumentFiles
import dev.rigspecs.data.importing.AndroidDocumentPages
import dev.rigspecs.data.importing.DocumentFiles
import dev.rigspecs.data.importing.DocumentPages
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val androidModule = module {
    single<AppDatabase> { buildDatabase(androidContext()) }
    single<DocumentPages> { AndroidDocumentPages(androidContext()) }
    single<DocumentFiles> { AndroidDocumentFiles(androidContext()) }
}
