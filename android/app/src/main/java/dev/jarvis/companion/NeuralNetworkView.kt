package dev.jarvis.companion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * The live JARVIS network.
 *
 * The shape is the architecture: the central core in the middle, the six
 * engines in a compact ring around it with connections that cross, and the
 * six clusters further out on a wider ring. The colours and the pulses come
 * from the real state of each node and link, so the picture shows what the
 * system is actually doing.
 */
class NeuralNetworkView(context: Context) : View(context) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
    }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
    }

    private var snapshot: NetworkSnapshot? = null
    private var phase = 0f
    private var running = true
    private var selectedId: String = ""
    private val nodeCentres = mutableMapOf<String, Pair<Float, Float>>()
    private val nodeRadii = mutableMapOf<String, Float>()

    /** Called when the user taps a node. */
    var onNodeSelected: ((String) -> Unit)? = null

    init {
        isClickable = true
        contentDescription = "The live JARVIS network. Tap a node to inspect it."
    }

    fun update(next: NetworkSnapshot) {
        snapshot = next
        invalidate()
    }

    fun setSelected(nodeId: String) {
        selectedId = nodeId
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = snapshot
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) * 0.44f

        drawBackdrop(canvas, cx, cy, radius)

        if (current == null || current.nodes.isEmpty()) {
            drawWaiting(canvas, cx, cy)
            if (running) postInvalidateOnAnimation()
            return
        }

        phase += 0.02f
        val centres = mutableMapOf<String, Pair<Float, Float>>()
        for (node in current.nodes) {
            centres[node.nodeId] = Pair(
                cx + (node.x - 0.5f) * radius * 2f,
                cy + (node.y - 0.5f) * radius * 2f,
            )
        }
        nodeCentres.clear()
        nodeCentres.putAll(centres)

        drawLinks(canvas, current, centres)

        // The core is drawn last so it sits on top of the connections.
        val core = current.nodes.firstOrNull { it.isCore }
        val satellites = current.nodes.filter { it.isCluster }
        val engines = current.nodes.filter { it.isEngine }

        for (node in engines) drawEngineNode(canvas, node, centres[node.nodeId])
        for (node in satellites) drawClusterNode(canvas, node, centres[node.nodeId])
        if (core != null) drawCoreNode(canvas, core, centres[core.nodeId])

        if (running) postInvalidateOnAnimation()
    }

    /** The deep background with a faint blue energy field. */
    private fun drawBackdrop(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        fillPaint.shader = RadialGradient(
            cx,
            cy,
            radius * 1.7f,
            intArrayOf(0xFF0B1A26.toInt(), 0xFF060C13.toInt(), 0xFF03060A.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)
        fillPaint.shader = null

        // A faint grid so the network reads as a scientific display.
        linePaint.strokeWidth = 1f
        linePaint.color = 0x0F1B3A4A
        val step = radius / 4f
        var x = cx % step
        while (x < width) {
            canvas.drawLine(x, 0f, x, height.toFloat(), linePaint)
            x += step
        }
        var y = cy % step
        while (y < height) {
            canvas.drawLine(0f, y, width.toFloat(), y, linePaint)
            y += step
        }
    }

    private fun drawWaiting(canvas: Canvas, cx: Float, cy: Float) {
        textPaint.color = 0xFF5C7B8C.toInt()
        textPaint.textSize = min(width, height) * 0.035f
        canvas.drawText("Waiting for the backend", cx, cy, textPaint)
    }

    /** The connections between the nodes, with a pulse on the live ones. */
    private fun drawLinks(
        canvas: Canvas,
        current: NetworkSnapshot,
        centres: Map<String, Pair<Float, Float>>,
    ) {
        for (link in current.links) {
            val from = centres[link.source] ?: continue
            val to = centres[link.destination] ?: continue
            val colour = NetworkPalette.forLink(link.status)
            val path = Path()
            path.moveTo(from.first, from.second)
            path.lineTo(to.first, to.second)

            // The faint base of every connection.
            linePaint.color = 0x33234A5E
            linePaint.strokeWidth = 1.2f
            canvas.drawPath(path, linePaint)

            if (link.isActive || link.isFailed || link.isSuccessful) {
                linePaint.color = colour
                linePaint.strokeWidth = if (link.status == "transmitting") 2.4f else 1.6f
                canvas.drawPath(path, linePaint)

                // A pulse that travels along a transmitting connection.
                if (link.status == "transmitting") {
                    val measure = PathMeasure(path, false)
                    val position = FloatArray(2)
                    measure.getPosTan((phase * 60f) % measure.length, position, null)
                    glowPaint.shader = RadialGradient(
                        position[0], position[1], 14f,
                        intArrayOf(colour, Color.TRANSPARENT),
                        null,
                        Shader.TileMode.CLAMP,
                    )
                    canvas.drawCircle(position[0], position[1], 14f, glowPaint)
                    glowPaint.shader = null
                }
            }
        }
    }

    /** The central core: a bright node with concentric rings. */
    private fun drawCoreNode(canvas: Canvas, node: NetworkNode, centre: Pair<Float, Float>?) {
        val (x, y) = centre ?: return
        val colour = NetworkPalette.forStatus(node.status)
        val coreRadius = min(width, height) * 0.075f
        nodeRadii[node.nodeId] = coreRadius

        glowPaint.shader = RadialGradient(
            x, y, coreRadius * 3.4f,
            intArrayOf(
                colour and 0x00FFFFFF or (0x66 shl 24),
                colour and 0x00FFFFFF or (0x18 shl 24),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(x, y, coreRadius * 3.4f, glowPaint)
        glowPaint.shader = null

        // The rotating rings around the core.
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeWidth = 1.6f
        linePaint.color = colour and 0x00FFFFFF or (0xAA shl 24)
        val ring = RectF(x - coreRadius * 1.55f, y - coreRadius * 1.55f,
            x + coreRadius * 1.55f, y + coreRadius * 1.55f)
        canvas.drawArc(ring, phase * 60f, 110f, false, linePaint)
        canvas.drawArc(ring, phase * 60f + 180f, 110f, false, linePaint)

        val inner = RectF(x - coreRadius * 1.2f, y - coreRadius * 1.2f,
            x + coreRadius * 1.2f, y + coreRadius * 1.2f)
        linePaint.strokeWidth = 1.1f
        canvas.drawArc(inner, -phase * 90f, 150f, false, linePaint)

        fillPaint.shader = RadialGradient(
            x, y, coreRadius,
            intArrayOf(0xFFFFFFFF.toInt(), colour, colour and 0x00FFFFFF or (0xDD shl 24)),
            floatArrayOf(0f, 0.35f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(x, y, coreRadius, fillPaint)
        fillPaint.shader = null

        drawLabel(canvas, node.name, x, y + coreRadius * 2.5f, 11f, 0xFFE8F4FA.toInt(), true)
        drawStatus(canvas, node, x, y + coreRadius * 3.6f)
    }

    /** An inner engine: a thin hexagonal outline. */
    private fun drawEngineNode(canvas: Canvas, node: NetworkNode, centre: Pair<Float, Float>?) {
        val (x, y) = centre ?: return
        val colour = NetworkPalette.forStatus(node.status)
        val radius = min(width, height) * 0.045f
        nodeRadii[node.nodeId] = radius
        val selected = node.nodeId == selectedId

        val path = Path()
        for (index in 0..6) {
            val angle = Math.toRadians((index * 60 + 30).toDouble())
            val px = x + cos(angle).toFloat() * radius
            val py = y + sin(angle).toFloat() * radius
            if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()

        fillPaint.color = colour and 0x00FFFFFF or (0x22 shl 24)
        canvas.drawPath(path, fillPaint)

        linePaint.style = Paint.Style.STROKE
        linePaint.strokeWidth = if (selected) 2.6f else 1.5f
        linePaint.color = colour
        canvas.drawPath(path, linePaint)

        if (selected) {
            linePaint.strokeWidth = 1f
            linePaint.color = 0x88FFFFFF.toInt()
            val outer = RectF(x - radius * 1.5f, y - radius * 1.5f,
                x + radius * 1.5f, y + radius * 1.5f)
            canvas.drawArc(outer, phase * 40f, 300f, false, linePaint)
        }

        // The state is shown as a small bar under the node.
        drawStatus(canvas, node, x, y + radius * 1.9f)
    }

    /** An outer cluster: a larger geometric satellite with an inner ring. */
    private fun drawClusterNode(canvas: Canvas, node: NetworkNode, centre: Pair<Float, Float>?) {
        val (x, y) = centre ?: return
        val colour = NetworkPalette.forStatus(node.status)
        val radius = min(width, height) * 0.055f
        nodeRadii[node.nodeId] = radius
        val selected = node.nodeId == selectedId

        // An octagon, so the satellites read differently from the engines.
        val path = Path()
        for (index in 0..8) {
            val angle = Math.toRadians((index * 45 + 22).toDouble())
            val px = x + cos(angle).toFloat() * radius
            val py = y + sin(angle).toFloat() * radius
            if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()

        glowPaint.shader = RadialGradient(
            x, y, radius * 2.2f,
            intArrayOf(
                colour and 0x00FFFFFF or (0x2E shl 24),
                Color.TRANSPARENT,
            ),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(x, y, radius * 2.2f, glowPaint)
        glowPaint.shader = null

        fillPaint.color = colour and 0x00FFFFFF or (0x1E shl 24)
        canvas.drawPath(path, fillPaint)

        linePaint.style = Paint.Style.STROKE
        linePaint.strokeWidth = if (selected) 2.6f else 1.5f
        linePaint.color = colour
        canvas.drawPath(path, linePaint)

        // A dashed inner ring.
        linePaint.strokeWidth = 1f
        linePaint.color = colour and 0x00FFFFFF or (0x99 shl 24)
        val inner = RectF(x - radius * 0.55f, y - radius * 0.55f,
            x + radius * 0.55f, y + radius * 0.55f)
        canvas.drawArc(inner, phase * 25f, 260f, false, linePaint)
        canvas.drawArc(inner, phase * 25f + 180f, 260f, false, linePaint)

        drawLabel(canvas, shortName(node.name), x, y + radius * 1.9f, 9f, 0xFFBBD4E4.toInt(), false)
        drawStatus(canvas, node, x, y + radius * 2.7f)
    }

    private fun drawLabel(
        canvas: Canvas,
        label: String,
        x: Float,
        y: Float,
        sizeSp: Float,
        colour: Int,
        bold: Boolean,
    ) {
        val paint = if (bold) textPaint else smallPaint
        paint.color = colour
        paint.textSize = sizeSp * resources.displayMetrics.scaledDensity
        canvas.drawText(label, x, y, paint)
    }

    /** The state of a node, written under it. */
    private fun drawStatus(canvas: Canvas, node: NetworkNode, x: Float, y: Float) {
        val paint = smallPaint
        paint.textSize = 8f * resources.displayMetrics.scaledDensity
        paint.color = NetworkPalette.forStatus(node.status) and 0x00FFFFFF or (0xCC shl 24)
        canvas.drawText(node.status.replace('_', ' '), x, y, paint)
    }

    private fun shortName(name: String): String {
        val words = name.split(' ')
        return if (words.size <= 2) name else words.take(2).joinToString(" ")
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            var best = ""
            var bestDistance = Float.MAX_VALUE
            for ((id, centre) in nodeCentres) {
                val distance = hypot(event.x - centre.first, event.y - centre.second)
                val radius = (nodeRadii[id] ?: 0f) * 1.6f
                if (distance < radius && distance < bestDistance) {
                    bestDistance = distance
                    best = id
                }
            }
            if (best.isNotEmpty()) {
                selectedId = best
                onNodeSelected?.invoke(best)
                performClick()
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
