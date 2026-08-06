package ru.compclub.tvshell.ui.launcher

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Build
import android.util.Log

data class HdmiSource(
    val inputId: String,
    val label: String,
)

/**
 * HDMI / passthrough inputs via TvInputManager.
 * If the OEM hides inputs or blocks the Intent — list stays empty and UI stays GONE.
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
            }.sortedBy { it.label }
        }.onFailure { Log.w(TAG, "discover failed: ${it.message}") }
            .getOrDefault(emptyList())
    }

    fun switchTo(context: Context, inputId: String): Boolean {
        return runCatching {
            val uri = TvContract.buildChannelUriForPassthroughInput(inputId)
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // Prefer Live TV / system tuner if present
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
        return "$type · $idTail"
    }
}
