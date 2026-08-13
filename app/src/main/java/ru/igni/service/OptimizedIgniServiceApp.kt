package ru.igni.service

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

private const val OPT_COAL_INTERVAL = 22 * 60 * 1000L
private const val OPT_ELECTRONIC_INTERVAL = 40 * 60 * 1000L
private const val OPT_MAX_COALS = 4
private const val OPT_PREFS = "igni_service_state"
private const val SOON_WINDOW = 5 * 60 * 1000L

private data class OptHookah(
    val type: String,
    val createdAt: Long,
    val deliveredAt: Long? = null,
    val lastCoalAt: Long? = null,
    val coalChanges: Int = 0,
    val counted: Boolean = false
)

private data class OptTableSession(val hookahs: List<OptHookah>)
private data class OptTable(val physical: Int, val zone: String)
private enum class OptShape { ROUND, RECT, WIDE }
private data class OptSpec(
    val physical: Int,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val shape: OptShape,
    val rotation: Float = 0f
)

private data class ServiceTask(
    val physical: Int,
    val hookahIndex: Int,
    val title: String,
    val subtitle: String,
    val priority: Int,
    val remainingMs: Long,
    val action: TaskAction
)
private enum class TaskAction { DELIVER, COAL, ELECTRONIC_DUE }

private val optTables = buildList {
    add(OptTable(77, "Основной зал"))
    (101..114).forEach { add(OptTable(it, "Основной зал")) }
    (301..308).forEach { add(OptTable(it, "Летняя веранда")) }
}

private val optMainSpecs = listOf(
    OptSpec(77, .055f, .775f, .16f, .17f, OptShape.WIDE),
    OptSpec(101, .27f, .08f, .095f, .14f, OptShape.ROUND),
    OptSpec(102, .46f, .06f, .075f, .20f, OptShape.RECT),
    OptSpec(103, .62f, .06f, .075f, .20f, OptShape.RECT),
    OptSpec(104, .79f, .06f, .075f, .20f, OptShape.RECT),
    OptSpec(105, .39f, .34f, .075f, .22f, OptShape.RECT),
    OptSpec(106, .55f, .34f, .075f, .22f, OptShape.RECT),
    OptSpec(107, .72f, .34f, .075f, .22f, OptShape.RECT),
    OptSpec(108, .38f, .64f, .075f, .21f, OptShape.RECT),
    OptSpec(109, .53f, .68f, .16f, .12f, OptShape.WIDE),
    OptSpec(110, .76f, .65f, .075f, .21f, OptShape.RECT),
    OptSpec(111, .25f, .58f, .075f, .12f, OptShape.ROUND),
    OptSpec(112, .20f, .47f, .075f, .12f, OptShape.ROUND),
    OptSpec(113, .04f, .39f, .14f, .12f, OptShape.WIDE),
    OptSpec(114, .04f, .59f, .14f, .12f, OptShape.WIDE)
)

private val optVerandaSpecs = listOf(
    OptSpec(301, .085f, .20f, .16f, .16f, OptShape.WIDE, -42f),
    OptSpec(302, .31f, .16f, .11f, .13f, OptShape.ROUND),
    OptSpec(303, .49f, .16f, .11f, .13f, OptShape.ROUND),
    OptSpec(304, .67f, .16f, .11f, .13f, OptShape.ROUND),
    OptSpec(305, .83f, .16f, .11f, .13f, OptShape.ROUND),
    OptSpec(306, .32f, .62f, .09f, .23f, OptShape.RECT),
    OptSpec(307, .53f, .60f, .09f, .23f, OptShape.RECT),
    OptSpec(308, .75f, .61f, .09f, .23f, OptShape.RECT)
)

private class OptimizedState(context: Context) {
    private val prefs = context.getSharedPreferences(OPT_PREFS, Context.MODE_PRIVATE)
    val sessions: SnapshotStateMap<Int, OptTableSession> = mutableStateMapOf()
    var shiftStartedAt by mutableLongStateOf(prefs.getLong("shiftStartedAt", 0L))
    var shiftHookahs by mutableIntStateOf(prefs.getInt("shiftHookahs", 0))
    var tableConfigVersion by mutableIntStateOf(0)

    init { restore() }

    fun displayNumber(physical: Int): Int = prefs.getInt("table_$physical", physical)

    fun openShift() {
        shiftStartedAt = System.currentTimeMillis()
        shiftHookahs = 0
        saveShift()
    }

