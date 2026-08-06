package ru.compclub.tvshell.command

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import ru.compclub.tvshell.BuildConfig
import ru.compclub.tvshell.R
import ru.compclub.tvshell.TvShellApp
import ru.compclub.tvshell.data.ShellApi
import ru.compclub.tvshell.kiosk.SessionNetworkPolicy
import ru.compclub.tvshell.ui.LoginActivity
import ru.compclub.tvshell.ui.SessionActivity

/**
 * LAN command API for backend / MikroTik / admin tools.
 *
 * GET  /health
 * POST /command  { "action": "show_message"|"session_end"|"ping"|"open_login", "text"?: "..." }
 */
class CommandService : Service() {
    private var server: CommandHttpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        val port = BuildConfig.COMMAND_PORT
        server = CommandHttpServer(port).also {
            runCatching { it.start() }
                .onSuccess { Log.i(TAG, "Command server on :$port") }
                .onFailure { e -> Log.e(TAG, "Command server failed", e) }
        }
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val channelId = "tv_shell_cmd"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(
                NotificationChannel(channelId, "TV Shell commands", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, LoginActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.command_port, BuildConfig.COMMAND_PORT))
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "TvShellCmd"
        private const val NOTIF_ID = 42

        fun start(context: Context) {
            val i = Intent(context, CommandService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }
    }
}

private class CommandHttpServer(port: Int) : NanoHTTPD(port) {
    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/')
        return when {
            session.method == Method.GET && (uri == "/health" || uri == "/") ->
                json(Response.Status.OK, """{"status":"ok","app":"tvshell"}""")

            session.method == Method.POST && uri == "/command" -> handleCommand(session)

            else -> json(Response.Status.NOT_FOUND, """{"status":"error","message":"not found"}""")
        }
    }

    private fun handleCommand(session: IHTTPSession): Response {
        val map = HashMap<String, String>()
        return try {
            session.parseBody(map)
            val raw = map["postData"] ?: session.queryParameterString.orEmpty()
            val obj = JSONObject(if (raw.isBlank()) "{}" else raw)
            val action = obj.optString("action").lowercase()
            val text = obj.optString("text")
            when (action) {
                "ping" -> json(Response.Status.OK, """{"status":"ok","action":"ping"}""")
                "show_message" -> {
                    TvShellApp.instance.session.update { it.copy(bannerMessage = text) }
                    json(Response.Status.OK, """{"status":"ok","action":"show_message"}""")
                }
                "clear_message" -> {
                    TvShellApp.instance.session.update { it.copy(bannerMessage = "") }
                    json(Response.Status.OK, """{"status":"ok","action":"clear_message"}""")
                }
                "session_end", "logout" -> {
                    val prefs = TvShellApp.instance.prefs
                    Thread {
                        runCatching { ShellApi(prefs).logout() }
                        SessionNetworkPolicy.onSessionIdle()
                    }.start()
                    TvShellApp.instance.session.clear()
                    val ctx = TvShellApp.instance
                    ctx.startActivity(
                        Intent(ctx, LoginActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    )
                    json(Response.Status.OK, """{"status":"ok","action":"session_end"}""")
                }
                "open_login" -> {
                    val ctx = TvShellApp.instance
                    ctx.startActivity(
                        Intent(ctx, LoginActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    )
                    json(Response.Status.OK, """{"status":"ok","action":"open_login"}""")
                }
                "open_session" -> {
                    if (!TvShellApp.instance.session.state.active) {
                        return json(
                            Response.Status.BAD_REQUEST,
                            """{"status":"error","message":"no active session"}"""
                        )
                    }
                    val ctx = TvShellApp.instance
                    ctx.startActivity(
                        Intent(ctx, SessionActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    )
                    json(Response.Status.OK, """{"status":"ok","action":"open_session"}""")
                }
                else -> json(
                    Response.Status.BAD_REQUEST,
                    """{"status":"error","message":"unknown action"}"""
                )
            }
        } catch (e: Exception) {
            json(
                Response.Status.INTERNAL_ERROR,
                JSONObject().put("status", "error").put("message", e.message ?: "fail").toString()
            )
        }
    }

    private fun json(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "application/json", body)
}
