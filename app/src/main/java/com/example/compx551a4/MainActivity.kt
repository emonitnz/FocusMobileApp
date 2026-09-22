package com.example.compx551a4

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.compx551a4.ui.FocusNavHost
import com.example.compx551a4.ui.theme.COMPX551A4Theme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            COMPX551A4Theme {
                FocusNavHost()
            }
        }
    }
}
