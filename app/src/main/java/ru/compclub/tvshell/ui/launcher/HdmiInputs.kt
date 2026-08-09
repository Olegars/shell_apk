package ru.compclub.tvshell.ui.launcher

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Build
import android.util.Log
import ru.compclub.tvshell.data.Prefs

data class HdmiSource(
    val inputId: String,
    val label: String,
    /** 1-based index among HDMI passthrough inputs (sorted). */
    val ordinal: Int = 0,
)

/**
 * HDMI / passthrough inputs via TvInputManager.
 * If the OEM hides inputs or blocks the Intent — list stays empty and UI stays GONE.
 *
 * Auto policy (Prefs): session → HDMI N (default 1 / PS), idle → HDMI M (default 2).
 */
object HdmiInputs {
    private const val TAG = "HdmiInputs"

    fun discover(context: Context): List<HdmiSource> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return emptyList()
        val tim = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
            ?: return emptyList()
        return runCatching {
            val list = tim.tvInputList ?: return emptyList()
            list.mapNotNull { info ->
                if (!info.isPassthroughInput) return@mapNotNull null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && info.isHidden(context)) {
                    return@mapNotNull null
                }
                val label = labelFor(context, info)
                HdmiSource(inputId = info.id, label = label)
            }.sortedWith(compareBy({ hdmiNumber(it.label) ?: Int.MAX_VALUE }, { it.label }))
                .mapIndexed { index, src -> src.copy(ordinal = index + 1) }
        }.onFailure { Log.w(TAG, "discover failed: ${it.message}") }
            .getOrDefault(emptyList())
    }

    /** HDMI-only sources, 1-based ordinals (HDMI1, HDMI2, …). */
    fun hdmiOnly(context: Context): List<HdmiSource> {
        val all = discover(context)
        val hdmi = all.filter { it.label.contains("HDMI", ignoreCase = true) }
        val base = if (hdmi.isNotEmpty()) hdmi else all
        return base.mapIndexed { index, src -> src.copy(ordinal = index + 1) }
    }

    fun findByOrdinal(context: Context, ordinal: Int): HdmiSource? {
        if (ordinal <= 0) return null
        val list = hdmiOnly(context)
        list.firstOrNull { it.ordinal == ordinal }?.let { return it }
        // Label match: "HDMI1", "HDMI 1", "HDMI·1", …
        list.firstOrNull { hdmiNumber(it.label) == ordinal }?.let { return it }
        return list.getOrNull(ordinal - 1)
    }

    fun switchTo(context: Context, inputId: String): Boolean {
        return runCatching {
            val uri = TvContract.buildChannelUriForPassthroughInput(inputId)
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val resolve = intent.resolveActivity(context.packageManager)
            if (resolve == null) {
                Log.w(TAG, "no activity for passthrough $inputId")
                return false
            }
            context.startActivity(intent)
            true
        }.onFailure { Log.w(TAG, "switch failed: ${it.message}") }
            .getOrDefault(false)
    }

    fun switchToOrdinal(context: Context, ordinal: Int): Boolean {
        val src = findByOrdinal(context, ordinal) ?: run {
            Log.w(TAG, "no HDMI ordinal=$ordinal")
            return false
        }
        Log.i(TAG, "switch ordinal=$ordinal → ${src.label} (${src.inputId})")
        return switchTo(context, src.inputId)
    }

    /** Session open: PS / game console input (default HDMI1). */
    fun applySessionInput(context: Context, prefs: Prefs): Boolean {
        if (!prefs.hdmiAutoSwitch) return false
        return switchToOrdinal(context, prefs.hdmiSessionOrdinal)
    }

    /** Session end: park on idle input (default HDMI2). */
    fun applyIdleInput(context: Context, prefs: Prefs): Boolean {
        if (!prefs.hdmiAutoSwitch) return false
        val ordinal = prefs.hdmiIdleOrdinal
        if (ordinal <= 0) return false
        return switchToOrdinal(context, ordinal)
    }

    private fun hdmiNumber(label: String): Int? {
        val m = Regex("""HDMI\s*[·.\-]?\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(label)
            ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    private fun labelFor(context: Context, info: TvInputInfo): String {
        val type = when (info.type) {
            TvInputInfo.TYPE_HDMI -> "HDMI"
            TvInputInfo.TYPE_COMPOSITE -> "AV"
            TvInputInfo.TYPE_COMPONENT -> "Component"
            TvInputInfo.TYPE_VGA -> "VGA"
            TvInputInfo.TYPE_DVI -> "DVI"
            TvInputInfo.TYPE_DISPLAY_PORT -> "DP"
            else -> "IN"
        }
        val idTail = info.id.substringAfterLast('/').substringAfterLast('.')
            .ifBlank { info.id.takeLast(8) }
        val custom = runCatching { info.loadLabel(context)?.toString() }.getOrNull()
        if (!custom.isNullOrBlank() && !custom.equals(info.id, ignoreCase = true)) {
            return custom
        }
        // Prefer stable HDMI1/HDMI2 labels when type is HDMI and id has a digit.
        if (info.type == TvInputInfo.TYPE_HDMI) {
            val n = Regex("""(\d+)""").find(idTail)?.groupValues?.getOrNull(1)
            if (!n.isNullOrBlank()) return "HDMI$n"
        }
        return "$type · $idTail"
    }
}
