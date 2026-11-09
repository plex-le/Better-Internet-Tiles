package be.casperverswijvelt.unifiedinternetqs.listeners

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.util.Log

class CellularChangeListener(private val callback: (type: NetworkChangeType?, args: List<Any?>?) -> Unit) {

    companion object {
        private const val TAG = "CellularChangeListener"
        @Volatile
        var currentTelephonyDisplayInfo: TelephonyDisplayInfo? = null
    }

    private var isListening = false
    private var telephonyCallbackRegistered = false

    private val mobileNetworkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                callback(NetworkChangeType.NETWORK_AVAILABLE, null)
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                callback(NetworkChangeType.NETWORK_LOST, null)
            }
        }

    private val telephonyCallback = object : TelephonyCallback(),
        TelephonyCallback.SignalStrengthsListener,
        TelephonyCallback.DisplayInfoListener,
        TelephonyCallback.ServiceStateListener {
        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
            callback(NetworkChangeType.SIGNAL_STRENGTH, null)
        }

        override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
            currentTelephonyDisplayInfo = telephonyDisplayInfo
            callback(NetworkChangeType.DISPLAY_INFO, null)
        }

        override fun onServiceStateChanged(serviceState: ServiceState) {
            callback(NetworkChangeType.SERVICE_STATE, listOf(serviceState))
        }
    }

    fun startListening(context: Context) {

        if (isListening) return

        // Cellular network state
        val cm =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()
        cm.registerNetworkCallback(networkRequest, mobileNetworkCallback)

        // Mobile signal strength listener. Registering this callback requires the
        //  READ_PHONE_STATE permission, so it must not take the whole service down when
        //  the permission has not been granted.
        try {
            val tm =
                context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            tm.registerTelephonyCallback(
                context.mainExecutor,
                telephonyCallback
            )
            telephonyCallbackRegistered = true
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot register telephony callback, missing permission", e)
        }

        isListening = true
    }

    fun stopListening(context: Context) {

        if (isListening) {

            isListening = false

            // Cellular network state
            val cm =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(mobileNetworkCallback)

            // Mobile signal strength listener
            if (telephonyCallbackRegistered) {
                val tm =
                    context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                tm.unregisterTelephonyCallback(telephonyCallback)
                telephonyCallbackRegistered = false
            }

            currentTelephonyDisplayInfo = null
        }
    }
}