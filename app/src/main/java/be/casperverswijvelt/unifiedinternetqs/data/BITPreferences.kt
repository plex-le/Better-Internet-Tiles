package be.casperverswijvelt.unifiedinternetqs.data

import android.content.Context
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import be.casperverswijvelt.unifiedinternetqs.BuildConfig
import be.casperverswijvelt.unifiedinternetqs.R
import be.casperverswijvelt.unifiedinternetqs.settings.TileChoiceOption
import be.casperverswijvelt.unifiedinternetqs.settings.settings.FollowOption
import be.casperverswijvelt.unifiedinternetqs.tilebehaviour.TileType
import be.casperverswijvelt.unifiedinternetqs.util.AlertDialogData
import be.casperverswijvelt.unifiedinternetqs.util.ShizukuUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import com.topjohnwu.superuser.Shell

class BITPreferences(context: Context) {

    private val appContext: Context = context.applicationContext ?: context

    companion object {
        private const val PREFERENCES_NAME = "preferences"
        private val Context.dataStore by preferencesDataStore(
            name = PREFERENCES_NAME,
            produceMigrations = {
                listOf(SharedPreferencesMigration(
                    it,
                    "${BuildConfig.APPLICATION_ID}_preferences"
                ))
            }
        )

        @Volatile
        private var cachedPreferences: Preferences? = null
        private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        @Volatile
        private var collectorStarted = false

        private fun ensureCollector(appContext: Context) {
            if (!collectorStarted) {
                synchronized(this) {
                    if (!collectorStarted) {
                        collectorStarted = true
                        cacheScope.launch {
                            appContext.dataStore.data.collect { prefs ->
                                cachedPreferences = prefs
                            }
                        }
                    }
                }
            }
        }

        // All caps because legacy
        private val KEY_INSTALLATION_ID = stringPreferencesKey(
            "INSTALLATION_ID"
        )

        private val KEY_REQUIRE_UNLOCK = booleanPreferencesKey("require_unlock")
        private val KEY_SHELL_METHOD = stringPreferencesKey("shell_method")

        private val KEY_HIDE_WIFI_SSID = booleanPreferencesKey("hide_ssid")
        private val KEY_TASKER_GUIDE = booleanPreferencesKey("tasker_guide")
    }

    init {
        ensureCollector(appContext)
    }

    private fun currentPreferences(): Preferences {
        return cachedPreferences ?: runBlocking {
            appContext.dataStore.data.first().also { cachedPreferences = it }
        }
    }

    fun loadPreferences() {
        currentPreferences()
    }

    // Require unlock

    val getRequireUnlock: Flow<Boolean> = appContext
        .dataStore.data.map {
            it[KEY_REQUIRE_UNLOCK] ?: true
        }

    fun getRequireUnlockSync(): Boolean {
        return currentPreferences()[KEY_REQUIRE_UNLOCK] ?: true
    }

    suspend fun setRequireUnlock(requireUnlock: Boolean) {
        cachedPreferences = appContext.dataStore.edit {
            it[KEY_REQUIRE_UNLOCK] = requireUnlock
        }
    }

    // Installation ID

    val getInstallationId: Flow<String?> = appContext
        .dataStore.data.map {
            it[KEY_INSTALLATION_ID]
        }

    suspend fun setInstallationId(id: String) {
        cachedPreferences = appContext.dataStore.edit {
            it[KEY_INSTALLATION_ID] = id
        }
    }

    // Shell method

    val getShellMethod: Flow<ShellMethod> = appContext
        .dataStore.data.map {
            it[KEY_SHELL_METHOD]?.let {methodString ->
                ShellMethod.getByValue(methodString)
            } ?: ShellMethod.AUTO
        }

    fun getShellMethodSync(): ShellMethod {
        return currentPreferences()[KEY_SHELL_METHOD]?.let { methodString ->
            ShellMethod.getByValue(methodString)
        } ?: ShellMethod.AUTO
    }

    suspend fun setShellMethod(shellMethod: ShellMethod) {
        cachedPreferences = appContext.dataStore.edit {
            it[KEY_SHELL_METHOD] = shellMethod.method
        }
    }

    // SSID method

    val getHideWiFiSSID: Flow<Boolean> = appContext
        .dataStore.data.map {
            it[KEY_HIDE_WIFI_SSID]?: false
        }

    fun getHideWiFiSSIDSync(): Boolean {
        return currentPreferences()[KEY_HIDE_WIFI_SSID] ?: false
    }

    suspend fun getHideWiFiSSID(value: Boolean) {
        cachedPreferences = appContext.dataStore.edit {
            it[KEY_HIDE_WIFI_SSID] = value
        }
    }

    // Tasker guide

    val getTaskerGuide: Flow<Boolean> = appContext
        .dataStore.data.map {
            it[KEY_TASKER_GUIDE] ?: true
        }

    fun getTaskerGuideSync(): Boolean {
        return currentPreferences()[KEY_TASKER_GUIDE] ?: true
    }

    suspend fun setTaskerGuide(value: Boolean) {
        cachedPreferences = appContext.dataStore.edit {
            it[KEY_TASKER_GUIDE] = value
        }
    }

    // Require unlock (tile specific)

    private fun getRequireUnlockKey(tileType: TileType): Preferences.Key<String> {
        return stringPreferencesKey("require_unlock/${tileType.value}")
    }

    suspend fun setRequireUnlock(tileType: TileType, setting: FollowOption) {
        cachedPreferences = appContext.dataStore.edit {
            it[getRequireUnlockKey(tileType)] = setting.value
        }
    }

    fun getRequireUnlock(tileType: TileType): Flow<FollowOption> {
        return appContext.dataStore.data.map {
            it[getRequireUnlockKey(tileType)]?.let { value ->
                TileChoiceOption.getByValue(value)
            } ?: FollowOption.FOLLOW
        }
    }

    fun getRequireUnlockSync(tileType: TileType): FollowOption {
        return currentPreferences()[getRequireUnlockKey(tileType)]?.let { value ->
            TileChoiceOption.getByValue(value)
        } ?: FollowOption.FOLLOW
    }
}
enum class ShellMethod(
    val method: String,
    val nameResource: Int,
    val descriptionResource: Int? = null,
    val isGranted: () -> Boolean = { false },
    val alertDialog: AlertDialogData? = null
) {
    ROOT(
        method = "root",
        nameResource = R.string.root,
        descriptionResource = R.string.root_description,
        isGranted = { Shell.isAppGrantedRoot() == true },
        alertDialog = AlertDialogData(
            iconResource = R.drawable.baseline_block_24,
            titleResource = R.string.allow_root_access,
            messageResource = R.string.allow_root_access_description,
            positiveButtonResource = R.string.ok
        )
    ),
    SHIZUKU(
        method = "shizuku",
        nameResource = R.string.shizuku,
        descriptionResource = R.string.shizuku_description,
        isGranted = { ShizukuUtil.hasShizukuPermission() },
        alertDialog = AlertDialogData(
            iconResource = R.drawable.baseline_block_24,
            titleResource = R.string.allow_shizuku_access,
            messageResource = R.string.allow_shizuku_access_description,
            positiveButtonResource = R.string.ok
        )
    ),
    AUTO(
        method = "auto",
        nameResource = R.string.auto,
        isGranted = {
            Shell.isAppGrantedRoot() == true ||
            ShizukuUtil.hasShizukuPermission()
        }
    );
    companion object {
        infix fun getByValue(value: String): ShellMethod {
            return ShellMethod.values().firstOrNull { it.method == value } ?: AUTO
        }
    }
}
