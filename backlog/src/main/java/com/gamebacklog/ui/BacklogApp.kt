@file:OptIn(ExperimentalMaterial3Api::class)

package com.gamebacklog.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamebacklog.Game
import com.gamebacklog.GameStore
import com.gamebacklog.Priority
import com.gamebacklog.SortOrder
import com.gamebacklog.Status
import com.gamebacklog.formatHours
import com.gamebacklog.pickNext
import com.gamebacklog.sortedFor

private val Bg = Color(0xFF0F1115)
private val Card = Color(0xFF181C24)
private val Accent = Color(0xFFB79CFF)
private val Muted = Color(0xFF8A93A6)
private val Track = Color(0xFF262B36)
private val Green = Color(0xFF7CE0B8)
private val Gold = Color(0xFFFFD27C)
private val Red = Color(0xFFFF8A8A)

private val colors = darkColorScheme(
    background = Bg, surface = Card, primary = Accent, onPrimary = Bg,
    onBackground = Color.White, onSurface = Color.White,
    surfaceContainer = Card, surfaceContainerHigh = Card,
)

private val DefaultPlatforms = listOf("PC", "PS5", "Switch", "Xbox", "Steam Deck")

private fun statusColor(status: Status) = when (status) {
    Status.PLAYING -> Green
    Status.BACKLOG -> Accent
    Status.FINISHED -> Gold
    Status.DROPPED -> Muted
}

private fun priorityColor(priority: Priority) = when (priority) {
    Priority.HIGH -> Red
    Priority.MEDIUM -> Gold
    Priority.LOW -> Muted
}

/** Moves a game to a new status, stamping when it was finished. */
private fun Game.withStatus(status: Status): Game = copy(
    status = status,
    finishedAt = when {
        status != Status.FINISHED -> null
        finishedAt != null -> finishedAt
        else -> System.currentTimeMillis()
    },
)

private const val NEW_GAME = "new"

@Composable
fun BacklogApp(store: GameStore) {
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            var games by remember { mutableStateOf(store.load()) }
            // Id of the game being edited, NEW_GAME when adding one, null on the list.
            var editingId by rememberSaveable { mutableStateOf<String?>(null) }
            var filter by rememberSaveable { mutableStateOf<Status?>(null) }
            var sort by rememberSaveable { mutableStateOf(SortOrder.PRIORITY) }
            var query by rememberSaveable { mutableStateOf("") }

            fun update(newGames: List<Game>) {
                games = newGames
                store.save(newGames)
            }

            val editing = games.find { it.id == editingId }

            // The phone's back gesture closes the form instead of the app.
            BackHandler(enabled = editingId != null) { editingId = null }

            if (editingId == NEW_GAME || editing != null) {
                EditGameScreen(
                    game = editing,
                    platforms = (games.map { it.platform }.filter { it.isNotBlank() } +
                        DefaultPlatforms).distinctBy { it.lowercase() },
                    onSave = { saved ->
                        update(
                            if (editing == null) games + saved
                            else games.map { if (it.id == saved.id) saved else it }
                        )
                        editingId = null
                    },
                    onDelete = {
                        update(games.filterNot { it.id == editingId })
                        editingId = null
                    },
                    onBack = { editingId = null },
                )
            } else {
                ListScreen(
                    games = games,
                    filter = filter,
                    sort = sort,
                    query = query,
                    onFilter = { filter = it },
                    onSort = { sort = it },
                    onQuery = { query = it },
                    onAdd = { editingId = NEW_GAME },
                    onOpen = { editingId = it.id },
                    onStatus = { game, status ->
                        update(games.map { if (it.id == game.id) it.withStatus(status) else it })
                    },
                )
            }
        }
    }
}

