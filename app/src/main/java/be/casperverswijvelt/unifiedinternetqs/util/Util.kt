package be.casperverswijvelt.unifiedinternetqs.util

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.nfc.NfcManager
import android.provider.Settings
import android.service.quicksettings.TileService
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.util.Log
import androidx.preference.PreferenceManager
import be.casperverswijvelt.unifiedinternetqs.R
import be.casperverswijvelt.unifiedinternetqs.data.BITPreferences
import be.casperverswijvelt.unifiedinternetqs.data.ShellMethod
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Method
import java.util.UUID

const val TAG = "Util"

// Connectivity

fun getDataEnabled(context: Context): Boolean {

    var mobileDataEnabled = false

    (context.getSystemService(
        Context.CONNECTIVITY_SERVICE
    ) as? ConnectivityManager)?.let {
        // Get mobile data enabled state
        try {
            val cmClass = Class.forName(it.javaClass.name)
            val method: Method = cmClass.getDeclaredMethod("getMobileDataEnabled")
            method.isAccessible = true // Make the method callable
            // get the setting for "mobile data"
            mobileDataEnabled = method.invoke(it) as Boolean
        } catch (e: Exception) {
            // Empty
        }
    }
    return mobileDataEnabled
}

fun getWifiEnabled(context: Context): Boolean {
    val wm = (context.getSystemService(Context.WIFI_SERVICE) as? WifiManager) ?: return false
    return wm.isWifiEnabled || wm.wifiState == WifiManager.WIFI_STATE_ENABLING
}

fun getNFCEnabled(context: Context): Boolean {

    return (context.getSystemService(Context.NFC_SERVICE) as? NfcManager)
        ?.defaultAdapter?.isEnabled ?: false
}

fun getBluetoothEnabled(context: Context): Boolean {

    return (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
        ?.adapter?.isEnabled ?: false
}

fun getAirplaneModeEnabled(context: Context): Boolean {

    return Settings.Global.getInt(
        context.contentResolver,
        Settings.Global.AIRPLANE_MODE_ON,
        0
    ) != 0
}

fun checkIsWifiConnected(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val activeNet = cm.activeNetwork
    if (activeNet != null) {
        val caps = cm.getNetworkCapabilities(activeNet)
        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return true
        }
    }
    for (net in cm.allNetworks) {
        val caps = cm.getNetworkCapabilities(net)
        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return true
        }
    }
    return false
}

fun getConnectedWifiSSID(
    context: Context,
    callback: ((String?) -> Unit)
) {
    // 1. Try directly via ConnectivityManager / WifiInfo (instant, 0ms, zero overhead)
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    cm?.activeNetwork?.let { network ->
        val caps = cm.getNetworkCapabilities(network)
        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            val wifiInfo = caps.transportInfo as? WifiInfo
            val ssid = wifiInfo?.ssid?.trim('"')
            if (!ssid.isNullOrEmpty() && ssid != "<unknown ssid>") {
                callback(ssid)
                return
            }
        }
    }

    if (hasShellAccess(context)) {
        // 2. Try fast shell command `cmd wifi status` (~10ms, active connected SSID)
        executeShellCommandAsync("cmd wifi status", context) { result ->
            val out = result?.out?.joinToString("\n") ?: ""
            val connectedPattern = """Wifi is connected to "([^"]+)"""".toRegex()
            val match = connectedPattern.find(out)
            if (match != null && match.groupValues.size > 1) {
                val ssid = match.groupValues[1]
                if (ssid.isNotEmpty() && ssid != "<unknown ssid>") {
                    callback(ssid)
                    return@executeShellCommandAsync
                }
            }

            val infoPattern = """WifiInfo:.*?\bSSID:\s*"([^"]+)"""".toRegex()
            val infoMatch = infoPattern.find(out)
            if (infoMatch != null && infoMatch.groupValues.size > 1) {
                val ssid = infoMatch.groupValues[1]
                if (ssid.isNotEmpty() && ssid != "<unknown ssid>") {
                    callback(ssid)
                    return@executeShellCommandAsync
                }
            }

            // 3. Fallback: dumpsys wifi
            executeShellCommandAsync("dumpsys wifi | grep -i 'mWifiInfo SSID:' | head -n 1", context) { wifiResult ->
                val wifiOut = wifiResult?.out?.joinToString("\n") ?: ""
                val dumpMatch = """SSID:\s*"([^"]+)"""".toRegex().find(wifiOut)
                if (dumpMatch != null && dumpMatch.groupValues.size > 1) {
                    val ssid = dumpMatch.groupValues[1]
                    if (ssid.isNotEmpty() && ssid != "<unknown ssid>") {
                        callback(ssid)
                        return@executeShellCommandAsync
                    }
                }

                callback(null)
            }
        }
    } else {
        callback(null)
    }
}

