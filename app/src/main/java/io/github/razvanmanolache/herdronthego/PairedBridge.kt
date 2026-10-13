package io.github.razvanmanolache.herdronthego

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

class PairingRequiredException(message: String) : IOException(message)

data class PairAddress(val host: String, val port: Int, val fingerprint: String) {
    companion object {
        fun parse(value: String): PairAddress {
            require(value.length <= 512) { "Pairing address is too long" }
            val uri = URI(value.trim())
            require(uri.scheme == "herdr-pair" && uri.userInfo == null && uri.fragment == null &&
                uri.rawPath.isNullOrEmpty() && uri.host?.matches(Regex("[A-Za-z0-9][A-Za-z0-9.-]{0,252}")) == true) {
                "Scan the QR code from Herdr's Pair devices tab"
            }
            val query = uri.rawQuery ?: ""
            require(query.matches(Regex("fingerprint=[a-fA-F0-9]{64}"))) { "The PC certificate fingerprint is required" }
            val port = if (uri.port == -1) 7443 else uri.port
            require(port in 1..65535) { "Invalid port" }
            return PairAddress(uri.host, port, query.substringAfter('=').lowercase())
        }
    }
}

/** Trust exactly the certificate obtained physically from the PC, before sending any secret. */
class PinnedTlsClient(private val address: PairAddress) : Closeable {
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var closed = false
    private val context = SSLContext.getInstance("TLS").also { context ->
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, auth: String?) =
                throw java.security.cert.CertificateException("Client certificates are not used")
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, auth: String?) {
                val certificate = chain?.firstOrNull() ?: throw java.security.cert.CertificateException("Missing PC certificate")
                certificate.checkValidity()
                val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                if (!MessageDigest.isEqual(actual.toByteArray(), address.fingerprint.toByteArray()))
                    throw java.security.cert.CertificateException("PC certificate changed. Pair again from Herdr; no credential was sent")
            }
        }
        context.init(null, arrayOf(trust), SecureRandom())
    }

    fun socket(timeout: Int = 5000): SSLSocket {
        val raw = Socket()
        fun track(socket: Socket) = synchronized(sockets) {
            if (closed) { socket.close(); throw IOException("Connection closed") }
            sockets.add(socket)
        }
        track(raw)
        try {
            raw.connect(InetSocketAddress(address.host, address.port), timeout)
            val tls = context.socketFactory.createSocket(raw, address.host, address.port, true) as SSLSocket
            track(tls)
            tls.soTimeout = timeout
            tls.enabledProtocols = tls.supportedProtocols.filter { it in setOf("TLSv1.2", "TLSv1.3") }.toTypedArray()
            tls.startHandshake()
            if (closed) throw IOException("Connection cancelled")
            return tls
        } catch (e: Exception) {
            sockets.remove(raw)
            raw.close()
            throw e
        }
    }

    fun request(message: JSONObject): JSONObject {
        val socket = socket()
        try {
            write(socket.outputStream, message)
            return read(BufferedInputStream(socket.inputStream))
                .also {
                    if (it.has("error")) {
                        val message = it.getString("error")
                        if (message == "Device is not paired or was revoked") throw PairingRequiredException(message)
                        throw IOException(message)
                    }
                }
        } finally { socket.close(); release(socket) }
    }

    fun release(socket: Socket) { sockets.remove(socket); sockets.removeIf { it.isClosed } }
    override fun close() {
        val closing = synchronized(sockets) { closed = true; sockets.toList().also { sockets.clear() } }
        closing.forEach { try { it.close() } catch (_: Exception) {} }
    }

    companion object {
        fun write(output: OutputStream, value: JSONObject) {
            val bytes = (value.toString() + "\n").toByteArray(Charsets.UTF_8)
            require(bytes.size <= 128 * 1024) { "Request frame is too large" }
            output.write(bytes); output.flush()
        }
        fun read(input: BufferedInputStream): JSONObject {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val next = input.read()
                if (next < 0) throw IOException("PC connection closed")
                if (next == 10) return JSONObject(bytes.toString("UTF-8"))
                if (bytes.size() >= BridgeLimits.FRAME_BYTES) throw IOException("Bridge frame exceeded limit")
                bytes.write(next)
            }
        }
    }
}

class PairedBridge(private val fingerprint: String) : BridgeConnection {
    @Volatile override var onFrame: ((Frame) -> Unit)? = null
    @Volatile override var onDisconnect: (() -> Unit)? = null
    private var client: PinnedTlsClient? = null
    private var device = ""
    private var secret = ""
    @Volatile private var connected = false
    @Volatile private var stream: SSLSocket? = null
    private var heartbeat: ScheduledExecutorService? = null
    private val generation = AtomicLong()
    private val sequence = AtomicLong()
    private val replies = ReplyRouter<Frame>()
    private var info: JSONObject? = null
    private fun credentials(op: String) = JSONObject().put("op", op).put("deviceId", device).put("secret", secret)

