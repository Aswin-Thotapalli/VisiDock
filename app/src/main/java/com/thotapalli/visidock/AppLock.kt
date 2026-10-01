package com.thotapalli.visidock

import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

internal const val LOCK_AUTHENTICATORS=BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
internal fun Context.fragmentActivity():FragmentActivity?=when(this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.fragmentActivity()
    else -> null
}
internal fun authenticateOwner(activity:FragmentActivity,start:Boolean=true,onResult:(Boolean,String?)->Unit):BiometricPrompt {
    val prompt=BiometricPrompt(activity,ContextCompat.getMainExecutor(activity),object:BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult) {onResult(true,null)}
        override fun onAuthenticationError(errorCode:Int,errString:CharSequence) {onResult(false,errString.toString())}
    })
    if(start) prompt.authenticate(ownerPromptInfo())
    return prompt
}
internal fun ownerPromptInfo()=BiometricPrompt.PromptInfo.Builder().setTitle("Unlock VisiDock")
        .setSubtitle("Use your fingerprint, face or device screen lock")
        .setAllowedAuthenticators(LOCK_AUTHENTICATORS).build()

@Composable internal fun AppLockScreen(message:String?,authenticating:Boolean,onUnlock:()->Unit) {
    val context=LocalContext.current
    Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Lock,null,Modifier.size(48.dp),tint=MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(24.dp));Text("Your card case is locked",style=MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp));Text("Authenticate with your device to open VisiDock.",style=MaterialTheme.typography.bodyMedium)
            message?.let {Spacer(Modifier.height(16.dp));Text(it,Modifier.semantics {liveRegion=LiveRegionMode.Polite})}
            Spacer(Modifier.height(24.dp));DockButton(onUnlock,enabled=!authenticating) {Text(if(authenticating) "Waiting for authentication" else "Unlock")}
            DockTextButton({context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))}) {Text("Device security settings")}
        }
    }
}

/** Reusable settings surface. Enabling OR disabling the lock requires device authentication. */
@Composable fun SecuritySettings(modifier:Modifier=Modifier) {
    val context=LocalContext.current
    val prefs=remember {SecurityPreferences(context)}
    var lock by remember {mutableStateOf(prefs.appLock)}
    var timeout by remember {mutableIntStateOf(prefs.timeoutSeconds)}
    var privateScreen by remember {mutableStateOf(prefs.privateScreen)}
    var message by remember {mutableStateOf<String?>(null)}
    var authenticating by remember {mutableStateOf(false)}
    var prompt by remember {mutableStateOf<BiometricPrompt?>(null)}
    DisposableEffect(Unit) {onDispose {prompt?.cancelAuthentication()}}
    Column(modifier,verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Privacy and app lock",style=MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {Text("Lock VisiDock");Text("Fingerprint, face or device PIN",style=MaterialTheme.typography.bodySmall)}
            DockSwitch(lock,{desired->
                val activity=context.fragmentActivity()
                val secure=context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure==true
                if(activity==null || !secure) message="Set up a device screen lock in Android settings first."
                else if(!authenticating) {
                    authenticating=true;message=null
                    runCatching {prompt=authenticateOwner(activity) {ok,error->
                        authenticating=false
                        if(ok) {prefs.appLock=desired;lock=desired} else message=error
                    }}.onFailure {authenticating=false;message="Authentication is unavailable. Check device security settings."}
                }
            },enabled=!authenticating)
        }
        if(lock) {
            Text("Lock after leaving the app",style=MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Immediately",30 to "30 seconds",300 to "5 minutes").forEach {(seconds,label)->
                    DockFilterChip(timeout==seconds,{prefs.timeoutSeconds=seconds;timeout=seconds},{Text(label)})
                }
            }
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {Text("Conceal screen content");Text("Hide previews in Recents and prevent screenshots",style=MaterialTheme.typography.bodySmall)}
            DockSwitch(privateScreen,{privateScreen=it;prefs.privateScreen=it})
        }
        Text("Home-screen shortcuts and the widget show no names, photographs or contact details.",style=MaterialTheme.typography.bodySmall)
        message?.let {Text(it,Modifier.semantics {liveRegion=LiveRegionMode.Polite},color=MaterialTheme.colorScheme.error)}
        DockTextButton({context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))}) {Text("Device security settings")}
    }
}
