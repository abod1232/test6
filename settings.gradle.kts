pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // Include JitPack and CloudStream repository for plugins
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://api.github.com/repos/recloudstream/Cloudstream-3/packages/maven/cloudstream") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // Include JitPack and CloudStream repository for dependencies
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://api.github.com/repos/recloudstream/Cloudstream-3/packages/maven/cloudstream") }
    }
}

rootProject.name = "FLUMMOX-Repo"
include(":ExampleProvider")
