package io.github.razvanmanolache.herdronthego

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real navigation controls with synthetic, unopened transports only. */
class NavigationControlsTest {
    @get:Rule val notifications = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val pins = object : KnownHostsStore {
        override fun get(port: Int, host: String): String? = null
        override fun put(port: Int, host: String, key: String) = error("Fixture must not authenticate")
    }

    @Test fun sessionCancelClosesOnlyTheUnattachedAttemptAndKeepsSavedConnection() {
        lateinit var c: BridgeController
        lateinit var saved: SavedConnection
        compose.runOnUiThread {
            c = ViewModelProvider(compose.activity)[ControllerViewModel::class.java].controller
            saved = SavedConnection("desk.example", 22, "demo", "default")
            c.saveConnection(saved, null)
            c.servers[saved.key] = ServerConn(saved, pins, Color.Blue)
            c.activeKey = saved.key
            c.screen = Screen.Sessions
        }
        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(5000) { c.servers.isEmpty() && c.screen == Screen.Connections }
        assertTrue(c.savedConnections().any { it == saved })
    }

    @Test fun sidebarOpensConnectionManagementWithoutDisconnectingTheComputer() {
        lateinit var c: BridgeController
        lateinit var server: ServerConn
        compose.runOnUiThread {
            c = ViewModelProvider(compose.activity)[ControllerViewModel::class.java].controller
            val saved = SavedConnection("desk.example", 22, "demo", "default")
            server = ServerConn(saved, pins, Color.Blue).also { it.attached = true }
            c.servers[saved.key] = server
            c.activeKey = saved.key
            c.screen = Screen.Agent
        }
        compose.onNodeWithContentDescription("menu").performClick()
        compose.onNodeWithText("Manage connections").assertIsDisplayed().performClick()
        compose.waitUntil(5000) { c.screen == Screen.Connections }
        assertSame(server, c.servers[server.conn.key])
        assertTrue(server.attached)
    }
}
