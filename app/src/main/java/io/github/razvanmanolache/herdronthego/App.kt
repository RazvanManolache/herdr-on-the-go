package io.github.razvanmanolache.herdronthego

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.IOException
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit

data class SavedConnection(
    val host: String,
    val port: Int,
    val user: String,
    val session: String,
) {
    /** Identity of one computer (independent of picked session). */
    val key: String get() = "$host:$port:$user"
}

sealed class Screen {
    data object Connections : Screen()
    data class SshForm(val edit: SavedConnection?) : Screen()
    data object Sessions : Screen()
    data object Agent : Screen()
}

/** At most this many computers stay live at once. */
const val MAX_LIVE = 4

private val CONN_SEP = 1.toChar().toString()

/** One accent color per computer, shown in the header + jump list. */
private val SERVER_COLORS = listOf(
    Color(0xFF60A5FA),
    Color(0xFF4ADE80),
    Color(0xFFC084FC),
    Color(0xFFFBBF24),
    Color(0xFF2DD4BF),
    Color(0xFFF472B6),
)

/**
 * Live state of one computer: its own SSH transport plus its own
 * snapshot world, so switching computers never mixes panes, tabs,
 * subscriptions, or message drafts across machines.
 */
class ServerConn(
    conn: SavedConnection,
    hosts: KnownHostsStore,
    color: Color,
) {
    var conn by mutableStateOf(conn)
    val bridge = SshBridge(hosts)
    var color by mutableStateOf(color)
    var hello by mutableStateOf<HelloInfo?>(null)
    var snapshot by mutableStateOf<SnapshotState?>(null)
    var sessions by mutableStateOf<List<BridgeSession>>(emptyList())
    var pickedSession by mutableStateOf(conn.session.ifBlank { "default" })
    var currentSession by mutableStateOf(conn.session.ifBlank { "default" })
    var currentTabId by mutableStateOf<String?>(null)
    var currentPaneId by mutableStateOf<String?>(null)
    var connPhase by mutableStateOf("")
    var attached by mutableStateOf(false)
    var pendingJump: Pair<String, String>? = null
    var sessionPassword: String? = null
    var rememberPassword = false
    var attachmentEpoch = 0L
    var lastSnapshotPoll = 0L
    /** Last seen pane status per pane, for finish-flip detection. */
    val lastStatus = ConcurrentHashMap<String, String>()
    /** Panes with an outstanding finish ping. */
    val notified = ConcurrentHashMap.newKeySet<String>()
    val paneText = mutableStateMapOf<String, String>()
    val paneRevision = mutableStateMapOf<String, Long>()
    val helloWaiter = AtomicReference<CompletableFuture<HelloInfo>?>(null)
    val snapshotWaiter = AtomicReference<CompletableFuture<SnapshotState>?>(null)
    val historyLines = ConcurrentHashMap<String, Int>()
}

/**
 * App state + bridge driver. All blocking SSH work runs on one
 * background thread; Compose state is snapshot-safe to update there.
 *
 * Up to [MAX_LIVE] computers stay connected at once; every screen
 * reads the ACTIVE computer through the delegates below, so swapping
 * machines swaps the whole visible world with no reconnect.
 */
class BridgeController(private val ctx: Context) {
    fun ctxForToast(): Context = ctx
    private val bg = Executors.newSingleThreadExecutor()
    private val fileIo = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private val prefs: SharedPreferences =
        ctx.getSharedPreferences("herdr_on_the_go", Context.MODE_PRIVATE)

    private val credentials = Credentials(ctx)
    val canSavePasswords: Boolean get() = credentials.available
    val credentialNotice: String? get() = credentials.notice
    private val journal = MutationJournal(File(ctx.noBackupFilesDir, "pending-actions.json"))
    var pendingActions by mutableStateOf<List<PendingMutation>>(emptyList())
        private set
    var pendingReview by mutableStateOf<PendingMutation?>(null)
    private val drafts = mutableStateMapOf<CommandTarget, String>()
    var attachmentTarget: CommandTarget? = null
    var hostKeyPrompt by mutableStateOf<String?>(null)
        private set
    private val hostKeyAnswer = AtomicReference<ArrayBlockingQueue<Boolean>?>(null)

    private val hostsStore = object : KnownHostsStore {
        override fun get(port: Int, host: String): String? =
            prefs.getString("hk2:$port:$host", null)
        override fun put(port: Int, host: String, key: String) {
            check(prefs.edit().putString("hk2:$port:$host", key).commit()) { "Could not save trusted host key" }
        }
    }

