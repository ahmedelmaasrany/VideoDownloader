package com.example.videodownloader

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Tracks temporary workspaces that are still owned by an active download/FFmpeg callback. */
internal object DownloadWorkspaceRegistry {
    private val activePaths = ConcurrentHashMap.newKeySet<String>()

    private fun key(directory: File): String =
        runCatching { directory.canonicalPath }.getOrElse { directory.absolutePath }

    private val lock = Any()

    /** Creates and registers atomically against the orphan sweeper. */
    fun createAndRegister(directory: File): Boolean = synchronized(lock) {
        val created = directory.mkdirs() || directory.isDirectory
        if (created) activePaths.add(key(directory))
        created
    }

    fun unregister(directory: File) = synchronized(lock) {
        activePaths.remove(key(directory))
    }

    /** Returns null for a live workspace; deletion and new registration are mutually exclusive. */
    fun deleteIfNotActive(directory: File, delete: () -> Boolean): Boolean? = synchronized(lock) {
        if (activePaths.contains(key(directory))) null else delete()
    }
}

/** One conservative maintenance pass per app process. */
internal object DownloadStorageMaintenance {
    private const val TAG = "DownloadStorageMaintenance"
    private const val KEEP_TERMINAL_QUEUE_ROWS = 100
    private const val WORK_DIRECTORY_PREFIX = "video-download-"
    private val started = AtomicBoolean(false)

    fun runOnce(context: Context) {
        if (!started.compareAndSet(false, true)) return

        val appContext = context.applicationContext
        Thread({
            try {
                val disk = cleanupOrphanedWorkspaces(appContext)
                val deletedRows = runCatching {
                    runBlocking(Dispatchers.IO) {
                        DownloadQueueRepository.getInstance(appContext)
                            .pruneTerminalHistory(KEEP_TERMINAL_QUEUE_ROWS)
                    }
                }.onFailure {
                    Log.w(TAG, "Could not prune old terminal download records", it)
                }.getOrDefault(-1)

                Log.i(
                    TAG,
                    "STARTUP_CLEANUP directoriesRemoved=${disk.directoriesRemoved} " +
                        "bytesFreed=${disk.bytesFreed} activeDirectoriesSkipped=${disk.activeDirectoriesSkipped} " +
                        "deleteFailures=${disk.deleteFailures} oldQueueRowsRemoved=$deletedRows " +
                        "queueRowsKept=$KEEP_TERMINAL_QUEUE_ROWS"
                )
            } catch (error: Throwable) {
                started.set(false)
                Log.w(TAG, "Startup storage maintenance failed; it may retry next time", error)
            }
        }, "video-downloader-storage-cleanup").apply {
            isDaemon = true
            start()
        }
    }

    private data class DiskCleanupResult(
        val directoriesRemoved: Int,
        val bytesFreed: Long,
        val activeDirectoriesSkipped: Int,
        val deleteFailures: Int
    )

    private fun cleanupOrphanedWorkspaces(context: Context): DiskCleanupResult {
        val baseDirectory = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: context.cacheDir

        var removed = 0
        var bytesFreed = 0L
        var activeSkipped = 0
        var failures = 0

        baseDirectory.listFiles().orEmpty()
            .asSequence()
            .filter { it.isDirectory && it.name.startsWith(WORK_DIRECTORY_PREFIX) }
            .forEach { directory ->
                val directoryBytes = runCatching {
                    directory.walkTopDown()
                        .filter { it.isFile }
                        .sumOf { it.length() }
                }.getOrDefault(0L)

                // Atomically check activity and delete, so a new download cannot
                // register the same workspace between the check and deletion.
                val deleted = DownloadWorkspaceRegistry.deleteIfNotActive(directory) {
                    runCatching { directory.deleteRecursively() }
                        .getOrElse {
                            Log.w(TAG, "Could not delete abandoned workspace: ${directory.name}", it)
                            false
                        }
                }

                when (deleted) {
                    null -> activeSkipped++
                    true -> {
                        removed++
                        bytesFreed += directoryBytes
                        Log.i(TAG, "ORPHAN_WORKSPACE_REMOVED name=${directory.name} bytes=$directoryBytes")
                    }
                    false -> failures++
                }
            }

        return DiskCleanupResult(removed, bytesFreed, activeSkipped, failures)
    }
}
