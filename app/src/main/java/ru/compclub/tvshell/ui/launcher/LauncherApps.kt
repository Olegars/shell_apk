package ru.compclub.tvshell.ui.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable

data class LauncherApp(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val pinned: Boolean = false,
)

object LauncherApps {
    /** Preferred pins — shown first if installed. */
    private val PINNED = listOf(
        "com.google.android.youtube.tv" to "YouTube",
        "com.google.android.youtube.tvunplugged" to "YouTube",
        "com.google.android.youtube.googletv" to "YouTube",
        "com.google.android.youtube" to "YouTube",
        "ru.kinopoisk.tv" to "Кинопоиск",
        "ru.kinopoisk.android" to "Кинопоиск",
        "ru.kinopoisk.yandex" to "Кинопоиск",
        "com.netflix.ninja" to "Netflix",
        "com.amazon.amazonvideo.livingroom" to "Prime Video",
        "ru.ivi.client" to "IVI",
        "com.softmedia.ok.ru.tv" to "OK Live",
    )

    private val BLOCKED_PREFIXES = listOf(
        "com.android.settings",
        "com.android.tv.settings",
        "com.google.android.tungsten.setupwraith",
        "com.google.android.apps.tv.launcherx",
        "com.google.android.tvlauncher",
        "com.google.android.leanbacklauncher",
        "com.android.vending",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.systemui",
        "com.xiaomi.mitv.settings",
        "com.xiaomi.mitv.tvmanager",
        "com.xiaomi.tv.settings",
    )

    private val BLOCKED_EXACT = setOf(
        "com.android.tv.settings",
        "com.android.settings",
        "ru.compclub.tvshell",
    )

    fun discover(context: Context): List<LauncherApp> {
        val pm = context.packageManager
        val self = context.packageName
        val seen = linkedSetOf<String>()
        val out = mutableListOf<LauncherApp>()

        for ((pkg, label) in PINNED) {
            if (pkg in seen) continue
            if (!isLaunchable(pm, pkg)) continue
            seen += pkg
            out += LauncherApp(
                packageName = pkg,
                label = label,
                icon = iconOrNull(pm, pkg),
                pinned = true,
            )
        }

        val leanback = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = mutableListOf<ResolveInfo>()
        resolved += pm.queryIntentActivities(leanback, PackageManager.MATCH_ALL)
        resolved += pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)

        for (ri in resolved) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (pkg == self || pkg in seen) continue
            if (isBlocked(pkg)) continue
            if (!isLaunchable(pm, pkg)) continue
            seen += pkg
            val label = ri.loadLabel(pm)?.toString()?.ifBlank { pkg } ?: pkg
            out += LauncherApp(
                packageName = pkg,
                label = label,
                icon = ri.loadIcon(pm),
                pinned = false,
            )
        }

        return out
    }

    fun packageNames(apps: List<LauncherApp>): Array<String> =
        apps.map { it.packageName }.distinct().toTypedArray()

    fun launch(context: Context, packageName: String): Boolean {
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(packageName)
            ?: pm.getLaunchIntentForPackage(packageName)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun isBlocked(pkg: String): Boolean {
        if (pkg in BLOCKED_EXACT) return true
        val lower = pkg.lowercase()
        if (lower.contains("settings") && !lower.contains("soundsettings")) return true
        return BLOCKED_PREFIXES.any { lower.startsWith(it.lowercase()) }
    }

    private fun isLaunchable(pm: PackageManager, pkg: String): Boolean {
        return runCatching {
            pm.getLeanbackLaunchIntentForPackage(pkg) != null ||
                pm.getLaunchIntentForPackage(pkg) != null
        }.getOrDefault(false)
    }

    private fun iconOrNull(pm: PackageManager, pkg: String): Drawable? =
        runCatching {
            val ai: ApplicationInfo = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationIcon(ai)
        }.getOrNull()
}
