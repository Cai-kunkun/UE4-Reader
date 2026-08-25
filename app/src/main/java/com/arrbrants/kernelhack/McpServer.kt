package com.arrbrants.kernelhack

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
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
                val headerLines = headerBytes.toString(StandardCharsets.ISO_8859_1.name()).split("\r\n")
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
                val response = process(JSONObject(String(body, StandardCharsets.UTF_8)))
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
            "tools/list" -> result(id, JSONObject().put("tools", toolDefinition()))
            "tools/call" -> callTool(request, id)
            else -> error(id, -32601, "Method not found: $method")
        }
    }

    private fun callTool(request: JSONObject, id: Any?): JSONObject {
        val params = request.optJSONObject("params") ?: JSONObject()
        val name = params.optString("name")
        val args = params.optJSONObject("arguments") ?: JSONObject()

        val pkg = args.optString("package")?.trim().orEmpty()
        if (!pkg.matches(Regex("[A-Za-z0-9_.]+"))) {
            return error(id, -32602, "package must be an Android package name")
        }

        val execArgs = when (name) {
            "reader_info" -> pkg
            "reader_modules" -> "$pkg modules"
            "reader_module" -> {
                val mod = args.optString("module")?.trim().orEmpty()
                if (!mod.matches(Regex("[A-Za-z0-9_.:+\\-]+")))
                    return error(id, -32602, "invalid module name")
                "$pkg module $mod"
            }
            "reader_read" -> {
                val addr = args.optString("address")?.trim().orEmpty()
                val size = args.optInt("size", 16)
                if (!addr.matches(Regex("[0-9a-fA-F]+")) || addr.isEmpty())
                    return error(id, -32602, "address must be a hex string")
                if (size < 1 || size > 4096)
                    return error(id, -32602, "size must be 1..4096")
                "$pkg read ${addr.lowercase()} $size"
            }
            "reader_scan" -> {
                val type = args.optString("type")?.trim().orEmpty()
                val value = args.optString("value")?.trim().orEmpty()
                val mod = args.optString("module")?.trim().orEmpty()
                if (!type.matches(Regex("(float|double|int|long|short|byte)")))
                    return error(id, -32602, "type must be float/double/int/long/short/byte")
                if (value.isEmpty() || !value.matches(Regex("[\\-0-9.eE+xXa-fA-F]+")))
                    return error(id, -32602, "invalid value")
                if (mod.isNotEmpty() && !mod.matches(Regex("[A-Za-z0-9_.:+\\-]+")))
                    return error(id, -32602, "invalid module name")
                if (mod.isNotEmpty()) "$pkg scan $type $value $mod" else "$pkg scan $type $value"
            }
            "writer_write" -> {
                val addr = args.optString("address")?.trim().orEmpty()
                val data = args.optString("hexdata")?.trim().orEmpty()
                if (!addr.matches(Regex("[0-9a-fA-F]+")) || addr.isEmpty())
                    return error(id, -32602, "address must be a hex string")
                if (!data.matches(Regex("[0-9a-fA-F]+")) || data.isEmpty() || data.length % 2 != 0 || data.length > 8192)
                    return error(id, -32602, "hexdata must be even-length hex string (max 4096 bytes)")
                "$pkg write ${addr.lowercase()} ${data.lowercase()}"
            }
            else -> return error(id, -32602, "Unknown tool: $name")
        }

        val result = executor(execArgs)
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

    private fun buildArgs(target: String, command: String, module: String, address: String, size: Int): String {
        val sb = StringBuilder(target)
        if (command != "info") {
            sb.append(' ').append(command)
        }
        when (command) {
            "module" -> if (module.isNotEmpty()) sb.append(' ').append(module)
            "read" -> {
                if (address.isNotEmpty()) sb.append(' ').append(address)
                if (size > 0) sb.append(' ').append(size)
            }
        }
        return sb.toString()
    }

    private fun strProp(desc: String): JSONObject =
        JSONObject().put("type", "string").put("description", desc)

    private fun toolDefinition(): JSONArray {
        val tools = JSONArray()

        fun base(vararg required: String): JSONObject {
            val props = JSONObject()
            props.put("package", strProp("Target Android package name"))
            val req = JSONArray()
            req.put("package")
            for (r in required) req.put(r)
            return JSONObject().put("type", "object").put("properties", props).put("required", req)
        }

        // 1. info
        tools.put(JSONObject()
            .put("name", "reader_info")
            .put("description", "Attach driver to a running app by package name. Returns PID and default libUE4.so base pointer probe.")
            .put("inputSchema", base()))

        // 2. modules
        tools.put(JSONObject()
            .put("name", "reader_modules")
            .put("description", "List all loaded modules (base address + path) of the target process.")
            .put("inputSchema", base()))

        // 3. module
        var schema = base("module")
        schema.getJSONObject("properties").put("module", strProp("Module file name e.g. libUE4.so"))
        tools.put(JSONObject()
            .put("name", "reader_module")
            .put("description", "Get base and bss addresses of one specific module in the target process.")
            .put("inputSchema", schema))

        // 4. read
        schema = base("address")
        schema.getJSONObject("properties").apply {
            put("address", strProp("Hex memory address to read from (no 0x prefix)"))
            put("size", JSONObject().put("type", "integer").put("description", "Bytes to read (1..4096)").put("minimum", 1).put("maximum", 4096))
        }
        tools.put(JSONObject()
            .put("name", "reader_read")
            .put("description", "Read raw bytes at a hex address in the target process. Returns hex dump.")
            .put("inputSchema", schema))

        // 5. write
        schema = base("address")
        schema.getJSONObject("properties").apply {
            put("address", strProp("Hex memory address to write to (no 0x prefix)"))
            put("hexdata", strProp("Raw bytes as even-length hex string, e.g. deadbeef"))
        }
        tools.put(JSONObject()
            .put("name", "writer_write")
            .put("description", "DANGEROUS: write raw bytes into target process memory. Use only when you know exactly what you are doing.")
            .put("inputSchema", schema))

        // 6. scan
        schema = base("type")
        schema.getJSONObject("properties").apply {
            put("type", JSONObject().put("type", "string").put("description", "Value type: float, double, int, long, short, byte").put("enum", JSONArray().put("float").put("double").put("int").put("long").put("short").put("byte")))
            put("value", JSONObject().put("type", "string").put("description", "Value to search for (as string, e.g. 3.14, 42)"))
            put("module", JSONObject().put("type", "string").put("description", "Optional: limit search to this module (e.g. libUE4.so)"))
        }
        tools.put(JSONObject()
            .put("name", "reader_scan")
            .put("description", "Search target process memory for a specific value of given type. Returns matching addresses. Optionally limit to one module.")
            .put("inputSchema", schema))

        return tools
    }

    private fun result(id: Any?, value: JSONObject): JSONObject = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", value)

    private fun error(id: Any?, code: Int, message: String): JSONObject = JSONObject()
        .put("jsonrpc", "2.0").put("id", id)
        .put("error", JSONObject().put("code", code).put("message", message))

    private fun respond(output: OutputStream, status: Int, body: JSONObject) {
        val payload = body.toString().toByteArray(StandardCharsets.UTF_8)
        val reason = if (status == 200) "OK" else "Error"
        output.write("HTTP/1.1 $status $reason\r\nContent-Type: application/json\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write(payload)
        output.flush()
    }
}
