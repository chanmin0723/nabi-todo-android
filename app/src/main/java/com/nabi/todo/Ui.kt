@file:OptIn(ExperimentalMaterial3Api::class)

package com.nabi.todo

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

private val Ink = Color(0xFF2B2A4C)
private val Orange = Color(0xFFF4A259)

@Composable
fun NabiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Orange,
            onPrimary = Ink,
            primaryContainer = Color(0xFFFFE3C4),
            onPrimaryContainer = Ink,
            secondary = Color(0xFF7AA7F0),
            background = Color(0xFFECEFFA),
            onBackground = Ink,
            surface = Color.White,
            onSurface = Ink,
            surfaceVariant = Color(0xFFE3E6F7),
            onSurfaceVariant = Color(0xFF5B5A80),
        ),
        content = content,
    )
}

data class EditTarget(val task: Task?, val date: LocalDate)

private val DATE_FMT = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)

private fun pickDate(ctx: Context, cur: String?, onPick: (String) -> Unit) {
    val d = try {
        if (cur != null) LocalDate.parse(cur) else LocalDate.now()
    } catch (e: Exception) {
        LocalDate.now()
    }
    DatePickerDialog(ctx, { _, y, m, day -> onPick(LocalDate.of(y, m + 1, day).toString()) }, d.year, d.monthValue - 1, d.dayOfMonth).show()
}

private fun pickTime(ctx: Context, cur: String, onPick: (String) -> Unit) {
    val t = try {
        LocalTime.parse(cur)
    } catch (e: Exception) {
        LocalTime.of(9, 0)
    }
    TimePickerDialog(ctx, { _, h, m -> onPick(String.format(Locale.US, "%02d:%02d", h, m)) }, t.hour, t.minute, true).show()
}

private fun fmtMinutes(m: Int) = String.format(Locale.US, "%02d:%02d", m / 60, m % 60)

@Composable
fun MainScreen(vm: MainViewModel) {
    val cals by vm.cals.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val date by vm.date.collectAsStateWithLifecycle()
    val msg by vm.msg.collectAsStateWithLifecycle()
    val tabReset by vm.tabReset.collectAsStateWithLifecycle()

    var tab by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<EditTarget?>(null) }
    var showJoin by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var nudgeTarget by remember { mutableStateOf<Task?>(null) }
    val snack = remember { SnackbarHostState() }

    LaunchedEffect(tabReset) { tab = 0 }
    LaunchedEffect(msg) {
        val m = msg
        if (m != null) {
            snack.showSnackbar(m)
            vm.toast(null)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    CalendarMenu(
                        cals = cals, activeCid = active,
                        onSelect = { vm.select(it) },
                        onJoin = { showJoin = true },
                        onCreate = { showCreate = true },
                    )
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "설정") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
        floatingActionButton = {
            if (active != null) {
                FloatingActionButton(
                    onClick = { editing = EditTarget(null, if (tab == 0) date else date) },
                    containerColor = Orange, contentColor = Ink,
                ) { Icon(Icons.Default.Add, "새 할 일") }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (active == null) {
                EmptyState(onJoin = { showJoin = true }, onCreate = { showCreate = true })
            } else {
                TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("일", fontWeight = FontWeight.Bold) })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("나중에", fontWeight = FontWeight.Bold) })
                }
                if (status.isNotEmpty()) {
                    Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                if (tab == 0) {
                    DayView(
                        tasks = tasks, groups = groups, date = date,
                        onDate = { vm.setDate(it) },
                        onToggle = { t, d -> vm.toggle(t, d) },
                        onSub = { t, d, sid -> vm.toggleSub(t, d, sid) },
                        onEdit = { t, d -> editing = EditTarget(t, d) },
                        onNudge = { t -> nudgeTarget = t },
                    )
                } else {
                    LaterView(
                        tasks = tasks, groups = groups,
                        onToggle = { t -> vm.toggle(t, LocalDate.now()) },
                        onEdit = { t -> editing = EditTarget(t, LocalDate.now()) },
                    )
                }
            }
        }
    }

    val e = editing
    if (e != null) {
        EditDialog(
            target = e, groups = groups,
            onSave = { vm.saveTask(e.task, it); editing = null },
            onDelete = { e.task?.let { vm.delete(it) }; editing = null },
            onSkip = { e.task?.let { vm.skipDay(it, e.date) }; editing = null },
            onDismiss = { editing = null },
            laterMode = tab == 1,
        )
    }
    val nt = nudgeTarget
    if (nt != null) {
        AlertDialog(
            onDismissRequest = { nudgeTarget = null },
            title = { Text("콕 찌를까요?", fontWeight = FontWeight.ExtraBold) },
            text = { Text("'${nt.title}' 하라고 이 달력을 쓰는 상대 폰에 알림을 보내요.\n할 일 하나당 하루에 한 번만 보낼 수 있어요.") },
            confirmButton = { TextButton(onClick = { vm.nudge(nt); nudgeTarget = null }) { Text("보내기", fontWeight = FontWeight.ExtraBold) } },
            dismissButton = { TextButton(onClick = { nudgeTarget = null }) { Text("취소") } },
        )
    }
    if (showJoin) JoinDialog(onDismiss = { showJoin = false }, onJoin = { raw, done -> vm.join(raw) { ok -> done(ok); if (ok) showJoin = false } })
    if (showCreate) CreateDialog(onDismiss = { showCreate = false }, onCreate = { name -> vm.create(name) { showCreate = false } })
    if (showSettings) {
        SettingsDialog(
            activeCal = cals.find { it.cid == active },
            onLeave = { cid -> vm.leave(cid); showSettings = false },
            onDismiss = { showSettings = false },
        )
    }
}

