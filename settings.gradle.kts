rootProject.name = "CloudstreamProvider"

// --- FIX: Ensure JitPack is correctly configured for dependency resolution ---
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Add JitPack at the end for proper dependency resolution
        maven { url = uri("https://jitpack.io") }
    }
}
