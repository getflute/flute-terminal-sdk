package com.flute.terminal.sdk.device

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import com.flute.terminal.deeplink.FluteDeeplinkContract

/**
 * Reads the device's hardware serial number — the key the backend uses to identify a terminal
 * (`GET /v2/terminals?serialNumber=`).
 *
 * Platform reality: only API ≤ 25 (the older Sunmi fleet) lets an ordinary app read the serial. On
 * 26–28 `Build.getSerial()` needs READ_PHONE_STATE; on 29+ it needs a privileged permission and
 * `android.os.SystemProperties` is off the non-SDK-interface allowlist, so both paths return
 * nothing — which is every Verifone terminal, and the newer Sunmi ones.
 *
 * So the Flute Terminal app is asked first. It reads the serial through the vendor SDK, which is
 * privileged, and publishes it on a read-only provider ([FluteDeeplinkContract.terminalInfoUri]).
 * That value is also authoritative: it is what the app registered the terminal with, so it wins
 * over anything scraped from the platform. The platform chain stays as the fallback for a device
 * whose terminal app predates the provider.
 */
internal object DeviceSerialResolver {

    private val SYSTEM_PROPERTY_KEYS = listOf(
        "gsm.sn1",
        "ril.serialnumber",
        "ro.serialno",
        "sys.serialnumber",
        "ro.boot.serialno",
        "ro.kernel.androidboot.serialno",
    )

    /** The device serial, or null when neither the terminal app nor the platform will reveal one. */
    fun read(context: Context): String? = candidates(
        terminalApp = terminalAppSerial(context),
        platform = SYSTEM_PROPERTY_KEYS.map { systemProperty(it) } + buildSerial(),
    )

    /** Pure selection logic (unit-testable): the terminal app's answer first, then the platform. */
    fun candidates(terminalApp: String?, platform: List<String?>): String? =
        firstUsable(listOf(terminalApp) + platform)

    /** Pure selection logic (unit-testable): first candidate that looks like a real serial. */
    fun firstUsable(candidates: List<String?>): String? =
        candidates.firstOrNull { isUsable(it) }?.replace("-", "")

    private fun isUsable(value: String?): Boolean =
        !value.isNullOrBlank() &&
            !value.equals("unknown", ignoreCase = true) &&
            !value.startsWith("EMULATOR", ignoreCase = true)

    /**
     * Queries the terminal app's info provider. Returns null for every failure mode — app absent,
     * provider absent (an older terminal app), or the query rejected — because each one means the
     * same thing here: fall through to the platform.
     */
    private fun terminalAppSerial(context: Context): String? = try {
        context.contentResolver.query(
            FluteDeeplinkContract.terminalInfoUri(),
            arrayOf(FluteDeeplinkContract.COLUMN_SERIAL_NUMBER),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (t: Throwable) {
        null
    }

    private fun systemProperty(key: String): String? = try {
        val clazz = Class.forName("android.os.SystemProperties")
        clazz.getMethod("get", String::class.java).invoke(clazz, key) as? String
    } catch (t: Throwable) {
        null
    }

    // Lint is right that READ_PRIVILEGED_PHONE_STATE is missing, and it always will be: an SDK
    // shipped to ISVs cannot hold a privileged permission. The call is attempted anyway because it
    // succeeds on the API 26–28 fleet, and the catch below is the handling for everywhere else.
    @SuppressLint("MissingPermission", "HardwareIds")
    private fun buildSerial(): String? = try {
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Build.getSerial() else Build.SERIAL
    } catch (t: Throwable) {
        // SecurityException on 26+ without READ_PHONE_STATE, or 29+ for non-privileged apps.
        null
    }
}
