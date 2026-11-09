package be.casperverswijvelt.unifiedinternetqs.tilebehaviour

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import be.casperverswijvelt.unifiedinternetqs.R
import be.casperverswijvelt.unifiedinternetqs.TileSyncService
import be.casperverswijvelt.unifiedinternetqs.data.BITPreferences
import be.casperverswijvelt.unifiedinternetqs.settings.settings.FollowOption
import be.casperverswijvelt.unifiedinternetqs.settings.ISetting
import be.casperverswijvelt.unifiedinternetqs.settings.settings.requireUnlockSetting
import be.casperverswijvelt.unifiedinternetqs.tiles.LongPressReceiverActivity
import be.casperverswijvelt.unifiedinternetqs.ui.MainActivity
import be.casperverswijvelt.unifiedinternetqs.util.AlertDialogData
import be.casperverswijvelt.unifiedinternetqs.util.hasShellAccess
import kotlinx.coroutines.Runnable

abstract class TileBehaviour(
    protected val context: Context,
    protected val showDialog: (AlertDialogData) -> Unit,
    protected val unlockAndRun: (Runnable) -> Unit = { it.run() }
) {

    protected val preferences = BITPreferences(context)
    protected val resources: Resources = context.resources

    private val updateTileListeners =
        java.util.concurrent.CopyOnWriteArrayList<(TileState) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    abstract val type: TileType
    abstract val tileName: String
    abstract val defaultIcon: Icon
    abstract val tileServiceClass: Class<TileService>
    protected abstract val tileState: TileState
    abstract val onLongClickIntentAction: String

    open val behaviourSettings: Array<ISetting<*>> = arrayOf(requireUnlockSetting)
    open val lookSettings: Array<ISetting<*>> = arrayOf()

    val finalTileState: TileState
        get() = tileState

    abstract fun onClick()

    companion object {
        private const val TAG = "TileBehaviour"
        private const val PERMISSION_NOTIFICATION_CHANNEL_ID = "permission_reminder_channel"
        private const val PERMISSION_NOTIFICATION_ID = 1001
    }

    protected fun checkShellAccess(): Boolean {
        if (!hasShellAccess(context)) {
            openSettingsPage()
            showPermissionReminderNotification()
            return false
        }
        return true
    }

    protected fun openSettingsPage() {
        try {
            val componentName = ComponentName(context, tileServiceClass)
            val intent = Intent(context, LongPressReceiverActivity::class.java).apply {
                action = TileService.ACTION_QS_TILE_PREFERENCES
                putExtra(Intent.EXTRA_COMPONENT_NAME, componentName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
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
            Log.e(TAG, "Failed to start settings via LongPressReceiverActivity", e)
        }
    }

    protected fun showPermissionReminderNotification() {
        try {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            val channel = NotificationChannel(
                PERMISSION_NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.permission_reminder_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.permission_reminder_channel_description)
            }
            notificationManager.createNotificationChannel(channel)

            val appIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                appIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val notification = Notification.Builder(context, PERMISSION_NOTIFICATION_CHANNEL_ID)
                .setContentTitle(context.getString(R.string.shizuku_permission_required_title))
                .setContentText(context.getString(R.string.shizuku_permission_required_desc))
                .setSmallIcon(R.drawable.ic_baseline_public_24)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(PERMISSION_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post permission reminder notification", e)
        }
    }

    val requiresUnlock: Boolean
        get() = when (preferences.getRequireUnlockSync(type)) {
            FollowOption.FOLLOW -> preferences.getRequireUnlockSync()
            FollowOption.YES -> true
            FollowOption.NO -> false
        }

    fun updateTile() {
        // Tile state is read and applied on the main thread: updates can be triggered from
        //  shell executor threads and network callback threads.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dispatchTileUpdate()
        } else {
            mainHandler.post { dispatchTileUpdate() }
        }
    }

    private fun dispatchTileUpdate() {
        val state = finalTileState
        updateTileListeners.forEach { it(state) }
    }

    fun addUpdateTileListeners(listener: (TileState) -> Unit) {
        updateTileListeners.add(listener)
    }

    fun removeUpdateTileListeners(listener: (TileState) -> Unit) {
        updateTileListeners.remove(listener)
    }
}