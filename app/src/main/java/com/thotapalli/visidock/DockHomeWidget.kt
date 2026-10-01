package com.thotapalli.visidock

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.widget.RemoteViews
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Only allow known destinations. Requests are published by MainActivity after unlocking. */
object DockLaunchRequests {
    const val EXTRA_DESTINATION="visidock.destination"
    private val state=MutableStateFlow<String?>(null)
    val pending=state.asStateFlow()
    internal fun publish(destination:String) {if(destination=="scan" || destination=="my_card" || destination=="import") state.value=destination}
    fun consume(destination:String) {state.compareAndSet(destination,null)}
}

/** No card text, names, thumbnails or contact IDs are exposed to the launcher. */
class DockHomeWidget:AppWidgetProvider() {
    override fun onUpdate(context:Context,manager:AppWidgetManager,ids:IntArray) {
        ids.forEach {id->
            val view=RemoteViews(context.packageName,R.layout.widget_visidock)
            view.setOnClickPendingIntent(R.id.widget_scan,launch(context,"scan",id*2))
            view.setOnClickPendingIntent(R.id.widget_my_card,launch(context,"my_card",id*2+1))
            manager.updateAppWidget(id,view)
        }
    }
    companion object {
        private fun intent(context:Context,destination:String)=Intent(context,MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW).putExtra(DockLaunchRequests.EXTRA_DESTINATION,destination)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        private fun launch(context:Context,destination:String,code:Int)=PendingIntent.getActivity(context,code,intent(context,destination),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun installShortcuts(context:Context) {
            runCatching {
                val manager=context.getSystemService(ShortcutManager::class.java)
                val icon=Icon.createWithResource(context,R.mipmap.ic_launcher)
                val shortcuts=listOf("scan" to "Scan card","my_card" to "My card").map {(destination,label)->
                    ShortcutInfo.Builder(context,"visidock_$destination").setShortLabel(label).setLongLabel(label)
                        .setIcon(icon).setIntent(intent(context,destination)).build()
                }
                // Keep other feature shortcuts rather than deleting them when app starts.
                manager?.addDynamicShortcuts(shortcuts)
            }
        }
    }
}
