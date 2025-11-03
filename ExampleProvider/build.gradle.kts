plugins {
    id("com.lagacy.cloudstream.plugin") version "1.0.1"
}

dependencies {
    // This is the CRITICAL line that downloads the necessary library
    implementation("com.lagacy:ext-api:master-SNAPSHOT")
}
