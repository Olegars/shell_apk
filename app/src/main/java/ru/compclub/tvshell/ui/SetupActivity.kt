package ru.compclub.tvshell.ui

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.compclub.tvshell.TvShellApp
import ru.compclub.tvshell.data.DeviceId
import ru.compclub.tvshell.data.ShellApi
import ru.compclub.tvshell.databinding.ActivitySetupBinding
import ru.compclub.tvshell.kiosk.LockTaskController

class SetupActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySetupBinding
    private val prefs get() = TvShellApp.instance.prefs
    private lateinit var hwid: String
    private lateinit var api: ShellApi

    private var boards: List<ShellApi.FanBoard> = emptyList()
    private var selectablePairs: List<PairItem> = emptyList()
    private var lastDiscover: ShellApi.FanDiscover? = null

    data class PairItem(
        val board: ShellApi.FanBoard,
        val pair: ShellApi.FanPair,
        val title: String,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hwid = DeviceId.hwid(this)
        api = ShellApi(prefs)

        binding.hwidText.text = "HWID: $hwid"
        binding.serverUrlInput.setText(prefs.serverUrl)
        binding.nameInput.setText(prefs.stationName.ifBlank { "TV" })

        binding.backButton.setOnClickListener { finish() }
        binding.checkButton.setOnClickListener { runCheck(saveUrl = true) }
        binding.registerButton.setOnClickListener { runRegister() }
        binding.fanRefreshButton.setOnClickListener { loadFans() }
        binding.fanTestButton.setOnClickListener { runFanTest() }
        binding.fanBindButton.setOnClickListener { runFanBind() }

        binding.boardSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                rebuildPairSpinner()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        if (prefs.hasServerUrl()) {
            runCheck(saveUrl = false)
        } else {
            binding.statusText.text = "Укажите URL сервера и зарегистрируйте TV"
            updateFanVisibility(false)
        }
        binding.messageText.text = LockTaskController.statusLine(this)
        binding.messageText.setTextColor(getColor(ru.compclub.tvshell.R.color.text_muted))
    }

    override fun onResume() {
        super.onResume()
        LockTaskController.start(this)
    }

    private fun saveServerUrl(): Boolean {
        val url = binding.serverUrlInput.text?.toString().orEmpty().trim().trimEnd('/')
        if (url.isBlank()) {
            toast("Укажите URL сервера")
            return false
        }
        prefs.serverUrl = url
        return true
    }

    private fun runCheck(saveUrl: Boolean) {
        if (saveUrl && !saveServerUrl()) return
        if (!prefs.hasServerUrl()) {
            binding.statusText.text = "Нет URL сервера"
            return
        }
        setBusy(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { api.check(hwid) }
                    .getOrElse {
                        ShellApi.CheckResult(false, message = it.message ?: "Сеть недоступна")
                    }
            }
            setBusy(false)
            if (result.registered) {
                prefs.terminalId = result.computerId
                prefs.stationName = result.name.ifBlank { prefs.stationName }
                prefs.zoneType = result.zoneSlug.ifBlank { "tv" }
                if (binding.nameInput.text.isNullOrBlank()) {
                    binding.nameInput.setText(result.name)
                }
                binding.statusText.text =
                    "Зарегистрирован · #${result.computerId} · ${result.name} · ${result.zoneSlug}"
                showMsg("OK · terminal #${result.computerId}", ok = true)
                updateFanVisibility(true)
                loadFans()
            } else {
                prefs.terminalId = 0
                binding.statusText.text = result.message.ifBlank { "Не зарегистрирован" }
                showMsg(result.message.ifBlank { "Нужна регистрация" }, ok = false)
                updateFanVisibility(false)
            }
        }
    }

    private fun runRegister() {
        if (!saveServerUrl()) return
        val name = binding.nameInput.text?.toString().orEmpty().trim()
        if (name.isBlank()) {
            toast("Укажите имя станции")
            return
        }
        setBusy(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { api.register(hwid, "tv", name) }
                    .getOrElse { ShellApi.SimpleResult(false, it.message ?: "Сеть недоступна") }
            }
            setBusy(false)
            showMsg(result.message, result.ok)
            if (result.ok && result.terminalId > 0) {
                prefs.terminalId = result.terminalId
                prefs.stationName = name
                prefs.zoneType = "tv"
                binding.statusText.text = "Зарегистрирован · #${result.terminalId} · $name · tv"
                updateFanVisibility(true)
                loadFans()
            } else if (result.ok) {
                runCheck(saveUrl = false)
            }
        }
    }

    private fun loadFans() {
        if (!prefs.isRegistered()) {
            updateFanVisibility(false)
            return
        }
        setBusy(true)
        lifecycleScope.launch {
            val disc = withContext(Dispatchers.IO) {
                runCatching { api.fanDiscover() }
                    .getOrElse {
                        ShellApi.FanDiscover(
                            available = false,
                            reason = "error",
                            spaceName = "",
                            slotsUsed = 0,
                            slotsMax = 2,
                            bound = emptyList(),
                            boards = emptyList(),
                            message = it.message ?: "discover failed",
                        )
                    }
            }
            setBusy(false)
            lastDiscover = disc
            boards = disc.boards
            when {
                disc.reason == "no_space" ->
                    binding.fanStatus.text =
                        "Комната не назначена на карте (space_id). Поставьте TV в админке, затем обновите."
                disc.message.isNotBlank() && !disc.available && disc.boards.isEmpty() ->
                    binding.fanStatus.text = disc.message
                else ->
                    binding.fanStatus.text =
                        "Комната: ${disc.spaceName.ifBlank { "—" }} · привязано ${disc.slotsUsed}/${disc.slotsMax}"
            }
            fillBoardSpinner()
            renderBound(disc.bound)
        }
    }

    private fun fillBoardSpinner() {
        val labels = if (boards.isEmpty()) {
            listOf("Нет плат")
        } else {
            boards.map { "${it.name} · ${it.host}" }
        }
        binding.boardSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        rebuildPairSpinner()
    }

    private fun rebuildPairSpinner() {
        val board = boards.getOrNull(binding.boardSpinner.selectedItemPosition)
        selectablePairs = if (board == null) {
            emptyList()
        } else {
            board.pairs
                .filter { it.status != "taken" }
                .map { PairItem(board, it, "${it.label} · ${it.status}") }
        }
        val labels = if (selectablePairs.isEmpty()) {
            listOf("Нет свободных пар")
        } else {
            selectablePairs.map { it.title }
        }
        binding.pairSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
    }

    private fun selectedPair(): PairItem? =
        selectablePairs.getOrNull(binding.pairSpinner.selectedItemPosition)

    private fun runFanTest() {
        val item = selectedPair()
        if (item == null) {
            toast("Выберите пару K1+K2")
            return
        }
        setBusy(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    api.fanTestPulse(
                        item.board.host,
                        item.board.port,
                        item.pair.channel,
                        item.pair.channel2,
                    )
                }.getOrElse { ShellApi.SimpleResult(false, it.message ?: "test failed") }
            }
            setBusy(false)
            showMsg(result.message, result.ok)
        }
    }

    private fun runFanBind() {
        val item = selectedPair()
        if (item == null) {
            toast("Выберите пару K1+K2")
            return
        }
        val slots = lastDiscover
        if (slots != null && slots.slotsUsed >= slots.slotsMax && item.pair.status != "mine") {
            toast("Лимит вентиляторов ${slots.slotsMax}")
            return
        }
        setBusy(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    api.fanBind(item.board.id, item.pair.channel, item.pair.channel2)
                }.getOrElse { ShellApi.SimpleResult(false, it.message ?: "bind failed") }
            }
            setBusy(false)
            showMsg(result.message, result.ok)
            if (result.ok) loadFans()
        }
    }

    private fun runFanUnbind(fanId: Int) {
        setBusy(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { api.fanUnbind(fanId) }
                    .getOrElse { ShellApi.SimpleResult(false, it.message ?: "unbind failed") }
            }
            setBusy(false)
            showMsg(result.message, result.ok)
            if (result.ok) loadFans()
        }
    }

    private fun renderBound(bound: List<ShellApi.FanBound>) {
        binding.boundList.removeAllViews()
        if (bound.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Нет привязок"
                setTextColor(getColor(ru.compclub.tvshell.R.color.text_muted))
                textSize = 14f
            }
            binding.boundList.addView(empty)
            return
        }
        bound.forEach { b ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }
            val label = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = "${b.label} · ${b.host}"
                setTextColor(getColor(ru.compclub.tvshell.R.color.text))
                textSize = 15f
            }
            val unbind = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "ОТВЯЗАТЬ"
                setTextColor(getColor(ru.compclub.tvshell.R.color.danger))
                minHeight = 48
                setOnClickListener { runFanUnbind(b.fanId) }
            }
            row.addView(label)
            row.addView(unbind)
            binding.boundList.addView(row)
        }
    }

    private fun updateFanVisibility(registered: Boolean) {
        binding.fanSection.visibility = if (registered) View.VISIBLE else View.GONE
    }

    private fun setBusy(busy: Boolean) {
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.checkButton.isEnabled = !busy
        binding.registerButton.isEnabled = !busy
        binding.fanRefreshButton.isEnabled = !busy
        binding.fanTestButton.isEnabled = !busy
        binding.fanBindButton.isEnabled = !busy
    }

    private fun showMsg(msg: String, ok: Boolean) {
        binding.messageText.text = msg
        binding.messageText.setTextColor(
            getColor(if (ok) ru.compclub.tvshell.R.color.ok else ru.compclub.tvshell.R.color.danger),
        )
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
