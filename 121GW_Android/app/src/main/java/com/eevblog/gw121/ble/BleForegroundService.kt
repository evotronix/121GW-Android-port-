package com.eevblog.gw121.ble

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.eevblog.gw121.R

class BleForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channelId = "121gw_ble"
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(channelId, "121GW Bluetooth", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val n = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("121GW")
            .setContentText("Connected to meter")
            .setOngoing(true)
            .build()
        startForeground(121, n)
    }

    companion object {
        fun start(ctx: Context) {
            val i = Intent(ctx, BleForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, BleForegroundService::class.java))
        }
    }
}
