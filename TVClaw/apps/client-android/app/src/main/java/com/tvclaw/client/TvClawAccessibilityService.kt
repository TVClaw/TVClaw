package com.tvclaw.client

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class TvClawAccessibilityService : AccessibilityService() {

    private var ioThread: HandlerThread? = null
    private var ioHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sleepTimerRunnable: Runnable? = null

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
        sleepTimerRunnable?.let { mainHandler.removeCallbacks(it) }
        sleepTimerRunnable = null
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
            "OPEN_URL" -> {
                val url = params.optString("url")
                val appId = params.optString("app_id")
                mainHandler.post { openUrl(url, appId) }
            }
            "SEARCH" -> {
                val pkg = params.optString("app_id")
                val query = params.optString("query")
                mainHandler.post { performInAppSearch(pkg, query) }
            }
            "UNIVERSAL_SEARCH" -> {
                val query = params.optString("query")
                mainHandler.post { performUniversalSearch(query) }
            }
            "SLEEP_TIMER" -> {
                val minutes = params.optInt("value", 0)
                mainHandler.post { setSleepTimer(minutes) }
            }
            "VISION_SYNC" -> {
                val requestId = params.optString("request_id").takeIf { it.isNotEmpty() }
                    ?: run { Log.w(TAG, "VISION_SYNC missing request_id"); return }
                mainHandler.post { captureScreenshot(requestId) }
            }
            "KEY_EVENT" -> {
                val keyName = params.optString("keycode")
                mainHandler.post { dispatchKeyByName(keyName) }
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
            "PLAY" -> dispatchMediaControlKey(am, KeyEvent.KEYCODE_MEDIA_PLAY)
            "PAUSE" -> dispatchMediaControlKey(am, KeyEvent.KEYCODE_MEDIA_PAUSE)
            "REWIND_30" -> dispatchMediaControlKey(am, KeyEvent.KEYCODE_MEDIA_REWIND)
            "FAST_FORWARD_30" -> dispatchMediaControlKey(am, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            else -> Log.w(TAG, "unknown control: $control")
        }
    }

    private fun dispatchMediaControlKey(am: AudioManager, keyCode: Int) {
        try {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            Log.i(TAG, "MEDIA_CONTROL keyCode=$keyCode dispatched")
        } catch (e: Exception) {
            Log.w(TAG, "MEDIA_CONTROL keyCode=$keyCode failed", e)
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
        if (hasLaunchIntent(s)) return s
        val needle = normalizeAppToken(s)
        if (needle.isEmpty()) return s
        val pm = packageManager
        val apps = pm.getInstalledApplications(0)
        for (app in apps) {
            val pkg = app.packageName ?: continue
            if (!hasLaunchIntent(pkg)) continue
            val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrNull() ?: ""
            val hayPkg = normalizeAppToken(pkg)
            val hayLabel = normalizeAppToken(label)
            if (hayPkg.contains(needle) || hayLabel.contains(needle)) return pkg
        }
        return s
    }

    private fun normalizeAppToken(value: String): String {
        return value.lowercase().filter { it.isLetterOrDigit() }
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
        if (pkg.contains("netflix", ignoreCase = true)) {
            candidates.add(
                Intent(Intent.ACTION_VIEW, Uri.parse("nflx://www.netflix.com/search?q=$encoded")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    setPackage(pkg)
                    putExtra("source", "30")
                },
            )
            candidates.add(
                Intent(Intent.ACTION_VIEW).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    setPackage(pkg)
                    data = Uri.parse("https://www.netflix.com/search?q=$encoded")
                    putExtra("source", "30")
                },
            )
        }
        candidates.add(
            Intent(Intent.ACTION_SEARCH).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage(pkg)
                putExtra(SearchManager.QUERY, q)
            },
        )
        if (pkg.contains("netflix", ignoreCase = true)) {
            candidates.add(
                Intent(Intent.ACTION_VIEW, Uri.parse("http://www.netflix.com/search?q=$encoded")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    setPackage(pkg)
                    putExtra("source", "30")
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

    private fun openUrl(rawUrl: String, appIdSpec: String) {
        val url = rawUrl.trim()
        if (url.isEmpty()) {
            Log.w(TAG, "OPEN_URL missing url")
            return
        }
        val pkg =
            appIdSpec.trim().takeIf { it.isNotEmpty() }?.let { resolveTargetPackage(it) }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!pkg.isNullOrEmpty()) setPackage(pkg)
            if ((pkg?.contains("netflix", ignoreCase = true) == true) || url.startsWith("nflx://")) {
                putExtra("source", "30")
            }
        }
        try {
            startActivity(intent)
            Log.i(TAG, "OPEN_URL ok url=$url pkg=${pkg ?: ""}")
        } catch (e: Exception) {
            Log.w(TAG, "OPEN_URL failed url=$url pkg=${pkg ?: ""}", e)
        }
    }

    private fun performUniversalSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            Log.w(TAG, "UNIVERSAL_SEARCH empty query")
            return
        }
        val encoded = Uri.encode(q)
        val candidates = mutableListOf<Intent>()
        candidates.add(
            Intent(Intent.ACTION_SEARCH).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(SearchManager.QUERY, q)
            },
        )
        candidates.add(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$encoded")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        for (intent in candidates) {
            try {
                startActivity(intent)
                Log.i(TAG, "UNIVERSAL_SEARCH ok action=${intent.action} data=${intent.data}")
                fillSearchFieldWithRetries(q)
                return
            } catch (e: Exception) {
                Log.d(TAG, "UNIVERSAL_SEARCH candidate failed", e)
            }
        }
        Log.w(TAG, "UNIVERSAL_SEARCH failed")
    }

    private fun fillSearchFieldWithRetries(query: String, attempt: Int = 0) {
        if (attempt >= 8) {
            Log.w(TAG, "UNIVERSAL_SEARCH set text failed after retries")
            return
        }
        val root = rootInActiveWindow
        if (root == null) {
            mainHandler.postDelayed({ fillSearchFieldWithRetries(query, attempt + 1) }, 300L)
            return
        }
        val target = findEditableNode(root)
        if (target == null) {
            mainHandler.postDelayed({ fillSearchFieldWithRetries(query, attempt + 1) }, 300L)
            return
        }
        try {
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val args = Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                query,
            )
            val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (ok) {
                Log.i(TAG, "UNIVERSAL_SEARCH text injected")
                return
            }
        } catch (e: Exception) {
            Log.d(TAG, "UNIVERSAL_SEARCH text injection attempt failed", e)
        }
        mainHandler.postDelayed({ fillSearchFieldWithRetries(query, attempt + 1) }, 300L)
    }

    private fun findEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isEditable) return root
        val cls = root.className?.toString()?.lowercase() ?: ""
        if (cls.contains("edittext") || cls.contains("search")) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findEditableNode(child)
            if (found != null) return found
        }
        return null
    }

    @SuppressLint("NewApi") // guarded by Build.VERSION_CODES.R and .S checks inside
    private fun captureScreenshot(requestId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "VISION_SYNC requires Android 11+ (API 30), current=${Build.VERSION.SDK_INT}")
            sendVisionFailure(requestId, "requires Android 11+")
            return
        }
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshotResult: ScreenshotResult) {
                    ioHandler?.post {
                        try {
                            val hardwareBuffer = screenshotResult.hardwareBuffer
                            val colorSpace = screenshotResult.colorSpace
                            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                            } else {
                                null
                            } ?: run {
                                sendVisionFailure(requestId, "bitmap unavailable (requires Android 12+)")
                                hardwareBuffer.close()
                                return@post
                            }
                            val out = ByteArrayOutputStream()
                            // Copy to software config so JPEG compression works reliably
                            val soft = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                            bitmap.recycle()
                            soft.compress(Bitmap.CompressFormat.JPEG, 80, out)
                            soft.recycle()
                            hardwareBuffer.close()
                            val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                            val msg = """{"type":"vision_sync_response","request_id":"$requestId","jpeg_base64":"$b64"}"""
                            TvClawBridgeService.broadcast(msg)
                            Log.i(TAG, "VISION_SYNC sent ${out.size()} bytes requestId=$requestId")
                        } catch (e: Exception) {
                            Log.e(TAG, "VISION_SYNC encode failed", e)
                            sendVisionFailure(requestId, e.message ?: "encode error")
                        }
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "VISION_SYNC takeScreenshot failed code=$errorCode")
                    sendVisionFailure(requestId, "capture failed code=$errorCode")
                }
            }
        )
    }

    private fun sendVisionFailure(requestId: String, reason: String) {
        val msg = """{"type":"vision_sync_response","request_id":"$requestId","error":"$reason"}"""
        TvClawBridgeService.broadcast(msg)
    }

    private fun dispatchKeyByName(keyName: String) {
        val keyCode = when (keyName.uppercase()) {
            "DPAD_UP" -> KeyEvent.KEYCODE_DPAD_UP
            "DPAD_DOWN" -> KeyEvent.KEYCODE_DPAD_DOWN
            "DPAD_LEFT" -> KeyEvent.KEYCODE_DPAD_LEFT
            "DPAD_RIGHT" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "DPAD_CENTER", "ENTER", "OK", "SELECT" -> KeyEvent.KEYCODE_DPAD_CENTER
            "BACK" -> KeyEvent.KEYCODE_BACK
            "HOME" -> KeyEvent.KEYCODE_HOME
            "MENU" -> KeyEvent.KEYCODE_MENU
            "CHANNEL_UP" -> KeyEvent.KEYCODE_CHANNEL_UP
            "CHANNEL_DOWN" -> KeyEvent.KEYCODE_CHANNEL_DOWN
            "VOLUME_UP" -> KeyEvent.KEYCODE_VOLUME_UP
            "VOLUME_DOWN" -> KeyEvent.KEYCODE_VOLUME_DOWN
            else -> {
                Log.w(TAG, "KEY_EVENT unknown keycode: $keyName")
                return
            }
        }
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        try {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            Log.i(TAG, "KEY_EVENT dispatched keycode=$keyName")
        } catch (e: Exception) {
            Log.w(TAG, "KEY_EVENT dispatch failed keycode=$keyName", e)
        }
    }

    private fun setSleepTimer(minutes: Int) {
        if (minutes <= 0) {
            Log.w(TAG, "SLEEP_TIMER invalid minutes=$minutes")
            return
        }
        sleepTimerRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            dispatchMediaControlKey(am, KeyEvent.KEYCODE_MEDIA_PAUSE)
            performGlobalAction(GLOBAL_ACTION_HOME)
            Log.i(TAG, "SLEEP_TIMER executed after ${minutes}m")
        }
        sleepTimerRunnable = runnable
        mainHandler.postDelayed(runnable, minutes.toLong() * 60_000L)
        Log.i(TAG, "SLEEP_TIMER set minutes=$minutes")
    }

    companion object {
        private const val TAG = "TvClawA11y"
        @Volatile
        var instance: TvClawAccessibilityService? = null

        fun deliverEnvelope(json: String): Boolean {
            val svc = instance ?: run {
                Log.w(TAG, "deliverEnvelope: accessibility service not running")
                return false
            }
            svc.enqueueEnvelopeJson(json)
            return true
        }
    }
}