    var screen: Screen by mutableStateOf(Screen.Connections)
    var busy: String? by mutableStateOf(null)
    var error: String? by mutableStateOf(null)
    var savedRevision by mutableStateOf(0)
        private set
    var theme: AppTheme by mutableStateOf(
        THEMES.firstOrNull { it.id == prefs.getString("theme_id", null) }
            ?: THEMES.first())
        private set
    var fontSp: Float by mutableStateOf(prefs.getFloat("font_sp", 14f))
        private set

    fun setTheme(id: String) {
        theme = THEMES.firstOrNull { it.id == id } ?: theme
        prefs.edit().putString("theme_id", theme.id).apply()
    }

    fun setFont(sp: Float) {
        fontSp = sp.coerceIn(10f, 24f)
        prefs.edit().putFloat("font_sp", fontSp).apply()
    }
    var rawMode: Boolean by mutableStateOf(false)
    var sending: Boolean by mutableStateOf(false)

    /** Live computers by key, in no particular order. */
    val servers = mutableStateMapOf<String, ServerConn>()
    private val recent = mutableStateListOf<String>()
    var activeKey by mutableStateOf<String?>(null)

    fun active(): ServerConn? = activeKey?.let { servers[it] }

    private fun touchRecent(key: String) {
        recent.remove(key)
        recent.add(key)
    }

    // ---- delegates: every screen reads the ACTIVE computer here ----

    val hello: HelloInfo? get() = active()?.hello
    val snapshot: SnapshotState? get() = active()?.snapshot
    val sessions: List<BridgeSession> get() = active()?.sessions ?: emptyList()
    var pickedSession: String
        get() = active()?.pickedSession ?: "default"
        set(v) { active()?.pickedSession = v }
    var currentSession: String
        get() = active()?.currentSession ?: "default"
        set(v) { active()?.currentSession = v }
    var currentTabId: String?
        get() = active()?.currentTabId
        set(v) { active()?.currentTabId = v }
    var currentPaneId: String?
        get() = active()?.currentPaneId
        set(v) { active()?.currentPaneId = v }

    private val emptyText = mutableStateMapOf<String, String>()
    private val emptyRev = mutableStateMapOf<String, Long>()

    /** paneId -> terminal text (batch events + raw.read), active server. */
    val paneText: MutableMap<String, String>
        get() = active()?.paneText ?: emptyText
    val paneRevision: MutableMap<String, Long>
        get() = active()?.paneRevision ?: emptyRev

    // ---- per-computer color (persisted, first-free palette slot) ----

    fun colorFor(c: SavedConnection): Color {
        servers[c.key]?.let { return it.color }
        val saved = prefs.getInt("color:${c.key}", -1)
        if (saved >= 0) return SERVER_COLORS[saved % SERVER_COLORS.size]
        val used = savedConnections()
            .mapNotNull { s ->
                prefs.getInt("color:${s.key}", -1).takeIf { it >= 0 }
            }.toSet()
        val idx = SERVER_COLORS.indices.firstOrNull { it !in used }
            ?: (c.key.hashCode().and(0x7fffffff) % SERVER_COLORS.size)
        prefs.edit().putInt("color:${c.key}", idx).apply()
        return SERVER_COLORS[idx]
    }

    fun activeColor(): Color = active()?.color ?: theme.accent
    fun activeHost(): String = active()?.conn?.host ?: ""

    /** Host label with a :port suffix for non-default ports. */
    fun hostLabel(c: SavedConnection): String =
        if (c.port == 22) c.host else "${c.host}:${c.port}"
    fun isActive(key: String): Boolean = activeKey == key

    fun isLive(c: SavedConnection): Boolean = servers.containsKey(c.key)

    /** Live computers, most-recently used last. */
    fun liveServers(): List<ServerConn> =
        recent.mapNotNull { servers[it] } +
            servers.values.filter { it.conn.key !in recent }

    // ---- per-connection last-known summary ("session · ws · N agents · ago") ----

    private fun sumKey(c: SavedConnection) = "sum:${c.host}:${c.port}:${c.user}"

    fun recordSummary(srv: ServerConn) {
        val snap = srv.snapshot ?: return
        val ws = snap.workspaces.firstOrNull { it.focused }?.label ?: ""
        prefs.edit().putString(sumKey(srv.conn),
            "${srv.currentSession} · $ws · ${snap.agents.size} agents · " +
                System.currentTimeMillis()).apply()
    }

    fun summaryFor(c: SavedConnection): String? {
        val raw = prefs.getString(sumKey(c), null) ?: return null
        val parts = raw.split(" · ")
        if (parts.size < 4) return raw
        val ago = parts[3].toLongOrNull() ?: return raw
        val mins = (System.currentTimeMillis() - ago) / 60000
        val whenStr = when {
            mins < 1 -> "just now"
            mins < 60 -> "${mins}m ago"
            else -> "${mins / 60}h ${mins % 60}m ago"
        }
        return "${parts[0]} · ${parts[1]} · ${parts[2]}, $whenStr"
    }

