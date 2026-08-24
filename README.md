# Flute Terminal SDK for Android

Take a card-present payment from your own Android point-of-sale app, on the payment terminal it
runs on. Your app calls one method; the SDK creates the transaction, launches the Flute Terminal
app to collect the card, and returns a typed result to a callback.

```kotlin
// once, in onCreate — before the Activity reaches STARTED
launcher = FluteTerminal.registerForPaymentResult(this) { result -> handleResult(result) }

// whenever you want to take money
launcher.startPayment(PaymentRequest.Builder(BigDecimal("25.00")).build())
```

Card data never touches your app. The Flute Terminal app owns card collection, and the result you
receive is the transaction record as the gateway sees it, not what the terminal reported.

## Requirements

| | |
|---|---|
| Hardware | A Flute-provisioned Android payment terminal (for example Sunmi P2 Pro, Verifone T650) |
| Flute Terminal app | Installed and activated on the same device, in **Semi-Integrated** mode |
| Android | `minSdk` 25 or higher; compiled against `compileSdk` 34 |
| Credentials | Merchant-scoped `clientId` / `clientSecret` issued by Flute |
| Language | Kotlin or Java — the public surface is Java-interoperable |

## Install

The SDK ships as a portable Maven repository. Unzip it next to your project, register it in
`settings.gradle.kts`, and add one dependency:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("../flute-terminal-sdk") }
    }
}
```

```kotlin
dependencies {
    implementation("com.flute.terminal:sdk:1.0.0")
}
```

Retrofit, OkHttp and coroutines resolve from Maven Central automatically. The
`com.flute.terminal:deeplink-contract` artifact resolves alongside the SDK; do not depend on it
directly.

## Getting started

**[docs/ANDROID_SDK_DOCUMENTATION.md](docs/ANDROID_SDK_DOCUMENTATION.md)** is the integration
guide: environments, initialization, taking a payment, handling the result, post-payment
operations, and recovery after process death.

Credentials are entered at runtime and stored encrypted per environment — never build them into
your APK. A `buildConfigField` is compiled in as a plaintext string, so every copy of your app
would carry the merchant's secret to whoever holds the file.

Integrate against `SANDBOX`. It runs the same deployment as production, separated by hostname, so
no real money moves and the wire behaviour is identical to live.

## What the SDK covers

**Payments** — sale, authorization for later capture, unreferenced refund, cancel in flight.
**After the sale** — capture, tip adjustment, void or refund, transaction lookup, receipt reprint
and share.
**Discovery** — the merchant's terminals and payment configuration, and this device's serial.
**Resilience** — a payment interrupted by process death is resolved on the next launch, and a
terminal that never returns is resolved against the API rather than hanging.

Every callback is delivered exactly once, on the main thread, for every outcome — approval,
decline, cancellation, timeout, or a failure before the terminal was ever launched.

## Errors and support

Every API failure carries the platform error envelope: an error code, a correlation id, and
per-field messages where they apply. **Quote the correlation id in support tickets** — it traces
the operation across services.

A decline is not an error. It is a completed transaction with a negative answer, and it arrives as
its own result type.

## Layout

```
sdk/                  the SDK your app depends on
deeplink-contract/    the Intent contract the SDK and the Flute Terminal app share
docs/                 integration guide and distribution notes
```

## License

Copyright Flute Commerce LLC. All rights reserved.
