package com.abhiram.androidtk

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket

/**
 * Background service hosting a loopback-only TCP listener on port 7191.
 * The `atk` shell function (defined in .profile) talks to this over
 * `busybox nc 127.0.0.1 7191`, so real shell sessions can trigger real
 * Kotlin-side package management without ever needing `atk` itself to
 * be an executable file.
 */
class AtkServerService : Service() {

    private var serverThread: Thread? = null
    private var serverSocket: ServerSocket? = null
    private lateinit var atk: AtkPackageManager

    override fun onCreate() {
        super.onCreate()

        val arch = when {
            Build.SUPPORTED_ABIS.contains("arm64-v8a") -> "aarch64"
            Build.SUPPORTED_ABIS.contains("armeabi-v7a") -> "arm"
            else -> "arm" // safe fallback
        }

        atk = AtkPackageManager(filesDir, applicationInfo.nativeLibraryDir, arch)

        serverThread = Thread {
            try {
                serverSocket = ServerSocket(7191, 50, InetAddress.getLoopbackAddress())
                while (!Thread.currentThread().isInterrupted) {
                    val client = serverSocket?.accept() ?: break
                    Thread { handleClient(client) }.start()
                }
            } catch (e: Exception) {
                // socket closed on service stop, or bind failed — nothing to recover from here
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