@Composable
private fun ListScreen(
    games: List<Game>,
    filter: Status?,
    sort: SortOrder,
    query: String,
    onFilter: (Status?) -> Unit,
    onSort: (SortOrder) -> Unit,
    onQuery: (String) -> Unit,
    onAdd: () -> Unit,
    onOpen: (Game) -> Unit,
    onStatus: (Game, Status) -> Unit,
) {
    var picked by remember { mutableStateOf<Game?>(null) }

    val shown = games
        .filter { filter == null || it.status == filter }
        .filter {
            query.isBlank() ||
                it.title.contains(query.trim(), ignoreCase = true) ||
                it.platform.contains(query.trim(), ignoreCase = true)
        }
        .sortedFor(sort)
        // With every status showing, keep them grouped: playing, backlog, finished, dropped.
        .let { list -> if (filter == null) list.sortedBy { it.status.ordinal } else list }

    Scaffold(
        containerColor = Bg,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                containerColor = Accent,
                contentColor = Bg,
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Add game", fontWeight = FontWeight.SemiBold) },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Backlog", Modifier.weight(1f).padding(start = 4.dp),
                        fontSize = 26.sp, fontWeight = FontWeight.Bold,
                    )
                    SortMenu(sort, onSort)
                }
            }

            item {
                SummaryCard(games, onPick = { picked = pickNext(games) })
            }

            if (games.isEmpty()) {
                item {
                    Text(
                        "Nothing here yet. Tap \"Add game\" to start your backlog.",
                        color = Muted, modifier = Modifier.padding(vertical = 24.dp, horizontal = 4.dp),
                    )
                }
                return@LazyColumn
            }

            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQuery,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search by title or platform") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { onQuery("") }) {
                                Icon(Icons.Filled.Close, "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors(),
                )
            }

            item {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Pill("All (${games.size})", selected = filter == null, color = Accent) {
                        onFilter(null)
                    }
                    Status.entries.forEach { status ->
                        val count = games.count { it.status == status }
                        Pill("${status.label} ($count)", filter == status, statusColor(status)) {
                            onFilter(status)
                        }
                    }
                }
            }

            if (shown.isEmpty()) {
                item {
                    Text(
                        if (query.isNotBlank()) "No games match \"${query.trim()}\"."
                        else "No games here.",
                        color = Muted, modifier = Modifier.padding(vertical = 24.dp, horizontal = 4.dp),
                    )
                }
            } else {
                items(shown, key = { it.id }) { game ->
                    GameRow(
                        game,
                        onClick = { onOpen(game) },
                        onStatus = { onStatus(game, it) },
                    )
                }
            }
        }
    }

    picked?.let { game ->
        PickDialog(
            game = game,
            onStart = {
                onStatus(game, Status.PLAYING)
                picked = null
            },
            onAgain = { picked = pickNext(games, exclude = game.id) },
            onDismiss = { picked = null },
        )
    }
}

