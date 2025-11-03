pluginManagement {
    repositories {
        google()
        mavenCentral()
        maven(url = "https://api.github.com/repos/recloudstream/cloudstream-3/packages/maven/cloudstream")
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "FLUMMOX-Repo"
include("ExampleProvider")
