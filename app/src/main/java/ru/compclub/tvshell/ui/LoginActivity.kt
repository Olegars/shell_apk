package ru.compclub.tvshell.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.compclub.tvshell.BuildConfig
import ru.compclub.tvshell.TvShellApp
import ru.compclub.tvshell.command.CommandService
import ru.compclub.tvshell.data.DeviceId
import ru.compclub.tvshell.data.ShellApi
import ru.compclub.tvshell.databinding.ActivityLoginBinding
import ru.compclub.tvshell.databinding.ItemOverlayBlockBinding
import ru.compclub.tvshell.kiosk.LockTaskController
import ru.compclub.tvshell.kiosk.SessionNetworkPolicy

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private val prefs get() = TvShellApp.instance.prefs
    private val session get() = TvShellApp.instance.session
    private var overlay: IdleOverlayController? = null

    private var titleTapCount = 0
    private var titleTapWindowStart = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        CommandService.start(this)

        if (session.state.active) {
            startActivity(Intent(this, SessionActivity::class.java))
            finish()
            return
        }

        binding.loginButton.setOnClickListener { doLogin() }
        binding.settingsButton.visibility = View.GONE
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }
        binding.title.setOnClickListener { onTitleTap() }

        disableSystemKeyboard(binding.phoneInput)
        disableSystemKeyboard(binding.pinInput)

        binding.phoneInput.setText(RuPhoneMaskWatcher.EMPTY)
        binding.phoneInput.addTextChangedListener(RuPhoneMaskWatcher(binding.phoneInput))
        binding.phoneInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                hideIme()
                binding.phoneInput.setSelection(binding.phoneInput.text?.length ?: 0)
            }
        }
        binding.pinInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) hideIme()
        }

        wireNumPad()

        binding.phoneInput.requestFocus()
        binding.phoneInput.post {
            binding.phoneInput.setSelection(binding.phoneInput.text?.length ?: 0)
            hideIme()
        }

        overlay = IdleOverlayController(
            buildOverlaySlots(),
            lifecycleScope,
        ) { ShellApi(prefs) }

        bootstrap()
        SessionNetworkPolicy.onSessionIdle()
    }

    private fun buildOverlaySlots(): Map<String, IdleOverlayController.OverlaySlotView> {
        fun slot(
            b: ItemOverlayBlockBinding,
            label: String,
            delayMs: Int,
        ) = IdleOverlayController.OverlaySlotView(
            root = b.root,
            title = b.blockTitle,
            image = b.blockImage,
            video = b.blockVideo,
            text = b.blockText,
            label = label,
            openDelayMs = delayMs,
        )
        return mapOf(
            "top_left" to slot(binding.blockTopLeft, "CAM_01 / TOP_LEFT", 0),
            "mid_left" to slot(binding.blockMidLeft, "DAT_02 / MID_LEFT", 500),
            "bottom_left" to slot(binding.blockBottomLeft, "INF_03 / BOTTOM_LEFT", 1000),
            "top_right" to slot(binding.blockTopRight, "CAM_04 / TOP_RIGHT", 250),
            "mid_right" to slot(binding.blockMidRight, "DAT_05 / MID_RIGHT", 750),
            "bottom_right" to slot(binding.blockBottomRight, "INF_06 / BOTTOM_RIGHT", 1250),
        )
    }

    override fun onResume() {
        super.onResume()
        refreshSubtitle()
        hideIme()
        LockTaskController.start(this)
        if (prefs.isConfigured()) overlay?.start()
    }

    override fun onPause() {
        // Pause path: full release (not hide-only) so surfaces/decoders die.
        overlay?.kill()
        super.onPause()
    }

    override fun onDestroy() {
        destroyOverlays()
        super.onDestroy()
    }

    private fun onTitleTap() {
        val now = SystemClock.elapsedRealtime()
        if (now - titleTapWindowStart > 2_500) {
            titleTapCount = 0
            titleTapWindowStart = now
        }
        titleTapCount++
        if (titleTapCount >= 5) {
            titleTapCount = 0
            askAdminPin()
        }
    }

    private fun askAdminPin() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Мастер-PIN"
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Сервисное меню")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val pin = input.text?.toString().orEmpty().trim()
                if (pin == prefs.adminPin) {
                    startActivity(Intent(this, SetupActivity::class.java))
                } else {
                    Toast.makeText(this, "Неверный PIN", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun disableSystemKeyboard(edit: EditText) {
        edit.showSoftInputOnFocus = false
        edit.setRawInputType(InputType.TYPE_CLASS_NUMBER)
    }

    private fun hideIme() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.windowToken?.let { imm.hideSoftInputFromWindow(it, 0) }
        window.decorView.windowToken?.let { imm.hideSoftInputFromWindow(it, 0) }
    }

    private fun wireNumPad() {
        val digitKeys = mapOf(
            binding.key0 to '0',
            binding.key1 to '1',
            binding.key2 to '2',
            binding.key3 to '3',
            binding.key4 to '4',
            binding.key5 to '5',
            binding.key6 to '6',
            binding.key7 to '7',
            binding.key8 to '8',
            binding.key9 to '9',
        )
        digitKeys.forEach { (btn, ch) ->
            btn.setOnClickListener { appendDigit(ch) }
        }
        binding.keyBack.setOnClickListener { deleteDigit() }
        binding.keyOk.setOnClickListener {
            val phoneDone = RuPhoneMaskWatcher.isComplete(binding.phoneInput.text?.toString().orEmpty())
            val pinDone = binding.pinInput.text?.toString().orEmpty().filter { it.isDigit() }.length == 4
            when {
                !phoneDone -> binding.phoneInput.requestFocus()
                !pinDone -> binding.pinInput.requestFocus()
                else -> doLogin()
            }
        }
    }

    private fun activeField(): EditText =
        if (binding.pinInput.hasFocus()) binding.pinInput else binding.phoneInput

    private fun appendDigit(ch: Char) {
        hideIme()
        val field = activeField()
        if (field === binding.phoneInput) {
            val digits = RuPhoneMaskWatcher.digitsForApi(field.text?.toString().orEmpty())
            if (digits.length >= 11) {
                binding.pinInput.requestFocus()
                appendDigit(ch)
                return
            }
            field.append(ch.toString())
            if (RuPhoneMaskWatcher.isComplete(field.text?.toString().orEmpty())) {
                binding.pinInput.requestFocus()
            }
        } else {
            val pin = field.text?.toString().orEmpty().filter { it.isDigit() }
            if (pin.length >= 4) return
            field.append(ch.toString())
            if (field.text?.toString().orEmpty().filter { it.isDigit() }.length == 4) {
                binding.loginButton.requestFocus()
            }
        }
    }

    private fun deleteDigit() {
        hideIme()
        val field = activeField()
        if (field === binding.phoneInput) {
            val api = RuPhoneMaskWatcher.digitsForApi(field.text?.toString().orEmpty())
            if (api.length <= 1) {
                field.setText(RuPhoneMaskWatcher.EMPTY)
            } else {
                field.setText(api.dropLast(1))
            }
            field.setSelection(field.text?.length ?: 0)
        } else {
            val t = field.text?.toString().orEmpty()
            if (t.isNotEmpty()) {
                field.setText(t.dropLast(1))
                field.setSelection(field.text?.length ?: 0)
            } else {
                binding.phoneInput.requestFocus()
            }
        }
    }

    private fun bootstrap() {
        if (!prefs.hasServerUrl()) {
            openSetupUnlocked()
            return
        }
        setLoading(true)
        val hwid = DeviceId.hwid(this)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ShellApi(prefs).check(hwid) }
                    .getOrElse {
                        ShellApi.CheckResult(false, message = it.message ?: "Сеть недоступна")
                    }
            }
            setLoading(false)
            if (result.registered) {
                prefs.terminalId = result.computerId
                if (result.name.isNotBlank()) prefs.stationName = result.name
                if (result.zoneSlug.isNotBlank()) prefs.zoneType = result.zoneSlug
                refreshSubtitle()
                overlay?.start()
            } else {
                openSetupUnlocked()
            }
        }
    }

    private fun openSetupUnlocked() {
        startActivity(Intent(this, SetupActivity::class.java))
    }

    private fun refreshSubtitle() {
        binding.subtitle.text = if (prefs.isConfigured()) {
            "TV · ${prefs.stationName.ifBlank { "terminal" }} #${prefs.terminalId} · ${prefs.serverUrl}\nLAN cmd :${BuildConfig.COMMAND_PORT}"
        } else {
            "Сначала зарегистрируйте TV (HWID) и при необходимости вентиляторы"
        }
    }

    private fun doLogin() {
        if (!prefs.isConfigured()) {
            openSetupUnlocked()
            return
        }
        val phoneRaw = binding.phoneInput.text?.toString().orEmpty()
        val phone = RuPhoneMaskWatcher.digitsForApi(phoneRaw)
        val pin = binding.pinInput.text?.toString().orEmpty().filter { it.isDigit() }
        if (!RuPhoneMaskWatcher.isComplete(phoneRaw) || pin.length != 4) {
            showError("Введите телефон +7 (XXX) XXX-XX-XX и PIN из 4 цифр")
            return
        }

        setLoading(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ShellApi(prefs).login(phone, pin) }
                    .getOrElse { ShellApi.LoginResult(false, it.message ?: "Сеть недоступна") }
            }
            setLoading(false)
            if (!result.ok || result.session == null) {
                showError(result.message)
                return@launch
            }
            // Destroy overlay decoders before leaving idle (do not only hide).
            destroyOverlays()
            session.update { result.session }
            SessionNetworkPolicy.onSessionActive()
            startActivity(Intent(this@LoginActivity, SessionActivity::class.java))
            finish()
        }
    }

    private fun destroyOverlays() {
        overlay?.kill()
        overlay = null
        binding.overlayLeftColumn.visibility = View.GONE
        binding.overlayRightColumn.visibility = View.GONE
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.loginButton.isEnabled = !loading
        binding.errorText.visibility = View.GONE
    }

    private fun showError(msg: String) {
        binding.errorText.text = msg
        binding.errorText.visibility = View.VISIBLE
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
