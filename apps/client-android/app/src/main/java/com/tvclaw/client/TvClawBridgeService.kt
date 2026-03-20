package com.tvclaw.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class TvClawBridgeService : Service() {

    private val client = OkHttpClient()
    private val socketRef = AtomicReference<WebSocket?>(null)
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var reconnect: ScheduledFuture<*>? = null
    private var url: String = BuildConfig.TV_BRAIN_WS_URL

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                disconnect()
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        url = intent?.getStringExtra(EXTRA_WS_URL) ?: BuildConfig.TV_BRAIN_WS_URL
        val n = buildNotification(getString(R.string.notification_bridge_running))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
        connectNow()
        return START_STICKY
    }

    override fun onDestroy() {
        reconnect?.cancel(false)
        reconnect = null
        disconnect()
        scheduler.shutdownNow()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun connectNow() {
        reconnect?.cancel(false)
        reconnect = null
        disconnect()
        val req = Request.Builder().url(url).build()
        val ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                TvClawAccessibilityService.postEnvelopeJson(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                socketRef.compareAndSet(webSocket, null)
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                socketRef.compareAndSet(webSocket, null)
                scheduleReconnect()
            }
        })
        socketRef.set(ws)
    }

    private fun scheduleReconnect() {
        if (reconnect != null) return
        reconnect = scheduler.schedule({ 
            reconnect = null
            connectNow() 
        }, 3, TimeUnit.SECONDS)
    }

    private fun disconnect() {
        socketRef.getAndSet(null)?.close(1000, null)
    }

    companion object {
        const val ACTION_START = "com.tvclaw.client.action.START_BRIDGE"
        const val ACTION_STOP = "com.tvclaw.client.action.STOP_BRIDGE"
        const val EXTRA_WS_URL = "ws_url"
        private const val CHANNEL_ID = "tvclaw_bridge"
        private const val NOTIFICATION_ID = 42
    }
}
