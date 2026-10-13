package io.github.razvanmanolache.herdronthego

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun HerdrOnTheGoApp(c: BridgeController) {
    // NOTE: Android 13+ needs a runtime ask for POST_NOTIFICATIONS —
    // the manifest entry alone never shows the system dialog.
    if (Build.VERSION.SDK_INT >= 33) {
        val ctx = LocalContext.current
        var asked by remember { mutableStateOf(false) }
        val askNotif = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()) { }
        LaunchedEffect(Unit) {
            if (!asked && ctx.checkSelfPermission(
                    Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
                asked = true
                askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    MaterialTheme(colorScheme = c.theme.colorScheme()) {
        Surface(Modifier.fillMaxSize(), color = c.theme.bg) {
            Box(Modifier.fillMaxSize()) {
                when (val s = c.screen) {
                    is Screen.Connections -> ConnectionsScreen(c)
                    is Screen.SshForm -> SshFormScreen(c, s.edit)
                    is Screen.Sessions -> SessionsScreen(c)
                    is Screen.Agent -> AgentScreen(c)
                }
                if (c.busy != null) {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = c.theme.accent)
                            Spacer(Modifier.height(8.dp))
                            Text(c.busy ?: "", color = c.theme.dim)
                        }
                    }
                }
                c.error?.let { msg ->
                    AlertDialog(
                        onDismissRequest = { c.error = null },
                        title = { Text("Action needs attention") },
                        text = { Text(msg) },
                        confirmButton = {
                            TextButton(onClick = { c.error = null }) { Text("DISMISS") }
                        })
                }
                c.hostKeyPrompt?.let { fp ->
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text("Unknown host key") },
                        text = { Text(fp) },
                        confirmButton = {
                            TextButton(onClick = { c.answerHostKey(true) }) { Text("ACCEPT") }
                        },
                        dismissButton = {
                            TextButton(onClick = { c.answerHostKey(false) }) { Text("REJECT") }
                        })
                }
                c.pendingReview?.let { action ->
                    AlertDialog(
                        onDismissRequest = { c.pendingReview = null },
                        title = { Text(if (action.state == "partial") "Action partially completed" else "Action outcome unknown") },
                        text = { Text("${action.target.connection}\nSession: ${action.target.session}\nTarget: ${action.target.id}\n\n${action.reason}\n\nInspect this terminal before continuing. Sending identical text checks the original request. Marking it inspected allows a new action to execute.") },
                        confirmButton = { TextButton(onClick = { c.pendingReview = null }) { Text("KEEP UNRESOLVED") } },
                        dismissButton = { TextButton(onClick = { c.resolveInspected(action) }) { Text("MARK INSPECTED") } })
                }
            }
        }
    }
}

// ---------- shared bits ----------

@Composable
fun ThemeButton(c: BridgeController) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true },
        contentPadding = PaddingValues(horizontal = 4.dp)) {
        Text(c.theme.name, color = c.theme.dim, fontSize = 13.sp)
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Color scheme") },
            text = {
                Column {
                    THEMES.forEach { t ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    c.setTheme(t.id)
                                    open = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(26.dp).clip(CircleShape)
                                .background(t.swatch))
                            Spacer(Modifier.width(12.dp))
                            Text(t.name, color = c.theme.text,
                                fontWeight = if (t.id == c.theme.id)
                                    FontWeight.Bold else FontWeight.Normal)
                            Spacer(Modifier.weight(1f))
                            if (t.id == c.theme.id)
                                Text("✓", color = c.theme.accent)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("CLOSE") } })
    }
}

