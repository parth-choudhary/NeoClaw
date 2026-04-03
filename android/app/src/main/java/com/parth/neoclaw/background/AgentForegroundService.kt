package com.parth.neoclaw.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import com.parth.neoclaw.models.ScheduledTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Foreground Service for long-running agent tasks.
 * Replaces iOS Live Activities — keeps the app alive with a persistent notification.
 */
class AgentForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "neoclaw_agent"
        const val NOTIFICATION_ID = 1001

        var instance: AgentForegroundService? = null

        fun start(context: Context, summary: String, tool: String? = null) {
            val svc = instance
            if (svc != null) {
                svc.updateNotification(summary, tool, 0.0)
            } else {
                val intent = Intent(context, AgentForegroundService::class.java)
                intent.putExtra("summary", summary)
                tool?.let { intent.putExtra("tool", it) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        context.startForegroundService(intent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentForegroundService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        
        val notification = buildNotification("Agent Ready", null, 0.0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "EXECUTE_TASK") {
            val taskId = intent.getStringExtra("TASK_ID")
            if (taskId != null) {
                updateNotification("Executing scheduled task...", null, 0.0)
                
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val orchestrator = com.parth.neoclaw.engine.AgentOrchestrator(application)
                        val task = orchestrator.taskScheduler.tasks.value.find { it.id == taskId }
                        if (task != null && task.isEnabled) {
                            val result = orchestrator.executeScheduledPrompt(task.prompt)
                            
                            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                            val notification = NotificationCompat.Builder(this@AgentForegroundService, "neoclaw_tasks")
                                .setSmallIcon(android.R.drawable.ic_dialog_info)
                                .setContentTitle("Task: ${task.prompt.take(30)}...")
                                .setContentText(result.take(100))
                                .setStyle(NotificationCompat.BigTextStyle().bigText(result))
                                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                                .setAutoCancel(true)
                                .build()
                                
                            nm.notify(task.id.hashCode(), notification)
                            
                            
                            if (task.recurrence is ScheduledTask.Recurrence.OneTime) {
                                orchestrator.taskScheduler.cancelTask(task.id)
                            } else {
                                val now = java.util.Date()
                                val updatedTask = task.copy(lastRunDate = now, nextRunDate = task.nextRun(now))
                                orchestrator.taskScheduler.updateTask(updatedTask)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        updateNotification("Agent Ready", null, 0.0)
                    }
                }
            }
            return START_STICKY
        }

        val summary = intent?.getStringExtra("summary") ?: "Agent Ready"
        val tool = intent?.getStringExtra("tool")
        updateNotification(summary, tool, 0.0)
        
        return START_STICKY
    }

    fun updateNotification(status: String, tool: String?, progress: Double) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(status, tool, progress))
    }

    private fun buildNotification(status: String, tool: String?, progress: Double): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("NeoClaw Agent")
            .setContentText(status)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (tool != null) {
            builder.setSubText("Tool: $tool")
        }

        if (progress > 0) {
            builder.setProgress(100, (progress * 100).toInt(), false)
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Agent Tasks",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of running agent tasks"
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }
}
