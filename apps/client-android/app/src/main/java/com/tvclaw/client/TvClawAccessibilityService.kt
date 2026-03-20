package com.tvclaw.client

import android.accessibilityservice.AccessibilityService
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject

class TvClawAccessibilityService : AccessibilityService() {

    private var ioThread: HandlerThread? = null
    private var ioHandler: Handler? = null

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
        val payload = envelope.optJSONObject("payload") ?: return
        val action = payload.optString("action")
        val params = payload.optJSONObject("params") ?: JSONObject()
        when (action) {
            "MEDIA_CONTROL" -> applyMediaControl(params.optString("control"))
            "LAUNCH_APP" -> {
                val pkg = params.optString("app_id")
                if (pkg.isNotEmpty()) launchPackage(pkg)
            }
            "SEARCH" -> Log.i(TAG, "SEARCH stub app_id=${params.optString("app_id")} query=${params.optString("query")}")
            "VISION_SYNC" -> Log.i(TAG, "VISION_SYNC stub (MediaProjection not wired)")
            else -> Log.w(TAG, "unknown action: $action")
        }
    }

    private fun applyMediaControl(control: String) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        when (control) {
            "MUTE" -> am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_MUTE,
                0
            )
            "PLAY", "PAUSE", "REWIND_30" -> Log.i(TAG, "MEDIA_CONTROL $control — wire KeyEvent / session when paired")
            else -> Log.w(TAG, "unknown control: $control")
        }
    }

    private fun launchPackage(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } else {
            Log.w(TAG, "no launch intent for $packageName")
        }
    }

    companion object {
        private const val TAG = "TvClawA11y"
        @Volatile
        var instance: TvClawAccessibilityService? = null

        fun postEnvelopeJson(json: String) {
            instance?.enqueueEnvelopeJson(json)
        }
    }
}
