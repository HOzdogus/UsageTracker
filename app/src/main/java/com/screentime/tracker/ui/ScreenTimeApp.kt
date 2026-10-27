package com.screentime.tracker.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.screentime.tracker.AppUsage
import com.screentime.tracker.DayUsage
import com.screentime.tracker.UsageRepository
import com.screentime.tracker.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val Bg = Color(0xFF0F1115)
private val Card = Color(0xFF181C24)
private val Accent = Color(0xFF7CE0B8)
private val Muted = Color(0xFF8A93A6)
private val Track = Color(0xFF262B36)

private val colors = darkColorScheme(
    background = Bg, surface = Card, primary = Accent,
    onBackground = Color.White, onSurface = Color.White,
)

@Composable
fun ScreenTimeApp(repo: UsageRepository) {
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            var hasPermission by remember { mutableStateOf(repo.hasPermission()) }
            var date by remember { mutableStateOf(LocalDate.now()) }
            var day by remember { mutableStateOf<DayUsage?>(null) }
            var refreshTick by remember { mutableIntStateOf(0) }

            // Re-check permission and refresh numbers every time you come back to the app.
            LifecycleResumeEffect(Unit) {
                hasPermission = repo.hasPermission()
                refreshTick++
                onPauseOrDispose { }
            }

            LaunchedEffect(hasPermission, date, refreshTick) {
                if (hasPermission) {
                    day = withContext(Dispatchers.IO) { repo.loadDay(date) }
                }
            }

            if (!hasPermission) {
                PermissionScreen()
            } else {
                UsageScreen(
                    date = date,
                    day = day?.takeIf { it.date == date },
                    onPrev = { date = date.minusDays(1) },
                    onNext = { if (date < LocalDate.now()) date = date.plusDays(1) },
                )
            }
        }
    }
}

@Composable
private fun PermissionScreen() {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("One quick step", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(
            "To see which apps you use and for how long, turn on Usage access for " +
                "Screen Time on the next screen. Nothing leaves your phone.",
            color = Muted, fontSize = 16.sp,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Bg),
        ) { Text("Open Usage access", fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun UsageScreen(
    date: LocalDate,
    day: DayUsage?,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    val today = LocalDate.now()
    val dateLabel = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern("EEE d MMM"))
    }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrev) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous day")
                }
                Text(
                    dateLabel, Modifier.weight(1f),
                    fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = onNext, enabled = date < today) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next day")
                }
            }
        }

        item { SummaryCard(day) }

        if (day == null) {
            item {
                Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) {
                    CircularProgressIndicator(color = Accent)
                }
            }
        } else if (day.apps.isEmpty()) {
            item {
                Text(
                    "No app usage recorded for this day.",
                    color = Muted, modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        } else {
            val max = day.apps.first().foregroundMs
            items(day.apps, key = { it.packageName }) { app ->
                AppRow(app, fraction = app.foregroundMs.toFloat() / max)
            }
        }
    }
}

@Composable
private fun SummaryCard(day: DayUsage?) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Card)
            .padding(20.dp)
    ) {
        Text("Screen time", color = Muted, fontSize = 14.sp)
        Text(
            day?.let { formatDuration(it.totalMs) } ?: "—",
            fontSize = 42.sp, fontWeight = FontWeight.Bold,
        )
        if (day != null && day.apps.isNotEmpty()) {
            val top = day.apps.first()
            Spacer(Modifier.height(4.dp))
            Text(
                "${day.apps.size} apps · ${day.apps.sumOf { it.opens }} opens · " +
                    "most used: ${top.label}",
                color = Muted, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AppRow(app: AppUsage, fraction: Float) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Card)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (app.icon != null) {
            Image(
                app.icon.asImageBitmap(), null,
                Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)),
            )
        } else {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Track))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    app.label, Modifier.weight(1f),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(formatDuration(app.foregroundMs), fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Track)) {
                Box(
                    Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                        .fillMaxHeight().clip(CircleShape).background(Accent)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (app.opens == 1) "Opened once" else "Opened ${app.opens} times",
                color = Muted, fontSize = 12.sp,
            )
        }
    }
}
