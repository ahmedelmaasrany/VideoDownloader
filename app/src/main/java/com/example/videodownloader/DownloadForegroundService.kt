package com.example.videodownloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.View
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException

internal data class DownloadTaskUiState(
    val id: String,
    val title: String,
    val progress: Float = 0f,
    val status: String = STATUS_QUEUED,
    val message: String = "في قائمة الانتظار",
    val savedUri: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
) {
    val isActive: Boolean
        get() = status == STATUS_QUEUED ||
                status == STATUS_RUNNING ||
                status == STATUS_WAITING_FOR_NETWORK

    companion object {
        const val STATUS_QUEUED = "QUEUED"
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_WAITING_FOR_NETWORK = "WAITING_FOR_NETWORK"
        const val STATUS_SUCCEEDED = "SUCCEEDED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_CANCELLED = "CANCELLED"
    }
}

internal data class DownloadUiState(
    val isRunning: Boolean = false,
    val progress: Float = 0f,
    val terminalMessage: String = "",
    val savedUri: String? = null,
    val finishedAt: Long = 0L
)

/** Shared in-process state. The service owns updates; Compose observes immutable snapshots. */
internal object DownloadUiStateStore {
    private val mutableTasks = MutableStateFlow<List<DownloadTaskUiState>>(emptyList())
    val tasks: StateFlow<List<DownloadTaskUiState>> = mutableTasks

    val state = MutableStateFlow(DownloadUiState())

    @Synchronized
    fun addTask(id: String, title: String) {
        val withoutDuplicate = mutableTasks.value.filterNot { it.id == id }
        val next = (withoutDuplicate + DownloadTaskUiState(id = id, title = title))
            .takeLast(MAX_VISIBLE_TASKS)
        mutableTasks.value = next
        publishAggregate()
    }

    @Synchronized
    fun updateTask(
        id: String,
        progress: Float? = null,
        status: String? = null,
        message: String? = null
    ) {
        val next = mutableTasks.value.map { task ->
            if (task.id != id) task else task.copy(
                progress = progress?.takeIf { it.isFinite() }?.coerceIn(0f, 100f)
                    ?: task.progress,
                status = status ?: task.status,
                message = message ?: task.message,
                updatedAt = System.currentTimeMillis()
            )
        }
        mutableTasks.value = next
        publishAggregate()
    }

    @Synchronized
    fun finishTask(
        id: String,
        status: String,
        message: String,
        savedUri: String? = null,
        openOnFinish: Boolean = false
    ) {
        val next = mutableTasks.value.map { task ->
            if (task.id != id) task else task.copy(
                progress = if (status == DownloadTaskUiState.STATUS_SUCCEEDED) 100f else task.progress,
                status = status,
                message = message,
                savedUri = savedUri,
                updatedAt = System.currentTimeMillis()
            )
        }
        mutableTasks.value = next
        val current = state.value
        state.value = current.copy(
            terminalMessage = message,
            savedUri = if (openOnFinish) savedUri else current.savedUri,
            finishedAt = System.currentTimeMillis()
        )
        publishAggregate()
    }

    /** Backward-compatible helpers used by the existing single-download UI. */
    fun begin() {
        state.value = DownloadUiState(isRunning = true, progress = 0f)
    }

    fun updateProgress(progress: Float) {
        val current = state.value
        if (current.isRunning) {
            state.value = current.copy(progress = progress.coerceIn(0f, 100f))
        }
    }

    fun finish(message: String, savedUri: String? = null) {
        state.value = DownloadUiState(
            isRunning = false,
            progress = if (savedUri != null) 100f else state.value.progress,
            terminalMessage = message,
            savedUri = savedUri,
            finishedAt = System.currentTimeMillis()
        )
    }

    fun failToStart(message: String) = finish(message)

    fun markSavedUriHandled(uri: String) {
        val current = state.value
        if (current.savedUri == uri) state.value = current.copy(savedUri = null)
    }

    fun markOpenFailed(uri: String, message: String) {
        val current = state.value
        if (current.savedUri == uri) {
            state.value = current.copy(
                terminalMessage = message,
                savedUri = null,
                finishedAt = System.currentTimeMillis()
            )
        }
    }

    @Synchronized
    private fun publishAggregate() {
        val snapshot = mutableTasks.value
        val active = snapshot.filter { it.isActive }
        val averageProgress = if (active.isEmpty()) {
            snapshot.lastOrNull()?.progress ?: state.value.progress
        } else {
            active.map { it.progress }.average().toFloat()
        }
        state.value = state.value.copy(
            isRunning = active.isNotEmpty(),
            progress = averageProgress.coerceIn(0f, 100f)
        )
    }

    private const val MAX_VISIBLE_TASKS = 80
}

class DownloadForegroundService : Service() {

    private data class DownloadRequest(
        val taskId: String,
        val url: String,
        val title: String,
        val selectedFormat: VideoFormat,
        val audioFormats: List<AudioFormat>,
        val openOnFinish: Boolean
    )

    private class ActiveTask(
        val request: DownloadRequest,
        val notificationId: Int
    ) {
        val cancellationRequested = AtomicBoolean(false)
        val userCancellationRequested = AtomicBoolean(false)
        val terminalHandled = AtomicBoolean(false)
        val started = AtomicBoolean(false)

        @Volatile var workerThread: Thread? = null
        @Volatile var future: Future<*>? = null
        @Volatile var progress: Float = 0f
        @Volatile var status: String = DownloadTaskUiState.STATUS_QUEUED
        @Volatile var message: String = "في قائمة الانتظار"
        @Volatile var lastNotifiedPercent: Int = -1
        @Volatile var lastNotifiedStatus: String = ""
        @Volatile var lastNotifiedMessage: String = ""
        @Volatile var lastPersistedPercent: Int = -1
        @Volatile var lastPersistedStatus: String = ""
        @Volatile var lastPersistedAt: Long = 0L
    }

