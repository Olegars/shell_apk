package ru.compclub.tvshell.kiosk

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import ru.compclub.tvshell.TvShellApp
import ru.compclub.tvshell.ui.LoginActivity
import ru.compclub.tvshell.ui.SessionActivity
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Kiosk only: keep shell in front. Network isolate is session-based (server/MikroTik).
 */
object KioskGuard : DefaultLifecycleObserver {
    private const val TAG = "KioskGuard"
    private const val DEBOUNCE_MS = 450L
    private const val PULL_INTERVAL_MS = 800L

    private val main = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean(false)
    private val inForeground = AtomicBoolean(true)
    private val pullAttempts = AtomicInteger(0)

    @Volatile
    var enabled: Boolean = true

    fun start(app: TvShellApp) {
        if (!started.compareAndSet(false, true)) return
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        Log.i(TAG, "started")
    }

    override fun onStart(owner: LifecycleOwner) {
        inForeground.set(true)
        pullAttempts.set(0)
        main.removeCallbacksAndMessages(null)
    }

    override fun onStop(owner: LifecycleOwner) {
        if (!enabled) return
        inForeground.set(false)
        main.postDelayed({
            if (inForeground.get()) return@postDelayed
            schedulePull()
        }, DEBOUNCE_MS)
    }

    private fun schedulePull() {
        if (!enabled || inForeground.get()) return
        pullAttempts.incrementAndGet()
        main.post {
            if (!inForeground.get()) pullToFront(TvShellApp.instance)
        }
        // Keep trying while backgrounded — no network isolate from here.
        main.postDelayed({
            if (!inForeground.get()) schedulePull()
        }, PULL_INTERVAL_MS)
    }

    fun pullToFront(context: Context) {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.appTasks.firstOrNull()?.moveToFront()
        } catch (e: Exception) {
            Log.w(TAG, "moveToFront failed: ${e.message}")
        }

        val target = if (TvShellApp.instance.session.state.active) {
            SessionActivity::class.java
        } else {
            LoginActivity::class.java
        }
        val intent = Intent(context, target).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "startActivity failed: ${it.message}") }
    }
}
