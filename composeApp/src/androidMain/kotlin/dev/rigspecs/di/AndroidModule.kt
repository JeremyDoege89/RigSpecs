package dev.rigspecs.di

import dev.rigspecs.data.AppDatabase
import dev.rigspecs.data.buildDatabase
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val androidModule = module {
    single<AppDatabase> { buildDatabase(androidContext()) }
}
