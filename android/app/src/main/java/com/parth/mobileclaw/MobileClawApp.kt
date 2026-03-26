package com.parth.mobileclaw

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class MobileClawApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            // Agent task channel
            nm.createNotificationChannel(
                NotificationChannel(
                    "mobileclaw_agent",
                    "Agent Tasks",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows progress of running agent tasks"
                }
            )

            // Scheduled task results channel
            nm.createNotificationChannel(
                NotificationChannel(
                    "mobileclaw_tasks",
                    "Scheduled Tasks",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Results from scheduled recurring tasks"
                }
            )
        }
    }
}
