plugins {
    `java-library`
}

repositories {
    maven {
        url = uri("https://maven.pkg.github.com/recloudstream/cloudstream")
        credentials {
            username = System.getenv("GITHUB_ACTOR")
            password = System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation("com.github.recloudstream:cloudstream:master-SNAPSHOT")
}
