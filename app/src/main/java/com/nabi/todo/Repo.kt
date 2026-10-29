package com.nabi.todo

import com.google.android.gms.tasks.Task as GmsTask
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.Transaction
import java.util.concurrent.TimeUnit

/** 웹과 같은 Firestore 구조: calendars/{cid} 와 calendars/{cid}/tasks/{taskId} */
object Repo {
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()

    private fun calDoc(cid: String) = db.collection("calendars").document(cid)
    private fun taskCol(cid: String) = calDoc(cid).collection("tasks")

    fun getCal(cid: String): GmsTask<DocumentSnapshot> = calDoc(cid).get()

    fun createCal(cid: String, name: String): GmsTask<Void> {
        val groups = DEFAULT_GROUPS.map {
            mapOf(
                "id" to it.id, "name" to it.name,
                "color" to String.format("#%06X", it.color and 0xFFFFFFL),
                "icon" to "cat",
            )
        }
        val data = mapOf<String, Any?>(
            "name" to name, "groups" to groups, "created" to System.currentTimeMillis(), "v" to 1,
        )
        return calDoc(cid).set(data, SetOptions.merge())
    }

    fun listenCal(cid: String, cb: (name: String?, groups: List<Group>, exists: Boolean) -> Unit): ListenerRegistration =
        calDoc(cid).addSnapshotListener { snap, _ ->
            if (snap == null) return@addSnapshotListener
            val d = snap.data
            cb(d?.get("name") as? String, groupsFromAny(d?.get("groups")), snap.exists())
        }

    fun listenTasks(
        cid: String,
        cb: (tasks: List<Task>, fromCache: Boolean) -> Unit,
        err: (String) -> Unit,
    ): ListenerRegistration =
        taskCol(cid).addSnapshotListener { snap, e ->
            if (e != null) {
                err(e.code.name)
                return@addSnapshotListener
            }
            if (snap == null) return@addSnapshotListener
            val list = snap.documents.map { doc ->
                taskFromMap(doc.id, doc.data ?: emptyMap())
            }
            cb(list, snap.metadata.isFromCache)
        }

    /** 필드 단위 병합 쓰기: 웹이 다른 필드를 동시에 고쳐도 서로 덮어쓰지 않습니다 */
    fun patch(cid: String, taskId: String, data: Map<String, Any?>): GmsTask<Void> =
        taskCol(cid).document(taskId).set(data, SetOptions.merge())

    fun delete(cid: String, taskId: String): GmsTask<Void> = taskCol(cid).document(taskId).delete()

    /**
     * 콕 찌르기: 할 일 문서의 nudges.{날짜}에 기록. 트랜잭션이라 같은 날 두 번은 들어가지 않는다.
     * 달력 문서의 lastNudge도 함께 올려서, 상대 폰이 문서 하나만 읽고 새 알림 여부를 알 수 있다.
     * @return true = 보냄, false = 오늘 이미 보낸 할 일
     */
    fun sendNudge(cid: String, taskId: String, day: String, rec: Map<String, Any?>): GmsTask<Boolean> {
        val tref = taskCol(cid).document(taskId)
        val cref = calDoc(cid)
        return db.runTransaction(Transaction.Function<Boolean> { tr ->
            val snap = tr.get(tref)
            if (!snap.exists()) throw IllegalStateException("missing")
            val cur = snap.get("nudges") as? Map<*, *>
            if (cur != null && cur.containsKey(day)) {
                false
            } else {
                tr.set(tref, mapOf("nudges" to mapOf(day to rec)), SetOptions.merge())
                tr.set(cref, mapOf("lastNudge" to rec["at"]), SetOptions.merge())
                true
            }
        })
    }

    /** 백그라운드 워커용(블로킹): 달력 문서 1개만 읽어 lastNudge 확인 */
    fun lastNudgeBlocking(cid: String): Long? {
        return try {
            val snap = Tasks.await(calDoc(cid).get(Source.SERVER), 20, TimeUnit.SECONDS)
            if (!snap.exists()) 0L else (snap.get("lastNudge") as? Number)?.toLong() ?: 0L
        } catch (e: Exception) {
            null
        }
    }

    /** 백그라운드 워커용(블로킹). 서버 우선, 실패하면 캐시 */
    fun fetchTasksBlocking(cid: String): List<Task>? {
        return try {
            val snap = try {
                Tasks.await(taskCol(cid).get(Source.SERVER), 25, TimeUnit.SECONDS)
            } catch (e: Exception) {
                Tasks.await(taskCol(cid).get(Source.CACHE), 10, TimeUnit.SECONDS)
            }
            snap.documents.map { taskFromMap(it.id, it.data ?: emptyMap()) }
        } catch (e: Exception) {
            null
        }
    }
}
