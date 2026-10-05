import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

/**
 * `-PdesktopOnly=true` leaves Android out entirely, so the desktop app and its Windows installer
 * build on a computer without the Android SDK. Set the same flag for every task in that build.
 */
val desktopOnly = providers.gradleProperty("desktopOnly").orNull == "true"
if (!desktopOnly) apply(plugin = "com.android.kotlin.multiplatform.library")

kotlin {
    if (!desktopOnly) {
        extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
            namespace = "dev.rigspecs.shared"
            compileSdk = 37
            minSdk = 26
            compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        }
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    // iOS targets (iosArm64, iosSimulatorArm64) go here once a Mac is available to build them.

    compilerOptions {
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.compose.material.icons)
            implementation(libs.navigation.compose)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.lifecycle.runtime.compose)

            implementation(libs.room.runtime)
            implementation(libs.sqlite.bundled)

            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.koin.compose.viewmodel)

            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
            implementation(libs.datetime)
            implementation(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        if (!desktopOnly) getByName("androidMain").dependencies {
            implementation(libs.koin.android)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.activity.compose)
            // VIN barcode scanning. The bundled ML Kit model works with no signal and no Play services.
            implementation(libs.camera.camera2)
            implementation(libs.camera.lifecycle)
            implementation(libs.camera.view)
            implementation(libs.camera.mlkit.vision)
            implementation(libs.mlkit.barcode)
            // Page subsets of manuals for assisted import. BouncyCastle only serves certificate-encrypted
            // PDFs; password-free "secured" manuals use the platform's AES, so it stays out of the APK.
            implementation("com.tom-roush:pdfbox-android:${libs.versions.pdfbox.android.get()}") {
                exclude(group = "org.bouncycastle")
            }
            implementation(libs.exifinterface)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.coroutines.swing)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.pdfbox)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.compose.ui.test)
                implementation(libs.room.testing)
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

dependencies {
    if (!desktopOnly) add("kspAndroid", libs.room.compiler)
    add("kspDesktop", libs.room.compiler)
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

room {
    schemaDirectory("$projectDir/schemas")
}

compose.desktop {
    application {
        mainClass = "dev.rigspecs.MainKt"
        // The bundled SQLite driver loads a native library; newer JDKs will block that without this.
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
        nativeDistributions {
            // Msi builds only on Windows; Deb lets this Linux host smoke-test packaging.
            targetFormats(TargetFormat.Msi, TargetFormat.Deb)
            packageName = "RigSpecs"
            packageVersion = "0.3.0"
            description = "Specs, torque values, fluids and part numbers for every rig"
            vendor = "Rig Specs"
            // On top of Compose's defaults (java.desktop, java.logging, jdk.crypto.ec): what
            // `suggestRuntimeModules` found, plus modules libraries can load by reflection.
            modules("java.instrument", "java.management", "jdk.unsupported", "java.xml", "java.naming", "java.sql")
            windows {
                menuGroup = "Rig Specs"
                shortcut = true
                dirChooser = true
                // No admin rights needed, which matters on locked-down department PCs.
                perUserInstall = true
                // Never change this: it is how a new installer finds and replaces the old version.
                upgradeUuid = "6f3d2b1e-8a4c-4f7e-9d2a-5b8c1e0f7a34"
                iconFile.set(project.file("icons/rigspecs.ico"))
            }
            linux {
                iconFile.set(project.file("icons/rigspecs.png"))
            }
        }
    }
}