@Composable
fun StatusPill(c: BridgeController, status: String) {
    val t = c.theme
    Box(Modifier.clip(RoundedCornerShape(20.dp))
        .background(statusColor(t, status).copy(alpha = 0.16f))
        .padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(status.replaceFirstChar { it.uppercase() },
            color = statusColor(t, status), fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun Avatar(c: BridgeController, label: String, selected: Boolean,
    size: Dp = 38.dp) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (selected) c.theme.accent
                else c.theme.accent.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center) {
        Text(initials(label),
            color = if (selected) c.theme.onAccent else c.theme.accent,
            fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

// ---------- connections ----------

@Composable
fun ConnectionsScreen(c: BridgeController) {
    val t = c.theme
    val list = remember(c.savedRevision) { c.savedConnections() }
    // NOTE: edge-to-edge (targetSdk 35) draws under the status and
    // gesture bars — pad both so header buttons stay tappable.
    Column(Modifier.fillMaxSize().statusBarsPadding()
        .navigationBarsPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Herdr on the Go", style = MaterialTheme.typography.headlineSmall,
                    color = t.text, fontWeight = FontWeight.Bold)
                Text("Secure mobile control center", color = t.dim)
            }
            ThemeButton(c)
        }
        Spacer(Modifier.height(12.dp))
        list.forEach { s ->
            Card(
                Modifier.fillMaxWidth().padding(vertical = 6.dp)
                    .clickable {
                        // NOTE: instant-switch when this computer is
                        // already live, full connect otherwise.
                        c.openConnection(s, c.passwordFor(s)) { c.askHostKey(it) }
                    },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = t.surface)) {
                Row(Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    // NOTE: a live computer wears its color ring so the
                    // list shows at a glance what is already connected.
                    Box(Modifier.clip(CircleShape)
                        .background(if (c.isLive(s)) c.colorFor(s)
                            else t.surface)
                        .padding(2.dp)) {
                        Avatar(c, s.host, false)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.hostLabel(s), color = t.text,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Text("${s.user} · " +
                            (c.summaryFor(s) ?: "session · ${s.session}"),
                            color = t.dim, fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = {
                        c.forgetConnection(s)
                    }) { Icon(Icons.Filled.Delete, "forget", tint = t.dim) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { c.screen = Screen.SshForm(null) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = t.accent, contentColor = t.onAccent),
            shape = RoundedCornerShape(12.dp)) {
            Text("+  New SSH connection", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        Text("Saved passwords use device secure storage · v0.1.1", color = t.dim)
        c.credentialNotice?.let { Text(it, color = t.warn) }
        if (c.pendingActions.isNotEmpty()) TextButton(onClick = { c.reviewPending() }) {
            Text("Review ${c.pendingActions.size} unresolved actions")
        }
    }
}

// ---------- ssh form ----------

@Composable
fun SshFormScreen(c: BridgeController, edit: SavedConnection?) {
    val t = c.theme
    var host by remember { mutableStateOf(edit?.host ?: "") }
    var port by remember { mutableStateOf(edit?.port?.toString() ?: "22") }
    var user by remember { mutableStateOf(edit?.user ?: "") }
    var pass by remember {
        mutableStateOf(if (edit != null) c.passwordFor(edit) ?: "" else "")
    }
    var rememberPassword by remember { mutableStateOf(c.canSavePasswords) }
    Column(Modifier.fillMaxSize().statusBarsPadding()
        .navigationBarsPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { c.screen = Screen.Connections }) {
                Icon(Icons.Filled.ArrowBack, "back", tint = t.accent)
            }
            Text("New SSH connection", color = t.text,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(host, { host = it }, label = { Text("Host") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(port, { port = it }, label = { Text("Port") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(user, { user = it }, label = { Text("Username") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(pass, { pass = it }, label = { Text("Password") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            visualTransformation = PasswordVisualTransformation())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = rememberPassword, onCheckedChange = { rememberPassword = it }, enabled = c.canSavePasswords)
            Text("Save password securely", color = t.text)
        }
        c.credentialNotice?.let { Text(it, color = t.warn) }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                val sc = SavedConnection(host.trim(),
                    port.toIntOrNull() ?: 22, user.trim(), edit?.session ?: "default")
                c.connect(sc, pass, { c.askHostKey(it) }, rememberPassword)
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = t.accent, contentColor = t.onAccent),
            enabled = host.isNotBlank() && user.isNotBlank() && port.toIntOrNull() in 1..65535 && c.busy == null) {
            Text("CONNECT", fontWeight = FontWeight.Bold)
        }
    }
}

// ---------- sessions ----------

@Composable
fun SessionsScreen(c: BridgeController) {
    val t = c.theme
    var picked by remember(c.activeKey, c.sessions) { mutableStateOf(c.pickedSession) }
    Column(Modifier.fillMaxSize().statusBarsPadding()
        .navigationBarsPadding().padding(16.dp)) {
        Text("Select a Herdr session", color = t.text,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold)
        Text("Attaching to this session:", color = t.dim)
        Spacer(Modifier.height(8.dp))
        c.sessions.forEach { s ->
            Card(
                Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .clickable { picked = s.name },
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (s.name == picked) t.surface2 else t.surface)) {
                Row(Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Avatar(c, s.name, s.name == picked)
                    Spacer(Modifier.width(12.dp))
                    Text(s.name, color = t.text, modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold)
                    if (s.name == picked) Text("✓", color = t.accent)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = { c.attach(picked) }, modifier = Modifier.fillMaxWidth(),
            enabled = c.sessions.any { it.name == picked && it.reachable } && c.busy == null,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = t.accent, contentColor = t.onAccent)) {
            Text("CONNECT", fontWeight = FontWeight.Bold)
        }
    }
}

// ---------- agent view ----------

@Composable
fun AgentScreen(c: BridgeController) {
    val t = c.theme
    val snap = c.snapshot
    var showSpaces by remember { mutableStateOf(false) }
    var showServers by remember { mutableStateOf(false) }
    var showCommands by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var tabJump by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }

    val tabList = snap?.tabs ?: emptyList()
    val currentTab = tabList.firstOrNull { it.tabId == c.currentTabId }
        ?: tabList.firstOrNull()
    val currentPane = snap?.panes?.firstOrNull { it.paneId == c.currentPaneId }
        ?: snap?.panes?.firstOrNull { it.tabId == currentTab?.tabId }
    val paneId = currentPane?.paneId ?: ""
    val target = c.targetFor(paneId)

    // NOTE: status-bar pad keeps the header row out from under the
    // system bar (untappable there on edge-to-edge, targetSdk 35).
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            // NOTE: the colored dot names the live computer behind this
            // view — tap it to switch computers without leaving the app.
            TextButton(onClick = { showServers = true }, modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape)
                    .background(c.activeColor()))
                Spacer(Modifier.width(6.dp))
                Text(c.activeHost().ifBlank { "…" }, color = t.text,
                    fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = { showSpaces = true }, modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text(snap?.workspaces?.firstOrNull { it.focused }?.label
                    ?: "…", color = t.text, fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(" ▾", color = t.text, fontSize = 15.sp)
            }
            IconButton(onClick = { confirmDisconnect = true },
                modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Close, "close", tint = t.dim)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            ThemeButton(c)
            TextButton(onClick = { c.rawMode = !c.rawMode },
                contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text(if (c.rawMode) "Raw ✓" else "Raw", color = t.dim,
                    fontSize = 13.sp)
            }
            Spacer(Modifier.weight(1f))
            Text("${snap?.agents?.size ?: 0} agents", color = t.dim, maxLines = 1,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 4.dp))
        }
        // tab chips with status dots, end fade, and a jump count
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp)) {
            tabList.forEach { tab ->
                val sel = tab.tabId == currentTab?.tabId
                val status = snap?.panes?.firstOrNull { it.tabId == tab.tabId }?.status
                    ?: tabList.firstOrNull { it.tabId == tab.tabId }
                        ?.let { snap?.agents?.firstOrNull { a -> a.tabId == it.tabId }?.status }
                    ?: "unknown"
                Row(
                    Modifier.padding(end = 2.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (sel) t.accent else t.surface)
                        .clickable {
                            val pane = snap?.panes?.firstOrNull { it.tabId == tab.tabId }
                                ?: return@clickable
                            c.selectPane(pane.paneId, tab.tabId)
                        }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape)
                        .background(statusColor(t, status)))
                    Spacer(Modifier.width(6.dp))
                    Text(tab.label, fontSize = 13.sp,
                        color = if (sel) t.onAccent else t.dim,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                }
            }
            }
            Box(Modifier.align(Alignment.CenterEnd).width(28.dp).height(34.dp)
                .background(Brush.horizontalGradient(
                    listOf(t.bg.copy(alpha = 0f), t.bg))))
            }
            TextButton(onClick = { tabJump = true },
                contentPadding = PaddingValues(horizontal = 4.dp)) {
                // NOTE: unified count across every live computer, like
                // the jump dialog it opens — not just this server.
                val jumpCount = c.liveServers()
                    .sumOf { it.snapshot?.tabs?.size ?: 0 }
                Text("▾$jumpCount", color = t.dim, fontSize = 13.sp)
            }
        }
        if (currentPane != null && target != null) {
            ChatDetail(c, currentTab?.label ?: paneId, currentPane, paneId,
                target,
                searchOpen, { searchOpen = it },
                c.draft(target), { c.setDraft(target, it) }, { showCommands = true },
                modifier = Modifier.weight(1f))
        } else {
            Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                Text("No panes yet", color = t.dim)
            }
        }
    }

    if (showSpaces) SpacesDialog(c) { showSpaces = false }
    if (showServers) ServersDialog(c) { showServers = false }
    if (showCommands && currentPane != null) {
        CommandsSheet(c, paneId) { showCommands = false }
    }
    if (tabJump) JumpDialog(c) { tabJump = false }
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Disconnect") },
            text = { Text("Disconnect from this session?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisconnect = false
                    c.disconnect()
                }) { Text("Disconnect") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisconnect = false }) {
                    Text("Stay")
                }
            })
    }
}

