package io.github.razvanmanolache.herdronthego

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

object BridgeLimits {
    const val TEXT = 12000
    const val PANES = 64
    // NOTE: matches the bridge reply budget (~2MB); Load earlier pages into this.
    const val HISTORY_CHARS = 2000000
    const val ATTACH_BYTES = 48000
    const val FRAME_BYTES = 17 * 1024 * 1024

    fun message(text: String): String {
        require(text.isNotBlank()) { "Enter a message first" }
        require('\u0000' !in text) { "Messages cannot contain NUL characters" }
        val wire = if (text.endsWith('\n')) text else "$text\n"
        require(wire.length <= TEXT) { "Message exceeds $TEXT characters, including Enter" }
        // Reject malformed UTF-16 rather than silently changing command contents.
        Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(wire))
        return wire
    }

    fun readText(input: InputStream, maxBytes: Int = ATTACH_BYTES): String {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - out.size()))
            if (count < 0) break
            if (count == 0) continue
            out.write(buffer, 0, count)
            require(out.size() <= maxBytes) { "Text file is too large" }
        }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(out.toByteArray())).toString()
        require(text.none { it == '\u0000' || (it.code < 32 && it !in "\n\r\t") }) {
            "Choose a UTF-8 text file"
        }
        return text
    }
}

data class CommandTarget(val connection: String, val session: String, val id: String)

/** Only the exact expected reply completes a waiter; ack cannot masquerade as success. */
class ReplyRouter<T>(private val maxPending: Int = 32) {
    private data class Pending<T>(val types: Set<String>, val future: CompletableFuture<T>)
    private val pending = ConcurrentHashMap<String, Pending<T>>()

    @Synchronized fun register(id: String, types: Set<String>): CompletableFuture<T> {
        check(pending.size < maxPending) { "Too many outstanding requests" }
        val future = CompletableFuture<T>()
        check(pending.putIfAbsent(id, Pending(types, future)) == null) { "Duplicate request ID" }
        return future
    }

    fun deliver(id: String, type: String, value: T): Boolean {
        val request = pending[id] ?: return false
        if (type in request.types || type == "command.result") request.future.complete(value)
        return true // Intermediate ack belongs to the request but does not finish it.
    }

    fun remove(id: String) { pending.remove(id) }

    @Synchronized fun disconnect() {
        pending.values.forEach { it.future.completeExceptionally(IOException("SSH connection closed")) }
        pending.clear()
    }
}

data class PendingMutation(
    val target: CommandTarget, val operation: String, val hash: String, val key: String,
    val created: Long, val state: String = "unknown", val reason: String = "Reply not received",
) {
    val scope: String get() = JSONArray(listOf(target.connection, target.session, target.id, operation)).toString()
}

/** Durable reservations contain hashes and destination metadata, never command text or passwords. */
class MutationJournal(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    companion object {
        const val MAX_RECORDS = 128
        // The bridge expires completed results at 24 h; don't retry near that boundary.
        const val RETRY_WINDOW_MS = 23 * 60 * 60 * 1000L
    }

    @Synchronized fun records(): List<PendingMutation> {
        if (!file.exists()) return emptyList()
        require(file.length() <= 256 * 1024) { "Pending action journal is too large; inspect it before sending" }
        val array = JSONArray(file.readText(Charsets.UTF_8))
        require(array.length() <= MAX_RECORDS) { "Too many unresolved actions" }
        return (0 until array.length()).map { i ->
            val row = array.getJSONObject(i)
            PendingMutation(CommandTarget(row.getString("connection"), row.getString("session"), row.getString("target")),
                row.getString("operation"), row.getString("hash"), row.getString("key"), row.getLong("created"),
                row.getString("state"), row.getString("reason"))
        }.also { rows -> require(rows.map { it.scope }.distinct().size == rows.size) { "Duplicate journal reservation" } }
    }

    @Synchronized fun reserve(target: CommandTarget, operation: String, content: String): PendingMutation {
        val rows = records().toMutableList()
        val hash = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val probe = PendingMutation(target, operation, hash, "", clock())
        rows.firstOrNull { it.scope == probe.scope }?.let { previous ->
            require(previous.hash == hash) { "Inspect the previous unresolved action before changing its text" }
            require(clock() - previous.created in 0 until RETRY_WINDOW_MS) {
                "This action is too old to retry safely. Inspect the terminal and mark it resolved first"
            }
            return previous
        }
        require(rows.size < MAX_RECORDS) { "Inspect unresolved actions before sending more" }
        val fresh = probe.copy(key = UUID.randomUUID().toString())
        rows.add(fresh)
        write(rows) // Must complete before any network write.
        return fresh
    }

    @Synchronized fun uncertain(record: PendingMutation, state: String, reason: String) {
        val rows = records().map { if (it.key == record.key) it.copy(state = state, reason = reason.take(256)) else it }
        write(rows)
    }

    @Synchronized fun resolve(record: PendingMutation) { write(records().filterNot { it.key == record.key }) }

    private fun write(rows: List<PendingMutation>) {
        val array = JSONArray()
        rows.forEach { r -> array.put(JSONObject().put("connection", r.target.connection)
            .put("session", r.target.session).put("target", r.target.id).put("operation", r.operation)
            .put("hash", r.hash).put("key", r.key).put("created", r.created).put("state", r.state).put("reason", r.reason)) }
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "Cannot create pending action store" }
        val temp = File(file.parentFile, file.name + ".next")
        FileOutputStream(temp).use { stream -> stream.write(array.toString().toByteArray(Charsets.UTF_8)); stream.fd.sync() }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