    fun closeShift() {
        shiftStartedAt = 0L
        shiftHookahs = 0
        saveShift()
    }

    fun openTable(physical: Int, type: String) {
        sessions[physical] = OptTableSession(listOf(OptHookah(type, System.currentTimeMillis())))
        saveSessions()
    }

    fun addHookah(physical: Int, type: String) {
        val current = sessions[physical] ?: return
        sessions[physical] = current.copy(hookahs = current.hookahs + OptHookah(type, System.currentTimeMillis()))
        saveSessions()
    }

    fun deliver(physical: Int, index: Int) {
        val current = sessions[physical] ?: return
        val old = current.hookahs.getOrNull(index) ?: return
        if (old.deliveredAt != null) return
        val now = System.currentTimeMillis()
        sessions[physical] = current.copy(hookahs = current.hookahs.mapIndexed { i, h ->
            if (i == index) h.copy(deliveredAt = now, lastCoalAt = now, counted = true) else h
        })
        if (!old.counted) {
            shiftHookahs++
            saveShift()
        }
        saveSessions()
    }

    fun changeCoal(physical: Int, index: Int) {
        val current = sessions[physical] ?: return
        val old = current.hookahs.getOrNull(index) ?: return
        if (old.type == "Электронная" || old.deliveredAt == null || old.coalChanges >= OPT_MAX_COALS) return
        sessions[physical] = current.copy(hookahs = current.hookahs.mapIndexed { i, h ->
            if (i == index) h.copy(lastCoalAt = System.currentTimeMillis(), coalChanges = h.coalChanges + 1) else h
        })
        saveSessions()
    }

    fun removeHookah(physical: Int, index: Int) {
        val current = sessions[physical] ?: return
        val next = current.hookahs.filterIndexed { i, _ -> i != index }
        if (next.isEmpty()) sessions.remove(physical) else sessions[physical] = current.copy(hookahs = next)
        saveSessions()
    }

    fun closeTable(physical: Int) {
        sessions.remove(physical)
        saveSessions()
    }

    private fun saveShift() {
        prefs.edit().putLong("shiftStartedAt", shiftStartedAt).putInt("shiftHookahs", shiftHookahs).apply()
    }

    private fun saveSessions() {
        val value = sessions.entries.joinToString(";;") { (table, session) ->
            val hookahs = session.hookahs.joinToString("|") { h ->
                listOf(h.type, h.createdAt, h.deliveredAt ?: 0L, h.lastCoalAt ?: 0L, h.coalChanges, if (h.counted) 1 else 0).joinToString(",")
            }
            "$table#$hookahs"
        }
        prefs.edit().putString("sessions", value).apply()
    }

    private fun restore() {
        val raw = prefs.getString("sessions", "").orEmpty()
        if (raw.isBlank()) return
        raw.split(";;").forEach { block ->
            val parts = block.split("#", limit = 2)
            val table = parts.getOrNull(0)?.toIntOrNull() ?: return@forEach
            val hookahs = parts.getOrNull(1).orEmpty().split("|").mapNotNull { item ->
                val f = item.split(",")
                if (f.size < 6) return@mapNotNull null
                OptHookah(
                    type = f[0],
                    createdAt = f[1].toLongOrNull() ?: return@mapNotNull null,
                    deliveredAt = f[2].toLongOrNull()?.takeIf { it > 0 },
                    lastCoalAt = f[3].toLongOrNull()?.takeIf { it > 0 },
                    coalChanges = f[4].toIntOrNull()?.coerceIn(0, OPT_MAX_COALS) ?: 0,
                    counted = f[5] == "1"
                )
            }
            if (hookahs.isNotEmpty()) sessions[table] = OptTableSession(hookahs)
        }
    }
}

