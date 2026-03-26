package com.parth.mobileclaw.bridge

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.android.gms.location.LocationServices
import com.parth.mobileclaw.models.ToolResult
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Bridge to Android device hardware and OS features.
 * Replaces iOS ShortcutsBridge with direct native API access.
 *
 * Much more powerful than iOS Shortcuts — no middleman needed.
 */
class DeviceBridge(private val context: Context) {

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
}
