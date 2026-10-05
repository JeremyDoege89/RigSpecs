plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "dev.rigspecs"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.rigspecs"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0-m2"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Phones and tablets are ARM; the x86 copies of the barcode and SQLite libraries only
            // serve emulators, which use debug builds.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":composeApp"))
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(project.dependencies.platform(libs.koin.bom))
    implementation(libs.koin.android)
}