fun getWifiIcon(context: Context): Int {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    val rssi = cm.activeNetwork?.let {
        (cm.getNetworkCapabilities(it)?.transportInfo as? WifiInfo)?.rssi
    }

    val signalStrength = rssi?.let {
        // We use 5 levels for our icon visualisation, so we use this deprecated
        //  calculation with 'numLevels' parameter. We don't want to use the system's
        //  level system since it might differ.
        WifiManager.calculateSignalLevel(it, 5) // 0-4
    } ?: 0

    return when (signalStrength) {
        4 -> R.drawable.ic_baseline_signal_wifi_4_bar_24
        3 -> R.drawable.ic_baseline_signal_wifi_3_bar_24
        2 -> R.drawable.ic_baseline_signal_wifi_2_bar_24
        1 -> R.drawable.ic_baseline_signal_wifi_1_bar_24
        else -> R.drawable.ic_baseline_signal_wifi_0_bar_24
    }
}

fun getCellularNetworkIcon(context: Context): Int {

    val tm =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    // TODO: We should try to get the signal strength of the data sim here.
    //  Only solution I found to do this requires fine location access, which I don't really want
    //  to add.

    return when (tm.signalStrength?.level ?: 0) {
        4 -> R.drawable.ic_baseline_signal_cellular_4_bar_24
        3 -> R.drawable.ic_baseline_signal_cellular_3_bar_24
        2 -> R.drawable.ic_baseline_signal_cellular_2_bar_24
        1 -> R.drawable.ic_baseline_signal_cellular_1_bar_24
        else -> R.drawable.ic_baseline_signal_cellular_0_bar
    }
}