    // ---- saved connections ----

    // NOTE: connection rows are \u0001-separated (spaces would break
    // hostnames/usernames carrying them). A space split stays as a
    // fallback so rows written by other builds still parse.
    private fun parseConn(h: String): SavedConnection? {
        val parts = h.split(CONN_SEP)
        val p = if (parts.size == 4) parts else h.split(" ")
        if (p.size != 4) return null
        return SavedConnection(
            p[0], p[1].toIntOrNull() ?: 22, p[2], p[3])
    }

    fun savedConnections(): List<SavedConnection> {
        val n = prefs.getInt("conns_n", 0)
        return (0 until n).mapNotNull { i ->
            val h = prefs.getString("conn_$i", null) ?: return@mapNotNull null
            parseConn(h)
        }
    }

    fun passwordFor(c: SavedConnection): String? = try { credentials.get(c) } catch (_: Exception) { null }

    fun saveConnection(c: SavedConnection, password: String?) {
        if (password != null) credentials.save(c, password)
        val list = savedConnections().filterNot { it.key == c.key } + c
        val editor = prefs.edit().putInt("conns_n", list.size)
        list.forEachIndexed { i, s ->
            editor.putString(
                "conn_$i", listOf(s.host, s.port.toString(), s.user, s.session)
                    .joinToString(CONN_SEP))
        }
        check(editor.commit()) { "Could not save the selected connection/session" }
        onUi { savedRevision++ }
    }

    fun forgetConnection(c: SavedConnection) {
        work {
            credentials.remove(c) // Do not claim to forget a credential if secure deletion failed.
            servers.remove(c.key)?.let { s ->
                s.attachmentEpoch++
                AgentNotif.cancelServer(ctx, s)
                try { s.bridge.disconnect() } catch (_: Exception) {}
                s.sessionPassword = null
            }
            drafts.keys.filter { it.connection == c.key }.forEach { drafts.remove(it) }
            recent.remove(c.key)
            val list = savedConnections().filterNot { it.key == c.key }
            val editor = prefs.edit().putInt("conns_n", list.size)
            list.forEachIndexed { i, s ->
                editor.putString(
                    "conn_$i", listOf(s.host, s.port.toString(), s.user, s.session)
                        .joinToString(CONN_SEP))
            }
            editor.remove("conn_${list.size}")
            editor.remove("color:${c.key}").remove(sumKey(c))
            check(editor.commit()) { "Could not forget connection" }
            onUi { savedRevision++ }
            if (activeKey == c.key) {
                val next = recent.lastOrNull()?.let { servers[it] }
                    ?: servers.values.firstOrNull()
                if (next != null) {
                    activeKey = next.conn.key
                    touchRecent(next.conn.key)
                    refreshSnapshot(next)
                    subscribe(next, active = true)
                    screen = Screen.Agent
                } else {
                    activeKey = null
                    screen = Screen.Connections
                }
            }
        }
    }

    // ---- lifecycle, trust prompts, and destination-scoped state ----

    init { reloadJournal() }

    private fun onUi(block: () -> Unit) {
        if (closed.get()) return
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else main.post { if (!closed.get()) block() }
    }

