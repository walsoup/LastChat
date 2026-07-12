package me.rerere.rikkahub.ui.pages.setting

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.common.android.Logging
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.ToastType
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import java.io.BufferedReader
import java.io.InputStreamReader

enum class LogSource {
    APP_MEMORY,
    SYSTEM_LOGCAT
}

data class ParsedLog(
    val raw: String,
    val time: String = "",
    val level: String = "I",
    val tag: String = "App",
    val message: String
)

@Composable
fun SettingLogsPage() {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val haptics = rememberPremiumHaptics()
    val scope = rememberCoroutineScope()

    var selectedSource by remember { mutableStateOf(LogSource.SYSTEM_LOGCAT) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedLevelFilter by remember { mutableStateOf("ALL") }
    var isFilterMenuExpanded by remember { mutableStateOf(false) }

    val rawLogs = remember { mutableStateListOf<String>() }
    var isLoading by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    var logcatError by remember { mutableStateOf<String?>(null) }

    // Load logs function
    fun refreshLogs() {
        scope.launch {
            isLoading = true
            rawLogs.clear()
            logcatError = null
            withContext(Dispatchers.IO) {
                if (selectedSource == LogSource.APP_MEMORY) {
                    rawLogs.addAll(Logging.getRecentLogs())
                } else {
                    try {
                        val process = Runtime.getRuntime().exec("logcat -d -v time")
                        val list = mutableListOf<String>()
                        
                        val readerJob = launch {
                            val bufferedReader = BufferedReader(InputStreamReader(process.inputStream))
                            var line: String?
                            while (bufferedReader.readLine().also { line = it } != null) {
                                list.add(line!!)
                            }
                        }

                        val errList = mutableListOf<String>()
                        val errorJob = launch {
                            val errReader = BufferedReader(InputStreamReader(process.errorStream))
                            var line: String?
                            while (errReader.readLine().also { line = it } != null) {
                                errList.add(line!!)
                            }
                        }

                        process.waitFor()
                        readerJob.join()
                        errorJob.join()

                        if (process.exitValue() != 0) {
                            val errMsg = errList.joinToString("\n").ifBlank { "Exit code ${process.exitValue()}" }
                            withContext(Dispatchers.Main) {
                                logcatError = errMsg
                            }
                        } else if (list.isEmpty()) {
                            withContext(Dispatchers.Main) {
                                logcatError = "Logcat output was empty."
                            }
                        } else {
                            val trimmed = if (list.size > 1000) list.takeLast(1000) else list
                            rawLogs.addAll(trimmed.reversed())
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            logcatError = e.message ?: e.toString()
                        }
                    }
                }
            }
            isLoading = false
        }
    }

    LaunchedEffect(selectedSource) {
        refreshLogs()
    }

    // Helper to parse logcat lines
    // Example: 07-12 19:48:19.123 D/TagName(12345): Message text here
    val parsedLogs by remember(rawLogs, selectedSource) {
        derivedStateOf {
            rawLogs.map { line ->
                if (selectedSource == LogSource.SYSTEM_LOGCAT) {
                    try {
                        // Very basic logcat regex parser
                        val match = Regex("""^(\d{2}-\d{2}\s\d{2}:\d{2}:\d{2}\.\d{3})\s+([VDIWEF])/(.*?)\(\s*\d+\):(.*)$""").find(line)
                        if (match != null) {
                            ParsedLog(
                                raw = line,
                                time = match.groupValues[1],
                                level = match.groupValues[2],
                                tag = match.groupValues[3].trim(),
                                message = match.groupValues[4].trim()
                            )
                        } else {
                            // Fallback if regex fails
                            ParsedLog(raw = line, level = "I", tag = "System", message = line)
                        }
                    } catch (e: Exception) {
                        ParsedLog(raw = line, level = "I", tag = "System", message = line)
                    }
                } else {
                    // For App Memory logs formatted as "Tag: Message"
                    val parts = line.split(":", limit = 2)
                    if (parts.size == 2) {
                        ParsedLog(
                            raw = line,
                            level = "I",
                            tag = parts[0].trim(),
                            message = parts[1].trim()
                        )
                    } else {
                        ParsedLog(raw = line, level = "I", tag = "App", message = line)
                    }
                }
            }
        }
    }

    // Filter logs based on search and level
    val filteredLogs by remember(parsedLogs, searchQuery, selectedLevelFilter) {
        derivedStateOf {
            parsedLogs.filter { log ->
                val matchesSearch = searchQuery.isBlank() ||
                        log.tag.contains(searchQuery, ignoreCase = true) ||
                        log.message.contains(searchQuery, ignoreCase = true)

                val matchesLevel = selectedLevelFilter == "ALL" || log.level == selectedLevelFilter

                matchesSearch && matchesLevel
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("App Logs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            text = if (isLoading) "Loading..." else "${filteredLogs.size} logs showing",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = {
                        haptics.perform(HapticPattern.Pop)
                        refreshLogs()
                    }) {
                        Icon(Icons.Rounded.Refresh, "Refresh")
                    }

                    IconButton(onClick = {
                        haptics.perform(HapticPattern.Pop)
                        val text = filteredLogs.joinToString("\n") { it.raw }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("LastChat Logs", text)
                        clipboard.setPrimaryClip(clip)
                        toaster.show("Logs copied to clipboard", ToastType.Success)
                    }) {
                        Icon(Icons.Rounded.ContentCopy, "Copy All")
                    }

                    IconButton(onClick = {
                        haptics.perform(HapticPattern.Pop)
                        val text = filteredLogs.joinToString("\n") { it.raw }
                        val sendIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, text)
                            type = "text/plain"
                        }
                        val shareIntent = Intent.createChooser(sendIntent, "Share Logs")
                        context.startActivity(shareIntent)
                    }) {
                        Icon(Icons.Rounded.Share, "Share Logs")
                    }

                    if (selectedSource == LogSource.SYSTEM_LOGCAT) {
                        IconButton(onClick = {
                            haptics.perform(HapticPattern.Thud)
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    runCatching { Runtime.getRuntime().exec("logcat -c").waitFor() }
                                }
                                toaster.show("Logcat buffer cleared", ToastType.Success)
                                refreshLogs()
                            }
                        }) {
                            Icon(Icons.Rounded.Delete, "Clear Logcat")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            // Quick scroll-to-bottom button
            val showScrollButton by remember {
                derivedStateOf {
                    listState.firstVisibleItemIndex > 0
                }
            }
            AnimatedVisibility(
                visible = showScrollButton,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                FloatingActionButton(
                    onClick = {
                        haptics.perform(HapticPattern.Pop)
                        scope.launch {
                            listState.animateScrollToItem(0)
                        }
                    },
                    shape = CircleShape,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Icon(Icons.Rounded.ArrowDownward, contentDescription = "Scroll to top")
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Source Tabs
            TabRow(
                selectedTabIndex = selectedSource.ordinal,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = selectedSource == LogSource.SYSTEM_LOGCAT,
                    onClick = {
                        haptics.perform(HapticPattern.Tick)
                        selectedSource = LogSource.SYSTEM_LOGCAT
                    },
                    text = { Text("Logcat Logs") }
                )
                Tab(
                    selected = selectedSource == LogSource.APP_MEMORY,
                    onClick = {
                        haptics.perform(HapticPattern.Tick)
                        selectedSource = LogSource.APP_MEMORY
                    },
                    text = { Text("App Memory Logs") }
                )
            }

            // Filters Panel
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search logs (tag, content)...") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Rounded.Clear, null)
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                // Level Filter Button
                Box {
                    IconButton(
                        onClick = {
                            haptics.perform(HapticPattern.Pop)
                            isFilterMenuExpanded = true
                        },
                        modifier = Modifier
                            .background(
                                color = if (selectedLevelFilter != "ALL") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .size(56.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FilterList,
                            contentDescription = "Filter Levels",
                            tint = if (selectedLevelFilter != "ALL") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    DropdownMenu(
                        expanded = isFilterMenuExpanded,
                        onDismissRequest = { isFilterMenuExpanded = false }
                    ) {
                        listOf("ALL", "V", "D", "I", "W", "E", "F").forEach { lvl ->
                            val label = when (lvl) {
                                "ALL" -> "All Levels"
                                "V" -> "Verbose (V)"
                                "D" -> "Debug (D)"
                                "I" -> "Info (I)"
                                "W" -> "Warning (W)"
                                "E" -> "Error (E)"
                                "F" -> "Fatal (F)"
                                else -> lvl
                            }
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    haptics.perform(HapticPattern.Tick)
                                    selectedLevelFilter = lvl
                                    isFilterMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            // Quick Filters scroll
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("ALL", "D", "I", "W", "E").forEach { lvl ->
                    val label = when (lvl) {
                        "ALL" -> "All"
                        "D" -> "Debug"
                        "I" -> "Info"
                        "W" -> "Warning"
                        "E" -> "Error"
                        else -> lvl
                    }
                    FilterChip(
                        selected = selectedLevelFilter == lvl,
                        onClick = {
                            haptics.perform(HapticPattern.Tick)
                            selectedLevelFilter = lvl
                        },
                        label = { Text(label) }
                    )
                }
            }

            // Logs output
            Box(modifier = Modifier.weight(1f)) {
                if (logcatError != null && selectedSource == LogSource.SYSTEM_LOGCAT && filteredLogs.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("System Logcat Restricted", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                                Text("Android has restricted logcat read permissions for this app (common on newer OS versions).\n\nError details:\n$logcatError\n\nYou can still view \"App Memory Logs\" (above) which captures standard print outputs and Ktor/OkHttp logs.\n\nTo enable system logs, run via ADB:\nadb shell pm grant me.rerere.rikkahub android.permission.READ_LOGS", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                } else if (filteredLogs.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isLoading) "Loading logs..." else "No matching logs found",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    SelectionContainer {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(filteredLogs) { log ->
                                LogLineItem(log = log)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LogLineItem(log: ParsedLog) {
    val levelColor = when (log.level) {
        "V" -> Color(0xFF9E9E9E)
        "D" -> Color(0xFF2196F3)
        "I" -> Color(0xFF4CAF50)
        "W" -> Color(0xFFFF9800)
        "E" -> Color(0xFFF44336)
        "F" -> Color(0xFFB71C1C)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val levelBg = levelColor.copy(alpha = 0.12f)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(6.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Level Badge
                Box(
                    modifier = Modifier
                        .background(levelBg, shape = RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = log.level,
                        color = levelColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Tag
                Text(
                    text = log.tag,
                    color = levelColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )

                // Time
                if (log.time.isNotEmpty()) {
                    Text(
                        text = log.time,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Message text
            Text(
                text = log.message,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 16.sp,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
