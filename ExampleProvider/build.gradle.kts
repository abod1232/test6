// Apply the 'com.lagradost.cloudstream3.provider' plugin
// This is usually at the top, if it's not, you might need to add it
// apply plugin: 'com.lagradost.cloudstream3.provider'

dependencies {
    // This is the CRITICAL line you were missing.
    // It links your provider to the main CloudStream app.
    implementation(project(":app"))

    // Standard Android libraries (yours were fine)
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Other standard libraries often needed
    implementation("androidx.core:core-ktx:1.13.1") // Or a similar version
    implementation("androidx.appcompat:appcompat:1.6.1") // Or a similar version
}

// Use an integer for version numbers
version = 1

cloudstream {
    // All of these properties are optional, you can safely remove any of them.

    description = "A provider that scrapes multiple sources" // Updated description
    authors = listOf("YourUsername") // Change this to your GitHub username

    /**
    * Status int as one of the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta-only
    **/
    status = 1 // 1 = OK

    // You are supporting Movies, TVSeries, and Anime in your code
    // This list MUST match what's in your Kotlin file
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime"
    )

    requiresResources = false // Set to false if you don't use custom layouts
    language = "en" // Matches your Kotlin file

    // You can keep this or remove it
    iconUrl = "https://upload.wikimedia.org/wikipedia/commons/2/2f/Korduene_Logo.png"
}

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    // Add this to ensure Java 17+ compatibility, which is required
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