@Composable
private fun SortMenu(sort: SortOrder, onSort: (SortOrder) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text("Sort: ${sort.label}", color = Muted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortOrder.entries.forEach { order ->
                DropdownMenuItem(
                    text = {
                        Text(
                            order.label,
                            fontWeight = if (order == sort) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (order == sort) Accent else Color.White,
                        )
                    },
                    onClick = {
                        onSort(order)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(games: List<Game>, onPick: () -> Unit) {
    val backlog = games.filter { it.status == Status.BACKLOG }
    val playing = games.count { it.status == Status.PLAYING }
    val finished = games.count { it.status == Status.FINISHED }
    // Count what's left of games in progress too, since they're still to be finished.
    val hoursLeft = games
        .filter { it.status == Status.BACKLOG || it.status == Status.PLAYING }
        .sumOf { it.hoursLeft ?: 0 }

    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Card)
            .padding(20.dp)
    ) {
        Text("Still to play", color = Muted, fontSize = 14.sp)
        Text(
            if (backlog.size == 1) "1 game" else "${backlog.size} games",
            fontSize = 42.sp, fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            buildString {
                append("$playing playing · $finished finished")
                if (hoursLeft > 0) append(" · ~${formatHours(hoursLeft)} to go")
            },
            color = Muted, fontSize = 14.sp,
        )
        if (backlog.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onPick,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Bg),
            ) { Text("Pick my next game", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun GameRow(game: Game, onClick: () -> Unit, onStatus: (Status) -> Unit) {
    val active = game.status == Status.BACKLOG || game.status == Status.PLAYING
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .background(Card)
            .height(IntrinsicSize.Min),
    ) {
        // Priority stripe down the left edge for games you've yet to finish.
        Box(
            Modifier.width(5.dp).fillMaxHeight()
                .background(if (active) priorityColor(game.priority) else Track)
        )
        Column(Modifier.weight(1f).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        game.title,
                        fontWeight = FontWeight.Medium, fontSize = 16.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (game.status == Status.DROPPED) Muted else Color.White,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor(game.status)))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            listOf(game.status.label, game.platform)
                                .filter { it.isNotBlank() }.joinToString(" · "),
                            color = Muted, fontSize = 13.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                when (game.status) {
                    Status.BACKLOG -> QuickAction("Play") { onStatus(Status.PLAYING) }
                    Status.PLAYING -> QuickAction("Finish") { onStatus(Status.FINISHED) }
                    else -> {}
                }
            }

            val toBeat = game.hoursToBeat
            if (active && toBeat != null && toBeat > 0) {
                Spacer(Modifier.height(10.dp))
                val fraction = (game.hoursPlayed.toFloat() / toBeat).coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Track)) {
                    if (fraction > 0f) {
                        Box(
                            Modifier.fillMaxWidth(fraction.coerceAtLeast(0.02f))
                                .fillMaxHeight().clip(CircleShape).background(statusColor(game.status))
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (game.hoursPlayed > 0) "${game.hoursPlayed} of ${formatHours(toBeat)}"
                    else "About ${formatHours(toBeat)}",
                    color = Muted, fontSize = 12.sp,
                )
            } else if (game.rating != null) {
                Spacer(Modifier.height(8.dp))
                Stars(game.rating, size = 16.dp)
            }
        }
    }
}

@Composable
private fun QuickAction(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 12.dp)) {
        Text(label, color = Accent, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Pill(label: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Card,
            labelColor = Muted,
            selectedContainerColor = color.copy(alpha = 0.2f),
            selectedLabelColor = color,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true, selected = selected,
            borderColor = Track, selectedBorderColor = color,
        ),
    )
}

@Composable
private fun Stars(rating: Int?, size: Dp, onRate: ((Int?) -> Unit)? = null) {
    Row {
        for (star in 1..5) {
            val lit = rating != null && star <= rating
            Icon(
                Icons.Filled.Star,
                contentDescription = if (onRate != null) "$star stars" else null,
                tint = if (lit) Gold else Track,
                modifier = Modifier.size(size).let { m ->
                    // Tapping the current rating again clears it.
                    if (onRate != null) m.clickable { onRate(if (rating == star) null else star) }
                    else m
                },
            )
        }
    }
}

@Composable
private fun PickDialog(game: Game, onStart: () -> Unit, onAgain: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = { Text("Play next", color = Muted, fontSize = 16.sp) },
        text = {
            Column {
                Text(game.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                val details = listOfNotNull(
                    game.platform.ifBlank { null },
                    game.hoursLeft?.let { "~${formatHours(it)}" },
                    "${game.priority.label} priority",
                )
                Spacer(Modifier.height(6.dp))
                Text(details.joinToString(" · "), color = Muted)
            }
        },
        confirmButton = {
            Button(
                onClick = onStart,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Bg),
            ) { Text("Start playing", fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onAgain) { Text("Pick again", color = Accent) }
        },
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Accent,
    unfocusedBorderColor = Track,
    cursorColor = Accent,
    focusedLabelColor = Accent,
    unfocusedLabelColor = Muted,
    focusedLeadingIconColor = Accent,
    unfocusedLeadingIconColor = Muted,
    unfocusedPlaceholderColor = Muted,
    focusedPlaceholderColor = Muted,
)

/** Keeps only the digits so the hours boxes can't hold anything we can't read back. */
private fun digitsOnly(text: String) = text.filter { it.isDigit() }.take(4)

@Composable
private fun EditGameScreen(
    game: Game?,
    platforms: List<String>,
    onSave: (Game) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    val key = game?.id ?: NEW_GAME
    var title by rememberSaveable(key) { mutableStateOf(game?.title ?: "") }
    var platform by rememberSaveable(key) { mutableStateOf(game?.platform ?: "") }
    var status by rememberSaveable(key) { mutableStateOf(game?.status ?: Status.BACKLOG) }
    var priority by rememberSaveable(key) { mutableStateOf(game?.priority ?: Priority.MEDIUM) }
    var toBeat by rememberSaveable(key) { mutableStateOf(game?.hoursToBeat?.toString() ?: "") }
    var played by rememberSaveable(key) {
        mutableStateOf(game?.hoursPlayed?.takeIf { it > 0 }?.toString() ?: "")
    }
    var rating by rememberSaveable(key) { mutableStateOf(game?.rating) }
    var notes by rememberSaveable(key) { mutableStateOf(game?.notes ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }

    fun save() {
        val base = game ?: Game(title = title.trim())
        onSave(
            base.copy(
                title = title.trim(),
                platform = platform.trim(),
                priority = priority,
                hoursToBeat = toBeat.toIntOrNull(),
                hoursPlayed = played.toIntOrNull() ?: 0,
                rating = rating,
                notes = notes.trim(),
            ).withStatus(status)
        )
    }

    Column(
        Modifier.fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            Text(
                if (game == null) "Add game" else "Edit game",
                fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            )
        }

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Title") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            shape = RoundedCornerShape(14.dp),
            colors = fieldColors(),
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = platform,
                onValueChange = { platform = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Platform") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                shape = RoundedCornerShape(14.dp),
                colors = fieldColors(),
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                platforms.forEach { p ->
                    Pill(p, selected = platform.equals(p, ignoreCase = true), color = Accent) {
                        platform = p
                    }
                }
            }
        }

        Section("Status") {
            Status.entries.forEach { s ->
                Pill(s.label, selected = status == s, color = statusColor(s)) { status = s }
            }
        }

        Section("Priority") {
            Priority.entries.forEach { p ->
                Pill(p.label, selected = priority == p, color = priorityColor(p)) { priority = p }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = toBeat,
                onValueChange = { toBeat = digitsOnly(it) },
                modifier = Modifier.weight(1f),
                label = { Text("Hours to beat") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(14.dp),
                colors = fieldColors(),
            )
            OutlinedTextField(
                value = played,
                onValueChange = { played = digitsOnly(it) },
                modifier = Modifier.weight(1f),
                label = { Text("Hours played") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(14.dp),
                colors = fieldColors(),
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Rating", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
            Stars(rating, size = 34.dp, onRate = { rating = it })
        }

        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
            label = { Text("Notes") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            shape = RoundedCornerShape(14.dp),
            colors = fieldColors(),
        )

        Button(
            onClick = { save() },
            enabled = title.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Accent, contentColor = Bg,
                disabledContainerColor = Track, disabledContentColor = Muted,
            ),
        ) { Text(if (game == null) "Add to backlog" else "Save", fontWeight = FontWeight.SemiBold) }

        if (game != null) {
            TextButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Delete game", color = Red) }
        }
    }

    if (confirmDelete && game != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Card,
            title = { Text("Delete ${game.title}?", color = Color.White) },
            text = { Text("This can't be undone.", color = Muted) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("Delete", color = Red) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = Accent) }
            },
        )
    }
}

@Composable
private fun Section(label: String, content: @Composable RowScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}
