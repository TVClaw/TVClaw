package com.tvclaw.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
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
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer

class TvClawBridgeService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var commandServer: WebSocketServer? = null
    private var nsdManager: NsdManager? = null
    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
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

                override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {}

                override fun onClose(
                    conn: WebSocket?,
                    code: Int,
                    reason: String?,
                    remote: Boolean,
                ) {
                }

                override fun onMessage(conn: WebSocket?, message: String?) {
                    if (message != null) {
                        TvClawAccessibilityService.postEnvelopeJson(message)
                    }
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
        try {
            commandServer?.stop(1000)
        } catch (_: Exception) {
        }
        commandServer = null
    }

    private fun emitConnectResult(ok: Boolean, message: String?) {
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
        const val ACTION_START = "com.tvclaw.client.action.START_BRIDGE"
        const val ACTION_STOP = "com.tvclaw.client.action.STOP_BRIDGE"
        const val ACTION_BRIDGE_STATUS = "com.tvclaw.client.action.BRIDGE_STATUS"
        const val EXTRA_REPORT_CONNECT_RESULT = "report_connect_result"
        const val EXTRA_BRIDGE_OK = "bridge_ok"
        const val EXTRA_BRIDGE_MESSAGE = "bridge_message"
        private const val CHANNEL_ID = "tvclaw_bridge"
        private const val NOTIFICATION_ID = 42
        private const val SERVICE_TYPE = "_tvclaw._tcp"
    }
}
