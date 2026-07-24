package com.abhiram.androidtk

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

const val SOCKET_NAME = "androidtk.sock"

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var serverSocket: LocalServerSocket? = null

    private val windows = ConcurrentHashMap<Int, FloatingWindow>()
    private val widgets = ConcurrentHashMap<Int, View>()
    @Volatile private var eventWriter: PrintWriter? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundNotification()
        startServer()
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "androidtk", "AndroidTk window server", NotificationManager.IMPORTANCE_MIN
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
            val notif = Notification.Builder(this, "androidtk")
                .setContentTitle("AndroidTk")
                .setContentText("Window server running")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .build()
            startForeground(1, notif)
        }
    }

    private fun startServer() {
        thread(name = "androidtk-accept") {
            try {
                serverSocket = LocalServerSocket(SOCKET_NAME)
                while (true) {
                    val client = serverSocket!!.accept()
                    thread(name = "androidtk-client") { handleClient(client) }
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun handleClient(client: LocalSocket) {
        val reader = BufferedReader(InputStreamReader(client.inputStream))
        val writer = PrintWriter(client.outputStream, true)
        eventWriter = writer
        try {
            var line: String?
            while (true) {
                line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val cmd = JSONObject(line)
                runOnUi { handleCommand(cmd, writer) }
            }
        } catch (e: Exception) {
        } finally {
            if (eventWriter === writer) eventWriter = null
            try { client.close() } catch (e: Exception) {}
        }
    }

    private fun runOnUi(block: () -> Unit) {
        android.os.Handler(mainLooper).post(block)
    }

    private fun handleCommand(cmd: JSONObject, writer: PrintWriter) {
        when (cmd.optString("cmd")) {
            "create_window" -> createWindow(cmd)
            "add_widget" -> addWidget(cmd)
            "set_text" -> setText(cmd)
            "destroy_window" -> destroyWindow(cmd.optInt("win_id"))
            "ping" -> writer.println(JSONObject().put("event", "pong").toString())
        }
    }

    private fun createWindow(cmd: JSONObject) {
        if (!Settings.canDrawOverlays(this)) return
        val winId = cmd.optInt("win_id")
        val title = cmd.optString("title", "Window")
        val width = cmd.optInt("width", 400)
        val height = cmd.optInt("height", 300)

        windows[winId]?.let { destroyWindow(winId) }

        val density = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#212121"))
        }

        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#37474F"))
            setPadding(16, 12, 16, 12)
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleLabel = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeButton = TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            setPadding(24, 0, 8, 0)
        }
        titleBar.addView(titleLabel)
        titleBar.addView(closeButton)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val scroll = ScrollView(this).apply { addView(content) }

        root.addView(titleBar)
        root.addView(scroll)

        val overlayType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            (width * density).toInt(),
            (height * density).toInt(),
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 60
        params.y = 200

        closeButton.setOnClickListener {
            destroyWindow(winId)
            eventWriter?.println(JSONObject().put("event", "window_closed").put("win_id", winId).toString())
        }

        var lastX = 0f
        var lastY = 0f
        titleBar.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX; lastY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - lastX).toInt()
                    val dy = (event.rawY - lastY).toInt()
                    params.x += dx
                    params.y += dy
                    lastX = event.rawX; lastY = event.rawY
                    windowManager.updateViewLayout(root, params)
                    true
                }
                else -> false
            }
        }

        windowManager.addView(root, params)
        windows[winId] = FloatingWindow(root, content, params)
    }

    private fun addWidget(cmd: JSONObject) {
        val winId = cmd.optInt("win_id")
        val win = windows[winId] ?: return
        val widgetId = cmd.optInt("widget_id")
        val type = cmd.optString("type")
        val text = cmd.optString("text", "")

        val view: View = when (type) {
            "label" -> TextView(this).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = 16f
            }
            "button" -> Button(this).apply {
                this.text = text
                setOnClickListener {
                    eventWriter?.println(
                        JSONObject().put("event", "click").put("widget_id", widgetId).toString()
                    )
                }
            }
            "entry" -> EditText(this).apply {
                setText(text)
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) {
                        eventWriter?.println(
                            JSONObject().put("event", "text_changed")
                                .put("widget_id", widgetId)
                                .put("text", s.toString()).toString()
                        )
                    }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
            }
            else -> TextView(this).apply { this.text = "[unknown widget: $type]" }
        }

        view.setPadding(0, 8, 0, 8)
        win.content.addView(view)
        widgets[widgetId] = view
    }

    private fun setText(cmd: JSONObject) {
        val widgetId = cmd.optInt("widget_id")
        val text = cmd.optString("text", "")
        when (val view = widgets[widgetId]) {
            is TextView -> view.text = text
            else -> {}
        }
    }

    private fun destroyWindow(winId: Int) {
        val win = windows.remove(winId) ?: return
        try { windowManager.removeView(win.root) } catch (e: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        try { serverSocket?.close() } catch (e: Exception) {}
        windows.keys.toList().forEach { destroyWindow(it) }
    }

    private data class FloatingWindow(
        val root: View,
        val content: LinearLayout,
        val params: WindowManager.LayoutParams
    )
}
