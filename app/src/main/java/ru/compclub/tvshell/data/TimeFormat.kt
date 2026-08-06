package ru.compclub.tvshell.data

object TimeFormat {
    fun parseHms(raw: String): Int {
        val parts = raw.trim().split(':')
        if (parts.size < 2) return 0
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val s = parts.getOrNull(2)?.toIntOrNull() ?: 0
        return (h * 3600 + m * 60 + s).coerceAtLeast(0)
    }

    fun formatHms(totalSec: Int): String {
        val sec = totalSec.coerceAtLeast(0)
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }
}
