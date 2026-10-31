package com.myphoneremote

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import android.util.Log
import org.webrtc.*

class ScreenShareService : Service() {

    companion object {
        const val CHANNEL_ID = "screen_share_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.myphoneremote.START_SCREEN_SHARE"
        const val ACTION_STOP = "com.myphoneremote.STOP_SCREEN_SHARE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val TAG = "ScreenShareService"
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var eglBase: EglBase? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "Screen sharing stopped by Android")
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

        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
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

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)

        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(
                EXTRA_RESULT_DATA,
                Intent::class.java
            )
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        }

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startCaptureNotification()
            startWebRtcCapture(resultData)
        } catch (e: Exception) {
            Log.e(TAG, "WebRTC screen capture failed", e)
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

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("My Phone Remote")
            .setContentText("Screen sharing is active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
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
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startWebRtcCapture(resultData: Intent) {

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(applicationContext)
                .createInitializationOptions()
        )

        eglBase = EglBase.create()

        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase!!.eglBaseContext,
            true,
            true
        )

        val decoderFactory = DefaultVideoDecoderFactory(
            eglBase!!.eglBaseContext
        )

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        screenCapturer = ScreenCapturerAndroid(
            resultData,
            projectionCallback
        )

        videoSource = peerConnectionFactory!!
            .createVideoSource(true)

        surfaceTextureHelper = SurfaceTextureHelper.create(
            "ScreenCaptureThread",
            eglBase!!.eglBaseContext
        )

        screenCapturer!!.initialize(
            surfaceTextureHelper,
            applicationContext,
            videoSource!!.capturerObserver
        )

        videoTrack = peerConnectionFactory!!
            .createVideoTrack(
                "SCREEN_VIDEO_TRACK",
                videoSource
            )

        screenCapturer!!.startCapture(
            720,
            1280,
            15
        )

        Log.d(TAG, "WebRTC screen capture started")
    }

    private fun stopWebRtcCapture() {

        try {
            screenCapturer?.stopCapture()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping capture", e)
        }

        screenCapturer?.dispose()
        screenCapturer = null

        videoTrack?.dispose()
        videoTrack = null

        videoSource?.dispose()
        videoSource = null

        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null

        peerConnectionFactory?.dispose()
        peerConnectionFactory = null

        eglBase?.release()
        eglBase = null
    }

    override fun onDestroy() {
        stopWebRtcCapture()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
