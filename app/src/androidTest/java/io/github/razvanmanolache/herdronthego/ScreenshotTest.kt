package io.github.razvanmanolache.herdronthego

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/** Documentation captures render production composables with wholly fictional state.
 * This fixture never connects to a host, reads real credentials, or sends a command.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val pins = object : KnownHostsStore {
        override fun get(port: Int, host: String): String? = null
        override fun put(port: Int, host: String, key: String) = error("Screenshot fixture must not connect")
    }
    private fun server(c: BridgeController, host: String, session: String, color: Color, labels: List<String>): ServerConn {
        val conn = SavedConnection(host, 22, "demo", session)
        c.saveConnection(conn, null)
        val panes = labels.mapIndexed { i, label ->
            PaneEntry("$host-pane-$i", "$host-tab-$i", if (i >= 2) "$host-lab" else "$host-space", "demo-agent",
                if (i == 1) "working" else "idle", i == 0, "C:\\Projects\\demo-dashboard", label, 42)
        }
        return ServerConn(conn, pins, color).also { srv ->
            srv.sessions = listOf(BridgeSession("default", true), BridgeSession("client-work", true), BridgeSession("weekend-project", true))
            srv.pickedSession = session
            srv.attached = true
            srv.connPhase = "live"
            srv.currentSession = session
            srv.currentPaneId = panes.first().paneId
            srv.currentTabId = panes.first().tabId
            srv.snapshot = SnapshotState("0.9.3", 22,
                panes.map { AgentEntry(it.paneId, it.tabId, it.workspaceId, it.agent, it.status, it.focused, it.cwd, it.title, it.revision) },
                panes, panes.mapIndexed { i, p -> TabEntry(p.tabId, p.workspaceId, labels[i], i + 1, i == 0, 1) },
                panes.groupBy { it.workspaceId }.entries.mapIndexed { i, (id, members) ->
                    WorkspaceEntry(id, if (i > 0) "Sandbox" else when(host) {
                        "desk.example" -> "Client work"; "laptop.example" -> "Weekend project"; else -> "Build queue"
                    }, i + 1, i == 0, members.size, members.size, members.first().tabId, "idle")
                },
                "$host-space", panes.first().tabId, panes.first().paneId)
            srv.paneText[panes.first().paneId] = """
                PS C:\Projects\demo-dashboard>
                \u001B[36m❯ Check the API and run the tests.\u001B[0m

                \u001B[35m[agent] Reviewing routes and tests...\u001B[0m
                \u001B[32m✓ Validated request handlers\u001B[0m
                \u001B[32m✓ Fixed the missing error state\u001B[0m
                \u001B[1;32m✓ 24 tests passed\u001B[0m

                Build completed successfully.
                \u001B[33mFictional demo. No private project data.\u001B[0m

                ❯ npm run build
                ❯ npm test

                Ready for the next task.
            """.trimIndent().replace("\\u001B", "\u001B")
            c.servers[conn.key] = srv
            c.recordSummary(srv)
        }
    }

    @Test fun capturePublicDocumentation() {
        assertTrue(instrument.targetContext.applicationContext is IsolatedTestApplication)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var c: BridgeController
            lateinit var desk: ServerConn
            scenario.onActivity { activity ->
                c = ViewModelProvider(activity)[ControllerViewModel::class.java].controller
                desk = server(c, "desk.example", "client-work", Color(0xFF60A5FA), listOf("API build", "UI polish", "Tests"))
                server(c, "laptop.example", "weekend-project", Color(0xFF4ADE80), listOf("Docs", "Prototype"))
                server(c, "build.example", "default", Color(0xFFC084FC), listOf("Build queue"))
                c.activeKey = desk.conn.key
                c.setDraft(CommandTarget(desk.conn.key, desk.currentSession, desk.currentPaneId!!), "Summarize the changes before I commit.")
            }
            for (mode in listOf("connections", "sessions", "terminal", "font-size", "computers", "jump", "search", "pair-request", "pair-code", "fit-menu")) {
                scenario.onActivity { activity ->
                    c.setTheme(if (mode == "search") "sand" else "graphite")
                    c.setFont(if (mode == "font-size") 18f else 14f)
                    activity.setContent {
                        MaterialTheme(colorScheme = c.theme.colorScheme()) {
                            Surface(Modifier.fillMaxSize(), color = c.theme.bg) {
                                Box(Modifier.fillMaxSize()) {
                                    when (mode) {
                                        "connections" -> ConnectionsScreen(c)
                                        "sessions" -> SessionsScreen(c)
                                        "terminal", "font-size", "fit-menu" -> AgentScreen(c)
                                        "computers" -> { AgentScreen(c); ServersDialog(c) {} }
                                        "jump" -> { AgentScreen(c); JumpDialog(c) {} }
                                        "pair-request" -> PairScreen(c, "herdr-pair://desk.example:7443?fingerprint=${"a".repeat(64)}")
                                        "pair-code" -> PairScreen(c, "", PairProgress("desk.example", "123456", System.currentTimeMillis() + 180000))
                                        "search" -> {
                                            val pane = desk.snapshot!!.panes.first()
                                            val target = CommandTarget(desk.conn.key, desk.currentSession, pane.paneId)
                                            ChatDetail(c, "API build", pane, pane.paneId, target, true, {}, c.draft(target), {}, {},
                                                modifier = Modifier.statusBarsPadding())
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                instrument.waitForIdleSync()
                compose.waitForIdle()
                if (mode == "fit-menu") {
                    compose.onNodeWithText("⋯").performClick()
                    compose.waitForIdle()
                    compose.onNodeWithText("Message options").assertIsDisplayed()
                }
                Thread.sleep(2000) // Allow software-rendered emulator frames and dialog animations to settle.
                val bitmap = instrument.uiAutomation.takeScreenshot()
                val folder = File(instrument.targetContext.cacheDir, "public-screenshots").also { it.mkdirs() }
                FileOutputStream(File(folder, "$mode.png")).use {
                    assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
                bitmap.recycle()
            }
        }
    }
}