@Composable
fun ChatDetail(
    c: BridgeController,
    title: String,
    pane: PaneEntry,
    paneId: String,
    target: CommandTarget,
    searchOpen: Boolean,
    onSearch: (Boolean) -> Unit,
    input: String,
    onInput: (String) -> Unit,
    onCommands: () -> Unit,
    // NOTE: callers pass weight(1f) so the chat takes exactly the
    // remaining space — an unweighted fillMaxSize overflows the screen
    // and pushes the input row under the gesture bar (untappable).
    modifier: Modifier = Modifier,
) {
    val t = c.theme
    var query by remember(target) { mutableStateOf("") }
    var attachOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    val text = c.paneText[paneId] ?: ""
    val lines = remember(text) { text.split("\n") }
    val matches = remember(query, lines) {
        if (query.isBlank()) emptyList()
        else lines.mapIndexedNotNull { i, l ->
            if (l.contains(query, ignoreCase = true)) i else null
        }
    }
    var matchPos by remember(query, target) { mutableStateOf(0) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(matchPos, matches) {
        matches.getOrNull(matchPos)?.let { listState.scrollToItem(it) }
    }
    LaunchedEffect(target) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }

    // document picker: picked text is inserted into the message box
    // (the bridge protocol defines no upload channel, so there is no
    // photo option — only text files).
    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            c.attachmentTarget?.let { c.attachText(uri, it) }
        }
        c.attachmentTarget = null
    }

    Column(modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = t.text,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text("${c.active()?.connPhase ?: "disconnected"} · ${c.activeHost()}" +
                    if (c.rawMode) " · Raw · rev ${c.paneRevision[paneId] ?: pane.revision}" else "",
                    color = t.dim, fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            StatusPill(c, pane.status)
        }
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth().height(32.dp)) {
            IconButton(onClick = { c.setFont(c.fontSp - 2) },
                modifier = Modifier.size(32.dp)) {
                Text("A-", color = t.dim, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold)
            }
            Text("${c.fontSp.toInt()}", color = t.dim, fontSize = 12.sp)
            IconButton(onClick = { c.setFont(c.fontSp + 2) },
                modifier = Modifier.size(32.dp)) {
                Text("A+", color = t.dim, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold)
            }
            IconButton(onClick = { onSearch(!searchOpen) },
                modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Search, "search", tint = t.dim)
            }
        }
        if (searchOpen) {
            Card(Modifier.fillMaxWidth().padding(bottom = 4.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = t.surface)) {
                Row(Modifier.padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it; matchPos = 0 },
                        label = { Text("Find in chat") },
                        modifier = Modifier.weight(1f), singleLine = true)
                    IconButton(onClick = {
                        matchPos = (matchPos - 1 + matches.size) % matches.size
                    }, enabled = matches.isNotEmpty()) {
                        Icon(Icons.Filled.KeyboardArrowUp, "prev", tint = t.accent)
                    }
                    IconButton(onClick = {
                        matchPos = (matchPos + 1) % matches.size
                    }, enabled = matches.isNotEmpty()) {
                        Icon(Icons.Filled.KeyboardArrowDown, "next", tint = t.accent)
                    }
                    Text(if (matches.isEmpty()) {
                        if (query.isNotBlank()) "No matches" else "0/0"
                    } else "${matchPos + 1}/${matches.size}", color = t.dim,
                        fontSize = 12.sp)
                }
            }
        }
        if (text.isBlank()) {
            Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No messages yet", color = t.text)
                    TextButton(enabled = c.canLoadEarlier(paneId), onClick = {
                        c.loadEarlier(paneId, lines.size)
                    }) { Text("↑ Load earlier", color = t.accent) }
                }
            }
        } else {
            val matchSet = matches.toSet()
            // NOTE: weight sits directly on the LazyColumn (a direct
            // child of this Column). A wrapping container lets the list
            // size to all lines and shoves the input row off screen;
            // per-message selection keeps text selectable instead.
            LazyColumn(state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(lines) { i, line ->
                    SelectionContainer {
                        val ann = buildAnnotatedString {
                            if (i in matchSet && query.isNotBlank()) {
                                var start = 0
                                while (true) {
                                    val hit = line.indexOf(query, start, ignoreCase = true)
                                    if (hit < 0) {
                                        append(line.substring(start))
                                        break
                                    }
                                    append(line.substring(start, hit))
                                    withStyle(SpanStyle(background = t.matchBg,
                                        color = t.matchFg,
                                        fontWeight = FontWeight.Bold)) {
                                        append(line.substring(hit, hit + query.length))
                                    }
                                    start = hit + query.length
                                }
                            } else append(line)
                        }
                        Text(ann, color = t.text,
                            fontFamily = FontFamily.Monospace,
                            fontSize = c.fontSp.sp,
                            lineHeight = (c.fontSp * 1.25f).sp,
                            modifier = Modifier.fillMaxWidth()
                                .background(if (i == matches.getOrNull(matchPos))
                                    t.trackBg else t.bg)
                                .padding(horizontal = 4.dp))
                        }
                    }
                }
            TextButton(enabled = c.canLoadEarlier(paneId), onClick = { c.loadEarlier(paneId, lines.size) }) {
                Text(if (c.canLoadEarlier(paneId)) "↑ Load earlier" else "History limit: 2,000 lines", color = t.accent)
            }
        }
        // input row: nav-bar + keyboard pads keep the message box
        // and send button above the system bars (reachable taps).
        Row(Modifier.navigationBarsPadding().imePadding(),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { moreOpen = true },
                contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text("⋯", color = t.dim, fontSize = 16.sp)
            }
            OutlinedTextField(input, onInput,
                label = { Text("Message the agent…") },
                modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                shape = RoundedCornerShape(12.dp),
                // NOTE: the box grows to five lines by itself, so the
                // old expand arrow is gone.
                singleLine = false, maxLines = 5)
            IconButton(
                onClick = {
                    if (input.isNotBlank() && !c.sending) {
                        c.sendMessage(target, input)
                    }
                },
                enabled = !c.sending) {
                if (c.sending) CircularProgressIndicator(
                    modifier = Modifier.size(22.dp), color = t.accent)
                else Icon(Icons.Filled.Send, "send", tint = t.accent)
            }
        }
        if (c.busy != null) Text(c.busy ?: "", color = t.dim)
    }

    if (moreOpen) {
        AlertDialog(
            onDismissRequest = { moreOpen = false },
            title = { Text("Message options") },
            text = {
                Column {
                    TextButton(onClick = {
                        moreOpen = false
                        attachOpen = true
                    }) { Text("▤  Attach a file", color = t.text) }
                    TextButton(onClick = {
                        moreOpen = false
                        onCommands()
                    }) { Text("⌘  Commands", color = t.text) }
                }
            },
            confirmButton = {
                TextButton(onClick = { moreOpen = false }) { Text("CANCEL") }
            })
    }
    if (attachOpen) {
        AlertDialog(
            onDismissRequest = { attachOpen = false },
            title = { Text("Attach a file") },
            text = {
                Column {
                    // NOTE: the bridge protocol defines no upload channel,
                    // so files are inserted as bounded UTF-8 message text.
                    TextButton(onClick = {
                        attachOpen = false
                        c.attachmentTarget = target
                        pickFile.launch("text/*")
                    }) { Text("▤  File", color = t.text) }
                    Text("UTF-8 text is inserted into the message · 12,000 characters including Enter",
                        color = t.dim, fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton(onClick = { attachOpen = false }) { Text("CANCEL") } })
    }
}

// ---------- spaces (+ long-press close) ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpacesDialog(c: BridgeController, onClose: () -> Unit) {
    val t = c.theme
    var confirm: WorkspaceEntry? by remember { mutableStateOf(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Spaces") },
        text = {
            Column {
                Text("Long-press a row to close that Space", color = t.dim)
                (c.snapshot?.workspaces ?: emptyList()).forEach { w ->
                    val agents = c.snapshot?.agents?.count {
                        it.workspaceId == w.workspaceId
                    } ?: 0
                    Row(
                        Modifier.fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    val tab = c.snapshot?.tabs?.firstOrNull {
                                        it.tabId == w.activeTabId
                                    } ?: c.snapshot?.tabs?.firstOrNull {
                                        it.workspaceId == w.workspaceId
                                    }
                                    if (tab != null) {
                                        val pane = c.snapshot?.panes?.firstOrNull {
                                            it.tabId == tab.tabId
                                        }
                                        if (pane != null) c.selectPane(pane.paneId, tab.tabId)
                                    }
                                    onClose()
                                },
                                onLongClick = { confirm = w })
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Avatar(c, w.label, w.focused, size = 28.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(w.label, color = t.text, fontWeight = FontWeight.SemiBold)
                            Text("$agents agents · ${w.tabCount} tabs", color = t.dim,
                                fontSize = 13.sp)
                        }
                        StatusPill(c, w.agentStatus)
                        IconButton(onClick = { confirm = w },
                            modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Close, "close space", tint = t.dim)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("CLOSE") } })

    confirm?.let { w ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Close Space") },
            text = { Text("This closes every tab and agent under this " +
                "Space on the server. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    c.closeSpace(w.workspaceId)
                    confirm = null
                    onClose()
                }) { Text("Close Space") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text("Keep it open") }
            })
    }
}

