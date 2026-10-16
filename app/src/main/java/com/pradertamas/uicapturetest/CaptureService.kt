package com.pradertamas.uicapturetest

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.WindowManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class CaptureUi(
    val status: String = "Idle", val busy: Boolean = false,
    val capturing: Boolean = false, val frames: Long = 0, val size: String = "-",
    val saving: Boolean = false, val path: String = "", val saveError: String = "",
    val visibility: String = "Unknown", val diagnostic: String = "",
    val metrics: CaptureMetricsSnapshot = CaptureMetricsSnapshot()
)

object CaptureState {
    private val mutable = MutableStateFlow(CaptureUi())
    val state = mutable.asStateFlow()
    fun set(value: CaptureUi) { mutable.value = value }
}

class CaptureService : Service() {
    companion object {
        const val START = "capture.START"
        const val SAVE = "capture.SAVE"
        const val SAVE_DELAYED = "capture.SAVE_DELAYED"
        const val CONSENT = "capture.CONSENT"
        private const val CHANNEL = "capture"
        private const val TAG = "CaptureDiag"
    }

    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    // Worker-owned current image is closed on replacement, resize, or shutdown.
    // Three slots leave two free for acquireLatestImage while retaining one for a snapshot.
    private var latest: Image? = null
    private var metrics: CaptureMetrics? = null
    private val frames: Long get() = metrics?.frames ?: 0L
    private var lastSize = "-"
    private var lastWidth = 0
    private var lastHeight = 0
    private var previousSample = IntArray(16 * 16)
    private var currentSample = IntArray(16 * 16)
    private var hasPreviousSample = false
    private val publishMetrics = object : Runnable {
        override fun run() {
            if (closing) return
            publishSnapshot(SystemClock.elapsedRealtimeNanos())
            handler.postDelayed(this, 1000)
        }
    }
    private var closing = false
    private var contentVisible: Boolean? = null
    private var latestVisible: Boolean? = null
    private var latestReceivedAt = 0L
    private var savePending = false
    private val callback = object : MediaProjection.Callback() {
        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            if (closing) return
            metrics?.visibility(SystemClock.elapsedRealtimeNanos(), isVisible)
            contentVisible = isVisible
            Log.i(TAG, "VISIBILITY visible=$isVisible frames=$frames")
            CaptureState.set(CaptureState.state.value.copy(visibility = isVisible.toString()))
        }
        override fun onStop() { finishCapture("Stopped by system") }
        override fun onCapturedContentResize(width: Int, height: Int) {
            Log.i(TAG, "RESIZE ${width}x$height")
            if (closing || width <= 0 || height <= 0) return
            if (reader?.width == width && reader?.height == height) return
            try {
                val replacement = newReader(width, height)
                val previous = reader
                reader = replacement
                latest?.close()
                latest = null
                hasPreviousSample = false
                try {
                    display?.resize(width, height, resources.configuration.densityDpi)
                    display?.surface = replacement.surface
                } finally { previous?.close() }
            } catch (e: Exception) { finishCapture("Resize error: ${e.message}") }
        }
    }

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("CaptureFrames").apply { start() }
        handler = Handler(worker.looper)
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> {
                val consent = intent.getParcelableExtra(CONSENT, Intent::class.java)
                if (consent == null) { stopSelf(); return START_NOT_STICKY }
                try {
                    getSystemService(NotificationManager::class.java).createNotificationChannel(
                        NotificationChannel(CHANNEL, "Screen capture", NotificationManager.IMPORTANCE_LOW))
                    val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    val notification = Notification.Builder(this, CHANNEL)
                        .setSmallIcon(android.R.drawable.ic_menu_camera)
                        .setContentTitle("MediaProjection Capture Test")
                        .setContentText("Capture active — open app to save or stop")
                        .setContentIntent(open).setOngoing(true).build()
                    // Fresh user consent precedes FGS, which precedes getMediaProjection.
                    startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                    handler.post {
                        if (projection != null || closing) return@post
                        try {
                            projection = getSystemService(MediaProjectionManager::class.java)
                                .getMediaProjection(Activity.RESULT_OK, consent)
                            projection!!.registerCallback(callback, handler)
                            val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
                            reader = newReader(bounds.width(), bounds.height())
                            metrics = CaptureMetrics(SystemClock.elapsedRealtimeNanos())
                            display = projection!!.createVirtualDisplay("CapturePoC", bounds.width(),
                                bounds.height(), resources.configuration.densityDpi,
                                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler)
                            CaptureState.set(CaptureUi(status = "Capturing", busy = true, capturing = true))
                            handler.post(publishMetrics)
                        } catch (e: Exception) { finishCapture("Capture error: ${e.message}") }
                    }
                } catch (e: Exception) {
                    handler.post { finishCapture("Start error: ${e.message}") }
                }
            }
            SAVE, SAVE_DELAYED -> handler.post {
                if (!closing && !savePending && latest != null) {
                    savePending = true
                    val delay = if (intent.action == SAVE_DELAYED) 5000L else 0L
                    CaptureState.set(CaptureState.state.value.copy(saving = true, saveError = "",
                        diagnostic = if (delay > 0) "Save in 5 seconds — switch to shared app" else "Saving"))
                    handler.postDelayed({
                        if (!closing) saveSample()
                        savePending = false
                    }, delay)
                }
            }
            else -> stopSelf()
        }
        // Never restart with an old consent token.
        return START_NOT_STICKY
    }

    private fun newReader(width: Int, height: Int): ImageReader =
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ source ->
                if (closing || source !== reader) return@setOnImageAvailableListener
                val processingStartNs = SystemClock.elapsedRealtimeNanos()
                try {
                    val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val arrivalNs = SystemClock.elapsedRealtimeNanos()
                    latest?.close()
                    latest = image
                    latestVisible = contentVisible
                    latestReceivedAt = SystemClock.elapsedRealtime()
                    val crop = image.cropRect
                    if (crop.width() != lastWidth || crop.height() != lastHeight) {
                        lastWidth = crop.width()
                        lastHeight = crop.height()
                        lastSize = "$lastWidth × $lastHeight"
                    }
                    metrics?.frame(arrivalNs, changedSample(image))
                    metrics?.processing(SystemClock.elapsedRealtimeNanos() - processingStartNs)
                } catch (e: Exception) { finishCapture("Frame error: ${e.message}") }
            }, handler)
        }

    // Exact RGB comparison at 256 grid-cell centers; no full-frame copy or Bitmap.
    // Absolute buffer reads preserve position for the existing PNG conversion.
    private fun changedSample(image: Image): Boolean? {
        check(image.format == PixelFormat.RGBA_8888 && image.planes.size == 1)
        val crop = image.cropRect
        check(crop.width() > 0 && crop.height() > 0)
        val plane = image.planes[0]
        check(plane.pixelStride >= 4)
        val buffer = plane.buffer
        val base = buffer.position()
        var differs = false
        for (gy in 0 until 16) {
            val y = crop.top + ((2L * gy + 1) * crop.height() / 32).toInt()
            for (gx in 0 until 16) {
                val x = crop.left + ((2L * gx + 1) * crop.width() / 32).toInt()
                val offset = base + y * plane.rowStride + x * plane.pixelStride
                val rgb = ((buffer.get(offset).toInt() and 255) shl 16) or
                    ((buffer.get(offset + 1).toInt() and 255) shl 8) or
                    (buffer.get(offset + 2).toInt() and 255)
                val index = gy * 16 + gx
                currentSample[index] = rgb
                if (rgb != previousSample[index]) differs = true
            }
        }
        val result = if (hasPreviousSample) differs else null
        val temporary = previousSample
        previousSample = currentSample
        currentSample = temporary
        hasPreviousSample = true
        return result
    }

    private fun publishSnapshot(nowNs: Long) {
        val snapshot = metrics?.snapshot(nowNs) ?: CaptureMetricsSnapshot()
        CaptureState.set(CaptureState.state.value.copy(frames = snapshot.frames,
            size = lastSize, metrics = snapshot))
    }

    private fun saveSample() {
        val image = latest ?: run {
            CaptureState.set(CaptureState.state.value.copy(saving = false,
                saveError = "No current frame (possibly resized); retry saving"))
            return
        }
        CaptureState.set(CaptureState.state.value.copy(saving = true, saveError = ""))
        metrics?.let { it.sampleSaves++ }
        var bitmap: Bitmap? = null
        var file: File? = null
        try {
            val crop = image.cropRect
            Log.i(TAG, "IMAGE frame=$frames timestampNs=${image.timestamp} " +
                "ageMs=${SystemClock.elapsedRealtime() - latestReceivedAt} " +
                "visibleAtAcquire=$latestVisible visibleNow=$contentVisible " +
                "format=${image.format} size=${image.width}x${image.height} crop=$crop planes=${image.planes.size}")
            image.planes.forEachIndexed { index, p ->
                val b = p.buffer
                Log.i(TAG, "PLANE[$index] pixelStride=${p.pixelStride} rowStride=${p.rowStride} " +
                    "rowPadding=${p.rowStride - image.width * p.pixelStride} " +
                    "position=${b.position()} limit=${b.limit()} remaining=${b.remaining()} capacity=${b.capacity()}")
            }
            check(image.format == PixelFormat.RGBA_8888 && image.planes.size == 1) {
                "Expected one RGBA_8888 plane; see CaptureDiag"
            }
            val plane = image.planes[0]
            check(plane.pixelStride >= 4) { "Unexpected pixelStride=${plane.pixelStride}" }
            val buffer = plane.buffer.duplicate()
            val base = buffer.position()
            val row = IntArray(crop.width())
            val output = Bitmap.createBitmap(crop.width(), crop.height(), Bitmap.Config.ARGB_8888)
            bitmap = output
            var nonBlack = 0L
            var nonZeroAlpha = 0L
            var opaque = 0L
            var nonZeroBytes = 0L
            var bitmapMismatches = 0L
            var bitmapNonBlack = 0L
            var maxRgb = 0
            val actualRow = IntArray(row.size)
            // Read cropped RGBA pixels using both strides, excluding all row padding.
            for (y in 0 until crop.height()) {
                for (x in row.indices) {
                    val offset = base + (y + crop.top) * plane.rowStride + (x + crop.left) * plane.pixelStride
                    val r = buffer.get(offset).toInt() and 255
                    val g = buffer.get(offset + 1).toInt() and 255
                    val b = buffer.get(offset + 2).toInt() and 255
                    val a = buffer.get(offset + 3).toInt() and 255
                    if (r != 0 || g != 0 || b != 0) nonBlack++
                    if (a != 0) nonZeroAlpha++
                    if (a == 255) opaque++
                    if (r != 0) nonZeroBytes++
                    if (g != 0) nonZeroBytes++
                    if (b != 0) nonZeroBytes++
                    if (a != 0) nonZeroBytes++
                    maxRgb = maxOf(maxRgb, r, g, b)
                    row[x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
                output.setPixels(row, 0, row.size, 0, y, row.size, 1)
                output.getPixels(actualRow, 0, row.size, 0, y, row.size, 1)
                for (x in row.indices) {
                    if (actualRow[x] != row[x]) bitmapMismatches++
                    if ((actualRow[x] and 0x00ffffff) != 0) bitmapNonBlack++
                }
                if (y == 0 || y == crop.height() / 2 || y == crop.height() - 1) {
                    for (x in intArrayOf(0, row.size / 2, row.size - 1)) {
                        Log.i(TAG, "PIXEL ($x,$y) rawARGB=%08X bitmapARGB=%08X".format(row[x], actualRow[x]))
                    }
                }
            }
            val pixels = crop.width().toLong() * crop.height()
            Log.i(TAG, "RAW pixels=$pixels nonBlackRGB=$nonBlack nonZeroAlpha=$nonZeroAlpha " +
                "opaque=$opaque nonZeroRGBABytes=$nonZeroBytes maxRGB=$maxRgb (crop only, padding excluded)")
            Log.i(TAG, "BITMAP rawArgbMismatches=$bitmapMismatches/$pixels " +
                "nonBlackRGB=$bitmapNonBlack " +
                "config=${output.config} hasAlpha=${output.hasAlpha()} premultiplied=${output.isPremultiplied} " +
                "(nonopaque pixels can differ due to premultiplication)")
            val directory = File(filesDir, "captures").apply { check(isDirectory || mkdirs()) }
            file = File.createTempFile("sample-", ".png", directory)
            file.outputStream().use { check(output.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val decoded = checkNotNull(BitmapFactory.decodeFile(file.absolutePath)) { "PNG decode failed" }
            val pngMatches = try {
                val matches = output.sameAs(decoded)
                Log.i(TAG, "PNG path=${file.absolutePath} bytes=${file.length()} " +
                    "decoded=${decoded.width}x${decoded.height} sameAsBitmap=$matches " +
                    "centerARGB=%08X".format(decoded.getPixel(decoded.width / 2, decoded.height / 2)))
                matches
            } finally { decoded.recycle() }
            CaptureState.set(CaptureState.state.value.copy(path = file.absolutePath, saving = false,
                diagnostic = "Raw nonblack: $nonBlack/$pixels; ARGB differences: $bitmapMismatches; PNG matches: $pngMatches"))
        } catch (e: Exception) {
            Log.e(TAG, "SAMPLE failed", e)
            file?.delete()
            CaptureState.set(CaptureState.state.value.copy(saving = false, saveError = "Save error: ${e.message}"))
        } finally { bitmap?.recycle() }
    }

    private fun finishCapture(status: String) {
        if (closing) return
        closing = true
        handler.removeCallbacks(publishMetrics)
        publishSnapshot(SystemClock.elapsedRealtimeNanos())
        display?.release()
        display = null
        latest?.close()
        latest = null
        reader?.close()
        reader = null
        projection?.unregisterCallback(callback)
        projection?.stop()
        projection = null
        CaptureState.set(CaptureState.state.value.copy(status = status, busy = true,
            capturing = false, saving = false, frames = frames))
        stopSelf()
    }

    override fun onDestroy() {
        handler.post {
            finishCapture("Stopped")
            // Only permit a new session after this service has been destroyed.
            CaptureState.set(CaptureState.state.value.copy(busy = false))
            worker.quitSafely()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
