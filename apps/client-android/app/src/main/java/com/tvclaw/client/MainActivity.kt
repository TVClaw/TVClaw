package com.tvclaw.client

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tvclaw.client.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.openAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.connectBridge.setOnClickListener {
            ContextCompat.startForegroundService(
                this,
                Intent(this, TvClawBridgeService::class.java).apply {
                    action = TvClawBridgeService.ACTION_START
                    putExtra(TvClawBridgeService.EXTRA_WS_URL, BuildConfig.TV_BRAIN_WS_URL)
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
    }
}