    private fun work(block: () -> Unit) {
        if (closed.get()) return
        try {
            bg.execute {
                if (!closed.get()) try { block() } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (e: Exception) { onUi { error = e.message ?: "Request failed" } }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }

    fun askHostKey(fingerprint: String): Boolean {
        val answer = ArrayBlockingQueue<Boolean>(1)
        check(hostKeyAnswer.compareAndSet(null, answer)) { "Another host verification is pending" }
        onUi { hostKeyPrompt = "$verifyingAddress\n\nServer key fingerprint:\n$fingerprint\n\nCompare it on the intended host before accepting. Older trust records require verification again." }
        return try { answer.poll(120, TimeUnit.SECONDS) == true } finally {
            hostKeyAnswer.compareAndSet(answer, null)
            onUi { hostKeyPrompt = null }
        }
    }

    fun answerHostKey(accept: Boolean) { hostKeyAnswer.get()?.offer(accept) }
    fun targetFor(paneId: String): CommandTarget? = active()?.let { CommandTarget(it.conn.key, it.currentSession, paneId) }
    fun draft(target: CommandTarget): String = drafts[target] ?: ""
    fun setDraft(target: CommandTarget, text: String) {
        if (text.length > BridgeLimits.TEXT) { error = "Message exceeds ${BridgeLimits.TEXT} characters"; return }
        if (text.isEmpty()) drafts.remove(target) else {
            if (target !in drafts && drafts.size >= 256) { error = "Too many open drafts"; return }
            drafts[target] = text
        }
    }

    private fun reloadJournal() {
        try {
            val rows = journal.records()
            onUi { pendingActions = rows }
        } catch (_: Exception) { onUi { error = "Pending action store is unreadable. Sending is blocked until it is recovered" } }
    }

    fun reviewPending() { pendingReview = pendingActions.firstOrNull() }
    fun resolveInspected(record: PendingMutation) = work {
        journal.resolve(record)
        reloadJournal()
        onUi { pendingReview = null }
    }

    private fun requireTarget(target: CommandTarget): ServerConn {
        val srv = servers[target.connection] ?: throw IOException("Reconnect to the command's original computer")
        check(srv.attached && srv.currentSession == target.session && srv.bridge.isConnected()) {
            "Reconnect to the command's original session before sending"
        }
        return srv
    }

    // ---- connection flow ----

    private var notificationJump: Triple<String, String, String>? = null
    private var verifyingAddress = ""

    fun connect(c: SavedConnection, password: String, onHostKey: (String) -> Boolean,
                rememberPassword: Boolean = true) {
        busy = "Connecting…"
        error = null
        work {
            try {
                val srv = connectNow(c, password, onHostKey, rememberPassword)
                val jump = notificationJump?.takeIf { it.first == c.key }
                if (jump != null) {
                    notificationJump = null
                    srv.pendingJump = jump.second to jump.third
                    attachNow(srv, c.session)
                } else if (srv.attached) {
                    activateNow(srv)
                } else onUi { screen = Screen.Sessions }
            } finally { onUi { busy = null } }
        }
    }

    private fun connectNow(c: SavedConnection, password: String, onHostKey: (String) -> Boolean,
                           rememberPassword: Boolean): ServerConn {
        val previousKey = activeKey
        val existing = servers[c.key]
        if (existing?.attached == true && existing.bridge.isConnected()) return existing
        check(existing != null || servers.size < MAX_LIVE) { "Already connected to $MAX_LIVE computers. Disconnect one first" }
        val srv = existing ?: ServerConn(c, hostsStore, colorFor(c)).also { servers[c.key] = it }
        try {
            srv.attachmentEpoch++
            srv.bridge.disconnect()
            srv.attached = false
            srv.sessionPassword = password
            srv.rememberPassword = rememberPassword
            verifyingAddress = "${c.user}@${c.host}:${c.port}"
            srv.bridge.connect(c.host, c.port, c.user, password, onHostKey = { fp ->
                onUi { busy = "Verify ${c.host}:${c.port}" }
                onHostKey(fp)
            })
            if (!srv.bridge.bridgePresent()) throw IOException("Authenticated, but the Herdr bridge is not installed")
            srv.bridge.bridgeVersion()
            srv.sessions = srv.bridge.sessions()
            srv.pickedSession = c.session.takeIf { wanted -> srv.sessions.any { it.name == wanted && it.reachable } }
                ?: srv.sessions.firstOrNull { it.reachable }?.name ?: ""
            if (rememberPassword) credentials.save(c, password)
            else if (credentials.available) credentials.remove(c)
            saveConnection(c, null)
            activeKey = c.key
            touchRecent(c.key)
            return srv
        } catch (e: Exception) {
            srv.bridge.disconnect()
            srv.sessionPassword = null
            srv.attached = false
            servers.remove(c.key)
            recent.remove(c.key)
            activeKey = previousKey?.takeIf { it in servers } ?: servers.keys.firstOrNull()
            throw e
        }
    }

    fun openConnection(c: SavedConnection, password: String?, onHostKey: (String) -> Boolean) {
        val srv = servers[c.key]
        if (srv?.attached == true && srv.bridge.isConnected()) activate(c.key)
        else if (password != null) connect(c, password, onHostKey)
        else screen = Screen.SshForm(c)
    }

    fun attach(session: String, target: ServerConn? = null) {
        val srv = target ?: active() ?: run { error = "No computer selected"; return }
        busy = "Attaching…"
        error = null
        work { try { attachNow(srv, session) } finally { onUi { busy = null } } }
    }

    private fun attachNow(srv: ServerConn, sessionName: String) {
        require(SshBridge.validSession(sessionName)) { "Select a running session" }
        if (!srv.bridge.isConnected()) {
            val password = srv.sessionPassword ?: passwordFor(srv.conn)
                ?: throw IOException("Enter your SSH password again")
            connectNow(srv.conn.copy(session = sessionName), password, { askHostKey(it) }, srv.rememberPassword)
        }
        AgentNotif.cancelServer(ctx, srv)
        srv.notified.clear()
        srv.lastStatus.clear()
        srv.attachmentEpoch++
        val epoch = srv.attachmentEpoch
        srv.attached = false
        srv.currentSession = sessionName
        srv.snapshot = null
        srv.paneText.clear()
        srv.paneRevision.clear()
        srv.historyLines.clear()
        srv.lastStatus.clear()
        srv.notified.clear()
        srv.currentPaneId = null
        srv.currentTabId = null
        val hello = CompletableFuture<HelloInfo>()
        val snapshot = CompletableFuture<SnapshotState>()
        srv.helloWaiter.set(hello)
        srv.snapshotWaiter.set(snapshot)
        srv.bridge.onFrame = { frame -> onUi {
            if (srv.attachmentEpoch == epoch && servers[srv.conn.key] === srv) {
                try { handleServerFrame(srv, frame) } catch (e: Exception) {
                    srv.helloWaiter.getAndSet(null)?.completeExceptionally(e)
                    srv.snapshotWaiter.getAndSet(null)?.completeExceptionally(e)
                    srv.attached = false
                    srv.connPhase = "protocol error"
                    error = "The bridge returned invalid data. Reconnect after checking the server"
                    work { srv.bridge.closeStdio() }
                }
            }
        } }
        srv.bridge.onDisconnect = {
            srv.helloWaiter.getAndSet(null)?.completeExceptionally(IOException("Bridge disconnected"))
            srv.snapshotWaiter.getAndSet(null)?.completeExceptionally(IOException("Bridge disconnected"))
            onUi { if (srv.attachmentEpoch == epoch) { srv.attached = false; srv.connPhase = "disconnected" } }
        }
        try {
            srv.bridge.openStdio(sessionName)
            srv.bridge.send("hello", sessionName, JSONObject().put("clientName", "herdr-on-the-go").put("clientVersion", "0.1.1"))
            srv.hello = hello.get(15, TimeUnit.SECONDS)
            sendSnapshot(srv)
            val snap = snapshot.get(15, TimeUnit.SECONDS)
            val jump = srv.pendingJump
            val pane = jump?.let { (paneId, tabId) -> snap.panes.firstOrNull { it.paneId == paneId && it.tabId == tabId } }
                ?: snap.panes.firstOrNull { it.paneId == snap.focusedPaneId } ?: snap.panes.firstOrNull()
            srv.currentPaneId = pane?.paneId
            srv.currentTabId = pane?.tabId ?: snap.tabs.firstOrNull()?.tabId
            srv.pendingJump = null
            if (jump != null && (pane?.paneId != jump.first || pane.tabId != jump.second)) {
                onUi { error = "The notification's pane no longer exists in this session" }
            }
            srv.conn = srv.conn.copy(session = sessionName)
            srv.pickedSession = sessionName
            saveConnection(srv.conn, null)
            srv.connPhase = "live"
            srv.attached = true
            activeKey = srv.conn.key
            touchRecent(srv.conn.key)
            subscribeNow(srv, true)
            recordSummary(srv)
            startWatcher()
            onUi { screen = Screen.Agent }
        } catch (e: Exception) {
            srv.attached = false
            srv.connPhase = "disconnected"
            srv.attachmentEpoch++
            srv.bridge.disconnect()
            srv.sessionPassword = null
            servers.remove(srv.conn.key)
            recent.remove(srv.conn.key)
            onUi {
                if (activeKey == srv.conn.key) {
                    activeKey = servers.keys.firstOrNull()
                    screen = if (active()?.attached == true) Screen.Agent else Screen.Connections
                }
            }
            throw e
        } finally {
            srv.helloWaiter.compareAndSet(hello, null)
            srv.snapshotWaiter.compareAndSet(snapshot, null)
        }
    }

    fun disconnect() {
        val srv = active() ?: run { screen = Screen.Connections; return }
        work {
            recordSummary(srv)
            srv.attachmentEpoch++
            srv.attached = false
            AgentNotif.cancelServer(ctx, srv)
            srv.bridge.disconnect()
            srv.sessionPassword = null
            servers.remove(srv.conn.key)
            recent.remove(srv.conn.key)
            val next = liveServers().lastOrNull()
            if (next != null) activateNow(next)
            else onUi { activeKey = null; screen = Screen.Connections }
        }
    }

    private fun activateNow(srv: ServerConn) {
        activeKey = srv.conn.key
        touchRecent(srv.conn.key)
        onUi { screen = if (srv.attached) Screen.Agent else Screen.Sessions }
        if (srv.attached) { sendSnapshot(srv); subscribeNow(srv, true) }
    }

    fun activate(key: String) {
        val srv = servers[key] ?: return
        activeKey = key
        screen = if (srv.attached) Screen.Agent else Screen.Sessions
        work { activateNow(srv) }
    }

    fun jumpTo(key: String, paneId: String, tabId: String, session: String? = null) {
        val srv = servers[key] ?: return
        val desiredSession = session ?: srv.currentSession
        if (srv.attached && srv.currentSession == desiredSession && srv.bridge.isConnected()) {
            val pane = srv.snapshot?.panes?.firstOrNull { it.paneId == paneId && it.tabId == tabId }
                ?: run { error = "The notification's pane no longer exists"; return }
            activeKey = key
            screen = Screen.Agent
            selectPane(pane.paneId, pane.tabId, srv)
        } else {
            srv.pendingJump = paneId to tabId
            attach(desiredSession, srv)
        }
    }

    fun deepLink(key: String, paneId: String, tabId: String, session: String? = null) {
        val srv = servers[key]
        if (srv != null) { jumpTo(key, paneId, tabId, session); return }
        val saved = savedConnections().firstOrNull { it.key == key } ?: return
        val desired = saved.copy(session = session ?: saved.session)
        if (!SshBridge.validSession(desired.session)) return
        notificationJump = Triple(key, paneId, tabId)
        val password = passwordFor(desired)
        if (password == null) screen = Screen.SshForm(desired)
        else connect(desired, password, { askHostKey(it) })
    }

    // ---- bounded protocol reads and mutation reservations ----

    private fun sendSnapshot(srv: ServerConn) {
        srv.bridge.send("snapshot.get", srv.currentSession, JSONObject())
        srv.lastSnapshotPoll = System.nanoTime()
    }

    fun refreshSnapshot(srv: ServerConn? = active()) { if (srv != null) work { sendSnapshot(srv) } }
    fun subscribe(srv: ServerConn? = active(), active: Boolean) { if (srv != null) work { subscribeNow(srv, active) } }

    private fun subscribeNow(srv: ServerConn, active: Boolean) {
        val pane = srv.currentPaneId ?: return
        if (srv.snapshot?.panes?.none { it.paneId == pane } != false) return
        // Only the visible pane needs terminal content; statuses come from snapshots.
        val payload = JSONObject().put("clientId", "android-${srv.conn.key.hashCode()}")
            .put("paneIds", org.json.JSONArray().put(pane)).put("active", active)
        if (active) payload.put("activePaneId", pane)
        val reply = srv.bridge.request("timeline.subscribe", srv.currentSession, payload, "ack", 15000)
        if (reply.type != "ack") throw IOException(reply.payload.optString("error", "Subscription rejected"))
    }

    fun selectPane(paneId: String, tabId: String, srv: ServerConn? = active()) {
        if (srv == null || srv.snapshot?.panes?.none { it.paneId == paneId && it.tabId == tabId } != false) return
        srv.currentPaneId = paneId
        srv.currentTabId = tabId
        AgentNotif.cancel(ctx, srv, paneId)
        srv.notified.remove(paneId)
        val target = CommandTarget(srv.conn.key, srv.currentSession, paneId)
        work { requireTarget(target); subscribeNow(srv, true); readPane(srv, target) }
    }

    private fun readPane(srv: ServerConn, target: CommandTarget) {
        val epoch = srv.attachmentEpoch
        val lines = (srv.historyLines[target.id] ?: 400).coerceIn(1, BridgeLimits.LINES)
        val response = srv.bridge.request("raw.read", target.session,
            JSONObject().put("paneId", target.id).put("lines", lines), "raw.read.result", 15000)
        if (response.type != "raw.read.result") throw IOException(response.payload.optString("error", "Read rejected"))
        require(response.payload.getString("paneId") == target.id) { "Read response target mismatch" }
        val text = response.payload.getString("text").takeLast(BridgeLimits.HISTORY_CHARS)
        val revision = response.payload.optLong("revision", 0)
        onUi {
            if (srv.attachmentEpoch == epoch && srv.currentSession == target.session) {
                srv.paneText[target.id] = text
                srv.paneRevision[target.id] = revision
                srv.connPhase = "live"
            }
        }
    }

    fun canLoadEarlier(paneId: String): Boolean = (active()?.historyLines?.get(paneId) ?: 400) < BridgeLimits.LINES
    fun loadEarlier(paneId: String, currentLines: Int) {
        val target = targetFor(paneId) ?: return
        work {
            val srv = requireTarget(target)
            try {
                onUi { busy = "Loading earlier…" }
                srv.historyLines[paneId] = ((srv.historyLines[paneId] ?: currentLines.coerceAtLeast(400)) + 1000)
                    .coerceAtMost(BridgeLimits.LINES)
                readPane(srv, target)
            } finally { onUi { busy = null } }
        }
    }

    private fun mutate(target: CommandTarget, type: String, payload: JSONObject, content: String): Boolean {
        val srv = requireTarget(target)
        val reservation = journal.reserve(target, type, content)
        reloadJournal()
        try {
            val reply = srv.bridge.request(type, target.session,
                payload.put("idempotencyKey", reservation.key), "command.result", 30000)
            val result = reply.payload
            if (result.opt("ok") == true && !result.optBoolean("uncertain") && !result.optBoolean("partial")) {
                journal.resolve(reservation)
                reloadJournal()
                return true
            }
            val reason = result.optString("error", "The server rejected the action")
            if (result.optBoolean("uncertain") || result.optBoolean("partial") || result.opt("ok") !is Boolean) {
                journal.uncertain(reservation, if (result.optBoolean("partial")) "partial" else "unknown", reason)
                reloadJournal()
                onUi { pendingReview = pendingActions.firstOrNull { it.key == reservation.key } ?: reservation }
            } else {
                journal.resolve(reservation)
                reloadJournal()
                onUi { error = reason }
            }
            return false
        } catch (e: Exception) {
            // The reservation was synced before the write. Never turn a lost reply into a fresh key.
            try { journal.uncertain(reservation, "unknown", "Connection interrupted or reply timed out") } catch (_: Exception) { }
            reloadJournal()
            onUi { pendingReview = pendingActions.firstOrNull { it.key == reservation.key } ?: reservation }
            if (e is InterruptedException) Thread.currentThread().interrupt()
            return false
        }
    }

    fun sendMessage(target: CommandTarget, text: String) {
        if (sending) return
        val wire = try { BridgeLimits.message(text) } catch (e: Exception) { error = e.message; return }
        sending = true
        work {
            try {
                if (mutate(target, "pane.sendText", JSONObject().put("paneId", target.id).put("text", wire), wire)) {
                    onUi { if (drafts[target] == text) drafts.remove(target) }
                    val srv = requireTarget(target)
                    readPane(srv, target)
                }
            } finally { onUi { sending = false } }
        }
    }

    fun closeSpace(spaceId: String) {
        val srv = active() ?: return
        val target = CommandTarget(srv.conn.key, srv.currentSession, spaceId)
        work {
            try {
                onUi { busy = "Closing space…" }
                if (mutate(target, "space.close", JSONObject().put("spaceId", spaceId), spaceId)) sendSnapshot(srv)
            } finally { onUi { busy = null } }
        }
    }

    fun attachText(uri: android.net.Uri, target: CommandTarget) {
        if (closed.get()) return
        fileIo.execute {
            try {
                val mime = ctx.contentResolver.getType(uri)
                require(mime == null || mime.startsWith("text/") || mime in setOf("application/json", "application/xml", "application/octet-stream")) {
                    "Choose a UTF-8 text file"
                }
                val content = ctx.contentResolver.openInputStream(uri)?.use { BridgeLimits.readText(it) }
                    ?: throw IOException("Could not open the text file")
                onUi {
                    val previous = draft(target)
                    val combined = if (previous.isEmpty()) content else "$previous\n$content"
                    try { BridgeLimits.message(combined); setDraft(target, combined) }
                    catch (e: Exception) { error = e.message ?: "Attachment is too large" }
                }
            } catch (e: Exception) { onUi { error = e.message ?: "Could not read attachment" } }
        }
    }

    // ---- stream frames: main-thread state, bounded handshake futures ----

    private fun handleServerFrame(srv: ServerConn, frame: Frame) {
        if (frame.sessionId != srv.currentSession) return
        when (frame.type) {
            "hello.result" -> srv.helloWaiter.getAndSet(null)?.complete(parseHello(frame.payload))
            "snapshot" -> {
                val snap = parseSnapshot(frame.payload.getJSONObject("snapshot"))
                srv.snapshot = snap
                val ids = snap.panes.map { it.paneId }.toSet()
                srv.paneText.keys.filter { it !in ids }.forEach { srv.paneText.remove(it) }
                srv.paneRevision.keys.filter { it !in ids }.forEach { srv.paneRevision.remove(it) }
                srv.historyLines.keys.retainAll(ids)
                if (srv.currentPaneId !in ids) {
                    val pane = snap.panes.firstOrNull { it.paneId == snap.focusedPaneId } ?: snap.panes.firstOrNull()
                    srv.currentPaneId = pane?.paneId
                    srv.currentTabId = pane?.tabId
                }
                checkFinished(srv, snap)
                srv.snapshotWaiter.getAndSet(null)?.complete(snap)
            }
            "timeline.batch", "timeline.event", "timeline.delta" -> storeEvent(srv, frame)
            "session.closed", "session.error" -> { srv.attached = false; srv.connPhase = "disconnected" }
        }
    }

    private fun checkFinished(srv: ServerConn, snap: SnapshotState) {
        val live = snap.panes.map { it.paneId }.toSet()
        snap.panes.forEach { pane ->
            val previous = srv.lastStatus.put(pane.paneId, pane.status)
            if (pane.status == "working") srv.notified.remove(pane.paneId)
            if (previous == "working" && pane.status in setOf("idle", "done") &&
                !(activeKey == srv.conn.key && pane.paneId == srv.currentPaneId && screen == Screen.Agent) && srv.notified.add(pane.paneId)) {
                val label = snap.tabs.firstOrNull { it.tabId == pane.tabId }?.label ?: pane.paneId
                AgentNotif.notify(ctx, srv, pane.paneId, pane.tabId, label, pane.status)
            }
        }
        srv.lastStatus.keys.retainAll(live)
        srv.notified.retainAll(live)
    }

    private fun storeEvent(srv: ServerConn, frame: Frame) {
        if (frame.type == "timeline.batch") {
            val events = frame.payload.optJSONArray("events") ?: return
            require(events.length() <= 1024) { "Too many timeline events" }
            for (i in 0 until events.length()) events.optJSONObject(i)?.let {
                storeEvent(srv, Frame(frame.id, "timeline.event", frame.sessionId, frame.seq, it))
            }
            return
        }
        val event = frame.payload.optJSONObject("event") ?: frame.payload
        val paneId = event.optString("paneId")
        if (srv.snapshot?.panes?.none { it.paneId == paneId } != false) return
        val old = srv.paneText[paneId] ?: ""
        when (event.optString("kind")) {
            "terminal" -> {
                val blocks = event.optJSONArray("blocks")
                val text = if (blocks == null) event.optString("text") else buildString {
                    for (i in 0 until blocks.length()) {
                        append(blocks.optJSONObject(i)?.optString("text") ?: "")
                        if (length > BridgeLimits.HISTORY_CHARS) delete(0, length - BridgeLimits.HISTORY_CHARS)
                    }
                }
                srv.paneText[paneId] = text.takeLast(BridgeLimits.HISTORY_CHARS)
            }
            "output", "message", "send", "tool" -> srv.paneText[paneId] =
                (old + (if (old.isEmpty()) "" else "\n") + event.optString("text")).takeLast(BridgeLimits.HISTORY_CHARS)
            "input" -> event.optString("text").let { if (!old.endsWith(it)) srv.paneText[paneId] = (old + it).takeLast(BridgeLimits.HISTORY_CHARS) }
            "revision" -> { srv.paneRevision[paneId] = event.optLong("revision"); if (event.has("text")) srv.paneText[paneId] = event.optString("text").takeLast(BridgeLimits.HISTORY_CHARS) }
            "clear" -> srv.paneText[paneId] = ""
        }
    }

    private val watch = Executors.newSingleThreadScheduledExecutor()
    private val watching = AtomicBoolean(false)
    private val pollQueued = AtomicBoolean(false)

    fun startWatcher() {
        if (!watching.compareAndSet(false, true)) return
        watch.scheduleWithFixedDelay({
            if (!closed.get() && pollQueued.compareAndSet(false, true)) work {
                try {
                    servers.values.toList().filter { it.attached && it.bridge.isConnected() }.forEach { srv ->
                        if (System.nanoTime() - srv.lastSnapshotPoll >= TimeUnit.SECONDS.toNanos(15)) {
                            try { sendSnapshot(srv) } catch (_: Exception) { }
                        }
                    }
                    active()?.takeIf { it.attached && it.bridge.isConnected() }?.let { srv ->
                        srv.currentPaneId?.let { pane ->
                            try { readPane(srv, CommandTarget(srv.conn.key, srv.currentSession, pane)) }
                            catch (_: Exception) { onUi { srv.connPhase = "stale" } }
                        }
                    }
                } finally { pollQueued.set(false) }
            }
        }, 2, 2, TimeUnit.SECONDS)
    }

    fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        hostKeyAnswer.getAndSet(null)?.offer(false)
        watch.shutdownNow()
        // Disconnect every transport before cancelling workers waiting for replies.
        servers.values.toList().forEach { srv ->
            srv.attachmentEpoch++
            srv.bridge.onFrame = null
            srv.bridge.onDisconnect = null
            srv.helloWaiter.getAndSet(null)?.completeExceptionally(IOException("App closed"))
            srv.snapshotWaiter.getAndSet(null)?.completeExceptionally(IOException("App closed"))
            srv.bridge.disconnect()
            srv.sessionPassword = null
        }
        servers.clear()
        attachmentTarget = null
        bg.shutdownNow()
        fileIo.shutdownNow()
        main.removeCallbacksAndMessages(null)
    }
}
