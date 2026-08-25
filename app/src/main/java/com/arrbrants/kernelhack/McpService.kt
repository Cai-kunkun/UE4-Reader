package com.arrbrants.kernelhack

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class McpService : Service() {
    private var server: McpServer? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
        server = McpServer(::runReader) { message -> McpLog.emit(message) }.also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        server?.stop()
        server = null
        McpLog.emit("MCP stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun runReader(target: String): ExecResult {
        val process = ProcessBuilder("su", "-c", "${shellQuote(applicationInfo.nativeLibraryDir + "/libexec.so")} ${shellQuote(target)}").start()
        process.outputStream.close()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        if (!process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return ExecResult(stdout, "$stderr\nexecution timed out", 124)
        }
        return ExecResult(stdout, stderr, process.exitValue())
    }

    private fun shellQuote(value: String) = "'${value.replace("'", "'\\''")}'"

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "MCP server", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle("KernelHack MCP server")
        .setContentText("Listening on 127.0.0.1:${McpServer.PORT}")
        .setOngoing(true)
        .build()

    companion object {
        const val CHANNEL_ID = "kernelhack-mcp"
        const val NOTIFICATION_ID = 25500
    }
}

object McpLog {
    @Volatile var listener: ((String) -> Unit)? = null
    fun emit(message: String) = listener?.invoke(message)
}
