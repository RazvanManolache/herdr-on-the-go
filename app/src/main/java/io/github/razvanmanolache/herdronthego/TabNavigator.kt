package io.github.razvanmanolache.herdronthego

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TabNavigator(
    tabs: List<TabDestination>, activeConnection: String?, activeSession: String,
    activeTab: String?, theme: AppTheme, onSelect: (TabDestination) -> Unit, onClose: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = tabs.filter { it.matches(query) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("All tabs") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                OutlinedTextField(query, { query = it }, singleLine = true,
                    label = { Text("Search all tabs") },
                    placeholder = { Text("Computer, space, or tab") },
                    modifier = Modifier.fillMaxWidth().testTag("tab-search"))
                Text("${filtered.size} of ${tabs.size} tabs · all connected computers",
                    color = theme.dim, fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 8.dp))
                if (filtered.isEmpty()) {
                    Text(if (tabs.isEmpty()) "No tabs on connected computers yet"
                        else "No matching tabs", color = theme.dim)
                }
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("tab-results")) {
                    itemsIndexed(filtered, key = { _, tab -> tab.identity }) { index, tab ->
                        val previous = filtered.getOrNull(index - 1)
                        if (previous?.connection != tab.connection || previous.session != tab.session) {
                            Text("${tab.computer} · ${tab.user} · ${tab.session}",
                                color = theme.accent, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                        }
                        if (previous?.connection != tab.connection || previous.session != tab.session ||
                            previous.workspaceId != tab.workspaceId) {
                            Text(tab.space, color = theme.dim, fontSize = 12.sp,
                                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
                        }
                        val selected = tab.connection == activeConnection && tab.session == activeSession &&
                            tab.tabId == activeTab
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(if (selected) theme.surface2 else theme.bg.copy(alpha = 0f))
                            .clickable { onSelect(tab) }.testTag("tab:${tab.identity}")
                            .heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape)
                                .background(statusColor(theme, tab.status)))
                            Spacer(Modifier.width(10.dp))
                            Text(tab.label, color = if (selected) theme.accent else theme.text,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f))
                            if (selected) Text(" ✓", color = theme.accent)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("CLOSE") } },
    )
}
