package com.abhi.agentviewer

import android.app.PictureInPictureParams
import android.app.Activity
import android.os.Bundle
import android.util.Rational
import android.os.Handler
import android.os.Looper

class MainActivity : Activity() {
    private lateinit var gridView: GridView
    private val handler = Handler(Looper.getMainLooper())
    private val pollIntervalMs = 300L

    private val pollRunnable = object : Runnable {
        override fun run() {
            gridView.reloadState()
            handler.postDelayed(this, pollIntervalMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gridView = GridView(this)
        setContentView(gridView)
        handler.post(pollRunnable)
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollRunnable)
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(3, 4))
            .build()
        enterPictureInPictureMode(params)
    }
}
