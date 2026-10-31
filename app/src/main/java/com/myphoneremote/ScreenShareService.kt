package com.myphoneremote

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
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
        const val EXTRA_PAIRING_CODE = "pairing_code"

        private const val TAG = "ScreenShareService"
    }

    private var webRtcManager: WebRtcManager? = null

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

        val pairingCode = intent.getStringExtra(
            EXTRA_PAIRING_CODE
        )

        if (
            resultCode != Activity.RESULT_OK ||
            resultData == null ||
            pairingCode.isNullOrBlank()
        ) {
            Log.e(TAG, "Missing capture permission or pairing code")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startCaptureNotification()

            webRtcManager?.close()

            webRtcManager = WebRtcManager(
                context = applicationContext,
                code = pairingCode,
                controller = false,
                onVideo = { _, _ -> },
                onStatus = { status ->
                    Log.d(TAG, "WebRTC status: $status")
                }
            )

            webRtcManager?.start(resultData)

        } catch (e: Exception) {
            Log.e(TAG, "Screen sharing failed", e)
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
            .setContentText("Screen sharing is active")
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
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }
    }

    override fun onDestroy() {
        webRtcManager?.close()
        webRtcManager = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
