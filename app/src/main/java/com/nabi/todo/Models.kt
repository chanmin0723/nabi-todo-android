package com.nabi.todo

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 웹 앱(index.html)의 할 일 문서와 같은 구조입니다. calendars/{cid}/tasks/{id} */
data class Sub(val id: String, val t: String)

data class Nudge(val from: String, val name: String, val at: Long)

data class Occ(val done: Boolean, val subs: Map<String, Boolean>)

data class Task(
    val id: String,
    val title: String,
    val note: String,
    val date: String?,          // yyyy-MM-dd, 없으면 "나중에"
    val time: String,           // HH:mm 또는 ""
    val groupId: String,
    val priority: Int,          // 0,1,2
    val subs: List<Sub>,
    val repeat: String,         // none, daily, weekdays, weekly, biweekly, monthly, yearly
    val until: String,          // yyyy-MM-dd 또는 ""
    val skip: List<String>,
    val remind: Int,            // -1 없음, 0 정각, 10/30/60 분 전
    val state: Map<String, Occ>, // 날짜별(반복) 또는 "once" 완료 상태
    val order: Double,
    val created: Long,
    val nudges: Map<String, Nudge> = emptyMap(), // 날짜 -> 보낸 알림(하루 한 번)
)

data class Group(val id: String, val name: String, val color: Long)

data class CalRef(val cid: String, val name: String)

val REPEAT_LABELS: List<Pair<String, String>> = listOf(
    "none" to "반복 안 함",
    "daily" to "매일",
    "weekdays" to "평일(월~금)",
    "weekly" to "매주",
    "biweekly" to "2주마다",
    "monthly" to "매월",
    "yearly" to "매년",
)

val REMIND_LABELS: List<Pair<Int, String>> = listOf(
    -1 to "알림 없음",
    0 to "정각",
    10 to "10분 전",
    30 to "30분 전",
    60 to "1시간 전",
)

val DEFAULT_GROUPS: List<Group> = listOf(
    Group("g1", "벽 속 마을", 0xFFF4A259),
    Group("g2", "책장 뒤 서고", 0xFF7AA7F0),
    Group("g3", "부엌 저잣거리", 0xFFF2C94C),
    Group("g4", "달빛 다락", 0xFFA98BEA),
)

fun Task.isRep(): Boolean = repeat.isNotEmpty() && repeat != "none"

fun Task.occKey(d: LocalDate): String = if (isRep()) d.toString() else "once"

fun Task.isDone(d: LocalDate): Boolean = state[occKey(d)]?.done == true

fun Task.subOn(d: LocalDate, sid: String): Boolean = state[occKey(d)]?.subs?.get(sid) == true

/** 웹의 occursOn()과 같은 규칙 */
fun Task.occursOn(d: LocalDate): Boolean {
    val start = date ?: return false
    val ds = d.toString()
    if (!isRep()) return start == ds
    if (ds < start || (until.isNotEmpty() && ds > until) || skip.contains(ds)) return false
    val a = try {
        LocalDate.parse(start)
    } catch (e: Exception) {
        return false
    }
    return when (repeat) {
        "daily" -> true
        "weekdays" -> d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY
        "weekly" -> a.dayOfWeek == d.dayOfWeek
        "biweekly" -> a.dayOfWeek == d.dayOfWeek && ChronoUnit.DAYS.between(a, d) % 14L == 0L
        "monthly" -> a.dayOfMonth == d.dayOfMonth ||
            (a.dayOfMonth > d.lengthOfMonth() && d.dayOfMonth == d.lengthOfMonth())
        "yearly" -> a.monthValue == d.monthValue &&
            (a.dayOfMonth == d.dayOfMonth ||
                (a.monthValue == 2 && a.dayOfMonth == 29 && d.dayOfMonth == 28 && d.lengthOfMonth() == 28))
        else -> false
    }
}

/** 지난 날짜의 반복 없는 미완료 일정(웹의 "기한 지난 일") */
fun Task.isOverdue(today: LocalDate): Boolean {
    val dt = date ?: return false
    return !isRep() && dt < today.toString() && !isDone(LocalDate.parse(dt))
}

fun tasksOnDay(all: List<Task>, d: LocalDate): List<Task> =
    all.filter { it.occursOn(d) }.sortedWith(
        compareBy<Task>({ it.isDone(d) }, { if (it.time.isEmpty()) "99:99" else it.time }, { it.order })
    )

// ---------- Map <-> Task ----------

private fun numOf(v: Any?, def: Double): Double = (v as? Number)?.toDouble() ?: def

