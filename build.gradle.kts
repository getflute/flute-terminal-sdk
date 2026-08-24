// Root build file. Plugin versions are declared here and applied per-module.
plugins {
    id("com.android.library") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.16.3"
}

// Single source of truth for published coordinates: com.flute.terminal:{sdk,deeplink-contract}.
allprojects {
    group = "com.flute.terminal"
    version = "0.1.0"
}

apiValidation {
    // Internal wiring is not part of the public contract.
    nonPublicMarkers += listOf()
}

// Portable partner drop: a zipped Maven repository (AARs + POMs + metadata). A consumer registers
// the unzipped folder as a maven repo and adds one dependency line — transitives resolve normally.
// Local packaging only; publishes nowhere.
tasks.register<Zip>("packageDistribution") {
    group = "distribution"
    description = "Zips a portable Maven repo (sdk + deeplink-contract) for manual partner hand-off."
    dependsOn(":sdk:publishReleasePublicationToDistRepository")
    dependsOn(":deeplink-contract:publishReleasePublicationToDistRepository")
    from(layout.buildDirectory.dir("dist-repo"))
    from("docs/DISTRIBUTION_README.md") { rename { "README.md" } }
    archiveFileName.set("flute-terminal-sdk-$version.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
}
