package com.nabi.todo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

/** 화면에서 편집하는 값 묶음 */
data class Draft(
    val title: String,
    val note: String,
    val date: String?,
    val time: String,
    val groupId: String,
    val priority: Int,
    val repeat: String,
    val remind: Int,
    val subs: List<Sub>,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()

    private val _cals = MutableStateFlow<List<CalRef>>(emptyList())
    val cals: StateFlow<List<CalRef>> = _cals.asStateFlow()
    private val _active = MutableStateFlow<String?>(null)
    val active: StateFlow<String?> = _active.asStateFlow()
    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()
    private val _groups = MutableStateFlow(DEFAULT_GROUPS)
    val groups: StateFlow<List<Group>> = _groups.asStateFlow()
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()
    private val _date = MutableStateFlow(LocalDate.now())
    val date: StateFlow<LocalDate> = _date.asStateFlow()
    private val _msg = MutableStateFlow<String?>(null)
    val msg: StateFlow<String?> = _msg.asStateFlow()

    private val _tabReset = MutableStateFlow(0)
    val tabReset: StateFlow<Int> = _tabReset.asStateFlow()

    private var regCal: ListenerRegistration? = null
    private var regTasks: ListenerRegistration? = null

    init {
        _cals.value = Store.cals(ctx)
        val a = Store.active(ctx)
        val start = if (_cals.value.any { it.cid == a }) a else _cals.value.firstOrNull()?.cid
        select(start)
    }

    fun toast(s: String?) { _msg.value = s }
    fun setDate(d: LocalDate) { _date.value = d }
    /** 위젯에서 날짜를 눌러 들어왔을 때: 그 날짜의 일 보기로 */
    fun openDay(d: LocalDate) { _date.value = d; _tabReset.value = _tabReset.value + 1 }
    fun refreshToday() { if (_date.value.isBefore(LocalDate.now().minusDays(400))) _date.value = LocalDate.now() }

    fun select(cid: String?) {
        regCal?.remove(); regTasks?.remove()
        regCal = null; regTasks = null
        _active.value = cid
        Store.setActive(ctx, cid)
        _groups.value = DEFAULT_GROUPS
        if (cid == null) {
            _tasks.value = emptyList()
            _status.value = ""
            return
        }
        _tasks.value = Store.loadTasks(ctx, cid) // 먼저 기기 캐시를 보여 주고 서버가 오면 갱신
        NabiWidget.refresh(ctx)
        NabiWeekWidget.refresh(ctx)
        LockScreen.update(ctx)
        LockWallpaper.update(ctx)
        _status.value = "연결 중…"
        regCal = Repo.listenCal(cid) { name, groups, exists ->
            if (exists) {
                _groups.value = groups
                if (name != null && _cals.value.any { it.cid == cid && it.name != name }) {
                    val list = _cals.value.map { if (it.cid == cid) it.copy(name = name) else it }
                    _cals.value = list
                    Store.saveCals(ctx, list)
                }
            }
        }
        regTasks = Repo.listenTasks(cid, { list, fromCache ->
            if (fromCache && list.isEmpty()) {
                _status.value = "연결 중…" // 빈 캐시로 저장된 내용과 알람을 지우지 않음
                return@listenTasks
            }
            _tasks.value = list
            Store.saveTasks(ctx, cid, list)
            Nudges.processIncoming(ctx, cid, list)
            Scheduler.rescheduleAll(ctx)
            _status.value = if (fromCache) "오프라인 · 저장된 내용 표시 중" else "실시간 동기화"
        }, { code ->
            _status.value = if (code == "PERMISSION_DENIED") "Firestore 규칙이 막고 있어요" else "연결 오류 ($code)"
        })
    }

    private fun addCal(cid: String, name: String) {
        val list = _cals.value.filter { it.cid != cid } + CalRef(cid, name)
        _cals.value = list
        Store.saveCals(ctx, list)
        select(cid)
    }

    fun join(raw: String, done: (Boolean) -> Unit) {
        val cid = normCode(raw)
        if (cid.isEmpty()) { toast("올바른 코드나 링크를 붙여 넣어 주세요."); done(false); return }
        if (_cals.value.any { it.cid == cid }) { select(cid); toast("이미 참여 중인 달력이에요."); done(true); return }
        Repo.getCal(cid).addOnSuccessListener { snap ->
            if (!snap.exists()) { toast("이 코드의 달력을 찾을 수 없어요."); done(false) }
            else { addCal(cid, (snap.getString("name") ?: "공유 달력")); toast("달력에 참여했어요."); done(true) }
        }.addOnFailureListener {
            toast("연결할 수 없어요. 인터넷과 Firestore 규칙을 확인해 주세요."); done(false)
        }
    }

    fun create(name: String, done: (String?) -> Unit) {
        val cid = genCode()
        val nm = name.ifBlank { "내 달력" }
        Repo.createCal(cid, nm).addOnSuccessListener {
            addCal(cid, nm); done(cid)
        }.addOnFailureListener {
            toast("만들지 못했어요. 인터넷과 Firestore 규칙을 확인해 주세요."); done(null)
        }
    }

    /** 이 기기에서만 목록에서 뺍니다(클라우드 데이터는 그대로) */
    fun leave(cid: String) {
        val list = _cals.value.filter { it.cid != cid }
        _cals.value = list
        Store.saveCals(ctx, list)
        Store.dropCal(ctx, cid)
        if (_active.value == cid) select(list.firstOrNull()?.cid)
        Scheduler.rescheduleAll(ctx)
    }

    private fun write(cid: String, id: String, data: Map<String, Any?>) {
        Repo.patch(cid, id, data).addOnFailureListener { toast("저장에 실패했어요. 연결되면 다시 시도돼요.") }
    }

    fun saveTask(orig: Task?, d: Draft) {
        val cid = _active.value ?: return
        if (orig == null) {
            val order = (_tasks.value.maxOfOrNull { it.order } ?: 0.0) + 1
            val t = Task(
                id = newId(), title = d.title, note = d.note, date = d.date, time = d.time, groupId = d.groupId,
                priority = d.priority, subs = d.subs, repeat = d.repeat, until = "", skip = emptyList(),
                remind = d.remind, state = emptyMap(), order = order, created = System.currentTimeMillis(),
            )
            write(cid, t.id, taskToMap(t))
            return
        }
        val p = HashMap<String, Any?>()
        if (d.title != orig.title) p["title"] = d.title
        if (d.note != orig.note) p["note"] = d.note
        if (d.date != orig.date) p["date"] = d.date
        if (d.time != orig.time) p["time"] = d.time
        if (d.groupId != orig.groupId) p["groupId"] = d.groupId
        if (d.priority != orig.priority) p["priority"] = d.priority
        if (d.repeat != orig.repeat) p["repeat"] = d.repeat
        if (d.remind != orig.remind) p["remind"] = d.remind
        if (d.subs != orig.subs) p["subs"] = subsToList(d.subs)
        if (p.isNotEmpty()) write(cid, orig.id, p)
    }

    /** 이 할 일을 하라고 상대에게 알림 보내기(할 일당 하루 한 번) */
    fun nudge(t: Task) {
        val cid = _active.value ?: return
        val day = LocalDate.now().toString()
        if (t.nudges.containsKey(day)) { toast("오늘은 이미 보낸 할 일이에요."); return }
        val rec = mapOf<String, Any?>(
            "from" to Store.deviceId(ctx),
            "name" to Store.myName(ctx).ifBlank { "친구" },
            "at" to System.currentTimeMillis(),
        )
        Repo.sendNudge(cid, t.id, day, rec)
            .addOnSuccessListener { sent ->
                toast(if (sent) "'${t.title}' 알림을 보냈어요 🐾" else "오늘은 이미 보낸 할 일이에요. (하루에 한 번만 보낼 수 있어요)")
            }
            .addOnFailureListener { toast("보내지 못했어요. 인터넷 연결을 확인해 주세요.") }
    }

    fun toggle(t: Task, d: LocalDate) {
        val cid = _active.value ?: return
        val key = t.occKey(d)
        val old = t.state[key]
        val occ = Occ(!(old?.done ?: false), old?.subs ?: emptyMap())
        write(cid, t.id, mapOf("state" to mapOf(key to occToMap(occ))))
    }

    fun toggleSub(t: Task, d: LocalDate, sid: String) {
        val cid = _active.value ?: return
        val key = t.occKey(d)
        val old = t.state[key]
        val subs = HashMap<String, Boolean>(old?.subs ?: emptyMap())
        subs[sid] = !(subs[sid] ?: false)
        val all = t.subs.isNotEmpty() && t.subs.all { subs[it.id] == true }
        write(cid, t.id, mapOf("state" to mapOf(key to occToMap(Occ(all, subs)))))
    }

    fun delete(t: Task) {
        val cid = _active.value ?: return
        Repo.delete(cid, t.id).addOnFailureListener { toast("삭제에 실패했어요.") }
    }

    fun skipDay(t: Task, d: LocalDate) {
        val cid = _active.value ?: return
        write(cid, t.id, mapOf("skip" to (t.skip + d.toString()).distinct()))
    }

    override fun onCleared() {
        regCal?.remove(); regTasks?.remove()
    }
}
