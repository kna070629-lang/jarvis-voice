package dev.jarvis.companion

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * The live network interface.
 *
 * It shows the real state of every node and connection, and a node can be
 * selected to see what it did, what it can do and what went wrong. It is an
 * additional view of the system. The conversation interface stays exactly
 * where it was.
 */
class NetworkActivity : Activity() {

    private lateinit var network: NeuralNetworkView
    private lateinit var detail: TextView
    private lateinit var stats: TextView
    private lateinit var status: TextView
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var selected = ""
    private var polling = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val background = Color.rgb(3, 6, 10)
        val cyan = Color.rgb(79, 216, 255)
        val white = Color.rgb(232, 244, 250)
        val muted = Color.rgb(140, 165, 180)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(background)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "JARVIS  NEURAL  NETWORK"
            setTextColor(white)
            textSize = 15f
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            letterSpacing = 0.12f
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(40), 1f))
        val refresh = Button(this).apply {
            text = "Refresh"
            setTextColor(cyan)
            setOnClickListener { fetch() }
        }
        header.addView(refresh, LinearLayout.LayoutParams(dp(88), dp(40)))
        root.addView(header)

        status = TextView(this).apply {
            text = "Connecting to the local backend…"
            setTextColor(muted)
            textSize = 11f
        }
        root.addView(status)

        network = NeuralNetworkView(this)
        network.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(330),
        )
        network.onNodeSelected = { nodeId -> showNode(nodeId) }
        root.addView(network)

        stats = TextView(this).apply {
            setTextColor(muted)
            textSize = 10f
        }
        root.addView(stats)

        val scroll = ScrollView(this)
        detail = TextView(this).apply {
            setTextColor(white)
            textSize = 12f
            setPadding(dp(4), dp(8), dp(4), dp(24))
            text = "Tap a node to inspect it."
        }
        scroll.addView(detail, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        polling = true
        fetch()
        main.postDelayed(poll, POLL_MS)
    }

    override fun onPause() {
        polling = false
        super.onPause()
    }

    override fun onDestroy() {
        polling = false
        io.shutdownNow()
        super.onDestroy()
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!polling) return
            fetch()
            main.postDelayed(this, POLL_MS)
        }
    }

    /** Fetch the live topology from the backend. */
    private fun fetch() {
        val token = TokenStore.load(this)
        if (token.isNullOrBlank()) {
            status.text = "No pairing token. Save it in JARVIS first."
            return
        }
        io.execute {
            val snapshot = BackendClient.fetchNetwork(this, token)
            main.post {
                if (snapshot == null) {
                    status.text = "The backend did not answer."
                    return@post
                }
                status.text = "Live · ${snapshot.nodes.size} nodes · ${snapshot.links.size} connections"
                network.update(snapshot)
                stats.text = describeStats(snapshot.stats)
                if (selected.isNotEmpty()) showNode(selected)
            }
        }
    }

    private fun describeStats(stats: Map<String, Any>): String {
        val parts = mutableListOf<String>()
        for (key in listOf("tasks_run", "tasks_failed", "agents")) {
            val value = stats[key]
            if (value != null) parts.add("$key $value")
        }
        (stats["memory"] as? Map<*, *>)?.let { memory ->
            memory["turns"]?.let { parts.add("turns $it") }
            memory["facts"]?.let { parts.add("facts $it") }
        }
        (stats["security"] as? Map<*, *>)?.let { security ->
            security["checks"]?.let { parts.add("checks $it") }
            security["refused"]?.let { parts.add("refused $it") }
        }
        (stats["model_router"] as? Map<*, *>)?.let { router ->
            router["last_provider"]?.let { parts.add("provider $it") }
            router["average_ms"]?.let { parts.add("avg ${it}ms") }
        }
        return parts.joinToString("   ·   ")
    }

    /** Show everything known about one node. */
    private fun showNode(nodeId: String) {
        selected = nodeId
        network.setSelected(nodeId)
        val token = TokenStore.load(this)
        if (token.isNullOrBlank()) return
        io.execute {
            val node = BackendClient.fetchNode(this, token, nodeId)
            main.post {
                detail.text = node ?: "No detail is available for $nodeId."
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val POLL_MS = 2000L
    }
}
