package com.tvclaw.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.util.Log
import android.widget.Toast
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.net.InetSocketAddress
import org.json.JSONObject
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer

class TvClawBridgeService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var commandServer: WebSocketServer? = null
    private val connectedClients = java.util.concurrent.CopyOnWriteArraySet<WebSocket>()
    private var nsdManager: NsdManager? = null
    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID_TOAST,
                    getString(R.string.notification_toast_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCommandServerAndNsd()
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val n = buildNotification(getString(R.string.notification_bridge_running))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
        val reportConnect =
            intent?.getBooleanExtra(EXTRA_REPORT_CONNECT_RESULT, false) == true
        startServerAndNsd(reportConnect)
        return START_STICKY
    }

    override fun onDestroy() {
        stopCommandServerAndNsd()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun showBrainToastOrNotification(text: String) {
        try {
            Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.w(TAG, "Toast failed", e)
        }
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val n = NotificationCompat.Builder(this, CHANNEL_ID_TOAST)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text.take(200))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify(NOTIFICATION_TOAST_ID, n)
    }

    private fun startServerAndNsd(reportConnectResult: Boolean) {
        stopCommandServerAndNsd()
        val listenPort = BuildConfig.TVCLAW_WS_LISTEN_PORT
        val bindPort = if (listenPort > 0) listenPort else 0
        val srv =
            object : WebSocketServer(InetSocketAddress("0.0.0.0", bindPort)) {
                override fun onStart() {
                    val p = port
                    mainHandler.post {
                        registerNsd(p, reportConnectResult)
                    }
                }

                override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
                    if (conn != null) connectedClients.add(conn)
                    instance = this@TvClawBridgeService
                }

                override fun onClose(
                    conn: WebSocket?,
                    code: Int,
                    reason: String?,
                    remote: Boolean,
                ) {
                    if (conn != null) connectedClients.remove(conn)
                }

                override fun onMessage(conn: WebSocket?, message: String?) {
                    if (message == null) return
                    extractShowToastMessage(message)?.let { text ->
                        mainHandler.post {
                            showBrainToastOrNotification(text)
                        }
                        return
                    }
                    if (TvClawAccessibilityService.deliverEnvelope(message)) return
                }

                override fun onError(conn: WebSocket?, ex: Exception?) {}
            }
        commandServer = srv
        Thread(
            {
                try {
                    srv.start()
                } catch (e: Exception) {
                    mainHandler.post {
                        if (reportConnectResult) {
                            emitConnectResult(
                                false,
                                e.message?.takeIf { it.isNotBlank() }
                                    ?: e.javaClass.simpleName,
                            )
                        }
                    }
                }
            },
            "tvclaw-wss",
        ).start()
    }

    private fun registerNsd(port: Int, reportConnectResult: Boolean) {
        unregisterNsdQuietly()
        val mgr = getSystemService(Context.NSD_SERVICE) as NsdManager
        nsdManager = mgr
        val safeName =
            "TVClaw-${Build.MODEL}".replace(Regex("[^a-zA-Z0-9-]"), "-").take(50)
        val info = NsdServiceInfo().apply {
            serviceName = safeName
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                if (!reportConnectResult) {
                    return
                }
                mainHandler.post {
                    emitConnectResult(
                        true,
                        getString(
                            R.string.bridge_nsd_ok,
                            serviceInfo.serviceName,
                            port,
                        ),
                    )
                }
            }

            override fun onRegistrationFailed(
                serviceInfo: NsdServiceInfo,
                errorCode: Int,
            ) {
                if (!reportConnectResult) {
                    return
                }
                mainHandler.post {
                    emitConnectResult(
                        false,
                        getString(R.string.bridge_nsd_failed, errorCode),
                    )
                }
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}

            override fun onUnregistrationFailed(
                serviceInfo: NsdServiceInfo,
                errorCode: Int,
            ) {}
        }
        nsdRegistrationListener = listener
        try {
            mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            if (reportConnectResult) {
                emitConnectResult(
                    false,
                    e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName,
                )
            }
        }
    }

    private fun unregisterNsdQuietly() {
        val mgr = nsdManager
        val listener = nsdRegistrationListener
        if (mgr != null && listener != null) {
            try {
                mgr.unregisterService(listener)
            } catch (_: Exception) {
            }
        }
        nsdManager = null
        nsdRegistrationListener = null
    }

    private fun stopCommandServerAndNsd() {
        unregisterNsdQuietly()
        connectedClients.clear()
        instance = null
        try {
            commandServer?.stop(1000)
        } catch (_: Exception) {
        }
        commandServer = null
    }

    fun sendToBrain(message: String) {
        for (client in connectedClients) {
            try {
                if (client.isOpen) client.send(message)
            } catch (e: Exception) {
                Log.w(TAG, "sendToBrain failed for client", e)
            }
        }
    }

    private fun extractShowToastMessage(json: String): String? {
        return runCatching {
            val payload = JSONObject(json).optJSONObject("payload") ?: return@runCatching null
            if (payload.optString("action") != "SHOW_TOAST") return@runCatching null
            val params = payload.optJSONObject("params") ?: return@runCatching null
            params.optString("message").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun emitConnectResult(ok: Boolean, message: String?) {
        getSharedPreferences(BRIDGE_PREFS, MODE_PRIVATE).edit().apply {
            putBoolean(PREF_BRIDGE_OK, ok)
            if (message != null) {
                putString(PREF_BRIDGE_MSG, message)
            } else {
                remove(PREF_BRIDGE_MSG)
            }
            apply()
        }
        sendBroadcast(
            Intent(ACTION_BRIDGE_STATUS).setPackage(packageName).apply {
                putExtra(EXTRA_BRIDGE_OK, ok)
                if (message != null) {
                    putExtra(EXTRA_BRIDGE_MESSAGE, message)
                }
            },
        )
    }

    companion object {
        private const val TAG = "TvClawBridge"
        const val BRIDGE_PREFS = "tvclaw_bridge_status"
        const val PREF_BRIDGE_OK = "bridge_ok"
        const val PREF_BRIDGE_MSG = "bridge_msg"
        @Volatile var instance: TvClawBridgeService? = null

        fun broadcast(message: String) {
            instance?.sendToBrain(message)
        }

        const val ACTION_START = "com.tvclaw.client.action.START_BRIDGE"
        const val ACTION_STOP = "com.tvclaw.client.action.STOP_BRIDGE"
        const val ACTION_BRIDGE_STATUS = "com.tvclaw.client.action.BRIDGE_STATUS"
        const val EXTRA_REPORT_CONNECT_RESULT = "report_connect_result"
        const val EXTRA_BRIDGE_OK = "bridge_ok"
        const val EXTRA_BRIDGE_MESSAGE = "bridge_message"
        private const val CHANNEL_ID = "tvclaw_bridge"
        private const val CHANNEL_ID_TOAST = "tvclaw_brain_toast"
        private const val NOTIFICATION_ID = 42
        private const val NOTIFICATION_TOAST_ID = 43
        private const val SERVICE_TYPE = "_tvclaw._tcp"
    }
}
