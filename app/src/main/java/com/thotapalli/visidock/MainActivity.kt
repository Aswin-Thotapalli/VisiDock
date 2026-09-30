package com.thotapalli.visidock

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import android.os.Build

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val navy=0xFF071D49.toInt()
        enableEdgeToEdge(statusBarStyle=SystemBarStyle.auto(android.graphics.Color.TRANSPARENT,navy),
            navigationBarStyle=SystemBarStyle.auto(0xFFF4F8FF.toInt(),navy))
        if(Build.VERSION.SDK_INT>=29) window.isNavigationBarContrastEnforced=false
        // Demo has only disposable sample data and can be captured for design reviews.
        if (!BuildConfig.DEMO) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent { VisiDockTheme { VaultScreen() } }
    }
}
