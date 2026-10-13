package io.github.razvanmanolache.herdronthego

import org.json.JSONArray
import org.json.JSONObject

/** Live data from the bridge's SessionSnapshot. */
data class AgentEntry(
    val paneId: String,
    val tabId: String,
    val workspaceId: String,
    val agent: String?,
    val status: String,
    val focused: Boolean,
    val cwd: String?,
    val title: String?,
    val revision: Long,
)

data class PaneEntry(
    val paneId: String,
    val tabId: String,
    val workspaceId: String,
    val agent: String?,
    val status: String,
    val focused: Boolean,
    val cwd: String?,
    val title: String?,
    val revision: Long,
)

data class TabEntry(
    val tabId: String,
    val workspaceId: String,
    val label: String,
    val number: Int,
    val focused: Boolean,
    val paneCount: Int,
)

data class WorkspaceEntry(
    val workspaceId: String,
    val label: String,
    val number: Int,
    val focused: Boolean,
    val tabCount: Int,
    val paneCount: Int,
    val activeTabId: String?,
    val agentStatus: String,
)

data class SnapshotState(
    val version: String,
    val protocol: Int,
    val agents: List<AgentEntry>,
    val panes: List<PaneEntry>,
    val tabs: List<TabEntry>,
    val workspaces: List<WorkspaceEntry>,
    val focusedWorkspaceId: String?,
    val focusedTabId: String?,
    val focusedPaneId: String?,
)

data class BridgeSession(val name: String, val reachable: Boolean)

data class HelloInfo(
    val bridgeVersion: String,
    val herdrVersion: String,
    val herdrProtocol: Int,
    val hostname: String,
    val capabilities: List<String>,
)

fun JSONObject.optStr(key: String): String? =
    if (isNull(key)) null else optString(key)

fun parseSnapshot(obj: JSONObject): SnapshotState {
    fun agents(): List<AgentEntry> {
        val out = mutableListOf<AgentEntry>()
        val arr = obj.optJSONArray("agents") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            out += AgentEntry(
                paneId = a.optString("pane_id"),
                tabId = a.optString("tab_id"),
                workspaceId = a.optString("workspace_id"),
                agent = a.optStr("agent") ?: a.optStr("display_agent"),
                status = a.optString("agent_status", "unknown"),
                focused = a.optBoolean("focused", false),
                cwd = a.optStr("cwd") ?: a.optStr("foreground_cwd"),
                title = a.optStr("terminal_title_stripped")
                    ?: a.optStr("title") ?: a.optStr("display_agent"),
                revision = a.optLong("revision", 0),
            )
        }
        return out
    }
    fun panes(): List<PaneEntry> {
        val out = mutableListOf<PaneEntry>()
        val arr = obj.optJSONArray("panes") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            out += PaneEntry(
                paneId = p.optString("pane_id"),
                tabId = p.optString("tab_id"),
                workspaceId = p.optString("workspace_id"),
                agent = p.optStr("agent") ?: p.optStr("display_agent"),
                status = p.optString("agent_status", "unknown"),
                focused = p.optBoolean("focused", false),
                cwd = p.optStr("cwd") ?: p.optStr("foreground_cwd"),
                title = p.optStr("terminal_title_stripped")
                    ?: p.optStr("title") ?: p.getIDFallback(),
                revision = p.optLong("revision", 0),
            )
        }
        return out
    }
    fun tabs(): List<TabEntry> {
        val out = mutableListOf<TabEntry>()
        val arr = obj.optJSONArray("tabs") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            out += TabEntry(
                tabId = t.optString("tab_id"),
                workspaceId = t.optString("workspace_id"),
                label = t.optString("label", t.optString("tab_id") ?: "?"),
                number = t.optInt("number", 0),
                focused = t.optBoolean("focused", false),
                paneCount = t.optInt("pane_count", 0),
            )
        }
        return out
    }
    fun workspaces(): List<WorkspaceEntry> {
        val out = mutableListOf<WorkspaceEntry>()
        val arr = obj.optJSONArray("workspaces") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val w = arr.getJSONObject(i)
            out += WorkspaceEntry(
                workspaceId = w.optString("workspace_id"),
                label = w.optString("label", w.optString("workspace_id") ?: "?"),
                number = w.optInt("number", 0),
                focused = w.optBoolean("focused", false),
                tabCount = w.optInt("tab_count", 0),
                paneCount = w.optInt("pane_count", 0),
                activeTabId = w.optStr("active_tab_id"),
                agentStatus = w.optString("agent_status", "unknown"),
            )
        }
        return out
    }
    return SnapshotState(
        version = obj.optString("version", "?"),
        protocol = obj.optInt("protocol", 0),
        agents = agents(),
        panes = panes(),
        tabs = tabs(),
        workspaces = workspaces(),
        focusedWorkspaceId = obj.optStr("focused_workspace_id"),
        focusedTabId = obj.optStr("focused_tab_id"),
        focusedPaneId = obj.optStr("focused_pane_id"),
    )
}

/** Pane title fallback when neither title key exists (plain terminals). */
private fun JSONObject.getIDFallback(): String? = optStr("pane_id")

fun parseHello(payload: JSONObject): HelloInfo {
    val server = payload.optJSONObject("server") ?: JSONObject()
    val caps = mutableListOf<String>()
    val arr = server.optJSONArray("capabilities") ?: JSONArray()
    for (i in 0 until arr.length()) caps += arr.optString(i)
    return HelloInfo(
        bridgeVersion = server.optString("bridgeVersion", "?"),
        herdrVersion = server.optString("herdrVersion", "?"),
        herdrProtocol = server.optInt("herdrProtocol", 0),
        hostname = server.optString("hostname", "?"),
        capabilities = caps,
    )
}

/** Outbound envelope builder: mirrors the real mobile protocol. */
fun envelope(id: String, type: String, sessionId: String, payload: JSONObject): String {
    val env = JSONObject()
    env.put("version", 1)
    env.put("id", id)
    env.put("type", type)
    env.put("sessionId", sessionId)
    env.put("timestamp", System.currentTimeMillis())
    env.put("payload", payload)
    return env.toString()
}
