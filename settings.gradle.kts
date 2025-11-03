// This file must be in the root directory.

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// CRITICAL FIX: Include the provider plugin repository source directly from GitHub
includeBuild("https://github.com/recloudstream/Cloudstream3") {
    dependencySubstitution {
        // Substitute the official plugin ID with the source code from the included build
        substitute(module("com.lagradost.cloudstream3:provider")).using(project(":provider"))
    }
}

rootProject.name = "CloudstreamProvider"
include(":ExampleProvider")
