package com.parth.mobileclaw.models

import java.util.Calendar
import java.util.Date
import java.util.UUID

/**
 * A scheduled recurring task.
 */
data class ScheduledTask(
    val id: String = UUID.randomUUID().toString(),
    val prompt: String,
    val recurrence: Recurrence,
    var isEnabled: Boolean = true,
    var lastRunDate: Date? = null,
    var nextRunDate: Date,
    val createdDate: Date = Date()
) {
    val scheduleDescription: String
        get() = when (recurrence) {
            is Recurrence.Daily -> "Daily at ${recurrence.hour}:${"%02d".format(recurrence.minute)}"
            is Recurrence.Weekly -> {
                val days = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
                val day = days.getOrElse(recurrence.weekday - 1) { "?" }
                "Weekly on $day at ${recurrence.hour}:${"%02d".format(recurrence.minute)}"
            }
            is Recurrence.EveryNHours -> "Every ${recurrence.n} hours"
            is Recurrence.EveryNMinutes -> "Every ${recurrence.n} minutes"
            is Recurrence.OneTime -> "Once in ${recurrence.delayMinutes} minutes"
        }

    fun nextRun(after: Date): Date = recurrence.nextDate(after)

    sealed class Recurrence {
        data class Daily(val hour: Int, val minute: Int) : Recurrence()
        data class Weekly(val weekday: Int, val hour: Int, val minute: Int) : Recurrence()
        data class EveryNHours(val n: Int) : Recurrence()
        data class EveryNMinutes(val n: Int) : Recurrence()
        data class OneTime(val delayMinutes: Int) : Recurrence()

        fun nextDate(after: Date): Date {
            val cal = Calendar.getInstance().apply { time = after }
            return when (this) {
                is Daily -> {
                    cal.set(Calendar.HOUR_OF_DAY, hour)
                    cal.set(Calendar.MINUTE, minute)
                    cal.set(Calendar.SECOND, 0)
                    if (cal.time <= after) cal.add(Calendar.DAY_OF_YEAR, 1)
                    cal.time
                }
                is Weekly -> {
                    cal.set(Calendar.DAY_OF_WEEK, weekday)
                    cal.set(Calendar.HOUR_OF_DAY, hour)
                    cal.set(Calendar.MINUTE, minute)
                    cal.set(Calendar.SECOND, 0)
                    if (cal.time <= after) cal.add(Calendar.WEEK_OF_YEAR, 1)
                    cal.time
                }
                is EveryNHours -> {
                    cal.add(Calendar.HOUR_OF_DAY, n)
                    cal.time
                }
                is EveryNMinutes -> {
                    cal.add(Calendar.MINUTE, n)
                    cal.time
                }
                is OneTime -> {
                    cal.add(Calendar.MINUTE, delayMinutes)
                    cal.time
                }
            }
        }

        companion object {
            fun from(args: Map<String, Any?>): Recurrence? {
                val type = args["type"] as? String ?: return null
                return when (type) {
                    "daily" -> Daily(
                        hour = (args["hour"] as? Number)?.toInt() ?: 9,
                        minute = (args["minute"] as? Number)?.toInt() ?: 0
                    )
                    "weekly" -> Weekly(
                        weekday = (args["weekday"] as? Number)?.toInt() ?: 2,
                        hour = (args["hour"] as? Number)?.toInt() ?: 9,
                        minute = (args["minute"] as? Number)?.toInt() ?: 0
                    )
                    "every_n_hours" -> EveryNHours(
                        n = (args["n"] as? Number)?.toInt() ?: 1
                    )
                    "every_n_minutes" -> EveryNMinutes(
                        n = (args["n"] as? Number)?.toInt() ?: 30
                    )
                    "one_time" -> OneTime(
                        delayMinutes = (args["delay_minutes"] as? Number)?.toInt() ?: 5
                    )
                    else -> null
                }
            }
        }
    }
}
