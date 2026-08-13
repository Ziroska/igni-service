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
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
private enum class TableShape { ROUND, RECT, WIDE }
private data class TableSpec(
    val physical: Int,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val shape: TableShape,
    val rotation: Float = 0f
)
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
    TableSpec(77, .055f, .775f, .16f, .17f, TableShape.WIDE),
    TableSpec(101, .27f, .08f, .095f, .14f, TableShape.ROUND),
    TableSpec(102, .46f, .06f, .075f, .20f, TableShape.RECT),
    TableSpec(103, .62f, .06f, .075f, .20f, TableShape.RECT),
    TableSpec(104, .79f, .06f, .075f, .20f, TableShape.RECT),
    TableSpec(105, .39f, .34f, .075f, .22f, TableShape.RECT),
    TableSpec(106, .55f, .34f, .075f, .22f, TableShape.RECT),
    TableSpec(107, .72f, .34f, .075f, .22f, TableShape.RECT),
    TableSpec(108, .38f, .64f, .075f, .21f, TableShape.RECT),
    TableSpec(109, .53f, .68f, .16f, .12f, TableShape.WIDE),
    TableSpec(110, .76f, .65f, .075f, .21f, TableShape.RECT),
    TableSpec(111, .25f, .58f, .075f, .12f, TableShape.ROUND),
    TableSpec(112, .20f, .47f, .075f, .12f, TableShape.ROUND),
    TableSpec(113, .04f, .39f, .14f, .12f, TableShape.WIDE),
    TableSpec(114, .04f, .59f, .14f, .12f, TableShape.WIDE)
)

