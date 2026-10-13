package io.github.razvanmanolache.herdronthego

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.io.FileOutputStream

/** Every preference/file name is isolated; tests never open a user's saved connections. */
class IsolatedContext(base: Context) : ContextWrapper(base), AutoCloseable {
    private val prefix = "test_${UUID.randomUUID()}_"
    private val names = mutableSetOf<String>()
    private val directory = File(base.noBackupFilesDir, prefix).also { it.mkdirs() }
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        names.add(name)
        return baseContext.getSharedPreferences(prefix + name, mode)
    }
    override fun deleteSharedPreferences(name: String): Boolean = baseContext.deleteSharedPreferences(prefix + name)
    override fun getNoBackupFilesDir(): File = directory
    override fun close() { names.toList().forEach { deleteSharedPreferences(it) }; directory.deleteRecursively() }
}

@RunWith(AndroidJUnit4::class)
class ControllerTest {
    // NOTE: the app asks for POST_NOTIFICATIONS on launch — pre-grant
    // so no system dialog covers the activity mid-test (it would stay
    // PAUSED and compose would never settle).
    @get:Rule
    val notifications: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private fun ui(block: () -> Unit) = instrument.runOnMainSync(block)
    private fun await(seconds: Int = 25, condition: () -> Boolean) {
        val deadline = System.nanoTime() + seconds * 1000000000L
        while (System.nanoTime() < deadline) {
            var done = false
            ui { done = condition() }
            if (done) return
            Thread.sleep(50)
        }
        throw AssertionError("Condition did not complete within $seconds seconds")
    }
    private fun isolated(block: (IsolatedContext) -> Unit) = IsolatedContext(instrument.targetContext).use(block)

