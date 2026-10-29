package com.screentime.tracker

import android.Manifest
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaScannerConnection
import android.media.ThumbnailUtils
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Size
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.PriorityQueue

data class AppStorage(
    val packageName: String,
    val label: String,
    val icon: Bitmap?,
    val isSystem: Boolean,
    /** The app itself: APK, libraries, compiled code. */
    val appBytes: Long,
    /** Saved data, logins, downloads, offline content (cache not included). */
    val dataBytes: Long,
    /** Temporary files the app can rebuild. Safe to clear. */
    val cacheBytes: Long,
) {
    val totalBytes: Long get() = appBytes + dataBytes + cacheBytes
}

data class StorageCategory(val name: String, val bytes: Long)

data class StorageOverview(
    val totalBytes: Long,
    val freeBytes: Long,
    val categories: List<StorageCategory>,
    val apps: List<AppStorage>,
) {
    val usedBytes: Long get() = totalBytes - freeBytes
}

data class FolderItem(
    val file: File,
    val bytes: Long,
    val isDirectory: Boolean,
    /** Number of files inside (1 for a plain file). */
    val fileCount: Int,
)

sealed interface FilePreview {
    data class Picture(val bitmap: Bitmap) : FilePreview
    data class TextSnippet(val text: String, val truncated: Boolean) : FilePreview
    data object Unavailable : FilePreview
}

data class FolderListing(
    val dir: File,
    val totalBytes: Long,
    val children: List<FolderItem>,
    /** The largest individual files anywhere below this folder. */
    val biggestFiles: List<FolderItem>,
    /** Folders Android wouldn't let us look inside (e.g. Android/data). */
    val lockedFolders: Int,
)

class StorageRepository(private val context: Context) {

    private val ssm = context.getSystemService(StorageStatsManager::class.java)
    private val pm = context.packageManager

    /**
     * Sizes for every installed app plus a phone-wide breakdown by category.
     * Needs the same "Usage access" permission as the screen-time view.
     */
    fun loadOverview(): StorageOverview {
        val uuid = StorageManager.UUID_DEFAULT
        val user = Process.myUserHandle()
        val total = ssm.getTotalBytes(uuid)
        val free = ssm.getFreeBytes(uuid)

        val apps = pm.getInstalledApplications(0).mapNotNull { info ->
            try {
                val stats = ssm.queryStatsForPackage(info.storageUuid, info.packageName, user)
                AppStorage(
                    packageName = info.packageName,
                    label = pm.getApplicationLabel(info).toString(),
                    icon = iconFor(info),
                    isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    appBytes = stats.appBytes,
                    // Android's dataBytes already includes the cache, so split it out.
                    dataBytes = (stats.dataBytes - stats.cacheBytes).coerceAtLeast(0),
                    cacheBytes = stats.cacheBytes,
                )
            } catch (_: Exception) {
                null
            }
        }.sortedByDescending { it.totalBytes }

        val appsTotal = apps.sumOf { it.totalBytes }
        val ext = try {
            ssm.queryExternalStatsForUser(uuid, user)
        } catch (_: Exception) {
            null
        }
        val images = ext?.imageBytes ?: 0L
        val videos = ext?.videoBytes ?: 0L
        val audio = ext?.audioBytes ?: 0L
        // Shared storage minus media minus app folders (those are already in each app's data).
        val otherFiles = ext?.let { it.totalBytes - it.appBytes - images - videos - audio }
            ?.coerceAtLeast(0) ?: 0L
        val used = total - free
        val system = (used - appsTotal - images - videos - audio - otherFiles).coerceAtLeast(0)

        val categories = listOf(
            StorageCategory("Apps", appsTotal),
            StorageCategory("Images", images),
            StorageCategory("Videos", videos),
            StorageCategory("Audio", audio),
            StorageCategory("Other files", otherFiles),
            StorageCategory("System & other", system),
        )
        return StorageOverview(total, free, categories, apps)
    }

    // ---------- Folder browser ----------

    val storageRoot: File
        @Suppress("DEPRECATION")
        get() = Environment.getExternalStorageDirectory()

    /** True once the user has allowed "All files access" (or storage on older phones). */
    fun hasFileAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    /** Adds up everything inside each item of [dir] and finds the biggest files below it. */
    fun scanFolder(dir: File, topFiles: Int = 20): FolderListing {
        val biggest = PriorityQueue<FolderItem>(compareBy { it.bytes })
        var locked = 0

        fun offer(file: File, bytes: Long) {
            if (biggest.size < topFiles) {
                biggest.add(FolderItem(file, bytes, false, 1))
            } else if (bytes > biggest.peek()!!.bytes) {
                biggest.poll()
                biggest.add(FolderItem(file, bytes, false, 1))
            }
        }

        val children = (dir.listFiles() ?: emptyArray()).mapNotNull { child ->
            if (isLink(child)) return@mapNotNull null
            if (child.isDirectory) {
                var bytes = 0L
                var count = 0
                val pending = ArrayDeque<File>()
                pending.addLast(child)
                while (pending.isNotEmpty()) {
                    val folder = pending.removeAt(pending.lastIndex)
                    val list = folder.listFiles()
                    if (list == null) {
                        locked++
                        continue
                    }
                    for (f in list) {
                        if (isLink(f)) continue
                        if (f.isDirectory) {
                            pending.addLast(f)
                        } else {
                            val len = f.length()
                            bytes += len
                            count++
                            offer(f, len)
                        }
                    }
                }
                FolderItem(child, bytes, true, count)
            } else {
                val len = child.length()
                offer(child, len)
                FolderItem(child, len, false, 1)
            }
        }.sortedByDescending { it.bytes }

        return FolderListing(
            dir = dir,
            totalBytes = children.sumOf { it.bytes },
            children = children,
            biggestFiles = biggest.sortedByDescending { it.bytes },
            lockedFolders = locked,
        )
    }

