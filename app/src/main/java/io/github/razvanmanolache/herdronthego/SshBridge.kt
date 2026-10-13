package io.github.razvanmanolache.herdronthego

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

data class Frame(val id: String, val type: String, val sessionId: String,
                 val seq: Long?, val payload: JSONObject)

class SshBridge(private val hosts: KnownHostsStore) : BridgeConnection {
    @Volatile private var session: Session? = null
    @Volatile override var onFrame: ((Frame) -> Unit)? = null
    @Volatile override var onDisconnect: (() -> Unit)? = null
    @Volatile private var stdioChannel: ChannelExec? = null
    @Volatile private var stdioOut: OutputStreamWriter? = null
    @Volatile private var reader: Thread? = null
    private val generation = AtomicLong()
    private val reqSeq = AtomicLong()
    private val replies = ReplyRouter<Frame>()

    fun nextId(): String = "c-${System.currentTimeMillis()}-${reqSeq.incrementAndGet()}"

    override fun connect(host: String, port: Int, user: String, password: String,
                timeoutMs: Int, onHostKey: (String) -> Boolean): Session {
        require(host.isNotBlank() && user.isNotBlank() && port in 1..65535) { "Invalid SSH address or user" }
        disconnect()
        val token = generation.get()
        val verifier = VerifyingHostKeys(hosts, host, port, onHostKey)
        val jsch = JSch().also { it.hostKeyRepository = verifier }
        val candidate = jsch.getSession(user, host, port)
        candidate.setPassword(password.toByteArray(Charsets.UTF_8))
        candidate.setConfig("StrictHostKeyChecking", "yes")
        candidate.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        candidate.serverAliveInterval = 15000
        candidate.serverAliveCountMax = 2
        session = candidate // A concurrent shutdown can also cancel an in-progress connect.
        try {
            candidate.connect(timeoutMs)
            if (token != generation.get() || session !== candidate) throw IOException("Connection cancelled")
            return candidate
        } catch (e: Exception) {
            candidate.disconnect()
            if (session === candidate) session = null
            verifier.rejection?.let { throw SecurityException(it, e) }
            throw e
        }
    }

    override fun isConnected(): Boolean = session?.isConnected == true

