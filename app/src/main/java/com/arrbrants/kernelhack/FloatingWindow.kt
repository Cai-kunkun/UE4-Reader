package com.arrbrants.kernelhack

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class FloatingWindow : Service() {
    private var windowManager: WindowManager? = null
    private var root: View? = null
    private val types = arrayOf("float", "double", "int", "long", "short", "byte")

    // 当前选中的目标（与主界面共享 targetProcess）
    private var target: String?
        get() = TargetHolder.target
        set(v) { TargetHolder.target = v }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        root = buildUi()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,  // 可聚焦: 输入框能弹键盘; 拖动用手动 touch 拦截不受影响
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 60; params.y = 200
        windowManager?.addView(root, params)
        enableDrag(params)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        root?.let { windowManager?.removeView(it) }
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val ctx = this
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(235, 25, 28, 32))
            setPadding(dp(14), dp(10), dp(14), dp(14))
        }

        // 标题栏（拖动手柄 + 关闭）
        val titleRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val title = TextView(ctx).apply {
            text = buildString { append("KernelHack"); TargetHolder.target?.let { append(" · "); append(it) } }
            setTextColor(Color.rgb(120, 220, 160))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnClose = TextView(ctx).apply {
            text = "✕"; setTextColor(Color.WHITE); setPadding(dp(12), 0, 0, 0)
            setOnClickListener { stopSelf() }
        }
        titleRow.addView(title); titleRow.addView(btnClose)
        panel.addView(titleRow)

        // 进程选择按钮（选择后显示 "pid package"）
        val btnPick = Button(ctx).apply {
            text = "选择进程 (Select Process)"
            tag = "btnPick"
            setOnClickListener {
                Thread { listUserProcessesForOverlay() }.start()
            }
        }
        panel.addView(btnPick)

        // 模块基址输入（可选，如 libUE4.so）
        val moduleEdit = EditText(ctx).apply {
            hint = "module (可选, 如 libUE4.so) — 留空=全局扫描"
            setSingleLine(true)
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        }
        panel.addView(moduleEdit)

        // 类型 spinner
        val spinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, types)
        }
        panel.addView(spinner)

        // 值输入
        val valueEdit = EditText(ctx).apply {
            hint = "value (如 100 / 3.14)"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        }
        panel.addView(valueEdit)

        // 搜索按钮
        val btnScan = Button(ctx).apply { text = "🔍 Scan" }
        panel.addView(btnScan)

        // 结果区
        val resultScroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(180))
            setBackgroundColor(Color.BLACK)
        }
        val resultView = TextView(ctx).apply {
            setTextColor(Color.rgb(150, 230, 170))
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(6), dp(4), dp(6), dp(4))
            text = "ready."
        }
        resultScroll.addView(resultView)
        panel.addView(resultScroll)

        btnScan.setOnClickListener {
            val pkg = target
            if (pkg == null) {
                toast("先选择进程")
                return@setOnClickListener
            }
            val mod = moduleEdit.text.toString().trim()
            val type = spinner.selectedItem as String
            val value = valueEdit.text.toString().trim()
            if (value.isEmpty()) { toast("请输入值"); return@setOnClickListener }
            // 按类型校验输入合法性
            val err = validateValue(type, value)
            if (err != null) { toast(err); return@setOnClickListener }
            // module 名合法性（可选填）
            if (mod.isNotEmpty() && !Regex("^[A-Za-z0-9_.:+\\-]+$").matches(mod)) {
                toast("module 名称不合法")
                return@setOnClickListener
            }
            appendResult(resultView, "\$ scan $type $value ${if (mod.isNotEmpty()) "[$mod]" else ""}\n")
            Thread {
                val argsLine = if (mod.isNotEmpty()) "$pkg scan $type $value $mod" else "$pkg scan $type $value"
                val r = execBinary(argsLine)
                showResult(resultView, r)
            }.start()
        }

        return panel
    }

    // ---- 业务逻辑：列出用户进程并弹出选择对话框（在 overlay 上）----
    private fun listUserProcessesForOverlay() {
        try {
            val pkgOut = runCapture("pm list packages -3")
            val userPkgs = pkgOut.lineSequence()
                .mapNotNull { it.removePrefix("package:").trim().takeIf(String::isNotEmpty) }.toHashSet()
            val psOut = runCapture("ps -A -o PID,USER,NAME")
            val items = mutableListOf<Pair<String,String>>()
            psOut.lines().drop(1).forEach { raw ->
                val line = raw.trim(); if (line.isEmpty()) return@forEach
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 3) return@forEach
                val proc = parts.last(); val name = proc.substringBefore(':')
                if (name in userPkgs) items.add("${parts[0]}  $proc" to name)
            }
            runOnUiThread {
                if (items.isEmpty()) { toast("没有运行中的用户应用"); return@runOnUiThread }
                showPicker(items.map { it.first }.toTypedArray(), items.map { it.second })
            }
        } catch (e: Exception) {
            runOnUiThread { toast("error: ${e.message}") }
        }
    }

    private fun showPicker(displays: Array<String>, pkgs: List<String>) {
        // 直接把选择列表渲染进悬浮窗内部（overlay 无法使用 AlertDialog）
        val panel = root as? LinearLayout ?: return
        // 移除旧 picker
        panel.findViewWithTag<LinearLayout>("picker")?.let { panel.removeView(it) }
        val picker = LinearLayout(this).apply { tag = "picker"; orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.argb(255,40,44,50)) }
        val scroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(200)) }
        val listLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        displays.forEachIndexed { i, d ->
            val tv = TextView(this).apply {
                text = d; setPadding(dp(8),dp(8),dp(8),dp(8)); setTextColor(Color.WHITE)
                setOnClickListener {
                    target = pkgs[i]
                    toast("target: ${pkgs[i]}")
                    panel.removeView(picker)
                    // 按钮显示 "pid package"，标题同步
                    val display = displays[i]
                    panel.findViewWithTag<Button>("btnPick")?.text = display
                    panel.findViewWithTag<TextView>("title")?.text = "KernelHack · ${pkgs[i]}"
                }
            }
            listLayout.addView(tv)
        }
        scroll.addView(listLayout); picker.addView(scroll)
        // 插到 index 2（进程按钮下方）
        panel.addView(picker, 2)
    }

    private fun getTitleView(v: View): TextView? = v.findViewWithTag("title")

    // ---- 类型校验 ----
    private fun validateValue(type: String, v: String): String? {
        return try {
            when (type) {
                "float" -> {
                    v.toFloat()
                    if (!Regex("^[-+]?[0-9]*\\.?[0-9]+([eE][-+]?[0-9]+)?$").matches(v))
                        "float 格式不合法: $v"
                    else null
                }
                "double" -> {
                    v.toDouble()
                    if (!Regex("^[-+]?[0-9]*\\.?[0-9]+([eE][-+]?[0-9]+)?$").matches(v))
                        "double 格式不合法: $v"
                    else null
                }
                "int" -> {
                    v.toInt()
                    null
                }
                "long" -> {
                    v.toLong()
                    null
                }
                "short" -> {
                    val n = v.toShort()
                    null
                }
                "byte" -> {
                    val n = v.toInt()
                    if (n < 0 || n > 255) "byte 范围 0..255"
                    else null
                }
                else -> "未知类型"
            }
        } catch (e: NumberFormatException) {
            "$type 无法解析: $v"
        }
    }

    // ---- 执行 ----
    private fun runCapture(cmd: String): String {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        p.waitFor()
        return out + err
    }

    private fun execBinary(argsLine: String): String {
        val bin = applicationInfo.nativeLibraryDir + "/libexec.so"
        val quoted = argsLine.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { "'${it.replace("'", "'\\''")}'" }
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "'$bin' $quoted"))
        p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        p.waitFor()
        return out + (if (err.isNotEmpty()) "\n[err] $err" else "")
    }

    // ---- UI helpers ----
    private fun showResult(view: TextView, text: String) {
        runOnUiThread { view.append(text + "\n") ; autoScroll(view) }
    }
    private fun appendResult(view: TextView, s: String) { runOnUiThread { view.append(s) } }
    private fun autoScroll(view: TextView) {
        (view.parent as? ScrollView)?.post { (view.parent as ScrollView).fullScroll(ScrollView.FOCUS_DOWN) }
    }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun runOnUiThread(f: () -> Unit) { android.os.Handler(mainLooper).post(f) }

    // ---- 拖动 ----
    private fun enableDrag(params: WindowManager.LayoutParams) {
        val view = root ?: return
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        view.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; false }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - downX).toInt()
                    params.y = startY + (e.rawY - downY).toInt()
                    windowManager?.updateViewLayout(v, params)
                    false
                }
                else -> false
            }
        }
    }
}

// 共享目标进程（MainActivity 与 FloatingWindow 之间）
object TargetHolder {
    @Volatile var target: String? = null
}
