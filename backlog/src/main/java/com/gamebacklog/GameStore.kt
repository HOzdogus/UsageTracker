package com.gamebacklog

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Keeps the game list in a small JSON file in the app's private storage. */
class GameStore(context: Context) {

    private val file = AtomicFile(File(context.filesDir, "games.json"))

    fun load(): List<Game> {
        val text = try {
            file.readFully().decodeToString()
        } catch (_: Exception) {
            return emptyList()
        }
        return try {
            val array = JSONArray(text)
            (0 until array.length()).mapNotNull { i -> fromJson(array.getJSONObject(i)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(games: List<Game>) {
        val array = JSONArray()
        games.forEach { array.put(toJson(it)) }
        val out = file.startWrite()
        try {
            out.write(array.toString().encodeToByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    private fun toJson(g: Game) = JSONObject().apply {
        put("id", g.id)
        put("title", g.title)
        put("platform", g.platform)
        put("status", g.status.name)
        put("priority", g.priority.name)
        g.hoursToBeat?.let { put("hoursToBeat", it) }
        put("hoursPlayed", g.hoursPlayed)
        g.rating?.let { put("rating", it) }
        put("notes", g.notes)
        put("addedAt", g.addedAt)
        g.finishedAt?.let { put("finishedAt", it) }
    }

    private fun fromJson(o: JSONObject): Game? {
        val title = o.optString("title").ifBlank { return null }
        return Game(
            id = o.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
            title = title,
            platform = o.optString("platform"),
            status = enumOrDefault(o.optString("status"), Status.BACKLOG),
            priority = enumOrDefault(o.optString("priority"), Priority.MEDIUM),
            hoursToBeat = if (o.has("hoursToBeat")) o.getInt("hoursToBeat") else null,
            hoursPlayed = o.optInt("hoursPlayed", 0),
            rating = if (o.has("rating")) o.getInt("rating") else null,
            notes = o.optString("notes"),
            addedAt = o.optLong("addedAt", System.currentTimeMillis()),
            finishedAt = if (o.has("finishedAt")) o.getLong("finishedAt") else null,
        )
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default
}
