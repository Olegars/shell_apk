package ru.compclub.tvshell

import android.app.Application
import ru.compclub.tvshell.data.Prefs
import ru.compclub.tvshell.data.SessionStore
import ru.compclub.tvshell.kiosk.KioskGuard
import ru.compclub.tvshell.kiosk.LockTaskController

class TvShellApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var session: SessionStore
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        session = SessionStore()
        LockTaskController.prepareLockTaskPackages(this)
        KioskGuard.start(this)
    }

    companion object {
        lateinit var instance: TvShellApp
            private set
    }
}
