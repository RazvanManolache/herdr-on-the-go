package io.github.razvanmanolache.herdronthego

import org.junit.Assert.*
import org.junit.Test

class NavigationTest {
    private fun pane(id: String, tab: String, space: String, focused: Boolean = false) =
        PaneEntry(id, tab, space, null, "idle", focused, null, null, 0)

    private fun snapshot() = SnapshotState("test", 1, emptyList(),
        listOf(pane("p-one", "one", "work"), pane("p-two", "two", "personal"),
            pane("p-split", "two", "personal", true)),
        listOf(TabEntry("two", "personal", "API", 1, false, 2),
            TabEntry("one", "work", "API", 1, true, 1),
            TabEntry("closed", "personal", "Closed", 2, false, 0)),
        listOf(WorkspaceEntry("work", "Client work", 1, true, 1, 1, "one", "idle"),
            WorkspaceEntry("personal", "Weekend project", 2, false, 2, 2, "two", "idle")),
        "work", "one", "p-one")

    @Test fun includesInactiveSpacesAndTerminalTabsWithoutAgents() {
        val tabs = tabDestinations("desk", "desk.example", "demo", "named", snapshot(), null)
        assertEquals(listOf("one", "two"), tabs.map { it.tabId })
        assertEquals(listOf("Client work", "Weekend project"), tabs.map { it.space })
        assertEquals("p-split", tabs.last().paneId)
    }

    @Test fun keepsTheSelectedSplitPaneInsteadOfAlwaysOpeningTheFirstPane() {
        val tabs = tabDestinations("desk", "desk.example", "demo", "named", snapshot(), "p-two")
        assertEquals("p-two", tabs.last().paneId)
    }

    @Test fun duplicateTabLabelsAndIdsOnDifferentComputersStaySeparate() {
        val a = tabDestinations("desk", "desk.example", "alice", "named", snapshot(), null)
        val b = tabDestinations("laptop", "laptop.example", "bob", "named", snapshot(), null)
        assertEquals(4, (a + b).map { it.identity }.toSet().size)
        assertEquals(2, a.map { it.identity }.toSet().size)
        assertNotEquals(a.first().identity, a.first().copy(session = "other").identity)
    }

    @Test fun searchCombinesComputerSpaceTabUserAndSessionCaseInsensitively() {
        val tab = tabDestinations("desk", "desk.example", "alice", "client-work", snapshot(), null).last()
        assertTrue(tab.matches(" DESK   weekend API alice client-work "))
        assertTrue(tab.matches(""))
        assertFalse(tab.matches("laptop"))
        assertFalse(tab.matches("desk unknown"))
    }

    @Test fun mismatchedWorkspacePaneCannotBecomeADestination() {
        val snap = snapshot().let { it.copy(panes = it.panes.map { pane -> pane.copy(workspaceId = "wrong") }) }
        assertTrue(tabDestinations("desk", "desk", "demo", "named", snap, null).isEmpty())
    }
}