    @Test fun passwordsUseEncryptedStorageAndForgetErasesThem() = isolated { ctx ->
        val c = BridgeController(ctx)
        val conn = SavedConnection("synthetic.invalid", 22, "test-user", "named")
        try {
            assertTrue(c.canSavePasswords)
            c.saveConnection(conn, "synthetic-secret-not-real")
            assertEquals("synthetic-secret-not-real", c.passwordFor(conn))
            assertTrue(ctx.getSharedPreferences("herdr_on_the_go_secret_plain", 0).all.isEmpty())
            val encrypted = ctx.getSharedPreferences("herdr_on_the_go_secret", 0).all.toString()
            assertFalse(encrypted.contains("synthetic-secret-not-real"))
            ui { c.forgetConnection(conn) }
            await { c.savedConnections().isEmpty() }
            assertNull(c.passwordFor(conn))
        } finally { ui { c.shutdown() } }
    }
    @Test fun legacyPlaintextIsErasedAndNeverReused() = isolated { ctx ->
        ctx.getSharedPreferences("herdr_on_the_go_secret_plain", 0).edit().putString("pw:test", "old-test-password").commit()
        val credentials = Credentials(ctx)
        assertTrue(ctx.getSharedPreferences("herdr_on_the_go_secret_plain", 0).all.isEmpty())
        assertTrue(credentials.notice!!.contains("removed"))
    }
    @Test fun encryptionFailureDoesNotSavePlaintext() = isolated { ctx ->
        val broken = object : ContextWrapper(ctx) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                if (name == "herdr_on_the_go_secret") throw java.io.IOException("Synthetic secure-store failure")
                return ctx.getSharedPreferences(name, mode)
            }
        }
        val credentials = Credentials(broken)
        assertFalse(credentials.available)
        assertThrows(Exception::class.java) { credentials.save(SavedConnection("test", 22, "u", "default"), "must-not-be-written") }
        assertTrue(ctx.getSharedPreferences("herdr_on_the_go_secret_plain", 0).all.isEmpty())
        assertTrue(credentials.notice!!.contains("unavailable"))
    }
    @Test fun failedLegacyCleanupDisablesPasswordSaving() = isolated { ctx ->
        val broken = object : ContextWrapper(ctx) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                if (name == "herdr_on_the_go_secret_plain") throw java.io.IOException("Synthetic legacy read failure")
                return ctx.getSharedPreferences(name, mode)
            }
        }
        val credentials = Credentials(broken)
        assertFalse(credentials.available)
        assertTrue(credentials.notice!!.contains("without saving"))
        assertThrows(Exception::class.java) { credentials.save(SavedConnection("test", 22, "u", "default"), "must-not-be-written") }
        assertTrue(ctx.getSharedPreferences("herdr_on_the_go_secret", 0).all.isEmpty())
    }
    @Test fun draftsAreScopedToComputerSessionAndPane() = isolated { ctx ->
        val c = BridgeController(ctx)
        try {
            val a = CommandTarget("computer-a", "named", "pane")
            val others = listOf(a.copy(connection = "computer-b"), a.copy(session = "other"), a.copy(id = "other-pane"))
            ui { c.setDraft(a, "command for A") }
            others.forEach { assertEquals("", c.draft(it)) }
            ui { c.setDraft(others[0], "command for B") }
            assertEquals("command for A", c.draft(a))
            assertEquals("command for B", c.draft(others[0]))
        } finally { ui { c.shutdown() } }
    }
    @Test fun chosenSessionAndPendingActionsSurviveControllerRestart() = isolated { ctx ->
        val conn = SavedConnection("test", 22, "test", "non-default")
        val target = CommandTarget(conn.key, conn.session, "pane")
        val record = MutationJournal(File(ctx.noBackupFilesDir, "pending-actions.json")).reserve(target, "pane.sendText", "private\n")
        val first = BridgeController(ctx)
        first.saveConnection(conn, null)
        ui { first.shutdown() }
        val next = BridgeController(ctx)
        try {
            assertEquals("non-default", next.savedConnections().single().session)
            await { next.pendingActions.isNotEmpty() }
            assertEquals(record.key, next.pendingActions.single().key)
        } finally { ui { next.shutdown() } }
    }

    @Test fun activityRotationRetainsControllerDraftAndTrustDecision() {
        val target = CommandTarget("test-only", "named", "pane")
        lateinit var before: BridgeController
        var decision: Boolean? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                before = ViewModelProvider(activity)[ControllerViewModel::class.java].controller
                before.setDraft(target, "rotation draft")
                before.screen = Screen.SshForm(null)
            }
            val verifier = Thread { decision = before.askHostKey("SHA256:synthetic-test-fingerprint") }.also { it.start() }
            await { before.hostKeyPrompt != null }
            scenario.recreate()
            scenario.onActivity { activity ->
                val after = ViewModelProvider(activity)[ControllerViewModel::class.java].controller
                assertSame(before, after)
                assertEquals("rotation draft", after.draft(target))
                assertTrue(after.hostKeyPrompt!!.contains("synthetic-test-fingerprint"))
                after.answerHostKey(false)
            }
            verifier.join(3000)
            assertEquals(false, decision)
            await { before.hostKeyPrompt == null }
            Thread.sleep(500) // Let the Compose dialog's exit animation finish before capture.
            instrument.waitForIdleSync()
            // A rendered screen for manual layout inspection; synthetic form values only.
            val screenshot = instrument.uiAutomation.takeScreenshot()
            assertNotNull(screenshot)
            FileOutputStream(File(instrument.targetContext.cacheDir, "fixed-ssh-form.png")).use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
        }
        assertTrue(before.servers.isEmpty())
    }

    /** Optional fixture-backed test; tools/tests run it against a disposable Windows session. */
    @Test fun windowsBridgeEndToEnd() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Run tests/windows_bridge_check.py for the live fixture", args.containsKey("bridgePort"))
        isolated { ctx ->
            val port = args.getString("bridgePort")!!.toInt()
            val session = args.getString("bridgeSession")!!
            val token = args.getString("bridgeToken")!!
            val conn = SavedConnection("10.0.2.2", port, "test-only-user", session)
            var c = BridgeController(ctx)
            val held = mutableListOf<ServerConn>()
            fun connect() {
                ui { c.connect(conn, "synthetic-test-password", { true }) }
                await { c.busy == null }
                assertNull(c.error)
                ui { c.attach(session) }
                await { c.busy == null }
                assertNull(c.error)
                assertTrue(c.active()!!.attached)
                held.add(c.active()!!)
            }
            try {
                connect()
                assertEquals(session, c.savedConnections().single().session)
                val target = c.targetFor(c.currentPaneId!!)!!
                val late = "LATE_$token"
                val text = "powershell -NoProfile -Command \"Start-Sleep -Seconds 4; Write-Output ('LATE_' + '$token')\""
                ui { c.setDraft(target, text); c.sendMessage(target, text) }
                await { !c.sending }
                assertNull(c.error)
                assertTrue(c.pendingActions.isEmpty())
                assertEquals("", c.draft(target))
                // No tab switch, manual refresh, or subscription: polling must discover delayed output.
                await { c.paneText[target.id]?.contains(late) == true }
                repeat(5) { ui { c.loadEarlier(target.id, 400) } }
                await { c.active()!!.historyLines[target.id] == BridgeLimits.LINES && c.busy == null }
                assertFalse(c.canLoadEarlier(target.id))
                val drop = "powershell -NoProfile -Command \"Write-Output ('DROP_ONCE_' + '$token')\""
                ui { c.setDraft(target, drop); c.sendMessage(target, drop) }
                await(40) { !c.sending }
                assertEquals(1, c.pendingActions.size)
                assertEquals(drop, c.draft(target))
                val key = c.pendingActions.single().key
                ui { c.shutdown() }
                c = BridgeController(ctx)
                await { c.pendingActions.isNotEmpty() }
                assertEquals(key, c.pendingActions.single().key)
                connect()
                ui { c.setDraft(target, drop); c.sendMessage(target, drop) }
                await(40) { !c.sending }
                assertTrue(c.pendingActions.isEmpty())
                await { c.paneText[target.id]?.contains("DROP_ONCE_$token") == true }
                assertEquals(1, c.paneText[target.id]!!.split("DROP_ONCE_$token").size - 1)
                // Notification reconnect must attach the named session and exact pane automatically.
                val tab = c.currentTabId!!
                ui { c.shutdown() }
                c = BridgeController(ctx)
                ui { c.deepLink(conn.key, target.id, tab, session) }
                await { c.busy == null && c.active()?.attached == true }
                assertEquals(Screen.Agent, c.screen)
                assertEquals(session, c.currentSession)
                assertEquals(target.id, c.currentPaneId)
                held.add(c.active()!!)
                // Second live SSH transport uses a different synthetic account identity.
                val second = conn.copy(user = "test-second-user")
                ui { c.connect(second, "synthetic-test-password", { throw AssertionError("Known host prompted") }, false) }
                await { c.busy == null }
                assertNull(c.error)
                held.add(c.active()!!)
                assertEquals(2, c.servers.size)
                ui { c.attach(session) }
                await { c.busy == null && c.active()?.attached == true }
                assertNull(c.error)
                // All spaces and both SSH identities appear, even though their opaque IDs overlap.
                val destinations = c.allTabs()
                assertEquals(setOf(conn.key, second.key), destinations.map { it.connection }.toSet())
                val firstComputerTabs = destinations.filter { it.connection == conn.key }
                assertTrue(firstComputerTabs.map { it.workspaceId }.toSet().size >= 2)
                val otherSpace = firstComputerTabs.first { it.workspaceId != c.snapshot!!.panes.first { p -> p.paneId == target.id }.workspaceId }
                ui { assertTrue(c.openTab(otherSpace)) }
                assertEquals(conn.key, c.activeKey)
                assertEquals(otherSpace.tabId, c.currentTabId)
                assertEquals(otherSpace.paneId, c.currentPaneId)
                await { c.paneText.containsKey(otherSpace.paneId) }
                val secondComputerTab = destinations.first { it.connection == second.key }
                ui { assertTrue(c.openTab(secondComputerTab)) }
                assertEquals(second.key, c.activeKey)
                assertEquals(secondComputerTab.paneId, c.currentPaneId)
                // A stale picker cannot attach another session or silently redirect a destination.
                ui { assertFalse(c.openTab(otherSpace.copy(session = "different-session"))) }
                assertEquals(second.key, c.activeKey)
                assertEquals(session, c.currentSession)
                ui { c.error = null }
                repeat(5) { index ->
                    ui { c.connect(conn.copy(user = "reject-$index"), "synthetic-test-password", { true }, false) }
                    await { c.busy == null }
                    assertNotNull(c.error)
                    assertEquals(2, c.servers.size)
                }
                ui { c.shutdown() }
                assertTrue(held.all { !it.bridge.isConnected() })
                assertTrue(c.servers.isEmpty())
            } finally { ui { c.shutdown() } }
        }
    }
}
