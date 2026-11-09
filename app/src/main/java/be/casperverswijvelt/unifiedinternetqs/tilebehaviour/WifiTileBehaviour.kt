package be.casperverswijvelt.unifiedinternetqs.tilebehaviour

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import be.casperverswijvelt.unifiedinternetqs.R
import be.casperverswijvelt.unifiedinternetqs.TileSyncService
import be.casperverswijvelt.unifiedinternetqs.settings.ISetting
import be.casperverswijvelt.unifiedinternetqs.settings.settings.taskerGuideSetting
import be.casperverswijvelt.unifiedinternetqs.settings.settings.wifiSSIDVisibilityOption
import be.casperverswijvelt.unifiedinternetqs.tiles.WifiTileService
import be.casperverswijvelt.unifiedinternetqs.util.AlertDialogData
import be.casperverswijvelt.unifiedinternetqs.util.executeShellCommandAsync
import be.casperverswijvelt.unifiedinternetqs.util.getWifiEnabled
import be.casperverswijvelt.unifiedinternetqs.util.getWifiIcon
import be.casperverswijvelt.unifiedinternetqs.util.hasShellAccess
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.Random

class WifiTileBehaviour(
    context: Context,
    showDialog: (AlertDialogData) -> Unit = {},
    unlockAndRun: (Runnable) -> Unit = { it.run() }
) : TileBehaviour(context, showDialog, unlockAndRun) {

    companion object {
        private const val TAG = "WifiTileBehaviour"
        private const val TASKER_PACKAGE_MARKET = "net.dinglisch.android.taskerm"
        private const val TASKER_PACKAGE_DIRECT = "net.dinglisch.android.tasker"
        private const val TASKER_ACTION_TASK = "net.dinglisch.android.tasker.ACTION_TASK"
        private const val TASKER_ACTION_OPEN_PREFS = "net.dinglisch.android.tasker.ACTION_OPEN_PREFS"
        private const val TASKER_PREFS_URI = "content://net.dinglisch.android.tasker/prefs"
        private const val ACTION_CODE_WIFI_STATUS = 425
        private const val TASKER_WIFI_TASK_NAME = "BIT_ToggleWifi"
        private const val TASKER_WIFI_TOKEN = "BIT_WIFI_2026"
    }

    override val type: TileType
        get() = TileType.WiFi
    override val tileName: String
        get() = resources.getString(R.string.wifi)
    override val defaultIcon: Icon
        get() = Icon.createWithResource(
            context,
            R.drawable.ic_baseline_signal_wifi_3_bar_24
        )

    @Suppress("UNCHECKED_CAST")
    override val tileServiceClass: Class<TileService>
        get() = WifiTileService::class.java as Class<TileService>

    override val tileState: TileState
        get() {
            val tile = TileState()
            val wifiEnabled = getWifiEnabled(context)

            if ((wifiEnabled && !TileSyncService.isTurningOffWifi) || TileSyncService.isTurningOnWifi) {

                if (wifiEnabled) {
                    TileSyncService.isTurningOnWifi = false
                    TileSyncService.isTurningOffWifi = false
                }

                val showSSID = runBlocking {
                    !preferences.getHideWiFiSSID.first()
                } && TileSyncService.wifiSSID?.isNotEmpty() == true

                tile.label = when {
                    TileSyncService.wifiConnected && showSSID -> TileSyncService.wifiSSID
                    else -> null
                } ?: resources.getString(R.string.wifi)
                tile.state = Tile.STATE_ACTIVE
                tile.icon = when {
                    TileSyncService.wifiConnected -> getWifiIcon(context)
                    else -> R.drawable.ic_baseline_signal_wifi_0_bar_24
                }
                tile.subtitle = when {
                    TileSyncService.isTurningOnWifi -> resources.getString(R.string.turning_on)
                    TileSyncService.wifiConnected && !showSSID -> resources.getString(R.string.connected)
                    else -> resources.getString(R.string.on)
                }

            } else {

                if (!wifiEnabled) {
                    TileSyncService.isTurningOffWifi = false
                    TileSyncService.wifiConnected = false
                    TileSyncService.wifiSSID = null
                }

                tile.label = resources.getString(R.string.wifi)
                tile.state = Tile.STATE_INACTIVE
                tile.icon = R.drawable.ic_baseline_signal_wifi_0_bar_24
                tile.subtitle = resources.getString(R.string.off)
            }
            return tile
        }
    override val onLongClickIntentAction: String
        get() = Settings.ACTION_WIFI_SETTINGS

    override val behaviourSettings: Array<ISetting<*>>
        get() = arrayOf(
            *super.behaviourSettings,
            taskerGuideSetting
        )

    override val lookSettings: Array<ISetting<*>>
        get() = arrayOf(
            *super.lookSettings,
            wifiSSIDVisibilityOption
        )

    override fun onClick() {
        log("onClick")

        if (!hasShellAccess(context)) {
            val taskerPackage = getInstalledTaskerPackage(context)
            Log.d(TAG, "onClick: hasShellAccess=false, taskerPackage=$taskerPackage")
            if (taskerPackage != null) {
                val extAccess = isTaskerExternalAccessEnabled(context)
                Log.d(TAG, "onClick: Tasker external access enabled=$extAccess")
                if (extAccess) {
                    if (requiresUnlock) {
                        unlockAndRun { toggleWifiViaTasker(taskerPackage) }
                    } else {
                        toggleWifiViaTasker(taskerPackage)
                    }
                    return
                } else {
                    val shouldGuide = runBlocking { preferences.getTaskerGuide.first() }
                    Log.d(TAG, "onClick: shouldGuide=$shouldGuide")
                    if (shouldGuide) {
                        openTaskerSettingsPage()
                    } else {
                        openSettingsPage()
                        showPermissionReminderNotification()
                    }
                    return
                }
            } else {
                Log.d(TAG, "onClick: Tasker not found, fallback to settings")
                openSettingsPage()
                showPermissionReminderNotification()
                return
            }
        }

        if (requiresUnlock) {
            unlockAndRun { toggleWifi() }
        } else {
            toggleWifi()
        }
    }

    private fun getInstalledTaskerPackage(context: Context): String? {
        val packages = arrayOf(TASKER_PACKAGE_MARKET, TASKER_PACKAGE_DIRECT)
        for (pkg in packages) {
            try {
                context.packageManager.getPackageInfo(pkg, 0)
                return pkg
            } catch (e: Exception) {
                // Ignore and try next
            }
        }
        return null
    }

    private fun isTaskerExternalAccessEnabled(context: Context): Boolean {
        return try {
            val uri = Uri.parse(TASKER_PREFS_URI)
            val cursor = context.contentResolver.query(uri, arrayOf("ext_access"), null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val colIdx = it.getColumnIndex("ext_access")
                    val value = if (colIdx != -1) it.getString(colIdx) else it.getString(0)
                    Log.d(TAG, "isTaskerExternalAccessEnabled value=$value")
                    return value.equals("true", ignoreCase = true)
                }
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query Tasker external access status", e)
            false
        }
    }

    private fun openTaskerSettingsPage() {
        try {
            val intent = Intent(TASKER_ACTION_OPEN_PREFS).apply {
                putExtra("tno", 3) // 3 = Misc tab
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            if (context is TileService) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val pendingIntent = PendingIntent.getActivity(
                        context,
                        0,
                        intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    context.startActivityAndCollapse(pendingIntent)
                } else {
                    @Suppress("DEPRECATION")
                    context.startActivityAndCollapse(intent)
                }
            } else {
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Tasker preferences page", e)
            openSettingsPage()
            showPermissionReminderNotification()
        }
    }

    private fun toggleWifiViaTasker(taskerPackage: String) {
        try {
            val wifiEnabled = getWifiEnabled(context)
            val targetState = !(wifiEnabled || TileSyncService.isTurningOnWifi)

            if (wifiEnabled || TileSyncService.isTurningOnWifi) {
                TileSyncService.isTurningOnWifi = false
                TileSyncService.isTurningOffWifi = true
            } else {
                TileSyncService.isTurningOnWifi = true
                TileSyncService.isTurningOffWifi = false
            }
            updateTile()

            val randomId = Random().nextLong()
            val intent = Intent(TASKER_ACTION_TASK).apply {
                setPackage(taskerPackage)
                data = Uri.parse("id:$randomId")
                putExtra("version_number", "1.1")
                putExtra("task_name", TASKER_WIFI_TASK_NAME)
                putExtra("task_priority", 9)

                // Dynamically constructed action: ActionCodes.WIFI_STATUS (425) with arg:1 = Boolean (target state)
                val actionBundle = Bundle().apply {
                    putInt("action", ACTION_CODE_WIFI_STATUS)
                    putBoolean("arg:1", targetState)
                }
                putExtra("action1", actionBundle)

                // Also attach variables and password in case user has pre-imported task
                putStringArrayListExtra("varNames", arrayListOf("%par1"))
                putStringArrayListExtra("varValues", arrayListOf(TASKER_WIFI_TOKEN))
                putExtra("task_pass_var", TASKER_WIFI_TOKEN)
                putExtra("par1", TASKER_WIFI_TOKEN)
            }
            Log.i(TAG, "toggleWifiViaTasker: targetState=$targetState, sending broadcast to $taskerPackage")
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to invoke Tasker to toggle WiFi", e)
            TileSyncService.isTurningOnWifi = false
            TileSyncService.isTurningOffWifi = false
            updateTile()
            openSettingsPage()
            showPermissionReminderNotification()
        }
    }

    private fun toggleWifi() {

        val wifiEnabled = getWifiEnabled(context)

        if (wifiEnabled || TileSyncService.isTurningOnWifi) {
            TileSyncService.isTurningOnWifi = false
            TileSyncService.isTurningOffWifi = true
            executeShellCommandAsync("svc wifi disable", context) {
                TileSyncService.isTurningOffWifi = false
                TileSyncService.isTurningOnWifi = false
                TileSyncService.refreshWifiStatus(context)
                updateTile()
            }
        } else {
            TileSyncService.isTurningOnWifi = true
            TileSyncService.isTurningOffWifi = false
            executeShellCommandAsync("svc wifi enable", context) {
                TileSyncService.isTurningOffWifi = false
                TileSyncService.isTurningOnWifi = false
                TileSyncService.refreshWifiStatus(context)
                updateTile()
            }
        }
        updateTile()
    }

    private fun log(text: String) {
        Log.d(TAG, text)
    }
}