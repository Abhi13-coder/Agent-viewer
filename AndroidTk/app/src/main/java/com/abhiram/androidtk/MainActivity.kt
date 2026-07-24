package com.abhiram.androidtk

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File

class MainActivity : Activity() {

    private lateinit var terminalView: TerminalView
    private var session: TerminalSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        terminalView = findViewById(R.id.terminalView)
        val statusText = findViewById<TextView>(R.id.statusText)
        val grantButton = findViewById<Button>(R.id.grantOverlayButton)

        startService(Intent(this, OverlayService::class.java))

        grantButton.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }

        statusText.text = if (Settings.canDrawOverlays(this)) {
            "AndroidTk — overlay: granted — socket: androidtk.sock"
        } else {
            "AndroidTk — overlay permission needed for windows"
        }

        startShellSession()
    }

    private fun startShellSession() {
        val home = filesDir.absolutePath
        File(home).mkdirs()

        val env = arrayOf(
            "HOME=$home",
            "TERM=xterm-256color",
            "PATH=/system/bin:/system/xbin",
            "ANDROIDTK_SOCK=androidtk.sock"
        )

        val client = object : TerminalSessionClient {
            override fun onTextChanged(changedSession: TerminalSession) {
                terminalView.onScreenUpdated()
            }
            override fun onTitleChanged(changedSession: TerminalSession) {}
            override fun onSessionFinished(finishedSession: TerminalSession) {}
            override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}
            override fun onPasteTextFromClipboard(session: TerminalSession?) {}
            override fun onBell(session: TerminalSession) {}
            override fun onColorsChanged(session: TerminalSession) {}
            override fun onTerminalCursorStateChange(state: Boolean) {}
            override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
            override fun getTerminalCursorStyle(): Int? = null
            override fun logError(tag: String?, message: String?) {}
            override fun logWarn(tag: String?, message: String?) {}
            override fun logInfo(tag: String?, message: String?) {}
            override fun logDebug(tag: String?, message: String?) {}
            override fun logVerbose(tag: String?, message: String?) {}
            override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
            override fun logStackTrace(tag: String?, e: Exception?) {}
        }

        session = TerminalSession(
            "/system/bin/sh",
            home,
            arrayOf("/system/bin/sh", "-l"),
            env,
            2000,
            client
        )

        val viewClient = object : TerminalViewClient {
            override fun onScale(scale: Float): Float = scale
            override fun onSingleTapUp(e: android.view.MotionEvent?) {
                terminalView.requestFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .showSoftInput(terminalView, 0)
            }
            override fun shouldBackButtonBeMappedToEscape(): Boolean = false
            override fun shouldEnforceCharBasedInput(): Boolean = true
            override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
            override fun isTerminalViewSelected(): Boolean = true
            override fun copyModeChanged(copyMode: Boolean) {}
            override fun onKeyDown(keyCode: Int, e: android.view.KeyEvent?, session: TerminalSession?): Boolean = false
            override fun onKeyUp(keyCode: Int, e: android.view.KeyEvent?): Boolean = false
            override fun onLongPress(event: android.view.MotionEvent?): Boolean = false
            override fun readControlKey(): Boolean = false
            override fun readAltKey(): Boolean = false
            override fun readShiftKey(): Boolean = false
            override fun readFnKey(): Boolean = false
            override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
            override fun onEmulatorSet() {}
            override fun logError(tag: String?, message: String?) {}
            override fun logWarn(tag: String?, message: String?) {}
            override fun logInfo(tag: String?, message: String?) {}
            override fun logDebug(tag: String?, message: String?) {}
            override fun logVerbose(tag: String?, message: String?) {}
            override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
            override fun logStackTrace(tag: String?, e: Exception?) {}
        }

        terminalView.attachSession(session)
        terminalView.setTerminalViewClient(viewClient)
        terminalView.requestFocus()
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.finishIfRunning()
    }
}
