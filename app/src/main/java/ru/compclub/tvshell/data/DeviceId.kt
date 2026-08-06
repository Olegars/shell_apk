package ru.compclub.tvshell.data

import android.content.Context
import android.provider.Settings
import java.util.UUID

object DeviceId {
    fun hwid(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.trim()
            ?.lowercase()
            .orEmpty()
        if (androidId.isNotBlank() && androidId != "9774d56d682e549c") {
            return "tv-$androidId"
        }
        val sp = context.getSharedPreferences("tv_shell", Context.MODE_PRIVATE)
        val existing = sp.getString("fallback_hwid", null)
        if (!existing.isNullOrBlank()) return existing
        val generated = "tv-${UUID.randomUUID()}"
        sp.edit().putString("fallback_hwid", generated).apply()
        return generated
    }
}
