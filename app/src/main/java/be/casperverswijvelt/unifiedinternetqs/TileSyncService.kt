package be.casperverswijvelt.unifiedinternetqs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Network
import android.nfc.NfcAdapter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.quicksettings.TileService
import android.telephony.ServiceState
import android.util.Log
import be.casperverswijvelt.unifiedinternetqs.listeners.CellularChangeListener
import be.casperverswijvelt.unifiedinternetqs.listeners.NetworkChangeType
import be.casperverswijvelt.unifiedinternetqs.listeners.WifiChangeListener
import be.casperverswijvelt.unifiedinternetqs.tilebehaviour.AirplaneModeTileBehaviour
import be.casperverswijvelt.unifiedinternetqs.tilebehaviour.BluetoothTileBehaviour
import be.casperverswijvelt.unifiedinternetqs.tilebehaviour.InternetTileBehaviour
import be.casperverswijvelt.unifiedinternetqs.tilebehaviour.MobileDataTileBehaviour
import be.casperverswijvelt.unifiedinternetqs.tilebehaviour.NFCTileBehaviour
import be.casperverswijvelt.unifiedinternetqs.tilebehaviour.TileBehaviour
import be.casperverswijvelt.unifiedinternetqs.tilebehaviour.WifiTileBehaviour
import be.casperverswijvelt.unifiedinternetqs.tiles.AirplaneModeTileService
import be.casperverswijvelt.unifiedinternetqs.tiles.BluetoothTileService
import be.casperverswijvelt.unifiedinternetqs.tiles.InternetTileService
import be.casperverswijvelt.unifiedinternetqs.tiles.MobileDataTileService
import be.casperverswijvelt.unifiedinternetqs.tiles.NFCTileService
import be.casperverswijvelt.unifiedinternetqs.tiles.WifiTileService
import be.casperverswijvelt.unifiedinternetqs.ui.MainActivity
import be.casperverswijvelt.unifiedinternetqs.util.checkIsWifiConnected
import be.casperverswijvelt.unifiedinternetqs.util.getConnectedWifiSSID
import be.casperverswijvelt.unifiedinternetqs.util.getWifiEnabled


const val ACT_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"

class TileSyncService: Service() {

