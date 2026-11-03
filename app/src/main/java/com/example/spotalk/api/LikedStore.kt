package com.example.spotalk.api

import com.example.spotalk.log.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** שיר מ"שירים שאהבתי" + מתי הוא נוסף (כדי לזהות מה חדש) */
data class LikedEntry(val item: Item, val addedAt: String) {
    val key: String get() = "${item.uri}|$addedAt"
}

/**
 * מטמון קבוע (בקובץ) של "שירים שאהבתי".
 * נשמר גם אחרי סגירת האפליקציה — כך שלא צריך להוריד את כל הרשימה בכל פעם.
 */
class LikedStore(dir: File) {

    private val file = File(dir, "liked_cache.json")

    fun load(): List<LikedEntry>? = try {
        if (!file.exists()) null else {
            val root = JSONObject(file.readText())
            // גרסה 3: שמות בעברית — מטמון ישן נבנה מחדש פעם אחת
            if (root.optInt("v", 1) < 3) null else {
                val arr = root.getJSONArray("items")
                (0 until arr.length()).map { i -> fromJson(arr.getJSONObject(i)) }
            }
        }
    } catch (e: Exception) {
        AppLog.w("LikedStore", "קריאת המטמון נכשלה — יבנה מחדש", e)
        null
    }

    fun save(entries: List<LikedEntry>) {
        try {
            val arr = JSONArray()
            entries.forEach { arr.put(toJson(it)) }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(JSONObject().put("v", 3).put("items", arr).toString())
            tmp.renameTo(file)
        } catch (e: Exception) {
            AppLog.w("LikedStore", "שמירת המטמון נכשלה", e)
        }
    }

    fun clear() {
        file.delete()
    }

    private fun toJson(e: LikedEntry) = JSONObject()
        .put("id", e.item.id)
        .put("uri", e.item.uri)
        .put("title", e.item.title)
        .put("subtitle", e.item.subtitle)
        .put("duration", e.item.durationMs)
        .put("explicit", e.item.explicit)
        .put("artistIds", JSONArray(e.item.artistIds))
        .put("album", e.item.albumName)
        .put("image", e.item.imageUrl ?: "")
        .put("addedAt", e.addedAt)

    private fun fromJson(o: JSONObject): LikedEntry {
        val ids = o.optJSONArray("artistIds") ?: JSONArray()
        return LikedEntry(
            item = Item(
                id = o.optString("id"),
                uri = o.optString("uri"),
                type = ItemType.TRACK,
                title = o.optString("title"),
                subtitle = o.optString("subtitle"),
                durationMs = o.optLong("duration"),
                explicit = o.optBoolean("explicit"),
                artistIds = (0 until ids.length()).map { ids.optString(it) },
                albumName = o.optString("album"),
                imageUrl = o.optString("image").ifBlank { null }
            ),
            addedAt = o.optString("addedAt")
        )
    }
}
