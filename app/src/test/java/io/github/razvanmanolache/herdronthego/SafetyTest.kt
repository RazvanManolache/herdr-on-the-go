package io.github.razvanmanolache.herdronthego

import com.jcraft.jsch.HostKeyRepository
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.ExecutionException

class SafetyTest {
    private fun failure(block: () -> Unit) { assertThrows(Exception::class.java) { block() } }
    private fun journal(block: (MutationJournal, File, (Long) -> Unit) -> Unit) {
        val directory = Files.createTempDirectory("herdr-journal-test").toFile()
        var time = 1000L
        try { block(MutationJournal(File(directory, "pending.json")) { time }, File(directory, "pending.json")) { time = it } }
        finally { directory.deleteRecursively() }
    }
    private val a = CommandTarget("a:22:user", "named", "pane-1")

    @Test fun messageAppendsEnterExactlyOnce() {
        assertEquals("echo ok\n", BridgeLimits.message("echo ok"))
        assertEquals("echo ok\n", BridgeLimits.message("echo ok\n"))
    }
    @Test fun messageLimitIncludesEnter() {
        assertEquals(12000, BridgeLimits.message("x".repeat(11999)).length)
        failure { BridgeLimits.message("x".repeat(12000)) }
    }
    @Test fun messageRejectsEmptyNulAndBrokenUnicode() {
        listOf("", " \n", "a\u0000b", "\uD800").forEach { text -> failure { BridgeLimits.message(text) } }
        assertEquals("🙂\n", BridgeLimits.message("🙂"))
    }
    @Test fun attachmentReadsOnlyLimitPlusOne() {
        val input = ByteArrayInputStream(ByteArray(100000) { 65 })
        failure { BridgeLimits.readText(input, 10) }
        assertEquals(99989, input.available())
    }
    @Test fun attachmentAcceptsUtf8AtLimit() {
        assertEquals("a\n\tb", BridgeLimits.readText(ByteArrayInputStream("a\n\tb".toByteArray()), 4))
        assertEquals("", BridgeLimits.readText(ByteArrayInputStream(byteArrayOf())))
    }
    @Test fun attachmentRejectsBinaryAndMalformedUtf8() {
        listOf(byteArrayOf(65, 0), byteArrayOf(65, 1), byteArrayOf(0xc3.toByte())).forEach { bytes ->
            failure { BridgeLimits.readText(ByteArrayInputStream(bytes)) }
        }
    }
    @Test fun immediateReplyCannotBeatRegistration() {
        val router = ReplyRouter<String>()
        val future = router.register("request", setOf("raw.read.result"))
        assertTrue(router.deliver("request", "raw.read.result", "immediate result"))
        assertEquals("immediate result", future.get())
    }
    @Test fun intermediateAckDoesNotFinishMutation() {
        val router = ReplyRouter<String>()
        val future = router.register("request", setOf("command.result"))
        assertTrue(router.deliver("request", "ack", "accepted"))
        assertFalse(future.isDone)
        router.deliver("request", "command.result", "verdict")
        assertEquals("verdict", future.get())
    }
    @Test fun wrongReplyCannotSatisfyRead() {
        val router = ReplyRouter<String>()
        val future = router.register("r", setOf("raw.read.result"))
        router.deliver("r", "snapshot", "wrong")
        assertFalse(future.isDone)
        assertFalse(router.deliver("unrelated", "raw.read.result", "wrong"))
        router.deliver("r", "command.result", "rejected")
        assertEquals("rejected", future.get())
    }
    @Test fun disconnectFailsAndClearsWaiters() {
        val router = ReplyRouter<String>(1)
        val future = router.register("r", setOf("result"))
        router.disconnect()
        assertThrows(ExecutionException::class.java) { future.get() }
        assertFalse(router.deliver("r", "result", "late"))
        assertFalse(router.register("new", setOf("result")).isDone)
    }
    @Test fun requestStorageIsBoundedAndDuplicatesRejected() {
        val router = ReplyRouter<String>(2)
        router.register("r", setOf("result"))
        failure { router.register("r", setOf("result")) }
        router.register("s", setOf("result"))
        failure { router.register("t", setOf("result")) }
        router.remove("r")
        router.register("t", setOf("result"))
    }
    @Test fun reservationSurvivesRestartAndReusesKey() = journal { store, file, _ ->
        val first = store.reserve(a, "pane.sendText", "private command\n")
        val restarted = MutationJournal(file) { 2000 }
        assertEquals(first.key, restarted.reserve(a, "pane.sendText", "private command\n").key)
        assertFalse(file.readText().contains("private command"))
    }
    @Test fun uncertainAndPartialRetainKey() = journal { store, _, _ ->
        val first = store.reserve(a, "pane.sendText", "command")
        store.uncertain(first, "partial", "Enter failed")
        assertEquals("partial", store.records().single().state)
        assertEquals(first.key, store.reserve(a, "pane.sendText", "command").key)
    }
    @Test fun editedUnresolvedCommandIsBlocked() = journal { store, _, _ ->
        store.reserve(a, "pane.sendText", "first")
        failure { store.reserve(a, "pane.sendText", "different") }
        assertEquals(1, store.records().size)
    }
    @Test fun reservationsAreScopedToComputerSessionPaneAndOperation() = journal { store, _, _ ->
        val records = listOf(a, a.copy(connection = "b"), a.copy(session = "other"), a.copy(id = "pane-2"))
            .map { store.reserve(it, "pane.sendText", "same") } + store.reserve(a, "space.close", "same")
        assertEquals(5, records.map { it.key }.distinct().size)
    }
    @Test fun completedCommandCanUseNewKey() = journal { store, _, _ ->
        val first = store.reserve(a, "pane.sendText", "command")
        store.resolve(first)
        assertTrue(store.records().isEmpty())
        assertNotEquals(first.key, store.reserve(a, "pane.sendText", "command").key)
    }
    @Test fun oldReservationsAndClockRollbackCannotBeRetried() = journal { store, _, time ->
        store.reserve(a, "pane.sendText", "command")
        time(1000 + MutationJournal.RETRY_WINDOW_MS - 1)
        store.reserve(a, "pane.sendText", "command")
        time(1000 + MutationJournal.RETRY_WINDOW_MS)
        failure { store.reserve(a, "pane.sendText", "command") }
        time(999)
        failure { store.reserve(a, "pane.sendText", "command") }
    }
    @Test fun corruptJournalBlocksNewCommands() = journal { store, file, _ ->
        file.writeText("broken")
        failure { store.reserve(a, "pane.sendText", "command") }
        assertEquals("broken", file.readText())
    }
    @Test fun journalDoesNotEvictUnresolvedReservations() = journal { store, _, _ ->
        repeat(MutationJournal.MAX_RECORDS) { store.reserve(a.copy(id = "pane-$it"), "pane.sendText", "command") }
        failure { store.reserve(a.copy(id = "extra"), "pane.sendText", "command") }
        assertEquals(MutationJournal.MAX_RECORDS, store.records().size)
    }
    @Test fun fingerprintHashesRawKeyBytes() {
        // SHA256 of ASCII abc, independently known digest.
        assertEquals("SHA256:ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0", hostFingerprint("abc".toByteArray()))
    }
    private class Pins(var pin: String? = null) : KnownHostsStore {
        var writes = 0
        override fun get(port: Int, host: String) = pin
        override fun put(port: Int, host: String, key: String) { writes++; pin = key }
    }
    @Test fun rejectedUnknownKeyIsNeverPinned() {
        val pins = Pins()
        val verifier = VerifyingHostKeys(pins, "host", 22) { false }
        assertEquals(HostKeyRepository.NOT_INCLUDED, verifier.check("host", byteArrayOf(1, 2)))
        assertEquals(0, pins.writes)
        assertNotNull(verifier.rejection)
    }
    @Test fun changedKeyCannotBeAcceptedOrOverwritten() {
        val pins = Pins(Base64.getEncoder().encodeToString(byteArrayOf(1, 2)))
        val verifier = VerifyingHostKeys(pins, "host", 22) { throw AssertionError("Must not prompt to replace a key") }
        assertEquals(HostKeyRepository.CHANGED, verifier.check("host", byteArrayOf(3, 4)))
        assertEquals(0, pins.writes)
    }
    @Test fun acceptedKeyIsPinnedAndKnownKeyNeedsNoPrompt() {
        val pins = Pins()
        val key = byteArrayOf(1, 2)
        assertEquals(HostKeyRepository.OK, VerifyingHostKeys(pins, "host", 22) { true }.check("host", key))
        assertEquals(1, pins.writes)
        assertEquals(HostKeyRepository.OK, VerifyingHostKeys(pins, "host", 22) { throw AssertionError() }.check("host", key))
        assertEquals(1, pins.writes)
    }
    @Test fun sessionNamesCannotInjectShellArguments() {
        listOf("default", "named-session_1.2").forEach { assertTrue(SshBridge.validSession(it)) }
        listOf("", "-option", "a;echo x", "a'b", "a\nb", "a".repeat(129)).forEach { assertFalse(SshBridge.validSession(it)) }
    }
}
