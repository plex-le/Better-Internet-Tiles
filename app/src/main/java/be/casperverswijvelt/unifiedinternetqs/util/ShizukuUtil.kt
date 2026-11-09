package be.casperverswijvelt.unifiedinternetqs.util

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Some convenience functions for handling using Shizuku.
 */
object ShizukuUtil {
    /**
     * Checks if Shizuku is available. If the Shizuku Manager app
     * is either uninstalled OR isn't running, this will return
     * false.
     */
    val shizukuAvailable: Boolean
        get() = Shizuku.pingBinder() && (Shizuku.getVersion() >= 11 && !Shizuku.isPreV11())

    /**
     * Checks if the current app has permission to use Shizuku.
     */
    fun hasShizukuPermission(): Boolean {
        if (!shizukuAvailable) {
            return false
        }

        return (
                Shizuku.getVersion() >= 11 &&
                        !Shizuku.isPreV11() &&
                        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                )
    }

    /**
     * Request permission to use Shizuku if it's not already granted. This works
     * for all versions of the Shizuku API.
     *
     * @param callback invoked when the permission grant result is received.
     */
    private const val PERMISSION_REQUEST_CODE = 69101

    fun requestShizukuPermission(callback: (granted: Boolean) -> Unit) {
        if (Shizuku.pingBinder()) {
            Shizuku.addRequestPermissionResultListener(object :
                Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(
                    requestCode: Int,
                    grantResult: Int
                ) {
                    if (requestCode != PERMISSION_REQUEST_CODE) return
                    Shizuku.removeRequestPermissionResultListener(this)
                    callback(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            })
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } else {
            callback(false)
        }

    }

    /**
     * Starts a process through Shizuku. The caller is responsible for consuming the output
     * streams and waiting for the process, see [be.casperverswijvelt.unifiedinternetqs.util.executeShellCommand].
     */
    fun executeCommand(command: String): Process? {
        return try {
            val args = if (command.contains("|") || command.contains(">") || command.startsWith("sh -c")) {
                arrayOf("sh", "-c", command)
            } else {
                command.trim().split(Regex("\\s+")).toTypedArray()
            }
            Shizuku.newProcess(
                args,
                null,
                null
            )
        } catch (e: Throwable) {
            Log.e("ShizukuUtil", "Failed to execute Shizuku command: $command", e)
            null
        }
    }
}