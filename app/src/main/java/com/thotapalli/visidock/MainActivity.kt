package com.thotapalli.visidock

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import android.os.Build
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.PathInterpolator

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if(Build.VERSION.SDK_INT>=31) splashScreen.setOnExitAnimationListener {splash ->
            // Android invokes this when content can draw: never hold launch for a timer.
            if(!ValueAnimator.areAnimatorsEnabled()) splash.remove()
            else {
                var removed=false
                fun finish() {if(!removed) {removed=true;splash.remove()}}
                val animations=mutableListOf<Animator>(ObjectAnimator.ofFloat(splash,View.ALPHA,1f,0f))
                splash.iconView?.let {icon ->
                    animations+=ObjectAnimator.ofFloat(icon,View.SCALE_X,1f,.9f)
                    animations+=ObjectAnimator.ofFloat(icon,View.SCALE_Y,1f,.9f)
                    animations+=ObjectAnimator.ofFloat(icon,View.TRANSLATION_Y,0f,-16f*resources.displayMetrics.density)
                }
                AnimatorSet().apply {
                    playTogether(animations)
                    duration=190
                    interpolator=PathInterpolator(.2f,0f,0f,1f)
                    addListener(object:AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation:Animator)=finish()
                        override fun onAnimationCancel(animation:Animator)=finish()
                    })
                    start()
                }
            }
        }
        val navy=0xFF071D49.toInt()
        enableEdgeToEdge(statusBarStyle=SystemBarStyle.auto(android.graphics.Color.TRANSPARENT,navy),
            navigationBarStyle=SystemBarStyle.auto(0xFFF4F8FF.toInt(),navy))
        if(Build.VERSION.SDK_INT>=29) window.isNavigationBarContrastEnforced=false
        // Demo has only disposable sample data and can be captured for design reviews.
        if (!BuildConfig.DEMO) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent { VisiDockTheme { VaultScreen() } }
    }
}
