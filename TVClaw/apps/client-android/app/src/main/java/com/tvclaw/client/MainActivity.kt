package com.tvclaw.client

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.tvclaw.client.databinding.ActivityMainBinding
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import okhttp3.Request

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val requestPostNotificationsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(
                    this,
                    R.string.permission_notifications_denied,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

    private val bridgeStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != TvClawBridgeService.ACTION_BRIDGE_STATUS) {
                return
            }
            val ok = intent.getBooleanExtra(TvClawBridgeService.EXTRA_BRIDGE_OK, false)
            val msg = intent.getStringExtra(TvClawBridgeService.EXTRA_BRIDGE_MESSAGE)
            val text =
                if (ok) {
                    getString(R.string.bridge_connect_ok)
                } else {
                    getString(R.string.bridge_connect_failed, msg ?: "")
                }
            Toast.makeText(this@MainActivity, text, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.versionFooter.text =
            if (BuildConfig.DEBUG) {
                "debug-${BuildConfig.VERSION_NAME}"
            } else {
                BuildConfig.VERSION_NAME
            }
        ensurePostNotificationsPermission()
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            binding.openAccessibility.setText(R.string.open_accessibility_entry_tv)
        }
        binding.openAccessibility.setOnClickListener {
            openAccessibilitySettingsBestEffort()
        }
        binding.connectBridge.setOnClickListener {
            ContextCompat.startForegroundService(
                this,
                Intent(this, TvClawBridgeService::class.java).apply {
                    action = TvClawBridgeService.ACTION_START
                    putExtra(TvClawBridgeService.EXTRA_WS_URL, BuildConfig.TV_BRAIN_WS_URL)
                    putExtra(TvClawBridgeService.EXTRA_REPORT_CONNECT_RESULT, true)
                }
            )
        }
        binding.disconnectBridge.setOnClickListener {
            startService(
                Intent(this, TvClawBridgeService::class.java).apply {
                    action = TvClawBridgeService.ACTION_STOP
                }
            )
        }
        binding.updateApp.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !packageManager.canRequestPackageInstalls()
            ) {
                Toast.makeText(
                    this,
                    R.string.permission_install_unknown_hint,
                    Toast.LENGTH_LONG,
                ).show()
                try {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = Uri.parse("package:$packageName")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    )
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(
                        this,
                        R.string.permission_settings_failed,
                        Toast.LENGTH_LONG,
                    ).show()
                }
                return@setOnClickListener
            }
            binding.updateApp.isEnabled = false
            Toast.makeText(this, getString(R.string.update_app_downloading), Toast.LENGTH_SHORT).show()
            thread {
                try {
                    val apkUrl = brainHttpApkUrl(BuildConfig.TV_BRAIN_WS_URL)
                    val out = File(cacheDir, "tvclaw-update.apk")
                    downloadToFile(apkUrl, out)
                    val uri = FileProvider.getUriForFile(
                        this,
                        "${BuildConfig.APPLICATION_ID}.fileprovider",
                        out,
                    )
                    val install = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runOnUiThread {
                        binding.updateApp.isEnabled = true
                        try {
                            startActivity(install)
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(
                                this,
                                getString(R.string.update_app_no_installer),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                } catch (e: Exception) {
                    val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                    runOnUiThread {
                        binding.updateApp.isEnabled = true
                        Toast.makeText(
                            this,
                            getString(R.string.update_app_failed, msg),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
    }

    private fun brainHttpApkUrl(wsUrl: String): String {
        val u = Uri.parse(wsUrl)
        val host = u.host ?: throw IllegalArgumentException("missing host")
        val scheme =
            when (u.scheme?.lowercase()) {
                "wss" -> "https"
                "ws" -> "http"
                else -> throw IllegalArgumentException("expected ws or wss")
            }
        val wsPort = u.port.takeIf { it != -1 } ?: 8765
        val httpPort = if (wsPort == 8765) 8770 else wsPort
        val hostPort =
            if ((scheme == "http" && httpPort == 80) ||
                (scheme == "https" && httpPort == 443)
            ) {
                host
            } else {
                "$host:$httpPort"
            }
        return "$scheme://$hostPort/tvclaw-client.apk"
    }

    private fun downloadToFile(url: String, out: File) {
        val client = OkHttpClient()
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code}")
            }
            val body = resp.body ?: throw IOException("empty body")
            out.outputStream().use { fos ->
                body.byteStream().copyTo(fos)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            bridgeStatusReceiver,
            IntentFilter(TvClawBridgeService.ACTION_BRIDGE_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStop() {
        unregisterReceiver(bridgeStatusReceiver)
        super.onStop()
    }

    private fun ensurePostNotificationsPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        requestPostNotificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun intentResolvable(intent: Intent): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.resolveActivity(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
                ) != null
            } else {
                @Suppress("DEPRECATION")
                packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
            }
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun startIfResolved(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!intentResolvable(intent)) {
            return false
        }
        return try {
            startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private fun openAccessibilitySettingsBestEffort() {
        val appDetails =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
        val isTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        if (isTv && startIfResolved(appDetails)) {
            Toast.makeText(this, R.string.open_accessibility_navigate_hint, Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val detail =
                Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
                    putExtra(
                        Intent.EXTRA_COMPONENT_NAME,
                        ComponentName(this@MainActivity, TvClawAccessibilityService::class.java),
                    )
                }
            if (startIfResolved(detail)) {
                return
            }
        }
        if (startIfResolved(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) {
            return
        }
        if (!isTv && startIfResolved(appDetails)) {
            Toast.makeText(this, R.string.open_accessibility_navigate_hint, Toast.LENGTH_LONG).show()
            return
        }
        val settingPkgs =
            listOf(
                "com.android.tv.settings",
                "com.google.android.tv.axel.settings",
                "com.xiaomi.mitv.settings",
                "com.xiaomi.tv.settings",
            )
        for (pkg in settingPkgs) {
            val launch =
                packageManager.getLeanbackLaunchIntentForPackage(pkg)
                    ?: packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null && startIfResolved(launch)) {
                Toast.makeText(this, R.string.open_accessibility_navigate_hint, Toast.LENGTH_LONG)
                    .show()
                return
            }
        }
        if (startIfResolved(Intent(Settings.ACTION_SETTINGS))) {
            Toast.makeText(this, R.string.open_accessibility_navigate_hint, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, R.string.open_accessibility_failed, Toast.LENGTH_LONG).show()
        }
    }
}
