package com.example.videodownloader

import android.content.Context
import android.net.Uri
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Chooses the least-time complete route using estimated download, conversion,
 * validation, save, and empirically observed retry costs.
 *
 * Initial priors are deliberately explicit: network=2 MiB/s, yt-dlp startup=1.2s,
 * save=35 MiB/s. They are replaced gradually by measurements from real downloads.
 */
internal object DownloadRoutePlanner {

    data class RouteEstimate(
        val totalMs: Long,
        val totalDownloadBytes: Long,
        val audioDownloadBytes: Long?,
        val sizeIsApproximate: Boolean,
        val videoDownloadMs: Long,
        val audioDownloadMs: Long,
        val processingMs: Long,
        val validationMs: Long,
        val saveMs: Long,
        val retryRiskMs: Long
    )

    private const val PREFS_NAME = "download_route_optimizer_v1"
    private const val MIB = 1024.0 * 1024.0
    private const val DEFAULT_NETWORK_MIB_PER_SEC = 2.0
    private const val DEFAULT_STARTUP_MS = 1_200L
    private const val DEFAULT_SAVE_MIB_PER_SEC = 35.0
    private const val EWMA_NEW_WEIGHT = 0.25
    private val lock = Any()

    fun estimate(
        context: Context,
        sourceUrl: String,
        video: VideoFormat,
        audio: AudioFormat?,
        durationSeconds: Double?
    ): RouteEstimate {
        val prefs = context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

        migrateLegacyNetworkStats(prefs)

        val host = normalizedHost(sourceUrl)
        val videoProtocol = protocolClass(video.protocol)
        val audioProtocol = protocolClass(audio?.protocol ?: video.protocol)

        val safeDuration = durationSeconds?.takeIf {
            it.isFinite() && it > 0.0
        }

        val videoRate = video.videoBitrateKbps
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?: fallbackVideoRate(video.qualityTier)

        val audioRate = audio?.bitrate
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?: 128.0

        val videoBytes = video.videoDownloadSizeBytes
            ?.takeIf { it > 0L }
            ?: video.fileSize?.takeIf { it > 0L }
            ?: estimateBytes(
                safeDuration,
                if (video.hasAudio) videoRate + audioRate else videoRate
            )

        val audioBytes = if (video.hasAudio) {
            null
        } else {
            audio?.fileSize?.takeIf { it > 0L }
                ?: estimateBytes(safeDuration, audioRate)
        }

        val totalDownloadBytes =
            safeAdd(videoBytes, audioBytes)
                ?: videoBytes
                ?: audioBytes
                ?: 1L

        val sizeIsApproximate =
            video.videoDownloadSizeBytes == null ||
                    (!video.hasAudio && (audio?.fileSize == null || audio.sizeIsEstimate)) ||
                    video.sizeIsEstimate

        val videoDownloadMs = estimateTransferMs(
            prefs,
            host,
            videoProtocol,
            videoBytes
        )

        val audioDownloadMs = if (video.hasAudio) {
            0L
        } else {
            estimateTransferMs(
                prefs,
                host,
                audioProtocol,
                audioBytes
            )
        }

        val videoNeedsEncode = !isLikelyH264(video.videoCodec)

        val audioCodec = if (video.hasAudio) {
            video.audioCodec
        } else {
            audio?.codec
        }

        val audioNeedsEncode = !isLikelyAacLc(audioCodec)
        val mediaSeconds = safeDuration ?: 0.0

        val encoderMode = if (videoNeedsEncode) {
            "openh264"
        } else {
            "aac"
        }

        val processingMs = estimateProcessingMs(
            prefs = prefs,
            videoCodec = video.videoCodec,
            height = video.height,
            audioCodec = audioCodec,
            videoNeedsEncode = videoNeedsEncode,
            audioNeedsEncode = audioNeedsEncode,
            durationSeconds = mediaSeconds,
            encoderMode = encoderMode
        )

        val validationMs = prefs.getLong(
            "validation.ms",
            700L
        ).coerceIn(200L, 5_000L)

        val targetVideoRate = if (videoNeedsEncode) {
            (videoRate * 1.35).coerceIn(350.0, 16_000.0)
        } else {
            videoRate
        }

        val expectedOutputBytes = if (
            videoNeedsEncode || audioNeedsEncode
        ) {
            estimateBytes(
                mediaSeconds.takeIf { it > 0.0 },
                targetVideoRate + 128.0
            ) ?: totalDownloadBytes
        } else {
            totalDownloadBytes
        }

        val saveSpeed = prefs.getFloat(
            "save.mib_per_sec",
            DEFAULT_SAVE_MIB_PER_SEC.toFloat()
        ).toDouble().coerceIn(1.0, 500.0)

        val saveMs = (
                (expectedOutputBytes / MIB) / saveSpeed * 1000.0
                ).coerceAtMost(Long.MAX_VALUE.toDouble()).roundToLong()

        val videoRiskMs = expectedRetryCostMs(
            prefs,
            host,
            videoProtocol,
            videoDownloadMs
        )

        val audioRiskMs = if (video.hasAudio) {
            0L
        } else {
            expectedRetryCostMs(
                prefs,
                host,
                audioProtocol,
                audioDownloadMs
            )
        }

        val retryRiskMs = safeAdd(
            videoRiskMs,
            audioRiskMs
        ) ?: Long.MAX_VALUE / 4L

        val total = listOf(
            videoDownloadMs,
            audioDownloadMs,
            processingMs,
            validationMs,
            saveMs,
            retryRiskMs
        ).fold(0L) { sum, value ->
            safeAdd(sum, value) ?: Long.MAX_VALUE / 2L
        }

        return RouteEstimate(
            totalMs = total,
            totalDownloadBytes = totalDownloadBytes,
            audioDownloadBytes = audioBytes,
            sizeIsApproximate = sizeIsApproximate,
            videoDownloadMs = videoDownloadMs,
            audioDownloadMs = audioDownloadMs,
            processingMs = processingMs,
            validationMs = validationMs,
            saveMs = saveMs,
            retryRiskMs = retryRiskMs
        )
    }

