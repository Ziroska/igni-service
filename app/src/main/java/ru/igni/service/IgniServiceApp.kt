package ru.igni.service

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

private const val COAL_INTERVAL_MS = 22 * 60 * 1000L
private const val ELECTRONIC_INTERVAL_MS = 40 * 60 * 1000L
private const val MAX_COAL_CHANGES = 4
private const val PREFS = "igni_service_state"

private data class HallTable(val physical: Int, val zone: String, val defaultNumber: Int = physical)
private data class TableSpec(val physical: Int, val x: Float, val y: Float, val w: Float, val h: Float, val round: Boolean = false)
private data class HookahSession(
    val type: String,
    val createdAt: Long,
    val deliveredAt: Long? = null,
    val lastCoalAt: Long? = null,
    val coalChanges: Int = 0,
    val countedInShift: Boolean = false
)
private data class TableSession(val hookahs: List<HookahSession>)

private val mainTables = buildList {
    add(HallTable(77, "Основной зал"))
    (101..114).forEach { add(HallTable(it, "Основной зал")) }
}
private val verandaTables = (301..308).map { HallTable(it, "Летняя веранда") }
private val allTables = mainTables + verandaTables

private val mainSpecs = listOf(
    TableSpec(101, .18f, .10f, .10f, .15f, true),
    TableSpec(102, .34f, .10f, .10f, .15f, true),
    TableSpec(103, .50f, .10f, .10f, .15f, true),
    TableSpec(104, .66f, .10f, .10f, .15f, true),
    TableSpec(105, .20f, .34f, .12f, .17f),
    TableSpec(106, .38f, .34f, .12f, .17f),
    TableSpec(107, .56f, .34f, .12f, .17f),
    TableSpec(108, .74f, .34f, .12f, .17f),
    TableSpec(109, .18f, .60f, .10f, .15f, true),
    TableSpec(110, .34f, .60f, .10f, .15f, true),
    TableSpec(111, .50f, .60f, .10f, .15f, true),
    TableSpec(112, .66f, .60f, .10f, .15f, true),
    TableSpec(113, .80f, .12f, .11f, .17f),
    TableSpec(114, .80f, .62f, .11f, .17f),
    TableSpec(77, .03f, .70f, .16f, .20f)
)

private val verandaSpecs = listOf(
    TableSpec(301, .10f, .18f, .11f, .19f),
    TableSpec(302, .30f, .18f, .11f, .19f),
    TableSpec(303, .50f, .18f, .11f, .19f),
    TableSpec(304, .70f, .18f, .11f, .19f),
    TableSpec(305, .10f, .57f, .11f, .19f),
    TableSpec(306, .30f, .57f, .11f, .19f),
    TableSpec(307, .50f, .57f, .11f, .19f),
    TableSpec(308, .70f, .57f, .11f, .19f)
)

