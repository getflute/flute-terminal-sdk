plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
}

android {
    publishing {
        singleVariant("release") { withSourcesJar() }
    }

    namespace = "com.flute.terminal.deeplink"
    compileSdk = 34

    defaultConfig {
        minSdk = 25
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Deliberately dependency-free (Android SDK only): both the Flute Terminal app and the
// Terminal SDK compile against this exact artifact, so the Intent contract cannot drift.

// ---- Publishing (com.flute.terminal:deeplink-contract) ----
// Publishes the release AAR + sources. Local verification: ./gradlew publishToMavenLocal
// TODO(infra): point `repositories` at the internal Maven repo once it exists.
publishing {
    repositories {
        // Local directory target for the manual partner drop (packageDistribution zips it).
        maven {
            name = "dist"
            url = uri(rootProject.layout.buildDirectory.dir("dist-repo"))
        }
    }
    publications {
        register<MavenPublication>("release") {
            artifactId = "deeplink-contract"
            afterEvaluate { from(components["release"]) }
        }
    }
}
