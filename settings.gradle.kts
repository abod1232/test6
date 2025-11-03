// This file must be in the root directory.

pluginManagement {
    repositories {
        // CRITICAL: We need Google and Maven Central here to find the Android/Kotlin plugins and the CloudStream plugin
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
// This section must be directly below Part A.
rootProject.name = "CloudstreamProvider"
include(":ExampleProvider")