@Composable
private fun CalendarMenu(
    cals: List<CalRef>, activeCid: String?,
    onSelect: (String) -> Unit, onJoin: () -> Unit, onCreate: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.clickable { open = true }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(cals.find { it.cid == activeCid }?.name ?: "나비 할 일", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            cals.forEach { c ->
                DropdownMenuItem(
                    text = { Text(c.name, fontWeight = if (c.cid == activeCid) FontWeight.Bold else FontWeight.Normal) },
                    onClick = { open = false; onSelect(c.cid) },
                )
            }
            if (cals.isNotEmpty()) HorizontalDivider()
            DropdownMenuItem(text = { Text("공유 달력 참여 (코드·링크)") }, onClick = { open = false; onJoin() })
            DropdownMenuItem(text = { Text("새 달력 만들기") }, onClick = { open = false; onCreate() })
        }
    }
}

@Composable
private fun EmptyState(onJoin: () -> Unit, onCreate: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🐈", fontSize = 56.sp)
        Spacer(Modifier.height(12.dp))
        Text("웹에서 쓰던 달력에 참여하거나\n새 달력을 만들어 보세요", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onJoin, modifier = Modifier.fillMaxWidth()) { Text("공유 코드·링크로 참여", fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Text("새 달력 만들기") }
    }
}

@Composable
private fun DayView(
    tasks: List<Task>, groups: List<Group>, date: LocalDate,
    onDate: (LocalDate) -> Unit,
    onToggle: (Task, LocalDate) -> Unit,
    onSub: (Task, LocalDate, String) -> Unit,
    onEdit: (Task, LocalDate) -> Unit,
    onNudge: (Task) -> Unit,
) {
    val ctx = LocalContext.current
    val today = LocalDate.now()
    val dayTasks = tasksOnDay(tasks, date)
    val overdue = if (date == today) tasks.filter { it.isOverdue(today) }.sortedBy { it.date } else emptyList()
    val done = dayTasks.count { it.isDone(date) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onDate(date.minusDays(1)) }) { Icon(Icons.Default.ChevronLeft, "전날") }
                TextButton(onClick = { pickDate(ctx, date.toString()) { onDate(LocalDate.parse(it)) } }, modifier = Modifier.weight(1f)) {
                    Text(date.format(DATE_FMT), fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = Ink)
                }
                IconButton(onClick = { onDate(date.plusDays(1)) }) { Icon(Icons.Default.ChevronRight, "다음날") }
                if (date != today) TextButton(onClick = { onDate(today) }) { Text("오늘", fontWeight = FontWeight.Bold) }
            }
        }
        item {
            Column {
                Text(if (dayTasks.isEmpty()) "비어 있어요" else "$done / ${dayTasks.size} 완료", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { if (dayTasks.isEmpty()) 0f else done.toFloat() / dayTasks.size },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                )
            }
        }
        if (overdue.isNotEmpty()) {
            item { Text("기한 지난 일", fontWeight = FontWeight.ExtraBold, color = Color(0xFFD9534F), modifier = Modifier.padding(top = 8.dp)) }
            items(overdue, key = { "o:" + it.id }) { t ->
                val d = LocalDate.parse(t.date!!)
                TaskRow(t, d, groups, showDate = true, onToggle = { onToggle(t, d) }, onSub = { sid -> onSub(t, d, sid) }, onClick = { onEdit(t, d) },
                    nudgeable = true, onNudge = { onNudge(t) })
            }
            item { Text("오늘", fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 8.dp)) }
        }
        items(dayTasks, key = { "d:" + it.id }) { t ->
            TaskRow(t, date, groups, showDate = false, onToggle = { onToggle(t, date) }, onSub = { sid -> onSub(t, date, sid) }, onClick = { onEdit(t, date) },
                nudgeable = date == today, onNudge = { onNudge(t) })
        }
    }
}

