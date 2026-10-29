package com.screentime.tracker.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
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
import android.widget.Toast
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import com.screentime.tracker.AppStorage
import com.screentime.tracker.FilePreview
import com.screentime.tracker.FolderItem
import com.screentime.tracker.FolderListing
import com.screentime.tracker.StorageOverview
import com.screentime.tracker.StorageRepository
import com.screentime.tracker.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

private val CategoryColors = listOf(
    Color(0xFF7CE0B8), // Apps
    Color(0xFF6FA8FF), // Images
    Color(0xFFE58BFF), // Videos
    Color(0xFFFFB86B), // Audio
    Color(0xFFFFE27A), // Other files
    Color(0xFF5C6577), // System & other
)

private enum class AppSort(val label: String) { Total("Total"), Data("Data"), Cache("Cache") }

@Composable
fun StorageTab(
    repo: StorageRepository,
    overview: StorageOverview?,
    onRefresh: () -> Unit,
) {
    var folderStack by remember { mutableStateOf<List<File>>(emptyList()) }

    if (folderStack.isNotEmpty()) {
        BackHandler { folderStack = folderStack.dropLast(1) }
        FolderScreen(
            repo = repo,
            dir = folderStack.last(),
            onBack = { folderStack = folderStack.dropLast(1) },
            onOpen = { folderStack = folderStack + it },
            onChanged = onRefresh,
        )
    } else {
        StorageOverviewScreen(
            repo = repo,
            overview = overview,
            onRefresh = onRefresh,
            onBrowseFolders = { folderStack = listOf(repo.storageRoot) },
        )
    }
}