    companion object {
        const val TAG = "TileSyncService"

        private var instance: TileSyncService? = null
        val isRunning
            get() = instance != null

        private var latestAvailableWifiNetwork: Network? = null
        var wifiConnected = false
        var wifiSSID: String? = null

        @Volatile var isTurningOnData = false
        @Volatile var isTurningOffData = false
        @Volatile var serviceState: ServiceState? = null

        private val handler = Handler(Looper.getMainLooper())
        private const val TRANSITION_TIMEOUT_MS = 6000L
        private val wifiLock = Any()

        private var wifiOnTimeoutRunnable: Runnable? = null
        private var wifiOffTimeoutRunnable: Runnable? = null

        @Volatile
        var isTurningOnWifi = false
            set(value) {
                synchronized(wifiLock) {
                    field = value
                    wifiOnTimeoutRunnable?.let { handler.removeCallbacks(it) }
                    wifiOnTimeoutRunnable = null
                    if (value) {
                        val runnable = Runnable {
                            synchronized(wifiLock) {
                                if (field) {
                                    Log.w(TAG, "isTurningOnWifi timed out (${TRANSITION_TIMEOUT_MS}ms), auto-resetting")
                                    field = false
                                    instance?.updateWifiTile()
                                    instance?.updateInternetTile()
                                }
                            }
                        }
                        wifiOnTimeoutRunnable = runnable
                        handler.postDelayed(runnable, TRANSITION_TIMEOUT_MS)
                    }
                }
            }

        @Volatile
        var isTurningOffWifi = false
            set(value) {
                synchronized(wifiLock) {
                    field = value
                    wifiOffTimeoutRunnable?.let { handler.removeCallbacks(it) }
                    wifiOffTimeoutRunnable = null
                    if (value) {
                        val runnable = Runnable {
                            synchronized(wifiLock) {
                                if (field) {
                                    Log.w(TAG, "isTurningOffWifi timed out (${TRANSITION_TIMEOUT_MS}ms), auto-resetting")
                                    field = false
                                    instance?.updateWifiTile()
                                    instance?.updateInternetTile()
                                }
                            }
                        }
                        wifiOffTimeoutRunnable = runnable
                        handler.postDelayed(runnable, TRANSITION_TIMEOUT_MS)
                    }
                }
            }

        @Volatile var isTurningOnNFC = false
        @Volatile var isTurningOffNFC = false

        @Volatile var isTurningOnAirplaneMode = false
        @Volatile var isTurningOffAirplaneMode = false

        @Volatile var isTurningOnBluetooth = false
        @Volatile var isTurningOffBluetooth = false

        val bluetoothConnectionState: MutableMap<String, Int> =
            java.util.concurrent.ConcurrentHashMap()
        val bluetoothBatteryLevel: MutableMap<String, Int> =
            java.util.concurrent.ConcurrentHashMap()

        @Volatile var bluetoothProfile: BluetoothProfile? = null

        private val behaviourListeners = arrayListOf<TileBehaviour>()

        // Behaviour classes whose Quick Settings tile is currently in listening state
        //  (separate from behaviourListeners, which also contains the in-app preview tiles)
        private val qsListeningBehaviours =
            java.util.Collections.synchronizedSet(mutableSetOf<Class<*>>())

        fun setQsListening(behaviourClass: Class<*>, listening: Boolean) {
            if (listening) {
                qsListeningBehaviours.add(behaviourClass)
            } else {
                qsListeningBehaviours.remove(behaviourClass)
            }
        }

        // SSID retrieval is expensive (may spawn shell commands), so it is throttled and
        //  never runs concurrently.
        private const val SSID_MIN_INTERVAL_MS = 3000L
        private val ssidFetchInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
        @Volatile private var lastSsidFetchTime = 0L

        fun fetchWifiSSID(
            context: Context,
            force: Boolean = false,
            onResult: (String?) -> Unit
        ) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (!force && now - lastSsidFetchTime < SSID_MIN_INTERVAL_MS) {
                onResult(wifiSSID)
                return
            }
            if (!ssidFetchInFlight.compareAndSet(false, true)) {
                onResult(wifiSSID)
                return
            }
            lastSsidFetchTime = now
            try {
                getConnectedWifiSSID(context) { ssid ->
                    ssidFetchInFlight.set(false)
                    onResult(ssid)
                }
            } catch (e: Throwable) {
                ssidFetchInFlight.set(false)
                Log.w(TAG, "Failed to fetch Wi-Fi SSID", e)
                onResult(null)
            }
        }

        fun addBehaviourListener(tileBehaviour: TileBehaviour) {
            synchronized(behaviourListeners) {
                if (!behaviourListeners.contains(tileBehaviour)) {
                    behaviourListeners.add(tileBehaviour)
                }
            }
        }
        fun removeBehaviourListener(tileBehaviour: TileBehaviour) {
            synchronized(behaviourListeners) {
                behaviourListeners.remove(tileBehaviour)
            }
        }

