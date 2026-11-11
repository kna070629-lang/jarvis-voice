package dev.jarvis.companion

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Takes a screenshot when the user asks for one.
 *
 * Android asks the user for permission before the display can be captured, and
 * that permission is requested through the system dialog. Nothing is captured
 * without it, and no screen is captured in the background.
 */
class ScreenshotManager(private val context: Context) {

    /** The result of a capture. */
    data class Capture(
        val ok: Boolean,
        val message: String,
        val path: String = "",
        val width: Int = 0,
        val height: Int = 0,
    )

    /** One screenshot that was taken. */
    data class Shot(
        val path: String,
        val takenAt: Long,
        val width: Int,
        val height: Int,
    ) {
        fun file(): File = File(path)
    }

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var pending: ((Capture) -> Unit)? = null

    /** The folder the screenshots are kept in. */
    private val folder: File
        get() = File(context.filesDir, "screenshots").also { it.mkdirs() }

    /** True when the display capture permission has been granted. */
    val isGranted: Boolean get() = projection != null

    /**
     * Asks the system for the capture permission.
     *
     * The user has to accept it, so the caller is told when it was granted.
     */
    fun requestPermission(activity: Activity, onResult: (Boolean) -> Unit) {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            onResult(false)
            return
        }
        try {
            activity.startActivityForResult(
                manager.createScreenCaptureIntent(),
                REQUEST_CODE,
            )
            pendingPermission = onResult
        } catch (_: Exception) {
            onResult(false)
        }
    }

    private var pendingPermission: ((Boolean) -> Unit)? = null

    /** Called with the result of the permission request. */
    fun onPermissionResult(resultCode: Int, data: Intent?): Boolean {
        val callback = pendingPermission
        pendingPermission = null
        if (resultCode != Activity.RESULT_OK || data == null) {
            callback?.invoke(false)
            return false
        }
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        val granted = try {
            manager?.getMediaProjection(resultCode, data)
        } catch (_: Exception) {
            null
        }
        if (granted == null) {
            callback?.invoke(false)
            return false
        }
        projection = granted
        granted.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stop()
            }
        }, handler ?: Handler(android.os.Looper.getMainLooper()))
        callback?.invoke(true)
        return true
    }

    /**
     * Takes a screenshot and saves it.
     *
     * The picture is taken from the virtual display that the projection
     * created, so it is exactly what the user can see.
     */
    fun capture(onResult: (Capture) -> Unit) {
        val active = projection
        if (active == null) {
            onResult(Capture(false, "The screen capture permission has not been given yet."))
            return
        }
        try {
            val metrics = context.resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            val worker = HandlerThread("jarvis-screenshot").also { it.start() }
            thread = worker
            val workerHandler = Handler(worker.looper)
            handler = workerHandler

            val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader = imageReader
            imageReader.setOnImageAvailableListener({ source ->
                val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val width = image.width
                    val height = image.height
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val pixels = IntArray(width * height)
                    val row = ByteArray(rowStride)
                    buffer.rewind()
                    for (y in 0 until height) {
                        val length = minOf(rowStride, buffer.remaining())
                        if (length <= 0) break
                        buffer.get(row, 0, length)
                        var offset = 0
                        for (x in 0 until width) {
                            if (offset + 3 >= length) break
                            pixels[y * width + x] = (row[offset + 2].toInt() and 0xFF shl 16) or
                                (row[offset + 1].toInt() and 0xFF shl 8) or
                                (row[offset].toInt() and 0xFF)
                            offset += pixelStride
                        }
                    }
                    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

                    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                    val file = File(folder, "screen-$stamp.png")
                    FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    onResult(
                        Capture(
                            true,
                            "Taken a screenshot.",
                            file.absolutePath,
                            width,
                            height,
                        ),
                    )
                } catch (_: Exception) {
                    onResult(Capture(false, "The screenshot could not be saved."))
                } finally {
                    image.close()
                }
            }, workerHandler)

            val virtual = active.createVirtualDisplay(
                "jarvis-screenshot",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                workerHandler,
            )
            display = virtual
        } catch (_: Exception) {
            onResult(Capture(false, "The screenshot could not be taken."))
        }
    }

    /** The screenshots that were taken, newest first. */
    fun history(limit: Int = 20): List<Shot> {
        val files = folder.listFiles { file -> file.name.startsWith("screen-") } ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }.take(limit).map {
            Shot(it.absolutePath, it.lastModified(), 0, 0)
        }
    }

    /** Removes the screenshots that were taken. */
    fun clearHistory(): Int {
        val files = folder.listFiles { file -> file.name.startsWith("screen-") } ?: return 0
        val count = files.size
        files.forEach { it.delete() }
        return count
    }

    /** Releases the capture. */
    fun stop() {
        try {
            display?.release()
        } catch (_: Exception) {
        }
        display = null
        try {
            reader?.close()
        } catch (_: Exception) {
        }
        reader = null
        try {
            thread?.quitSafely()
        } catch (_: Exception) {
        }
        thread = null
        handler = null
        projection = null
    }

    companion object {
        const val REQUEST_CODE = 4401
    }
}
