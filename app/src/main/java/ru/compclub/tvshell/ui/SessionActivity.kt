package ru.compclub.tvshell.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.compclub.tvshell.BuildConfig
import ru.compclub.tvshell.R
import ru.compclub.tvshell.TvShellApp
import ru.compclub.tvshell.data.SessionState
import ru.compclub.tvshell.data.ShellApi
import ru.compclub.tvshell.data.TimeFormat
import ru.compclub.tvshell.databinding.ActivitySessionBinding
import ru.compclub.tvshell.kiosk.KioskGuard
import ru.compclub.tvshell.kiosk.LockTaskController
import ru.compclub.tvshell.kiosk.SessionNetworkPolicy
import ru.compclub.tvshell.ui.launcher.HdmiInputs
import ru.compclub.tvshell.ui.launcher.LauncherApps
import ru.compclub.tvshell.ui.launcher.QrBitmap

class SessionActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySessionBinding
    private val session get() = TvShellApp.instance.session
    private val prefs get() = TvShellApp.instance.prefs

    private var tickJob: Job? = null
    private var pollJob: Job? = null
    private var remainingSec: Int = 0
    private val warnedAt = mutableSetOf<Int>()
    private var ending = false
    private var allowedPackages: Array<String> = emptyArray()

    private val observer: (SessionState) -> Unit = { state ->
        runOnUiThread { render(state) }
    }

    private val sosReasons = listOf(
        "peripherals" to "Проблема с пультом / звуком",
        "auth_help" to "Нужна помощь с входом",
        "other" to "Другое",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!session.state.active) {
            goLogin()
            return
        }

        // Allow leaving shell for YouTube / HDMI while session is active.
        KioskGuard.enabled = false

        remainingSec = session.state.remainingSeconds
            .takeIf { it > 0 }
            ?: TimeFormat.parseHms(session.state.timeRemaining)

        binding.commandHint.text = getString(R.string.command_port, BuildConfig.COMMAND_PORT)
        binding.logoutButton.setOnClickListener { doLogout(callApi = true) }
        binding.sosButton.setOnClickListener { showSosDialog() }
        binding.extendButton.setOnClickListener { showExtendDialog() }

        buildLauncher()
        session.observe(observer)
        startTicker()
        startPoll()
        // PS / console on HDMI1 (Prefs): switch as soon as session UI is up.
        binding.root.post { HdmiInputs.applySessionInput(this, prefs) }
    }

    override fun onResume() {
        super.onResume()
        LockTaskController.start(this, allowedPackages)
    }

    override fun onDestroy() {
        tickJob?.cancel()
        pollJob?.cancel()
        session.removeObserver(observer)
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event?.repeatCount == 0) {
            when (keyCode) {
                KeyEvent.KEYCODE_PROG_GREEN,
                KeyEvent.KEYCODE_F2,
                -> {
                    if (binding.extendButton.isEnabled) showExtendDialog()
                    return true
                }
                KeyEvent.KEYCODE_PROG_RED,
                KeyEvent.KEYCODE_F3,
                -> {
                    showSosDialog()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun buildLauncher() {
        val apps = LauncherApps.discover(this)
        allowedPackages = LauncherApps.packageNames(apps)
        LockTaskController.prepareLockTaskPackages(this, allowedPackages)

        binding.appsRow.removeAllViews()
        if (apps.isEmpty()) {
            binding.appsLabel.text = getString(R.string.apps_empty)
        } else {
            binding.appsLabel.text = getString(R.string.apps_label)
            for (app in apps) {
                val btn = layoutInflater.inflate(
                    R.layout.item_launcher_app,
                    binding.appsRow,
                    false,
                ) as MaterialButton
                btn.text = app.label
                app.icon?.let { btn.icon = it }
                btn.setOnClickListener {
                    if (!LauncherApps.launch(this, app.packageName)) {
                        Toast.makeText(this, R.string.launch_failed, Toast.LENGTH_SHORT).show()
                    }
                }
                binding.appsRow.addView(btn)
            }
        }

        val hdmi = HdmiInputs.discover(this)
        binding.hdmiRow.removeAllViews()
        if (hdmi.isEmpty()) {
            binding.hdmiLabel.visibility = View.GONE
            binding.hdmiRow.visibility = View.GONE
        } else {
            binding.hdmiLabel.visibility = View.VISIBLE
            binding.hdmiRow.visibility = View.VISIBLE
            for (src in hdmi) {
                val btn = MaterialButton(
                    this,
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle,
                ).apply {
                    text = src.label
                    setTextColor(getColor(R.color.text))
                    minimumHeight = 56
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).also { it.marginEnd = 12 }
                    setOnClickListener {
                        if (!HdmiInputs.switchTo(this@SessionActivity, src.inputId)) {
                            Toast.makeText(
                                this@SessionActivity,
                                R.string.hdmi_failed,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
                binding.hdmiRow.addView(btn)
            }
        }
    }

    private fun showSosDialog() {
        val labels = sosReasons.map { it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.sos_title)
            .setItems(labels) { _, which ->
                val (code, label) = sosReasons[which]
                sendSos(code, label)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun sendSos(code: String, label: String) {
        val state = session.state
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    ShellApi(prefs).reportSos(
                        computerId = prefs.terminalId,
                        bookingId = state.bookingId,
                        reasonCode = code,
                        reasonLabel = label,
                    )
                }.getOrElse { ShellApi.SimpleResult(false, it.message ?: "SOS fail") }
            }
            Toast.makeText(
                this@SessionActivity,
                if (res.ok) getString(R.string.sos_sent) else res.message,
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun showExtendDialog() {
        binding.extendButton.isEnabled = false
        Toast.makeText(this, R.string.extend_loading, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val pack = withContext(Dispatchers.IO) {
                runCatching { ShellApi(prefs).extendOptions(session.state.bookingId) }
                    .getOrElse { ShellApi.ExtendOptionsResult(false, message = it.message ?: "") }
            }
            binding.extendButton.isEnabled = true
            if (!pack.ok || pack.options.isEmpty()) {
                Toast.makeText(
                    this@SessionActivity,
                    pack.message.ifBlank { getString(R.string.extend_failed) },
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }

            val labels = pack.options.map { opt ->
                when {
                    opt.conflict -> getString(R.string.extend_option_busy, opt.label)
                    opt.canPay -> getString(R.string.extend_option_fmt, opt.label, opt.cost)
                    else -> getString(R.string.extend_option_short_fmt, opt.label, opt.shortage)
                }
            }.toTypedArray()

            AlertDialog.Builder(this@SessionActivity)
                .setTitle(R.string.extend_title)
                .setItems(labels) { _, which ->
                    val opt = pack.options.getOrNull(which) ?: return@setItems
                    when {
                        opt.conflict -> Toast.makeText(
                            this@SessionActivity,
                            getString(R.string.extend_option_busy, opt.label),
                            Toast.LENGTH_SHORT,
                        ).show()
                        opt.canPay -> confirmExtendFromBalance(opt)
                        else -> offerTopUpForExtend(opt)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun confirmExtendFromBalance(opt: ShellApi.ExtendOption) {
        AlertDialog.Builder(this)
            .setTitle(R.string.extend_session)
            .setMessage(getString(R.string.extend_confirm_fmt, opt.cost, opt.label))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.extend_session) { _, _ ->
                requestExtend(opt.minutes)
            }
            .show()
    }

    private fun offerTopUpForExtend(opt: ShellApi.ExtendOption) {
        val topup = opt.suggestedTopup.takeIf { it >= 100.0 } ?: maxOf(100.0, opt.shortage)
        AlertDialog.Builder(this)
            .setTitle(R.string.extend_session)
            .setMessage(getString(R.string.extend_need_topup_fmt, opt.shortage, topup))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.extend_pay) { _, _ ->
                requestTopUpQr(topup)
            }
            .show()
    }

    private fun requestExtend(minutes: Int) {
        val state = session.state
        binding.extendButton.isEnabled = false
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching { ShellApi(prefs).extendSession(state.bookingId, minutes) }
                    .getOrElse { ShellApi.ExtendResult(ok = false, message = it.message ?: "") }
            }
            binding.extendButton.isEnabled = true
            when {
                res.applied -> {
                    val sec = res.remainingSeconds.takeIf { it > 0 }
                        ?: TimeFormat.parseHms(res.timeRemaining)
                    if (sec > 0) remainingSec = sec
                    session.update {
                        it.copy(
                            balance = if (res.balance > 0) res.balance else it.balance,
                            timeRemaining = res.timeRemaining.ifBlank { TimeFormat.formatHms(remainingSec) },
                            remainingSeconds = remainingSec,
                        )
                    }
                    Toast.makeText(
                        this@SessionActivity,
                        res.message.ifBlank { getString(R.string.extend_ok) },
                        Toast.LENGTH_LONG,
                    ).show()
                }
                res.needsTopup -> {
                    val opt = ShellApi.ExtendOption(
                        minutes = res.minutes,
                        label = when (res.minutes) {
                            30 -> "30 мин"
                            60 -> "1 ч"
                            120 -> "2 ч"
                            180 -> "3 ч"
                            else -> "${res.minutes} мин"
                        },
                        cost = res.cost,
                        canPay = false,
                        shortage = res.shortage,
                        suggestedTopup = res.suggestedTopup,
                        conflict = false,
                    )
                    offerTopUpForExtend(opt)
                }
                else -> Toast.makeText(
                    this@SessionActivity,
                    res.message.ifBlank { getString(R.string.extend_failed) },
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun requestTopUpQr(amount: Double) {
        val state = session.state
        binding.extendButton.isEnabled = false
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    ShellApi(prefs).topUpRedirect(
                        amount = amount,
                        bookingId = state.bookingId,
                        userId = state.userId,
                    )
                }.getOrElse {
                    ShellApi.TopUpResult(false, message = it.message ?: "top-up fail")
                }
            }
            binding.extendButton.isEnabled = true
            if (!res.ok || res.confirmationUrl.isBlank()) {
                Toast.makeText(
                    this@SessionActivity,
                    res.message.ifBlank { getString(R.string.extend_failed) },
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            showQrDialog(res.confirmationUrl, amount)
        }
    }

    private fun showQrDialog(url: String, amount: Double) {
        val bmp = runCatching { QrBitmap.encode(url, 640) }.getOrNull()
        if (bmp == null) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()
            return
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 24, 32, 8)
        }
        box.addView(
            TextView(this).apply {
                text = getString(R.string.extend_qr_hint, amount.toInt())
                setTextColor(getColor(R.color.text))
                textSize = 18f
                gravity = Gravity.CENTER
            },
        )
        box.addView(
            ImageView(this).apply {
                setImageBitmap(bmp)
                adjustViewBounds = true
                layoutParams = LinearLayout.LayoutParams(480, 480).also {
                    it.topMargin = 16
                    it.gravity = Gravity.CENTER_HORIZONTAL
                }
            },
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.extend_session)
            .setView(box)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                if (ending) break
                remainingSec = (remainingSec - 1).coerceAtLeast(0)
                val hms = TimeFormat.formatHms(remainingSec)
                session.update {
                    it.copy(timeRemaining = hms, remainingSeconds = remainingSec)
                }
                maybeWarn(remainingSec)
                if (remainingSec <= 0) {
                    forceEnd("Время сессии истекло")
                    break
                }
            }
        }
    }

    private fun startPoll() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive) {
                delay(8_000)
                if (ending) break
                val state = session.state
                val hb = withContext(Dispatchers.IO) {
                    runCatching { ShellApi(prefs).heartbeat() }
                        .getOrElse { ShellApi.HeartbeatResult(false, message = it.message ?: "") }
                }
                if (hb.ok && !hb.sessionActive) {
                    forceEnd("Сессия закрыта на сервере")
                    break
                }
                val bal = withContext(Dispatchers.IO) {
                    runCatching { ShellApi(prefs).getBalance(state.userId, state.bookingId) }
                        .getOrElse { ShellApi.BalanceResult(false, message = it.message ?: "") }
                }
                if (bal.ok) {
                    val serverSec = TimeFormat.parseHms(bal.timeRemaining)
                    if (serverSec > 0 && kotlin.math.abs(serverSec - remainingSec) > 3) {
                        remainingSec = serverSec
                    }
                    session.update {
                        it.copy(
                            balance = bal.balance,
                            timeRemaining = TimeFormat.formatHms(remainingSec),
                            remainingSeconds = remainingSec,
                            bookingId = bal.bookingId.takeIf { id -> id > 0 } ?: it.bookingId,
                        )
                    }
                    if (!bal.sessionActive) {
                        forceEnd("Сессия закрыта на сервере")
                        break
                    }
                } else if (bal.message.contains("не найдена", ignoreCase = true)) {
                    forceEnd("Сессия закрыта на сервере")
                    break
                }
            }
        }
    }

    private fun maybeWarn(sec: Int) {
        val marks = listOf(600, 300, 60)
        for (m in marks) {
            if (sec == m && warnedAt.add(m)) {
                val label = when (m) {
                    600 -> "10 минут"
                    300 -> "5 минут"
                    else -> "1 минута"
                }
                val msg = "Осталось $label"
                session.update { it.copy(warnBanner = msg) }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }
        if (sec !in marks && session.state.warnBanner.isNotBlank()) {
            session.update { it.copy(warnBanner = "") }
        }
    }

    private fun render(state: SessionState) {
        if (!state.active) {
            // Soft-kick / LAN clear: leave once, park on idle HDMI.
            if (!ending) {
                ending = true
                goLogin(applyIdleHdmi = true)
            }
            return
        }
        binding.userName.text = state.userName
        binding.balanceValue.text = String.format("%.2f ₽", state.balance)
        binding.timeValue.text = state.timeRemaining
        val banner = state.warnBanner.ifBlank { state.bannerMessage }
        if (banner.isBlank()) {
            binding.messageBanner.visibility = View.GONE
        } else {
            binding.messageBanner.visibility = View.VISIBLE
            binding.messageBanner.text = banner
            binding.messageBanner.setTextColor(
                getColor(if (state.warnBanner.isNotBlank()) R.color.danger else R.color.text),
            )
        }
        binding.timeValue.setTextColor(
            getColor(if (remainingSec <= 60) R.color.danger else R.color.ok),
        )
    }

    private fun forceEnd(reason: String) {
        if (ending) return
        ending = true
        Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
        doLogout(callApi = true)
    }

    private fun doLogout(callApi: Boolean) {
        ending = true
        binding.logoutButton.isEnabled = false
        tickJob?.cancel()
        pollJob?.cancel()
        lifecycleScope.launch {
            if (callApi) {
                withContext(Dispatchers.IO) {
                    runCatching { ShellApi(prefs).logout() }
                }
            }
            session.clear()
            KioskGuard.enabled = true
            LockTaskController.prepareLockTaskPackages(this@SessionActivity)
            SessionNetworkPolicy.onSessionIdle()
            goLogin(applyIdleHdmi = true)
        }
    }

    private fun goLogin(applyIdleHdmi: Boolean = false) {
        KioskGuard.enabled = true
        startActivity(
            Intent(this, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        if (applyIdleHdmi) {
            HdmiInputs.applyIdleInput(this, prefs)
        }
        finish()
    }
}
