package com.arrbrants.kernelhack

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        // Status line
        val status = TextView(this).apply {
            text = "checking su..."
            setPadding(0, 0, 0, 16)
        }

        // Check SU button
        val btnSu = Button(this).apply { text = "Check su" }
        // List user apps button
        val btnApps = Button(this).apply { text = "List user apps" }
        // Exec self button
        val btnExec = Button(this).apply { text = "Exec libexec.so" }

        // Output console
        scroll = ScrollView(this).apply { setBackgroundColor(Color.BLACK) }
        output = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(Color.GREEN)
            text = ""
            setPadding(16, 16, 16, 16)
        }
        scroll.addView(output)
        scroll.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        )

        // Command input + run button row
        input = EditText(this).apply {
            hint = "shell command (runs as root)"
            setSingleLine(true)
            typeface = Typeface.MONOSPACE
        }
        val btnRun = Button(this).apply { text = "Run" }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnRun)
        }

        root.addView(status)
        root.addView(btnSu)
        root.addView(btnApps)
        root.addView(btnExec)
        root.addView(scroll)
        root.addView(row)
        setContentView(root)

        btnSu.setOnClickListener {
            Thread {
                val ok = checkSu()
                runOnUiThread {
                    status.text = if (ok) "su: AVAILABLE" else "su: NOT AVAILABLE"
                    Toast.makeText(
                        this,
                        if (ok) "root granted" else "no root",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }.start()
        }

        btnExec.setOnClickListener {
            appendLine("\$ exec libexec.so")
            Thread {
                execRoot(binaryPath(), emptyArray())
            }.start()
        }

        btnApps.setOnClickListener {
            appendLine("\$ list user apps (running)")
            Thread { listUserApps() }.start()
        }

        btnRun.setOnClickListener {
            val cmd = input.text.toString().trim()
            if (cmd.isEmpty()) return@setOnClickListener
            input.text.clear()
            appendLine("\$ $cmd")
            Thread { execRoot("sh", arrayOf("-c", cmd)) }.start()
        }

        // Auto check on launch
        Thread {
            val ok = checkSu()
            runOnUiThread {
                status.text = if (ok) "su: AVAILABLE" else "su: NOT AVAILABLE"
            }
        }.start()
    }

    private fun binaryPath(): String {
        // jniLibs packaged libexec.so lands here with exec permission
        return applicationInfo.nativeLibraryDir + "/libexec.so"
    }

    private fun checkSu(): Boolean {
        return try {
            val p = ProcessBuilder("su", "-c", "id").start()
            p.waitFor()
            p.inputStream.bufferedReader().readText().contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Lists running user apps only:
     * 1) pm list packages -3  -> third-party (user-installed) packages
     * 2) ps -A -o PID,USER,NAME -> all running processes
     * 3) intersect: keep processes whose package is in the user-app set
     * System services / native daemons are excluded automatically.
     */
    private fun listUserApps() {
        try {
            // 1. user-installed packages
            val pkgOut = runCapture("pm list packages -3")
            val userPkgs = pkgOut.lineSequence()
                .mapNotNull { it.removePrefix("package:").trim().takeIf { n -> n.isNotEmpty() } }
                .toHashSet()
            if (userPkgs.isEmpty()) {
                appendLine("[no user packages found]")
                return
            }

            // 2. running processes
            val psOut = runCapture("ps -A -o PID,USER,NAME")

            // 3. intersect
            var count = 0
            val lines = psOut.lines()
            appendLine(lines[0]) // header
            for (i in 1 until lines.size) {
                val line = lines[i].trim()
                if (line.isEmpty()) continue
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 3) continue
                // NAME may contain the package name; also handle "com.x:y" sub-processes
                val name = parts.last().substringBefore(':')
                if (name in userPkgs) {
                    appendLine(line)
                    count++
                }
            }
            appendLine("[$count running user app process(es), ${userPkgs.size} installed]")
        } catch (e: Exception) {
            appendLine("[error] ${e.message}")
        }
    }

    /** Run a command as root and return stdout+stderr text without touching the console UI. */
    private fun runCapture(cmd: String): String {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        p.waitFor()
        return out + err
    }

    private fun execRoot(cmd: String, args: Array<String>) {
        try {
            // Wrap in su -c so everything runs as root
            val full = mutableListOf("su", "-c", (listOf(cmd) + args).joinToString(" "))
            val p = Runtime.getRuntime().exec(full.toTypedArray())

            // Close stdin immediately: the binary is non-interactive now
            p.outputStream.close()

            val outThread = Thread {
                p.inputStream.bufferedReader().forEachLine { appendLine(it) }
            }
            val errThread = Thread {
                p.errorStream.bufferedReader().forEachLine { appendLine("[err] $it") }
            }
            outThread.start(); errThread.start()
            p.waitFor()
            outThread.join(); errThread.join()
            appendLine("[exit ${p.exitValue()}]")
        } catch (e: Exception) {
            appendLine("[error] ${e.message}")
        }
    }

    private fun appendLine(line: String) {
        runOnUiThread {
            output.append(line + "\n")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }
}