    override fun closeStdio() {
        generation.incrementAndGet()
        val oldChannel = stdioChannel
        val oldWriter = stdioOut
        val oldReader = reader
        stdioChannel = null
        stdioOut = null
        reader = null
        oldChannel?.disconnect()
        try { oldWriter?.close() } catch (_: Exception) { }
        replies.disconnect()
        if (oldReader !== Thread.currentThread()) {
            try { oldReader?.join(300) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
    }

    override fun disconnect() {
        generation.incrementAndGet()
        val old = session
        session = null
        // Close the socket before closing writers, so cleanup cannot flush to a dead peer.
        try { old?.disconnect() } catch (_: Exception) { }
        closeStdio()
    }

    private class LimitedOutput(private val limit: Int) : OutputStream() {
        private val bytes = ByteArrayOutputStream()
        @Synchronized override fun write(value: Int) {
            if (bytes.size() >= limit) throw IOException("SSH output exceeded limit")
            bytes.write(value)
        }
        @Synchronized override fun write(buffer: ByteArray, offset: Int, count: Int) {
            if (bytes.size() + count > limit) throw IOException("SSH output exceeded limit")
            bytes.write(buffer, offset, count)
        }
        @Synchronized fun text(): String = bytes.toString("UTF-8")
    }

    fun execOnce(command: String, timeoutMs: Int = 20000): String {
        val s = session ?: throw IOException("Not connected")
        val ch = s.openChannel("exec") as ChannelExec
        val output = LimitedOutput(1024 * 1024)
        ch.setCommand(command)
        ch.setInputStream(null)
        ch.setOutputStream(output)
        ch.setErrStream(LimitedOutput(64 * 1024))
        try {
            ch.connect(timeoutMs)
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
            while (!ch.isClosed) {
                if (System.nanoTime() >= deadline) throw TimeoutException("SSH discovery timed out")
                Thread.sleep(20)
            }
            if (ch.exitStatus != 0) throw IOException("SSH discovery command failed (${ch.exitStatus})")
            return output.text()
        } finally { ch.disconnect() }
    }

    override fun bridgePresent(): Boolean = execOnce("[ -x ~/.local/bin/herdr-mobile-bridge ] && echo OK || echo NO").trim() == "OK"
    override fun bridgeVersion(): String = execOnce("~/.local/bin/herdr-mobile-bridge --version").trim()

    override fun sessions(): List<BridgeSession> {
        val array = JSONArray(execOnce("~/.local/bin/herdr-mobile-bridge sessions --json").trim())
        require(array.length() <= 32) { "Too many server sessions" }
        return (0 until array.length()).map { i ->
            val row = array.getJSONObject(i)
            val name = row.getString("name")
            require(validSession(name)) { "Invalid session name" }
            BridgeSession(name, row.optBoolean("reachable", true))
        }
    }

    override fun socketAlive(): Boolean = execOnce("[ -S ~/.config/herdr/herdr.sock ] && echo OK || echo NO").trim() == "OK"

    override fun openStdio(sessionName: String) {
        require(validSession(sessionName)) { "Invalid session name" }
        closeStdio()
        val s = session ?: throw IOException("Not connected")
        val ch = s.openChannel("exec") as ChannelExec
        ch.setCommand("~/.local/bin/herdr-mobile-bridge stdio --session '$sessionName'")
        ch.setInputStream(null)
        ch.setErrStream(LimitedOutput(64 * 1024))
        val input = ch.outputStream
        val output = BufferedInputStream(ch.inputStream)
        val token = generation.get()
        try { ch.connect(15000) } catch (e: Exception) { ch.disconnect(); throw e }
        if (token != generation.get()) { ch.disconnect(); throw IOException("Connection cancelled") }
        stdioChannel = ch
        stdioOut = OutputStreamWriter(input, Charsets.UTF_8)
        reader = Thread({
            try {
                while (token == generation.get()) {
                    val line = boundedLine(output) ?: break
                    if (line.isBlank()) continue
                    val obj = JSONObject(line)
                    require(obj.getInt("version") == 1 && obj.getString("sessionId") == sessionName) {
                        "Invalid frame version or session"
                    }
                    val frame = Frame(obj.optString("id"), obj.getString("type"), sessionName,
                        if (obj.isNull("seq")) null else obj.getLong("seq"), obj.getJSONObject("payload"))
                    val requestId = frame.payload.optString("requestId", "")
                    if (requestId.isEmpty() || !replies.deliver(requestId, frame.type, frame)) onFrame?.invoke(frame)
                }
            } catch (_: Exception) {
                // Disconnect completes all request waiters. Do not log protocol contents.
            } finally {
                if (token == generation.get()) {
                    stdioOut = null
                    stdioChannel = null
                    ch.disconnect()
                    replies.disconnect()
                    onDisconnect?.invoke()
                }
            }
        }, "herdr-stdio-reader").also { it.isDaemon = true; it.start() }
    }

    @Synchronized override fun send(type: String, sessionId: String, payload: JSONObject): String =
        nextId().also { sendRaw(it, type, sessionId, payload) }

    @Synchronized fun sendRaw(id: String, type: String, sessionId: String, payload: JSONObject) {
        val out = stdioOut ?: throw IOException("Bridge stream is not open")
        val wire = envelope(id, type, sessionId, payload)
        require(wire.toByteArray(Charsets.UTF_8).size + 1 <= 128 * 1024) { "Request frame is too large" }
        out.write(wire)
        out.write("\n")
        out.flush()
    }

    override fun request(type: String, sessionId: String, payload: JSONObject, expected: String,
                timeoutMs: Long): Frame {
        val id = nextId()
        val future = replies.register(id, setOf(expected))
        try {
            sendRaw(id, type, sessionId, payload)
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } finally { replies.remove(id) }
    }

    companion object {
        fun validSession(name: String): Boolean = name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"))
        private fun boundedLine(input: InputStream): String? {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val value = input.read()
                if (value < 0) return if (bytes.size() == 0) null else throw IOException("Incomplete JSONL frame")
                if (value == 10) return bytes.toString("UTF-8").removeSuffix("\r")
                if (bytes.size() >= BridgeLimits.FRAME_BYTES) throw IOException("Bridge frame exceeded limit")
                bytes.write(value)
            }
        }
    }
}