private class ServiceState(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val sessions: SnapshotStateMap<Int, TableSession> = mutableStateMapOf()
    var shiftStartedAt by mutableLongStateOf(prefs.getLong("shiftStartedAt", 0L))
    var shiftHookahs by mutableIntStateOf(prefs.getInt("shiftHookahs", 0))
    var tableConfigVersion by mutableIntStateOf(0)

    init { restoreSessions() }

    fun displayNumber(physical: Int): Int = prefs.getInt("table_$physical", physical)

    fun renameTable(physical: Int, number: Int): Boolean {
        if (allTables.any { it.physical != physical && displayNumber(it.physical) == number }) return false
        prefs.edit().putInt("table_$physical", number).apply()
        tableConfigVersion++
        return true
    }

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
        val now = System.currentTimeMillis()
        sessions[physical] = TableSession(listOf(HookahSession(type, now)))
        saveSessions()
    }

    fun addHookah(physical: Int, type: String) {
        val current = sessions[physical] ?: return
        sessions[physical] = current.copy(hookahs = current.hookahs + HookahSession(type, System.currentTimeMillis()))
        saveSessions()
    }

    fun deliver(physical: Int, index: Int) {
        val current = sessions[physical] ?: return
        val now = System.currentTimeMillis()
        val updated = current.hookahs.mapIndexed { i, h ->
            if (i != index || h.deliveredAt != null) h else h.copy(deliveredAt = now, lastCoalAt = now, countedInShift = true)
        }
        val wasCounted = current.hookahs.getOrNull(index)?.countedInShift == true
        sessions[physical] = current.copy(hookahs = updated)
        if (!wasCounted) {
            shiftHookahs++
            saveShift()
        }
        saveSessions()
    }

    fun changeCoal(physical: Int, index: Int) {
        val current = sessions[physical] ?: return
        val h = current.hookahs.getOrNull(index) ?: return
        if (h.type == "Электронная" || h.deliveredAt == null || h.coalChanges >= MAX_COAL_CHANGES) return
        val now = System.currentTimeMillis()
        sessions[physical] = current.copy(hookahs = current.hookahs.mapIndexed { i, item ->
            if (i == index) item.copy(lastCoalAt = now, coalChanges = (item.coalChanges + 1).coerceAtMost(MAX_COAL_CHANGES)) else item
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

    fun moveTable(from: Int, to: Int): Boolean {
        val current = sessions[from] ?: return false
        if (sessions.containsKey(to)) return false
        sessions.remove(from)
        sessions[to] = current
        saveSessions()
        return true
    }

    private fun saveShift() {
        prefs.edit().putLong("shiftStartedAt", shiftStartedAt).putInt("shiftHookahs", shiftHookahs).apply()
    }

    private fun saveSessions() {
        val value = sessions.entries.joinToString(";;") { (table, session) ->
            val hs = session.hookahs.joinToString("|") { h ->
                listOf(h.type, h.createdAt, h.deliveredAt ?: 0L, h.lastCoalAt ?: 0L, h.coalChanges, if (h.countedInShift) 1 else 0).joinToString(",")
            }
            "$table#$hs"
        }
        prefs.edit().putString("sessions", value).apply()
    }

    private fun restoreSessions() {
        val raw = prefs.getString("sessions", "").orEmpty()
        if (raw.isBlank()) return
        raw.split(";;").forEach { tableBlock ->
            val parts = tableBlock.split("#", limit = 2)
            val table = parts.getOrNull(0)?.toIntOrNull() ?: return@forEach
            val hookahs = parts.getOrNull(1).orEmpty().split("|").mapNotNull { item ->
                val f = item.split(",")
                if (f.size < 6) return@mapNotNull null
                val created = f[1].toLongOrNull() ?: return@mapNotNull null
                HookahSession(
                    type = f[0],
                    createdAt = created,
                    deliveredAt = f[2].toLongOrNull()?.takeIf { it > 0 },
                    lastCoalAt = f[3].toLongOrNull()?.takeIf { it > 0 },
                    coalChanges = f[4].toIntOrNull()?.coerceIn(0, MAX_COAL_CHANGES) ?: 0,
                    countedInShift = f[5] == "1"
                )
            }
            if (hookahs.isNotEmpty()) sessions[table] = TableSession(hookahs)
        }
    }
}

@Composable
fun IgniServiceApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state = remember { ServiceState(context.applicationContext) }
    val colors = darkColorScheme(
        primary = Color(0xFFE1B96D),
        background = Color(0xFF11110F),
        surface = Color(0xFF1A1916),
        surfaceVariant = Color(0xFF26231E),
        onBackground = Color(0xFFF1EEE7),
        onSurface = Color(0xFFF1EEE7)
    )
    MaterialTheme(colorScheme = colors) { ServiceScreen(state) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServiceScreen(state: ServiceState) {
    var zone by remember { mutableStateOf("Основной зал") }
    var selectedPhysical by remember { mutableStateOf<Int?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val playedAlerts = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    LaunchedEffect(now, state.sessions.size) {
        state.sessions.forEach { (table, session) ->
            session.hookahs.forEachIndexed { index, hookah ->
                val delivered = hookah.deliveredAt ?: return@forEachIndexed
                val reference = if (hookah.type == "Электронная") delivered else (hookah.lastCoalAt ?: delivered)
                val interval = if (hookah.type == "Электронная") ELECTRONIC_INTERVAL_MS else COAL_INTERVAL_MS
                val due = now - reference >= interval && (hookah.type == "Электронная" || hookah.coalChanges < MAX_COAL_CHANGES)
                val key = "$table:$index:${hookah.coalChanges}:$reference"
                if (due && playedAlerts[key] != true) {
                    playedAlerts[key] = true
                    playAlert()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("IGNI SERVICE", fontWeight = FontWeight.Bold)
                        Text("1.0 • Alpha 1", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                },
                actions = {
                    if (state.shiftStartedAt > 0L) {
                        Text("Кальянов за смену: ${state.shiftHookahs}", modifier = Modifier.padding(end = 16.dp), fontWeight = FontWeight.SemiBold)
                        OutlinedButton(onClick = { state.closeShift() }, modifier = Modifier.padding(end = 16.dp)) { Text("Закрыть смену") }
                    } else {
                        Button(onClick = { state.openShift() }, modifier = Modifier.padding(end = 16.dp)) { Text("Открыть смену") }
                    }
                }
            )
        }
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (zone == "Основной зал") Button(onClick = { zone = "Основной зал" }) { Text("Основной зал") }
                    else OutlinedButton(onClick = { zone = "Основной зал" }) { Text("Основной зал") }
                    if (zone == "Летняя веранда") Button(onClick = { zone = "Летняя веранда" }) { Text("Летняя веранда") }
                    else OutlinedButton(onClick = { zone = "Летняя веранда" }) { Text("Летняя веранда") }
                }
                Spacer(Modifier.height(10.dp))
                Card(Modifier.fillMaxSize(), colors = CardDefaults.cardColors(containerColor = Color(0xFF151411))) {
                    BoxWithConstraints(Modifier.fillMaxSize().padding(10.dp)) {
                        val specs = if (zone == "Основной зал") mainSpecs else verandaSpecs
                        specs.forEach { spec ->
                            val session = state.sessions[spec.physical]
                            val display = state.displayNumber(spec.physical)
                            val alert = session?.hookahs?.any { isDue(it, now) } == true
                            val preparing = session?.hookahs?.any { it.deliveredAt == null } == true
                            val color = when {
                                session == null -> Color(0xFF292720)
                                alert -> Color(0xFF4A2323)
                                preparing -> Color(0xFF403722)
                                else -> Color(0xFF20352A)
                            }
                            val border = when {
                                session == null -> Color(0xFF8B6B3E)
                                alert -> Color(0xFFE05B5B)
                                preparing -> Color(0xFFD4A84A)
                                else -> Color(0xFF59A875)
                            }
                            Card(
                                modifier = Modifier
                                    .offset(maxWidth * spec.x, maxHeight * spec.y)
                                    .width(maxWidth * spec.w)
                                    .height(maxHeight * spec.h)
                                    .clickable { selectedPhysical = spec.physical },
                                shape = if (spec.round) CircleShape else RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = color),
                                border = BorderStroke(2.dp, border)
                            ) {
                                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Text(display.toString(), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                                    if (session != null) {
                                        Text("🔥 ${session.hookahs.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        Text(tableStatus(session, now), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                                    }
                                }
                            }
                        }
                        if (zone == "Основной зал") {
                            Text("Барная зона", modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp), color = MaterialTheme.colorScheme.primary)
                        } else {
                            Text("ВХОД", modifier = Modifier.align(Alignment.TopCenter).padding(8.dp), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            ActiveTablesPanel(
                state = state,
                now = now,
                onOpen = { physical ->
                    zone = allTables.firstOrNull { it.physical == physical }?.zone ?: zone
                    selectedPhysical = physical
                },
                modifier = Modifier.width(300.dp).fillMaxHeight()
            )
        }
    }

    selectedPhysical?.let { physical ->
        val session = state.sessions[physical]
        TableDialog(
            physical = physical,
            displayNumber = state.displayNumber(physical),
            session = session,
            shiftOpen = state.shiftStartedAt > 0L,
            now = now,
            state = state,
            onDismiss = { selectedPhysical = null }
        )
    }
}

@Composable
private fun ActiveTablesPanel(state: ServiceState, now: Long, onOpen: (Int) -> Unit, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color(0xFF171613))) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Text("Активные столы", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (state.sessions.isEmpty()) {
                Text("Нет активных столов", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.sessions.keys.sortedBy { state.displayNumber(it) }) { physical ->
                        val session = state.sessions[physical] ?: return@items
                        Card(
                            Modifier.fillMaxWidth().clickable { onOpen(physical) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Стол ${state.displayNumber(physical)}", fontWeight = FontWeight.SemiBold)
                                    Text(tableStatus(session, now), style = MaterialTheme.typography.bodySmall)
                                }
                                Text("🔥${session.hookahs.size}", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TableDialog(
    physical: Int,
    displayNumber: Int,
    session: TableSession?,
    shiftOpen: Boolean,
    now: Long,
    state: ServiceState,
    onDismiss: () -> Unit
) {
    var newType by remember(physical) { mutableStateOf("Классическая") }
    var showMove by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf(displayNumber.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Стол $displayNumber") },
        text = {
            Column(Modifier.widthIn(min = 540.dp, max = 700.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (session == null) {
                    Text(if (shiftOpen) "Стол свободен" else "Сначала открой смену")
                    TypeSelector(newType) { newType = it }
                } else {
                    session.hookahs.forEachIndexed { index, hookah ->
                        HookahCard(
                            index = index,
                            hookah = hookah,
                            now = now,
                            onDeliver = { state.deliver(physical, index) },
                            onCoal = { state.changeCoal(physical, index) },
                            onRemove = { state.removeHookah(physical, index) }
                        )
                    }
                    Divider()
                    Text("Добавить кальян", fontWeight = FontWeight.SemiBold)
                    TypeSelector(newType) { newType = it }
                    Button(onClick = { state.addHookah(physical, newType) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Add, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Добавить $newType")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showMove = true }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.SwapHoriz, null); Spacer(Modifier.width(6.dp)); Text("Сменить стол")
                        }
                        OutlinedButton(onClick = { showRename = true }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.Tag, null); Spacer(Modifier.width(6.dp)); Text("Номер стола")
                        }
                    }
                    OutlinedButton(onClick = { state.closeTable(physical); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text("Закрыть стол") }
                }
            }
        },
        confirmButton = {
            if (session == null) {
                Button(enabled = shiftOpen, onClick = { state.openTable(physical, newType) }) { Text("Открыть стол") }
            } else TextButton(onClick = onDismiss) { Text("Готово") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )

    if (showMove && session != null) {
        val free = allTables.filter { !state.sessions.containsKey(it.physical) }
        AlertDialog(
            onDismissRequest = { showMove = false },
            title = { Text("Перенести стол $displayNumber") },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(free) { table ->
                        TextButton(onClick = {
                            state.moveTable(physical, table.physical)
                            showMove = false
                            onDismiss()
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("Стол ${state.displayNumber(table.physical)} • ${table.zone}", modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showMove = false }) { Text("Отмена") } }
        )
    }

    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Изменить номер стола") },
            text = {
                OutlinedTextField(value = renameText, onValueChange = { renameText = it.filter(Char::isDigit).take(4) }, label = { Text("Новый номер") })
            },
            confirmButton = {
                Button(onClick = {
                    renameText.toIntOrNull()?.let { state.renameTable(physical, it) }
                    showRename = false
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun TypeSelector(type: String, onChange: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (type == "Классическая") Button(onClick = { onChange("Классическая") }) { Text("Классическая") }
        else OutlinedButton(onClick = { onChange("Классическая") }) { Text("Классическая") }
        if (type == "Электронная") Button(onClick = { onChange("Электронная") }) { Text("Электронная • 40 мин") }
        else OutlinedButton(onClick = { onChange("Электронная") }) { Text("Электронная • 40 мин") }
    }
}

@Composable
private fun HookahCard(index: Int, hookah: HookahSession, now: Long, onDeliver: () -> Unit, onCoal: () -> Unit, onRemove: () -> Unit) {
    val delivered = hookah.deliveredAt
    val due = isDue(hookah, now)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.LocalFireDepartment, null, tint = if (due) Color(0xFFE05B5B) else MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Кальян ${index + 1} • ${hookah.type}", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onRemove) { Text("Удалить") }
            }
            if (delivered == null) {
                Text("Статус: готовится", color = Color(0xFFD4A84A))
                Button(onClick = onDeliver, modifier = Modifier.fillMaxWidth()) { Text("Отдать кальян и запустить таймер") }
            } else {
                Text(timerText(hookah, now), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = if (due) Color(0xFFE05B5B) else MaterialTheme.colorScheme.primary)
                if (hookah.type == "Электронная") {
                    Text("Таймер электронной чаши • 40 минут")
                } else {
                    Text("Замены углей: ${hookah.coalChanges}/$MAX_COAL_CHANGES")
                    Button(
                        onClick = onCoal,
                        enabled = hookah.coalChanges < MAX_COAL_CHANGES,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (hookah.coalChanges >= MAX_COAL_CHANGES) "Лимит замен достигнут" else "Заменить угли") }
                }
            }
        }
    }
}

private fun isDue(hookah: HookahSession, now: Long): Boolean {
    val delivered = hookah.deliveredAt ?: return false
    val interval = if (hookah.type == "Электронная") ELECTRONIC_INTERVAL_MS else COAL_INTERVAL_MS
    val reference = if (hookah.type == "Электронная") delivered else (hookah.lastCoalAt ?: delivered)
    return now - reference >= interval && (hookah.type == "Электронная" || hookah.coalChanges < MAX_COAL_CHANGES)
}

private fun timerText(hookah: HookahSession, now: Long): String {
    val delivered = hookah.deliveredAt ?: return "Готовится"
    val interval = if (hookah.type == "Электронная") ELECTRONIC_INTERVAL_MS else COAL_INTERVAL_MS
    val reference = if (hookah.type == "Электронная") delivered else (hookah.lastCoalAt ?: delivered)
    val remaining = interval - (now - reference)
    if (remaining <= 0L) return if (hookah.type == "Электронная") "40 минут прошло" else "Пора менять угли"
    val min = TimeUnit.MILLISECONDS.toMinutes(remaining)
    val sec = TimeUnit.MILLISECONDS.toSeconds(remaining) % 60
    return "%02d:%02d".format(min, sec)
}

private fun tableStatus(session: TableSession, now: Long): String {
    if (session.hookahs.any { it.deliveredAt == null }) return "Готовится"
    val due = session.hookahs.count { isDue(it, now) }
    if (due > 0) return "Обслужить: $due"
    return session.hookahs.map { timerText(it, now) }.minByOrNull { text -> text } ?: "Активен"
}

private fun playAlert() {
    runCatching {
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90).apply {
            startTone(ToneGenerator.TONE_PROP_BEEP2, 700)
            release()
        }
    }
}
