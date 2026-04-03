package com.parth.mobileclaw.bridge

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.location.Location
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import androidx.core.content.FileProvider
import com.google.android.gms.location.LocationServices
import com.parth.mobileclaw.models.ToolResult
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Bridge to Android device hardware and OS features.
 * Replaces iOS ShortcutsBridge with direct native API access.
 *
 * Much more powerful than iOS Shortcuts — no middleman needed.
 */
class DeviceBridge(private val context: Context) {

    // Track flashlight state
    private var isFlashlightOn = false

    // MARK: - Clipboard

    fun getClipboard(): ToolResult {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        return ToolResult(
            id = UUID.randomUUID().toString(),
            output = text ?: "(clipboard is empty)"
        )
    }

    fun setClipboard(text: String): ToolResult {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("MobileClaw", text))
        return ToolResult(
            id = UUID.randomUUID().toString(),
            output = "Copied ${text.length} chars to clipboard"
        )
    }

    // MARK: - Device Info

    fun getDeviceInfo(): ToolResult {
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryLevel = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = battery.isCharging

        val statFs = StatFs(Environment.getDataDirectory().absolutePath)
        val freeSpace = statFs.availableBytes / (1024 * 1024)
        val totalSpace = statFs.totalBytes / (1024 * 1024)

        val info = buildString {
            appendLine("📱 Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("🤖 Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("🔋 Battery: ${batteryLevel}%${if (isCharging) " ⚡ Charging" else ""}")
            appendLine("💾 Storage: ${freeSpace}MB free / ${totalSpace}MB total")
            appendLine("🏗️ Architecture: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        }

        return ToolResult(
            id = UUID.randomUUID().toString(),
            output = info.trimEnd()
        )
    }

    // MARK: - Location

    @SuppressLint("MissingPermission")
    suspend fun getLocation(): ToolResult {
        return try {
            val fusedClient = LocationServices.getFusedLocationProviderClient(context)
            val location = suspendCancellableCoroutine<Location?> { cont ->
                fusedClient.lastLocation
                    .addOnSuccessListener { loc -> cont.resume(loc) }
                    .addOnFailureListener { cont.resume(null) }
            }

            if (location != null) {
                ToolResult(
                    id = UUID.randomUUID().toString(),
                    output = "📍 Location: ${location.latitude}, ${location.longitude}\n" +
                            "Accuracy: ${location.accuracy}m\n" +
                            "Altitude: ${location.altitude}m"
                )
            } else {
                ToolResult(
                    id = UUID.randomUUID().toString(),
                    output = "Location unavailable. Ensure location permission is granted and GPS is enabled.",
                    exitCode = 1,
                    isError = true
                )
            }
        } catch (e: Exception) {
            ToolResult(
                id = UUID.randomUUID().toString(),
                output = "Location error: ${e.message}",
                exitCode = 1,
                isError = true
            )
        }
    }

    // MARK: - App Launcher

    fun launchApp(packageName: String): ToolResult {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        return if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ToolResult(
                id = UUID.randomUUID().toString(),
                output = "Launched $packageName"
            )
        } else {
            // Try search by name
            val searchIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$packageName"))
            searchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ToolResult(
                id = UUID.randomUUID().toString(),
                output = "App not found: $packageName",
                exitCode = 1,
                isError = true
            )
        }
    }

    // MARK: - URL Opening

    fun openUrl(url: String): ToolResult {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ToolResult(
                id = UUID.randomUUID().toString(),
                output = "Opened: $url"
            )
        } catch (e: Exception) {
            ToolResult(
                id = UUID.randomUUID().toString(),
                output = "Failed to open URL: ${e.message}",
                exitCode = 1,
                isError = true
            )
        }
    }

    // MARK: - Share File

    fun shareFile(filePath: String): ToolResult {
        val file = File(filePath)
        if (!file.exists()) {
            return ToolResult(
                id = UUID.randomUUID().toString(),
                output = "File not found: $filePath",
                exitCode = 1,
                isError = true
            )
        }

        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share via").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            ToolResult(
                id = UUID.randomUUID().toString(),
                output = "Share sheet opened for ${file.name}"
            )
        } catch (e: Exception) {
            ToolResult(
                id = UUID.randomUUID().toString(),
                output = "Share failed: ${e.message}",
                exitCode = 1,
                isError = true
            )
        }
    }

    // MARK: - List Installed Apps

    fun listInstalledApps(): ToolResult {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .sortedBy { pm.getApplicationLabel(it).toString() }
            .take(50)

        val listing = apps.joinToString("\n") { app ->
            "📱 ${pm.getApplicationLabel(app)} (${app.packageName})"
        }

        return ToolResult(
            id = UUID.randomUUID().toString(),
            output = listing
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // MARK: - Hardware Controls (New — matching AI Edge Gallery)
    // ═══════════════════════════════════════════════════════════════

    // MARK: - Flashlight

    fun toggleFlashlight(turnOn: Boolean): ToolResult {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull()
                ?: return ToolResult(
                    id = UUID.randomUUID().toString(),
                    output = "No camera found on device",
                    exitCode = 1, isError = true
                )

            // Check if device has flash
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
                return ToolResult(
                    id = UUID.randomUUID().toString(),
                    output = "Device does not have a camera flash",
                    exitCode = 1, isError = true
                )
            }

            cameraManager.setTorchMode(cameraId, turnOn)
            isFlashlightOn = turnOn

            ToolResult(
                id = UUID.randomUUID().toString(),
                output = "🔦 Flashlight turned ${if (turnOn) "ON" else "OFF"}"
            )
        } catch (e: Exception) {
            ToolResult(
                id = UUID.randomUUID().toString(),
                output = "Flashlight error: ${e.message}",
                exitCode = 1, isError = true
            )
        }
    }

    // MARK: - Create Contact

    fun createContact(name: String, phone: String?, email: String?): ToolResult {
        return try {
            val ops = ArrayList<ContentProviderOperation>()

            // Insert raw contact
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                    .build()
            )

            // Add display name
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                    .build()
            )

            // Add phone number if provided
            if (!phone.isNullOrBlank()) {
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                        .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                        .build()
                )
            }

            // Add email if provided
            if (!email.isNullOrBlank()) {
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Email.DATA, email)
                        .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_WORK)
                        .build()
                )
            }

            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)

            val details = buildString {
                append("👤 Contact created: $name")
                if (!phone.isNullOrBlank()) append("\n📞 Phone: $phone")
                if (!email.isNullOrBlank()) append("\n📧 Email: $email")
            }

            ToolResult(id = UUID.randomUUID().toString(), output = details)
        } catch (e: Exception) {
            ToolResult(
                id = UUID.randomUUID().toString(),
                output = "Failed to create contact: ${e.message}",
                exitCode = 1, isError = true
            )
        }
    }

    // MARK: - Create Calendar Event

    @SuppressLint("MissingPermission")
    fun createCalendarEvent(
        title: String,
        description: String?,
        location: String?,
        startTimeMs: Long?,
        endTimeMs: Long?,
        allDay: Boolean
    ): ToolResult {
        return try {
            // Find the primary calendar
            val calendarId = getPrimaryCalendarId()
                ?: return ToolResult(
                    id = UUID.randomUUID().toString(),
                    output = "No calendar account found. Please add a Google account to use calendar features.",
                    exitCode = 1, isError = true
                )

            val now = System.currentTimeMillis()
            val defaultStart = startTimeMs ?: (now + 3600_000) // default: 1 hour from now
            val defaultEnd = endTimeMs ?: (defaultStart + 3600_000)  // default: 1 hour duration

            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, defaultStart)
                put(CalendarContract.Events.DTEND, defaultEnd)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                if (!description.isNullOrBlank()) put(CalendarContract.Events.DESCRIPTION, description)
                if (!location.isNullOrBlank()) put(CalendarContract.Events.EVENT_LOCATION, location)
                if (allDay) put(CalendarContract.Events.ALL_DAY, 1)
            }

            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)

            if (uri != null) {
                val sdf = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())
                val details = buildString {
                    append("📅 Event created: $title")
                    append("\n🕐 Start: ${sdf.format(Date(defaultStart))}")
                    append("\n🕐 End: ${sdf.format(Date(defaultEnd))}")
                    if (!location.isNullOrBlank()) append("\n📍 Location: $location")
                    if (!description.isNullOrBlank()) append("\n📝 $description")
                }
                ToolResult(id = UUID.randomUUID().toString(), output = details)
            } else {
                ToolResult(
                    id = UUID.randomUUID().toString(),
                    output = "Failed to insert calendar event",
                    exitCode = 1, isError = true
                )
            }
        } catch (e: Exception) {
            ToolResult(
                id = UUID.randomUUID().toString(),
                output = "Calendar error: ${e.message}",
                exitCode = 1, isError = true
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun getPrimaryCalendarId(): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID)
        val selection = "${CalendarContract.Calendars.VISIBLE} = 1 AND ${CalendarContract.Calendars.IS_PRIMARY} = 1"

        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection, selection, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(0)
            }
        }

        // Fallback: just grab the first visible calendar
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.VISIBLE} = 1",
            null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(0)
            }
        }

        return null
    }

    // MARK: - Set Alarm

    fun setAlarm(hour: Int, minute: Int, label: String?): ToolResult {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)

            val amPm = if (hour >= 12) "PM" else "AM"
            val displayHour = if (hour > 12) hour - 12 else if (hour == 0) 12 else hour
            val displayMin = String.format("%02d", minute)

            ToolResult(
                id = UUID.randomUUID().toString(),
                output = "⏰ Alarm set for $displayHour:$displayMin $amPm${if (!label.isNullOrBlank()) " — $label" else ""}"
            )
        } catch (e: Exception) {
            ToolResult(
                id = UUID.randomUUID().toString(),
                output = "Failed to set alarm: ${e.message}",
                exitCode = 1, isError = true
            )
        }
    }

    // MARK: - Set Timer

    fun setTimer(seconds: Int, label: String?): ToolResult {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)

            val mins = seconds / 60
            val secs = seconds % 60
            val display = if (mins > 0) "${mins}m ${secs}s" else "${secs}s"

            ToolResult(
                id = UUID.randomUUID().toString(),
                output = "⏱️ Timer set for $display${if (!label.isNullOrBlank()) " — $label" else ""}"
            )
        } catch (e: Exception) {
            ToolResult(
                id = UUID.randomUUID().toString(),
                output = "Failed to set timer: ${e.message}",
                exitCode = 1, isError = true
            )
        }
    }

    // MARK: - Send SMS

    @SuppressLint("MissingPermission")
    fun sendSms(phoneNumber: String, message: String): ToolResult {
        return try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            // Split long messages automatically
            val parts = smsManager.divideMessage(message)
            if (parts.size == 1) {
                smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            } else {
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            }

            ToolResult(
                id = UUID.randomUUID().toString(),
                output = "📱 SMS sent to $phoneNumber: \"${message.take(50)}${if (message.length > 50) "..." else ""}\""
            )
        } catch (e: Exception) {
            ToolResult(
                id = UUID.randomUUID().toString(),
                output = "SMS failed: ${e.message}",
                exitCode = 1, isError = true
            )
        }
    }

    // MARK: - Make Phone Call

    @SuppressLint("MissingPermission")
    fun makeCall(phoneNumber: String): ToolResult {
        return try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult(
                id = UUID.randomUUID().toString(),
                output = "📞 Calling $phoneNumber..."
            )
        } catch (e: Exception) {
            ToolResult(
                id = UUID.randomUUID().toString(),
                output = "Call failed: ${e.message}",
                exitCode = 1, isError = true
            )
        }
    }

    // MARK: - Take Photo (using camera intent)

    fun takePhoto(): ToolResult {
        return try {
            // Create a file to save the photo
            val photosDir = File(context.getExternalFilesDir(null), "Photos").also { it.mkdirs() }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val photoFile = File(photosDir, "IMG_$timestamp.jpg")

            val photoUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                photoFile
            )

            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)

            ToolResult(
                id = UUID.randomUUID().toString(),
                output = "📸 Camera opened. Photo will be saved to: ${photoFile.absolutePath}"
            )
        } catch (e: Exception) {
            ToolResult(
                id = UUID.randomUUID().toString(),
                output = "Camera error: ${e.message}",
                exitCode = 1, isError = true
            )
        }
    }

    // MARK: - WiFi Settings

    fun openWifiSettings(): ToolResult {
        return try {
            // Post Android 10, apps cannot directly toggle WiFi — must open settings panel
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Intent(Settings.Panel.ACTION_WIFI)
            } else {
                Intent(Settings.ACTION_WIFI_SETTINGS)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ToolResult(
                id = UUID.randomUUID().toString(),
                output = "📶 WiFi settings panel opened"
            )
        } catch (e: Exception) {
            ToolResult(
                id = UUID.randomUUID().toString(),
                output = "Failed to open WiFi settings: ${e.message}",
                exitCode = 1, isError = true
            )
        }
    }

    // MARK: - Bluetooth Settings

    fun openBluetoothSettings(): ToolResult {
        return try {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult(
                id = UUID.randomUUID().toString(),
                output = "🔵 Bluetooth settings opened"
            )
        } catch (e: Exception) {
            ToolResult(
                id = UUID.randomUUID().toString(),
                output = "Failed to open Bluetooth settings: ${e.message}",
                exitCode = 1, isError = true
            )
        }
    }

    // MARK: - Open Maps Location

    fun openMap(query: String): ToolResult {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(query)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult(
                id = UUID.randomUUID().toString(),
                output = "🗺️ Opened map for: $query"
            )
        } catch (e: Exception) {
            ToolResult(
                id = UUID.randomUUID().toString(),
                output = "Map error: ${e.message}",
                exitCode = 1, isError = true
            )
        }
    }
}