        fun refreshWifiStatus(context: Context, notifyTiles: Boolean = true) {
            val isEnabled = getWifiEnabled(context)
            val isConnected = isEnabled && checkIsWifiConnected(context)

            // Self-healing: if state contradicts turning on/off flags, clear them
            if (isEnabled && isTurningOnWifi) {
                isTurningOnWifi = false
            }
            if (!isEnabled && isTurningOffWifi) {
                isTurningOffWifi = false
            }

            wifiConnected = isConnected
            if (isEnabled && isConnected) {
                fetchWifiSSID(context, force = true) { ssid ->
                    wifiSSID = ssid
                    if (notifyTiles) {
                        instance?.updateWifiTile()
                        instance?.updateInternetTile()
                    } else {
                        instance?.requestTileBehaviourUpdate(WifiTileBehaviour::class.java)
                        instance?.requestTileBehaviourUpdate(InternetTileBehaviour::class.java)
                    }
                }
            } else {
                wifiSSID = null
                latestAvailableWifiNetwork = null
                if (notifyTiles) {
                    instance?.updateWifiTile()
                    instance?.updateInternetTile()
                } else {
                    instance?.requestTileBehaviourUpdate(WifiTileBehaviour::class.java)
                    instance?.requestTileBehaviourUpdate(InternetTileBehaviour::class.java)
                }
            }
        }
    }

    private val wifiChangeListener: WifiChangeListener = WifiChangeListener { type, network ->
        var forceSsid = false
        when(type) {
            NetworkChangeType.NETWORK_LOST -> {
                wifiConnected = checkIsWifiConnected(applicationContext)
                if (!wifiConnected) {
                    latestAvailableWifiNetwork = null
                    wifiSSID = null
                }
            }
            NetworkChangeType.NETWORK_AVAILABLE -> {
                // Only a different network (or a first connection) warrants a forced SSID lookup
                if (network != latestAvailableWifiNetwork) forceSsid = true
                latestAvailableWifiNetwork = network
                wifiConnected = true
                if (isTurningOnWifi) {
                    isTurningOnWifi = false
                }
            }
            NetworkChangeType.SIGNAL_STRENGTH -> {
                wifiConnected = checkIsWifiConnected(applicationContext)
                if (!wifiConnected) {
                    wifiSSID = null
                }
            }
            else -> {}
        }

        val setSSID: (ssid: String?) -> Unit = {
            wifiSSID = it
            updateWifiTile()
            updateInternetTile()
        }

        if (wifiConnected) {
            updateWifiTile()
            updateInternetTile()
            fetchWifiSSID(applicationContext, forceSsid) { setSSID(it) }
        } else {
            setSSID(null)
        }
    }
    private val cellularChangeListener: CellularChangeListener = CellularChangeListener { type, data ->
        when(type) {
            NetworkChangeType.SERVICE_STATE -> {
                (data?.firstOrNull() as? ServiceState)?.let {
                    serviceState = it
                }
            }
            else -> {}
        }
        updateMobileDataTile()
        updateInternetTile()
    }
    private val airplaneModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_AIRPLANE_MODE_CHANGED) {
                updateMobileDataTile()
                updateInternetTile()
                updateAirplaneModeTile()
            }
        }
    }
    private val nfcReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == NfcAdapter.ACTION_ADAPTER_STATE_CHANGED) {
                updateNFCTile()
            }
        }
    }
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                val device: BluetoothDevice? =
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        it.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        it.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                when (it.action) {
                    BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED -> {
                        val state = it.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE, -1)
                        device?.let { bluetoothConnectionState[device.address] = state }
                    }
                    ACT_BATTERY_LEVEL_CHANGED -> {
                        val batteryLevel = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
                        device?.let { bluetoothBatteryLevel[device.address] = batteryLevel }
                    }
                    else -> {}
                }
            }
            updateBluetoothTile()
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG,"onBind")
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG,"onStartCommand")
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification =
            Notification.Builder(this, TileApplication.CHANNEL_ID)
                .setContentTitle(resources.getString(R.string.hide_service_title))
                .setContentText(resources.getString(R.string.hide_service_description))
                .setSmallIcon(R.drawable.ic_baseline_public_24)
                .setContentIntent(pendingIntent)
                .build()
        val mNotificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            TileApplication.CHANNEL_ID,
            TileApplication.CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        )
        mNotificationManager.createNotificationChannel(channel)
        startForeground(1, notification)
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        instance = this

        // Wi-Fi
        wifiConnected = checkIsWifiConnected(applicationContext)
        wifiChangeListener.startListening(applicationContext)
        if (wifiConnected) {
            fetchWifiSSID(applicationContext, force = true) { ssid ->
                wifiSSID = ssid
                updateWifiTile()
                updateInternetTile()
            }
        } else {
            latestAvailableWifiNetwork = null
            wifiSSID = null
        }

        // Cellular
        cellularChangeListener.startListening(applicationContext)
        registerReceiver(
            airplaneModeReceiver,
            IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        )

        // NFC
        registerReceiver(
            nfcReceiver,
            IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)
        )

        // Bluetooth
        registerReceiver(
            bluetoothReceiver,
            IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
                addAction(ACT_BATTERY_LEVEL_CHANGED)
            }
        )
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.getProfileProxy(
            applicationContext,
            object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, bluetoothProfile: BluetoothProfile?) {
                    Log.d(TAG, "Bluetooth profile proxy connected")
                    TileSyncService.bluetoothProfile = bluetoothProfile
                    updateBluetoothTile()
                }

                override fun onServiceDisconnected(p0: Int) {
                    Log.d(TAG, "Bluetooth profile proxy disconnected")
                    bluetoothProfile = null
                    updateBluetoothTile()
                }
            },
            BluetoothProfile.HEADSET
        )

        updateAllTiles()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")

        instance = null

        isTurningOnWifi = false
        isTurningOffWifi = false

        wifiChangeListener.stopListening(applicationContext)
        cellularChangeListener.stopListening(applicationContext)
        unregisterReceiver(airplaneModeReceiver)
        unregisterReceiver(nfcReceiver)
        unregisterReceiver(bluetoothReceiver)

        bluetoothProfile?.let {
            try {
                (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                    ?.adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to close Bluetooth profile proxy", e)
            }
        }
        bluetoothProfile = null

        updateAllTiles()
    }

    private fun updateAllTiles() {
        updateWifiTile()
        updateMobileDataTile()
        updateInternetTile()
        updateNFCTile()
        updateAirplaneModeTile()
        updateBluetoothTile()
    }

    private fun isTileListening(cls: Class<out TileBehaviour>): Boolean {
        return qsListeningBehaviours.contains(cls)
    }

    private fun updateWifiTile() {
        if (!isTileListening(WifiTileBehaviour::class.java)) {
            requestListeningState(WifiTileService::class.java)
        }
        requestTileBehaviourUpdate(WifiTileBehaviour::class.java)
    }

    private fun updateMobileDataTile() {
        if (!isTileListening(MobileDataTileBehaviour::class.java)) {
            requestListeningState(MobileDataTileService::class.java)
        }
        requestTileBehaviourUpdate(MobileDataTileBehaviour::class.java)
    }

    private fun updateInternetTile () {
        if (!isTileListening(InternetTileBehaviour::class.java)) {
            requestListeningState(InternetTileService::class.java)
        }
        requestTileBehaviourUpdate(InternetTileBehaviour::class.java)
    }

    private fun updateNFCTile() {
        if (!isTileListening(NFCTileBehaviour::class.java)) {
            requestListeningState(NFCTileService::class.java)
        }
        requestTileBehaviourUpdate(NFCTileBehaviour::class.java)
    }

    private fun updateAirplaneModeTile() {
        if (!isTileListening(AirplaneModeTileBehaviour::class.java)) {
            requestListeningState(AirplaneModeTileService::class.java)
        }
        requestTileBehaviourUpdate(AirplaneModeTileBehaviour::class.java)
    }

    private fun updateBluetoothTile() {
        if (!isTileListening(BluetoothTileBehaviour::class.java)) {
            requestListeningState(BluetoothTileService::class.java)
        }
        requestTileBehaviourUpdate(BluetoothTileBehaviour::class.java)
    }

    private fun <T>requestListeningState(cls: Class<T>) {
        TileService.requestListeningState(
            applicationContext,
            ComponentName(applicationContext, cls)
        )
    }

    private fun <T>requestTileBehaviourUpdate(cls: Class<T>) {
        val listeners = synchronized(behaviourListeners) {
            ArrayList(behaviourListeners)
        }
        listeners.forEach {
            if (it.javaClass == cls) it.updateTile()
        }
    }
}