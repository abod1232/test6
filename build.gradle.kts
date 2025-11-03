plugins {
    id("com.android.application") version "8.1.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}

// Subprojects block is empty to avoid Gradle 8.x error
subprojects {
    // Repositories are now defined in settings.gradle.kts
}