@Composable
fun OptimizedIgniServiceApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state = remember { OptimizedState(context.applicationContext) }
    val scheme = darkColorScheme(
        primary = Color(0xFFE1B96D),
        background = Color(0xFF0F100F),
        surface = Color(0xFF181815),
        surfaceVariant = Color(0xFF24231F),
        onBackground = Color(0xFFF2EEE5),
        onSurface = Color(0xFFF2EEE5)
    )
    MaterialTheme(colorScheme = scheme) { OptimizedServiceScreen(state) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptimizedServiceScreen(state: OptimizedState) {
    var zone by remember { mutableStateOf("Основной зал") }
    var selected by remember { mutableStateOf<Int?>(null) }
    var freeDialog by remember { mutableStateOf<Int?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val alerted = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    LaunchedEffect(now, state.sessions.size) {
        tasksFor(state, now).filter { it.priority == 0 }.forEach { task ->
            val h = state.sessions[task.physical]?.hookahs?.getOrNull(task.hookahIndex)
            val key = "${task.physical}:${task.hookahIndex}:${h?.coalChanges}:${h?.lastCoalAt}:${h?.deliveredAt}"
            if (alerted[key] != true) {
                alerted[key] = true
                optimizedAlert()
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = {
                Column {
                    Text("IGNI SERVICE", fontWeight = FontWeight.Bold)
                    Text("1.0 • Alpha 2 • быстрый режим", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            },
            actions = {
                if (state.shiftStartedAt > 0L) {
                    Text("${state.sessions.size} столов • ${state.shiftHookahs} кальянов", modifier = Modifier.padding(end = 12.dp), fontWeight = FontWeight.SemiBold)
                    OutlinedButton(onClick = { state.closeShift(); selected = null }, modifier = Modifier.padding(end = 14.dp)) { Text("Закрыть смену") }
                } else {
                    Button(onClick = state::openShift, modifier = Modifier.padding(end = 14.dp)) { Text("Открыть смену") }
                }
            }
        )
    }) { padding ->
        Row(
            Modifier.fillMaxSize().padding(padding).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ZoneButton("Основной зал", zone) { zone = "Основной зал" }
                    ZoneButton("Летняя веранда", zone) { zone = "Летняя веранда" }
                    Spacer(Modifier.weight(1f))
                    val urgent = tasksFor(state, now).count { it.priority == 0 }
                    if (urgent > 0) AssistChip(onClick = {}, label = { Text("Сейчас: $urgent") })
                }
                Spacer(Modifier.height(8.dp))
                HallMap(
                    zone = zone,
                    state = state,
                    now = now,
                    selected = selected,
                    onTable = { physical ->
                        if (state.sessions.containsKey(physical)) selected = physical else freeDialog = physical
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            val active = selected?.let { p -> state.sessions[p]?.let { p to it } }
            if (active != null) {
                QuickTablePanel(
                    physical = active.first,
                    session = active.second,
                    state = state,
                    now = now,
                    onClose = { selected = null },
                    modifier = Modifier.width(360.dp).fillMaxHeight()
                )
            } else {
                TaskPanel(
                    state = state,
                    now = now,
                    onOpen = { physical ->
                        zone = optTables.firstOrNull { it.physical == physical }?.zone ?: zone
                        selected = physical
                    },
                    modifier = Modifier.width(360.dp).fillMaxHeight()
                )
            }
        }
    }

    freeDialog?.let { physical ->
        QuickOpenTableDialog(
            physical = physical,
            display = state.displayNumber(physical),
            shiftOpen = state.shiftStartedAt > 0,
            onDismiss = { freeDialog = null },
            onOpen = { type ->
                state.openTable(physical, type)
                freeDialog = null
                selected = physical
            }
        )
    }
}

@Composable
private fun ZoneButton(name: String, selected: String, onClick: () -> Unit) {
    if (name == selected) Button(onClick = onClick) { Text(name) }
    else OutlinedButton(onClick = onClick) { Text(name) }
}

@Composable
private fun HallMap(
    zone: String,
    state: OptimizedState,
    now: Long,
    selected: Int?,
    onTable: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111315)),
        border = BorderStroke(1.dp, Color(0xFF4A3924))
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(10.dp)) {
            Box(Modifier.fillMaxSize().background(Color(0xFF17191B), RoundedCornerShape(14.dp)).border(1.dp, Color(0xFF73552D), RoundedCornerShape(14.dp)))
            if (zone == "Основной зал") OptMainDecor(maxWidth, maxHeight) else OptVerandaDecor(maxWidth, maxHeight)
            val specs = if (zone == "Основной зал") optMainSpecs else optVerandaSpecs
            specs.forEach { spec ->
                val session = state.sessions[spec.physical]
                val status = tablePriority(session, now)
                val fill = when (status) {
                    0 -> Color(0xFF4A2323)
                    1 -> Color(0xFF493D21)
                    2 -> Color(0xFF20352A)
                    3 -> Color(0xFF403722)
                    else -> Color(0xFF292720)
                }
                val stroke = when (status) {
                    0 -> Color(0xFFE05B5B)
                    1 -> Color(0xFFE1B96D)
                    2 -> Color(0xFF59A875)
                    3 -> Color(0xFFD4A84A)
                    else -> Color(0xFF8B6B3E)
                }
                Card(
                    Modifier.offset(maxWidth * spec.x, maxHeight * spec.y)
                        .width(maxWidth * spec.w).height(maxHeight * spec.h)
                        .graphicsLayer { rotationZ = spec.rotation }
                        .clickable { onTable(spec.physical) },
                    shape = when (spec.shape) {
                        OptShape.ROUND -> CircleShape
                        OptShape.RECT -> RoundedCornerShape(9.dp)
                        OptShape.WIDE -> RoundedCornerShape(14.dp)
                    },
                    colors = CardDefaults.cardColors(containerColor = if (selected == spec.physical) fill.copy(alpha = .75f) else fill),
                    border = BorderStroke(if (selected == spec.physical) 3.dp else 2.dp, stroke)
                ) {
                    Column(
                        Modifier.fillMaxSize().graphicsLayer { rotationZ = -spec.rotation },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(state.displayNumber(spec.physical).toString(), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        session?.let {
                            Text("🔥${it.hookahs.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(shortStatus(it, now), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskPanel(state: OptimizedState, now: Long, onOpen: (Int) -> Unit, modifier: Modifier = Modifier) {
    val tasks = tasksFor(state, now)
    Card(modifier, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF171613)), border = BorderStroke(1.dp, Color(0xFF4A3924))) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Text("Что делать сейчас", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Приоритеты смены без поиска по столам", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f))
            Spacer(Modifier.height(10.dp))
            if (tasks.isEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF20352A))) {
                    Text("Все спокойно • ближайших задач нет", Modifier.fillMaxWidth().padding(14.dp), color = Color(0xFF91C7A2))
                }
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(tasks.take(10)) { task ->
                        val urgent = task.priority == 0
                        Card(
                            Modifier.fillMaxWidth().clickable { onOpen(task.physical) },
                            colors = CardDefaults.cardColors(containerColor = if (urgent) Color(0xFF452322) else MaterialTheme.colorScheme.surfaceVariant),
                            border = if (urgent) BorderStroke(1.dp, Color(0xFFE05B5B)) else null
                        ) {
                            Column(Modifier.fillMaxWidth().padding(11.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Стол ${state.displayNumber(task.physical)} • ${task.title}", fontWeight = FontWeight.SemiBold)
                                        Text(task.subtitle, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text(if (urgent) "СЕЙЧАС" else formatRemaining(task.remainingMs), color = if (urgent) Color(0xFFE58B83) else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                }
                                when (task.action) {
                                    TaskAction.COAL -> Button(onClick = { state.changeCoal(task.physical, task.hookahIndex) }, modifier = Modifier.fillMaxWidth()) {
                                        Icon(Icons.Outlined.LocalFireDepartment, null)
                                        Spacer(Modifier.width(5.dp))
                                        Text("Угли заменены")
                                    }
                                    TaskAction.DELIVER -> Button(onClick = { state.deliver(task.physical, task.hookahIndex) }, modifier = Modifier.fillMaxWidth()) {
                                        Icon(Icons.Outlined.PlayArrow, null)
                                        Spacer(Modifier.width(5.dp))
                                        Text("Отдать • запустить таймер")
                                    }
                                    TaskAction.ELECTRONIC_DUE -> OutlinedButton(onClick = { onOpen(task.physical) }, modifier = Modifier.fillMaxWidth()) { Text("Открыть стол") }
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Color(0xFF4A3924))
            Text("Активные столы", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            val active = state.sessions.keys.sortedWith(compareBy({ tablePriority(state.sessions[it], now) }, { state.displayNumber(it) }))
            LazyColumn(Modifier.heightIn(max = 190.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                items(active) { physical ->
                    val session = state.sessions[physical] ?: return@items
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(physical) }.padding(vertical = 7.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${state.displayNumber(physical)}", Modifier.width(48.dp), fontWeight = FontWeight.Bold)
                        Text(shortStatus(session, now), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text("🔥${session.hookahs.size}", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickTablePanel(
    physical: Int,
    session: OptTableSession,
    state: OptimizedState,
    now: Long,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var type by remember(physical) { mutableStateOf("Классическая") }
    Card(modifier, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF171613)), border = BorderStroke(1.dp, Color(0xFF4A3924))) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Стол ${state.displayNumber(physical)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(shortStatus(session, now), color = if (tablePriority(session, now) == 0) Color(0xFFE05B5B) else MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Закрыть панель") }
            }
            HorizontalDivider(Modifier.padding(vertical = 9.dp), color = Color(0xFF4A3924))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(session.hookahs) { index, hookah ->
                    val remaining = remainingFor(hookah, now)
                    val due = remaining != null && remaining <= 0
                    Card(colors = CardDefaults.cardColors(containerColor = if (due) Color(0xFF452322) else MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.fillMaxWidth().padding(11.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Кальян ${index + 1} • ${hookah.type}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                TextButton(onClick = {
                                    state.removeHookah(physical, index)
                                    if (session.hookahs.size == 1) onClose()
                                }) { Text("Убрать") }
                            }
                            if (hookah.deliveredAt == null) {
                                Button(onClick = { state.deliver(physical, index) }, modifier = Modifier.fillMaxWidth()) { Text("Отдать • запустить таймер") }
                            } else {
                                Text(if (due) dueText(hookah) else formatRemaining(remaining ?: 0), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = if (due) Color(0xFFE05B5B) else MaterialTheme.colorScheme.primary)
                                if (hookah.type != "Электронная") {
                                    Text("Угли ${hookah.coalChanges}/$OPT_MAX_COALS", style = MaterialTheme.typography.bodySmall)
                                    Button(onClick = { state.changeCoal(physical, index) }, enabled = hookah.coalChanges < OPT_MAX_COALS, modifier = Modifier.fillMaxWidth()) {
                                        Text(if (due) "Угли заменены" else "Заменить угли сейчас")
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    HorizontalDivider(color = Color(0xFF4A3924))
                    Spacer(Modifier.height(4.dp))
                    Text("Быстро добавить", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        FilterChip(selected = type == "Классическая", onClick = { type = "Классическая" }, label = { Text("Классическая") })
                        FilterChip(selected = type == "Электронная", onClick = { type = "Электронная" }, label = { Text("Электронная") })
                    }
                    Button(onClick = { state.addHookah(physical, type) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Add, null)
                        Spacer(Modifier.width(5.dp))
                        Text("Добавить кальян")
                    }
                }
            }
            OutlinedButton(
                onClick = { state.closeTable(physical); onClose() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE58B83))
            ) { Text("Гость ушёл • закрыть стол") }
        }
    }
}

@Composable
private fun QuickOpenTableDialog(
    physical: Int,
    display: Int,
    shiftOpen: Boolean,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit
) {
    var type by remember(physical) { mutableStateOf("Классическая") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Стол $display") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(if (shiftOpen) "Открыть стол сразу с первым кальяном" else "Сначала откройте смену")
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    FilterChip(selected = type == "Классическая", onClick = { type = "Классическая" }, label = { Text("Классическая") })
                    FilterChip(selected = type == "Электронная", onClick = { type = "Электронная" }, label = { Text("Электронная") })
                }
            }
        },
        confirmButton = { Button(enabled = shiftOpen, onClick = { onOpen(type) }) { Text("Открыть") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

private fun tasksFor(state: OptimizedState, now: Long): List<ServiceTask> {
    val result = mutableListOf<ServiceTask>()
    state.sessions.forEach { (physical, session) ->
        session.hookahs.forEachIndexed { index, h ->
            if (h.deliveredAt == null) {
                result += ServiceTask(physical, index, "Кальян ${index + 1} готовится", "Запустить таймер при отдаче", 0, 0, TaskAction.DELIVER)
                return@forEachIndexed
            }
            val remaining = remainingFor(h, now) ?: return@forEachIndexed
            if (h.type != "Электронная" && h.coalChanges >= OPT_MAX_COALS) return@forEachIndexed
            if (remaining <= 0) {
                result += ServiceTask(
                    physical, index,
                    if (h.type == "Электронная") "Электронная чаша" else "Заменить угли",
                    if (h.type == "Электронная") "40 минут прошло" else "Кальян ${index + 1} • просрочено ${formatElapsed(-remaining)}",
                    0, remaining,
                    if (h.type == "Электронная") TaskAction.ELECTRONIC_DUE else TaskAction.COAL
                )
            } else if (remaining <= SOON_WINDOW) {
                result += ServiceTask(
                    physical, index,
                    if (h.type == "Электронная") "Проверить чашу" else "Скоро угли",
                    "Кальян ${index + 1}", 1, remaining,
                    if (h.type == "Электронная") TaskAction.ELECTRONIC_DUE else TaskAction.COAL
                )
            }
        }
    }
    return result.sortedWith(compareBy<ServiceTask> { it.priority }.thenBy { it.remainingMs }.thenBy { state.displayNumber(it.physical) })
}

private fun remainingFor(h: OptHookah, now: Long): Long? {
    val delivered = h.deliveredAt ?: return null
    val interval = if (h.type == "Электронная") OPT_ELECTRONIC_INTERVAL else OPT_COAL_INTERVAL
    val reference = if (h.type == "Электронная") delivered else (h.lastCoalAt ?: delivered)
    return interval - (now - reference)
}

private fun tablePriority(session: OptTableSession?, now: Long): Int {
    if (session == null) return 4
    if (session.hookahs.any { it.deliveredAt == null }) return 3
    val remaining = session.hookahs.mapNotNull { h ->
        if (h.type != "Электронная" && h.coalChanges >= OPT_MAX_COALS) null else remainingFor(h, now)
    }
    if (remaining.any { it <= 0 }) return 0
    if (remaining.any { it <= SOON_WINDOW }) return 1
    return 2
}

private fun shortStatus(session: OptTableSession, now: Long): String {
    val preparing = session.hookahs.count { it.deliveredAt == null }
    if (preparing > 0) return "Готовится: $preparing"
    val active = session.hookahs.mapNotNull { h ->
        if (h.type != "Электронная" && h.coalChanges >= OPT_MAX_COALS) null else remainingFor(h, now)
    }
    val min = active.minOrNull() ?: return "Активен"
    return when {
        min <= 0 -> "Обслужить сейчас"
        min <= SOON_WINDOW -> "Через ${formatRemaining(min)}"
        else -> formatRemaining(min)
    }
}

private fun dueText(h: OptHookah): String = if (h.type == "Электронная") "40 минут прошло" else "Пора менять угли"

private fun formatRemaining(ms: Long): String {
    val safe = ms.coerceAtLeast(0)
    val min = TimeUnit.MILLISECONDS.toMinutes(safe)
    val sec = TimeUnit.MILLISECONDS.toSeconds(safe) % 60
    return "%02d:%02d".format(min, sec)
}

private fun formatElapsed(ms: Long): String {
    val min = TimeUnit.MILLISECONDS.toMinutes(ms.coerceAtLeast(0))
    return "$min мин"
}

@Composable
private fun OptMainDecor(w: Dp, h: Dp) {
    OptDecorBlock("Хост", .025f, .045f, .105f, .225f, w, h)
    OptDecorLabel("ВХОД", .205f, .025f, w, h)
    OptDecorLine(.015f, .305f, .255f, .012f, w, h)
    OptDecorLine(.40f, .565f, .46f, .018f, w, h)
    OptDecorBlock("VIP 77", .025f, .75f, .23f, .22f, w, h)
    OptDecorBlock("Барная зона", .375f, .875f, .59f, .07f, w, h)
}

@Composable
private fun OptVerandaDecor(w: Dp, h: Dp) {
    OptDecorLine(.02f, .018f, .96f, .05f, w, h)
    OptDecorLine(.03f, .115f, .20f, .010f, w, h)
    OptDecorLine(.30f, .115f, .25f, .010f, w, h)
    OptDecorLine(.62f, .115f, .34f, .010f, w, h)
    OptDecorLabel("ВХОД", .245f, .095f, w, h)
    OptDecorLabel("ВХОД", .18f, .79f, w, h)
}

@Composable
private fun OptDecorBlock(label: String, x: Float, y: Float, width: Float, height: Float, w: Dp, h: Dp) {
    Box(
        Modifier.offset(w * x, h * y).width(w * width).height(h * height)
            .background(Color(0xFF201D19), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF75552C), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) { Text(label, color = Color(0xFFE5C17A), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center) }
}

@Composable
private fun OptDecorLabel(label: String, x: Float, y: Float, w: Dp, h: Dp) {
    Text(label, color = Color(0xFFE5C17A), style = MaterialTheme.typography.labelSmall, modifier = Modifier.offset(w * x, h * y))
}

@Composable
private fun OptDecorLine(x: Float, y: Float, width: Float, height: Float, w: Dp, h: Dp) {
    Box(Modifier.offset(w * x, h * y).width(w * width).height(h * height).background(Color(0xFF5B4428), RoundedCornerShape(4.dp)))
}

private fun optimizedAlert() {
    runCatching {
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90).apply {
            startTone(ToneGenerator.TONE_PROP_BEEP2, 500)
            release()
        }
    }
}
