package com.abhiram.androidtk

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class MainActivity : Activity() {

    private lateinit var terminalView: TerminalView
    private var session: TerminalSession? = null
    private val crashFile: File by lazy { File(filesDir, "last_crash.txt") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                crashFile.writeText(sw.toString())
            } catch (e: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        if (crashFile.exists()) {
            showCrashScreen(crashFile.readText())
            crashFile.delete()
            return
        }

        try {
            setContentView(R.layout.activity_main)

            terminalView = findViewById(R.id.terminalView)
            terminalView.setTextSize(28)
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
                "AndroidTk — overlay: granted — socket port 7191"
            } else {
                "AndroidTk — overlay permission needed for windows"
            }

            startShellSession()
        } catch (t: Throwable) {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            showCrashScreen(sw.toString())
        }
    }

    private fun showCrashScreen(text: String) {
        val tv = TextView(this).apply {
            setText("Last crash:\n\n$text")
            setTextIsSelectable(true)
            textSize = 12f
            setPadding(24, 24, 24, 24)
        }
        setContentView(ScrollView(this).apply { addView(tv) })
    }

    private fun startShellSession() {
        val home = filesDir.absolutePath
        File(home).mkdirs()
        File(home, "bin").mkdirs()

        val env = arrayOf(
            "HOME=$home",
            "TERM=xterm-256color",
            "PATH=$home/bin:/system/bin:/system/xbin",
            "ANDROIDTK_PORT=7191"
        )

        val client = object : TerminalSessionClient {
            override fun onTextChanged(changedSession: TerminalSession) {
                terminalView.onScreenUpdated()
            }
            override fun onTitleChanged(changedSession: TerminalSession) {}
            override fun onSessionFinished(finishedSession: TerminalSession) {}
            override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
            }
            override fun onPasteTextFromClipboard(session: TerminalSession?) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = clipboard.primaryClip?.getItemAt(0)?.text
                if (clip != null) this@MainActivity.session?.write(clip.toString())
            }
            override fun onBell(session: TerminalSession) {}
            override fun onColorsChanged(session: TerminalSession) {}
            override fun onTerminalCursorStateChange(state: Boolean) {}
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

        findViewById<Button>(R.id.keyEsc).setOnClickListener { session?.write("\u001b") }
        findViewById<Button>(R.id.keyTab).setOnClickListener { session?.write("\t") }
        findViewById<Button>(R.id.keyCtrlC).setOnClickListener { session?.write("\u0003") }
        findViewById<Button>(R.id.keyUp).setOnClickListener { session?.write("\u001b[A") }
        findViewById<Button>(R.id.keyDown).setOnClickListener { session?.write("\u001b[B") }
        findViewById<Button>(R.id.keyLeft).setOnClickListener { session?.write("\u001b[D") }
        findViewById<Button>(R.id.keyRight).setOnClickListener { session?.write("\u001b[C") }
        findViewById<Button>(R.id.keySlash).setOnClickListener { session?.write("/") }
        findViewById<Button>(R.id.keyPipe).setOnClickListener { session?.write("|") }
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.finishIfRunning()
    }
}
