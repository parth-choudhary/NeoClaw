package com.parth.neoclaw.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.parth.neoclaw.background.AgentForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class TaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Triggered by AlarmManager. We start the foreground service to handle processing safely.
        val taskId = intent.getStringExtra("TASK_ID") ?: return
        
        // Start foreground service to keep process alive during LLM query
        AgentForegroundService.start(context, "Executing scheduled task...")
        
        // We need an entry point to the orchestrator. Since BroadcastReceivers don't have
        // access to ViewModels, we should route this via an intent to a dedicated Service,
        // or a singleton orchestrator. The best way in Android without DI is to let the
        // ForegroundService spin up the LLMService directly or trigger an event.
        // Let's create an action in ForegroundService to process it.
        val serviceIntent = Intent(context, AgentForegroundService::class.java).apply {
            action = "EXECUTE_TASK"
            putExtra("TASK_ID", taskId)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
