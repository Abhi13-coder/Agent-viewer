package com.abhiram.androidtk

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.system.Os
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
    private val sessions = mutableListOf<TerminalSession>()
    private var currentIndex = -1
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
            val newSessionButton = findViewById<Button>(R.id.newSessionButton)
            val sessionsButton = findViewById<Button>(R.id.sessionsButton)

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
                "AndroidTk — overlay: granted"
            } else {
                "AndroidTk — overlay permission needed"
            }

            installBundledBusybox()
            installBundledProot()
            installBundledProotDeps()
            installBundledCurl() 
            installBundledAtkScript() 
            ensureDefaultProfile()

            newSessionButton.setOnClickListener {
                createSession()
                switchTo(sessions.size - 1)
            }

            sessionsButton.setOnClickListener { showSessionsDialog() }

            wireExtraKeys()

            // start with one session
            createSession()
            switchTo(0)
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

    /** Busybox is bundled inside the APK as a "native library" — the only
     * location Android 10+ still allows executing from. This REQUIRES
     * android:extractNativeLibs="true" in the manifest (or
     * packagingOptions { jniLibs { useLegacyPackaging = true } } in
     * build.gradle) — otherwise nativeLibraryDir never actually contains
     * libbusybox.so and this whole function silently no-ops.
     *
     * We symlink busybox itself into $HOME/bin, then use `busybox --install -s`
     * (symlinks, not hardlinks — filesDir and nativeLibraryDir can be on
     * different mount points, so hardlinks fail cross-device). */
    private fun installBundledBusybox() {
        val home = filesDir.absolutePath
        val binDir = File(home, "bin").apply { mkdirs() }
        val bundled = File(applicationInfo.nativeLibraryDir, "libbusybox.so")
        val busyboxLink = File(binDir, "busybox")
        val logFile = File(filesDir, "install.log")

        if (!bundled.exists()) {
            logFile.appendText(
                "busybox missing at ${bundled.absolutePath} — check android:extractNativeLibs=\"true\" " +
                    "in AndroidManifest.xml and that libbusybox.so is present under jniLibs/<abi>/\n"
            )
            return
        }
        bundled.setExecutable(true)

        if (!busyboxLink.exists()) {
            try {
                Os.symlink(bundled.absolutePath, busyboxLink.absolutePath)
            } catch (e: Exception) {
                logFile.appendText("busybox symlink failed: $e\n")
                return
            }
        }

        try {
            val proc = ProcessBuilder(busyboxLink.absolutePath, "--install", "-s", binDir.absolutePath)
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            val exit = proc.waitFor()
            if (exit != 0) {
                logFile.appendText("busybox --install exited $exit: $output\n")
            }
        } catch (e: Exception) {
            logFile.appendText("busybox --install failed: $e\n")
        }
    }

    /** Same idea as busybox — proot is bundled as a native library at build
     * time so it's exempt from Android's runtime-download exec restriction.
     * We just symlink it into $HOME/bin; no --install step needed since
     * proot is a single binary, not a multi-applet toolbox like busybox. */
    private fun installBundledProot() {
        val home = filesDir.absolutePath
        val binDir = File(home, "bin").apply { mkdirs() }
        val bundled = File(applicationInfo.nativeLibraryDir, "libproot.so")
        val prootLink = File(binDir, "proot")
        val logFile = File(filesDir, "install.log")
        if (!bundled.exists()) {
            logFile.appendText("proot missing at ${bundled.absolutePath}\n")
            return
        }
        bundled.setExecutable(true)
        if (!prootLink.exists()) {
            try {
                Os.symlink(bundled.absolutePath, prootLink.absolutePath)
            } catch (e: Exception) {
                logFile.appendText("proot symlink failed: $e\n")
            }
        }
    }

    private fun installBundledProotDeps() {
        val home = filesDir.absolutePath
        val libDir = File(home, "lib").apply { mkdirs() }
        val logFile = File(filesDir, "install.log")

        fun linkAs(bundledName: String, sonameNeeded: String) {
            val bundled = File(applicationInfo.nativeLibraryDir, bundledName)
            val link = File(libDir, sonameNeeded)
            if (!bundled.exists()) {
                logFile.appendText("$bundledName missing at ${bundled.absolutePath}\n")
                return
            }
            if (!link.exists()) {
                try {
                    Os.symlink(bundled.absolutePath, link.absolutePath)
                } catch (e: Exception) {
                    logFile.appendText("$bundledName symlink failed: $e\n")
                }
            }
        }

        // proot's NEEDED entries, exactly as readelf reported them:
        linkAs("libtalloc.so", "libtalloc.so.2")
        linkAs("libandroid-shmem.so", "libandroid-shmem.so")
    }

  private fun installBundledCurl() {
    val home = filesDir.absolutePath
    val binDir = File(home, "bin").apply { mkdirs() }
    val bundled = File(applicationInfo.nativeLibraryDir, "libcurl.so")
    val curlLink = File(binDir, "curl")
    val logFile = File(filesDir, "install.log")
    if (!bundled.exists()) {
        logFile.appendText("curl missing at ${bundled.absolutePath}\n")
        return
    }
    bundled.setExecutable(true)
    if (!curlLink.exists()) {
        try {
            Os.symlink(bundled.absolutePath, curlLink.absolutePath)
        } catch (e: Exception) {
            logFile.appendText("curl symlink failed: $e\n")
        }
    }
}

    /** Copies the bundled atk.sh out of assets/ into $HOME once. This is
     * the entire atk package manager — pure shell, no server, no Kotlin
     * logic at runtime. Editing behavior later means editing this asset
     * and rebuilding (or editing $HOME/atk.sh live on-device for a quick
     * test before folding the fix back into the asset). */
    private fun installBundledAtkScript() {
        val dest = File(filesDir, "atk.sh")
        if (dest.exists()) return
        try {
            assets.open("atk.sh").use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest.setExecutable(true)
        } catch (e: Exception) {
            File(filesDir, "install.log").appendText("atk.sh asset copy failed: $e\n")
        }
    }

    /** Seeds a default prompt + the atk() shell function once. This file is
     * sourced automatically by the shell at startup via the ENV env var
     * (see buildEnv) — no manual write() needed, which avoids a race with
     * the pty attaching. The user can permanently change it themselves by
     * editing $HOME/.profile directly. */
    private fun ensureDefaultProfile() {
        val profile = File(filesDir, ".profile")
        if (!profile.exists()) {
            profile.writeText(
                "export PS1='Atk\$ '\n" +
                "atk() { busybox sh \"\$HOME/atk.sh\" \"\$@\"; }\n"
            )
        }
    }

    private fun buildEnv(home: String): Array<String> = arrayOf(
        "HOME=$home",
        "TERM=xterm-256color",
        "PATH=$home/bin:/system/bin:/system/xbin",
        "ANDROIDTK_PORT=7191",
        "ENV=$home/.profile"
    )

    private fun createSession(): TerminalSession {
        val home = filesDir.absolutePath
        val env = buildEnv(home)

        val client = object : TerminalSessionClient {
            override fun onTextChanged(changedSession: TerminalSession) {
                if (changedSession === sessions.getOrNull(currentIndex)) {
                    terminalView.onScreenUpdated()
                }
            }
            override fun onTitleChanged(changedSession: TerminalSession) {}
            override fun onSessionFinished(finishedSession: TerminalSession) {
                runOnUiThread { handleSessionFinished(finishedSession) }
            }
            override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
            }
            override fun onPasteTextFromClipboard(session: TerminalSession?) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = clipboard.primaryClip?.getItemAt(0)?.text
                if (clip != null) session?.write(clip.toString())
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

        val session = TerminalSession(
            "/system/bin/sh",
            home,
            arrayOf("/system/bin/sh"),
            env,
            2000,
            client
        )

        sessions.add(session)
        return session
    }

    private fun switchTo(index: Int) {
        if (index !in sessions.indices) return
        currentIndex = index
        val viewClient = buildViewClient()
        terminalView.attachSession(sessions[index])
        terminalView.setTerminalViewClient(viewClient)
        terminalView.requestFocus()
        findViewById<TextView>(R.id.statusText).text =
            "AndroidTk — session ${index + 1}/${sessions.size}"
    }

    private fun handleSessionFinished(finished: TerminalSession) {
        val idx = sessions.indexOf(finished)
        if (idx == -1) return
        sessions.removeAt(idx)
        if (sessions.isEmpty()) {
            createSession()
            switchTo(0)
        } else {
            switchTo((idx - 1).coerceIn(0, sessions.size - 1))
        }
    }

    private fun showSessionsDialog() {
        val labels = sessions.indices.map { i ->
            if (i == currentIndex) "Session ${i + 1} (current)" else "Session ${i + 1}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Sessions")
            .setItems(labels) { _, which -> switchTo(which) }
            .setNegativeButton("Close current") { _, _ ->
                sessions.getOrNull(currentIndex)?.finishIfRunning()
            }
            .setPositiveButton("Cancel", null)
            .show()
    }

    private fun buildViewClient(): TerminalViewClient = object : TerminalViewClient {
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

    private fun wireExtraKeys() {
        fun current() = sessions.getOrNull(currentIndex)
        findViewById<Button>(R.id.keyEsc).setOnClickListener { current()?.write("\u001b") }
        findViewById<Button>(R.id.keyTab).setOnClickListener { current()?.write("\t") }
        findViewById<Button>(R.id.keyCtrlC).setOnClickListener { current()?.write("\u0003") }
        findViewById<Button>(R.id.keyUp).setOnClickListener { current()?.write("\u001b[A") }
        findViewById<Button>(R.id.keyDown).setOnClickListener { current()?.write("\u001b[B") }
        findViewById<Button>(R.id.keyLeft).setOnClickListener { current()?.write("\u001b[D") }
        findViewById<Button>(R.id.keyRight).setOnClickListener { current()?.write("\u001b[C") }
        findViewById<Button>(R.id.keySlash).setOnClickListener { current()?.write("/") }
        findViewById<Button>(R.id.keyPipe).setOnClickListener { current()?.write("|") }
    }

    override fun onDestroy() {
        super.onDestroy()
        sessions.forEach { it.finishIfRunning() }
    }
}
