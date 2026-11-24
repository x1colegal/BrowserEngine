package com.x1colegal.browserengine

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.widget.Toast

internal const val RESTART_PREFERENCES = "browser_restart"
internal const val RESTART_TABS = "tabs"
internal const val RESTART_SELECTED_TAB = "selected_tab"
internal const val RESTART_PREVIOUS_PID = "previous_pid"

class RestartActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var restartStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getIntExtra(RESTART_PREVIOUS_PID, 0) <= 0) finishAndRemoveTask()
    }

    override fun onResume() {
        super.onResume()
        val previousPid = intent.getIntExtra(RESTART_PREVIOUS_PID, 0)
        if (restartStarted || previousPid <= 0 || previousPid == Process.myPid()) return
        restartStarted = true
        handler.post {
            Process.killProcess(previousPid)
            waitForExit(previousPid, attempts = 30)
        }
    }

    private fun waitForExit(pid: Int, attempts: Int) {
        val exited = try {
            Os.kill(pid, 0)
            false
        } catch (error: ErrnoException) {
            error.errno == OsConstants.ESRCH
        }
        when {
            exited -> {
                startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finishAndRemoveTask()
            }
            attempts > 0 -> handler.postDelayed({ waitForExit(pid, attempts - 1) }, 80)
            else -> {
                Toast.makeText(this, "BrowserEngine could not restart", Toast.LENGTH_SHORT).show()
                finishAndRemoveTask()
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
