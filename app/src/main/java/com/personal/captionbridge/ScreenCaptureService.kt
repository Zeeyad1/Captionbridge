package com.personal.captionbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
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
import android.util.DisplayMetrics
import android.view.WindowManager
import android.content.pm.ServiceInfo
import android.graphics.Rect
import java.util.concurrent.atomic.AtomicBoolean
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class ScreenCaptureService : Service() {

    companion object {
        private const val CHANNEL_ID = "caption_capture"
        private const val NOTIFICATION_ID = 101

        private const val REFERENCE_WIDTH = 2400
        private const val REFERENCE_HEIGHT = 1000

        private val CAPTION_RECT = Rect(400, 100, 2000, 500)
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private val recognizer = TextRecognition.getClient(
        TextRecognizerOptions.DEFAULT_OPTIONS
    )

    private val processing = AtomicBoolean(false)

    private val projectionCallback =
        object : MediaProjection.Callback() {

            override fun onStop() {
                stopSelf()
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

        val resultCode = intent?.getIntExtra(
            "resultCode",
            0
        ) ?: 0

        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(
                "data",
                Intent::class.java
            )
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

            projection = manager.getMediaProjection(
                resultCode,
                data
            )

            val currentProjection = projection

            if (currentProjection == null) {
                stopSelf()
                return START_NOT_STICKY
            }

            currentProjection.registerCallback(
                projectionCallback,
                Handler(mainLooper)
            )

            startCapture(currentProjection)

        } catch (_: Exception) {

            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startCapture(
        mediaProjection: MediaProjection
    ) {

        val windowManager =
            getSystemService(Context.WINDOW_SERVICE)
                as WindowManager

        val metrics = DisplayMetrics()

        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        if (width <= 0 || height <= 0) {
            stopSelf()
            return
        }

        imageReader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            2
        )

        captureThread = HandlerThread(
            "CaptionOCR"
        ).also {
            it.start()
        }

        captureHandler = Handler(
            captureThread!!.looper
        )

        imageReader?.setOnImageAvailableListener(
            { reader -> processFrame(reader) },
            captureHandler
        )

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "CaptionBridgeCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            captureHandler
        )
    }

    private fun processFrame(reader: ImageReader) {

        val prefs = getSharedPreferences(
            "settings",
            MODE_PRIVATE
        )

        if (
            !prefs.getBoolean("enabled", false) ||
            !prefs.getBoolean("youtube_fullscreen", false)
        ) {
            return
        }

        if (!processing.compareAndSet(false, true)) {
            return
        }

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

            crop = Bitmap.createBitmap(
                fullBitmap,
                left,
                top,
                right - left,
                bottom - top
            )

            fullBitmap.recycle()

            val input = InputImage.fromBitmap(
                crop,
                0
            )

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
                        CaptionBuffer.appendLines(
                            this,
                            lines
                        )
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

        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.setOnImageAvailableListener(
            null,
            null
        )

        imageReader?.close()
        imageReader = null

        projection?.unregisterCallback(
            projectionCallback
        )

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
