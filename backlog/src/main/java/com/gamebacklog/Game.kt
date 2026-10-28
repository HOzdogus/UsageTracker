package com.gamebacklog

import java.util.UUID

/** Listed in the order they show up on screen: what you're playing comes first. */
enum class Status(val label: String) {
    PLAYING("Playing"),
    BACKLOG("Backlog"),
    FINISHED("Finished"),
    DROPPED("Dropped"),
}

enum class Priority(val label: String, val weight: Int) {
    HIGH("High", 3),
    MEDIUM("Medium", 2),
    LOW("Low", 1),
}

data class Game(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val platform: String = "",
    val status: Status = Status.BACKLOG,
    val priority: Priority = Priority.MEDIUM,
    /** Rough hours to finish, e.g. from HowLongToBeat. Null when unknown. */
    val hoursToBeat: Int? = null,
    val hoursPlayed: Int = 0,
    /** 1 to 5 stars, only meaningful once you've played it. */
    val rating: Int? = null,
    val notes: String = "",
    val addedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
) {
    /** Hours still to go, or null if we don't know how long it is. */
    val hoursLeft: Int?
        get() = hoursToBeat?.let { (it - hoursPlayed).coerceAtLeast(0) }
}

enum class SortOrder(val label: String) {
    PRIORITY("Priority"),
    RECENT("Recently added"),
    SHORTEST("Shortest first"),
    TITLE("A–Z"),
}

fun List<Game>.sortedFor(order: SortOrder): List<Game> = when (order) {
    SortOrder.PRIORITY -> sortedWith(
        compareBy<Game> { it.priority.ordinal }.thenByDescending { it.addedAt }
    )
    SortOrder.RECENT -> sortedByDescending { it.addedAt }
    // Games with no length go last, since we can't tell how short they are.
    SortOrder.SHORTEST -> sortedWith(compareBy(nullsLast<Int>()) { game: Game -> game.hoursLeft })
    SortOrder.TITLE -> sortedBy { it.title.lowercase() }
}

/**
 * Picks something from the backlog to play next. Higher priority games are more
 * likely to come up, but anything can. Returns null if the backlog is empty.
 */
fun pickNext(games: List<Game>, exclude: String? = null): Game? {
    val pool = games.filter { it.status == Status.BACKLOG && it.id != exclude }
        .ifEmpty { games.filter { it.status == Status.BACKLOG } }
    if (pool.isEmpty()) return null
    var roll = (0 until pool.sumOf { it.priority.weight }).random()
    for (game in pool) {
        roll -= game.priority.weight
        if (roll < 0) return game
    }
    return pool.last()
}

fun formatHours(hours: Int): String = if (hours == 1) "1 hour" else "$hours hours"
