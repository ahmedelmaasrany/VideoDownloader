package com.example.videodownloader

import android.content.Context
import androidx.core.net.toUri
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class DownloadQueueRepository private constructor(context: Context) {

    private val dao = DownloadQueueDatabase.getInstance(
        context.applicationContext
    ).downloadQueueDao()

    val tasks: Flow<List<DownloadQueueEntity>> = dao.observeActive()

    internal suspend fun enqueue(
        url: String,
        title: String,
        selectedFormat: VideoFormat,
        audioFormats: List<AudioFormat>,
        openOnFinish: Boolean = true,
        taskId: String = UUID.randomUUID().toString()
    ): String {
        val cleanUrl = url.trim()
        val uri = cleanUrl.toUri()

        require(
            (uri.scheme == "https" || uri.scheme == "http") &&
                !uri.host.isNullOrBlank()
        ) {
            "A valid HTTP or HTTPS URL is required."
        }

        require(selectedFormat.isAvailable) {
            "The selected video format is not available."
        }

        require(taskId.isNotBlank()) {
            "Task ID cannot be blank."
        }

        val now = System.currentTimeMillis()

        dao.insertAtEnd(
            DownloadQueueEntity(
                id = taskId,
                queuePosition = 0L,
                url = cleanUrl,
                title = title,
                selectedVideoJson =
                    DownloadQueueFormatCodec.encodeVideo(selectedFormat),
                audioFormatsJson =
                    DownloadQueueFormatCodec.encodeAudio(audioFormats),
                openOnFinish = openOnFinish,
                status = DownloadTaskUiState.STATUS_QUEUED,
                progress = 0f,
                message = "في قائمة الانتظار",
                savedUri = null,
                createdAt = now,
                updatedAt = now
            )
        )

        return taskId
    }

    suspend fun getAll(): List<DownloadQueueEntity> =
        dao.getAll()

    suspend fun getActiveTasks(): List<DownloadQueueEntity> =
        dao.getActiveTasks()
    suspend fun getTask(taskId: String): DownloadQueueEntity? =
        dao.getById(taskId)

    suspend fun getNextQueued(): DownloadQueueEntity? =
        dao.getNextPending()
    suspend fun reorderQueuedTasks(
        orderedTaskIds: List<String>
    ): Boolean {
        return dao.reorderQueuedTasks(orderedTaskIds)
    }
    suspend fun claimNextQueued(): DownloadQueueEntity? =
        dao.claimNextQueued()

    suspend fun getPendingCount(): Int =
        dao.getPendingCount()
    suspend fun deleteTask(taskId: String) {
        dao.deleteById(taskId)
    }

    suspend fun deleteAllActiveAndReturn(): List<DownloadQueueEntity> =
        dao.deleteAllActiveAndReturn()

    /** Keeps only the newest terminal records; active queue rows are never selected. */
    suspend fun pruneTerminalHistory(keepNewest: Int = 100): Int =
        dao.pruneTerminalHistory(keepNewest.coerceAtLeast(0))
    suspend fun updateTaskState(
        taskId: String,
        status: String,
        progress: Float,
        message: String,
        savedUri: String? = null
    ) {
        dao.updateTaskState(
            taskId = taskId,
            status = status,
            progress = progress,
            message = message,
            savedUri = savedUri
        )
    }

    suspend fun recoverInterruptedTasks() {
        dao.requeueInterruptedTasks()
    }

    companion object {
        @Volatile
        private var INSTANCE: DownloadQueueRepository? = null

        fun getInstance(context: Context): DownloadQueueRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DownloadQueueRepository(
                    context.applicationContext
                ).also { INSTANCE = it }
            }
        }
    }
}

internal object DownloadQueueFormatCodec {

