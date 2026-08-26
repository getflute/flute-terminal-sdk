# Flute Terminal SDK for Android

## Overview

The Flute Mobile SDK for Android lets an ISV point-of-sale app take a **card-present payment on the
same device it runs on**. The app calls one method; the SDK creates the transaction, launches the
Flute Terminal app to collect the card, and returns a typed result to a callback.

It is the counterpart to the [POS Terminal Integration](https://developer.flute.com/docs/in-person-payments/pos-terminal-integration)
REST flow, with one important difference:

| | **Android SDK (this page)** | **POS Terminal Integration (REST)** |
|---|---|---|
| Where your app runs | On the payment terminal itself | Anywhere — back office, web POS, another till |
| How the terminal is reached | Deeplink to the Flute Terminal app on-device | Cloud push over the terminal's websocket |
| Terminal must be Online/Ready at create time | No | Yes |
| How you get the outcome | Callback, delivered exactly once | Poll `GET /v2/pos/transactions/{id}` |
| Integration effort | One method + one callback | Create, poll, interpret status |

> 📝 **Note.** The [iOS SDK](https://developer.flute.com/docs/sdk/ios) is not the same integration. It talks to the Flute API
> directly and supports Tap to Pay on iPhone. The Android SDK described here drives a physical
> terminal through the Flute Terminal app. If you need off-device initiation from an Android app,
> use the REST flow instead.

## How it works

1. Your app calls `startPayment()` with an amount.
2. The SDK resolves the terminal and currency, and asks the gateway for the amounts to display
   (surcharge, cash discount, dual pricing, tip).
3. The SDK creates the POS transaction through the Flute API.
4. The SDK launches the Flute Terminal app, which collects the card and processes the payment.
5. The Flute Terminal app returns to your app.
6. The SDK re-fetches the **canonical** outcome from the API and delivers it to your callback.

> 📝 **Note.** Step 6 is why the result is trustworthy. The value handed back by the terminal app is
> treated as a hint only; the outcome you receive is the transaction record as the gateway sees it.

## Get started

### Prerequisites

| Requirement | Detail |
|---|---|
| Hardware | A Flute-provisioned Android payment terminal (for example Sunmi P2 Pro, Verifone T650) |
| Flute Terminal app | Installed and activated on the same device, in **Semi-Integrated** mode |
| Android | `minSdk` 25 or higher; the SDK is compiled against `compileSdk` 34 |
| Credentials | Merchant-scoped `clientId` / `clientSecret` issued by Flute |
| Language | Kotlin or Java — the public surface is Java-interoperable |

### Environments

| Environment | API base URL | OAuth base URL |
|---|---|---|
| `SANDBOX` | `https://sandbox.api.flute.com` | `https://sandbox.oauth.api.flute.com` |
| `PRODUCTION` | `https://api.flute.com` | `https://oauth.api.flute.com` |

Integrate against `SANDBOX`. It runs the same deployment as production, separated by hostname, and
your key's account kind decides which surface it may use — so no real money moves and the wire
behaviour is identical to live.

> ⚠️ **Warning.** On `PRODUCTION`, every payment and refund is real — cards are charged and money
> moves.

> ⚠️ **Warning.** Credentials are scoped to one environment, and the API rejects a mismatch: a
> sandbox key on the production host, or a live key on the sandbox host, both fail — as does a key
> issued for a different environment. A mismatch surfaces as `401` during warm-up.

### Installation

The SDK ships as a portable Maven repository. Unzip it next to your project and register it in
`settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("../flute-terminal-sdk") }
    }
}
```

Then add the dependency to your app module:

```kotlin
dependencies {
    implementation("com.flute.terminal:sdk:1.0.0")
}
```

Third-party dependencies (Retrofit, OkHttp, coroutines) resolve from Maven Central automatically.

## Init and session management

### `initialize`

Call once, typically from `Application.onCreate()`.

```kotlin
FluteTerminal.initialize(
    applicationContext,
    FluteTerminalConfig(
        environment = FluteTerminalConfig.Environment.SANDBOX,
    ),
) { result ->
    when (result) {
        is FluteResult.Success -> Log.i("Flute", "SDK ready")
        is FluteResult.Failure -> Log.e("Flute", "Warm-up failed: ${result.error.message}")
    }
}
```

Initialization is idempotent and warms up in the background: it fetches and caches an OAuth token,
loads the merchant's payment configuration, resolves the terminal from the device serial, and starts
the token-refresh loop. The `onReady` callback is optional — on-demand calls still work if warm-up is
skipped or fails.

**`FluteTerminalConfig` parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `environment` | `Environment` | Yes | Target environment. |
| `clientId` | `String?` | First run only | Persisted encrypted; omit on later launches. |
| `clientSecret` | `String?` | First run only | As above. |
| `serialNumber` | `String?` | No | Overrides the auto-detected serial. Needed only where the SDK cannot resolve it — an emulator, or a device whose Flute Terminal app predates serial publishing. |
| `apiBaseUrlOverride` | `String?` | No | Point at a non-standard API host. |
| `identityBaseUrlOverride` | `String?` | No | Point at a non-standard OAuth host. |
| `enableHttpLogging` | `Boolean` | No | Logs requests/responses. Disable in production. |
| `logger` | `FluteLogger?` | No | Receives redacted SDK diagnostics. |
| `terminalResultTimeoutSeconds` | `Long` | No | Watchdog for a terminal that never returns. |

> ⚠️ **Important.** Do not supply credentials as a build input. A `buildConfigField` is compiled
> into the APK as a plaintext string, so every copy of your app carries the merchant's secret to
> whoever holds the file — recoverable with `strings` alone. Collect them once in an onboarding
> screen and pass them to [`provisionCredentials`](#provisioncredentials); the SDK stores them
> encrypted per environment and reuses them on later launches, so `initialize` needs no credentials
> at all.

### `provisionCredentials`

Supplies or rotates credentials at runtime, without a rebuild. The SDK stores them encrypted per
environment, drops any token issued to the previous credentials, and re-warms.

```kotlin
FluteTerminal.provisionCredentials(clientId, clientSecret) { result ->
    // FluteResult.Failure with httpStatus 401 = wrong keys, or keys for another environment
}
```

### `hasCredentials`, `shutdown`

| Method | Returns | Description |
|---|---|---|
| `hasCredentials()` | `Boolean` | Whether this device holds credentials for the selected environment. |
| `shutdown()` | `Unit` | Releases the SDK. A launcher registered against it stops delivering; re-register after re-initializing. |

## Transactions

### `registerForPaymentResult`

Registers the result callback and returns the launcher used to start payments.

```kotlin
class CheckoutActivity : ComponentActivity() {
    private lateinit var launcher: FluteTerminalLauncher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launcher = FluteTerminal.registerForPaymentResult(this) { result ->
            handleResult(result)
        }
    }
}
```

> ⚠️ **Important.** Must be called before the Activity reaches `STARTED` — `onCreate()` is the only
> safe place. Registering later throws `RegistrationLifecycleException`.

The callback fires **exactly once** per payment, on the **main thread**, for every outcome: approval,
decline, cancellation, timeout, or a failure before the terminal was ever launched.

### `startPayment`

```kotlin
launcher.startPayment(
    PaymentRequest.Builder(BigDecimal("25.00"))
        .posDeviceId("TILL-1")
        .referenceId(orderId)
        .build(),
)
```

**`PaymentRequest` parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `baseAmount` | `BigDecimal` | Yes | Amount before ZCP/tip math. Two decimals maximum. |
| `pricingType` | `PricingType?` | Conditional | `CARD` or `CASH`. **Required for Dual Pricing merchants** — states which price `baseAmount` is. Must be omitted otherwise. |
| `captureMethod` | `CaptureMethod` | No | `AUTO` (sale, default) or `MANUAL` (authorization to capture later). |
| `readingMethod` | `ReadingMethod?` | No | `KEYED_ENTRY` opens manual card entry on the terminal. Omit to let the terminal use tap/insert/swipe. |
| `tipAmount` | `BigDecimal?` | No | Preset tip. Omit to let the terminal prompt. |
| `tipRatePercent` | `BigDecimal?` | No | Preset tip as a percentage. |
| `referenceId` | `String?` | No | Your order reference, echoed on the transaction. |
| `posDeviceId` | `String?` | No | Identifies the till within your estate. |
| `paymentProcessorId` | `String?` | No | Overrides the merchant's default processor. |
| `customerId` | `String?` | No | Associates the payment with a stored customer. |
| `requestPaymentMethodStorageConsent` | `Boolean` | No | Ask the cardholder to store the card. |

> 📝 **Note.** `terminalId` and `currencyCode` are resolved by the SDK from the device and the
> merchant configuration. You do not supply them.

Only one payment may be in flight at a time. A second call while one is running is rejected
immediately with `ErrorReason.ALREADY_IN_PROGRESS` and cannot disturb the running payment.

### `startRefund`

An unreferenced refund — money returned to whatever card the customer presents, with no originating
transaction.

```kotlin
launcher.startRefund(
    RefundRequest(
        refundAmount = BigDecimal("25.00"),
        posDeviceId = "TILL-1",
    ),
)
```

The outcome arrives on the same callback, as `PaymentResult.Approved` with `transactionType`
identifying it as the refund and a negative `processedAmount`.

> ⚠️ **Warning.** An unreferenced refund moves real money to a card you cannot match against an
> earlier sale. Confirm the amount with the operator before calling it.

### `cancelPayment`

Cancels the payment currently in flight, before the cardholder completes it.

```kotlin
FluteTerminal.pendingPosTransactionId()?.let { id ->
    FluteTerminal.cancelPayment(id) { /* accepted or rejected */ }
}
```

The result still arrives on the payment callback. This method reports only whether the cancel
*request* was accepted — the backend rejects it once the transaction has reached the processor.

## Handling the result

`PaymentResult` is a sealed class with three cases.

### `PaymentResult.Approved`

| Property | Type | Description |
|---|---|---|
| `posTransactionId` | `String` | The POS transaction record. |
| `transactionId` | `String?` | The gateway transaction. |
| `authCode` | `String?` | Processor authorization code. |
| `responseCode` | `String?` | Processor response code. |
| `processedAmount` | `BigDecimal?` | **Total actually charged.** Reconcile against this, not the requested base. |
| `amounts` | `AmountBreakdown?` | Base, tip, surcharge, discount, and their rates. |
| `card` | `CardInfo?` | Masked PAN, brand, type, entry method, CVM. |
| `processor` | `ProcessorReferences?` | Auth code, RRN, MID, TID — receipt and dispute references. |
| `avs` | `AvsResult?` | Address verification outcome. |
| `availableRefundAmount` | `BigDecimal?` | Refundable remainder. |
| `transactionType` | `String?` | `"Sale"`, `"Authorization"`, `"Refund"`. |
| `transactionDateTime` | `String?` | Gateway timestamp. |
| `receiptData` | `String?` | Opaque receipt payload to render or print. |

### `PaymentResult.Declined`

Carries `posTransactionId`, `transactionId`, `responseCode`, `message`, plus `processedAmount`,
`amounts`, `card`, `avs`, `gatewayReferenceId` and `transactionDateTime` where available.

> 📝 **Note.** A decline is a completed transaction with a negative answer — not an error. AVS and
> CVV rejections arrive here, with the reason in `message`.

### `PaymentResult.Error`

| Property | Type | Description |
|---|---|---|
| `reason` | `ErrorReason` | Machine-readable cause (below). |
| `message` | `String?` | Human-readable detail. |
| `posTransactionId` | `String?` | Present when the record was created before the failure. |
| `correlationId` | `String?` | Flute trace id — **quote this in support tickets.** |

**`ErrorReason` values**

| Value | Meaning | Suggested handling |
|---|---|---|
| `USER_CANCELLED` | Cancelled on the terminal or by your app. | Return to the order. |
| `TIMEOUT` | No result within the configured timeout. | The transaction may still complete — reconcile with `checkPendingPayment()`. |
| `ALREADY_IN_PROGRESS` | Another payment is running. | Wait for its result. |
| `TRANSACTION_CREATION_FAILED` | The transaction was never created. | Show the message; safe to retry. |
| `AUTHENTICATION_FAILED` | Credentials rejected. | Re-provision credentials. |
| `APP_NOT_INSTALLED` | Flute Terminal app missing or cannot handle the deeplink. | Install/activate the terminal app. |
| `UNAUTHORIZED_CALLER` | This app is not permitted to start payments. | Contact Flute. |
| `TERMINAL_FAILED` | The terminal flow failed. | Retry. |
| `MALFORMED_RESPONSE` | Unexpected response shape. | Retry; report with the correlationId. |
| `NOT_INITIALIZED` | `initialize()` was not called, or the SDK was shut down. | Initialize first. |
| `UNKNOWN` | Unclassified. | Show the message; report with the correlationId. |

```kotlin
private fun handleResult(result: PaymentResult) = when (result) {
    is PaymentResult.Approved -> completeOrder(result.transactionId, result.processedAmount)
    is PaymentResult.Declined -> showDeclined(result.message)
    is PaymentResult.Error -> when (result.reason) {
        ErrorReason.USER_CANCELLED -> returnToOrder()
        ErrorReason.TIMEOUT -> reconcileLater(result.posTransactionId)
        else -> showError(result.message, result.correlationId)
    }
}
```

## Post-payment operations

All are `@JvmStatic` on `FluteTerminal` and deliver a `FluteCallback` on the main thread.

| Method | Description |
|---|---|
| `capture(transactionId, amount?, callback)` | Captures an authorization, fully or partially. |
| `adjustTip(transactionId, tipAmount?, tipRate?, callback)` | Adjusts the tip on a captured transaction. |
| `reverseTransaction(transactionId, amount?, callback)` | Voids or refunds an existing transaction, fully or partially. |
| `getTransaction(transactionId, callback)` | Fetches the current transaction record. |
| `printReceipt(posTransactionId, callback)` | Reprints the receipt on the terminal. |
| `shareReceipt(transactionId, method, recipient, hasCustomerConsent, callback)` | Sends the receipt by `SMS` or `EMAIL`. Pass `hasCustomerConsent = true` only when the cardholder agreed to be contacted. |

## Device and merchant information

| Method | Returns | Description |
|---|---|---|
| `fetchTerminals(callback)` | `List<TerminalInfo>` | Terminals on the merchant account, with online status. |
| `fetchPaymentConfig(callback)` | `PaymentConfig` | Currency, zero-cost-processing option, processors. Live fetch. |
| `deviceSerialNumber()` | `String?` | Serial the SDK resolved for this device. |

> 📝 **Note.** Check `PaymentConfig.requiresPricingType` to decide whether `pricingType` must be set
> on every payment. It is `true` for Dual Pricing merchants.

> 📝 **Note.** The SDK exposes no cached accessor. Caching policy is yours: how long a stale
> currency or pricing mode is tolerable depends on your UI, so hold the result of
> `fetchPaymentConfig` for as long as that suits you.

## Recovery after process death

If your app is killed while the terminal is collecting the card, the in-flight transaction id is
persisted. Reconcile at startup, before taking new payments:

```kotlin
FluteTerminal.checkPendingPayment { check ->
    check.onSuccess { pending ->
        when {
            !pending.hasPending -> Unit                       // nothing was interrupted
            pending.stillInProgress -> waitForTerminal()      // terminal has not finished yet
            else -> pending.result?.let { handleResult(it) }  // resolved outcome
        }
    }
}
```

**`PendingPaymentCheck`**

| Property | Type | Description |
|---|---|---|
| `hasPending` | `Boolean` | Whether a payment was left in flight. |
| `stillInProgress` | `Boolean` | The terminal has not produced an outcome yet. |
| `result` | `PaymentResult?` | The resolved outcome, when one exists. |

| Method | Description |
|---|---|
| `checkPendingPayment(callback)` | Resolves a payment left in flight, returning its outcome or that it is still running. |
| `pendingPosTransactionId()` | The persisted in-flight id, if any. |
| `parseResult(activityResult)` | Parses a terminal `ActivityResult` directly. Escape hatch; the callback is the supported path. |

## Threading and lifecycle

- Every callback is delivered on the **main thread** — you may touch UI directly.
- The payment flow runs on a process-wide scope, so it survives your Activity being recreated while
  the terminal app is in front.
- `registerForPaymentResult` must be called in `onCreate()`, before `STARTED`.
- After `shutdown()` or an `initialize()` with changed configuration, re-register to obtain a live
  launcher.

## Troubleshooting

| Symptom | Cause | Resolution |
|---|---|---|
| `401` during warm-up | Credentials belong to another environment | Provision keys for the selected environment. |
| `Transaction is already in progress` | A previous POS transaction is unresolved on the terminal | Cancel it, or wait for the SDK to release it. |
| `Merchant uses Dual Pricing; pricingType is required` | `pricingType` omitted on a Dual Pricing merchant | Set `CARD` or `CASH`. |
| `APP_NOT_INSTALLED` | Flute Terminal app missing, or not in Semi-Integrated mode | Install and activate it. |
| Result never arrives | Launcher registered after `STARTED`, or against a shut-down SDK | Register in `onCreate()`; re-register after re-initializing. |

## Changelog

| Version | Changes |
|---|---|
| 1.0.0 | First release. Payments and unreferenced refunds; result delivery with exactly-once callbacks; recovery after process death; capture, tip adjust, reversal, receipt reprint and share, and transaction lookup; terminal and payment-configuration discovery; SANDBOX and PRODUCTION environments; device-serial resolution via the Flute Terminal app. |
