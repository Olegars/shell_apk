package ru.compclub.tvshell.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ShellApi(private val prefs: Prefs) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    data class LoginResult(val ok: Boolean, val message: String, val session: SessionState? = null)
    data class CheckResult(
        val registered: Boolean,
        val computerId: Int = 0,
        val name: String = "",
        val zoneSlug: String = "",
        val clubName: String = "",
        val message: String = "",
    )
    data class SimpleResult(val ok: Boolean, val message: String, val terminalId: Int = 0, val clubName: String = "")

    data class FanPair(
        val channel: Int,
        val channel2: Int,
        val label: String,
        val status: String,
        val spaceName: String,
        val fanId: Int,
    )

    data class FanBoard(
        val id: Int,
        val name: String,
        val host: String,
        val port: Int,
        val pairs: List<FanPair>,
    )

    data class FanBound(
        val fanId: Int,
        val label: String,
        val host: String,
        val channel: Int,
        val channel2: Int,
    )

    data class FanDiscover(
        val available: Boolean,
        val reason: String,
        val spaceName: String,
        val slotsUsed: Int,
        val slotsMax: Int,
        val bound: List<FanBound>,
        val boards: List<FanBoard>,
        val message: String = "",
    )

    fun check(hwid: String): CheckResult {
        val body = JSONObject().put("hwid", hwid).toString()
        val req = Request.Builder()
            .url("${prefs.serverUrl}/api/shell/check")
            .header("User-Agent", "CompClubTvShell/0.1")
            .post(body.toRequestBody(jsonType))
            .build()
        client.newCall(req).execute().use { resp ->
            val obj = JSONObject(resp.body?.string().orEmpty().ifBlank { "{}" })
            if (obj.optString("status") == "success" && obj.optInt("computer_id") > 0) {
                return CheckResult(
                    registered = true,
                    computerId = obj.optInt("computer_id"),
                    name = obj.optString("name"),
                    zoneSlug = obj.optString("zone_slug", obj.optString("type", "tv")),
                    clubName = obj.optString("club_name"),
                )
            }
            return CheckResult(
                registered = false,
                message = obj.optString("message", "Оборудование не зарегистрировано"),
            )
        }
    }

    fun register(hwid: String, zoneType: String, name: String): SimpleResult {
        val body = JSONObject()
            .put("hwid", hwid)
            .put("zone_type", zoneType)
            .put("name", name)
            .toString()
        val req = Request.Builder()
            .url("${prefs.serverUrl}/api/shell/register-terminal")
            .header("User-Agent", "CompClubTvShell/0.1")
            .post(body.toRequestBody(jsonType))
            .build()
        client.newCall(req).execute().use { resp ->
            val obj = JSONObject(resp.body?.string().orEmpty().ifBlank { "{}" })
            val ok = obj.optString("status") == "success"
            return SimpleResult(
                ok = ok,
                message = obj.optString("message", if (ok) "OK" else "Ошибка регистрации"),
                terminalId = obj.optInt("terminal_id"),
                clubName = obj.optString("club_name"),
            )
        }
    }

    fun login(phone: String, pin: String): LoginResult {
        val body = JSONObject()
            .put("phone", phone.filter { it.isDigit() })
            .put("pin", pin.filter { it.isDigit() })
            .put("terminal_id", prefs.terminalId)
            .toString()
        val req = Request.Builder()
            .url("${prefs.serverUrl}/api/shell/login")
            .header("User-Agent", "CompClubTvShell/0.1")
            .post(body.toRequestBody(jsonType))
            .build()
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            val obj = runCatching { JSONObject(raw) }.getOrNull()
                ?: return LoginResult(false, "Пустой ответ сервера (${resp.code})")
            if (obj.optString("status") != "success") {
                return LoginResult(false, obj.optString("message", "Ошибка входа"))
            }
            val club = obj.optString("club_name").trim()
            if (club.isNotBlank()) prefs.clubName = club
            val user = obj.optJSONObject("user") ?: JSONObject()
            val timeStr = user.optString("time_remaining", "00:00:00")
            return LoginResult(
                true,
                obj.optString("message", "OK"),
                SessionState(
                    userId = user.optInt("id"),
                    userName = user.optString("name", "Игрок"),
                    phone = phone.filter { it.isDigit() },
                    balance = user.optDouble("balance", 0.0),
                    timeRemaining = timeStr,
                    remainingSeconds = TimeFormat.parseHms(timeStr),
                    bookingId = obj.optInt("booking_id"),
                    active = true,
                ),
            )
        }
    }

    data class HeartbeatResult(
        val ok: Boolean,
        val sessionActive: Boolean = false,
        val powerAction: String = "none",
        val message: String = "",
    )

    data class BalanceResult(
        val ok: Boolean,
        val balance: Double = 0.0,
        val sessionActive: Boolean = false,
        val timeRemaining: String = "00:00:00",
        val bookingId: Int = 0,
        val message: String = "",
    )

    data class OverlayMedia(
        val position: String,
        val kind: String, // image | video | text
        val value: String,
        val color: String = "",
        val size: Int = 0,
    )

    data class OverlayBlock(
        val position: String,
        val active: Boolean,
        val layers: List<OverlayMedia>,
    )

    fun heartbeat(macAddress: String? = null): HeartbeatResult {
        if (prefs.terminalId <= 0) return HeartbeatResult(false, message = "no terminal")
        val body = JSONObject().put("terminal_id", prefs.terminalId)
        if (!macAddress.isNullOrBlank()) body.put("mac_address", macAddress)
        val req = Request.Builder()
            .url("${prefs.serverUrl}/api/shell/power/heartbeat")
            .header("User-Agent", "CompClubTvShell/0.1")
            .post(body.toString().toRequestBody(jsonType))
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                val obj = JSONObject(resp.body?.string().orEmpty().ifBlank { "{}" })
                HeartbeatResult(
                    ok = obj.optString("status") == "success",
                    sessionActive = obj.optBoolean("session_active", false),
                    powerAction = obj.optString("power_action", "none"),
                    message = obj.optString("message"),
                )
            }
        }.getOrElse { HeartbeatResult(false, message = it.message ?: "heartbeat fail") }
    }

    fun getBalance(userId: Int, bookingId: Int): BalanceResult {
        if (prefs.terminalId <= 0) return BalanceResult(false, message = "no terminal")
        val url = buildString {
            append("${prefs.serverUrl}/api/shell/balance?terminal_id=${prefs.terminalId}")
            if (userId > 0) append("&user_id=$userId")
            if (bookingId > 0) append("&booking_id=$bookingId")
        }
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "CompClubTvShell/0.1")
            .get()
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                val obj = JSONObject(resp.body?.string().orEmpty().ifBlank { "{}" })
                if (obj.optString("status") != "success") {
                    return BalanceResult(
                        ok = false,
                        sessionActive = false,
                        message = obj.optString("message", "session gone"),
                    )
                }
                BalanceResult(
                    ok = true,
                    balance = obj.optDouble("balance", 0.0),
                    sessionActive = obj.optBoolean("session_active", true),
                    timeRemaining = obj.optString("time_remaining", "00:00:00"),
                    bookingId = obj.optInt("booking_id", bookingId),
                )
            }
        }.getOrElse { BalanceResult(false, message = it.message ?: "balance fail") }
    }

    fun getOverlayBlocks(): Map<String, OverlayBlock> {
        if (!prefs.hasServerUrl()) return emptyMap()
        val url = "${prefs.serverUrl}/api/shell/overlays?terminal_id=${prefs.terminalId}"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "CompClubTvShell/0.1")
            .get()
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                val obj = JSONObject(resp.body?.string().orEmpty().ifBlank { "{}" })
                if (obj.optString("status") != "success") return emptyMap()
                val data = obj.optJSONObject("data") ?: return emptyMap()
                val out = linkedMapOf<String, OverlayBlock>()
                val keys = data.keys()
                while (keys.hasNext()) {
                    val pos = keys.next()
                    val block = data.optJSONObject(pos) ?: continue
                    val content = block.optJSONObject("content")
                    val layersArr = content?.optJSONArray("layers")
                    val layers = mutableListOf<OverlayMedia>()
                    if (layersArr != null) {
                        for (i in 0 until layersArr.length()) {
                            val layer = layersArr.optJSONObject(i) ?: continue
                            val type = layer.optString("type", layer.optString("kind", "image"))
                                .lowercase()
                            var value = layer.optString("value")
                            if (value.isBlank()) continue
                            val kind = when {
                                type.contains("video") -> "video"
                                type.contains("text") -> "text"
                                else -> "image"
                            }
                            if (kind != "text") {
                                value = resolveMediaUrl(value)
                            }
                            layers += OverlayMedia(
                                position = pos,
                                kind = kind,
                                value = value,
                                color = layer.optString("color"),
                                size = layer.optInt("size", 0),
                            )
                        }
                    }
                    if (layers.none { it.kind == "video" }) {
                        val v = block.optString("video_url")
                        if (v.isNotBlank()) {
                            layers += OverlayMedia(pos, "video", resolveMediaUrl(v))
                        }
                    }
                    val active = if (block.has("is_active")) block.optBoolean("is_active") else true
                    out[pos] = OverlayBlock(pos, active, layers)
                }
                out
            }
        }.getOrDefault(emptyMap())
    }

    /** Prefer configured server host for /storage/... (DB may keep stale LAN IP). */
    private fun resolveMediaUrl(raw: String): String {
        val base = prefs.serverUrl.trimEnd('/')
        if (raw.startsWith("http://") || raw.startsWith("https://")) {
            return runCatching {
                val u = java.net.URI(raw)
                val path = u.path.orEmpty()
                if (path.contains("/storage/", ignoreCase = true) && base.isNotBlank()) {
                    val q = u.rawQuery?.let { "?$it" }.orEmpty()
                    "$base$path$q"
                } else {
                    raw
                }
            }.getOrDefault(raw)
        }
        return "$base/${raw.trimStart('/')}"
    }

    fun logout(): Boolean {
        val body = JSONObject().put("terminal_id", prefs.terminalId).toString()
        val req = Request.Builder()
            .url("${prefs.serverUrl}/api/shell/logout")
            .header("User-Agent", "CompClubTvShell/0.1")
            .post(body.toRequestBody(jsonType))
            .build()
        return runCatching {
            client.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    fun fanDiscover(): FanDiscover {
        val url = "${prefs.serverUrl}/api/shell/fan/discover?terminal_id=${prefs.terminalId}"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "CompClubTvShell/0.1")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val obj = JSONObject(resp.body?.string().orEmpty().ifBlank { "{}" })
            if (obj.optString("status") == "error") {
                return FanDiscover(
                    available = false,
                    reason = "error",
                    spaceName = "",
                    slotsUsed = 0,
                    slotsMax = 2,
                    bound = emptyList(),
                    boards = emptyList(),
                    message = obj.optString("message", "discover failed"),
                )
            }
            return parseDiscover(obj)
        }
    }

    fun fanBind(boardId: Int, channel: Int, channel2: Int): SimpleResult {
        val body = JSONObject()
            .put("terminal_id", prefs.terminalId)
            .put("relay_board_id", boardId)
            .put("channel", channel)
            .put("channel2", channel2)
            .toString()
        val req = Request.Builder()
            .url("${prefs.serverUrl}/api/shell/fan/bind")
            .header("User-Agent", "CompClubTvShell/0.1")
            .post(body.toRequestBody(jsonType))
            .build()
        client.newCall(req).execute().use { resp ->
            val obj = JSONObject(resp.body?.string().orEmpty().ifBlank { "{}" })
            return SimpleResult(
                ok = obj.optString("status") == "success",
                message = obj.optString("message", "bind"),
            )
        }
    }

    fun fanUnbind(fanId: Int): SimpleResult {
        val body = JSONObject()
            .put("terminal_id", prefs.terminalId)
            .put("fan_id", fanId)
            .toString()
        val req = Request.Builder()
            .url("${prefs.serverUrl}/api/shell/fan/unbind")
            .header("User-Agent", "CompClubTvShell/0.1")
            .post(body.toRequestBody(jsonType))
            .build()
        client.newCall(req).execute().use { resp ->
            val obj = JSONObject(resp.body?.string().orEmpty().ifBlank { "{}" })
            return SimpleResult(
                ok = obj.optString("status") == "success",
                message = obj.optString("message", "unbind"),
            )
        }
    }

    /** Quick W5100 pulse: mid ~2s then night (LAN from TV). */
    fun fanTestPulse(host: String, port: Int, k1: Int, k2: Int): SimpleResult {
        fun get(cmd: String): Boolean {
            val url = "http://$host/$port/$cmd"
            val req = Request.Builder().url(url).get().build()
            return runCatching {
                client.newCall(req).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }
        fun ch(channel: Int, on: Boolean): String {
            val n = (channel - 1) * 2 + if (on) 1 else 0
            return n.toString().padStart(2, '0')
        }
        if (!get("99")) return SimpleResult(false, "Нет связи с http://$host/$port/99")
        get(ch(k2, false))
        get(ch(k1, true)) // mid
        Thread.sleep(2000)
        get(ch(k2, false))
        get(ch(k1, false)) // night
        return SimpleResult(true, "Тест OK → night K$k1+K$k2")
    }

    private fun parseDiscover(obj: JSONObject): FanDiscover {
        val bound = mutableListOf<FanBound>()
        val boundArr = obj.optJSONArray("bound") ?: JSONArray()
        for (i in 0 until boundArr.length()) {
            val b = boundArr.optJSONObject(i) ?: continue
            bound += FanBound(
                fanId = b.optInt("fan_id"),
                label = b.optString("label"),
                host = b.optString("host"),
                channel = b.optInt("channel"),
                channel2 = b.optInt("channel2"),
            )
        }
        val boards = mutableListOf<FanBoard>()
        val boardsArr = obj.optJSONArray("boards") ?: JSONArray()
        for (i in 0 until boardsArr.length()) {
            val bo = boardsArr.optJSONObject(i) ?: continue
            val pairs = mutableListOf<FanPair>()
            val pairsArr = bo.optJSONArray("pairs") ?: JSONArray()
            for (j in 0 until pairsArr.length()) {
                val p = pairsArr.optJSONObject(j) ?: continue
                pairs += FanPair(
                    channel = p.optInt("channel"),
                    channel2 = p.optInt("channel2"),
                    label = p.optString("label"),
                    status = p.optString("status", "free"),
                    spaceName = p.optString("space_name"),
                    fanId = p.optInt("fan_id"),
                )
            }
            boards += FanBoard(
                id = bo.optInt("id"),
                name = bo.optString("name"),
                host = bo.optString("host"),
                port = bo.optInt("port", 30000),
                pairs = pairs,
            )
        }
        return FanDiscover(
            available = obj.optBoolean("available", boards.isNotEmpty()),
            reason = obj.optString("reason"),
            spaceName = obj.optString("space_name"),
            slotsUsed = obj.optInt("slots_used"),
            slotsMax = obj.optInt("slots_max", 2),
            bound = bound,
            boards = boards,
            message = obj.optString("message"),
        )
    }

    /** Kiosk UI state for server / MikroTik isolate queue. */
    fun reportUiState(
        state: String,
        pullAttempts: Int = 0,
        macAddress: String? = null,
        lanIp: String? = null,
    ): SimpleResult {
        if (prefs.terminalId <= 0 || !prefs.hasServerUrl()) {
            return SimpleResult(false, "not registered")
        }
        val body = JSONObject()
            .put("terminal_id", prefs.terminalId)
            .put("state", state)
            .put("pull_attempts", pullAttempts)
        if (!macAddress.isNullOrBlank()) body.put("mac_address", macAddress)
        if (!lanIp.isNullOrBlank()) body.put("lan_ip", lanIp)
        val req = Request.Builder()
            .url("${prefs.serverUrl}/api/shell/ui-state")
            .header("User-Agent", "CompClubTvShell/0.1")
            .post(body.toString().toRequestBody(jsonType))
            .build()
        client.newCall(req).execute().use { resp ->
            val obj = JSONObject(resp.body?.string().orEmpty().ifBlank { "{}" })
            return SimpleResult(
                ok = obj.optString("status") == "success",
                message = obj.optString("action", obj.optString("message", "ok")),
            )
        }
    }

    data class TopUpResult(
        val ok: Boolean,
        val confirmationUrl: String = "",
        val paymentId: String = "",
        val message: String = "",
    )

    data class ExtendOption(
        val minutes: Int,
        val label: String,
        val cost: Double,
        val canPay: Boolean,
        val shortage: Double,
        val suggestedTopup: Double,
        val conflict: Boolean,
    )

    data class ExtendOptionsResult(
        val ok: Boolean,
        val balance: Double = 0.0,
        val hourlyRate: Double = 0.0,
        val options: List<ExtendOption> = emptyList(),
        val message: String = "",
    )

    data class ExtendResult(
        val ok: Boolean,
        val applied: Boolean = false,
        val needsTopup: Boolean = false,
        val minutes: Int = 0,
        val cost: Double = 0.0,
        val balance: Double = 0.0,
        val shortage: Double = 0.0,
        val suggestedTopup: Double = 0.0,
        val timeRemaining: String = "",
        val remainingSeconds: Int = 0,
        val message: String = "",
    )

    fun extendOptions(bookingId: Int): ExtendOptionsResult {
        val qs = buildString {
            append("terminal_id=${prefs.terminalId}")
            if (bookingId > 0) append("&booking_id=$bookingId")
        }
        val req = Request.Builder()
            .url("${prefs.serverUrl}/api/shell/session/extend/options?$qs")
            .header("User-Agent", "CompClubTvShell/0.1")
            .get()
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                val obj = JSONObject(resp.body?.string().orEmpty().ifBlank { "{}" })
                if (obj.optString("status") != "success") {
                    return ExtendOptionsResult(
                        ok = false,
                        message = obj.optString("message", "Не удалось загрузить варианты"),
                    )
                }
                val opts = mutableListOf<ExtendOption>()
                val arr = obj.optJSONArray("options") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    opts += ExtendOption(
                        minutes = o.optInt("minutes"),
                        label = o.optString("label"),
                        cost = o.optDouble("cost"),
                        canPay = o.optBoolean("can_pay"),
                        shortage = o.optDouble("shortage"),
                        suggestedTopup = o.optDouble("suggested_topup"),
                        conflict = o.optBoolean("conflict"),
                    )
                }
                ExtendOptionsResult(
                    ok = true,
                    balance = obj.optDouble("balance"),
                    hourlyRate = obj.optDouble("hourly_rate"),
                    options = opts,
                )
            }
        }.getOrElse { ExtendOptionsResult(false, message = it.message ?: "extend options fail") }
    }

    fun extendSession(bookingId: Int, minutes: Int): ExtendResult {
        val body = JSONObject()
            .put("terminal_id", prefs.terminalId)
            .put("minutes", minutes)
        if (bookingId > 0) body.put("booking_id", bookingId)
        val req = Request.Builder()
            .url("${prefs.serverUrl}/api/shell/session/extend")
            .header("User-Agent", "CompClubTvShell/0.1")
            .post(body.toString().toRequestBody(jsonType))
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                val obj = JSONObject(resp.body?.string().orEmpty().ifBlank { "{}" })
                val applied = obj.optBoolean("applied")
                val needsTopup = obj.optBoolean("needs_topup")
                ExtendResult(
                    ok = obj.optString("status") == "success" || applied || needsTopup,
                    applied = applied,
                    needsTopup = needsTopup,
                    minutes = obj.optInt("minutes", minutes),
                    cost = obj.optDouble("cost"),
                    balance = obj.optDouble("balance"),
                    shortage = obj.optDouble("shortage"),
                    suggestedTopup = obj.optDouble("suggested_topup"),
                    timeRemaining = obj.optString("time_remaining"),
                    remainingSeconds = obj.optInt("remaining_seconds"),
                    message = obj.optString("message", if (applied) "OK" else "Ошибка продления"),
                )
            }
        }.getOrElse { ExtendResult(ok = false, message = it.message ?: "extend fail") }
    }

    fun topUpRedirect(amount: Double, bookingId: Int, userId: Int): TopUpResult {
        val body = JSONObject()
            .put("terminal_id", prefs.terminalId)
            .put("amount", amount)
            .put("confirmation", "redirect")
        if (bookingId > 0) body.put("booking_id", bookingId)
        if (userId > 0) body.put("user_id", userId)
        val req = Request.Builder()
            .url("${prefs.serverUrl}/api/shell/billing/topup")
            .header("User-Agent", "CompClubTvShell/0.1")
            .post(body.toString().toRequestBody(jsonType))
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                val obj = JSONObject(resp.body?.string().orEmpty().ifBlank { "{}" })
                if (obj.optString("status") != "success") {
                    return TopUpResult(
                        ok = false,
                        message = obj.optString("message", "Ошибка оплаты"),
                    )
                }
                TopUpResult(
                    ok = true,
                    confirmationUrl = obj.optString("confirmation_url"),
                    paymentId = obj.optString("payment_id"),
                    message = obj.optString("message", "OK"),
                )
            }
        }.getOrElse { TopUpResult(false, message = it.message ?: "top-up fail") }
    }

    fun reportSos(
        computerId: Int,
        bookingId: Int,
        reasonCode: String,
        reasonLabel: String,
    ): SimpleResult {
        val body = JSONObject()
            .put("computer_id", computerId)
            .put("reason", JSONObject().put("code", reasonCode).put("label", reasonLabel))
            .put("timestamp", java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                java.util.Locale.US,
            ).format(java.util.Date()))
        if (bookingId > 0) body.put("booking_id", bookingId)
        val req = Request.Builder()
            .url("${prefs.serverUrl}/api/shell/sos")
            .header("User-Agent", "CompClubTvShell/0.1")
            .post(body.toString().toRequestBody(jsonType))
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                val obj = JSONObject(resp.body?.string().orEmpty().ifBlank { "{}" })
                SimpleResult(
                    ok = obj.optString("status") == "success",
                    message = obj.optString("message", if (resp.isSuccessful) "OK" else "SOS fail"),
                )
            }
        }.getOrElse { SimpleResult(false, it.message ?: "SOS fail") }
    }
}
