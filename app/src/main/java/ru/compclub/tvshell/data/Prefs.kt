package ru.compclub.tvshell.data

import android.content.Context
import ru.compclub.tvshell.BuildConfig

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("tv_shell", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString(KEY_SERVER, BuildConfig.DEFAULT_SERVER_URL)?.trim().orEmpty()
        set(value) = sp.edit().putString(KEY_SERVER, value.trim().trimEnd('/')).apply()

    var terminalId: Int
        get() = sp.getInt(KEY_TERMINAL, 0)
        set(value) = sp.edit().putInt(KEY_TERMINAL, value).apply()

    var stationName: String
        get() = sp.getString(KEY_NAME, "")?.trim().orEmpty()
        set(value) = sp.edit().putString(KEY_NAME, value.trim()).apply()

    var zoneType: String
        get() = sp.getString(KEY_ZONE, "tv")?.trim().orEmpty().ifBlank { "tv" }
        set(value) = sp.edit().putString(KEY_ZONE, value.trim().lowercase()).apply()

    var adminPin: String
        get() = sp.getString(KEY_ADMIN_PIN, BuildConfig.DEFAULT_ADMIN_PIN)?.trim().orEmpty()
            .ifBlank { BuildConfig.DEFAULT_ADMIN_PIN }
        set(value) = sp.edit().putString(KEY_ADMIN_PIN, value.trim()).apply()

    /**
     * Auto HDMI switch: session → [hdmiSessionOrdinal] (PS), idle → [hdmiIdleOrdinal].
     * Set idle ordinal to 0 to skip parking on HDMI after logout (Android login only).
     */
    var hdmiAutoSwitch: Boolean
        get() = sp.getBoolean(KEY_HDMI_AUTO, true)
        set(value) = sp.edit().putBoolean(KEY_HDMI_AUTO, value).apply()

    /** 1-based HDMI index for active session (default 1 = HDMI1 / PS). */
    var hdmiSessionOrdinal: Int
        get() = sp.getInt(KEY_HDMI_SESSION, 1).coerceAtLeast(1)
        set(value) = sp.edit().putInt(KEY_HDMI_SESSION, value.coerceAtLeast(1)).apply()

    /** 1-based HDMI index after session end (default 2). 0 = do not switch. */
    var hdmiIdleOrdinal: Int
        get() = sp.getInt(KEY_HDMI_IDLE, 2).coerceAtLeast(0)
        set(value) = sp.edit().putInt(KEY_HDMI_IDLE, value.coerceAtLeast(0)).apply()

    fun hasServerUrl(): Boolean =
        serverUrl.isNotBlank() && !serverUrl.contains("your-club.example")

    fun isRegistered(): Boolean = hasServerUrl() && terminalId > 0

    /** Alias used by login screen. */
    fun isConfigured(): Boolean = isRegistered()

    companion object {
        private const val KEY_SERVER = "server_url"
        private const val KEY_TERMINAL = "terminal_id"
        private const val KEY_NAME = "station_name"
        private const val KEY_ZONE = "zone_type"
        private const val KEY_ADMIN_PIN = "admin_pin"
        private const val KEY_HDMI_AUTO = "hdmi_auto_switch"
        private const val KEY_HDMI_SESSION = "hdmi_session_ordinal"
        private const val KEY_HDMI_IDLE = "hdmi_idle_ordinal"
    }
}
