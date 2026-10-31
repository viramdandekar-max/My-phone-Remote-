package com.myphoneremote

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log

class ScreenShareService : Service() {

    companion object {
        const val CHANNEL_ID = "screen_share_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START =
            "com.myphoneremote.START_SCREEN_SHARE"

        const val ACTION_STOP =
            "com.myphoneremote.STOP_SCREEN_SHARE"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val TAG = "ScreenShareService"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private var lastFrameTime = 0L
    private var isCapturing = false

    private val projectionCallback =
        object : MediaProjection.Callback() {

            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped")
                stopSelf()
            }
        }

    override fun onCreate() {
        super.onCreate()

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen Sharing",
            NotificationManager.IMPORTANCE_LOW
        )

        getSystemService(
            NotificationManager::class.java
        ).createNotificationChannel(channel)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(
            EXTRA_RESULT_CODE,
            0
        )

        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(
                EXTRA_RESULT_DATA,
                Intent::class.java
            )
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>(
                EXTRA_RESULT_DATA
            )
        }

        if (
            resultCode != Activity.RESULT_OK ||
            resultData == null
        ) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startCaptureNotification()
            startScreenCapture(resultCode, resultData)
        } catch (e: Exception) {
            Log.e(TAG, "Screen capture failed", e)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startCaptureNotification() {

        val stopIntent = Intent(
            this,
            ScreenShareService::class.java
        ).apply {
            action = ACTION_STOP
        }

        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(
            this,
            CHANNEL_ID
        )
            .setContentTitle("My Phone Remote")
            .setContentText("Screen capture is active")
            .setSmallIcon(
                android.R.drawable.ic_menu_camera
            )
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "STOP",
                    stopPendingIntent
                ).build()
            )
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo
                    .FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }
    }

    private fun startScreenCapture(
        resultCode: Int,
        resultData: Intent
    ) {

        val projectionManager = getSystemService(
            MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        val projection = projectionManager.getMediaProjection(
            resultCode,
            resultData
        )

        mediaProjection = projection

        captureThread = HandlerThread(
            "ScreenCaptureThread"
        ).apply {
            start()
        }

        captureHandler = Handler(
            captureThread!!.looper
        )

        projection.registerCallback(
            projectionCallback,
            captureHandler
        )

        val metrics = resources.displayMetrics

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            2
        )

        virtualDisplay = projection.createVirtualDisplay(
            "MyPhoneRemoteCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            captureHandler
        )

        isCapturing = true

        imageReader?.setOnImageAvailableListener(
            { reader ->

                val image = try {
                    reader.acquireLatestImage()
                } catch (e: Exception) {
                    null
                }

                if (image != null) {
                    try {
                        val now = System.currentTimeMillis()

                        if (now - lastFrameTime >= 500) {
                            lastFrameTime = now

                            Log.d(
                                TAG,
                                "Screen frame captured: " +
                                    "${image.width}x${image.height}"
                            )
                        }
                    } finally {
                        image.close()
                    }
                }
            },
            captureHandler
        )

        Log.d(TAG, "Screen capture started")
    }

    private fun stopScreenCapture() {

        if (!isCapturing && mediaProjection == null) {
            return
        }

        isCapturing = false

        imageReader?.setOnImageAvailableListener(
            null,
            null
        )

        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        val projection = mediaProjection
        mediaProjection = null

        if (projection != null) {
            projection.unregisterCallback(
                projectionCallback
            )
            projection.stop()
        }

        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null

        Log.d(TAG, "Screen capture stopped")
    }

    override fun onDestroy() {
        stopScreenCapture()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
