pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://api.github.com/repos/recloudstream/Cloudstream-3/packages/maven/cloudstream") }
    }
}

// Add the same repositories for dependency resolution
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://api.github.com/repos/recloudstream/Cloudstream-3/packages/maven/cloudstream") }
    }
}

rootProject.name = "FLUMMOX-Repo"
include(":ExampleProvider")
