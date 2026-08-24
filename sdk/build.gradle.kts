plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
}

// Published artifacts are immutable, so any change ships as a new version. Versioned per module
// rather than repo-wide: deeplink-contract is a frozen set of Intent keys and stays at 0.1.0 —
// bumping it would force a matching change in the terminal app for no reason.
version = "1.0.0"

android {
    publishing {
        singleVariant("release") { withSourcesJar() }
    }

    namespace = "com.flute.terminal.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 25
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Shared Intent contract with the Flute Terminal app — exposed transitively (api).
    api(project(":deeplink-contract"))

    implementation("androidx.core:core-ktx:1.13.1")
    // ComponentActivity + ActivityResult APIs (registerForPaymentResult).
    implementation("androidx.activity:activity-ktx:1.9.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Networking: OAuth token + POST /v2/pos/transactions.
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // Encrypted-at-rest persistence for credentials, token, and cached config (Android Keystore).
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

// ---- Publishing (com.flute.terminal:sdk) ----
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
            artifactId = "sdk"
            afterEvaluate { from(components["release"]) }
        }
    }
}
