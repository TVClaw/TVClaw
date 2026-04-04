package com.tvclaw.client

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
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
            if (ok) {
                showBridgeSuccessUi()
            } else {
                showBridgeFailureUi(msg ?: "")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.versionFooter.text =
            if (BuildConfig.DEBUG) {
                "debug · v${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}"
            } else {
                "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            }
        ensurePostNotificationsPermission()
        binding.retryBridge.setOnClickListener {
            startBridgeWithReport()
        }
        binding.testConnection.setOnClickListener {
            val (ok, _) = readBridgePrefs()
            if (ok != true) {
                Toast.makeText(this, R.string.test_connection_not_ready, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (!isTvClawAccessibilityEnabled()) {
                Toast.makeText(this, R.string.test_connection_need_accessibility, Toast.LENGTH_LONG)
                    .show()
                return@setOnClickListener
            }
            Toast.makeText(this, R.string.test_connection_success, Toast.LENGTH_LONG).show()
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
                    val apkUrl = BuildConfig.TVCLAW_UPDATE_APK_URL.trim()
                    if (apkUrl.isEmpty()) {
                        throw IOException("empty TVCLAW_UPDATE_APK_URL")
                    }
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

        if (savedInstanceState == null) {
            val (ok, msg) = readBridgePrefs()
            when {
                ok == true -> applyUiFromStoredBridgeStatus()
                ok == false && msg == BRIDGE_STOPPED_MARKER -> applyUiFromStoredBridgeStatus()
                else -> startBridgeWithReport()
            }
        } else {
            applyUiFromStoredBridgeStatus()
        }
    }

    private fun startBridgeWithReport() {
        getSharedPreferences(TvClawBridgeService.BRIDGE_PREFS, MODE_PRIVATE).edit().clear()
            .apply()
        binding.progressConnect.visibility = View.VISIBLE
        binding.statusText.text = getString(R.string.status_connecting_brain, BuildConfig.VERSION_CODE)
        binding.retryBridge.visibility = View.GONE
        binding.updateApp.visibility = View.GONE
        binding.testConnection.visibility = View.GONE
        ContextCompat.startForegroundService(
            this,
            Intent(this, TvClawBridgeService::class.java).apply {
                action = TvClawBridgeService.ACTION_START
                putExtra(TvClawBridgeService.EXTRA_REPORT_CONNECT_RESULT, true)
            },
        )
    }

    private fun readBridgePrefs(): Pair<Boolean?, String?> {
        val p = getSharedPreferences(TvClawBridgeService.BRIDGE_PREFS, MODE_PRIVATE)
        if (!p.contains(TvClawBridgeService.PREF_BRIDGE_OK)) {
            return Pair(null, null)
        }
        return Pair(
            p.getBoolean(TvClawBridgeService.PREF_BRIDGE_OK, false),
            p.getString(TvClawBridgeService.PREF_BRIDGE_MSG, null),
        )
    }

    private fun applyUiFromStoredBridgeStatus() {
        val (ok, msg) = readBridgePrefs()
        when {
            ok == null -> {
                binding.progressConnect.visibility = View.VISIBLE
                binding.statusText.text =
                    getString(R.string.status_connecting_brain, BuildConfig.VERSION_CODE)
                binding.retryBridge.visibility = View.GONE
                binding.updateApp.visibility = View.GONE
                binding.testConnection.visibility = View.GONE
            }
            ok -> showBridgeSuccessUi()
            msg == BRIDGE_STOPPED_MARKER -> showBridgeStoppedUi()
            else -> showBridgeFailureUi(msg ?: "")
        }
    }

    private fun showBridgeSuccessUi() {
        binding.progressConnect.visibility = View.GONE
        binding.statusText.text = getString(R.string.status_connected_whatsapp_hint)
        binding.retryBridge.visibility = View.GONE
        binding.updateApp.visibility = View.VISIBLE
        binding.testConnection.visibility = View.VISIBLE
    }

    private fun showBridgeFailureUi(msg: String) {
        binding.progressConnect.visibility = View.GONE
        binding.statusText.text = getString(R.string.bridge_connect_failed, msg)
        binding.retryBridge.visibility = View.VISIBLE
        binding.updateApp.visibility = View.GONE
        binding.testConnection.visibility = View.GONE
    }

    private fun showBridgeStoppedUi() {
        binding.progressConnect.visibility = View.GONE
        binding.statusText.setText(R.string.status_bridge_stopped)
        binding.retryBridge.visibility = View.VISIBLE
        binding.updateApp.visibility = View.GONE
        binding.testConnection.visibility = View.GONE
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

    override fun onResume() {
        super.onResume()
        applyUiFromStoredBridgeStatus()
    }

    override fun onStop() {
        unregisterReceiver(bridgeStatusReceiver)
        super.onStop()
    }

    private fun isTvClawAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val list =
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val want = android.content.ComponentName(this, TvClawAccessibilityService::class.java)
        for (info in list) {
            val si = info.resolveInfo.serviceInfo
            if (si.packageName == want.packageName && si.name == want.className) {
                return true
            }
        }
        return false
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

    companion object {
        private const val BRIDGE_STOPPED_MARKER = "user_stopped"
    }
}
