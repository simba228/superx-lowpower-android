package com.myAllVideoBrowser.util.downloaders

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.myAllVideoBrowser.data.local.VideoMetadataManager
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.data.repository.ProgressRepository
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.FileUtil
import com.myAllVideoBrowser.util.SharedPrefHelper
import com.myAllVideoBrowser.util.downloaders.custom_downloader.CustomRegularDownloader
import com.myAllVideoBrowser.util.downloaders.generic_downloader.IDownloader
import com.myAllVideoBrowser.util.downloaders.super_x_downloader.SuperXDownloader
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import com.myAllVideoBrowser.util.downloaders.youtubedl_downloader.YoutubeDlDownloader
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import com.myAllVideoBrowser.util.ContextUtils
import com.myAllVideoBrowser.util.Memory
import java.util.concurrent.TimeUnit

@Singleton
class SystemDownloadManager @Inject constructor(
    private val sharedPrefHelper: SharedPrefHelper,
    private val fileUtil: FileUtil,
    private val progressRepository: ProgressRepository
) : IDownloader {

    companion object {
        const val PENDING_EXT = ".PENDING"
        const val DOWNLOADING_EXT = ".DOWNLOADING"
        private const val RETRY_COUNT_EXT = ".RETRY_COUNT"
        private const val MAX_AUTOMATIC_REQUEUE_ROUNDS = 3
        private const val MIN_QUEUE_CHECK_DELAY_MS = 500L
        private const val ACTIVE_QUEUE_CHECK_INTERVAL_MS = 15_000L
        private const val WORK_START_TIMEOUT_MS = 30_000L
        private const val ACTIVE_NO_PROGRESS_TIMEOUT_MS = 60_000L
        private const val IMMEDIATE_QUEUE_WORK_NAME = "SYSTEM_DOWNLOAD_QUEUE_CHECK"
        private const val WATCHDOG_QUEUE_WORK_NAME = "SYSTEM_DOWNLOAD_QUEUE_WATCHDOG"
    }

    private fun getDownloader(videoInfo: VideoInfo): IDownloader {
        return when {
            videoInfo.isRegularDownload -> CustomRegularDownloader
            videoInfo.isDetectedBySuperX -> SuperXDownloader
            else -> YoutubeDlDownloader
        }
    }

    override fun startDownload(context: Context, videoInfo: VideoInfo) {
        AppLogger.d("SystemDownloadManager: startDownload ${videoInfo.id}")
        clearRetryCount(videoInfo.id)
        VideoMetadataManager.saveVideoInfo(videoInfo.id, videoInfo)
        createFlagFile(videoInfo.id, PENDING_EXT)
        checkQueue(context)
    }

    override fun pauseDownload(context: Context, videoInfo: VideoInfo) {
        AppLogger.d("SystemDownloadManager: pauseDownload ${videoInfo.id}")
        getDownloader(videoInfo).pauseDownload(context, videoInfo)
        removeFlagFiles(videoInfo.id)
        checkQueue(context)
    }

    @Synchronized
    fun pauseAllDownloads(context: Context, videoInfos: List<VideoInfo>) {
        // Do not refill the queue after pausing each item; otherwise a pending task can start
        // between two pause operations and "Pause all" never reaches a stable paused state.
        videoInfos.forEach { videoInfo ->
            AppLogger.d("SystemDownloadManager: pauseAll ${videoInfo.id}")
            getDownloader(videoInfo).pauseDownload(context, videoInfo)
            removeFlagFiles(videoInfo.id)
        }
    }

    override fun resumeDownload(context: Context, videoInfo: VideoInfo) {
        AppLogger.d("SystemDownloadManager: resumeDownload ${videoInfo.id}")
        clearRetryCount(videoInfo.id)
        VideoMetadataManager.saveVideoInfo(videoInfo.id, videoInfo)
        createFlagFile(videoInfo.id, PENDING_EXT)
        // A user explicitly pressing Resume should get the next available slot instead of
        // silently going behind every automatically queued/retried item.
        getFlagFile(videoInfo.id, PENDING_EXT)?.setLastModified(0L)
        checkQueue(context)
    }

    override fun cancelDownload(context: Context, videoInfo: VideoInfo, removeFile: Boolean) {
        AppLogger.d("SystemDownloadManager: cancelDownload ${videoInfo.id}")
        getDownloader(videoInfo).cancelDownload(context, videoInfo, removeFile)
        VideoMetadataManager.deleteVideoInfo(videoInfo.id)
        removeFlagFiles(videoInfo.id)
        clearRetryCount(videoInfo.id)
        checkQueue(context)
    }

    @Synchronized
    fun cancelDownloads(context: Context, videoInfos: List<VideoInfo>, removeFile: Boolean) {
        videoInfos.forEach { videoInfo ->
            AppLogger.d("SystemDownloadManager: cancel selected ${videoInfo.id}")
            getDownloader(videoInfo).cancelDownload(context, videoInfo, removeFile)
            VideoMetadataManager.deleteVideoInfo(videoInfo.id)
            removeFlagFiles(videoInfo.id)
            clearRetryCount(videoInfo.id)
        }
        checkQueue(context)
    }

    override fun stopAndSaveDownload(context: Context, videoInfo: VideoInfo) {
        AppLogger.d("SystemDownloadManager: stopAndSave ${videoInfo.id}")
        getDownloader(videoInfo).stopAndSaveDownload(context, videoInfo)
        VideoMetadataManager.deleteVideoInfo(videoInfo.id)
        removeFlagFiles(videoInfo.id)
        checkQueue(context)
    }

    override fun isWorkScheduled(context: Context, workId: String): Boolean {
        return getFlagFile(workId, DOWNLOADING_EXT)?.exists() == true ||
                getFlagFile(workId, PENDING_EXT)?.exists() == true
    }

    @Synchronized
    fun checkQueue(context: Context) {
        val tmpDir = fileUtil.tmpDir
        if (!tmpDir.exists()) tmpDir.mkdirs()

        val downloadingFlagFiles =
            tmpDir.listFiles { _, name -> name.endsWith(DOWNLOADING_EXT) } ?: emptyArray()
        val maxSimultaneous = sharedPrefHelper.getMaxSimultaneousDownloads()
        val reconciledFiles = reconcileDownloadingFlags(context, downloadingFlagFiles)
        val downloadingFiles = enforceConcurrencyLimit(
            context,
            reconciledFiles,
            maxSimultaneous
        )
        var nextQueueCheckDelayMs: Long? = null

        AppLogger.d("SystemDownloadManager: checkQueue. Active: ${downloadingFiles.size}, Max: $maxSimultaneous")

        if (downloadingFiles.size < maxSimultaneous) {
            if (Memory.isMemoryCritical(context)) {
                AppLogger.w("SystemDownloadManager: Memory is critical. Postponing queue.")
                return
            }

            val allPendingFiles =
                tmpDir.listFiles { _, name -> name.endsWith(PENDING_EXT) } ?: emptyArray()
            val now = System.currentTimeMillis()
            val visibleTaskIds = getVisibleTaskIdsOrNull()
            if (visibleTaskIds != null) {
                allPendingFiles
                    .filter { it.name.removeSuffix(PENDING_EXT) !in visibleTaskIds }
                    .forEach { pendingFile ->
                        val taskId = pendingFile.name.removeSuffix(PENDING_EXT)
                        pendingFile.delete()
                        VideoMetadataManager.deleteVideoInfo(taskId)
                        clearRetryCount(taskId)
                        AppLogger.w(
                            "SystemDownloadManager: removed invisible pending task $taskId before start"
                        )
                    }
            }
            val pendingFiles = allPendingFiles
                .filter { it.exists() }
                .filter { it.lastModified() <= now }
                .sortedBy { it.lastModified() }

            val slotsAvailable = maxSimultaneous - downloadingFiles.size
            for (i in 0 until minOf(slotsAvailable, pendingFiles.size)) {
                val pendingFile = pendingFiles[i]
                val taskId = pendingFile.name.removeSuffix(PENDING_EXT)
                val videoInfo = VideoMetadataManager.getVideoInfo(taskId)

                if (videoInfo != null) {
                    val downloadingFile = File(tmpDir, taskId + DOWNLOADING_EXT)
                    if (pendingFile.renameTo(downloadingFile)) {
                        AppLogger.d("SystemDownloadManager: Starting queued task $taskId $downloadingFile")
                        getDownloader(videoInfo).startDownload(context, videoInfo)
                    }
                } else {
                    AppLogger.w("SystemDownloadManager: VideoInfo not found for taskId $taskId")
                    pendingFile.delete()
                }
            }

            // A failed task uses a future last-modified timestamp as its retry eligibility.
            // If there is still a free slot, wake the queue when the first cooling task is due.
            allPendingFiles
                .filter { it.lastModified() > now }
                .minOfOrNull { it.lastModified() }
                ?.let { nextReadyAt ->
                    nextQueueCheckDelayMs =
                        (nextReadyAt - now).coerceAtLeast(MIN_QUEUE_CHECK_DELAY_MS)
                }
        }

        // Retry eligibility and active-work supervision use different unique work chains.
        // This prevents a watchdog from cancelling itself while still allowing an immediate
        // completion callback to replace an older delayed retry check.
        nextQueueCheckDelayMs?.let { scheduleQueueCheck(context, it) }

        // Keep supervising running work even when all slots are full. The HLS-local watchdog
        // cannot help if a worker is stuck before it reaches the segment download loop.
        if (downloadingFiles.isNotEmpty()) {
            scheduleWatchdogCheck(context, ACTIVE_QUEUE_CHECK_INTERVAL_MS)
        }
    }

    /**
     * A process kill, force-stop, or APK replacement can leave a .DOWNLOADING marker behind
     * after WorkManager has already stopped the corresponding worker. Those markers must not
     * consume concurrency forever. Keep markers backed by live unique work and move orphaned
     * tasks back to the queue so their downloaded fragments can be resumed.
     */
    private fun reconcileDownloadingFlags(
        context: Context,
        downloadingFiles: Array<File>
    ): List<File> {
        if (downloadingFiles.isEmpty()) return emptyList()

        val workManager = WorkManager.getInstance(context)
        val visibleTaskIds = getVisibleTaskIdsOrNull()
        return downloadingFiles.filter { downloadingFile ->
            val taskId = downloadingFile.name.removeSuffix(DOWNLOADING_EXT)
            if (visibleTaskIds != null && taskId !in visibleTaskIds) {
                // The user-facing task was deleted, but an older worker/marker can survive a
                // cancellation race. It must never remain invisible while consuming a slot.
                workManager.cancelUniqueWork(taskId)
                downloadingFile.delete()
                VideoMetadataManager.deleteVideoInfo(taskId)
                clearRetryCount(taskId)
                AppLogger.w(
                    "SystemDownloadManager: removed invisible active task $taskId and released its slot"
                )
                return@filter false
            }
            val activeStates = try {
                workManager.getWorkInfosForUniqueWork(taskId)
                    .get(2, TimeUnit.SECONDS)
                    .map { it.state }
                    .filter { !it.isFinished }
            } catch (error: Throwable) {
                // A temporary WorkManager query failure must not start a duplicate download.
                AppLogger.w(
                    "SystemDownloadManager: unable to verify active task $taskId; " +
                        "keeping its slot: ${error.message}"
                )
                return@filter true
            }

            val noProgressForMs = System.currentTimeMillis() - downloadingFile.lastModified()
            val isRunning = activeStates.any { it == WorkInfo.State.RUNNING }
            val isWaitingToStart = activeStates.any {
                it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED
            }
            val startTimedOut = !isRunning && isWaitingToStart &&
                noProgressForMs >= WORK_START_TIMEOUT_MS
            val progressTimedOut = isRunning &&
                noProgressForMs >= ACTIVE_NO_PROGRESS_TIMEOUT_MS
            val isStalled = startTimedOut || progressTimedOut

            if (isStalled) {
                val reason = if (startTimedOut) {
                    "等待系统启动超过 ${WORK_START_TIMEOUT_MS / 1000} 秒"
                } else {
                    "连续 ${ACTIVE_NO_PROGRESS_TIMEOUT_MS / 1000} 秒无下载进度"
                }
                AppLogger.w(
                    "SystemDownloadManager: task $taskId stalled ($reason, " +
                        "states=$activeStates); moving it to queue tail"
                )
                workManager.cancelUniqueWork(taskId)
                val pendingFile = File(fileUtil.tmpDir, taskId + PENDING_EXT)
                pendingFile.delete()
                if (!downloadingFile.renameTo(pendingFile)) {
                    downloadingFile.delete()
                    pendingFile.createNewFile()
                }
                pendingFile.setLastModified(System.currentTimeMillis())
                updateTaskStatus(
                    taskId,
                    VideoTaskState.RETRY_PENDING,
                    "$reason，已移至队尾"
                )
                return@filter false
            }

            if (isRunning || isWaitingToStart) {
                true
            } else {
                val videoInfo = VideoMetadataManager.getVideoInfo(taskId)
                if (videoInfo == null) {
                    downloadingFile.delete()
                    AppLogger.w(
                        "SystemDownloadManager: removed orphaned marker $taskId; metadata missing"
                    )
                } else {
                    val pendingFile = File(fileUtil.tmpDir, taskId + PENDING_EXT)
                    pendingFile.delete()
                    val moved = downloadingFile.renameTo(pendingFile)
                    if (!moved) {
                        downloadingFile.delete()
                        pendingFile.createNewFile()
                    }
                    pendingFile.setLastModified(System.currentTimeMillis())
                    updateTaskStatus(
                        taskId,
                        VideoTaskState.ENQUEUE,
                        "检测到下载异常中断，已自动恢复排队"
                    )
                    AppLogger.w(
                        "SystemDownloadManager: recovered stale active marker $taskId to queue"
                    )
                }
                false
            }
        }
    }

    private fun getVisibleTaskIdsOrNull(): Set<String>? {
        return try {
            progressRepository.getProgressInfos().blockingFirst(emptyList())
                .mapTo(hashSetOf()) { it.id }
        } catch (error: Throwable) {
            AppLogger.w(
                "SystemDownloadManager: unable to verify visible download tasks: ${error.message}"
            )
            null
        }
    }

    /**
     * WorkManager may restore every unfinished worker after an APK update/process restart, or
     * the user may lower the configured limit while several tasks are already running. Enforce
     * the limit here as a hard cap instead of merely preventing additional queue starts.
     */
    private fun enforceConcurrencyLimit(
        context: Context,
        downloadingFiles: List<File>,
        maxSimultaneous: Int
    ): List<File> {
        if (downloadingFiles.size <= maxSimultaneous) return downloadingFiles

        // Keep the tasks with the freshest real progress. Older/excess workers are cancelled
        // and placed at the tail without deleting their downloaded HLS fragments.
        val orderedByFreshness = downloadingFiles.sortedByDescending { it.lastModified() }
        val keep = orderedByFreshness.take(maxSimultaneous)
        orderedByFreshness.drop(maxSimultaneous).forEach { downloadingFile ->
            val taskId = downloadingFile.name.removeSuffix(DOWNLOADING_EXT)
            AppLogger.w(
                "SystemDownloadManager: active count ${downloadingFiles.size} exceeds " +
                    "limit $maxSimultaneous; moving excess task $taskId to queue tail"
            )
            WorkManager.getInstance(context).cancelUniqueWork(taskId)
            val pendingFile = File(fileUtil.tmpDir, taskId + PENDING_EXT)
            pendingFile.delete()
            if (!downloadingFile.renameTo(pendingFile)) {
                downloadingFile.delete()
                pendingFile.createNewFile()
            }
            pendingFile.setLastModified(System.currentTimeMillis())
            updateTaskStatus(
                taskId,
                VideoTaskState.ENQUEUE,
                "并发任务超过当前上限，已移至队尾"
            )
        }
        return keep
    }

    fun onTaskProgress(taskId: String) {
        getFlagFile(taskId, DOWNLOADING_EXT)?.takeIf { it.exists() }
            ?.setLastModified(System.currentTimeMillis())
    }

    fun requestQueueReconciliation() {
        scheduleQueueCheck(ContextUtils.getApplicationContext(), MIN_QUEUE_CHECK_DELAY_MS)
    }

    fun onTaskRecordMissing(taskId: String) {
        // A worker can finish after its row was deleted from the download list. Do not route
        // that case through normal failure retry, otherwise it recreates a marker forever.
        VideoMetadataManager.deleteVideoInfo(taskId)
        removeFlagFiles(taskId)
        clearRetryCount(taskId)
        AppLogger.w("SystemDownloadManager: discarded finished task $taskId; UI record is missing")
        scheduleQueueCheck(ContextUtils.getApplicationContext(), MIN_QUEUE_CHECK_DELAY_MS)
    }

    fun onTaskFinished(taskId: String, isSuccess: Boolean, errorMessage: String = "") {
        val context = ContextUtils.getApplicationContext()

        AppLogger.d("SystemDownloadManager: task finished $taskId success=$isSuccess")
        if (isSuccess) {
            VideoMetadataManager.deleteVideoInfo(taskId)
            removeFlagFiles(taskId)
            clearRetryCount(taskId)
        } else {
            // Keep the downloaded fragments and put a transiently failed task at the end of
            // the queue. Recreating the pending marker gives it the newest timestamp, while
            // removing the DOWNLOADING marker releases its concurrency slot immediately.
            val videoInfo = VideoMetadataManager.getVideoInfo(taskId)
            if (videoInfo != null) {
                val decision = retryDecision(taskId, errorMessage)
                removeFlagFiles(taskId)
                if (decision.needsAction) {
                    updateTaskStatus(taskId, VideoTaskState.NEEDS_ACTION, decision.message)
                    AppLogger.w("SystemDownloadManager: task $taskId requires user action: ${decision.message}")
                } else {
                    createFlagFile(taskId, PENDING_EXT)
                    getFlagFile(taskId, PENDING_EXT)?.setLastModified(
                        System.currentTimeMillis() + decision.delayMs
                    )
                    updateTaskStatus(
                        taskId,
                        VideoTaskState.RETRY_PENDING,
                        "排队重试 · ${decision.delayMs / 1000} 秒后重新开始 · ${decision.message}"
                    )
                    AppLogger.d(
                        "SystemDownloadManager: requeued failed task $taskId at queue tail " +
                            "with ${decision.delayMs}ms cooldown"
                    )
                }
            } else {
                removeFlagFiles(taskId)
                AppLogger.w("SystemDownloadManager: cannot requeue $taskId; metadata missing")
            }
        }
        scheduleQueueCheck(context, MIN_QUEUE_CHECK_DELAY_MS)
    }

    private data class RetryDecision(
        val delayMs: Long = 0,
        val needsAction: Boolean = false,
        val message: String
    )

    private fun retryDecision(taskId: String, rawError: String): RetryDecision {
        val error = rawError.lowercase()
        return when {
            "no space" in error || "enospc" in error || "空间不足" in error ->
                RetryDecision(needsAction = true, message = "存储空间不足，请释放空间后重试")
            "http 403" in error || "403" in error || "denied" in error ->
                RetryDecision(needsAction = true, message = "视频授权已过期，请回原页面重新嗅探")
            "http 404" in error || "404" in error ->
                RetryDecision(needsAction = true, message = "视频分片已失效，请回原页面重新嗅探")
            else -> {
                val round = incrementRetryCount(taskId)
                if (round > MAX_AUTOMATIC_REQUEUE_ROUNDS) {
                    RetryDecision(
                        needsAction = true,
                        message = "连续自动重试 3 轮仍失败，请检查网络或重新嗅探"
                    )
                } else {
                    val delay = if ("429" in error) {
                        120_000L
                    } else {
                        when (round) {
                            1 -> 30_000L
                            2 -> 60_000L
                            else -> 120_000L
                        }
                    }
                    val reason = when {
                        "429" in error -> "服务器限流"
                        "timeout" in error || "timed out" in error || "no download progress" in error -> "长时间无下载进度"
                        "http 5" in error -> "服务器暂时异常"
                        else -> rawError.ifBlank { "网络异常" }
                    }
                    RetryDecision(delayMs = delay, message = reason)
                }
            }
        }
    }

    private fun incrementRetryCount(taskId: String): Int {
        val file = File(fileUtil.tmpDir, taskId + RETRY_COUNT_EXT)
        val next = (file.takeIf { it.exists() }?.readText()?.toIntOrNull() ?: 0) + 1
        runCatching { file.writeText(next.toString()) }
        return next
    }

    private fun clearRetryCount(taskId: String) {
        runCatching { File(fileUtil.tmpDir, taskId + RETRY_COUNT_EXT).delete() }
    }

    private fun updateTaskStatus(taskId: String, status: Int, message: String) {
        val info = progressRepository.getProgressInfos().blockingFirst(emptyList())
            .firstOrNull { it.id == taskId } ?: return
        progressRepository.saveProgressInfo(info.copy(downloadStatus = status, infoLine = message))
    }

    private fun scheduleQueueCheck(context: Context, delayMs: Long) {
        val queueRequest = OneTimeWorkRequestBuilder<QueueWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()

        val workManager = WorkManager.getInstance(context)
        // An immediate/cooldown check becomes the new source of truth and will create a fresh
        // watchdog after it runs; discard any older watchdog chain to avoid delayed duplicates.
        workManager.cancelUniqueWork(WATCHDOG_QUEUE_WORK_NAME)
        workManager.enqueueUniqueWork(
            IMMEDIATE_QUEUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            queueRequest
        )
    }

    private fun scheduleWatchdogCheck(context: Context, delayMs: Long) {
        val queueRequest = OneTimeWorkRequestBuilder<QueueWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()

        // APPEND_OR_REPLACE lets a running watchdog finish before its successor starts.
        // Using REPLACE here cancels the currently executing QueueWorker from inside itself,
        // which can make MIUI drop the next queue pass.
        WorkManager.getInstance(context).enqueueUniqueWork(
            WATCHDOG_QUEUE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            queueRequest
        )
    }

    private fun getFlagFile(taskId: String, extension: String): File? {
        return try {
            File(fileUtil.tmpDir, taskId + extension)
        } catch (e: Throwable) {
            AppLogger.e("$e.message")
            null
        }
    }

    private fun removeFlagFiles(taskId: String) {
        try {
            File(fileUtil.tmpDir, taskId + PENDING_EXT).delete()
        } catch (e: Throwable) {
            AppLogger.e("$e.message")
        }
        try {
            File(fileUtil.tmpDir, taskId + DOWNLOADING_EXT).delete()
        } catch (e: Throwable) {
            AppLogger.e("$e.message")
        }
    }


    private fun createFlagFile(taskId: String, extension: String) {
        try {
            removeFlagFiles(taskId)
            val file = File(fileUtil.tmpDir, taskId + extension)
            file.createNewFile()
        } catch (e: Throwable) {
            AppLogger.e("Error creating flag file: ${e.message}")
        }
    }
}