@Composable
private fun LaterView(tasks: List<Task>, groups: List<Group>, onToggle: (Task) -> Unit, onEdit: (Task) -> Unit) {
    val later = tasks.filter { it.date == null }.sortedBy { it.order }
    val d = LocalDate.now()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 12.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("나중에 할 일 ${later.count { !it.isDone(d) }}개 남음", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp) }
        if (later.isEmpty()) item { Text("날짜를 정하지 않은 할 일이 여기 모여요.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(later, key = { it.id }) { t ->
            TaskRow(t, d, groups, showDate = false, onToggle = { onToggle(t) }, onSub = { }, onClick = { onEdit(t) })
        }
    }
}

@Composable
private fun TaskRow(
    t: Task, d: LocalDate, groups: List<Group>, showDate: Boolean,
    onToggle: () -> Unit, onSub: (String) -> Unit, onClick: () -> Unit,
    nudgeable: Boolean = false, onNudge: () -> Unit = {},
) {
    val done = t.isDone(d)
    val nudgedToday = t.nudges.containsKey(LocalDate.now().toString())
    val g = groups.find { it.id == t.groupId } ?: groups.firstOrNull()
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.Top) {
            Checkbox(checked = done, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f).padding(top = 10.dp, end = 4.dp)) {
                Text(
                    (if (t.priority >= 2) "!! " else if (t.priority == 1) "! " else "") + t.title,
                    fontWeight = FontWeight.Bold, fontSize = 17.sp,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else Ink,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    if (g != null) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(g.color)))
                        Spacer(Modifier.width(5.dp))
                        Text(g.name, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(10.dp))
                    }
                    if (showDate && t.date != null) {
                        Text(LocalDate.parse(t.date).format(DATE_FMT), fontSize = 12.sp, color = Color(0xFFD9534F))
                        Spacer(Modifier.width(10.dp))
                    }
                    if (t.time.isNotEmpty()) {
                        Text(t.time + if (t.remind >= 0) " 🔔" else "", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(10.dp))
                    }
                    if (t.isRep()) Text("↻ " + (REPEAT_LABELS.find { it.first == t.repeat }?.second ?: ""), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                t.subs.forEach { s ->
                    Row(Modifier.clickable { onSub(s.id) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = t.subOn(d, s.id), onCheckedChange = { onSub(s.id) }, modifier = Modifier.size(36.dp))
                        Text(s.t, fontSize = 14.sp)
                    }
                }
            }
            if (nudgeable && !done) {
                TextButton(
                    onClick = onNudge,
                    enabled = !nudgedToday,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                ) { Text(if (nudgedToday) "보냄 ✓" else "👉 콕", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun <T> Picker(label: String, value: T, options: List<Pair<T, String>>, onChange: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(options.find { it.first == value }?.second ?: "", modifier = Modifier.weight(1f), color = Ink)
                Icon(Icons.Default.ArrowDropDown, null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (v, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onChange(v) })
                }
            }
        }
    }
}

