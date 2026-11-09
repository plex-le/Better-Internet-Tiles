package be.casperverswijvelt.unifiedinternetqs.tiles

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import be.casperverswijvelt.unifiedinternetqs.util.getDataEnabled
import be.casperverswijvelt.unifiedinternetqs.util.getWifiEnabled


class LongPressReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val qsTile: ComponentName? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME)
        }

        val intent = Intent(
            // TODO: use 'onLongClickIntentAction' property on TileBehaviour
            when (qsTile?.className) {
                InternetTileService::class.java.name -> {
                    when {
                        getDataEnabled(applicationContext) -> {
                            Settings.ACTION_NETWORK_OPERATOR_SETTINGS
                        }
                        getWifiEnabled(applicationContext) -> {
                            Settings.ACTION_WIFI_SETTINGS
                        }
                        else -> {
                            Settings.ACTION_WIRELESS_SETTINGS
                        }
                    }
                }
                MobileDataTileService::class.java.name -> {
                    Settings.ACTION_NETWORK_OPERATOR_SETTINGS
                }
                WifiTileService::class.java.name -> {
                    Settings.ACTION_WIFI_SETTINGS
                }
                NFCTileService::class.java.name -> {
                    Settings.ACTION_NFC_SETTINGS
                }
                AirplaneModeTileService::class.java.name -> {
                    Settings.ACTION_AIRPLANE_MODE_SETTINGS
                }
                BluetoothTileService::class.java.name -> {
                    Settings.ACTION_BLUETOOTH_SETTINGS
                }
                else -> Settings.ACTION_WIRELESS_SETTINGS
            }
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        startActivity(intent)
        finish()
    }
}