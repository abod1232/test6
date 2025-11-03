plugins {
    id("com.android.application") version "8.1.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}

// Line 9 is here -> this block must be exactly correct
subprojects {
    repositories {
        mavenCentral()
        google()
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://api.github.com/repos/recloudstream/Cloudstream-3/packages/maven/cloudstream") }
    }
}