@Composable
private fun EditDialog(
    target: EditTarget, groups: List<Group>,
    onSave: (Draft) -> Unit, onDelete: () -> Unit, onSkip: () -> Unit, onDismiss: () -> Unit,
    laterMode: Boolean,
) {
    val ctx = LocalContext.current
    val orig = target.task
    var title by remember { mutableStateOf(orig?.title ?: "") }
    var note by remember { mutableStateOf(orig?.note ?: "") }
    var date by remember { mutableStateOf(if (orig != null) orig.date else if (laterMode) null else target.date.toString()) }
    var time by remember { mutableStateOf(orig?.time ?: "") }
    var groupId by remember { mutableStateOf(orig?.groupId ?: groups.firstOrNull()?.id ?: "g1") }
    var priority by remember { mutableIntStateOf(orig?.priority ?: 0) }
    var repeat by remember { mutableStateOf(orig?.repeat ?: "none") }
    var remind by remember { mutableIntStateOf(orig?.remind ?: -1) }
    val subs = remember { mutableStateListOf<Sub>().also { l -> l.addAll(orig?.subs ?: emptyList()) } }
    var newSub by remember { mutableStateOf("") }
    var confirmDel by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (orig == null) "새 할 일" else "할 일 수정", fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("할 일") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { pickDate(ctx, date) { date = it } }, modifier = Modifier.weight(1f)) {
                        Text(date?.let { LocalDate.parse(it).format(DATE_FMT) } ?: "날짜 없음 (나중에)", color = Ink)
                    }
                    if (date != null) IconButton(onClick = { date = null }) { Icon(Icons.Default.Close, "날짜 지우기") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { pickTime(ctx, time) { time = it } }, modifier = Modifier.weight(1f)) {
                        Text(if (time.isEmpty()) "시간 없음" else time, color = Ink)
                    }
                    if (time.isNotEmpty()) IconButton(onClick = { time = "" }) { Icon(Icons.Default.Close, "시간 지우기") }
                }
                Picker("그룹", groupId, groups.map { it.id to it.name }) { groupId = it }
                Picker("반복", repeat, REPEAT_LABELS) { repeat = it }
                Picker("알림", remind, REMIND_LABELS) { remind = it }
                if (remind >= 0 && (time.isEmpty() || date == null)) {
                    Text("알림은 날짜와 시간을 둘 다 정해야 울려요.", fontSize = 12.sp, color = Color(0xFFD9534F))
                }
                Text("중요도", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "보통", 1 to "중요 !", 2 to "매우 중요 !!").forEach { (v, n) ->
                        FilterChip(selected = priority == v, onClick = { priority = v }, label = { Text(n) })
                    }
                }
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("메모") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                Text("하위 할 일", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                subs.forEachIndexed { i, s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = s.t, onValueChange = { subs[i] = s.copy(t = it) }, singleLine = true, modifier = Modifier.weight(1f))
                        IconButton(onClick = { subs.removeAt(i) }) { Icon(Icons.Default.Close, "삭제") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = newSub, onValueChange = { newSub = it }, placeholder = { Text("하위 할 일 추가") }, singleLine = true, modifier = Modifier.weight(1f))
                    IconButton(onClick = { if (newSub.isNotBlank()) { subs.add(Sub(newId(), newSub.trim())); newSub = "" } }) { Icon(Icons.Default.Add, "추가") }
                }
                if (orig != null) {
                    if (orig.isRep()) {
                        OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                            Text("이 날(${target.date.format(DATE_FMT)})만 건너뛰기")
                        }
                    }
                    TextButton(onClick = { confirmDel = true }) { Text("이 할 일 삭제", color = Color(0xFFD9534F), fontWeight = FontWeight.Bold) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = {
                    val pendingSub = newSub.trim()
                    val finalSubs = subs.filter { it.t.isNotBlank() } + (if (pendingSub.isNotEmpty()) listOf(Sub(newId(), pendingSub)) else emptyList())
                    onSave(Draft(title.trim(), note, date, time, groupId, priority, repeat, remind, finalSubs))
                },
            ) { Text("저장", fontWeight = FontWeight.ExtraBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )

    if (confirmDel) {
        AlertDialog(
            onDismissRequest = { confirmDel = false },
            title = { Text("삭제할까요?") },
            text = { Text(if (orig?.isRep() == true) "반복 일정 전체가 지워지고, 웹에서도 사라져요." else "웹에서도 함께 사라져요.") },
            confirmButton = { TextButton(onClick = { confirmDel = false; onDelete() }) { Text("삭제", color = Color(0xFFD9534F)) } },
            dismissButton = { TextButton(onClick = { confirmDel = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun JoinDialog(onDismiss: () -> Unit, onJoin: (String, (Boolean) -> Unit) -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("공유 달력 참여", fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("웹에서 만든 공유 코드나 링크(…?cal=코드)를 붙여 넣으세요.", fontSize = 14.sp)
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text("코드 또는 링크") })
                TextButton(onClick = {
                    val cm = ctx.getSystemService(ClipboardManager::class.java)
                    val s = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
                    if (!s.isNullOrEmpty()) text = s
                }) { Text("클립보드에서 붙여넣기") }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && text.isNotBlank(), onClick = { busy = true; onJoin(text) { busy = false } }) { Text("참여하기", fontWeight = FontWeight.ExtraBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun CreateDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("내 달력") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("새 달력 만들기", fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("이름") })
                Text("만들면 공유 코드가 생기고, 설정에서 코드를 복사해 웹에서 참여할 수 있어요.", fontSize = 13.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name) }) { Text("만들기", fontWeight = FontWeight.ExtraBold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun SettingsDialog(activeCal: CalRef?, onLeave: (String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var nagOn by remember { mutableStateOf(Store.nagEnabled(ctx)) }
    var nagMin by remember { mutableIntStateOf(Store.nagMinutes(ctx)) }
    var copied by remember { mutableStateOf(false) }
    var updMode by remember { mutableIntStateOf(Store.updateMode(ctx)) }
    var updStatus by remember { mutableStateOf<String?>(null) }
    var updBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var myName by remember { mutableStateOf(Store.myName(ctx)) }
    var lockOn by remember { mutableStateOf(Store.lockOn(ctx)) }
    var lockWp by remember { mutableStateOf(Store.lockWpOn(ctx)) }
    var wpMsg by remember { mutableStateOf<String?>(null) }
    val pickBg = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val ok = LockWallpaper.saveBackground(ctx, uri)
                if (ok) LockWallpaper.update(ctx, true)
                wpMsg = if (ok) "배경 사진을 바꿨어요" else "사진을 불러오지 못했어요"
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("설정", fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("콕 알림에 표시할 내 이름", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = myName,
                    onValueChange = { myName = it; Store.setMyName(ctx, it.trim()) },
                    singleLine = true, placeholder = { Text("예: 찬민") }, modifier = Modifier.fillMaxWidth(),
                )
                Text("할 일 옆 '👉 콕'을 누르면 같은 달력을 쓰는 상대 폰에 알림이 가요. 할 일 하나당 하루에 한 번만 보낼 수 있어요.", fontSize = 12.sp)
                HorizontalDivider()
                Text("잠금 화면 사진에 오늘 할 일", fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = lockWp, onCheckedChange = {
                        lockWp = it; Store.setLockWpOn(ctx, it)
                        if (it) { LockWallpaper.update(ctx, true); wpMsg = "잠금 화면 사진을 바꿨어요. 잠금 화면을 확인해 보세요." }
                    })
                    Spacer(Modifier.width(10.dp))
                    Text(if (lockWp) "켜짐" else "꺼짐")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickBg.launch("image/*") }) { Text("배경 사진 고르기", fontSize = 12.sp) }
                    OutlinedButton(onClick = {
                        LockWallpaper.clearBackground(ctx); LockWallpaper.update(ctx, true); wpMsg = "기본 배경으로 되돌렸어요"
                    }) { Text("기본 배경", fontSize = 12.sp) }
                }
                wpMsg?.let { Text(it, fontSize = 12.sp) }
                Text("켜면 잠금 화면 사진이 오늘 할 일이 그려진 그림으로 바뀌고, 할 일이 바뀔 때마다 새로 그려요. 끄면 더 이상 바꾸지 않아요(원래 사진은 폰의 배경화면 설정에서 다시 고르세요).", fontSize = 12.sp)
                HorizontalDivider()
                Text("잠금 화면 알림으로 오늘 할 일", fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = lockOn, onCheckedChange = {
                        lockOn = it; Store.setLockOn(ctx, it); LockScreen.update(ctx)
                    })
                    Spacer(Modifier.width(10.dp))
                    Text(if (lockOn) "켜짐" else "꺼짐")
                }
                Text("오늘 남은 할 일을 알림으로 계속 띄워 두고, 잠금 화면에서도 보여요. 제목이 가려지면 폰 설정 → 알림 → 잠금 화면 알림을 '모든 내용 표시'로 바꿔 주세요. 보고 있는 달력(위쪽 이름)의 할 일이에요.", fontSize = 12.sp)
                HorizontalDivider()
                Text("홈 화면 달력 위젯", fontWeight = FontWeight.Bold)
                Text("홈 화면 빈 곳을 길게 누르기 → 위젯 → '나비 할 일 주간'(한 주의 날짜별 할 일 제목) 또는 '나비 할 일 달력'(월간)을 추가하세요. 줄을 누르면 그날 할 일이 열리고, ‹ › 로 주·달을 넘길 수 있어요.", fontSize = 12.sp)
                HorizontalDivider()
                Text("못 끝낸 일 알림", fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = nagOn, onCheckedChange = {
                        nagOn = it; Store.setNagEnabled(ctx, it); Scheduler.scheduleNag(ctx)
                    })
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(enabled = nagOn, onClick = {
                        TimePickerDialog(ctx, { _, h, m ->
                            nagMin = h * 60 + m; Store.setNagMinutes(ctx, nagMin); Scheduler.scheduleNag(ctx)
                        }, nagMin / 60, nagMin % 60, true).show()
                    }) { Text("매일 " + fmtMinutes(nagMin)) }
                }
                Text("그날 끝내지 못한 일과 기한이 지난 일을 한 번 알려 줘요.", fontSize = 12.sp)
                HorizontalDivider()
                Text("알람", fontWeight = FontWeight.Bold)
                if (!Scheduler.canExact(ctx)) {
                    Text("정확한 시각에 울리려면 '알람 및 리마인더' 권한이 필요해요.", fontSize = 13.sp, color = Color(0xFFD9534F))
                    OutlinedButton(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            ctx.startActivity(Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }) { Text("권한 설정 열기") }
                }
                OutlinedButton(onClick = {
                    ctx.startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("알림 설정 열기") }
                OutlinedButton(onClick = { Scheduler.testIn(ctx, 5) }) { Text("5초 뒤 테스트 알람") }
                Text("알람이 안 울리면 배터리 설정에서 이 앱을 '제한 없음'으로 두세요.", fontSize = 12.sp)
                if (Updater.ENABLED) {
                HorizontalDivider()
                Text("🔄 자동 업데이트  (지금 v${Updater.currentName()})", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(Updater.MODE_OFF to "끄기", Updater.MODE_WIFI to "Wi-Fi만", Updater.MODE_ANY to "Wi-Fi+데이터").forEach { (m, label) ->
                        FilterChip(
                            selected = updMode == m,
                            onClick = { updMode = m; Store.setUpdateMode(ctx, m); Updater.schedule(ctx) },
                            label = { Text(label, fontSize = 12.sp) },
                        )
                    }
                }
                if (!Updater.canInstall(ctx)) {
                    Text("스스로 업데이트하려면 '이 출처 허용'이 필요해요 (한 번만)", fontSize = 12.sp, color = Color(0xFFD9534F))
                    OutlinedButton(onClick = {
                        ctx.startActivity(
                            Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }) { Text("설치 허용하러 가기") }
                }
                OutlinedButton(
                    enabled = !updBusy,
                    onClick = {
                        updBusy = true; updStatus = "확인 중…"
                        scope.launch { updStatus = Updater.checkAndInstall(ctx); updBusy = false }
                    },
                ) { Text("지금 업데이트 확인") }
                updStatus?.let { Text(it, fontSize = 12.sp) }
                }
                if (activeCal != null) {
                    HorizontalDivider()
                    Text("현재 달력: ${activeCal.name}", fontWeight = FontWeight.Bold)
                    Text("공유 코드", fontSize = 12.sp)
                    Text(activeCal.cid, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = {
                        val cm = ctx.getSystemService(ClipboardManager::class.java)
                        cm?.setPrimaryClip(ClipData.newPlainText("nabi-code", activeCal.cid))
                        copied = true
                    }) { Text(if (copied) "복사했어요" else "코드 복사") }
                    Text("웹에서는 계정 메뉴의 '공유 달력 참여'에 이 코드를 붙여 넣으면 돼요. 코드를 아는 사람은 누구나 보고 고칠 수 있어요.", fontSize = 12.sp)
                    TextButton(onClick = { onLeave(activeCal.cid) }) {
                        Text("이 기기에서 목록에서 빼기", color = Color(0xFFD9534F), fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기", fontWeight = FontWeight.ExtraBold) } },
    )
}
