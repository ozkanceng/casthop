package io.github.ozkanceng.yansit.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import io.github.ozkanceng.yansit.MainActivity
import io.github.ozkanceng.yansit.R

/**
 * Ekran yakalama için ön plan servisi (`foregroundServiceType="mediaProjection"`).
 * Sıra Android 14+ kuralına uyar: startForeground → getMediaProjection → registerCallback → createVirtualDisplay.
 */
class MirrorService : Service() {
    private var projection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                MirrorController.stop()
                return START_NOT_STICKY
            }
            ACTION_START -> start(intent)
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        val board = intent.getStringExtra(EXTRA_BOARD).orEmpty()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(board),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        val mp = data?.let { getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, it) }
        if (mp == null) {
            Log.e(TAG, "MediaProjection alınamadı")
            MirrorController.onProjectionStopped()
            stopForegroundAndSelf()
            return
        }
        projection = mp
        mp.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection durdu")
                    MirrorController.onProjectionStopped()
                }

                // API 34+: döndürme veya paylaşılan pencere boyutu değişti.
                override fun onCapturedContentResize(width: Int, height: Int) {
                    MirrorController.onCapturedContentResize()
                }
            },
            Handler(Looper.getMainLooper()),
        )
        MirrorController.onProjectionReady(this, mp)
    }

    internal fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        projection = null
        super.onDestroy()
    }

    private fun notification(board: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MirrorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.notif_title, board))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.action_stop), stop)
            .build()
    }

    companion object {
        private const val TAG = "YansitService"
        private const val CHANNEL_ID = "mirroring"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_START = "io.github.ozkanceng.yansit.START"
        private const val ACTION_STOP = "io.github.ozkanceng.yansit.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_BOARD = "board"

        fun startIntent(context: Context, resultCode: Int, data: Intent, board: String): Intent =
            Intent(context, MirrorService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
                .putExtra(EXTRA_BOARD, board)
    }
}