// ---------- commands palette ----------

@Composable
fun CommandsSheet(c: BridgeController, paneId: String, onClose: () -> Unit) {
    val target = c.targetFor(paneId) ?: return
    val t = c.theme
    val text = c.paneText[paneId] ?: ""
    val commands = remember(text) {
        text.split("\n").map { it.trim() }
            .filter { it.startsWith("❯") || it.startsWith(">") }
            .map { it.trimStart('❯', '>', ' ').trim() }
            .filter { it.isNotEmpty() }.distinct().takeLast(20).reversed()
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Commands") },
        text = {
            Column {
                Text("Read live from the agent — tap one to run it", color = t.dim)
                if (commands.isEmpty()) Text("Reading commands from the terminal…", color = t.dim)
                commands.forEach { cmd ->
                    TextButton(onClick = {
                        c.sendMessage(target, cmd)
                        onClose()
                    }) { Text(cmd, color = t.text) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("×") } })
}

// ---------- computers (multi-server switcher) ----------

@Composable
fun ServersDialog(c: BridgeController, onClose: () -> Unit) {
    val t = c.theme
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Computers") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Live — tap to switch, no reconnect needed",
                    color = t.dim, fontSize = 12.sp)
                c.liveServers().forEach { srv ->
                    val snap = srv.snapshot
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                c.activate(srv.conn.key)
                                onClose()
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape)
                            .background(srv.color))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.hostLabel(srv.conn), color = t.text,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text("${snap?.agents?.size ?: 0} agents · " +
                                (snap?.workspaces?.firstOrNull { it.focused }?.label
                                    ?: "…"),
                                color = t.dim, fontSize = 12.sp, maxLines = 1)
                        }
                        if (c.isActive(srv.conn.key))
                            Text("✓", color = t.accent,
                                fontWeight = FontWeight.Bold)
                    }
                }
                val idle = c.savedConnections().filterNot { c.isLive(it) }
                if (idle.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Saved — tap to connect", color = t.dim,
                        fontSize = 12.sp)
                    idle.forEach { s ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    c.openConnection(s, c.passwordFor(s)) {
                                        c.askHostKey(it)
                                    }
                                    onClose()
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape)
                                .background(c.colorFor(s).copy(alpha = 0.35f)))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.hostLabel(s), color = t.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                Text(c.summaryFor(s)
                                    ?: "session · ${s.session}",
                                    color = t.dim, fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Up to $MAX_LIVE computers live at once",
                    color = t.dim, fontSize = 12.sp)
                if (c.pendingActions.isNotEmpty()) TextButton(onClick = { onClose(); c.reviewPending() }) {
                    Text("Review ${c.pendingActions.size} unresolved actions")
                }
            }
        },
        confirmButton = {
            // NOTE: adding a computer must not cost a disconnect — the
            // form returns here and every live world keeps running.
            Row {
                TextButton(onClick = {
                    c.screen = Screen.SshForm(null)
                    onClose()
                }) { Text("+ NEW") }
                TextButton(onClick = onClose) { Text("CLOSE") }
            }
        })
}

