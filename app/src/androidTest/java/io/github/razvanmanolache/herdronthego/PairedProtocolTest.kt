package io.github.razvanmanolache.herdronthego

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class PairedProtocolTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private fun ui(block: () -> Unit) = instrument.runOnMainSync(block)
    private fun await(seconds: Int = 30, condition: () -> Boolean) {
        val until = System.nanoTime() + seconds * 1000000000L
        while (System.nanoTime() < until) {
            var ready = false
            ui { ready = condition() }
            if (ready) return
            Thread.sleep(50)
        }
        throw AssertionError("Paired condition did not complete within $seconds seconds")
    }

    @Test fun legacyConnectionsAndPairedCredentialsStaySeparate() = IsolatedContext(instrument.targetContext).use { ctx ->
        val pin = "a".repeat(64)
        ctx.getSharedPreferences("herdr_on_the_go", 0).edit().putInt("conns_n", 1)
            .putString("conn_0", listOf("pc", "22", "demo", "named").joinToString(1.toChar().toString())).commit()
        val c = BridgeController(ctx)
        try {
            val ssh = c.savedConnections().single()
            assertEquals("ssh", ssh.protocol)
            val paired = ssh.copy(protocol = "paired", certificate = pin)
            c.saveConnection(ssh, "synthetic-ssh-password")
            c.saveConnection(paired, "synthetic-device-secret")
            assertEquals(2, c.savedConnections().size)
            assertEquals("synthetic-ssh-password", c.passwordFor(ssh))
            assertEquals("synthetic-device-secret", c.passwordFor(paired))
            assertEquals(pin, c.savedConnections().first { it.protocol == "paired" }.certificate)
            ui { c.forgetConnection(paired) }
            await { c.savedConnections().size == 1 }
            assertNull(c.passwordFor(paired))
            assertEquals("synthetic-ssh-password", c.passwordFor(ssh))
        } finally { ui { c.shutdown() } }
    }

    /** The fixture approves only synthetic requests in its private temporary store. */
    @Test fun realTlsPairingApprovalControlAndRevocation() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Run tests/paired_bridge_check.py", args.containsKey("pairedPort"))
        val address = PairAddress("10.0.2.2", args.getString("pairedPort")!!.toInt(), args.getString("pairedFingerprint")!!)
        val session = args.getString("pairedSession")!!
        val marker = args.getString("pairedMarker")!!
        val fixtureToken = args.getString("pairedFixtureToken")!!
        // Wrong pins must fail in TLS before the first application message is sent.
        val wrongPin = address.copy(fingerprint = "0".repeat(64))
        assertThrows(Exception::class.java) {
            PinnedTlsClient(wrongPin).use { it.request(JSONObject().put("op", "pair.start")
                .put("name", "Must not reach server").put("secret", "x".repeat(43))) }
        }
        val report = File(instrument.targetContext.cacheDir, "paired-test-approval.json")
        lateinit var c: BridgeController
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                c = ViewModelProvider(activity)[ControllerViewModel::class.java].controller
                c.screen = Screen.PairForm("herdr-pair://${address.host}:${address.port}?fingerprint=${address.fingerprint}")
                c.pairDevice("herdr-pair://${address.host}:${address.port}?fingerprint=${address.fingerprint}", "Synthetic test phone")
            }
            await { c.pairing?.code?.length == 6 }
            val phoneCode = c.pairing!!.code
            scenario.recreate()
            scenario.onActivity { activity ->
                assertSame(c, ViewModelProvider(activity)[ControllerViewModel::class.java].controller)
                assertEquals(phoneCode, c.pairing!!.code)
            }
            report.writeText(JSONObject().put("fixture", fixtureToken).put("code", phoneCode).toString())
            await { c.pairing == null && c.busy == null && c.active() != null }
            assertNull(c.error)
            val conn = c.savedConnections().single()
            assertEquals("paired", conn.protocol)
            assertEquals(address.fingerprint, conn.certificate)
            assertNotNull(c.passwordFor(conn))
            ui { c.attach(session) }
            await { c.busy == null && c.active()?.attached == true }
            assertNull(c.error)
            val target = c.targetFor(c.currentPaneId!!)!!
            val message = "echo PAIRED_$marker"
            ui { c.setDraft(target, message); c.sendMessage(target, message) }
            await { !c.sending }
            assertNull(c.error)
            assertTrue(c.pendingActions.isEmpty())
            await { c.paneText[target.id]?.contains("PAIRED_$marker") == true }
            report.writeText(JSONObject().put("fixture", fixtureToken).put("revoke", conn.user).toString())
            await { c.active()?.attached == false }
            ui { c.openConnection(conn, c.passwordFor(conn)) { throw AssertionError("Paired mode must not prompt for SSH") } }
            await { c.busy == null }
            assertNotNull(c.error)
            assertTrue(c.servers.isEmpty())
        }
        assertTrue(c.servers.isEmpty())
        report.delete()
    }
}