    override fun connect(host: String, port: Int, user: String, password: String, timeoutMs: Int,
                         onHostKey: (String) -> Boolean): Any {
        require(fingerprint.matches(Regex("[a-f0-9]{64}"))) { "Pair this PC from its Herdr tab first" }
        disconnect()
        val epoch = generation.get()
        val candidate = PinnedTlsClient(PairAddress(host, port, fingerprint))
        synchronized(this) {
            if (epoch != generation.get()) { candidate.close(); throw IOException("Connection cancelled") }
            client = candidate
        }
        device = user; secret = password
        try {
            val result = candidate.request(credentials("info"))
            synchronized(this) {
                if (epoch != generation.get() || client !== candidate) throw IOException("Connection cancelled")
                info = result
                connected = true
            }
        } catch (e: Exception) { disconnect(); throw e }
        return Unit
    }
    override fun isConnected() = connected
    override fun bridgePresent() = connected && info != null
    override fun bridgeVersion() = info?.getString("version") ?: throw IOException("Not connected")
    override fun socketAlive() = connected
    override fun sessions(): List<BridgeSession> {
        val list = info?.getJSONArray("sessions") ?: throw IOException("Not connected")
        require(list.length() <= 32) { "Too many sessions" }
        return (0 until list.length()).map { index ->
            val row = list.getJSONObject(index)
            val name = row.getString("name")
            require(SshBridge.validSession(name)) { "Invalid session" }
            BridgeSession(name, row.optBoolean("reachable", true))
        }
    }
    override fun closeStdio() {
        generation.incrementAndGet()
        heartbeat?.shutdownNow(); heartbeat = null
        val old = stream; stream = null
        try { old?.close() } catch (_: Exception) {}
        replies.disconnect()
    }
    @Synchronized override fun disconnect() {
        connected = false; closeStdio(); client?.close(); client = null
        device = ""; secret = ""; info = null
    }
    override fun openStdio(sessionName: String) {
        require(SshBridge.validSession(sessionName)) { "Invalid session" }
        closeStdio()
        val owner = client ?: throw IOException("Not connected")
        val epoch = generation.get()
        val socket = owner.socket(15000)
        try {
            PinnedTlsClient.write(socket.outputStream, credentials("stdio").put("session", sessionName))
            val input = BufferedInputStream(socket.inputStream)
            require(PinnedTlsClient.read(input).optBoolean("ok")) { "PC refused the selected session" }
            socket.soTimeout = 0
            if (epoch != generation.get()) throw IOException("Connection cancelled")
            stream = socket
            Thread({
                try {
                    while (epoch == generation.get()) {
                        val obj = PinnedTlsClient.read(input)
                        require(obj.getInt("version") == 1 && obj.getString("sessionId") == sessionName) { "Invalid bridge frame" }
                        val frame = Frame(obj.optString("id"), obj.getString("type"), sessionName,
                            if (obj.isNull("seq")) null else obj.getLong("seq"), obj.getJSONObject("payload"))
                        val requestId = frame.payload.optString("requestId", "")
                        if (requestId.isEmpty() || !replies.deliver(requestId, frame.type, frame)) onFrame?.invoke(frame)
                    }
                } catch (_: Exception) {
                    // Complete pending requests without retaining frames or credentials in logs.
                } finally {
                    socket.close(); owner.release(socket)
                    if (epoch == generation.get()) {
                        stream = null; connected = false; replies.disconnect(); onDisconnect?.invoke()
                    }
                }
            }, "herdr-paired-reader").also { it.isDaemon = true; it.start() }
            synchronized(this) {
                if (epoch != generation.get() || stream !== socket) throw IOException("Connection cancelled")
                heartbeat = Executors.newSingleThreadScheduledExecutor { task ->
                    Thread(task, "herdr-paired-keepalive").also { it.isDaemon = true }
                }.also { timer ->
                    timer.scheduleWithFixedDelay({
                        try {
                            // Existing servers already support this read-only request. No new wire protocol.
                            // Snapshot events are unsolicited and have no requestId. An empty,
                            // inactive subscription has a correlated ack and reads no pane data.
                            request("timeline.subscribe", sessionName, JSONObject()
                                .put("clientId", "android-keepalive")
                                .put("paneIds", org.json.JSONArray()).put("active", false), "ack", 15000)
                        } catch (_: Exception) {
                            // Closing the exact socket wakes its reader and the controller's reconnect path.
                            try { socket.close() } catch (_: Exception) {}
                            timer.shutdown()
                        }
                    }, 20, 20, TimeUnit.SECONDS)
                }
            }
        } catch (e: Exception) { socket.close(); owner.release(socket); throw e }
    }
    @Synchronized override fun send(type: String, sessionId: String, payload: JSONObject): String {
        val id = "p-${System.currentTimeMillis()}-${sequence.incrementAndGet()}"
        val socket = stream ?: throw IOException("Bridge stream is not open")
        PinnedTlsClient.write(socket.outputStream, JSONObject(envelope(id, type, sessionId, payload)))
        return id
    }
    override fun request(type: String, sessionId: String, payload: JSONObject, expected: String, timeoutMs: Long): Frame {
        val id = "p-${System.currentTimeMillis()}-${sequence.incrementAndGet()}"
        val future = replies.register(id, setOf(expected))
        try {
            synchronized(this) {
                val socket = stream ?: throw IOException("Bridge stream is not open")
                PinnedTlsClient.write(socket.outputStream, JSONObject(envelope(id, type, sessionId, payload)))
            }
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } finally { replies.remove(id) }
    }
}
