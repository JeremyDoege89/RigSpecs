pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "RigSpecs"
// Shared UI, data and logic for Android, desktop (Windows) and, later, iOS.
include(":composeApp")
// Thin Android shell: AGP 9 keeps com.android.application out of KMP modules.
// Left out of desktop-only builds (-PdesktopOnly=true), which need no Android SDK.
if (providers.gradleProperty("desktopOnly").orNull != "true") include(":androidApp")
