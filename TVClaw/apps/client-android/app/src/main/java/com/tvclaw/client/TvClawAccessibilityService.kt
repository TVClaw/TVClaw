package com.tvclaw.client

import android.accessibilityservice.AccessibilityService
import android.app.SearchManager
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.app.AlertDialog
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject

class TvClawAccessibilityService : AccessibilityService() {

    private var ioThread: HandlerThread? = null
    private var ioHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val thread = HandlerThread("tvclaw-io").also { it.start() }
        ioThread = thread
        ioHandler = Handler(thread.looper)
        Log.i(TAG, "TvClawAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        ioThread?.quitSafely()
        ioThread = null
        ioHandler = null
        super.onDestroy()
    }

    fun enqueueEnvelopeJson(json: String) {
        ioHandler?.post {
            runCatching { handleEnvelope(JSONObject(json)) }
                .onFailure { Log.e(TAG, "handleEnvelope failed", it) }
        } ?: Log.w(TAG, "ioHandler not ready; drop message")
    }

    private fun handleEnvelope(envelope: JSONObject) {
        val payload = envelope.optJSONObject("payload") ?: run {
            Log.w(TAG, "envelope missing payload")
            return
        }
        val action = payload.optString("action")
        val params = payload.optJSONObject("params") ?: JSONObject()
        Log.i(TAG, "command action=$action")
        when (action) {
            "MEDIA_CONTROL" -> {
                val c = params.optString("control")
                mainHandler.post { applyMediaControl(c) }
            }
            "LAUNCH_APP" -> {
                val pkg = params.optString("app_id")
                if (pkg.isNotEmpty()) {
                    mainHandler.post { launchPackage(pkg) }
                }
            }
            "SEARCH" -> {
                val pkg = params.optString("app_id")
                val query = params.optString("query")
                mainHandler.post { performInAppSearch(pkg, query) }
            }
            "VISION_SYNC" -> Log.i(TAG, "VISION_SYNC stub (MediaProjection not wired)")
            "SHOW_TOAST" -> {
                val msg =
                    params.optString("message").takeIf { it.isNotBlank() }
                        ?: "TVClaw POC"
                mainHandler.post {
                    val ctx = this@TvClawAccessibilityService
                    val dialog =
                        AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle("TVClaw")
                            .setMessage(msg)
                            .setPositiveButton(android.R.string.ok) { d, _ -> d.dismiss() }
                            .setCancelable(true)
                            .create()
                    dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
                    dialog.show()
                }
            }
            else -> Log.w(TAG, "unknown action: $action")
        }
    }

    private fun applyMediaControl(control: String) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        when (control.uppercase()) {
            "HOME" -> {
                val ok = performGlobalAction(GLOBAL_ACTION_HOME)
                Log.i(TAG, "MEDIA_CONTROL HOME globalActionOk=$ok")
            }
            "BACK" -> {
                val ok = performGlobalAction(GLOBAL_ACTION_BACK)
                Log.i(TAG, "MEDIA_CONTROL BACK globalActionOk=$ok")
            }
            "MUTE" -> {
                Log.i(TAG, "MUTE on main thread")
                val streams = ArrayList<Int>(4)
                streams.add(AudioManager.STREAM_MUSIC)
                streams.add(AudioManager.STREAM_SYSTEM)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    streams.add(AudioManager.STREAM_ACCESSIBILITY)
                }
                streams.add(AudioManager.STREAM_VOICE_CALL)
                for (stream in streams) {
                    try {
                        @Suppress("DEPRECATION")
                        am.setStreamMute(stream, true)
                    } catch (e: Exception) {
                        Log.w(TAG, "setStreamMute stream=$stream", e)
                    }
                    try {
                        am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
                    } catch (e: Exception) {
                        Log.w(TAG, "adjustStreamVolume ADJUST_MUTE stream=$stream", e)
                    }
                }
            }
            "PLAY", "PAUSE", "REWIND_30" -> Log.i(TAG, "MEDIA_CONTROL $control — wire KeyEvent / session when paired")
            else -> Log.w(TAG, "unknown control: $control")
        }
    }

    private fun hasLaunchIntent(pkg: String): Boolean {
        val pm = packageManager
        if (pm.getLaunchIntentForPackage(pkg) != null) return true
        if (pm.getLeanbackLaunchIntentForPackage(pkg) != null) return true
        return false
    }

    private fun resolveLaunchIntent(pkg: String): Intent? {
        val pm = packageManager
        pm.getLaunchIntentForPackage(pkg)?.let { return it }
        return pm.getLeanbackLaunchIntentForPackage(pkg)
    }

    private fun resolveTargetPackage(spec: String): String {
        val s = spec.trim()
        if (s.isEmpty()) return s
        val lower = s.lowercase()
        if (lower.contains("netflix")) {
            for (id in listOf("com.netflix.ninja", "com.netflix.mediaclient")) {
                if (hasLaunchIntent(id)) return id
            }
        }
        if (lower.contains("youtube")) {
            for (id in listOf(
                "com.google.android.youtube.tv",
                "com.google.android.youtube",
            )) {
                if (hasLaunchIntent(id)) return id
            }
        }
        return s
    }

    private fun launchPackage(packageName: String) {
        val pkg = resolveTargetPackage(packageName)
        val intent = resolveLaunchIntent(pkg)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(intent)
                Log.i(TAG, "LAUNCH_APP started pkg=$pkg")
            } catch (e: Exception) {
                Log.e(TAG, "LAUNCH_APP startActivity failed pkg=$pkg", e)
            }
        } else {
            Log.w(TAG, "no launch intent for $packageName (resolved=$pkg)")
        }
    }

    private fun performInAppSearch(appIdSpec: String, query: String) {
        val pkg = resolveTargetPackage(appIdSpec)
        if (pkg.isEmpty()) {
            Log.w(TAG, "SEARCH missing app_id")
            return
        }
        val q = query.trim()
        if (q.isEmpty()) {
            Log.w(TAG, "SEARCH empty query")
            return
        }
        val encoded = Uri.encode(q)
        val candidates = mutableListOf<Intent>()
        candidates.add(
            Intent(Intent.ACTION_SEARCH).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage(pkg)
                putExtra(SearchManager.QUERY, q)
            },
        )
        if (pkg.contains("netflix", ignoreCase = true)) {
            candidates.add(
                Intent(Intent.ACTION_VIEW).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    setPackage(pkg)
                    data = Uri.parse("https://www.netflix.com/search?q=$encoded")
                },
            )
            candidates.add(
                Intent(Intent.ACTION_VIEW, Uri.parse("nflx://www.netflix.com/search?q=$encoded")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    setPackage(pkg)
                },
            )
        }
        for (intent in candidates) {
            try {
                startActivity(intent)
                Log.i(TAG, "SEARCH ok action=${intent.action} data=${intent.data}")
                return
            } catch (e: Exception) {
                Log.d(TAG, "SEARCH candidate failed", e)
            }
        }
        Log.w(TAG, "SEARCH could not start for pkg=$pkg")
    }

    companion object {
        private const val TAG = "TvClawA11y"
        @Volatile
        var instance: TvClawAccessibilityService? = null

        fun postEnvelopeJson(json: String) {
            val svc = instance
            if (svc == null) {
                Log.w(TAG, "postEnvelopeJson dropped: accessibility service not running")
                return
            }
            svc.enqueueEnvelopeJson(json)
        }
    }
}