fun taskFromMap(id: String, m: Map<String, Any?>): Task {
    val subs = ArrayList<Sub>()
    (m["subs"] as? List<*>)?.forEach { e ->
        val mm = e as? Map<*, *>
        val sid = mm?.get("id") as? String
        if (mm != null && sid != null) subs.add(Sub(sid, (mm["t"] as? String) ?: ""))
    }
    val skip = ArrayList<String>()
    (m["skip"] as? List<*>)?.forEach { e -> if (e is String) skip.add(e) }
    val state = HashMap<String, Occ>()
    (m["state"] as? Map<*, *>)?.forEach { (k, v) ->
        val ks = k as? String
        val vm = v as? Map<*, *>
        if (ks != null && vm != null) {
            val sm = HashMap<String, Boolean>()
            (vm["subs"] as? Map<*, *>)?.forEach { (sk, sv) ->
                if (sk is String && sv is Boolean) sm[sk] = sv
            }
            state[ks] = Occ(vm["done"] == true, sm)
        }
    }
    val nudges = HashMap<String, Nudge>()
    (m["nudges"] as? Map<*, *>)?.forEach { (k, v) ->
        val ks = k as? String
        val vm = v as? Map<*, *>
        if (ks != null && vm != null) {
            nudges[ks] = Nudge((vm["from"] as? String) ?: "", (vm["name"] as? String) ?: "", numOf(vm["at"], 0.0).toLong())
        }
    }
    return Task(
        id = id,
        title = (m["title"] as? String) ?: "",
        note = (m["note"] as? String) ?: "",
        date = (m["date"] as? String)?.takeIf { it.isNotEmpty() },
        time = (m["time"] as? String) ?: "",
        groupId = (m["groupId"] as? String) ?: "g1",
        priority = numOf(m["priority"], 0.0).toInt(),
        subs = subs,
        repeat = (m["repeat"] as? String) ?: "none",
        until = (m["until"] as? String) ?: "",
        skip = skip,
        remind = numOf(m["remind"], -1.0).toInt(),
        state = state,
        order = numOf(m["order"], 0.0),
        created = numOf(m["created"], 0.0).toLong(),
        nudges = nudges,
    )
}

fun subsToList(subs: List<Sub>): List<Map<String, Any?>> = subs.map { mapOf("id" to it.id, "t" to it.t) }

fun occToMap(o: Occ): Map<String, Any?> = mapOf("done" to o.done, "subs" to o.subs)

fun taskToMap(t: Task): Map<String, Any?> = mapOf(
    "title" to t.title,
    "note" to t.note,
    "date" to t.date,
    "time" to t.time,
    "groupId" to t.groupId,
    "priority" to t.priority,
    "subs" to subsToList(t.subs),
    "repeat" to t.repeat,
    "until" to t.until,
    "skip" to t.skip,
    "remind" to t.remind,
    "state" to t.state.mapValues { occToMap(it.value) },
    "order" to t.order,
    "created" to t.created,
)

// ---------- 기기 안 캐시(JSON) : 알람 수신기가 앱이 꺼져 있어도 읽습니다 ----------

fun tasksToJson(tasks: List<Task>): String {
    val arr = JSONArray()
    tasks.forEach { t ->
        val o = JSONObject(taskToMap(t))
        o.put("id", t.id)
        arr.put(o)
    }
    return arr.toString()
}

private fun jsonToAny(v: Any?): Any? = when (v) {
    JSONObject.NULL -> null
    is JSONObject -> {
        val out = HashMap<String, Any?>()
        val it = v.keys()
        while (it.hasNext()) {
            val k = it.next()
            out[k] = jsonToAny(v.opt(k))
        }
        out
    }
    is JSONArray -> {
        val out = ArrayList<Any?>()
        for (i in 0 until v.length()) out.add(jsonToAny(v.opt(i)))
        out
    }
    else -> v
}

fun tasksFromJson(s: String?): List<Task> {
    if (s.isNullOrEmpty()) return emptyList()
    return try {
        val arr = JSONArray(s)
        val out = ArrayList<Task>()
        for (i in 0 until arr.length()) {
            @Suppress("UNCHECKED_CAST")
            val m = jsonToAny(arr.getJSONObject(i)) as? Map<String, Any?> ?: continue
            val id = m["id"] as? String ?: continue
            out.add(taskFromMap(id, m))
        }
        out
    } catch (e: Exception) {
        emptyList()
    }
}

fun groupsFromAny(v: Any?): List<Group> {
    val out = ArrayList<Group>()
    (v as? List<*>)?.forEach { e ->
        val m = e as? Map<*, *>
        val id = m?.get("id") as? String
        if (m != null && id != null) {
            val hex = (m["color"] as? String) ?: "#F4A259"
            val c = try {
                0xFF000000L or hex.removePrefix("#").toLong(16)
            } catch (ex: Exception) {
                0xFFF4A259L
            }
            out.add(Group(id, (m["name"] as? String) ?: "", c))
        }
    }
    return if (out.isEmpty()) DEFAULT_GROUPS else out
}

private const val CODE_CHARS = "abcdefghijkmnpqrstuvwxyz23456789"

fun genCode(): String {
    val r = java.security.SecureRandom()
    val sb = StringBuilder()
    repeat(24) { sb.append(CODE_CHARS[r.nextInt(CODE_CHARS.length)]) }
    return sb.toString()
}

/** 웹의 normCode(): 코드 또는 ?cal=코드 링크에서 코드를 뽑습니다 */
fun normCode(raw: String): String {
    var v = raw.trim()
    val m = Regex("[?&]cal=([A-Za-z0-9]+)").find(v)
    if (m != null) v = m.groupValues[1]
    v = v.lowercase().replace(Regex("[^a-z0-9]"), "")
    return if (v.length in 16..64) v else ""
}

fun newId(): String {
    val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
    val r = java.security.SecureRandom()
    val sb = StringBuilder()
    repeat(6) { sb.append(chars[r.nextInt(chars.length)]) }
    sb.append(java.lang.Long.toString(System.currentTimeMillis(), 36).takeLast(3))
    return sb.toString()
}
