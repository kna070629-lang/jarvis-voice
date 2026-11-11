package dev.jarvis.companion

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Opens an application and reports honestly what happened.
 *
 * The launcher tries the launch intent of the application first, then the
 * applications own entry point, and only then the integration of that
 * application. It never reports that an application opened when nothing was
 * actually started.
 */
class AppLauncher(private val context: Context) {

    /** The outcome of a launch. */
    data class Result(
        val ok: Boolean,
        val message: String,
        val packageName: String = "",
        val openedWith: String = "",
    )

    /**
     * Opens an application by its package.
     *
     * @return the outcome, which says which way it was opened.
     */
    fun launch(packageName: String, label: String = ""): Result {
        val manager = context.packageManager
        val launchIntent = try {
            manager.getLaunchIntentForPackage(packageName)
        } catch (_: Exception) {
            null
        }
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (start(launchIntent)) {
                return Result(true, "Opened ${label.ifEmpty { packageName }}.", packageName, "launch intent")
            }
        }

        // Some applications have an entry point that the launcher does not
        // expose, so the package's own main activity is tried.
        val own = ownMainIntent(packageName)
        if (own != null && start(own)) {
            return Result(true, "Opened ${label.ifEmpty { packageName }}.", packageName, "application entry point")
        }

        return Result(
            false,
            "${label.ifEmpty { packageName }} could not be opened from here.",
            packageName,
        )
    }

    /** The intent of an application that declares its own entry point. */
    private fun ownMainIntent(packageName: String): Intent? {
        val manager = context.packageManager
        return try {
            val info = manager.getApplicationInfo(packageName, 0)
            val className = info.className ?: return null
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(packageName, className)
        } catch (_: Exception) {
            null
        }
    }

    /** True when the application is installed. */
    fun isInstalled(packageName: String): Boolean = try {
        context.packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (_: Exception) {
        false
    }

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: android.content.ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    } catch (_: Exception) {
        false
    }
}