private val verandaSpecs = listOf(
    TableSpec(301, .085f, .20f, .16f, .16f, TableShape.WIDE, -42f),
    TableSpec(302, .31f, .16f, .11f, .13f, TableShape.ROUND),
    TableSpec(303, .49f, .16f, .11f, .13f, TableShape.ROUND),
    TableSpec(304, .67f, .16f, .11f, .13f, TableShape.ROUND),
    TableSpec(305, .83f, .16f, .11f, .13f, TableShape.ROUND),
    TableSpec(306, .32f, .62f, .09f, .23f, TableShape.RECT),
    TableSpec(307, .53f, .60f, .09f, .23f, TableShape.RECT),
    TableSpec(308, .75f, .61f, .09f, .23f, TableShape.RECT)
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
                Card(
                    Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF111315)),
                    border = BorderStroke(1.dp, Color(0xFF4A3924))
                ) {
                    BoxWithConstraints(Modifier.fillMaxSize().padding(10.dp)) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color(0xFF17191B), RoundedCornerShape(14.dp))
                                .border(1.dp, Color(0xFF73552D), RoundedCornerShape(14.dp))
                        )
                        val specs = if (zone == "Основной зал") mainSpecs else verandaSpecs
                        if (zone == "Основной зал") MainHallDecor(maxWidth, maxHeight)
                        else VerandaDecor(maxWidth, maxHeight)
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
                                    .graphicsLayer { rotationZ = spec.rotation }
                                    .clickable { selectedPhysical = spec.physical },
                                shape = when (spec.shape) {
                                    TableShape.ROUND -> CircleShape
                                    TableShape.RECT -> RoundedCornerShape(9.dp)
                                    TableShape.WIDE -> RoundedCornerShape(14.dp)
                                },
                                colors = CardDefaults.cardColors(containerColor = color),
                                border = BorderStroke(2.dp, border)
                            ) {
                                Column(
                                    Modifier.fillMaxSize().graphicsLayer { rotationZ = -spec.rotation },
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(display.toString(), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                                    if (session != null) {
                                        Text("🔥 ${session.hookahs.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        Text(tableStatus(session, now), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val activePhysical = selectedPhysical?.takeIf { state.sessions.containsKey(it) }
            val activeSession = activePhysical?.let { state.sessions[it] }
            if (activePhysical != null && activeSession != null) {
                ActiveTablePanel(
                    physical = activePhysical,
                    displayNumber = state.displayNumber(activePhysical),
                    session = activeSession,
                    now = now,
                    state = state,
                    onClosePanel = { selectedPhysical = null },
                    onMoved = { newPhysical ->
                        zone = allTables.firstOrNull { it.physical == newPhysical }?.zone ?: zone
                        selectedPhysical = newPhysical
                    },
                    modifier = Modifier.width(340.dp).fillMaxHeight()
                )
            } else {
                ActiveTablesPanel(
                    state = state,
                    now = now,
                    onOpen = { physical ->
                        zone = allTables.firstOrNull { it.physical == physical }?.zone ?: zone
                        selectedPhysical = physical
                    },
                    modifier = Modifier.width(340.dp).fillMaxHeight()
                )
            }
        }
    }

    selectedPhysical?.let { physical ->
        val session = state.sessions[physical]
        if (session == null) {
            FreeTableDialog(
                physical = physical,
                displayNumber = state.displayNumber(physical),
                shiftOpen = state.shiftStartedAt > 0L,
                state = state,
                onDismiss = { selectedPhysical = null }
            )
        }
    }
}

@Composable
private fun MainHallDecor(w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp) {
    DecorBlock("Хост", .025f, .045f, .105f, .225f, w, h)
    DecorLabel("ВХОД", .205f, .025f, w, h)
    DecorBlock("", .395f, .065f, .045f, .17f, w, h)
    DecorLine(.015f, .305f, .255f, .012f, w, h)
    DecorLine(.40f, .565f, .46f, .018f, w, h)
    DecorBlock("VIP 77", .025f, .75f, .23f, .22f, w, h)
    DecorDiamond("WC", .285f, .875f, .065f, .075f, w, h)
    DecorBlock("Барная зона", .375f, .875f, .59f, .07f, w, h)
    DecorPlant(.015f, .36f, w, h)
    DecorPlant(.84f, .035f, w, h)
    DecorPlant(.89f, .70f, w, h)
    DecorPlant(.34f, .47f, w, h)
}

@Composable
private fun VerandaDecor(w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp) {
    DecorLine(.02f, .018f, .96f, .05f, w, h)
    DecorLine(.03f, .115f, .20f, .010f, w, h)
    DecorLine(.30f, .115f, .25f, .010f, w, h)
    DecorLine(.62f, .115f, .34f, .010f, w, h)
    DecorLine(.025f, .33f, .010f, .52f, w, h)
    DecorLine(.955f, .33f, .010f, .52f, w, h)
    DecorLabel("ВХОД", .245f, .095f, w, h)
    DecorLabel("ВХОД", .18f, .79f, w, h)
    DecorLine(.27f, .91f, .66f, .032f, w, h)
    DecorPlant(.015f, .17f, w, h)
    DecorPlant(.90f, .18f, w, h)
    DecorPlant(.05f, .83f, w, h)
    DecorPlant(.90f, .83f, w, h)
}

@Composable
private fun DecorBlock(label: String, x: Float, y: Float, width: Float, height: Float, w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .offset(w * x, h * y)
            .width(w * width)
            .height(h * height)
            .background(Color(0xFF201D19), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF75552C), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color(0xFFE5C17A), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
    }
}

@Composable
private fun DecorLabel(label: String, x: Float, y: Float, w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp) {
    Text(
        label,
        color = Color(0xFFE5C17A),
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.offset(w * x, h * y)
    )
}

@Composable
private fun DecorLine(x: Float, y: Float, width: Float, height: Float, w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .offset(w * x, h * y)
            .width(w * width)
            .height(h * height)
            .background(Color(0xFF5B4428), RoundedCornerShape(4.dp))
    )
}

@Composable
private fun DecorDiamond(label: String, x: Float, y: Float, width: Float, height: Float, w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .offset(w * x, h * y)
            .width(w * width)
            .height(h * height)
            .graphicsLayer { rotationZ = 45f }
            .background(Color(0xFF201D19), RoundedCornerShape(5.dp))
            .border(1.dp, Color(0xFF75552C), RoundedCornerShape(5.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = Color(0xFFE5C17A),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.graphicsLayer { rotationZ = -45f }
        )
    }
}

