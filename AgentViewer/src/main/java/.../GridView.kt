package com.abhi.agentviewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import org.json.JSONObject
import java.io.File

class GridView(context: Context) : View(context) {

    private val statePath = "/data/data/com.termux/files/home/agent_state.json"
    private val bgPaint = Paint().apply { color = Color.BLACK }
    private val gridPaint = Paint().apply { color = Color.DKGRAY; strokeWidth = 1f }
    private val agentPaint = Paint().apply { color = Color.GREEN }
    private val foodPaint = Paint().apply { color = Color.YELLOW }
    private val energyPaint = Paint().apply { color = Color.RED }
    private val textPaint = Paint().apply { color = Color.WHITE; textSize = 28f }

    private var gridSize = 10
    private var agentX = 0
    private var agentY = 0
    private var energy = 100
    private var foodList: List<Pair<Int, Int>> = emptyList()

    fun reloadState() {
        try {
            val file = File(statePath)
            if (!file.exists()) return
            val json = JSONObject(file.readText())
            gridSize = json.optInt("grid_size", 10)
            agentX = json.optInt("agent_x", 0)
            agentY = json.optInt("agent_y", 0)
            energy = json.optInt("energy", 100)
            val foods = json.optJSONArray("food")
            val list = mutableListOf<Pair<Int, Int>>()
            if (foods != null) {
                for (i in 0 until foods.length()) {
                    val f = foods.getJSONArray(i)
                    list.add(Pair(f.getInt(0), f.getInt(1)))
                }
            }
            foodList = list
            invalidate()
        } catch (e: Exception) {
            // malformed/partial write during tick - ignore, retry next poll
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val gridArea = height - 80f
        val cell = minOf(width.toFloat(), gridArea) / gridSize

        for (i in 0..gridSize) {
            canvas.drawLine(i * cell, 0f, i * cell, gridSize * cell, gridPaint)
            canvas.drawLine(0f, i * cell, gridSize * cell, i * cell, gridPaint)
        }

        for ((fx, fy) in foodList) {
            canvas.drawCircle(fx * cell + cell / 2, fy * cell + cell / 2, cell / 4, foodPaint)
        }

        canvas.drawCircle(agentX * cell + cell / 2, agentY * cell + cell / 2, cell / 3, agentPaint)

        val barY = gridSize * cell + 20f
        canvas.drawRect(10f, barY, 10f + (energy * 2f), barY + 20f, energyPaint)
        canvas.drawText("Energy: $energy", 10f, barY + 60f, textPaint)
    }
}
