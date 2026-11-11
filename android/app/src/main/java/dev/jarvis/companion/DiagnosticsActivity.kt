package dev.jarvis.companion

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * The system diagnostics screen.
 *
 * Everything on it comes from the backend, which measures it. A service is
 * only shown as connected when its connection was really tested, and the
 * counters are the ones the system actually recorded.
 */
class DiagnosticsActivity : Activity() {

    private lateinit var body: TextView
    private lateinit var summary: TextView
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var polling = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val background = 0xFF03060A.toInt()
        val cyan = 0xFF4FD8FF.toInt()
        val white = 0xFFE8F4FA.toInt()
        val muted = 0xFF8CA5B4.toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(background)
        }

        root.addView(TextView(this).apply {
            text = "SYSTEM  DIAGNOSTICS"
            setTextColor(white)
            textSize = 16f
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            letterSpacing = 0.12f
        })

        summary = TextView(this).apply {
            setTextColor(cyan)
            textSize = 12f
            setPadding(0, dp(8), 0, dp(6))
        }
        root.addView(summary)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(8))
        }
        buttons.addView(button("Check every service", cyan) { checkServices() })
        buttons.addView(button("Refresh", cyan) { refresh() })
        root.addView(buttons)

        val scroll = ScrollView(this)
        body = TextView(this).apply {
            setTextColor(muted)
            textSize = 11f
            setPadding(0, dp(4), 0, dp(24))
        }
        scroll.addView(body, ViewGroup.LayoutParams(
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
        refresh()
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

    private fun refresh() {
        val token = TokenStore.load(this)
        if (token.isNullOrBlank()) {
            body.text = "No pairing token. Save it in JARVIS first."
            return
        }
        io.execute {
            val network = BackendClient.fetchNetwork(this, token)
            main.post {
                if (network == null) {
                    body.text = "The backend did not answer."
                    return@post
                }
                body.text = describe(network)
                summary.text = "Updated"
            }
        }
    }

    /** Run the real connection test for every service. */
    private fun checkServices() {
        val token = TokenStore.load(this)
        if (token.isNullOrBlank()) return
        summary.text = "Testing every service…"
        io.execute {
            val report = BackendClient.checkServices(this, token)
            main.post {
                if (report == null) {
                    summary.text = "The service check did not answer."
                    return@post
                }
                summary.text = "Checked: " + report.count
                body.text = report.text
            }
        }
    }

    private fun describe(snapshot: NetworkSnapshot): String {
        val lines = mutableListOf<String>()
        lines.add("AGENTS")
        for (node in snapshot.nodes.filter { it.kind == "agent" }) {
            lines.add(
                "  ${node.nodeId.padEnd(22)} ${node.status.padEnd(10)} " +
                    "done ${node.tasksCompleted}  failed ${node.tasksFailed}" +
                    if (node.lastError.isNotBlank()) "  ${node.lastError}" else "",
            )
        }
        lines.add("")
        lines.add("EXTERNAL SERVICES")
        for (node in snapshot.nodes.filter { it.kind == "service" }) {
            lines.add("  ${node.nodeId.removePrefix("service.").padEnd(20)} ${node.status.padEnd(24)} ${node.detail}")
        }
        lines.add("")
        lines.add("TASK GRAPH")
        val tasks = snapshot.stats["tasks_run"]
        val failed = snapshot.stats["tasks_failed"]
        lines.add("  tasks run $tasks   tasks failed $failed")
        return lines.joinToString("\n")
    }

    private fun button(label: String, colour: Int, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            setTextColor(colour)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                marginEnd = dp(4)
            }
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