@Composable
private fun StorageOverviewScreen(
    repo: StorageRepository,
    overview: StorageOverview?,
    onRefresh: () -> Unit,
    onBrowseFolders: () -> Unit,
) {
    val context = LocalContext.current
    var sort by remember { mutableStateOf(AppSort.Total) }
    var showSystem by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Storage", Modifier.weight(1f).padding(start = 4.dp),
                    fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, "Refresh") }
            }
        }

        if (overview == null) {
            item {
                Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) {
                    CircularProgressIndicator(color = Accent)
                }
            }
            return@LazyColumn
        }

        item { StorageSummaryCard(overview) }

        item { BrowseFoldersCard(repo, onBrowseFolders) }

        item {
            Column(Modifier.padding(top = 6.dp)) {
                Text("Apps", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppSort.entries.forEach { option ->
                        FilterChip(
                            selected = sort == option,
                            onClick = { sort = option },
                            label = { Text(option.label) },
                        )
                    }
                    FilterChip(
                        selected = showSystem,
                        onClick = { showSystem = !showSystem },
                        label = { Text("System apps") },
                    )
                }
            }
        }

        val apps = overview.apps
            .filter { showSystem || !it.isSystem }
            .sortedByDescending {
                when (sort) {
                    AppSort.Total -> it.totalBytes
                    AppSort.Data -> it.dataBytes
                    AppSort.Cache -> it.cacheBytes
                }
            }
        val max = apps.firstOrNull()?.let {
            when (sort) {
                AppSort.Total -> it.totalBytes
                AppSort.Data -> it.dataBytes
                AppSort.Cache -> it.cacheBytes
            }
        }?.coerceAtLeast(1) ?: 1

        items(apps, key = { it.packageName }) { app ->
            val value = when (sort) {
                AppSort.Total -> app.totalBytes
                AppSort.Data -> app.dataBytes
                AppSort.Cache -> app.cacheBytes
            }
            AppStorageRow(app, value, value.toFloat() / max) {
                // Opens Android's App info page, where you can clear cache or storage.
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:${app.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}

@Composable
private fun StorageSummaryCard(overview: StorageOverview) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Card)
            .padding(20.dp)
    ) {
        Text("Used", color = Muted, fontSize = 14.sp)
        Text(formatBytes(overview.usedBytes), fontSize = 42.sp, fontWeight = FontWeight.Bold)
        Text(
            "of ${formatBytes(overview.totalBytes)} · ${formatBytes(overview.freeBytes)} free",
            color = Muted, fontSize = 14.sp,
        )
        Spacer(Modifier.height(16.dp))

        // One bar split into each category, with free space as the empty track.
        Row(Modifier.fillMaxWidth().height(14.dp).clip(CircleShape).background(Track)) {
            overview.categories.forEachIndexed { i, cat ->
                val fraction = cat.bytes.toFloat() / overview.totalBytes
                if (fraction > 0.002f) {
                    Box(
                        Modifier.weight(fraction).fillMaxHeight()
                            .background(CategoryColors[i % CategoryColors.size])
                    )
                }
            }
            val freeFraction = overview.freeBytes.toFloat() / overview.totalBytes
            if (freeFraction > 0.002f) Spacer(Modifier.weight(freeFraction))
        }
        Spacer(Modifier.height(14.dp))

        overview.categories.forEachIndexed { i, cat ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(10.dp).clip(CircleShape)
                        .background(CategoryColors[i % CategoryColors.size])
                )
                Spacer(Modifier.width(10.dp))
                Text(cat.name, Modifier.weight(1f))
                Text(formatBytes(cat.bytes), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun BrowseFoldersCard(repo: StorageRepository, onBrowse: () -> Unit) {
    val context = LocalContext.current
    var hasAccess by remember { mutableStateOf(repo.hasFileAccess()) }
    LifecycleResumeEffect(Unit) {
        hasAccess = repo.hasFileAccess()
        onPauseOrDispose { }
    }
    // Android 10 and older use the normal storage permission pop-up.
    val legacyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasAccess = granted }

    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable {
                if (hasAccess) {
                    onBrowse()
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                } else {
                    legacyLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
            .background(Card)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Browse folders", fontWeight = FontWeight.Medium)
            Text(
                if (hasAccess) "See which folders and files take the most space"
                else "Tap to allow All files access so folders can be measured",
                color = Muted, fontSize = 12.sp,
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Muted)
    }
}

@Composable
private fun AppStorageRow(app: AppStorage, value: Long, fraction: Float, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
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
                Text(formatBytes(value), fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Track)) {
                if (value > 0) {
                    Box(
                        Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                            .fillMaxHeight().clip(CircleShape).background(Accent)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "App ${formatBytes(app.appBytes)} · Data ${formatBytes(app.dataBytes)} · " +
                    "Cache ${formatBytes(app.cacheBytes)}",
                color = Muted, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FolderScreen(
    repo: StorageRepository,
    dir: File,
    onBack: () -> Unit,
    onOpen: (File) -> Unit,
    onChanged: () -> Unit,
) {
    var listing by remember(dir) { mutableStateOf<FolderListing?>(null) }
    var rescanTick by remember(dir) { mutableIntStateOf(0) }
    var selected by remember(dir) { mutableStateOf<FolderItem?>(null) }
    LaunchedEffect(dir, rescanTick) {
        listing = withContext(Dispatchers.IO) { repo.scanFolder(dir) }
    }

    selected?.let { file ->
        FileDialog(
            repo = repo,
            item = file,
            onDismiss = { selected = null },
            onDeleted = {
                selected = null
                rescanTick++
                onChanged()
            },
        )
    }

    val rootPath = repo.storageRoot.absolutePath
    val title = if (dir.absolutePath == rootPath) "Internal storage"
    else "Internal storage" + dir.absolutePath.removePrefix(rootPath)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text(
                    title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }

        val current = listing
        if (current == null) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = Accent)
                    Spacer(Modifier.height(12.dp))
                    Text("Measuring folders…", color = Muted, fontSize = 13.sp)
                }
            }
            return@LazyColumn
        }

        item {
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Card)
                    .padding(20.dp)
            ) {
                Text("In this folder", color = Muted, fontSize = 14.sp)
                Text(formatBytes(current.totalBytes), fontSize = 36.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${current.children.sumOf { it.fileCount }} files",
                    color = Muted, fontSize = 14.sp,
                )
                if (current.lockedFolders > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Android hides some app folders (like Android/data). Their size is " +
                            "counted in each app's Data figure on the Storage tab instead.",
                        color = Muted, fontSize = 12.sp,
                    )
                }
            }
        }

        if (current.children.isEmpty()) {
            item {
                Text("This folder is empty.", color = Muted, modifier = Modifier.padding(16.dp))
            }
        }

        val max = current.children.firstOrNull()?.bytes?.coerceAtLeast(1) ?: 1
        items(current.children, key = { "c:" + it.file.absolutePath }) { item ->
            FolderRow(
                name = item.file.name,
                bytes = item.bytes,
                subtitle = if (item.isDirectory) {
                    if (item.fileCount == 1) "1 file" else "${item.fileCount} files"
                } else "File",
                fraction = item.bytes.toFloat() / max,
                isDirectory = item.isDirectory,
                onClick = {
                    if (item.isDirectory) {
                        onOpen(item.file)
                    } else {
                        selected = item
                    }
                },
            )
        }

        if (current.biggestFiles.isNotEmpty() && current.children.any { it.isDirectory }) {
            item {
                Text(
                    "Biggest files in here", color = Muted, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 10.dp, start = 4.dp),
                )
            }
            val maxFile = current.biggestFiles.first().bytes.coerceAtLeast(1)
            items(current.biggestFiles, key = { "f:" + it.file.absolutePath }) { item ->
                val parent = item.file.parentFile?.absolutePath
                    ?.removePrefix(dir.absolutePath)?.trimStart('/') ?: ""
                FolderRow(
                    name = item.file.name,
                    bytes = item.bytes,
                    subtitle = if (parent.isEmpty()) "In this folder" else parent,
                    fraction = item.bytes.toFloat() / maxFile,
                    isDirectory = false,
                    onClick = { selected = item },
                )
            }
        }
    }
}

