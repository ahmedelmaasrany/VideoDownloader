package com.example.videodownloader

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Persistent representation of one download request.
 *
 * Video/audio format selections are stored as JSON strings so the
 * existing VideoFormat and AudioFormat models do not need to change.
 */
@Entity(
    tableName = "download_queue",
    indices = [Index(value = ["notificationId"], unique = true)]
)
data class DownloadQueueEntity(
    @PrimaryKey
    val id: String,

    val queuePosition: Long,

    val url: String,

    val title: String,

    val selectedVideoJson: String,

    val audioFormatsJson: String,

    val openOnFinish: Boolean,

    val status: String = DownloadTaskUiState.STATUS_QUEUED,

    val progress: Float = 0f,

    val message: String = "في قائمة الانتظار",

    val savedUri: String? = null,

    val createdAt: Long = System.currentTimeMillis(),

    val updatedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(defaultValue = "0")
    val notificationId: Int = 0
)

/**
 * Database operations for the persistent download queue.
 */
@Dao
interface DownloadQueueDao {

    @Query(
        """
        SELECT * FROM download_queue
        ORDER BY queuePosition ASC, createdAt ASC
        """
    )
    fun observeAll(): Flow<List<DownloadQueueEntity>>
    @Query(
        """
    SELECT * FROM download_queue
    WHERE status IN ('QUEUED', 'RUNNING', 'WAITING_FOR_NETWORK')
    ORDER BY queuePosition ASC, createdAt ASC
    """
    )
    fun observeActive(): Flow<List<DownloadQueueEntity>>

    @Query(
        """
    SELECT * FROM download_queue
    WHERE status IN ('QUEUED', 'RUNNING', 'WAITING_FOR_NETWORK')
    ORDER BY queuePosition ASC, createdAt ASC
    """
    )
    suspend fun getActiveTasks(): List<DownloadQueueEntity>
    @Query(
        """
        SELECT * FROM download_queue
        ORDER BY queuePosition ASC, createdAt ASC
        """
    )
    suspend fun getAll(): List<DownloadQueueEntity>

    @Query(
        """
        SELECT * FROM download_queue
        WHERE id = :taskId
        LIMIT 1
        """
    )
    suspend fun getById(taskId: String): DownloadQueueEntity?

    @Query(
        """
        SELECT * FROM download_queue
        WHERE status = 'QUEUED'
        ORDER BY queuePosition ASC, createdAt ASC
        LIMIT 1
        """
    )
    suspend fun getNextPending(): DownloadQueueEntity?

    /**
     * Atomically move the oldest queued task to RUNNING and return its latest row.
     * The transaction prevents two queue pumps from claiming the same task.
     */
    @Transaction
    suspend fun claimNextQueued(): DownloadQueueEntity? {
        val next = getNextPending() ?: return null
        val changed = markQueuedAsRunning(next.id)
        return if (changed == 1) getById(next.id) else null
    }

    @Query(
        """
        UPDATE download_queue
        SET status = 'RUNNING',
            updatedAt = :updatedAt
        WHERE id = :taskId AND status = 'QUEUED'
        """
    )
    suspend fun markQueuedAsRunning(
        taskId: String,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        """
        SELECT COALESCE(MAX(notificationId), 9999) + 1
        FROM download_queue
        """
    )
    suspend fun getNextNotificationId(): Int

    @Query(
        """
        SELECT COALESCE(MAX(queuePosition), -1) + 1
        FROM download_queue
        """
    )
    suspend fun getNextQueuePosition(): Long


    /**
     * Insert a task at the end of the queue atomically.
     */
    @Transaction
    suspend fun insertAtEnd(task: DownloadQueueEntity) {
        val nextPosition = getNextQueuePosition()
        val nextNotificationId = getNextNotificationId()
        require(nextNotificationId in 10_000..909_999) {
            "No notification IDs are available."
        }
        insert(
            task.copy(
                queuePosition = nextPosition,
                notificationId = nextNotificationId
            )
        )
    }