    // ---------- File preview / open / delete ----------

    fun mimeTypeOf(file: File): String =
        MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase(Locale.ROOT))
            ?: when (file.extension.lowercase(Locale.ROOT)) {
                in textExtensions -> "text/plain"
                else -> "*/*"
            }

    /** Builds a small preview: a thumbnail for images, videos, PDFs and music, or the start of a text file. */
    fun previewFor(file: File): FilePreview {
        val mime = mimeTypeOf(file)
        val ext = file.extension.lowercase(Locale.ROOT)
        val size = Size(720, 720)
        return try {
            when {
                mime.startsWith("image/") -> {
                    val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ThumbnailUtils.createImageThumbnail(file, size, null)
                    } else {
                        decodeSampled(file, 720)
                    }
                    bmp?.let { FilePreview.Picture(it) } ?: FilePreview.Unavailable
                }
                mime.startsWith("video/") -> {
                    val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ThumbnailUtils.createVideoThumbnail(file, size, null)
                    } else {
                        @Suppress("DEPRECATION")
                        ThumbnailUtils.createVideoThumbnail(
                            file.absolutePath, MediaStore.Images.Thumbnails.MINI_KIND
                        )
                    }
                    bmp?.let { FilePreview.Picture(it) } ?: FilePreview.Unavailable
                }
                mime.startsWith("audio/") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    FilePreview.Picture(ThumbnailUtils.createAudioThumbnail(file, size, null))
                mime == "application/pdf" -> renderPdfFirstPage(file)
                mime.startsWith("text/") || ext in textExtensions -> {
                    val limit = 4000
                    val bytes = file.inputStream().use { input ->
                        val buf = ByteArray(limit)
                        var read = 0
                        while (read < limit) {
                            val n = input.read(buf, read, limit - read)
                            if (n < 0) break
                            read += n
                        }
                        buf.copyOf(read)
                    }
                    FilePreview.TextSnippet(String(bytes, Charsets.UTF_8), file.length() > limit)
                }
                else -> FilePreview.Unavailable
            }
        } catch (_: Exception) {
            // No album art, unreadable file, unsupported format, etc.
            FilePreview.Unavailable
        }
    }

    /** Hands the file to another app (Gallery, a PDF viewer, a video player…). False if nothing can open it. */
    fun openExternally(file: File): Boolean {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeTypeOf(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            context.startActivity(
                Intent.createChooser(view, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    /** Deletes the file and tells the media library so Gallery etc. stop showing it. */
    fun deleteFile(file: File): Boolean {
        val ok = file.delete()
        if (ok) MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
        return ok
    }

    private fun decodeSampled(file: File, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(
            file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }

    private fun renderPdfFirstPage(file: File): FilePreview {
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(fd) // closes fd when it is closed
        try {
            if (renderer.pageCount == 0) return FilePreview.Unavailable
            val page = renderer.openPage(0)
            try {
                val width = 720
                val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return FilePreview.Picture(bmp)
            } finally {
                page.close()
            }
        } finally {
            renderer.close()
        }
    }

    private val textExtensions = setOf(
        "txt", "log", "json", "xml", "md", "csv", "ini", "cfg", "conf", "yaml", "yml",
        "srt", "properties", "html", "htm", "kt", "java", "py", "js", "sh",
    )

    private fun isLink(f: File): Boolean = try {
        Files.isSymbolicLink(f.toPath())
    } catch (_: Exception) {
        false
    }

    private fun iconFor(info: ApplicationInfo): Bitmap? = try {
        pm.getApplicationIcon(info).toBitmap(96, 96)
    } catch (_: Exception) {
        null
    }
}

/** Uses 1000-based units, the same way Android's own Storage settings do. */
fun formatBytes(bytes: Long): String {
    if (bytes < 1000) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var i = -1
    while (value >= 1000 && i < units.lastIndex) {
        value /= 1000
        i++
    }
    val pattern = when {
        value >= 100 -> "%.0f %s"
        value >= 10 -> "%.1f %s"
        else -> "%.2f %s"
    }
    return String.format(Locale.UK, pattern, value, units[i])
}