// ---------- unified jump (every tab on every live computer) ----------

@Composable
fun JumpDialog(c: BridgeController, onClose: () -> Unit) {
    val t = c.theme
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Jump to tab") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                c.liveServers().forEach { srv ->
                    val snap = srv.snapshot ?: return@forEach
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape)
                            .background(srv.color))
                        Spacer(Modifier.width(8.dp))
                        Text(c.hostLabel(srv.conn), color = t.dim,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    snap.tabs.forEach { tab ->
                        val status = snap.panes
                            .firstOrNull { it.tabId == tab.tabId }?.status
                            ?: snap.agents
                                .firstOrNull { a -> a.tabId == tab.tabId }?.status
                            ?: "unknown"
                        val sel = c.isActive(srv.conn.key) &&
                            tab.tabId == srv.currentTabId
                        TextButton(onClick = {
                            val pane = snap.panes
                                .firstOrNull { it.tabId == tab.tabId }
                                ?: return@TextButton
                            c.jumpTo(srv.conn.key, pane.paneId, tab.tabId)
                            onClose()
                        }) {
                            Box(Modifier.size(8.dp).clip(CircleShape)
                                .background(statusColor(t, status)))
                            Spacer(Modifier.width(8.dp))
                            Text(tab.label,
                                color = if (sel) t.accent else t.text,
                                fontWeight = if (sel) FontWeight.Bold
                                else FontWeight.Normal)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("CLOSE") } })
}

@Composable
fun RefreshButton(c: BridgeController) {
    IconButton(onClick = {
        c.refreshSnapshot()
        c.subscribe(active = true)
    }) { Icon(Icons.Filled.Refresh, "refresh", tint = c.theme.dim) }
}

@Composable
fun BackButton(c: BridgeController, onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.Filled.ArrowBack, "back", tint = c.theme.accent)
    }
}
