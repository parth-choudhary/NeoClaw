package com.parth.neoclaw.scheduling

import android.content.Context
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.app.AlarmManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type
import com.parth.neoclaw.models.ScheduledTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Manages scheduled recurring tasks.
 * Uses JSON file persistence + WorkManager for background execution.
 */
class TaskScheduler(private val context: Context) {

    private val _tasks = MutableStateFlow<List<ScheduledTask>>(emptyList())
    val tasks: StateFlow<List<ScheduledTask>> = _tasks

    private val gson = GsonBuilder()
        .registerTypeAdapter(ScheduledTask.Recurrence::class.java, RecurrenceAdapter())
        .create()
    private val storageFile = File(context.filesDir, ".neoclaw_scheduled_tasks.json")

    var executePrompt: (suspend (String) -> String)? = null

    init {
        loadTasks()
        rescheduleAllAlarms()
    }

    private fun rescheduleAllAlarms() {
        val now = Date()
        _tasks.value.filter { it.isEnabled }.forEach { task ->
            scheduleAlarm(task)
        }
    }

    fun scheduleAlarm(task: ScheduledTask) {
        if (!task.isEnabled) return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, TaskReceiver::class.java).apply {
            putExtra("TASK_ID", task.id)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getBroadcast(context, task.id.hashCode(), intent, flags)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.nextRunDate.time, pi)
            } catch (e: SecurityException) {
                // Fallback if permission revoked
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.nextRunDate.time, pi)
            }
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, task.nextRunDate.time, pi)
        }
    }

    fun updateTask(task: ScheduledTask) {
        val current = _tasks.value.toMutableList()
        val index = current.indexOfFirst { it.id == task.id }
        if (index != -1) {
            current[index] = task
            _tasks.value = current
            saveTasks()
            scheduleAlarm(task)
        }
    }

    fun addTask(prompt: String, recurrence: ScheduledTask.Recurrence): ScheduledTask {
        val task = ScheduledTask(
            prompt = prompt,
            recurrence = recurrence,
            nextRunDate = recurrence.nextDate(Date())
        )
        _tasks.value = _tasks.value + task
        saveTasks()
        return task
    }

    fun cancelTask(id: String): Boolean {
        val current = _tasks.value.toMutableList()
        val task = current.find { it.id == id } ?: return false
        val removed = current.remove(task)
        _tasks.value = current
        saveTasks()
        
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, TaskReceiver::class.java).apply {
            putExtra("TASK_ID", id)
        }
        val pi = PendingIntent.getBroadcast(context, id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.cancel(pi)
        
        return removed
    }

    fun listFormatted(): String {
        val tasks = _tasks.value
        if (tasks.isEmpty()) return "No scheduled tasks."
        val df = SimpleDateFormat("MM/dd HH:mm", Locale.US)
        return tasks.joinToString("\n\n") { task ->
            val status = if (task.isEnabled) "✅" else "⏸️"
            val lastRun = task.lastRunDate?.let { df.format(it) } ?: "Never"
            "$status ${task.id.take(8)}\n  Prompt: ${task.prompt.take(80)}\n  Schedule: ${task.scheduleDescription}\n  Next: ${df.format(task.nextRunDate)} | Last: $lastRun"
        }
    }

    fun runOverdueTasks() {
        // Obsolete, left empty. Will be removed.
    }

    private suspend fun executeScheduledTask(task: ScheduledTask) {
        // Moved to AgentForegroundService
    }

    private fun saveTasks() {
        try { storageFile.writeText(gson.toJson(_tasks.value)) } catch (_: Exception) { }
    }

    private fun loadTasks() {
        try {
            if (storageFile.exists()) {
                val type = object : TypeToken<List<ScheduledTask>>() {}.type
                _tasks.value = gson.fromJson(storageFile.readText(), type) ?: emptyList()
            }
        } catch (_: Exception) { }
    }
}

class RecurrenceAdapter : JsonSerializer<ScheduledTask.Recurrence>, JsonDeserializer<ScheduledTask.Recurrence> {
    override fun serialize(src: ScheduledTask.Recurrence, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        val obj = JsonObject()
        when (src) {
            is ScheduledTask.Recurrence.Daily -> {
                obj.addProperty("type", "daily")
                obj.addProperty("hour", src.hour)
                obj.addProperty("minute", src.minute)
            }
            is ScheduledTask.Recurrence.Weekly -> {
                obj.addProperty("type", "weekly")
                obj.addProperty("weekday", src.weekday)
                obj.addProperty("hour", src.hour)
                obj.addProperty("minute", src.minute)
            }
            is ScheduledTask.Recurrence.EveryNHours -> {
                obj.addProperty("type", "every_n_hours")
                obj.addProperty("n", src.n)
            }
            is ScheduledTask.Recurrence.EveryNMinutes -> {
                obj.addProperty("type", "every_n_minutes")
                obj.addProperty("n", src.n)
            }
            is ScheduledTask.Recurrence.OneTime -> {
                obj.addProperty("type", "one_time")
                obj.addProperty("delay_minutes", src.delayMinutes)
            }
        }
        return obj
    }

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): ScheduledTask.Recurrence {
        val obj = json.asJsonObject
        val type = obj.get("type")?.asString ?: return ScheduledTask.Recurrence.Daily(9, 0)
        return when (type) {
            "daily" -> ScheduledTask.Recurrence.Daily(obj.get("hour").asInt, obj.get("minute").asInt)
            "weekly" -> ScheduledTask.Recurrence.Weekly(obj.get("weekday").asInt, obj.get("hour").asInt, obj.get("minute").asInt)
            "every_n_hours" -> ScheduledTask.Recurrence.EveryNHours(obj.get("n").asInt)
            "every_n_minutes" -> ScheduledTask.Recurrence.EveryNMinutes(obj.get("n").asInt)
            "one_time" -> ScheduledTask.Recurrence.OneTime(obj.get("delay_minutes").asInt)
            else -> ScheduledTask.Recurrence.Daily(9, 0)
        }
    }
}