    fun recordTransferSample(
        context: Context,
        sourceUrl: String,
        protocol: String?,
        bytes: Long,
        totalElapsedMs: Long,
        startupElapsedMs: Long?
    ) {
        if (bytes <= 0L || totalElapsedMs <= 0L) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        migrateLegacyNetworkStats(prefs)
        val host = normalizedHost(sourceUrl)
        if (host == UNTRACKED_PLATFORM) return
        val proto = protocolClass(protocol)
        val key = "net.$host.$proto"
        val startup = startupElapsedMs?.coerceIn(0L, totalElapsedMs)
            ?: prefs.getLong("$key.startup_ms", DEFAULT_STARTUP_MS)
        val transferMs = (totalElapsedMs - startup).coerceAtLeast(1L)
        val measuredSpeed = (bytes / MIB) / (transferMs / 1000.0)
        if (!measuredSpeed.isFinite() || measuredSpeed <= 0.0) return

        synchronized(lock) {
            val oldSpeed = prefs.getFloat("$key.mib_per_sec", DEFAULT_NETWORK_MIB_PER_SEC.toFloat()).toDouble()
            val oldStartup = prefs.getLong("$key.startup_ms", DEFAULT_STARTUP_MS).toDouble()
            val newSpeed = if (prefs.contains("$key.samples")) ewma(oldSpeed, measuredSpeed) else measuredSpeed
            val newStartup = if (prefs.contains("$key.samples")) ewma(oldStartup, startup.toDouble()) else startup.toDouble()
            prefs.edit()
                .putFloat("$key.mib_per_sec", newSpeed.toFloat())
                .putLong("$key.startup_ms", newStartup.roundToLong().coerceAtLeast(100L))
                .putInt("$key.samples", prefs.getInt("$key.samples", 0) + 1)
                .putInt("$key.successes", prefs.getInt("$key.successes", 0) + 1)
                .apply()
        }
    }