    fun encodeVideo(format: VideoFormat): String =
        JSONObject().apply {
            put("formatId", format.formatId)
            put("height", format.height)
            put("extension", format.extension)
            put("fileSize", format.fileSize ?: JSONObject.NULL)
            put("sizeIsEstimate", format.sizeIsEstimate)
            put("hasAudio", format.hasAudio)
            put("fps", format.fps ?: JSONObject.NULL)
            put("qualityTier", format.qualityTier.name)
            put("videoCodec", format.videoCodec ?: JSONObject.NULL)
            put("audioCodec", format.audioCodec ?: JSONObject.NULL)
            put("protocol", format.protocol ?: JSONObject.NULL)
            put("videoDownloadSizeBytes", format.videoDownloadSizeBytes ?: JSONObject.NULL)
            put("audioDownloadSizeBytes", format.audioDownloadSizeBytes ?: JSONObject.NULL)
            put("videoBitrateKbps", format.videoBitrateKbps ?: JSONObject.NULL)
            put("plannedAudioFormatId", format.plannedAudioFormatId ?: JSONObject.NULL)
            put("estimatedTotalMs", format.estimatedTotalMs ?: JSONObject.NULL)
        }.toString()

    fun decodeVideo(json: String): VideoFormat {
        val item = JSONObject(json)

        return VideoFormat(
            formatId = item.getString("formatId"),
            height = item.getInt("height"),
            extension = item.getString("extension"),
            fileSize = nullableLong(item, "fileSize"),
            sizeIsEstimate = item.getBoolean("sizeIsEstimate"),
            hasAudio = item.getBoolean("hasAudio"),
            fps = nullableDouble(item, "fps"),
            qualityTier = QualityTier.valueOf(
                item.getString("qualityTier")
            ),
            videoCodec = nullableString(item, "videoCodec"),
            audioCodec = nullableString(item, "audioCodec"),
            protocol = nullableString(item, "protocol"),
            videoDownloadSizeBytes = nullableLong(item, "videoDownloadSizeBytes"),
            audioDownloadSizeBytes = nullableLong(item, "audioDownloadSizeBytes"),
            videoBitrateKbps = nullableDouble(item, "videoBitrateKbps"),
            plannedAudioFormatId = nullableString(item, "plannedAudioFormatId"),
            estimatedTotalMs = nullableLong(item, "estimatedTotalMs")
        )
    }

    fun encodeAudio(formats: List<AudioFormat>): String =
        JSONArray().apply {
            formats.forEach { audio ->
                put(
                    JSONObject().apply {
                        put("formatId", audio.formatId)
                        put("bitrate", audio.bitrate)
                        put("extension", audio.extension)
                        put("fileSize", audio.fileSize ?: JSONObject.NULL)
                        put("codec", audio.codec ?: JSONObject.NULL)
                        put("sizeIsEstimate", audio.sizeIsEstimate)
                        put("language", audio.language ?: JSONObject.NULL)
                        put("formatNote", audio.formatNote ?: JSONObject.NULL)
                        put(
                            "languagePreference",
                            audio.languagePreference ?: JSONObject.NULL
                        )
                        put("protocol", audio.protocol ?: JSONObject.NULL)
                    }
                )
            }
        }.toString()

    fun decodeAudio(json: String): List<AudioFormat> {
        val array = JSONArray(json)

        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)

            AudioFormat(
                formatId = item.getString("formatId"),
                bitrate = item.optDouble("bitrate", 0.0),
                extension = item.optString("extension", "unknown"),
                fileSize = nullableLong(item, "fileSize"),
                codec = nullableString(item, "codec"),
                sizeIsEstimate = item.optBoolean("sizeIsEstimate", true),
                language = nullableString(item, "language"),
                formatNote = nullableString(item, "formatNote"),
                languagePreference = nullableInt(item, "languagePreference"),
                protocol = nullableString(item, "protocol")
            )
        }
    }

    private fun nullableString(json: JSONObject, key: String): String? =
        if (!json.has(key) || json.isNull(key)) null else json.getString(key)

    private fun nullableLong(json: JSONObject, key: String): Long? =
        if (!json.has(key) || json.isNull(key)) null else json.getLong(key)

    private fun nullableInt(json: JSONObject, key: String): Int? =
        if (!json.has(key) || json.isNull(key)) null else json.getInt(key)

    private fun nullableDouble(json: JSONObject, key: String): Double? =
        if (!json.has(key) || json.isNull(key)) null else json.getDouble(key)
}
