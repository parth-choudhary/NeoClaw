package com.parth.mobileclaw.ui.utils

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.OpenableColumns
import com.parth.mobileclaw.bridge.DeviceBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class Attachment(
    val type: String, // "image", "photo", "document", "contact", "location"
    val content: String, // Details for LLM
    val id: String = UUID.randomUUID().toString(),
    val displayUri: Uri? = null,
    val name: String = ""
)

suspend fun copyAttachmentToDocuments(
    context: Context,
    uri: Uri,
    type: String
): Attachment {
    return withContext(Dispatchers.IO) {
        val fileName = getFileName(context, uri) ?: "attachment_${System.currentTimeMillis()}"
        val docsDir = File(context.getExternalFilesDir(null), "Documents")
        docsDir.mkdirs()
        val destFile = File(docsDir, fileName)
        
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        val displayUri = if (type == "image" || type == "photo") uri else Uri.fromFile(destFile)
        
        Attachment(
            type = type,
            content = "Attached $type: Documents/$fileName",
            displayUri = displayUri,
            name = fileName
        )
    }
}

suspend fun processContact(
    context: Context,
    uri: Uri
): Attachment {
    return withContext(Dispatchers.IO) {
        var name = "Contact"
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                if (nameIndex != -1) {
                    name = it.getString(nameIndex)
                }
            }
        }
        Attachment(
            type = "contact",
            content = "Attached contact: $name",
            name = name
        )
    }
}

suspend fun fetchAndAppendLocation(
    context: Context
): Attachment {
    val bridge = DeviceBridge(context)
    val result = bridge.getLocation()
    return Attachment(
        type = "location",
        content = result.output,
        name = "Location"
    )
}

private fun getFileName(context: Context, uri: Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    result = cursor.getString(index)
                }
            }
        }
    }
    if (result == null) {
        result = uri.path?.let { File(it).name }
    }
    return result
}
