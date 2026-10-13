package io.github.razvanmanolache.herdronthego

import android.Manifest
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Production picker exercised with fictional data; no real connections or credentials. */
class TabNavigatorTest {
    // NOTE: the app asks for POST_NOTIFICATIONS on launch — pre-grant
    // so no system dialog covers the activity mid-test (it would stay
    // PAUSED and compose would never settle).
    @get:Rule
    val notifications: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val work = TabDestination("desk", "named", "client", "api", "pane-a",
        "desk.example", "demo", "Client work", "API", "idle")
    private val weekend = work.copy(workspaceId = "weekend", tabId = "docs", paneId = "pane-b",
        space = "Weekend project", label = "Docs")
    private val laptop = work.copy(connection = "laptop", computer = "laptop.example", paneId = "pane-c")
    private val theme = THEMES.first()

    private fun render(onSelect: (TabDestination) -> Unit) {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MaterialTheme {
                    TabNavigator(listOf(work, weekend, laptop), "desk", "named", "api", theme,
                        onSelect, {})
                }
            }
        }
    }

    @Test fun searchFindsAnotherComputerAndSelectsItsExactDestination() {
        var selected: TabDestination? = null
        render { selected = it }
        compose.onNodeWithTag("tab-search").performTextInput("laptop client API")
        compose.onNodeWithTag("tab:${work.identity}").assertDoesNotExist()
        compose.onNodeWithTag("tab:${laptop.identity}").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(laptop, selected) }
    }

    @Test fun inactiveSpaceIsSearchableAndEmptySearchCanBeCleared() {
        var selected: TabDestination? = null
        render { selected = it }
        compose.onNodeWithTag("tab-search").performTextInput("weekend docs")
        compose.onNodeWithTag("tab:${weekend.identity}").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(weekend, selected) }
        compose.onNodeWithTag("tab-search").performTextReplacement("no-such-tab")
        compose.onNodeWithText("No matching tabs").assertIsDisplayed()
        compose.onNodeWithTag("tab-search").performTextClearance()
        compose.onNodeWithTag("tab:${work.identity}").assertExists()
    }
}
