
package com.personal.captionbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {

    companion object {
        private const val CHANNEL_ID = "caption_capture"
        private const val NOTIFICATION_ID = 101

        private const val REFERENCE_WIDTH = 2400
        private const val REFERENCE_HEIGHT = 1000

        private val CAPTION_RECT = Rect(400, 100, 2000, 500)

        private const val OCR_INTERVAL_MS = 500L
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private var captureWidth = 0
    private var captureHeight = 0
    private var captureDensity = 0

    private var lastOcrAt = 0L

    private val processing = AtomicBoolean(false)

    private val recognizer = TextRecognition.getClient(
        TextRecognizerOptions.DEFAULT_OPTIONS
    )

    private val projectionCallback =
        object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }

    private val displayListener =
        object : DisplayManager.DisplayListener {

            override fun onDisplayAdded(displayId: Int) {}

            override fun onDisplayRemoved(displayId: Int) {}

            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) {
                    updateCaptureSurface()
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (projection != null) {
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra("resultCode", 0) ?: 0

        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("data")
        }

        if (resultCode == 0 || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification()
                )
            }

            val manager = getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

            projection = manager.getMediaProjection(resultCode, data)

            val currentProjection = projection ?: run {
                stopSelf()
                return START_NOT_STICKY
            }

            currentProjection.registerCallback(
                projectionCallback,
                Handler(mainLooper)
            )

            captureThread = HandlerThread("CaptionOCR").also {
                it.start()
            }

            captureHandler = Handler(captureThread!!.looper)

            val displayManager = getSystemService(
                Context.DISPLAY_SERVICE
            ) as DisplayManager

            displayManager.registerDisplayListener(
                displayListener,
                captureHandler
            )

            updateCaptureSurface()

        } catch (_: Exception) {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun updateCaptureSurface() {

        val windowManager = getSystemService(
            Context.WINDOW_SERVICE
        ) as WindowManager

        val metrics = DisplayMetrics()

        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        if (width <= 0 || height <= 0) return

        if (
            width == captureWidth &&
            height == captureHeight &&
            density == captureDensity
        ) {
            return
        }

        val handler = captureHandler ?: return

        val newReader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            2
        )

        newReader.setOnImageAvailableListener(
            { reader -> processFrame(reader) },
            handler
        )

        try {

            if (virtualDisplay == null) {

                virtualDisplay = projection?.createVirtualDisplay(
                    "CaptionBridgeCapture",
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    newReader.surface,
                    null,
                    handler
                )

                if (virtualDisplay == null) {
                    newReader.close()
                    stopSelf()
                    return
                }

            } else {

                virtualDisplay?.resize(
                    width,
                    height,
                    density
                )

                virtualDisplay?.setSurface(newReader.surface)
            }

            val oldReader = imageReader

            imageReader = newReader

            captureWidth = width
            captureHeight = height
            captureDensity = density

            oldReader?.setOnImageAvailableListener(null, null)
            oldReader?.close()

        } catch (_: Exception) {

            newReader.close()
            stopSelf()
        }
    }

    private fun processFrame(reader: ImageReader) {

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)

        if (
            !prefs.getBoolean("enabled", false) ||
            !prefs.getBoolean("youtube_fullscreen", false)
        ) {
            return
        }

        val now = SystemClock.elapsedRealtime()

        if (now - lastOcrAt < OCR_INTERVAL_MS) {
            return
        }

        if (!processing.compareAndSet(false, true)) {
            return
        }

        lastOcrAt = now

        var image: Image? = null
        var crop: Bitmap? = null

        try {

            image = reader.acquireLatestImage()

            if (image == null) {
                processing.set(false)
                return
            }

            val fullBitmap = imageToBitmap(image)

            image.close()
            image = null

            if (fullBitmap.width < fullBitmap.height) {
                fullBitmap.recycle()
                processing.set(false)
                return
            }

            val scaleX =
                fullBitmap.width.toFloat() / REFERENCE_WIDTH

            val scaleY =
                fullBitmap.height.toFloat() / REFERENCE_HEIGHT

            val left = (CAPTION_RECT.left * scaleX).toInt()
            val top = (CAPTION_RECT.top * scaleY).toInt()
            val right = (CAPTION_RECT.right * scaleX).toInt()
            val bottom = (CAPTION_RECT.bottom * scaleY).toInt()

            if (
                left < 0 ||
                top < 0 ||
                right > fullBitmap.width ||
                bottom > fullBitmap.height ||
                right <= left ||
                bottom <= top
            ) {
                fullBitmap.recycle()
                processing.set(false)
                return
            }

            val cropBitmap = Bitmap.createBitmap(
                fullBitmap,
                left,
                top,
                right - left,
                bottom - top
            )

            crop = cropBitmap
            fullBitmap.recycle()

            val input = InputImage.fromBitmap(cropBitmap, 0)

            recognizer.process(input)
                .addOnSuccessListener { result ->

                    val lines = result.textBlocks
                        .flatMap { block -> block.lines }
                        .sortedWith(
                            compareBy(
                                { it.boundingBox?.top ?: 0 },
                                { it.boundingBox?.left ?: 0 }
                            )
                        )
                        .map { it.text.trim() }
                        .filter { it.length >= 2 }

                    if (lines.isNotEmpty()) {
                        CaptionBuffer.appendLines(this, lines)
                    }
                }
                .addOnCompleteListener {

                    crop?.recycle()
                    crop = null

                    processing.set(false)
                }

        } catch (_: Exception) {

            image?.close()
            crop?.recycle()

            processing.set(false)
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {

        val plane = image.planes[0]
        val buffer = plane.buffer

        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride

        val rowPadding =
            rowStride - pixelStride * image.width

        val paddedWidth =
            image.width + rowPadding / pixelStride

        val paddedBitmap = Bitmap.createBitmap(
            paddedWidth,
            image.height,
            Bitmap.Config.ARGB_8888
        )

        buffer.rewind()
        paddedBitmap.copyPixelsFromBuffer(buffer)

        val result = Bitmap.createBitmap(
            paddedBitmap,
            0,
            0,
            image.width,
            image.height
        )

        paddedBitmap.recycle()

        return result
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= 26) {

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Caption screen capture",
                NotificationManager.IMPORTANCE_LOW
            )

            val manager = getSystemService(
                NotificationManager::class.java
            )

            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {

        val builder =
            if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }

        return builder
            .setContentTitle("Caption Bridge")
            .setContentText("Screen capture is active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {

        val displayManager = getSystemService(
            Context.DISPLAY_SERVICE
        ) as DisplayManager

        try {
            displayManager.unregisterDisplayListener(displayListener)
        } catch (_: Exception) {}

        virtualDisplay?.setSurface(null)
        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null

        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        projection = null

        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null

        recognizer.close()

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