    fun recordAttemptFailure(context: Context, sourceUrl: String, protocol: String?) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        migrateLegacyNetworkStats(prefs)
        val host = normalizedHost(sourceUrl)
        if (host == UNTRACKED_PLATFORM) return
        val key = "net.$host.${protocolClass(protocol)}"
        synchronized(lock) {
            prefs.edit()
                .putInt("$key.failures", prefs.getInt("$key.failures", 0) + 1)
                .apply()
        }
    }

    fun recordSaveSample(context: Context, bytes: Long, elapsedMs: Long) {
        if (bytes <= 0L || elapsedMs <= 0L) return
        val measured = (bytes / MIB) / (elapsedMs / 1000.0)
        if (!measured.isFinite() || measured <= 0.0) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        synchronized(lock) {
            val exists = prefs.contains("save.mib_per_sec")
            val old = prefs.getFloat("save.mib_per_sec", DEFAULT_SAVE_MIB_PER_SEC.toFloat()).toDouble()
            val next = if (exists) ewma(old, measured) else measured
            prefs.edit().putFloat("save.mib_per_sec", next.toFloat()).apply()
        }
    }

    private fun processingProfileKey(
        videoCodec: String?,
        height: Int?,
        audioCodec: String?,
        videoNeedsEncode: Boolean,
        audioNeedsEncode: Boolean,
        encoderMode: String
    ): String {
        return "processing." +
                "${codecClass(videoCodec)}." +
                "${height ?: 0}." +
                "${codecClass(audioCodec)}." +
                "${if (videoNeedsEncode) "venc" else "vcopy"}." +
                "${if (audioNeedsEncode) "aenc" else "acopy"}." +
                encoderMode
    }

    private fun estimateProcessingMs(
        prefs: android.content.SharedPreferences,
        videoCodec: String?,
        height: Int?,
        audioCodec: String?,
        videoNeedsEncode: Boolean,
        audioNeedsEncode: Boolean,
        durationSeconds: Double,
        encoderMode: String
    ): Long {
        if (!videoNeedsEncode && !audioNeedsEncode) {
            return 350L
        }

        val key = processingProfileKey(
            videoCodec,
            height,
            audioCodec,
            videoNeedsEncode,
            audioNeedsEncode,
            encoderMode
        )

        val fallbackSpeed = if (videoNeedsEncode) {
            defaultEncodeSpeed(videoCodec)
        } else {
            18.0
        }

        val measuredSpeed = prefs.getFloat(
            "$key.realtime_speed",
            fallbackSpeed.toFloat()
        ).toDouble().coerceIn(0.1, 100.0)

        val startupMs = if (videoNeedsEncode) 900L else 400L

        val encodeMs = (
                durationSeconds / measuredSpeed * 1000.0
                ).coerceAtMost(Long.MAX_VALUE.toDouble()).roundToLong()

        return safeAdd(startupMs, encodeMs)
            ?: Long.MAX_VALUE / 4L
    }

    fun recordProcessingSample(
        context: Context,
        sourceVideoCodec: String?,
        height: Int?,
        sourceAudioCodec: String?,
        videoNeedsEncode: Boolean,
        audioNeedsEncode: Boolean,
        durationSeconds: Double?,
        elapsedMs: Long,
        encoderMode: String
    ) {
        if (!videoNeedsEncode && !audioNeedsEncode) return
        if (
            elapsedMs <= 0L ||
            durationSeconds == null ||
            !durationSeconds.isFinite() ||
            durationSeconds <= 0.0
        ) {
            return
        }

        val measuredSpeed = durationSeconds / (elapsedMs / 1000.0)
        if (!measuredSpeed.isFinite() || measuredSpeed <= 0.0) return

        val prefs = context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

        val key = processingProfileKey(
            sourceVideoCodec,
            height,
            sourceAudioCodec,
            videoNeedsEncode,
            audioNeedsEncode,
            encoderMode
        )

        synchronized(lock) {
            val samples = prefs.getInt("$key.samples", 0)
            val fallbackSpeed = if (videoNeedsEncode) {
                defaultEncodeSpeed(sourceVideoCodec)
            } else {
                18.0
            }
            val oldSpeed = prefs.getFloat(
                "$key.realtime_speed",
                fallbackSpeed.toFloat()
            ).toDouble()

            val nextSpeed = if (samples > 0) {
                ewma(oldSpeed, measuredSpeed)
            } else {
                measuredSpeed
            }

            prefs.edit()
                .putFloat("$key.realtime_speed", nextSpeed.toFloat())
                .putInt("$key.samples", samples + 1)
                .apply()
        }
    }

    private fun estimateTransferMs(prefs: android.content.SharedPreferences, host: String, protocol: String, bytes: Long?): Long {
        if (bytes == null || bytes <= 0L) return prefs.getLong("net.$host.$protocol.startup_ms", DEFAULT_STARTUP_MS)
        val key = "net.$host.$protocol"
        val speed = prefs.getFloat("$key.mib_per_sec", DEFAULT_NETWORK_MIB_PER_SEC.toFloat())
            .toDouble().coerceIn(0.1, 500.0)
        val startup = prefs.getLong("$key.startup_ms", DEFAULT_STARTUP_MS).coerceIn(100L, 30_000L)
        val transfer = ((bytes / MIB) / speed * 1000.0)
            .coerceAtMost(Long.MAX_VALUE.toDouble()).roundToLong()
        return safeAdd(startup, transfer) ?: Long.MAX_VALUE / 4
    }

    private fun expectedRetryCostMs(
        prefs: android.content.SharedPreferences,
        host: String,
        protocol: String,
        networkMs: Long
    ): Long {
        val key = "net.$host.$protocol"
        val successes = prefs.getInt("$key.successes", 0)
        val failures = prefs.getInt("$key.failures", 0)
        // Beta prior: 1 failure / 20 observations (5%) until actual route data exists.
        val probability = ((failures + 1.0) / (successes + failures + 20.0)).coerceIn(0.0, 0.35)
        return (networkMs * probability / (1.0 - probability)).coerceAtMost(Long.MAX_VALUE.toDouble()).roundToLong()
    }

    private fun defaultEncodeSpeed(codec: String?): Double {
        val value = codec.orEmpty().lowercase(Locale.ROOT)
        return when {
            value.contains("vp9") -> 0.97
            value.contains("av01") || value.contains("av1") -> 0.65
            value.contains("hevc") || value.contains("h265") -> 0.85
            else -> 0.75
        }
    }

    private fun isLikelyH264(codec: String?): Boolean {
        val value = codec.orEmpty().lowercase(Locale.ROOT)
        return value.contains("avc") || value.contains("h264")
    }

    private fun isLikelyAacLc(codec: String?): Boolean {
        val value = codec.orEmpty().lowercase(Locale.ROOT)
        return value == "aac" || value.contains("mp4a.40.2")
    }

    private fun fallbackVideoRate(tier: QualityTier): Double = when (tier) {
        QualityTier.WEAK -> 120.0
        QualityTier.MEDIUM -> 500.0
        QualityTier.STRONG -> 2_000.0
        QualityTier.ULTRA -> 5_000.0
    }

    private fun estimateBytes(duration: Double?, bitrateKbps: Double): Long? {
        if (duration == null || !duration.isFinite() || duration <= 0.0 || !bitrateKbps.isFinite() || bitrateKbps <= 0.0) return null
        val value = duration * bitrateKbps * 1000.0 / 8.0
        return if (value.isFinite() && value > 0.0 && value < Long.MAX_VALUE.toDouble()) value.roundToLong().coerceAtLeast(1L) else null
    }

    private fun safeAdd(first: Long?, second: Long?): Long? {
        if (first == null || second == null || first < 0L || second < 0L || Long.MAX_VALUE - first < second) return null
        return first + second
    }

    private fun ewma(old: Double, sample: Double): Double = old * (1.0 - EWMA_NEW_WEIGHT) + sample * EWMA_NEW_WEIGHT

    private const val UNTRACKED_PLATFORM = "untracked"
    private const val PLATFORM_STATS_MIGRATION_KEY = "platform_stats_migrated_v3"

    // These are learning categories only. yt-dlp remains responsible for actual extraction support.
    private val PLATFORM_DOMAINS: Map<String, Set<String>> = mapOf(
        "youtube" to setOf("youtube.com", "youtu.be", "youtube-nocookie.com"),
        "facebook" to setOf("facebook.com", "fb.com", "fb.watch"),
        "tiktok" to setOf("tiktok.com", "tiktokv.com", "musical.ly"),
        "instagram" to setOf("instagram.com", "instagr.am"),
        "snapchat" to setOf("snapchat.com", "snap.com"),
        "x" to setOf("x.com", "twitter.com", "t.co"),
        "linkedin" to setOf("linkedin.com", "lnkd.in"),
        "telegram" to setOf("t.me", "telegram.me", "telegram.org"),
        "reddit" to setOf("reddit.com", "redd.it"),
        "pinterest" to setOf("pinterest.com", "pin.it"),
        "threads" to setOf("threads.net"),
        "twitch" to setOf("twitch.tv"),
        "vimeo" to setOf("vimeo.com"),
        "dailymotion" to setOf("dailymotion.com", "dai.ly"),
        "likee" to setOf("likee.video", "likee.com")
    )

    private val NETWORK_METRIC_SUFFIXES = listOf(
        "mib_per_sec", "startup_ms", "samples", "successes", "failures"
    )
    private val NETWORK_PROTOCOL_CLASSES = listOf("hls", "dash", "http", "other")

    private fun platformForHost(rawHost: String?): String? {
        val host = rawHost?.lowercase(Locale.ROOT)?.trim()?.trimEnd('.') ?: return null
        if (host.isBlank()) return null
        return PLATFORM_DOMAINS.entries.firstOrNull { (platform, domains) ->
            host == platform || domains.any { domain ->
                host == domain || host.endsWith(".$domain")
            }
        }?.key
    }

    // Unknown websites still use the planner's default estimates, but never get persistent per-site stats.
    private fun normalizedHost(url: String): String =
        platformForHost(runCatching { Uri.parse(url).host }.getOrNull()) ?: UNTRACKED_PLATFORM

    /** Migrate known legacy host keys to platform keys and discard old untracked per-host network keys once. */
    private fun migrateLegacyNetworkStats(prefs: android.content.SharedPreferences) {
        synchronized(lock) {
            if (prefs.getBoolean(PLATFORM_STATS_MIGRATION_KEY, false)) return

            val all = prefs.all
            val candidatesByTarget = linkedMapOf<String, LinkedHashSet<String>>()
            val keysToRemove = linkedSetOf<String>()

            all.keys.forEach { key ->
                if (!key.startsWith("net.")) return@forEach
                val metric = NETWORK_METRIC_SUFFIXES.firstOrNull { key.endsWith(".$it") }
                    ?: return@forEach
                val body = key.removePrefix("net.").removeSuffix(".$metric")
                val protocol = NETWORK_PROTOCOL_CLASSES.firstOrNull { body.endsWith(".$it") }
                    ?: return@forEach
                val oldHost = body.removeSuffix(".$protocol")
                val platform = platformForHost(oldHost)

                if (platform == null) {
                    keysToRemove.add(key)
                    return@forEach
                }

                val oldPrefix = "net.$oldHost.$protocol"
                val newPrefix = "net.$platform.$protocol"
                if (oldPrefix != newPrefix) {
                    candidatesByTarget.getOrPut(newPrefix) { linkedSetOf() }.add(oldPrefix)
                    keysToRemove.add(key)
                }
            }

            val editor = prefs.edit()
            candidatesByTarget.forEach { (targetPrefix, sourcePrefixes) ->
                // Include an existing platform-level record in the migration.
                // Otherwise legacy alias keys would be deleted even though the
                // previous implementation skipped merging when the target existed.
                val candidatePrefixes = listOf(targetPrefix) + sourcePrefixes
                listOf("mib_per_sec", "startup_ms").forEach { metric ->
                    val bestSource = candidatePrefixes
                        .filter { all.containsKey("$it.$metric") }
                        .maxByOrNull { prefix ->
                            (all["$prefix.samples"] as? Int ?: 0).coerceAtLeast(0)
                        }
                    if (bestSource != null) {
                        when (val value = all["$bestSource.$metric"]) {
                            is Float -> editor.putFloat("$targetPrefix.$metric", value)
                            is Long -> editor.putLong("$targetPrefix.$metric", value)
                            is Int -> editor.putInt("$targetPrefix.$metric", value)
                        }
                    }
                }

                listOf("samples", "successes", "failures").forEach { metric ->
                    val total = candidatePrefixes.sumOf { prefix ->
                        (all["$prefix.$metric"] as? Int ?: 0).coerceAtLeast(0).toLong()
                    }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    if (total > 0) editor.putInt("$targetPrefix.$metric", total)
                }
            }

            keysToRemove.forEach { key -> editor.remove(key) }
            editor.putBoolean(PLATFORM_STATS_MIGRATION_KEY, true).apply()
        }
    }

    private fun protocolClass(protocol: String?): String {
        val value = protocol.orEmpty().lowercase(Locale.ROOT)
        return when {
            value.contains("m3u8") || value.contains("hls") -> "hls"
            value.contains("dash") || value.contains("http_dash") -> "dash"
            value == "http" || value == "https" || value.contains("https") -> "http"
            else -> "other"
        }
    }

    private fun codecClass(codec: String?): String {
        val value = codec.orEmpty().lowercase(Locale.ROOT)
        return when {
            value.contains("vp9") -> "vp9"
            value.contains("av01") || value.contains("av1") -> "av1"
            value.contains("hevc") || value.contains("h265") -> "hevc"
            value.contains("avc") || value.contains("h264") -> "h264"
            else -> "other"
        }
    }

}