fun getCellularNetworkText(
    context: Context,
    telephonyDisplayInfo: TelephonyDisplayInfo?
): String {

    val info = ArrayList<String>()
    val tm =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    val subscriptionInfo = getDataSubscriptionInfo(context)
    // No data sim set or no read phone state permission
        ?: return context.getString(R.string.network_not_available)

    subscriptionInfo.displayName?.let {
        info.add(it.toString())
    }

    // TODO: Use signal strength of data SIM
    if (tm.signalStrength?.level == 0) {

        // No service
        return context.getString(R.string.no_service)
    }

    var connType: String? = telephonyDisplayInfo?.let {
        when (telephonyDisplayInfo.overrideNetworkType) {
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> "4G+"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> "5Ge"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> "5G"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> "5G+"
            else -> null
        }
    }

    // Fallback
    if (
        connType == null &&
        context.checkSelfPermission(
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    ) {
        connType = getNetworkClassString(tm.dataNetworkType)
    }

    connType?.let { info.add(it) }

    return info.joinToString(separator = ", ")
}

fun getDataSubscriptionInfo(context: Context): SubscriptionInfo? {

    if (
        context.checkSelfPermission(
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    ) {
        val sm =
            context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as
                    SubscriptionManager
        val subId = SubscriptionManager.getActiveDataSubscriptionId()

        if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {

            return sm.getActiveSubscriptionInfo(subId)
        }
    }
    // No data sim set or no read phone state permission
    return null
}

private fun getNetworkClassString(networkType: Int): String? {

    return when (networkType) {
        TelephonyManager.NETWORK_TYPE_GSM,
        TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_1xRTT -> "2G"
        TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_EVDO_B,
        TelephonyManager.NETWORK_TYPE_EHRPD,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSPAP,
        TelephonyManager.NETWORK_TYPE_UMTS,
        TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "3G"
        TelephonyManager.NETWORK_TYPE_LTE,
        TelephonyManager.NETWORK_TYPE_IWLAN -> "4G"
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        else -> null
    }
}

// Shell access

data class AlertDialogData(
    val iconResource: Int? = null,
    val titleResource: Int,
    val messageResource: Int,
    val positiveButtonResource: Int,
    val onPositiveButtonClicked: () -> Unit = {}
)

fun AlertDialogData.toDialog(context: Context): Dialog {
    val dialog = AlertDialog.Builder(context)
        .setTitle(titleResource)
        .setMessage(messageResource)
        .setPositiveButton(positiveButtonResource) { _, _ ->
            onPositiveButtonClicked()
        }
        .setCancelable(true)
    iconResource?.let {
        dialog.setIcon(it)
    }
    return dialog.create()
}

fun executeShellCommand(command: String, context: Context): Shell.Result? {
    val shellMethod = BITPreferences(context).getShellMethodSync()

    when (shellMethod) {
        ShellMethod.ROOT -> {
            if (Shell.isAppGrantedRoot() == true) {
                return Shell.cmd(command).exec()
            }
        }
        ShellMethod.SHIZUKU -> {
            if (ShizukuUtil.hasShizukuPermission()) {
                return executeShizukuCommand(command)
            }
        }
        ShellMethod.AUTO -> {
            if (Shell.isAppGrantedRoot() == true) {
                return Shell.cmd(command).exec()
            } else if (ShizukuUtil.hasShizukuPermission()) {
                return executeShizukuCommand(command)
            }
        }
    }

    return null
}

private const val SHIZUKU_COMMAND_TIMEOUT_SECONDS = 10L

private fun executeShizukuCommand(command: String): Shell.Result? {
    return try {
        val process = ShizukuUtil.executeCommand(command) ?: return null

        // Drain stdout and stderr concurrently, otherwise the process can block on a full
        //  pipe buffer and never exit. The output is cached so Shell.Result can be read
        //  multiple times.
        val outLines = mutableListOf<String>()
        val errLines = mutableListOf<String>()
        val outThread = Thread {
            try {
                outLines.addAll(
                    process.inputStream.bufferedReader().use { it.readText() }.split("\n")
                )
            } catch (e: Exception) {
                // Ignore, partial output is returned
            }
        }
        val errThread = Thread {
            try {
                errLines.addAll(
                    process.errorStream.bufferedReader().use { it.readText() }.split("\n")
                )
            } catch (e: Exception) {
                // Ignore, partial output is returned
            }
        }
        outThread.start()
        errThread.start()

        val finished = process.waitFor(
            SHIZUKU_COMMAND_TIMEOUT_SECONDS,
            java.util.concurrent.TimeUnit.SECONDS
        )
        if (!finished) {
            Log.w(TAG, "Shizuku command timed out: $command")
            process.destroy()
        }
        outThread.join(1000)
        errThread.join(1000)

        val exitCode = if (finished) {
            try {
                process.exitValue()
            } catch (e: Exception) {
                -1
            }
        } else {
            -1
        }

        object : Shell.Result() {
            override fun getOut(): MutableList<String> = outLines

            override fun getErr(): MutableList<String> = errLines

            override fun getCode(): Int = exitCode
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to execute Shizuku command: $command", e)
        null
    }
}

fun executeShellCommandAsync(
    command: String,
    context: Context,
    callback: ((Shell.Result?) -> Unit)? = {}
) {
    ExecutorServiceSingleton.getInstance().execute {
        val result = executeShellCommand(command, context)
        callback?.let { it(result) }
    }
}

/**
 * Check if app has shell access (either root or shizuku).
 * If shell access is detected, the shizuku detection service is automatically stopped
 */
fun hasShellAccess(context: Context): Boolean {
    val shellMethod = BITPreferences(context).getShellMethodSync()

    return when (shellMethod) {
        ShellMethod.ROOT -> Shell.isAppGrantedRoot() == true
        ShellMethod.SHIZUKU -> ShizukuUtil.hasShizukuPermission()
        ShellMethod.AUTO -> {
            Shell.isAppGrantedRoot() == true || ShizukuUtil.hasShizukuPermission()
        }
    }
}

// Usage tracking

fun saveTileUsed(instance: TileService) {
    // TODO replace with datastore
    PreferenceManager.getDefaultSharedPreferences(instance)
        ?.edit()
        ?.putLong(instance.javaClass.name, System.currentTimeMillis())
        ?.apply()
}

/**
 * Analytics reporting is disabled in this build: no data is collected or sent anywhere.
 * The function is kept so existing call sites stay valid.
 */
@Suppress("UNUSED_PARAMETER")
fun reportToAnalytics(context: Context) {
    // Intentionally empty
}

fun getInstallId(context: Context): String = runBlocking {

    val preferences = BITPreferences(context)
    val existingId =  preferences.getInstallationId.firstOrNull()

    existingId ?: run {
        val uuid = UUID.randomUUID().toString()
        preferences.setInstallationId(uuid)
        uuid
    }
}
