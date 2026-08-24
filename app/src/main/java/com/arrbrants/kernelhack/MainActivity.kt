package com.arrbrants.kernelhack

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView

class MainActivity : Activity() {
    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: TextInputEditText
    private var targetProcess: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MaterialColors.getColor(this@MainActivity, com.google.android.material.R.attr.colorSurface, Color.WHITE))
        }

        val toolbar = MaterialToolbar(this).apply {
            title = "KernelHack"
            subtitle = "Root workspace"
            setTitleTextAppearance(this@MainActivity, com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
            elevation = 0f
        }
        page.addView(toolbar, LinearLayout.LayoutParams(-1, dp(76)))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(20))
        }
        val contentScroll = ScrollView(this).apply { addView(content) }
        page.addView(contentScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val statusCard = MaterialCardView(this).apply {
            radius = dp(24).toFloat()
            strokeWidth = dp(1)
            setCardBackgroundColor(MaterialColors.getColor(this@MainActivity, com.google.android.material.R.attr.colorPrimaryContainer, Color.LTGRAY))
            setContentPadding(dp(18), dp(18), dp(18), dp(18))
        }
        val status = MaterialTextView(this).apply {
            text = "Checking root access..."
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
        }
        statusCard.addView(status)
        content.addView(statusCard, LinearLayout.LayoutParams(-1, dp(82)).apply { bottomMargin = dp(16) })

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val btnSu = actionButton("Check su", true)
        val btnApps = actionButton("User apps", false)
        actionRow.addView(btnSu, LinearLayout.LayoutParams(0, dp(56), 1f).apply { rightMargin = dp(8) })
        actionRow.addView(btnApps, LinearLayout.LayoutParams(0, dp(56), 1f))
        content.addView(actionRow, LinearLayout.LayoutParams(-1, dp(64)).apply { bottomMargin = dp(8) })

        val btnExec = actionButton("Execute target", true).apply { icon = null }
        content.addView(btnExec, LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(16) })

        val consoleCard = MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            setCardBackgroundColor(Color.rgb(20, 22, 25))
            setContentPadding(0, 0, 0, 0)
        }
        scroll = ScrollView(this)
        output = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(Color.rgb(178, 240, 190))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            text = "Ready. Check root access to begin.\n"
        }
        scroll.addView(output)
        consoleCard.addView(scroll, ViewGroup.LayoutParams(-1, dp(230)))
        content.addView(consoleCard, LinearLayout.LayoutParams(-1, dp(230)).apply { bottomMargin = dp(16) })

        val commandLayout = TextInputLayout(this).apply {
            hint = "Root command"
            setBoxCornerRadii(dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat())
            endIconMode = TextInputLayout.END_ICON_NONE
        }
        input = TextInputEditText(this).apply {
            setSingleLine(true)
            typeface = Typeface.MONOSPACE
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
        }
        commandLayout.addView(input)
        val run = MaterialButton(this).apply {
            text = "Run command"
            cornerRadius = dp(18)
        }
        content.addView(commandLayout, LinearLayout.LayoutParams(-1, dp(64)).apply { bottomMargin = dp(8) })
        content.addView(run, LinearLayout.LayoutParams(-1, dp(52)))

        setContentView(page)

        btnSu.setOnClickListener {
            Thread {
                val ok = checkSu()
                runOnUiThread {
                    status.text = if (ok) "Root access available" else "Root access unavailable"
                    Toast.makeText(this, if (ok) "su granted" else "su unavailable", Toast.LENGTH_SHORT).show()
                }
            }.start()
        }
        btnApps.setOnClickListener {
            appendLine("\\$ list user apps (running)")
            Thread { listUserApps() }.start()
        }
        btnExec.setOnClickListener {
            val target = targetProcess
            appendLine("\\$ execute libexec.so ${target ?: "(default target)"}")
            Thread { execRoot(binaryPath(), target?.let { arrayOf(it) } ?: emptyArray()) }.start()
        }
        run.setOnClickListener {
            val cmd = input.text?.toString()?.trim().orEmpty()
            if (cmd.isEmpty()) return@setOnClickListener
            input.text?.clear()
            appendLine("\\$ $cmd")
            Thread { execRoot("sh", arrayOf("-c", cmd)) }.start()
        }
        Thread {
            val ok = checkSu()
            runOnUiThread { status.text = if (ok) "Root access available" else "Root access unavailable" }
        }.start()
    }

    private fun actionButton(label: String, filled: Boolean): MaterialButton = MaterialButton(this).apply {
        text = label
        cornerRadius = dp(18)
        if (!filled) {
            setBackgroundColor(Color.TRANSPARENT)
            strokeWidth = dp(1)
        }
    }

    private fun binaryPath() = applicationInfo.nativeLibraryDir + "/libexec.so"

    private fun checkSu(): Boolean = try {
        val p = ProcessBuilder("su", "-c", "id").start()
        val result = p.inputStream.bufferedReader().readText()
        p.waitFor()
        result.contains("uid=0")
    } catch (_: Exception) { false }

    private fun listUserApps() {
        try {
            val packages = runCapture("pm list packages -3").lineSequence()
                .mapNotNull { it.removePrefix("package:").trim().takeIf(String::isNotEmpty) }.toHashSet()
            val items = mutableListOf<Pair<String, String>>()
            val lines = runCapture("ps -A -o PID,USER,NAME").lines()
            appendLine(lines.firstOrNull().orEmpty())
            lines.drop(1).forEach { raw ->
                val line = raw.trim()
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 3) return@forEach
                val process = parts.last()
                val packageName = process.substringBefore(':')
                if (packageName in packages) {
                    appendLine(line)
                    items += "${parts[0]}  $process" to packageName
                }
            }
            appendLine("[${items.size} running user processes, ${packages.size} installed]")
            if (items.isNotEmpty()) runOnUiThread { showProcessPicker(items) }
        } catch (e: Exception) { appendLine("[error] ${e.message}") }
    }

    private fun showProcessPicker(items: List<Pair<String, String>>) {
        val labels = arrayOf("Cancel / keep current") + items.map { it.first }.toTypedArray()
        val current = targetProcess?.let { "\nCurrent: $it" } ?: ""
        MaterialAlertDialogBuilder(this)
            .setTitle("Select target process$current")
            .setItems(labels) { _, which ->
                if (which == 0) return@setItems
                targetProcess = items[which - 1].second
                appendLine("[target set -> $targetProcess]")
                Toast.makeText(this, "Target selected", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun runCapture(command: String): String {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        p.waitFor()
        return out + err
    }

    private fun execRoot(cmd: String, args: Array<String>) {
        try {
            val command = (listOf(cmd) + args).joinToString(" ")
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            p.outputStream.close()
            val out = Thread { p.inputStream.bufferedReader().forEachLine { appendLine(it) } }
            val err = Thread { p.errorStream.bufferedReader().forEachLine { appendLine("[err] $it") } }
            out.start(); err.start(); p.waitFor(); out.join(); err.join()
            appendLine("[exit ${p.exitValue()}]")
        } catch (e: Exception) { appendLine("[error] ${e.message}") }
    }

    private fun appendLine(line: String) {
        runOnUiThread {
            output.append("$line\n")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