    /** Exactly one complete download pipeline runs at a time. */
    private val serviceExecutor: ExecutorService = Executors.newFixedThreadPool(MAX_CONCURRENT_DOWNLOADS)
    private val activeTasks = ConcurrentHashMap<String, ActiveTask>()
    private val processingTaskIds = ConcurrentHashMap.newKeySet<String>()
    private val allocatedNotificationIds = ConcurrentHashMap.newKeySet<Int>()
    private val nextNotificationId = AtomicInteger(TASK_NOTIFICATION_ID_BASE)
    private val mainHandler by lazy { android.os.Handler(mainLooper) }

    @Volatile private var foregroundStarted = false
    @Volatile private var latestStartId: Int = 0
    @Volatile private var currentForegroundType: Int = 0
    @Volatile private var networkAvailable = false
    @Volatile private var wakeLock: PowerManager.WakeLock? = null

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueReady = CompletableDeferred<Unit>()
    private val queuePumpRunning = AtomicBoolean(false)
    private val pendingEnqueueCount = AtomicInteger(0)

    private val queueControlMutex = Mutex()
    private lateinit var queueRepository: DownloadQueueRepository
    @Volatile private var serviceShuttingDown = false

    private val notificationManager: NotificationManager
        get() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    private val connectivityManager: ConnectivityManager
        get() = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun onCreate() {
        super.onCreate()
        DownloadStorageMaintenance.runOnce(applicationContext)
        queueRepository = DownloadQueueRepository.getInstance(applicationContext)
        createNotificationChannel()
        registerNetworkMonitor()

        serviceScope.launch {
            try {
                queueRepository.recoverInterruptedTasks()
                val persistedTasks = queueRepository.getAll()
                persistedTasks
                    .filter { it.status == DownloadTaskUiState.STATUS_QUEUED }
                    .forEach { entity ->

                        try {

                            val task = requestTaskFromEntity(entity)

                            val existing = activeTasks.putIfAbsent(entity.id, task)
                            val restored = existing ?: task

                            DownloadUiStateStore.addTask(entity.id, entity.title)

                            DownloadUiStateStore.updateTask(
                                id = entity.id,
                                progress = entity.progress,
                                status = entity.status,
                                message = entity.message
                            )

                            notifyTaskAndSummary(restored)

                        } catch (cancelled: CancellationException) {
                            throw cancelled

                        } catch (error: Exception) {

                            Log.e(
                                TAG,
                                "Could not restore queued task id=${entity.id}",
                                error
                            )

                            runCatching {
                                queueRepository.updateTaskState(
                                    taskId = entity.id,
                                    status = DownloadTaskUiState.STATUS_FAILED,
                                    progress = entity.progress,
                                    message = "تعذرت استعادة بيانات هذه المهمة؛ أعد إضافتها."
                                )
                            }.onFailure {
                                Log.e(
                                    TAG,
                                    "Could not mark corrupted task as failed id=${entity.id}",
                                    it
                                )
                            }
                        }
                    }
                queueReady.complete(Unit)

                Log.i(
                    TAG,
                    "Queue restored: ${persistedTasks.count { it.status == DownloadTaskUiState.STATUS_QUEUED }} pending task(s)"
                )

            } catch (cancelled: CancellationException) {

                queueReady.cancel(cancelled)
                throw cancelled

            } catch (error: Exception) {

                Log.e(
                    TAG,
                    "Could not restore persistent download queue",
                    error
                )

                queueReady.completeExceptionally(error)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val commandIntent = intent ?: return START_NOT_STICKY

        when (commandIntent.action) {
            ACTION_CANCEL -> {
                cancelTask(commandIntent.getStringExtra(EXTRA_TASK_ID))
                return START_NOT_STICKY
            }

            ACTION_CANCEL_ALL -> {
                cancelAllTasks()
                return START_NOT_STICKY
            }

            ACTION_RESUME_QUEUE -> {
                try {
                    if (!foregroundStarted) startAsForeground()
                } catch (error: Exception) {
                    Log.e(TAG, "Could not resume queue in foreground", error)
                    DownloadUiStateStore.failToStart("Could not resume downloads: ${errorDetails(error)}")
                    stopSelfResult(startId)
                    return START_NOT_STICKY
                }
                serviceScope.launch {
                    runCatching { queueReady.await() }
                        .onSuccess { pumpQueue() }
                        .onFailure { Log.e(TAG, "Queue initialization failed", it); stopIfIdle(startId) }
                }
                return START_NOT_STICKY
            }

            ACTION_START -> {
                if (!foregroundStarted) {
                    try {
                        startAsForeground()
                    } catch (error: Exception) {
                        Log.e(TAG, "Could not enter foreground state", error)
                        DownloadUiStateStore.failToStart("Could not start download service: ${errorDetails(error)}")
                        stopSelfResult(startId)
                        return START_NOT_STICKY
                    }
                }

                val request = try {
                    decodeRequest(commandIntent)
                } catch (error: Exception) {
                    Log.e(TAG, "Invalid download request", error)
                    notificationManager.notify(
                        ERROR_NOTIFICATION_ID,
                        buildResultNotification("Could not start download: ${errorDetails(error)}")
                    )
                    serviceScope.launch {
                        runCatching { queueReady.await() }
                        stopIfIdle(startId)
                    }
                    return START_NOT_STICKY
                }

                enqueue(request)
                return START_NOT_STICKY
            }

            else -> {
                serviceScope.launch {
                    runCatching { queueReady.await() }
                    stopIfIdle(startId)
                }
                return START_NOT_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Android 15+ gives dataSync and mediaProcessing separate six-hour/24-hour quotas. */
    override fun onTimeout(startId: Int, foregroundServiceType: Int) {
        serviceShuttingDown = true

        // Request service shutdown immediately.
        stopSelf()

        Log.e(
            TAG,
            "Foreground service time limit reached: type=$foregroundServiceType"
        )

        activeTasks.values.forEach { task ->
            if (
                task.status == DownloadTaskUiState.STATUS_RUNNING ||
                task.status == DownloadTaskUiState.STATUS_WAITING_FOR_NETWORK
            ) {
                task.cancellationRequested.set(true)
                task.workerThread?.interrupt()
            }
        }

        serviceExecutor.shutdownNow()
    }

    override fun onDestroy() {
        serviceShuttingDown = true
        unregisterNetworkMonitor()

        activeTasks.values.forEach { task ->
            if (task.status == DownloadTaskUiState.STATUS_RUNNING ||
                task.status == DownloadTaskUiState.STATUS_WAITING_FOR_NETWORK
            ) {
                task.cancellationRequested.set(true)
                task.workerThread?.interrupt()
            }
        }
        serviceExecutor.shutdownNow()

        // A teardown is not a user cancellation. Preserve every unfinished row
        // as QUEUED so a later service start can recover it from Room.
        runCatching {
            runBlocking(Dispatchers.IO) { queueRepository.recoverInterruptedTasks() }
        }.onFailure { Log.e(TAG, "Could not recover interrupted tasks during service teardown", it) }

        activeTasks.values.forEach { task ->
            if (task.status == DownloadTaskUiState.STATUS_RUNNING ||
                task.status == DownloadTaskUiState.STATUS_WAITING_FOR_NETWORK
            ) {
                task.status = DownloadTaskUiState.STATUS_QUEUED
                task.message = "Download will resume when the queue service starts again."
                notifyTaskAndSummary(task)
            }
        }
        processingTaskIds.clear()
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }


    private fun enqueue(request: DownloadRequest) {
        pendingEnqueueCount.incrementAndGet()

        serviceScope.launch {
            try {
                queueReady.await()

                val shouldPump = queueControlMutex.withLock {

                    val existing = queueRepository.getTask(request.taskId)

                    if (existing == null) {
                        queueRepository.enqueue(
                            url = request.url,
                            title = request.title,
                            selectedFormat = request.selectedFormat,
                            audioFormats = request.audioFormats,
                            openOnFinish = request.openOnFinish,
                            taskId = request.taskId
                        )
                    }

                    val entity = queueRepository.getTask(request.taskId)
                        ?: throw IOException(
                            "The queued download could not be read back from Room."
                        )

                    // A repeated START intent for an existing row must not duplicate it.
                    if (entity.status != DownloadTaskUiState.STATUS_QUEUED) {

                        Log.i(
                            TAG,
                            "Ignoring duplicate start for task=${request.taskId}, status=${entity.status}"
                        )

                        false

                    } else {

                        val task = activeTasks.computeIfAbsent(entity.id) {
                            requestTaskFromEntity(entity)
                        }

                        DownloadUiStateStore.addTask(
                            entity.id,
                            entity.title
                        )

                        DownloadUiStateStore.updateTask(
                            id = entity.id,
                            progress = entity.progress,
                            status = entity.status,
                            message = entity.message
                        )

                        notifyTaskAndSummary(task)

                        true
                    }
                }

                // Start the queue only after releasing the control mutex.
                if (shouldPump) {
                    pumpQueue()
                }

            } catch (cancelled: CancellationException) {
                throw cancelled

            } catch (error: Exception) {

                Log.e(
                    TAG,
                    "Could not enqueue download id=${request.taskId}",
                    error
                )

                DownloadUiStateStore.failToStart(
                    "Could not add download to queue: ${errorDetails(error)}"
                )

                notificationManager.notify(
                    ERROR_NOTIFICATION_ID,
                    buildResultNotification(
                        "Could not add download to queue: ${errorDetails(error)}"
                    )
                )

            } finally {
                pendingEnqueueCount.decrementAndGet()
                stopIfIdle()
            }
        }
    }


    /** One serial worker claims and executes persisted FIFO tasks. */

    private fun pumpQueue() {
        if (
            serviceShuttingDown ||
            !queuePumpRunning.compareAndSet(false, true)
        ) {
            return
        }

        try {
            serviceExecutor.submit {

                try {
                    runBlocking(Dispatchers.IO) {

                        queueReady.await()

                        while (
                            !serviceShuttingDown &&
                            !Thread.currentThread().isInterrupted
                        ) {

                            val task = queueControlMutex.withLock {

                                val entity = queueRepository.claimNextQueued()
                                    ?: return@withLock null

                                val claimedTask = activeTasks.computeIfAbsent(entity.id) {
                                    requestTaskFromEntity(entity)
                                }

                                claimedTask.progress = entity.progress
                                claimedTask.status = DownloadTaskUiState.STATUS_RUNNING
                                claimedTask.message = entity.message

                                claimedTask
                            } ?: break

                            acquireWakeLockIfNeeded()

                            notifyTaskAndSummary(task)

                            executeTask(task)
                        }
                    }

                } catch (error: Throwable) {

                    if (!serviceShuttingDown) {
                        Log.e(
                            TAG,
                            "Persistent queue worker failed",
                            error
                        )
                    }

                } finally {

                    queuePumpRunning.set(false)

                    if (!serviceShuttingDown) {

                        serviceScope.launch {

                            val hasQueued = runCatching {

                                queueReady.await()

                                queueRepository.getNextQueued() != null

                            }.getOrDefault(false)

                            if (hasQueued) {

                                pumpQueue()

                            } else if (activeTasks.isEmpty()) {

                                releaseWakeLock()
                                stopIfIdle()
                            }
                        }
                    }
                }
            }

        } catch (error: Exception) {

            queuePumpRunning.set(false)

            Log.e(
                TAG,
                "Could not schedule persistent queue worker",
                error
            )
        }
    }

    private fun executeTask(task: ActiveTask) {
        if (!task.started.compareAndSet(false, true)) return
        task.workerThread = Thread.currentThread()

        try {
            if (task.cancellationRequested.get()) {
                finishTask(task, DownloadTaskUiState.STATUS_CANCELLED, "تم إلغاء التنزيل.")
                return
            }

            setTaskState(
                task,
                if (networkAvailable) DownloadTaskUiState.STATUS_RUNNING
                else DownloadTaskUiState.STATUS_WAITING_FOR_NETWORK,
                if (networkAvailable) "جاري بدء التنزيل..." else "الاتصال بالإنترنت غير متاح؛ في انتظار عودته."
            )

            val savedUri = downloadSelectedVideo(
                context = applicationContext,
                url = task.request.url,
                title = task.request.title,
                selectedFormat = task.request.selectedFormat,
                audioFormats = task.request.audioFormats
            ) { progress, message ->
                if (task.cancellationRequested.get() || Thread.currentThread().isInterrupted) {
                    throw IOException("تم إيقاف التنزيل.")
                }

                task.progress = if (progress.isFinite()) progress.coerceIn(0f, 100f) else task.progress
                task.message = message
                val status = when {
                    !networkAvailable && isNetworkTransferStage(message) ->
                        DownloadTaskUiState.STATUS_WAITING_FOR_NETWORK
                    else -> DownloadTaskUiState.STATUS_RUNNING
                }
                setTaskState(task, status, message)
                updateForegroundStage(task, message)
            }

            if (task.cancellationRequested.get() || Thread.currentThread().isInterrupted) {
                finishTask(task, DownloadTaskUiState.STATUS_CANCELLED, "تم إلغاء التنزيل.")
            } else {
                finishTask(
                    task = task,
                    status = DownloadTaskUiState.STATUS_SUCCEEDED,
                    message = "تم حفظ الفيديو بنجاح.",
                    savedUri = savedUri,
                    openOnFinish = task.request.openOnFinish
                )
            }
        } catch (error: Throwable) {
            if (serviceShuttingDown) {
                Log.i(TAG, "Task interrupted for service recovery id=${task.request.taskId}")
            } else if (
                task.cancellationRequested.get() ||
                Thread.currentThread().isInterrupted ||
                error is InterruptedException
            ) {
                Log.i(TAG, "Task cancelled id=${task.request.taskId}")
                finishTask(task, DownloadTaskUiState.STATUS_CANCELLED, "Download cancelled.")
            } else {
                Log.e(TAG, "Background download failed id=${task.request.taskId}", error)
                finishTask(
                    task,
                    DownloadTaskUiState.STATUS_FAILED,
                    "تعذر إكمال التنزيل."
                )
            }
        } finally {
            task.workerThread = null
        }
    }

    private fun setTaskState(task: ActiveTask, status: String, message: String) {
        if (task.userCancellationRequested.get() || task.terminalHandled.get()) {
            return
        }
        task.status = status
        task.message = message
        DownloadUiStateStore.updateTask(
            id = task.request.taskId,
            progress = task.progress,
            status = status,
            message = message
        )
        notifyTaskAndSummary(task)

        // Persist meaningful progress changes, but avoid writing every callback
        // from yt-dlp/FFmpeg to SQLite.
        val now = System.currentTimeMillis()
        val percent = task.progress.roundToInt().coerceIn(0, 100)
        val shouldPersist = status != task.lastPersistedStatus ||
            percent != task.lastPersistedPercent ||
            now - task.lastPersistedAt >= 1_500L
        if (shouldPersist && !serviceShuttingDown) {
            task.lastPersistedStatus = status
            task.lastPersistedPercent = percent
            task.lastPersistedAt = now
            runCatching {
                runBlocking(Dispatchers.IO) {
                    queueRepository.updateTaskState(
                        taskId = task.request.taskId,
                        status = status,
                        progress = task.progress,
                        message = message
                    )
                }
            }.onFailure {
                Log.w(TAG, "Could not persist task progress id=${task.request.taskId}", it)
            }
        }
    }

    private fun finishTask(
        task: ActiveTask,
        status: String,
        message: String,
        savedUri: Uri? = null,
        openOnFinish: Boolean = false
    ) {
        var finalStatus = status
        var finalMessage = message

        val accepted = synchronized(task) {
            if (!task.terminalHandled.compareAndSet(false, true)) {
                false
            } else {
                if (task.userCancellationRequested.get()) {
                    finalStatus = DownloadTaskUiState.STATUS_CANCELLED
                    finalMessage = "Download cancelled."
                }

                task.status = finalStatus
                task.message = finalMessage

                if (finalStatus == DownloadTaskUiState.STATUS_SUCCEEDED) {
                    task.progress = 100f
                }

                true
            }
        }

        if (!accepted) return

        val finalSavedUri = if (
            finalStatus == DownloadTaskUiState.STATUS_SUCCEEDED
        ) savedUri else null

        if (!serviceShuttingDown) {
            runCatching {
                runBlocking(Dispatchers.IO) {
                    if (finalStatus == DownloadTaskUiState.STATUS_CANCELLED) {
                        queueRepository.deleteTask(task.request.taskId)
                    } else {
                        queueRepository.updateTaskState(
                            taskId = task.request.taskId,
                            status = finalStatus,
                            progress = task.progress,
                            message = finalMessage,
                            savedUri = finalSavedUri?.toString()
                        )
                    }
                }
            }.onFailure {
                Log.e(
                    TAG,
                    "Could not persist final task state id=${task.request.taskId}",
                    it
                )
            }
        }

        DownloadUiStateStore.finishTask(
            id = task.request.taskId,
            status = finalStatus,
            message = finalMessage,
            savedUri = finalSavedUri?.toString(),
            openOnFinish = finalStatus == DownloadTaskUiState.STATUS_SUCCEEDED &&
                    openOnFinish
        )

        processingTaskIds.remove(task.request.taskId)
        activeTasks.remove(task.request.taskId)

        synchronized(task) {
            if (
                finalStatus == DownloadTaskUiState.STATUS_CANCELLED ||
                task.userCancellationRequested.get()
            ) {
                notificationManager.cancel(task.notificationId)
            } else {
                notificationManager.notify(
                    task.notificationId,
                    buildTaskNotification(task, ongoing = false)
                )
            }
        }

        updateForegroundType()
        updateSummaryNotification()
        releaseWakeLockIfIdle()
    }


    private fun cancelTask(taskId: String?) {
        if (taskId.isNullOrBlank()) return

        serviceScope.launch {
            try {
                queueReady.await()

                val wasNotStarted = queueControlMutex.withLock {

                    val task = activeTasks[taskId] ?: run {
                        val entity = queueRepository.getTask(taskId)
                            ?.takeIf {
                                it.status == DownloadTaskUiState.STATUS_QUEUED ||
                                        it.status == DownloadTaskUiState.STATUS_RUNNING ||
                                        it.status == DownloadTaskUiState.STATUS_WAITING_FOR_NETWORK
                            }
                            ?: return@withLock null

                        requestTaskFromEntity(entity).also {
                            activeTasks[taskId] = it
                        }
                    }

                    val accepted = synchronized(task) {
                        if (
                            task.terminalHandled.get() ||
                            task.userCancellationRequested.get()
                        ) {
                            false
                        } else {
                            task.cancellationRequested.set(true)
                            task.userCancellationRequested.set(true)
                            task.message = "Cancelling download..."

                            notificationManager.cancel(task.notificationId)

                            true
                        }
                    }

                    if (!accepted) return@withLock null

                    runCatching {
                        queueRepository.deleteTask(taskId)
                    }.onFailure {
                        Log.e(TAG, "Could not immediately remove cancelled task id=$taskId", it)
                    }

                    DownloadUiStateStore.updateTask(
                        id = taskId,
                        progress = task.progress,
                        status = task.status,
                        message = task.message
                    )

                    if (!task.started.get()) {
                        task.future?.cancel(true)

                        finishTask(
                            task = task,
                            status = DownloadTaskUiState.STATUS_CANCELLED,
                            message = "Download cancelled."
                        )

                        true
                    } else {
                        task.workerThread?.interrupt()

                        updateSummaryNotification()

                        false
                    }
                } ?: return@launch

                if (wasNotStarted) {
                    val hasQueuedTasks =
                        queueRepository.getNextQueued() != null

                    if (hasQueuedTasks) {
                        mainHandler.post {
                            if (!foregroundStarted) {
                                runCatching {
                                    startAsForeground()
                                }.onFailure {
                                    Log.e(
                                        TAG,
                                        "Could not resume foreground after cancellation",
                                        it
                                    )
                                }
                            }

                            if (foregroundStarted) {
                                pumpQueue()
                            }
                        }
                    } else {
                        stopIfIdle()
                    }
                }

            } catch (cancelled: CancellationException) {
                throw cancelled

            } catch (error: Exception) {
                Log.e(TAG, "Could not cancel task id=$taskId", error)
            }
        }
    }


    private fun cancelAllTasks() {
        serviceScope.launch {
            try {
                queueReady.await()

                queueControlMutex.withLock {

                    val persistedTasks =
                        queueRepository.deleteAllActiveAndReturn()

                    val tasksToCancel = linkedMapOf<String, ActiveTask>()

                    activeTasks.values.forEach { task ->
                        if (!task.terminalHandled.get()) {
                            tasksToCancel[task.request.taskId] = task
                        }
                    }

                    persistedTasks.forEach { entity ->
                        if (entity.id !in tasksToCancel) {
                            tasksToCancel[entity.id] =
                                activeTasks[entity.id] ?: requestTaskFromEntity(entity)
                        }
                    }

                    tasksToCancel.values.forEach { task ->

                        val accepted = synchronized(task) {
                            if (
                                task.terminalHandled.get() ||
                                task.userCancellationRequested.get()
                            ) {
                                false
                            } else {
                                task.cancellationRequested.set(true)
                                task.userCancellationRequested.set(true)
                                task.message = "Cancelling all downloads..."

                                notificationManager.cancel(task.notificationId)

                                true
                            }
                        }

                        if (!accepted) return@forEach

                        DownloadUiStateStore.updateTask(
                            id = task.request.taskId,
                            progress = task.progress,
                            status = task.status,
                            message = task.message
                        )

                        if (task.started.get()) {
                            task.workerThread?.interrupt()
                        } else {
                            task.future?.cancel(true)

                            finishTask(
                                task = task,
                                status = DownloadTaskUiState.STATUS_CANCELLED,
                                message = "Download cancelled."
                            )
                        }
                    }
                }

                updateSummaryNotification()
                stopIfIdle()

            } catch (cancelled: CancellationException) {
                throw cancelled

            } catch (error: Exception) {
                Log.e(TAG, "Could not cancel all downloads", error)
            }
        }
    }

    private fun registerNetworkMonitor() {
        networkAvailable = hasValidatedInternet()

        try {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    updateNetworkAvailability(validated)
                }

                override fun onLost(network: Network) {
                    updateNetworkAvailability(hasValidatedInternet())
                }
            }
            connectivityManager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (error: Exception) {
            Log.w(TAG, "Could not register network callback", error)
        }
    }

    private fun unregisterNetworkMonitor() {
        val callback = networkCallback ?: return
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        networkCallback = null
    }

    private fun hasValidatedInternet(): Boolean {
        return runCatching {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }.getOrDefault(false)
    }

    private fun updateNetworkAvailability(available: Boolean) {
        val previous = networkAvailable
        networkAvailable = available
        if (previous == available) return

        activeTasks.values.forEach { task ->
            if (task.status == DownloadTaskUiState.STATUS_QUEUED) return@forEach
            if (available && task.status == DownloadTaskUiState.STATUS_WAITING_FOR_NETWORK) {
                setTaskState(task, DownloadTaskUiState.STATUS_RUNNING, "عاد الاتصال بالإنترنت؛ جارٍ استكمال التنزيل.")
            } else if (
                !available &&
                task.status == DownloadTaskUiState.STATUS_RUNNING &&
                isNetworkTransferStage(task.message)
            ) {
                setTaskState(task, DownloadTaskUiState.STATUS_WAITING_FOR_NETWORK, "انقطع الإنترنت؛ يحتفظ yt-dlp بالتقدم ويحاول الاستكمال تلقائيًا.")
            }
        }
        updateSummaryNotification()
    }

    private fun isNetworkTransferStage(message: String): Boolean {
        val value = message.lowercase()
        return value.contains("تنزيل الفيديو") ||
                value.contains("تنزيل الصوت") ||
                value.contains("تجربة صيغة الصوت") ||
                value.contains("بدء التنزيل") ||
                value.contains("extracting") ||
                value.contains("downloading")
    }

    private fun updateForegroundStage(task: ActiveTask, message: String) {
        val normalized = message.lowercase()
        val processing = normalized.contains("mp4") ||
                normalized.contains("معالجة الفيديو") ||
                normalized.contains("تحويل الفيديو")

        if (processing) processingTaskIds.add(task.request.taskId)
        else processingTaskIds.remove(task.request.taskId)

        updateForegroundType()
    }

    private fun updateForegroundType() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !foregroundStarted) return

        val dataSync = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        val mediaProcessing = if (Build.VERSION.SDK_INT >= 35) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            0
        }
        val requestedType = if (
            processingTaskIds.isNotEmpty() &&
            mediaProcessing != 0
        ) {
            mediaProcessing
        } else {
            dataSync
        }

        if (requestedType == currentForegroundType) return
        mainHandler.post {
            if (!foregroundStarted || requestedType == currentForegroundType) return@post
            try {
                startForeground(
                    FOREGROUND_NOTIFICATION_ID,
                    buildSummaryNotification(),
                    requestedType
                )
                currentForegroundType = requestedType
                Log.i(TAG, "Foreground service types updated: $requestedType")
            } catch (error: Exception) {
                Log.e(TAG, "Could not update foreground service type", error)
            }
        }
    }

    private fun startAsForeground() {
        val notification = buildSummaryNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            currentForegroundType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            startForeground(FOREGROUND_NOTIFICATION_ID, notification, currentForegroundType)
        } else {
            @Suppress("DEPRECATION")
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }



    private fun buildSummaryNotification(): Notification {
        val tasks = activeTasks.values.toList()

        val runningCount = tasks.count {
            it.status == DownloadTaskUiState.STATUS_RUNNING
        }

        val waitingCount = tasks.count {
            it.status == DownloadTaskUiState.STATUS_WAITING_FOR_NETWORK
        }

        val queuedCount = tasks.count {
            it.status == DownloadTaskUiState.STATUS_QUEUED
        }

        val progressTasks = tasks.filter {
            it.status == DownloadTaskUiState.STATUS_RUNNING ||
                    it.status == DownloadTaskUiState.STATUS_WAITING_FOR_NETWORK
        }

        val averageProgress = if (progressTasks.isEmpty()) {
            0
        } else {
            progressTasks.map { it.progress }
                .average()
                .roundToInt()
                .coerceIn(0, 100)
        }

        val summary = when {
            waitingCount > 0 && runningCount > 0 ->
                "جاري التنزيل • بعض المهام تنتظر الإنترنت"

            waitingCount > 0 ->
                "$waitingCount تنزيلات في انتظار الإنترنت"

            runningCount > 0 && queuedCount > 0 ->
                "جاري تنزيل $runningCount • $queuedCount في الانتظار"

            runningCount > 0 ->
                "جاري تنزيل $runningCount فيديو"

            queuedCount > 0 ->
                "$queuedCount فيديو في قائمة الانتظار"

            else ->
                "لا توجد تنزيلات نشطة"
        }

        fun createRemoteViews(
            layoutId: Int
        ): android.widget.RemoteViews {
            return android.widget.RemoteViews(packageName, layoutId).apply {
                setTextViewText(
                    R.id.notification_title,
                    "مدير التنزيلات"
                )

                setTextViewText(
                    R.id.notification_status,
                    summary
                )

                setTextViewText(
                    R.id.notification_percent,
                    "$averageProgress%"
                )

                setProgressBar(
                    R.id.notification_progress,
                    100,
                    averageProgress,
                    waitingCount > 0
                )

                if (layoutId == R.layout.notification_download_expanded) {
                    setTextViewText(
                        R.id.notification_cancel,
                        "إلغاء جميع التنزيلات"
                    )

                    setViewVisibility(
                        R.id.notification_cancel,
                        if (tasks.isNotEmpty()) View.VISIBLE else View.GONE
                    )

                    if (tasks.isNotEmpty()) {
                        setOnClickPendingIntent(
                            R.id.notification_cancel,
                            cancelAllPendingIntent()
                        )
                    }
                }
            }
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setColor(android.graphics.Color.rgb(57, 136, 232))
            .setContentTitle("مدير التنزيلات")
            .setContentText(summary)
            .setSubText("VIDEO DOWNLOADER")
            .setCustomContentView(
                createRemoteViews(R.layout.notification_download_compact)
            )
            .setCustomBigContentView(
                createRemoteViews(R.layout.notification_download_expanded)
            )
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setContentIntent(mainPendingIntent())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(true)
            .setAutoCancel(false)
            .setGroup(NOTIFICATION_GROUP)
            .setGroupSummary(true)
            .build()
    }



    private fun buildTaskNotification(
        task: ActiveTask,
        ongoing: Boolean
    ): Notification {
        val title = task.request.title.ifBlank {
            "فيديو قيد التنزيل"
        }

        val percent = task.progress.roundToInt().coerceIn(0, 100)

        val statusTitle = when (task.status) {
            DownloadTaskUiState.STATUS_QUEUED ->
                "في قائمة الانتظار"

            DownloadTaskUiState.STATUS_WAITING_FOR_NETWORK ->
                "في انتظار الاتصال بالإنترنت"

            DownloadTaskUiState.STATUS_SUCCEEDED ->
                "اكتمل التنزيل بنجاح"

            DownloadTaskUiState.STATUS_FAILED ->
                "تعذر إكمال التنزيل"

            DownloadTaskUiState.STATUS_CANCELLED ->
                "تم إلغاء التنزيل"

            else ->
                task.message.ifBlank { "جاري تنزيل الفيديو" }
        }

        fun createRemoteViews(
            layoutId: Int
        ): android.widget.RemoteViews {
            return android.widget.RemoteViews(packageName, layoutId).apply {
                setTextViewText(
                    R.id.notification_title,
                    title
                )

                setTextViewText(
                    R.id.notification_status,
                    statusTitle
                )

                setTextViewText(
                    R.id.notification_percent,
                    "$percent%"
                )

                setProgressBar(
                    R.id.notification_progress,
                    100,
                    percent,
                    ongoing &&
                            task.status == DownloadTaskUiState.STATUS_WAITING_FOR_NETWORK
                )

                if (layoutId == R.layout.notification_download_expanded) {
                    setViewVisibility(
                        R.id.notification_cancel,
                        if (ongoing) View.VISIBLE else View.GONE
                    )

                    if (ongoing) {
                        setOnClickPendingIntent(
                            R.id.notification_cancel,
                            cancelPendingIntent(
                                task.request.taskId,
                                task.notificationId
                            )
                        )
                    }
                }
            }
        }

        val compact = createRemoteViews(
            R.layout.notification_download_compact
        )

        val expanded = createRemoteViews(
            R.layout.notification_download_expanded
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(
                when {
                    ongoing ->
                        android.R.drawable.stat_sys_download

                    task.status == DownloadTaskUiState.STATUS_SUCCEEDED ->
                        android.R.drawable.stat_sys_download_done

                    else ->
                        android.R.drawable.stat_notify_error
                }
            )
            .setColor(android.graphics.Color.rgb(57, 136, 232))
            .setContentTitle(title)
            .setContentText("$statusTitle • $percent%")
            .setSubText("VIDEO DOWNLOADER")
            .setCustomContentView(compact)
            .setCustomBigContentView(expanded)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setContentIntent(mainPendingIntent())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setGroup(NOTIFICATION_GROUP)
            .build()
    }

    private fun buildResultNotification(message: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Video Downloader")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(mainPendingIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()
    }

    private fun mainPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            this,
            CONTENT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun cancelPendingIntent(taskId: String, requestCode: Int): PendingIntent {
        val cancelIntent = Intent(this, DownloadForegroundService::class.java).apply {
            action = ACTION_CANCEL
            putExtra(EXTRA_TASK_ID, taskId)
        }
        return PendingIntent.getService(
            this,
            requestCode,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun cancelAllPendingIntent(): PendingIntent {
        val cancelIntent = Intent(this, DownloadForegroundService::class.java).apply {
            action = ACTION_CANCEL_ALL
        }
        return PendingIntent.getService(
            this,
            CANCEL_ALL_REQUEST_CODE,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun notifyTaskAndSummary(task: ActiveTask) {

        synchronized(task) {

            if (
                task.userCancellationRequested.get() ||
                task.terminalHandled.get()
            ) {
                return
            }

            val percent = task.progress.roundToInt().coerceIn(0, 100)

            val changed = percent != task.lastNotifiedPercent ||
                    task.status != task.lastNotifiedStatus ||
                    task.message != task.lastNotifiedMessage

            if (!changed) return

            task.lastNotifiedPercent = percent
            task.lastNotifiedStatus = task.status
            task.lastNotifiedMessage = task.message

            if (task.status != DownloadTaskUiState.STATUS_CANCELLED) {
                notificationManager.notify(
                    task.notificationId,
                    buildTaskNotification(task, ongoing = true)
                )
            }
        }

        updateSummaryNotification()
    }

    private fun updateSummaryNotification() {
        if (foregroundStarted) {
            notificationManager.notify(FOREGROUND_NOTIFICATION_ID, buildSummaryNotification())
        }
    }

    /** Allocate collision-free notification IDs for all tasks in this service instance. */
    private fun allocateNotificationId(): Int {
        while (true) {
            val candidate = nextNotificationId.getAndUpdate { current ->
                if (current >= TASK_NOTIFICATION_ID_BASE + TASK_NOTIFICATION_ID_RANGE - 1) {
                    TASK_NOTIFICATION_ID_BASE
                } else {
                    current + 1
                }
            }
            if (allocatedNotificationIds.add(candidate)) return candidate
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "تنزيل الفيديوهات",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "إظهار تقدم كل تنزيل وحالة قائمة التنزيلات"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLockIfNeeded() {
        val existing = wakeLock
        if (existing?.isHeld == true) return
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:VideoDownloader"
            ).apply {
                setReferenceCounted(false)
                acquire(MAX_WAKE_LOCK_MS)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not acquire partial wake lock", error)
        }
    }

    private fun releaseWakeLockIfIdle() {
        val hasExecutingTask = activeTasks.values.any {
            it.status == DownloadTaskUiState.STATUS_RUNNING ||
                it.status == DownloadTaskUiState.STATUS_WAITING_FOR_NETWORK
        }
        if (!hasExecutingTask) releaseWakeLock()
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let { lock -> if (lock.isHeld) lock.release() }
        }.onFailure { Log.w(TAG, "Could not release partial wake lock", it) }
        wakeLock = null
    }

    private fun stopIfIdle(startId: Int? = null) {
        mainHandler.post {

            if (
                serviceShuttingDown ||
                activeTasks.isNotEmpty() ||
                queuePumpRunning.get() ||
                pendingEnqueueCount.get() > 0
            ) {
                return@post
            }

            val idToStop = startId ?: latestStartId

            val stopped = if (idToStop > 0) {
                stopSelfResult(idToStop)
            } else {
                stopSelf()
                true
            }

            if (stopped) {
                if (foregroundStarted) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }

                foregroundStarted = false
                releaseWakeLock()
            }
        }
    }

    private fun decodeRequest(intent: Intent): DownloadRequest {
        val url = intent.getStringExtra(EXTRA_URL)
        val title = intent.getStringExtra(EXTRA_TITLE)
        val videoJson = intent.getStringExtra(EXTRA_VIDEO_JSON)
        val audioJson = intent.getStringExtra(EXTRA_AUDIO_JSON)
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()

        if (url.isNullOrBlank() || videoJson.isNullOrBlank() || audioJson.isNullOrBlank()) {
            throw IOException("بيانات طلب التنزيل غير مكتملة.")
        }

        val video = JSONObject(videoJson)
        val selectedFormat = VideoFormat(
            formatId = video.getString("formatId"),
            height = video.getInt("height"),
            extension = video.getString("extension"),
            fileSize = nullableFileSize(video),
            sizeIsEstimate = video.getBoolean("sizeIsEstimate"),
            hasAudio = video.getBoolean("hasAudio"),
            fps = nullableFps(video),
            qualityTier = QualityTier.valueOf(video.getString("qualityTier")),
            videoCodec = nullableString(video, "videoCodec"),
            audioCodec = nullableString(video, "audioCodec"),
            protocol = nullableString(video, "protocol")
        )

        val audioArray = JSONArray(audioJson)
        val audioFormats = (0 until audioArray.length()).map { index ->
            val item = audioArray.getJSONObject(index)
            AudioFormat(
                formatId = item.getString("formatId"),
                bitrate = item.optDouble("bitrate", 0.0),
                extension = item.optString("extension", "unknown"),
                fileSize = nullableFileSize(item),
                codec = nullableString(item, "codec"),
                sizeIsEstimate = item.optBoolean("sizeIsEstimate", true),
                language = nullableString(item, "language"),
                formatNote = nullableString(item, "formatNote"),
                languagePreference = nullableLanguagePreference(item)
            )
        }

        return DownloadRequest(
            taskId = taskId,
            url = url,
            title = title.orEmpty(),
            selectedFormat = selectedFormat,
            audioFormats = audioFormats,
            openOnFinish = intent.getBooleanExtra(EXTRA_OPEN_ON_FINISH, true)
        )
    }

    private fun requestTaskFromEntity(entity: DownloadQueueEntity): ActiveTask {
        val request = DownloadRequest(
            taskId = entity.id,
            url = entity.url,
            title = entity.title,
            selectedFormat = DownloadQueueFormatCodec.decodeVideo(entity.selectedVideoJson),
            audioFormats = DownloadQueueFormatCodec.decodeAudio(entity.audioFormatsJson),
            openOnFinish = entity.openOnFinish
        )
        return ActiveTask(request, entity.notificationId).apply {
            progress = entity.progress.coerceIn(0f, 100f)
            status = entity.status
            message = entity.message
        }
    }

    private fun nullableString(json: JSONObject, key: String): String? {
        if (json.isNull(key)) return null
        return json.optString(key).takeIf { it.isNotBlank() && it != "null" }
    }

    private fun nullableFileSize(json: JSONObject): Long? {
        if (json.isNull("fileSize")) return null
        return json.optLong("fileSize").takeIf { it > 0L }
    }

    private fun nullableLanguagePreference(json: JSONObject): Int? {
        if (json.isNull("languagePreference")) return null
        return json.optInt("languagePreference")
    }

    private fun nullableFps(json: JSONObject): Double? {
        if (json.isNull("fps")) return null
        return json.optDouble("fps").takeIf { it.isFinite() && it > 0.0 }
    }

    companion object {
        private const val TAG = "VideoDownloader"
        private const val CHANNEL_ID = "video_download_progress"
        private const val NOTIFICATION_GROUP = "video_download_tasks"
        private const val FOREGROUND_NOTIFICATION_ID = 7301
        private const val ERROR_NOTIFICATION_ID = 7304
        private const val CONTENT_REQUEST_CODE = 7302
        private const val CANCEL_ALL_REQUEST_CODE = 7305
        private const val TASK_NOTIFICATION_ID_BASE = 10_000
        private const val TASK_NOTIFICATION_ID_RANGE = 900_000
        private const val MAX_CONCURRENT_DOWNLOADS = 1
        private const val MAX_WAKE_LOCK_MS = 6L * 60L * 60L * 1000L

        private const val ACTION_START = "com.example.videodownloader.action.START_DOWNLOAD"
        private const val ACTION_CANCEL = "com.example.videodownloader.action.CANCEL_DOWNLOAD"
        private const val ACTION_CANCEL_ALL = "com.example.videodownloader.action.CANCEL_ALL_DOWNLOADS"
        private const val ACTION_RESUME_QUEUE = "com.example.videodownloader.action.RESUME_QUEUE"

        private const val EXTRA_URL = "download_url"
        private const val EXTRA_TITLE = "download_title"
        private const val EXTRA_VIDEO_JSON = "download_video_json"
        private const val EXTRA_AUDIO_JSON = "download_audio_json"
        private const val EXTRA_TASK_ID = "download_task_id"
        private const val EXTRA_OPEN_ON_FINISH = "download_open_on_finish"

        internal fun createCancelIntent(
            context: Context,
            taskId: String
        ): Intent = Intent(context, DownloadForegroundService::class.java).apply {
            action = ACTION_CANCEL
            putExtra(EXTRA_TASK_ID, taskId)
        }

        internal fun createCancelAllIntent(context: Context): Intent =
            Intent(context, DownloadForegroundService::class.java).apply {
                action = ACTION_CANCEL_ALL
            }

        internal fun createResumeIntent(context: Context): Intent =
            Intent(context, DownloadForegroundService::class.java).apply {
                action = ACTION_RESUME_QUEUE
            }

        internal fun createStartIntent(
            context: Context,
            url: String,
            title: String,
            selectedFormat: VideoFormat,
            audioFormats: List<AudioFormat>,
            taskId: String = UUID.randomUUID().toString(),
            openOnFinish: Boolean = true
        ): Intent {
            val videoJson = JSONObject().apply {
                put("formatId", selectedFormat.formatId)
                put("height", selectedFormat.height)
                put("extension", selectedFormat.extension)
                put("fileSize", selectedFormat.fileSize ?: JSONObject.NULL)
                put("sizeIsEstimate", selectedFormat.sizeIsEstimate)
                put("hasAudio", selectedFormat.hasAudio)
                put("fps", selectedFormat.fps ?: JSONObject.NULL)
                put("qualityTier", selectedFormat.qualityTier.name)
                put("videoCodec", selectedFormat.videoCodec ?: JSONObject.NULL)
                put("audioCodec", selectedFormat.audioCodec ?: JSONObject.NULL)
                put("protocol", selectedFormat.protocol ?: JSONObject.NULL)
            }

            val audioJson = JSONArray().apply {
                audioFormats.forEach { audio ->
                    put(JSONObject().apply {
                        put("formatId", audio.formatId)
                        put("bitrate", audio.bitrate)
                        put("extension", audio.extension)
                        put("fileSize", audio.fileSize ?: JSONObject.NULL)
                        put("codec", audio.codec ?: JSONObject.NULL)
                        put("sizeIsEstimate", audio.sizeIsEstimate)
                        put("language", audio.language ?: JSONObject.NULL)
                        put("formatNote", audio.formatNote ?: JSONObject.NULL)
                        put("languagePreference", audio.languagePreference ?: JSONObject.NULL)
                    })
                }
            }

            return Intent(context, DownloadForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_VIDEO_JSON, videoJson.toString())
                putExtra(EXTRA_AUDIO_JSON, audioJson.toString())
                putExtra(EXTRA_TASK_ID, taskId)
                putExtra(EXTRA_OPEN_ON_FINISH, openOnFinish)
            }
        }
    }
}
