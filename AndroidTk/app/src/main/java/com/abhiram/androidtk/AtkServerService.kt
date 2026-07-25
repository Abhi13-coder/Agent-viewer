package com.abhiram.androidtk

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket

class AtkServerService : Service() {

    private var serverThread: Thread? = null
    private var serverSocket: ServerSocket? = null
    private lateinit var atk: AtkPackageManager
    private val logFile: File by lazy { File(filesDir, "install.log") }

    override fun onCreate() {
        super.onCreate()

        val arch = when {
            Build.SUPPORTED_ABIS.contains("arm64-v8a") -> "aarch64"
            Build.SUPPORTED_ABIS.contains("armeabi-v7a") -> "arm"
            else -> "arm"
        }

        atk = AtkPackageManager(filesDir, applicationInfo.nativeLibraryDir, arch)

        serverThread = Thread {
            try {
                serverSocket = ServerSocket(7191, 50, InetAddress.getLoopbackAddress())
                logFile.appendText("AtkServerService: listening on 127.0.0.1:7191\n")
                while (!Thread.currentThread().isInterrupted) {
                    val client = serverSocket?.accept() ?: break
                    Thread { handleClient(client) }.start()
                }
            } catch (e: Exception) {
                logFile.appendText("AtkServerService: FAILED to bind/listen: $e\n")
            }
        }
        serverThread?.isDaemon = true
        serverThread?.start()
    }

    private fun handleClient(socket: java.net.Socket) {
        socket.use { s ->
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val writer = PrintWriter(s.getOutputStream(), true)

            val line = reader.readLine() ?: return
            logFile.appendText("AtkServerService: received command: $line\n")
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.isEmpty()) return

            val cmd = parts[0]
            val arg = parts.getOrNull(1)

            try {
                when (cmd) {
                    "install" -> {
                        if (arg == null) { writer.println("usage: atk install <name>"); return }
                        atk.install(arg) { msg -> writer.println(msg) }
                    }
                    "installed" -> {
                        writer.println(if (arg != null && atk.isInstalled(arg)) "yes" else "no")
                    }
                    "refresh" -> {
                        writer.println("refreshing index...")
                        val list = atk.refreshIndex()
                        writer.println("index has ${list.size} packages")
                    }
                    else -> writer.println("unknown command: $cmd (supported: install, installed, refresh)")
                }
            } catch (e: Exception) {
                logFile.appendText("AtkServerService: error handling '$line': $e\n")
                writer.println("error: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serverThread?.interrupt()
        try { serverSocket?.close() } catch (e: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
    
