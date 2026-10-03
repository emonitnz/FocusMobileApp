package com.example.compx551a4

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.example.compx551a4.ui.FocusNavHost
import com.example.compx551a4.ui.theme.COMPX551A4Theme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val container get() = (application as FocusApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            COMPX551A4Theme {
                FocusNavHost()
            }
        }
    }

    // Connect to the headset whenever the app is visible, so it is ready before calibration or a
    // session starts and Home can show its live status. onResume rather than onStart: the runtime
    // permission dialog only pauses the activity, and the first scan must happen after that grant.
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { container.eegSource.hold() }
    }

    override fun onStop() {
        super.onStop()
        // A rotation stops and restarts the activity; dropping BLE for that would force a rescan.
        if (isChangingConfigurations) return
        container.eegSource.release(keepConnected = container.serviceController.running.value)
    }
}