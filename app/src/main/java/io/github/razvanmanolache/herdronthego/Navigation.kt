package io.github.razvanmanolache.herdronthego

/** IDs always include the computer and session; labels are only for display/search. */
data class TabDestination(
    val connection: String,
    val session: String,
    val workspaceId: String,
    val tabId: String,
    val paneId: String,
    val computer: String,
    val user: String,
    val space: String,
    val label: String,
    val status: String,
) {
    val identity: String get() = "$connection\u0000$session\u0000$tabId"
    val context: String get() = "$computer · $space"

    fun matches(query: String): Boolean = query.trim().split(Regex("\\s+"))
        .filter { it.isNotEmpty() }.all { word ->
            listOf(computer, user, session, space, label).any { it.contains(word, ignoreCase = true) }
        }
}

/** Enumerate every workspace, including inactive spaces and ordinary terminal tabs. */
fun tabDestinations(
    connection: String, computer: String, user: String, session: String,
    snapshot: SnapshotState, currentPaneId: String?,
): List<TabDestination> {
    val spaces = snapshot.workspaces.associateBy { it.workspaceId }
    val panes = snapshot.panes.groupBy { it.tabId }
    return snapshot.tabs.sortedWith(compareBy<TabEntry>(
        { spaces[it.workspaceId]?.number ?: Int.MAX_VALUE }, { it.number }
    )).mapNotNull { tab ->
        val candidates = panes[tab.tabId].orEmpty().filter { it.workspaceId == tab.workspaceId }
        val pane = candidates.firstOrNull { it.paneId == currentPaneId }
            ?: candidates.firstOrNull { it.focused } ?: candidates.firstOrNull()
            ?: return@mapNotNull null
        TabDestination(connection, session, tab.workspaceId, tab.tabId, pane.paneId,
            computer, user, spaces[tab.workspaceId]?.label ?: "Other space", tab.label, pane.status)
    }
}