@Composable
private fun DecorPlant(x: Float, y: Float, w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .offset(w * x, h * y)
            .size(22.dp)
            .background(Color(0xFF26321F), CircleShape)
            .border(1.dp, Color(0xFF6C8048), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text("✦", color = Color(0xFF93A861), style = MaterialTheme.typography.labelSmall)
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
private fun ActiveTablePanel(
    physical: Int,
    displayNumber: Int,
    session: TableSession,
    now: Long,
    state: ServiceState,
    onClosePanel: () -> Unit,
    onMoved: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var newType by remember(physical) { mutableStateOf("Классическая") }
    var showMove by remember(physical) { mutableStateOf(false) }
    var showRename by remember(physical) { mutableStateOf(false) }
    var renameText by remember(physical, displayNumber) { mutableStateOf(displayNumber.toString()) }
    var renameError by remember(physical) { mutableStateOf<String?>(null) }

    Card(
        modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF171613)),
        border = BorderStroke(1.dp, Color(0xFF4A3924))
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Стол $displayNumber", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        tableStatus(session, now),
                        color = if (session.hookahs.any { isDue(it, now) }) Color(0xFFE05B5B) else MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                IconButton(onClick = onClosePanel) {
                    Icon(Icons.Outlined.Close, contentDescription = "Закрыть панель")
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Color(0xFF4A3924))

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(session.hookahs) { index, hookah ->
                    HookahCard(
                        index = index,
                        hookah = hookah,
                        now = now,
                        onDeliver = { state.deliver(physical, index) },
                        onCoal = { state.changeCoal(physical, index) },
                        onRemove = {
                            state.removeHookah(physical, index)
                            if (session.hookahs.size == 1) onClosePanel()
                        }
                    )
                }

                item {
                    HorizontalDivider(color = Color(0xFF4A3924))
                    Spacer(Modifier.height(8.dp))
                    Text("Добавить кальян", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    TypeSelector(newType) { newType = it }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { state.addHookah(physical, newType) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Add, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Добавить $newType")
                    }
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showMove = true }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.SwapHoriz, null)
                            Spacer(Modifier.width(4.dp))
                            Text("Сменить стол")
                        }
                        OutlinedButton(onClick = {
                            renameText = displayNumber.toString()
                            renameError = null
                            showRename = true
                        }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.Tag, null)
                            Spacer(Modifier.width(4.dp))
                            Text("Номер")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            state.closeTable(physical)
                            onClosePanel()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE58B83))
                    ) {
                        Text("Закрыть стол")
                    }
                }
            }
        }
    }

    if (showMove) {
        val free = allTables.filter { !state.sessions.containsKey(it.physical) }
        AlertDialog(
            onDismissRequest = { showMove = false },
            title = { Text("Перенести стол $displayNumber") },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(free) { table ->
                        TextButton(
                            onClick = {
                                if (state.moveTable(physical, table.physical)) {
                                    showMove = false
                                    onMoved(table.physical)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
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
                Column {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = {
                            renameText = it.filter(Char::isDigit).take(4)
                            renameError = null
                        },
                        label = { Text("Новый номер") },
                        isError = renameError != null
                    )
                    renameError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val number = renameText.toIntOrNull()
                    when {
                        number == null -> renameError = "Введите номер стола"
                        state.renameTable(physical, number) -> showRename = false
                        else -> renameError = "Этот номер уже используется"
                    }
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun FreeTableDialog(
    physical: Int,
    displayNumber: Int,
    shiftOpen: Boolean,
    state: ServiceState,
    onDismiss: () -> Unit
) {
    var newType by remember(physical) { mutableStateOf("Классическая") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Стол $displayNumber") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (shiftOpen) "Стол свободен" else "Сначала открой смену")
                TypeSelector(newType) { newType = it }
            }
        },
        confirmButton = {
            Button(enabled = shiftOpen, onClick = { state.openTable(physical, newType) }) {
                Text("Открыть стол")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
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
