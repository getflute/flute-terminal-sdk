# Consumer ProGuard rules shipped to apps that depend on this SDK.
# Gson DTOs are (de)serialized reflectively — keep their fields.
-keep class com.flute.terminal.sdk.data.remote.dto.** { *; }

# PaymentConfig is persisted to the encrypted store as Gson JSON and read back on a later launch,
# so its field names are a storage format, not just an in-memory detail. They carry no
# @SerializedName, so R8 is free to rename them — consistently within one build, but not across an
# app update. Gson does not throw on keys it cannot match, it leaves the fields null, so a config
# cached by the previous build comes back with a null currency and the next payment fails with
# "Merchant has no configured currency" instead of cleanly re-fetching.
#
# Scoped to the whole package rather than the one class: everything here is public API an
# integrator already holds, and a future model that gets persisted inherits the protection instead
# of reintroducing the bug.
-keep class com.flute.terminal.sdk.model.** { *; }
