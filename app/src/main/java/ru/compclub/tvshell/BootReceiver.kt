package ru.compclub.tvshell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ru.compclub.tvshell.command.CommandService
import ru.compclub.tvshell.ui.LoginActivity

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        CommandService.start(context)
        context.startActivity(
            Intent(context, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
