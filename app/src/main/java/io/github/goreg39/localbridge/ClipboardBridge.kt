package io.github.goreg39.localbridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

class ClipboardBridge(private val context: Context) {
    private val clipboard: ClipboardManager
        get() = context.getSystemService(ClipboardManager::class.java)

    fun writeText(text: String) {
        clipboard.setPrimaryClip(ClipData.newPlainText("Local Bridge", text))
    }

    fun readText(): String? {
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(context)?.toString()
    }
}
