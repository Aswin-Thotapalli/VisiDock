package com.thotapalli.visidock

import android.content.Context
import android.content.SharedPreferences

/** No contact data is stored in the security settings. Private recents are the default. */
class SecurityPreferences(context:Context) {
    private val prefs=context.applicationContext.getSharedPreferences("security",Context.MODE_PRIVATE)
    var appLock:Boolean
        get()=prefs.getBoolean("app_lock",false)
        set(value) {prefs.edit().putBoolean("app_lock",value).apply()}
    var timeoutSeconds:Int
        get()=prefs.getInt("timeout",30).coerceIn(0,300)
        set(value) {prefs.edit().putInt("timeout",value.coerceIn(0,300)).apply()}
    var privateScreen:Boolean
        get()=prefs.getBoolean("private_screen",true)
        set(value) {prefs.edit().putBoolean("private_screen",value).apply()}
    fun observe(listener:SharedPreferences.OnSharedPreferenceChangeListener) {prefs.registerOnSharedPreferenceChangeListener(listener)}
    fun remove(listener:SharedPreferences.OnSharedPreferenceChangeListener) {prefs.unregisterOnSharedPreferenceChangeListener(listener)}
}

internal object LockPolicy {
    fun needsAuthentication(enabled:Boolean,coldStart:Boolean,backgroundElapsed:Long,timeoutSeconds:Int)=
        enabled && (coldStart || backgroundElapsed>=timeoutSeconds.coerceIn(0,300)*1000L)
}