    /**
     * Recover tasks interrupted by process termination.
     */
    @Query(
        """
        UPDATE download_queue
        SET status = 'QUEUED',
            message = 'أعيدت إلى قائمة الانتظار بعد توقف التطبيق',
            updatedAt = :updatedAt
        WHERE status IN ('RUNNING', 'WAITING_FOR_NETWORK')
        """
    )
    suspend fun requeueInterruptedTasks(
        updatedAt: Long = System.currentTimeMillis()
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(task: DownloadQueueEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(tasks: List<DownloadQueueEntity>)

    @Update
    suspend fun update(task: DownloadQueueEntity)

    @Query(
        """
        UPDATE download_queue
        SET status = :status,
            progress = :progress,
            message = :message,
            savedUri = :savedUri,
            updatedAt = :updatedAt
        WHERE id = :taskId
        """
    )
    suspend fun updateTaskState(
        taskId: String,
        status: String,
        progress: Float,
        message: String,
        savedUri: String?,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query(
        """
        UPDATE download_queue
        SET queuePosition = :newPosition,
            updatedAt = :updatedAt
        WHERE id = :taskId
        """
    )
    suspend fun updateQueuePosition(
        taskId: String,
        newPosition: Long,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query(
        """
        SELECT id FROM download_queue
        WHERE status = 'QUEUED'
        ORDER BY queuePosition ASC, createdAt ASC
        """
    )
    suspend fun getQueuedTaskIds(): List<String>

    @Query(
        """
        SELECT COALESCE(MAX(queuePosition), -1) + 1
        FROM download_queue
        WHERE status IN ('RUNNING', 'WAITING_FOR_NETWORK')
        """
    )
    suspend fun getFirstQueuedPositionAfterFixedTasks(): Long

    @Query(
        """
        UPDATE download_queue
        SET queuePosition = :newPosition,
            updatedAt = :updatedAt
        WHERE id = :taskId AND status = 'QUEUED'
        """
    )
    suspend fun updateQueuedTaskPosition(
        taskId: String,
        newPosition: Long,
        updatedAt: Long
    ): Int

    /**
     * Reorders only QUEUED tasks.
     * Rejects stale orders if the queue changed during dragging.
     */
    @Transaction
    suspend fun reorderQueuedTasks(orderedTaskIds: List<String>): Boolean {
        val currentQueuedIds = getQueuedTaskIds()

        if (orderedTaskIds.size != currentQueuedIds.size) {
            return false
        }

        val requestedIds = orderedTaskIds.toSet()

        if (
            requestedIds.size != orderedTaskIds.size ||
            requestedIds != currentQueuedIds.toSet()
        ) {
            return false
        }

        val firstPosition = getFirstQueuedPositionAfterFixedTasks()
        val updatedAt = System.currentTimeMillis()

        orderedTaskIds.forEachIndexed { index, taskId ->
            check(
                updateQueuedTaskPosition(
                    taskId = taskId,
                    newPosition = firstPosition + index,
                    updatedAt = updatedAt
                ) == 1
            ) {
                "Failed to update queued task position: $taskId"
            }
        }

        return true
    }

    @Query(
        """
    SELECT * FROM download_queue
    WHERE status IN ('QUEUED', 'RUNNING', 'WAITING_FOR_NETWORK')
    ORDER BY queuePosition ASC, createdAt ASC
    """
    )
    suspend fun getActiveTasksForCancellation(): List<DownloadQueueEntity>

    @Query(
        """
    DELETE FROM download_queue
    WHERE status IN ('QUEUED', 'RUNNING', 'WAITING_FOR_NETWORK')
    """
    )
    suspend fun deleteAllActiveRows(): Int

    @Transaction
    suspend fun deleteAllActiveAndReturn(): List<DownloadQueueEntity> {
        val activeTasks = getActiveTasksForCancellation()
        deleteAllActiveRows()
        return activeTasks
    }
    @Query("DELETE FROM download_queue WHERE id = :taskId")
    suspend fun deleteById(taskId: String)

    @Query("DELETE FROM download_queue")
    suspend fun deleteAll()

    @Query(
        """
        SELECT COUNT(*) FROM download_queue
        WHERE status IN ('QUEUED', 'RUNNING', 'WAITING_FOR_NETWORK')
        """
    )
    suspend fun getPendingCount(): Int
}

/**
 * Application-wide persistent download database.
 *
 * The singleton uses the application context to avoid retaining an Activity.
 */
@Database(
    entities = [DownloadQueueEntity::class],
    version = 2,
    exportSchema = true
)
abstract class DownloadQueueDatabase : RoomDatabase() {

    abstract fun downloadQueueDao(): DownloadQueueDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `download_queue` ADD COLUMN `notificationId` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE `download_queue` SET `notificationId` = 10000 + rowid")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_download_queue_notificationId` ON `download_queue` (`notificationId`)")
            }
        }

        @Volatile
        private var INSTANCE: DownloadQueueDatabase? = null

        fun getInstance(context: Context): DownloadQueueDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    DownloadQueueDatabase::class.java,
                    "video_downloader_queue.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build().also { database ->
                    INSTANCE = database
                }
            }
        }
    }
}
