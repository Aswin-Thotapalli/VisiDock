package com.thotapalli.visidock

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import android.os.SystemClock
import android.content.SharedPreferences
import androidx.biometric.BiometricPrompt
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

internal class AppLockSession:ViewModel() {
    var initialized=false
    var locked by mutableStateOf(false)
    var authenticating by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var backgroundAt:Long?=null
    var destination:String?=null
}

class MainActivity : FragmentActivity() {
    private val lockSession:AppLockSession by viewModels()
    private lateinit var security:SecurityPreferences
    private lateinit var authentication:BiometricPrompt
    private val securityListener=SharedPreferences.OnSharedPreferenceChangeListener {_,_->applyScreenPrivacy()}
    private fun applyScreenPrivacy() {
        if(!BuildConfig.DEMO && (security.privateScreen || lockSession.locked)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    private fun acceptDestination(intent:Intent?) {
        intent?.getStringExtra(DockLaunchRequests.EXTRA_DESTINATION)?.takeIf {it=="scan" || it=="my_card" || it=="import"}?.let {
            lockSession.destination=it
            intent.removeExtra(DockLaunchRequests.EXTRA_DESTINATION)
        }
        lockSession.backgroundAt?.let {at->
            if(LockPolicy.needsAuthentication(security.appLock,false,SystemClock.elapsedRealtime()-at,security.timeoutSeconds)) lockSession.locked=true
        }
        if(!lockSession.locked) publishDestination()
    }
    private fun publishDestination() {lockSession.destination?.let {DockLaunchRequests.publish(it)};lockSession.destination=null}
    private fun attachAuthentication() {
        authentication=authenticateOwner(this,start=false) {ok,error->
            lockSession.authenticating=false
            if(ok) {lockSession.locked=false;lockSession.backgroundAt=null;applyScreenPrivacy();publishDestination()}
            else lockSession.message=error
        }
    }
    private fun unlock() {
        if(lockSession.authenticating) return
        attachAuthentication()
        lockSession.authenticating=true;lockSession.message=null
        runCatching {authentication.authenticate(ownerPromptInfo())}.onFailure {
            lockSession.authenticating=false
            lockSession.message="Authentication is unavailable. Use device security settings to check your screen lock."
        }
    }
    override fun onNewIntent(intent:Intent) {super.onNewIntent(intent);setIntent(intent);acceptDestination(intent);acceptIncoming(intent)}
    private fun acceptIncoming(intent:Intent) {
        val owner=if(BuildConfig.DEMO) "demo" else runCatching {com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid}.getOrNull()
        IncomingImports.accept(applicationContext,intent,owner) {
            lockSession.destination="import"
            if(!lockSession.locked) publishDestination()
        }
    }
    override fun onResume() {
        super.onResume()
        if(!::security.isInitialized) return
        lockSession.backgroundAt?.let {at->
            if(LockPolicy.needsAuthentication(security.appLock,false,SystemClock.elapsedRealtime()-at,security.timeoutSeconds)) lockSession.locked=true
        }
        lockSession.backgroundAt=null
        if(!security.appLock) lockSession.locked=false
        applyScreenPrivacy()
        if(lockSession.locked) unlock() else publishDestination()
    }
    override fun onStop() {
        if(!isChangingConfigurations) {
            lockSession.backgroundAt=SystemClock.elapsedRealtime()
            if(::security.isInitialized && security.appLock && security.timeoutSeconds==0) lockSession.locked=true
        }
        super.onStop()
    }
    override fun onDestroy() {if(::security.isInitialized) security.remove(securityListener);super.onDestroy()}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        security=SecurityPreferences(this)
        if(!lockSession.initialized) {lockSession.locked=security.appLock;lockSession.initialized=true}
        attachAuthentication()
        security.observe(securityListener)
        acceptDestination(intent)
        if(savedInstanceState==null) acceptIncoming(intent)
        DockHomeWidget.installShortcuts(this)
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
        applyScreenPrivacy()
        setContent { VisiDockTheme {
            val savedUi=rememberSaveableStateHolder()
            if(lockSession.locked) AppLockScreen(lockSession.message,lockSession.authenticating,::unlock)
            else savedUi.SaveableStateProvider("vault") {VaultScreen()}
        } }
    }
}
