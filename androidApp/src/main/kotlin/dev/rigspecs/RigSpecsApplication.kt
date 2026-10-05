package dev.rigspecs

import android.app.Application
import dev.rigspecs.di.androidModule
import dev.rigspecs.di.sharedModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class RigSpecsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@RigSpecsApplication)
            modules(sharedModule, androidModule)
        }
    }
}
