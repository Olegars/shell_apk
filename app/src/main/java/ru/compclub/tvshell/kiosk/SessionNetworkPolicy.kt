package ru.compclub.tvshell.kiosk

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.util.Log
import ru.compclub.tvshell.TvShellApp
import ru.compclub.tvshell.data.ShellApi
import java.net.NetworkInterface
import java.util.concurrent.Executors

/**
 * Session-based internet policy: idle → MikroTik isolate, active → restore.
 * Server also queues this on login/logout for TV terminals.
 */
object SessionNetworkPolicy {
    private const val TAG = "SessionNetPolicy"
    private val io = Executors.newSingleThreadExecutor()

    fun onSessionActive() = report("session_active")

    fun onSessionIdle() = report("session_idle")

    private fun report(state: String) {
        val prefs = TvShellApp.instance.prefs
        if (!prefs.isRegistered()) return
        val mac = lanMac()
        val ip = lanIp(TvShellApp.instance)
        io.execute {
            runCatching {
                ShellApi(prefs).reportUiState(
                    state = state,
                    macAddress = mac,
                    lanIp = ip,
                )
            }.onFailure { Log.w(TAG, "$state failed: ${it.message}") }
        }
    }

    private fun lanMac(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.asSequence()
            ?.filter { ni ->
                val n = ni.name.lowercase()
                (n.startsWith("wlan") || n.startsWith("eth")) && !ni.isLoopback
            }
            ?.mapNotNull { ni ->
                val b = ni.hardwareAddress ?: return@mapNotNull null
                if (b.size < 6) return@mapNotNull null
                b.joinToString(":") { "%02X".format(it) }
            }
            ?.firstOrNull { it != "00:00:00:00:00:00" }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun lanIp(context: Context): String? = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val link = cm.activeNetwork ?: return null
        val props = cm.getLinkProperties(link) ?: return null
        props.linkAddresses
            .mapNotNull { it.address?.hostAddress }
            .firstOrNull { !it.contains(':') && it != "0.0.0.0" }
            ?: run {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val ip = wm.connectionInfo.ipAddress
                if (ip == 0) null
                else String.format(
                    "%d.%d.%d.%d",
                    ip and 0xff,
                    ip shr 8 and 0xff,
                    ip shr 16 and 0xff,
                    ip shr 24 and 0xff,
                )
            }
    }.getOrNull()
}
