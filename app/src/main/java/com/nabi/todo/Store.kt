package com.nabi.todo

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** 기기 안에 저장하는 것: 참여한 달력 목록, 설정, 알람용 할 일 캐시 */
object Store {
    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("nabi", Context.MODE_PRIVATE)

    fun cals(ctx: Context): List<CalRef> {
        val s = sp(ctx).getString("cals", null) ?: return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                CalRef(o.getString("cid"), o.optString("name", "공유 달력"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveCals(ctx: Context, list: List<CalRef>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("cid", it.cid).put("name", it.name)) }
        sp(ctx).edit().putString("cals", arr.toString()).apply()
    }

    fun active(ctx: Context): String? = sp(ctx).getString("active", null)
    fun setActive(ctx: Context, cid: String?) = sp(ctx).edit().putString("active", cid).apply()

    fun nagEnabled(ctx: Context): Boolean = sp(ctx).getBoolean("nagOn", true)
    fun setNagEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("nagOn", v).apply()
    fun nagMinutes(ctx: Context): Int = sp(ctx).getInt("nagMin", 21 * 60) // 하루 중 분 단위
    fun setNagMinutes(ctx: Context, v: Int) = sp(ctx).edit().putInt("nagMin", v).apply()

    /** 자동 업데이트: 0 끄기, 1 Wi-Fi에서만(기본), 2 Wi-Fi+데이터 */
    fun updateMode(ctx: Context): Int = sp(ctx).getInt("updMode", 1)
    fun setUpdateMode(ctx: Context, v: Int) = sp(ctx).edit().putInt("updMode", v).apply()
    fun lastUpdateCheck(ctx: Context): Long = sp(ctx).getLong("updLast", 0L)
    fun setLastUpdateCheck(ctx: Context, v: Long) = sp(ctx).edit().putLong("updLast", v).apply()

    /** 이 기기를 구분하는 값(내가 보낸 알림이 내 폰에 다시 뜨지 않게) */
    fun deviceId(ctx: Context): String {
        val p = sp(ctx)
        var id = p.getString("deviceId", null)
        if (id == null) {
            id = newId() + newId()
            p.edit().putString("deviceId", id).apply()
        }
        return id
    }
    fun myName(ctx: Context): String = sp(ctx).getString("myName", "") ?: ""
    fun setMyName(ctx: Context, v: String) = sp(ctx).edit().putString("myName", v).apply()
    fun nudgeSeen(ctx: Context): Set<String> = sp(ctx).getStringSet("nudgeSeen", emptySet()) ?: emptySet()
    fun setNudgeSeen(ctx: Context, s: Set<String>) = sp(ctx).edit().putStringSet("nudgeSeen", HashSet(s)).apply()
    fun lastNudgeSeen(ctx: Context, cid: String): Long = sp(ctx).getLong("lastNudge_$cid", 0L)
    fun setLastNudgeSeen(ctx: Context, cid: String, v: Long) = sp(ctx).edit().putLong("lastNudge_$cid", v).apply()

    fun lockOn(ctx: Context): Boolean = sp(ctx).getBoolean("lockOn", false)
    fun setLockOn(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("lockOn", v).apply()
    /** 위젯이 보여 주는 달: 이번 달 기준 +- 개월 */
    fun widgetMonth(ctx: Context): Int = sp(ctx).getInt("wMonth", 0)
    fun setWidgetMonth(ctx: Context, v: Int) = sp(ctx).edit().putInt("wMonth", v).apply()

    fun loadTasks(ctx: Context, cid: String): List<Task> = tasksFromJson(sp(ctx).getString("tasks_$cid", null))
    fun saveTasks(ctx: Context, cid: String, tasks: List<Task>) =
        sp(ctx).edit().putString("tasks_$cid", tasksToJson(tasks)).apply()

    fun dropCal(ctx: Context, cid: String) = sp(ctx).edit().remove("tasks_$cid").apply()

    fun scheduled(ctx: Context): Set<String> = sp(ctx).getStringSet("sched", emptySet()) ?: emptySet()
    fun setScheduled(ctx: Context, s: Set<String>) = sp(ctx).edit().putStringSet("sched", HashSet(s)).apply()
}
