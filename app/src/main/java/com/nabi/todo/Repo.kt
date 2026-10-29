package com.nabi.todo

import com.google.android.gms.tasks.Task as GmsTask
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
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
