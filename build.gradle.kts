// Root level build.gradle.kts
// This file is crucial for setting up plugins and repositories.

plugins {
    // Standard Android and Kotlin plugins needed for the project structure
    id("com.android.application") version "8.5.1" apply false
    id("com.android.library") version "8.5.1" apply false
    kotlin("android") version "1.9.24" apply false
    
    // CloudStream Gradle Plugin - required for the 'cloudstream' block
    id("com.lagradost.cloudstream3.provider") version "1.4.2" apply false
}

// CRITICAL FIX: Defines where Gradle should look for libraries (including JitPack for CloudStream)
allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") // Must have JitPack to find the CloudStream library
    }
}
