package com.espforge.ide

import android.app.IntentService
import android.content.Intent
import android.os.Bundle

class TermuxResultService : IntentService("TermuxResultService") {
    override fun onHandleIntent(intent: Intent?) {
        val result: Bundle? = intent?.getBundleExtra("result")
        val exitCode = result?.getInt("exitCode", -999) ?: -999
        val stdout = result?.getString("stdout").orEmpty()
        val stderr = result?.getString("stderr").orEmpty()
        val error = result?.getString("errmsg").orEmpty()
        val message = buildString {
            append("Offline build exit code: ").append(exitCode).append('\n')
            if (stdout.isNotBlank()) append(stdout)
            if (stderr.isNotBlank()) append("\n").append(stderr)
            if (error.isNotBlank()) append("\nTermux error: ").append(error)
        }
        sendBroadcast(
            Intent(ACTION_TERMUX_RESULT)
                .setPackage(packageName)
                .putExtra(EXTRA_OUTPUT, message.takeLast(90000))
                .putExtra(EXTRA_EXIT_CODE, exitCode)
        )
    }

    companion object {
        const val ACTION_TERMUX_RESULT = "com.espforge.ide.TERMUX_RESULT"
        const val EXTRA_OUTPUT = "output"
        const val EXTRA_EXIT_CODE = "exit_code"
    }
}
