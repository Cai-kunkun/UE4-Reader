package com.arrbrants.kernelhack

import org.json.JSONArray
import org.json.JSONObject
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class ExecResult(val stdout: String, val stderr: String, val exitCode: Int)

class McpServer(
    private val executor: (String) -> ExecResult,
    private val onRequest: (String) -> Unit = {}
) {
    companion object {
        const val PORT = 25500
        private const val MAX_BODY_BYTES = 64 * 1024
    }

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private val clients = Executors.newCachedThreadPool()

    fun start() {
        if (running) return
        running = true
        Thread {
            try {
                serverSocket = ServerSocket(PORT, 16, java.net.InetAddress.getByName("127.0.0.1"))
                while (running) {
                    try {
                        val client = serverSocket!!.accept()
                        clients.execute { handle(client) }
                    } catch (_: SocketException) {
                        if (running) onRequest("MCP accept failed")
                    }
                }
            } catch (e: Exception) {
                onRequest("MCP server error: ${e.message}")
            } finally {
                running = false
            }
        }.apply { name = "mcp-server" }.start()
    }

    fun stop() {
        running = false
        serverSocket?.close()
        serverSocket = null
        clients.shutdownNow()
    }

    fun isRunning() = running

    private fun handle(socket: Socket) {
        try {
            socket.use { client ->
                client.soTimeout = 120_000
                val input = client.getInputStream()
                val output = client.getOutputStream()
                val headerBytes = ByteArrayOutputStream()
                var matched = 0
                while (headerBytes.size() < 16 * 1024) {
                    val byte = input.read()
                    if (byte < 0) return
                    headerBytes.write(byte)
                    matched = when {
                        matched == 0 && byte == '\r'.code -> 1
                        matched == 1 && byte == '\n'.code -> 2
                        matched == 2 && byte == '\r'.code -> 3
                        matched == 3 && byte == '\n'.code -> 4
                        byte == '\r'.code -> 1
                        else -> 0
                    }
                    if (matched == 4) break
                }
                if (matched != 4) {
                    respond(output, 431, JSONObject().put("error", "headers too large"))
                    return
                }
                val headerLines = headerBytes.toString(Charsets.ISO_8859_1.name()).split("\r\n")
                val requestLine = headerLines.firstOrNull() ?: return
                val headers = headerLines.drop(1).mapNotNull { line ->
                    val separator = line.indexOf(':')
                    if (separator > 0) line.substring(0, separator).lowercase() to line.substring(separator + 1).trim() else null
                }.toMap()

                if (!requestLine.startsWith("POST /mcp ")) {
                    respond(output, 404, JSONObject().put("error", "POST /mcp required"))
                    return
                }
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                if (length !in 1..MAX_BODY_BYTES) {
                    respond(output, 413, JSONObject().put("error", "invalid content length"))
                    return
                }
                val body = ByteArray(length)
                var read = 0
                while (read < length) {
                    val count = input.read(body, read, length - read)
                    if (count < 0) return
                    read += count
                }
                val response = process(JSONObject(String(body, Charsets.UTF_8)))
                respond(output, 200, response)
            }
        } catch (e: Exception) {
            Log.e("KernelHackMCP", "request failed", e)
            onRequest("MCP request failed: ${e.message}")
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun process(request: JSONObject): JSONObject {
        val id = request.opt("id")
        val method = request.optString("method")
        onRequest("MCP $method")
        return when (method) {
            "initialize" -> result(id, JSONObject()
                .put("protocolVersion", "2025-03-26")
                .put("capabilities", JSONObject().put("tools", JSONObject()))
                .put("serverInfo", JSONObject().put("name", "KernelHack MCP").put("version", "0.1.0")))
            "notifications/initialized" -> JSONObject()
            "ping" -> result(id, JSONObject())
            "tools/list" -> result(id, JSONObject().put("tools", JSONArray().put(toolDefinition())))
            "tools/call" -> callTool(request, id)
            else -> error(id, -32601, "Method not found: $method")
        }
    }

    private fun callTool(request: JSONObject, id: Any?): JSONObject {
        val params = request.optJSONObject("params") ?: JSONObject()
        if (params.optString("name") != "run_ue4_reader") {
            return error(id, -32602, "Unknown tool")
        }
        val target = params.optJSONObject("arguments")?.optString("package")?.trim().orEmpty()
        if (!target.matches(Regex("[A-Za-z0-9_.]+"))) {
            return error(id, -32602, "package must be an Android package name")
        }
        val result = executor(target)
        val text = buildString {
            append("exitCode: ").append(result.exitCode).append('\n')
            append("stdout:\n").append(result.stdout)
            if (!result.stdout.endsWith('\n')) append('\n')
            append("stderr:\n").append(result.stderr)
        }
        return result(id, JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
            .put("isError", result.exitCode != 0))
    }

    private fun toolDefinition() = JSONObject()
        .put("name", "run_ue4_reader")
        .put("description", "Run the packaged KernelHack ELF as root for one selected Android package and return its output.")
        .put("inputSchema", JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("package", JSONObject().put("type", "string").put("description", "Target Android package name")))
            .put("required", JSONArray().put("package")))

    private fun result(id: Any?, value: JSONObject) = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", value)

    private fun error(id: Any?, code: Int, message: String) = JSONObject()
        .put("jsonrpc", "2.0").put("id", id)
        .put("error", JSONObject().put("code", code).put("message", message))

    private fun respond(output: OutputStream, status: Int, body: JSONObject) {
        val payload = body.toString().toByteArray(Charsets.UTF_8)
        val reason = if (status == 200) "OK" else "Error"
        output.write("HTTP/1.1 $status $reason\r\nContent-Type: application/json\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.write(payload)
        output.flush()
    }
}
