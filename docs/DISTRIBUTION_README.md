# Flute Terminal SDK — integration package

This zip is a **portable Maven repository** containing the Flute Terminal SDK for Android.

## Contents

- `com.flute.terminal:sdk` — the SDK your app depends on (one line, see below)
- `com.flute.terminal:deeplink-contract` — internal contract, resolved automatically; do not depend on it directly

## Setup (2 steps)

**1.** Unzip anywhere, e.g. next to your project as `flute-terminal-sdk/`, and register it in
`settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("../flute-terminal-sdk") } // path to the unzipped folder
    }
}
```

**2.** Add the dependency to your app module:

```kotlin
dependencies {
    implementation("com.flute.terminal:sdk:1.0.0")
}
```

Third-party dependencies (Retrofit, OkHttp, coroutines, …) resolve from Maven Central
automatically — nothing else to add.

## Credentials

The SDK needs the merchant-scoped API credentials issued to you by Flute (`clientId` /
`clientSecret`). Supply them **once** via `FluteTerminal.initialize` — the SDK stores them
encrypted on the device. Never hardcode them in source; inject via `local.properties` →
`BuildConfig` or your own secrets mechanism.

## Requirements

- minSdk 25, JDK 17 toolchain
- The Flute Terminal app must be installed on the device to process payments

## Versioning

Each package is immutable: a new drop always carries a new version number. If you have an issue,
report the SDK version and — for API errors — the `correlationId` from the error details.

## Changelog

### 1.0.0
- Initial pilot package: deeplink payment initiation (`startPayment` / `startPaymentAutoResolve`),
  typed results with canonical API reconciliation, timeout + process-death recovery
  (`checkPendingPayment`), terminal/payment-config discovery, UAT environment.
