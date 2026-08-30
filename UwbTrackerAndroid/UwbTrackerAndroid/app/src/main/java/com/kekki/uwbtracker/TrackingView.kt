package com.kekki.uwbtracker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

data class AnchorDef(val label: String, val x: Float, val y: Float)

class TrackingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ---- Config: match your real anchor placement (meters) ----
    private val anchors = listOf(
        AnchorDef("A1", 0f, 0f),
        AnchorDef("A2", 5f, 0f),
        AnchorDef("A3", 0f, 5f)
    )
    private val listenPort = 5005
    private val maxDistBar = 8f // meters, for bar scaling
    private val smooth = 0.15f
    private val staleMs = 2500L

    // ---- Live raw values (updated from UDP thread) ----
    @Volatile private var rawX = 0f
    @Volatile private var rawY = 0f
    @Volatile private var havePosition = false
    @Volatile private var lastPacketTime = 0L

    private val rawDist = FloatArray(3) { -1f }
    private val distTime = LongArray(3) { 0L }

    // ---- Smoothed/displayed values (touched only on UI thread) ----
    private var dispX = 0f
    private var dispY = 0f
    private val dispDist = FloatArray(3) { -1f }

    // ---- Trail (recent, capped) + full persistent path ----
    private val trail = ArrayList<FloatArray>() // [x,y]
    private val maxTrail = 150
    private val fullPath = ArrayList<FloatArray>()
    private lateinit var pathFile: File

    // ---- Pan/zoom state ----
    private var scaleFactor = 60f // pixels per meter
    private var offsetX = 0f
    private var offsetY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private lateinit var scaleDetector: ScaleGestureDetector

    // ---- Panel visibility ----
    var showPanels = true

    // ---- Paints ----
    private val bgPaint = Paint().apply { color = Color.rgb(15, 15, 15) }
    private val gridPaint = Paint().apply { color = Color.rgb(45, 45, 45); strokeWidth = 1f }
    private val anchorPaint = Paint().apply { color = Color.rgb(55, 138, 221); isAntiAlias = true }
    private val tagPaint = Paint().apply { color = Color.rgb(0, 255, 120); isAntiAlias = true }
    private val trailPaint = Paint().apply {
        color = Color.argb(180, 0, 255, 120); style = Paint.Style.STROKE
        strokeWidth = 5f; isAntiAlias = true
    }
    private val fullPathPaint = Paint().apply {
        color = Color.argb(90, 80, 180, 255); style = Paint.Style.STROKE
        strokeWidth = 3f; isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        color = Color.rgb(210, 210, 210); textSize = 32f; isAntiAlias = true
    }
    private val panelBgPaint = Paint().apply { color = Color.argb(170, 25, 25, 25); isAntiAlias = true }
    private val panelBorderPaint = Paint().apply {
        color = Color.rgb(60, 60, 60); style = Paint.Style.STROKE; strokeWidth = 2f; isAntiAlias = true
    }
    private val barBgPaint = Paint().apply { color = Color.rgb(45, 45, 45) }
    private val barFillPaint = Paint().apply { color = Color.rgb(55, 160, 220) }

    init {
        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor *= detector.scaleFactor
                scaleFactor = max(20f, min(scaleFactor, 300f))
                return true
            }
        })
        pathFile = File(context.filesDir, "tag_path.csv")
        loadStoredPath()
        startUdpListener()
        // Redraw loop (~30fps) so smoothing animates even without new packets
        postDelayed(object : Runnable {
            override fun run() {
                invalidate()
                postDelayed(this, 33)
            }
        }, 33)
    }

    // ---------------- UDP ----------------

    private fun startUdpListener() {
        thread(isDaemon = true) {
            try {
                val socket = DatagramSocket(listenPort)
                val buf = ByteArray(256)
                while (true) {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val msg = String(packet.data, 0, packet.length).trim()
                    handleMessage(msg)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun handleMessage(msg: String) {
        if (msg.startsWith("POS,")) {
            val parts = msg.split(",")
            if (parts.size >= 3) {
                val x = parts[1].toFloatOrNull()
                val y = parts[2].toFloatOrNull()
                if (x != null && y != null) updatePosition(x, y)
            }
        } else if (msg.startsWith("DIST,")) {
            val parts = msg.split(",")
            if (parts.size == 3) {
                val idx = parts[1].toIntOrNull()
                val d = parts[2].toFloatOrNull()
                if (idx != null && idx in 0..2 && d != null) {
                    rawDist[idx] = d
                    distTime[idx] = System.currentTimeMillis()
                }
            }
        }
    }

    private fun updatePosition(x: Float, y: Float) {
        rawX = x
        rawY = y
        havePosition = true
        lastPacketTime = System.currentTimeMillis()

        synchronized(trail) {
            trail.add(floatArrayOf(x, y))
            if (trail.size > maxTrail) trail.removeAt(0)
        }

        val movedEnough = if (fullPath.isEmpty()) true else {
            val last = fullPath[fullPath.size - 1]
            val dx = last[0] - x
            val dy = last[1] - y
            kotlin.math.sqrt(dx * dx + dy * dy) > 0.05f
        }
        if (movedEnough) {
            synchronized(fullPath) {
                fullPath.add(floatArrayOf(x, y))
            }
            appendPathToFile(x, y)
        }
    }

    // ---------------- Persistent path storage ----------------

    private fun loadStoredPath() {
        try {
            if (!pathFile.exists()) return
            pathFile.forEachLine { line ->
                if (line.startsWith("x")) return@forEachLine // header
                val parts = line.split(",")
                if (parts.size >= 2) {
                    val x = parts[0].toFloatOrNull()
                    val y = parts[1].toFloatOrNull()
                    if (x != null && y != null) fullPath.add(floatArrayOf(x, y))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun appendPathToFile(x: Float, y: Float) {
        thread(isDaemon = true) {
            try {
                val isNew = !pathFile.exists()
                pathFile.appendText(
                    (if (isNew) "x,y,timestamp\n" else "") +
                        "$x,$y,${System.currentTimeMillis()}\n"
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ---------------- Touch: pan + pinch zoom ----------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress) {
                    offsetX += event.x - lastTouchX
                    offsetY += event.y - lastTouchY
                    lastTouchX = event.x
                    lastTouchY = event.y
                    invalidate()
                }
            }
        }
        return true
    }

    // ---------------- Drawing ----------------

    private fun toScreenX(mx: Float) = width / 2f + offsetX + mx * scaleFactor
    private fun toScreenY(my: Float) = height / 2f + offsetY - my * scaleFactor // flip Y (up = +Y in meters)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Smooth toward latest raw readings
        dispX += (rawX - dispX) * smooth
        dispY += (rawY - dispY) * smooth
        for (i in 0..2) {
            if (rawDist[i] >= 0) {
                dispDist[i] = if (dispDist[i] < 0) rawDist[i] else dispDist[i] + (rawDist[i] - dispDist[i]) * smooth
            }
        }

        drawGrid(canvas)
        drawFullPath(canvas)
        drawTrail(canvas)
        drawAnchors(canvas)
        drawTag(canvas)

        if (showPanels) {
            drawPositionPanel(canvas)
            drawDistancePanel(canvas)
        }
        drawStatus(canvas)
    }

    private fun drawGrid(canvas: Canvas) {
        val step = 1f // meter
        val left = -20; val right = 20
        for (gx in left..right) {
            val sx = toScreenX(gx * step)
            canvas.drawLine(sx, 0f, sx, height.toFloat(), gridPaint)
        }
        for (gy in left..right) {
            val sy = toScreenY(gy * step)
            canvas.drawLine(0f, sy, width.toFloat(), sy, gridPaint)
        }
    }

    private fun drawAnchors(canvas: Canvas) {
        for (a in anchors) {
            val sx = toScreenX(a.x)
            val sy = toScreenY(a.y)
            canvas.drawCircle(sx, sy, 16f, anchorPaint)
            canvas.drawText("${a.label} (${a.x},${a.y})", sx - 40f, sy - 24f, textPaint)
        }
    }

    private fun drawTrail(canvas: Canvas) {
        val snapshot = synchronized(trail) { trail.toList() }
        if (snapshot.size < 2) return
        val path = android.graphics.Path()
        path.moveTo(toScreenX(snapshot[0][0]), toScreenY(snapshot[0][1]))
        for (p in snapshot.drop(1)) path.lineTo(toScreenX(p[0]), toScreenY(p[1]))
        canvas.drawPath(path, trailPaint)
    }

    private fun drawFullPath(canvas: Canvas) {
        val snapshot = synchronized(fullPath) { fullPath.toList() }
        if (snapshot.size < 2) return
        val path = android.graphics.Path()
        path.moveTo(toScreenX(snapshot[0][0]), toScreenY(snapshot[0][1]))
        for (p in snapshot.drop(1)) path.lineTo(toScreenX(p[0]), toScreenY(p[1]))
        canvas.drawPath(path, fullPathPaint)
    }

    private fun drawTag(canvas: Canvas) {
        if (!havePosition) return
        val sx = toScreenX(dispX)
        val sy = toScreenY(dispY)
        canvas.drawCircle(sx, sy, 20f, tagPaint)
        canvas.drawText("(${"%.2f".format(dispX)}, ${"%.2f".format(dispY)})", sx + 24f, sy + 8f, textPaint)
    }

    private fun drawStatus(canvas: Canvas) {
        val fresh = havePosition && (System.currentTimeMillis() - lastPacketTime < 3000)
        val statusPaint = Paint(textPaint).apply {
            color = if (fresh) Color.rgb(0, 255, 120) else Color.rgb(255, 90, 90)
        }
        val statusText = if (fresh) "Tracking (live)" else if (havePosition) "Signal lost" else "Waiting for data..."
        canvas.drawText(statusText, 24f, height - 70f, statusPaint)
        canvas.drawText("Drag = pan | Pinch = zoom | Tap panel toggle top-right", 24f, height - 34f, Paint(textPaint).apply { color = Color.rgb(150,150,150); textSize = 26f })
    }

    private fun roundRect(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float, fillPaint: Paint, borderPaint: Paint) {
        val rect = RectF(x, y, x + w, y + h)
        canvas.drawRoundRect(rect, r, r, fillPaint)
        canvas.drawRoundRect(rect, r, r, borderPaint)
    }

    private fun drawPositionPanel(canvas: Canvas) {
        val panelW = 340f
        val panelH = 200f
        val panelX = width / 2f - panelW / 2f
        val panelY = 20f
        roundRect(canvas, panelX, panelY, panelW, panelH, 20f, panelBgPaint, panelBorderPaint)

        val pad = 28f
        canvas.drawText("Position", panelX + pad, panelY + pad + 10f, Paint(textPaint).apply { textSize = 28f })

        val fresh = havePosition && (System.currentTimeMillis() - lastPacketTime < 3000)
        val valuePaint = Paint(textPaint).apply {
            color = if (fresh) Color.rgb(0, 255, 120) else Color.rgb(255, 90, 90)
            textSize = 30f
        }
        val labelPaint = Paint(textPaint).apply { textSize = 30f }

        val rows = listOf("X" to dispX, "Y" to dispY)
        var rowY = panelY + pad + 60f
        for ((label, value) in rows) {
            canvas.drawText(label, panelX + pad, rowY, labelPaint)
            canvas.drawText("%.2f m".format(value), panelX + panelW - pad - 130f, rowY, valuePaint)
            rowY += 55f
        }
    }

    private fun drawDistancePanel(canvas: Canvas) {
        val panelW = 460f
        val panelH = 260f
        val panelX = width - panelW - 24f
        val panelY = 20f
        roundRect(canvas, panelX, panelY, panelW, panelH, 20f, panelBgPaint, panelBorderPaint)

        val pad = 28f
        canvas.drawText("Anchor distances", panelX + pad, panelY + pad + 10f, Paint(textPaint).apply { textSize = 28f })

        val barW = panelW - pad * 2 - 130f
        val barH = 20f
        var rowY = panelY + pad + 50f

        for (i in 0..2) {
            val fresh = rawDist[i] >= 0 && (System.currentTimeMillis() - distTime[i] < staleMs)
            canvas.drawText(anchors[i].label, panelX + pad, rowY + 16f, Paint(textPaint).apply { textSize = 28f })

            val barX = panelX + pad + 60f
            canvas.drawRoundRect(RectF(barX, rowY, barX + barW, rowY + barH), barH / 2, barH / 2, barBgPaint)

            if (fresh && dispDist[i] >= 0) {
                val frac = (dispDist[i] / maxDistBar).coerceIn(0f, 1f)
                canvas.drawRoundRect(RectF(barX, rowY, barX + barW * frac, rowY + barH), barH / 2, barH / 2, barFillPaint)
                canvas.drawText("%.2f m".format(dispDist[i]), barX + barW + 12f, rowY + 16f, Paint(textPaint).apply { textSize = 26f })
            } else {
                canvas.drawText("no signal", barX + barW + 12f, rowY + 16f, Paint(textPaint).apply { textSize = 26f; color = Color.rgb(255,90,90) })
            }
            rowY += 65f
        }
    }

    // Simple tap to toggle panels: tap in top area outside any specific control
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