@Composable
private fun FolderRow(
    name: String,
    bytes: Long,
    subtitle: String,
    fraction: Float,
    isDirectory: Boolean,
    onClick: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(Card)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (isDirectory) "$name/" else name, Modifier.weight(1f),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(formatBytes(bytes), fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Track)) {
                if (bytes > 0) {
                    Box(
                        Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                            .fillMaxHeight().clip(CircleShape)
                            .background(if (isDirectory) Accent else Color(0xFF6FA8FF))
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(subtitle, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (isDirectory) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Muted)
        }
    }
}

private val DangerRed = Color(0xFFFF6B6B)

/** Tapping a file: shows a preview and lets you open it in another app or delete it. */
@Composable
private fun FileDialog(
    repo: StorageRepository,
    item: FolderItem,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
    val file = item.file
    var preview by remember(file) { mutableStateOf<FilePreview?>(null) }
    var confirmDelete by remember(file) { mutableStateOf(false) }
    LaunchedEffect(file) {
        preview = withContext(Dispatchers.IO) { repo.previewFor(file) }
    }

    val location = file.parentFile?.absolutePath
        ?.removePrefix(repo.storageRoot.absolutePath)?.trimStart('/')
        ?.ifEmpty { null }
        ?.let { "Internal storage/$it" } ?: "Internal storage"
    val modified = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(Date(file.lastModified()))

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Card,
            title = { Text("Delete this file?") },
            text = {
                Text(
                    "${file.name} (${formatBytes(item.bytes)}) will be permanently deleted. " +
                        "This can't be undone.",
                    color = Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (repo.deleteFile(file)) {
                        Toast.makeText(context, "Deleted ${file.name}", Toast.LENGTH_SHORT).show()
                        onDeleted()
                    } else {
                        Toast.makeText(context, "Couldn't delete ${file.name}", Toast.LENGTH_SHORT).show()
                        confirmDelete = false
                    }
                }) { Text("Delete", color = DangerRed, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = {
            Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 18.sp)
        },
        text = {
            Column {
                when (val p = preview) {
                    null -> Box(Modifier.fillMaxWidth().height(160.dp), Alignment.Center) {
                        CircularProgressIndicator(color = Accent)
                    }
                    is FilePreview.Picture -> Image(
                        p.bitmap.asImageBitmap(), null,
                        Modifier.fillMaxWidth().heightIn(max = 300.dp).clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Fit,
                    )
                    is FilePreview.TextSnippet -> Text(
                        p.text + if (p.truncated) "\n…" else "",
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Bg)
                            .padding(10.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        maxLines = 14,
                        overflow = TextOverflow.Ellipsis,
                    )
                    FilePreview.Unavailable -> Box(
                        Modifier.fillMaxWidth().height(72.dp)
                            .clip(RoundedCornerShape(12.dp)).background(Bg),
                        Alignment.Center,
                    ) {
                        Text("No preview for this type of file", color = Muted, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(formatBytes(item.bytes), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Modified $modified", color = Muted, fontSize = 13.sp)
                Text(location, color = Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (!repo.openExternally(file)) {
                    Toast.makeText(context, "No app on this phone can open this file", Toast.LENGTH_SHORT).show()
                }
            }) { Text("Open", fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = DangerRed) }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}
