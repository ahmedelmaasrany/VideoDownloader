package com.example.videodownloader

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaCodecList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import android.Manifest
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import sh.calvin.reorderable.*
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.example.videodownloader.ui.theme.VideoDownloaderTheme
import dev.ffmpegkit_maintained.ytdlp.DownloadProgressCallback
import dev.ffmpegkit_maintained.ytdlp.LogCallback
import dev.ffmpegkit_maintained.ytdlp.YtDlp
import dev.ffmpegkit_maintained.ytdlp.YtDlpException
import dev.ffmpegkit_maintained.ytdlp.YtDlpRequest
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import androidx.core.content.edit

private const val TAG = "VideoDownloader"

private fun hasValidatedInternet(context: Context): Boolean {
    val manager = context.getSystemService(
        Context.CONNECTIVITY_SERVICE
    ) as? ConnectivityManager ?: return false

    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false

    return capabilities.hasCapability(
        NetworkCapabilities.NET_CAPABILITY_INTERNET
    ) && capabilities.hasCapability(
        NetworkCapabilities.NET_CAPABILITY_VALIDATED
    )
}
internal enum class QualityTier(
    val label: String,
    val minHeight: Int,
    val maxHeight: Int
) {
    WEAK("جودة ضعيفة", 1, 149),
    MEDIUM("جودة متوسطة", 150, 539),
    STRONG("جودة قوية", 540, 900),
    ULTRA("جودة فائقة", 901, Int.MAX_VALUE);

    companion object {
        fun fromHeight(height: Int): QualityTier? {
            return entries.firstOrNull {
                height in it.minHeight..it.maxHeight
            }
        }
    }
}

internal data class VideoFormat(
    val formatId: String,
    val height: Int,
    val extension: String,
    val fileSize: Long?,
    val sizeIsEstimate: Boolean,
    val hasAudio: Boolean,
    val fps: Double?,
    val qualityTier: QualityTier,
    val videoCodec: String?,
    val audioCodec: String?,
    val protocol: String?,
    val videoDownloadSizeBytes: Long? = null,
    val audioDownloadSizeBytes: Long? = null,
    val videoBitrateKbps: Double? = null,
    val plannedAudioFormatId: String? = null,
    val estimatedTotalMs: Long? = null
) {
    val isAvailable: Boolean
        get() = formatId.isNotBlank()
}

internal data class AudioFormat(
    val formatId: String,
    val bitrate: Double,
    val extension: String,
    val fileSize: Long?,
    val codec: String? = null,
    val sizeIsEstimate: Boolean = true,
    val language: String? = null,
    val formatNote: String? = null,
    val languagePreference: Int? = null,
    val protocol: String? = null
)

internal data class VideoInfo(
    val title: String,
    val formats: List<VideoFormat>,
    val audioFormats: List<AudioFormat>
)

internal object AnalysisStateStore {
    val executor: ExecutorService = Executors.newSingleThreadExecutor()

    var videoUrl by mutableStateOf("")
    var videoTitle by mutableStateOf("")
    var formats by mutableStateOf<List<VideoFormat>>(emptyList())
    var audioFormats by mutableStateOf<List<AudioFormat>>(emptyList())
    var selectedFormatId by mutableStateOf<String?>(null)
    var statusMessage by mutableStateOf("")
    var statusIsError by mutableStateOf(false)
    var isAnalyzing by mutableStateOf(false)
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        DownloadStorageMaintenance.runOnce(applicationContext)

        val welcomePreferences = getSharedPreferences(
            "video_downloader_preferences",
            MODE_PRIVATE
        )
        val welcomeCompletedAtStartup =
            welcomePreferences.getBoolean("welcome_completed", false)

        // Returning users skip onboarding. Initialization is process-scoped,
        // so a configuration change cannot start a second initialization.
        if (welcomeCompletedAtStartup) {
            DownloadEngineState.initialize(applicationContext)
        }

        setContent {
            VideoDownloaderTheme {
                var showWelcome by remember {
                    mutableStateOf(!welcomeCompletedAtStartup)
                }

                // These observable values live outside the Activity and survive
                // screen rotation while the Activity is recreated.
                val isInitializing = DownloadEngineState.isInitializing
                val engineReady = DownloadEngineState.engineReady

                if (showWelcome) {
                    WelcomeIntroScreen(
                        isLaunching = isInitializing,
                        onStart = {
                            welcomePreferences.edit {
                                putBoolean("welcome_completed", true)
                            }
                            showWelcome = false
                            DownloadEngineState.initialize(applicationContext)
                        }
                    )
                } else {
                    DownloaderScreen(
                        engineReady = engineReady,
                        context = this@MainActivity
                    )
                }
            }
        }
    }
}

/** Process-wide state prevents duplicate yt-dlp initialization on Activity recreation. */
private object DownloadEngineState {

    var engineReady by mutableStateOf(false)
        private set

    var isInitializing by mutableStateOf(false)
        private set

    private val lock = Any()

    fun initialize(context: Context) {
        synchronized(lock) {
            if (engineReady || isInitializing) return
            isInitializing = true
        }

        Thread({
            val ready = try {
                YtDlp.init(context.applicationContext)
                Log.i(TAG, "yt-dlp initialized")
                true
            } catch (error: YtDlpException) {
                Log.e(TAG, "yt-dlp initialization failed", error)
                false
            }

            if (ready) {
                try {
                    val embeddedVersion = Python.getInstance()
                        .getModule("yt_dlp.version")["__version__"]
                        .toString()

                    Log.i(TAG, "EMBEDDED_YTDLP_VERSION=$embeddedVersion")
                } catch (error: Exception) {
                    Log.e(TAG, "Could not read yt-dlp version", error)
                }

                Thread(
                    { checkYtDlpOptionMapping() },
                    "YtDlpOptionDiagnostic"
                ).start()
            }

            runCatching { checkFfmpegEncoders() }
                .onFailure {
                    Log.e(TAG, "FFmpeg encoder diagnostic failed", it)
                }

            Thread(
                {
                    checkAndroidAvcEncoders()
                    checkAndroidAv1Decoders()
                },
                "AndroidCodecDiagnostic"
            ).start()

            Handler(Looper.getMainLooper()).post {
                synchronized(lock) {
                    engineReady = ready
                    isInitializing = false
                }
            }
        }, "VideoDownloaderEngineInit").start()
    }
}


private fun pyString(obj: PyObject, key: String): String? {
    val value = obj.callAttr("get", key) ?: return null
    return value.toString().takeUnless {
        it == "None" || it == "null" || it.isBlank()
    }
}

private fun pyInt(obj: PyObject, key: String): Int? {
    val value = obj.callAttr("get", key) ?: return null
    if (value.toString() == "None") return null
    return runCatching { value.toInt() }.getOrNull()
}

private fun pyLong(obj: PyObject, key: String): Long? {
    val value = obj.callAttr("get", key) ?: return null
    if (value.toString() == "None") return null
    return runCatching { value.toLong() }.getOrNull()
}

private fun pyDouble(obj: PyObject, key: String): Double? {
    val value = obj.callAttr("get", key) ?: return null
    if (value.toString() == "None") return null
    return runCatching { value.toDouble() }.getOrNull()
}


private data class SizeEstimate(
    val bytes: Long?,
    val isApproximate: Boolean
)


private data class MediaProbe(
    val formatNames: String,
    val durationSeconds: Double?,
    val videoCodec: String?,
    val audioCodec: String?,
    val videoProfile: String?,
    val videoPixelFormat: String?,
    val audioProfile: String?,
    val videoWidth: Int?,
    val videoHeight: Int?,
    val frameRate: Double?,
    val videoBitrateBps: Long?,
    val videoFrameCount: Int?
)

private fun parseFrameRate(value: String?): Double? {
    if (value.isNullOrBlank()) return null

    val parts = value.split("/")

    val result = if (parts.size == 2) {
        val numerator = parts[0].toDoubleOrNull()
            ?: return null

        val denominator = parts[1].toDoubleOrNull()
            ?: return null

        if (denominator == 0.0) return null

        numerator / denominator
    } else {
        value.toDoubleOrNull() ?: return null
    }

    return result.takeIf {
        it.isFinite() && it > 0.0
    }
}

private fun probeMediaFile(
    file: File,
    countFrames: Boolean = false
): MediaProbe {

    if (!file.exists() || file.length() <= 0L) {
        throw IOException("ملف الوسائط غير موجود أو فارغ.")
    }

    val arguments = mutableListOf(
        "-v", "error"
    )

    if (countFrames) {
        arguments.add("-count_frames")
    }

    arguments.addAll(
        listOf(
            "-show_entries",
            "format=format_name,duration:" +
                    "stream=index,codec_type,codec_name,profile,pix_fmt," +
                    "width,height,avg_frame_rate," +
                    "duration,bit_rate,nb_read_frames,nb_frames",
            "-of", "json",
            file.absolutePath
        )
    )

    val session = FFprobeKit.executeWithArguments(
        arguments.toTypedArray()
    )

    val output = session.output.orEmpty()

    if (!ReturnCode.isSuccess(session.getReturnCode())) {
        throw IOException(
            "FFprobe failed: ${session.getReturnCode()} $output"
        )
    }

    val root = try {
        JSONObject(output)
    } catch (error: Exception) {
        throw IOException("تعذر تحليل بيانات FFprobe.", error)
    }

    val streamsArray = root.optJSONArray("streams")
        ?: throw IOException("FFprobe لم يُرجع قائمة المسارات.")

    val streams = (0 until streamsArray.length())
        .mapNotNull { streamsArray.optJSONObject(it) }

    val videoStream = streams.firstOrNull {
        it.optString("codec_type") == "video"
    }

    val audioStream = streams.firstOrNull {
        it.optString("codec_type") == "audio"
    }

    val format = root.optJSONObject("format")

    fun readPositiveLong(
        source: JSONObject?,
        key: String
    ): Long? {
        return source?.optString(key)
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
    }

    fun readPositiveInt(
        source: JSONObject?,
        key: String
    ): Int? {
        return source?.optString(key)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
    }
    fun readStreamString(
        source: JSONObject?,
        key: String
    ): String? {
        val value = source?.optString(key)?.trim()
            ?: return null

        return value.takeIf {
            it.isNotEmpty() &&
                    !it.equals("N/A", ignoreCase = true) &&
                    !it.equals("null", ignoreCase = true)
        }
    }
    val duration = videoStream
        ?.optString("duration")
        ?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it > 0.0 }
        ?: format?.optString("duration")
            ?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 }

    return MediaProbe(
        formatNames = format
            ?.optString("format_name")
            .orEmpty(),

        durationSeconds = duration,

        videoCodec = videoStream
            ?.optString("codec_name")
            ?.takeIf { it.isNotBlank() && it != "N/A" },

        audioCodec = audioStream
            ?.optString("codec_name")
            ?.takeIf { it.isNotBlank() && it != "N/A" },

        videoProfile = readStreamString(
            videoStream,
            "profile"
        ),

        videoPixelFormat = readStreamString(
            videoStream,
            "pix_fmt"
        ),

        audioProfile = readStreamString(
            audioStream,
            "profile"
        ),

        videoWidth = readPositiveInt(videoStream, "width"),

        videoHeight = readPositiveInt(videoStream, "height"),

        frameRate = parseFrameRate(
            videoStream?.optString("avg_frame_rate")
        ),

        videoBitrateBps = readPositiveLong(
            videoStream,
            "bit_rate"
        ),

        videoFrameCount =
            readPositiveInt(videoStream, "nb_read_frames")
                ?: readPositiveInt(videoStream, "nb_frames")
    )
}

private fun estimateBytesFromBitrate(
    durationSeconds: Double?,
    bitrateKbps: Double?
): Long? {

    if (
        durationSeconds == null ||
        bitrateKbps == null ||
        !durationSeconds.isFinite() ||
        !bitrateKbps.isFinite() ||
        durationSeconds <= 0.0 ||
        bitrateKbps <= 0.0
    ) {
        return null
    }

    val bytes = durationSeconds * bitrateKbps * 1000.0 / 8.0

    if (!bytes.isFinite() || bytes <= 0.0 ||
        bytes > Long.MAX_VALUE.toDouble()
    ) {
        return null
    }

    return bytes.roundToLong().coerceAtLeast(1L)
}


private fun estimateFormatSize(
    item: PyObject,
    durationSeconds: Double?,
    bitrateKbps: Double?
): SizeEstimate {

    val exactSize = pyLong(item, "filesize")
        ?.takeIf { it > 0L }

    if (exactSize != null) {
        return SizeEstimate(exactSize, false)
    }

    val approximateSize = pyLong(item, "filesize_approx")
        ?.takeIf { it > 0L }

    if (approximateSize != null) {
        return SizeEstimate(approximateSize, true)
    }

    return SizeEstimate(
        estimateBytesFromBitrate(durationSeconds, bitrateKbps),
        true
    )
}


private fun addEstimatedSizes(
    first: Long?,
    second: Long?
): Long? {

    if (first == null || second == null) return null

    if (first < 0L || second < 0L) return null

    if (Long.MAX_VALUE - first < second) return null

    return first + second
}


private fun isAacCodec(codec: String?): Boolean {
    val normalized = codec.orEmpty().lowercase(Locale.ROOT)

    return normalized == "aac" || normalized.startsWith("mp4a")
}


// 0 = المسار الأصلي، 1 = غير معروف، 2 = مدبلج تلقائيًا.
// كلمة (default) وحدها غير موثوقة: بعض المسارات المدبلجة تحملها أيضًا.
private fun audioTrackRank(format: AudioFormat): Int {
    val note = format.formatNote.orEmpty().lowercase(Locale.ROOT)

    return when {
        note.contains("dubbed") -> 2
        note.contains("original") -> 0
        else -> 1
    }
}


private fun containerPreference(extension: String): Int {
    return when (extension.lowercase(Locale.ROOT)) {
        "mp4" -> 0
        "m4v" -> 1
        "webm" -> 2
        else -> 3
    }
}


private fun videoCodecPreference(codec: String?): Int {
    val normalized = codec.orEmpty().lowercase(Locale.ROOT)

    return when {
        normalized.contains("avc") ||
                normalized.contains("h264") -> 0

        normalized.contains("vp9") -> 1

        normalized.contains("av01") ||
                normalized.contains("av1") -> 2

        else -> 3
    }
}


private fun protocolPreference(protocol: String?): Int {
    val normalized = protocol.orEmpty().lowercase(Locale.ROOT)

    return when {
        normalized == "https" || normalized == "http" -> 0
        normalized.contains("m3u8") -> 1
        normalized.contains("dash") -> 2
        else -> 3
    }
}


private fun fallbackVideoBitrateKbps(tier: QualityTier): Double {
    return when (tier) {
        QualityTier.WEAK -> 120.0
        QualityTier.MEDIUM -> 500.0
        QualityTier.STRONG -> 2000.0
        QualityTier.ULTRA -> 5000.0
    }
}


private fun pyBoolean(obj: PyObject): Boolean {
    val value = obj.callAttr("get", "has_drm") ?: return false
    return value.toString().equals("True", ignoreCase = true)
}
private fun isYouTubeUrl(url: String): Boolean {
    val host = url.toUri()
        .host
        ?.lowercase(Locale.ROOT)
        .orEmpty()

    return host == "youtube.com" ||
            host.endsWith(".youtube.com") ||
            host == "youtu.be"
}

private fun quickJsExecutablePath(context: Context): String {
    val executable = File(
        context.applicationInfo.nativeLibraryDir,
        "libqjs.so"
    )

    if (!executable.isFile || !executable.canExecute()) {
        throw IOException(
            "QuickJS is missing or not executable: ${executable.absolutePath}"
        )
    }

    return executable.absolutePath
}

private fun createQuickJsRuntimeOptions(
    python: Python,
    context: Context
): PyObject {
    val runtimeOptions = python.builtins.callAttr("dict")

    runtimeOptions.asMap()[PyObject.fromJava("path")] =
        PyObject.fromJava(quickJsExecutablePath(context))

    val runtimes = python.builtins.callAttr("dict")

    runtimes.asMap()[PyObject.fromJava("quickjs")] =
        runtimeOptions

    return runtimes
}

private fun createYouTubeExtractorArgs(python: Python): PyObject {
    val playerClients = python.builtins.callAttr("list")
    playerClients.callAttr("append", PyObject.fromJava("default"))
    playerClients.callAttr("append", PyObject.fromJava("-visionos"))

    val youtubeArgs = python.builtins.callAttr("dict")
    youtubeArgs.asMap()[PyObject.fromJava("player_client")] = playerClients

    val extractorArgs = python.builtins.callAttr("dict")
    extractorArgs.asMap()[PyObject.fromJava("youtube")] = youtubeArgs

    return extractorArgs
}

internal fun extractVideoInfo(url: String, context: Context): VideoInfo {

    val python = Python.getInstance()
    val ytDlpModule = python.getModule("yt_dlp")
    val options = python.builtins.callAttr("dict")
    val optionsMap = options.asMap()


    optionsMap[PyObject.fromJava("quiet")] = PyObject.fromJava(false)
    optionsMap[PyObject.fromJava("no_warnings")] = PyObject.fromJava(false)
    optionsMap[PyObject.fromJava("verbose")] = PyObject.fromJava(true)
    optionsMap[PyObject.fromJava("skip_download")] = PyObject.fromJava(true)
    optionsMap[PyObject.fromJava("noplaylist")] = PyObject.fromJava(true)
    if (isYouTubeUrl(url)) {
        optionsMap[PyObject.fromJava("js_runtimes")] =
            createQuickJsRuntimeOptions(python, context)

        optionsMap[PyObject.fromJava("extractor_args")] =
            createYouTubeExtractorArgs(python)
    }


    val downloader = ytDlpModule.callAttr("YoutubeDL", options)

    try {
        val info = downloader.callAttr("extract_info", url, false)

        if (isYouTubeUrl(url)) {
            try {
                val stored = python.getModule("ytdlp_runner")
                    .callAttr("cache_info", url, info)
                Log.i(TAG, "YTDLP_INFO_CACHE_STORE=$stored")
            } catch (error: Exception) {
                Log.w(
                    TAG,
                    "YTDLP_INFO_CACHE_STORE_FAILED: ${errorDetails(error)}"
                )
            }
        }

        val title = pyString(info, "title") ?: "Video"
        val duration = pyDouble(info, "duration")

        val formatsObject = info.callAttr("get", "formats")

        if (formatsObject == null || formatsObject.toString() == "None") {
            return VideoInfo(
                title = title,
                formats = QualityTier.entries.map { tier ->
                    VideoFormat(
                        formatId = "",
                        height = 0,
                        extension = "",
                        fileSize = null,
                        sizeIsEstimate = true,
                        hasAudio = false,
                        fps = null,
                        qualityTier = tier,
                        videoCodec = null,
                        audioCodec = null,
                        protocol = null
                    )
                },
                audioFormats = emptyList()
            )
        }

        val availableFormats = formatsObject.asList()

        // أولًا: تجهيز مسارات الصوت المنفصلة.
        val audioFormats = availableFormats.mapNotNull { item ->

            val formatId = pyString(item, "format_id")
                ?: return@mapNotNull null

            val videoCodec = pyString(item, "vcodec")
            val audioCodec = pyString(item, "acodec")

            if (
                !videoCodec.equals("none", ignoreCase = true) ||
                audioCodec.isNullOrBlank() ||
                audioCodec.equals("none", ignoreCase = true) ||
                pyBoolean(item)
            ) {
                return@mapNotNull null
            }

            val formatDuration =
                pyDouble(item, "duration") ?: duration

            val bitrate =
                pyDouble(item, "abr")
                    ?: pyDouble(item, "tbr")
                    ?: 0.0

            val size = estimateFormatSize(
                item = item,
                durationSeconds = formatDuration,
                bitrateKbps = bitrate
            )

            AudioFormat(
                formatId = formatId,
                bitrate = bitrate,
                extension = pyString(item, "ext") ?: "unknown",
                fileSize = size.bytes,
                codec = audioCodec,
                sizeIsEstimate = size.isApproximate,
                language = pyString(item, "language"),
                formatNote = pyString(item, "format_note"),
                languagePreference = pyInt(item, "language_preference"),
                protocol = pyString(item, "protocol")
            )

        }.distinctBy { it.formatId }
            .sortedWith(
                compareBy<AudioFormat> {
                    audioTrackRank(it)
                }.thenByDescending {
                    it.languagePreference ?: -1000
                }.thenBy {
                    if (isAacCodec(it.codec)) 0 else 1
                }.thenBy {
                    if (it.extension == "m4a") 0 else 1
                }.thenByDescending {
                    it.bitrate
                }
            )

        audioFormats.forEach {
            Log.i(
                TAG,
                "AUDIO_CANDIDATE id=${it.formatId} rank=${audioTrackRank(it)} " +
                        "lang=${it.language} langPref=${it.languagePreference} " +
                        "note=${it.formatNote} codec=${it.codec} " +
                        "abr=${it.bitrate} ext=${it.extension}"
            )
        }

        // نستخدم نفس مسار الصوت لتقدير الحجم الإجمالي لكل فيديو منفصل.
        val preferredAudio = audioFormats.firstOrNull()

        // ثانيًا: استخراج صيغ الفيديو الصالحة.
        val videoCandidates = availableFormats.mapNotNull { item ->

            val formatId = pyString(item, "format_id")
                ?: return@mapNotNull null

            val videoCodec = pyString(item, "vcodec")

            if (
                videoCodec.isNullOrBlank() ||
                videoCodec.equals("none", ignoreCase = true) ||
                pyBoolean(item)
            ) {
                return@mapNotNull null
            }

            val resolution = pyString(item, "resolution")

            val height = pyInt(item, "height")
                ?: resolution?.let {
                    Regex(
                        "[xp](\\d{3,4})",
                        RegexOption.IGNORE_CASE
                    ).find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
                }
                ?: return@mapNotNull null

            if (height <= 0) return@mapNotNull null

            val tier = QualityTier.fromHeight(height)
                ?: return@mapNotNull null

            val audioCodec = pyString(item, "acodec")

            val hasAudio =
                !audioCodec.isNullOrBlank() &&
                        !audioCodec.equals("none", ignoreCase = true)

            val formatDuration =
                pyDouble(item, "duration") ?: duration

            val bitrate = if (hasAudio) {
                pyDouble(item, "tbr")
                    ?: run {
                        val videoRate = pyDouble(item, "vbr")
                        val audioRate = pyDouble(item, "abr")

                        if (videoRate != null && audioRate != null) {
                            videoRate + audioRate
                        } else {
                            videoRate ?: audioRate
                        }
                    }
            } else {
                pyDouble(item, "vbr")
                    ?: pyDouble(item, "tbr")
            }

            val videoSize = estimateFormatSize(
                item = item,
                durationSeconds = formatDuration,
                bitrateKbps = bitrate
            )

            val audioSize = if (hasAudio) {
                null
            } else {
                preferredAudio?.let {
                    SizeEstimate(it.fileSize, it.sizeIsEstimate)
                } ?: estimateBytesFromBitrate(
                    formatDuration,
                    128.0
                )?.let {
                    SizeEstimate(it, true)
                }
            }

            val fallbackAudioRate =
                preferredAudio?.bitrate?.takeIf { it > 0.0 }
                    ?: 128.0

            val fallbackVideoSize = if (
                videoSize.bytes == null &&
                formatDuration != null
            ) {

                val fallbackRate = if (hasAudio) {
                    fallbackVideoBitrateKbps(tier) + fallbackAudioRate
                } else {
                    fallbackVideoBitrateKbps(tier)
                }

                estimateBytesFromBitrate(
                    formatDuration,
                    fallbackRate
                )

            } else {
                null
            }

            val fallbackAudioSize = if (
                !hasAudio &&
                audioSize?.bytes == null &&
                formatDuration != null
            ) {

                estimateBytesFromBitrate(
                    formatDuration,
                    fallbackAudioRate
                )

            } else {
                null
            }

            val resolvedVideoSize =
                videoSize.bytes ?: fallbackVideoSize

            val resolvedAudioSize = if (hasAudio) {
                null
            } else {
                audioSize?.bytes ?: fallbackAudioSize
            }

            val totalSize = if (hasAudio) {
                resolvedVideoSize
            } else {
                addEstimatedSizes(
                    resolvedVideoSize,
                    resolvedAudioSize
                )
            }

            val sizeIsEstimate =
                videoSize.isApproximate ||
                        fallbackVideoSize != null ||
                        (
                                !hasAudio &&
                                        (
                                                audioSize?.isApproximate == true ||
                                                        fallbackAudioSize != null
                                                )
                                )

            VideoFormat(
                formatId = formatId,
                height = height,
                extension = pyString(item, "ext") ?: "unknown",
                fileSize = totalSize,
                sizeIsEstimate = sizeIsEstimate,
                hasAudio = hasAudio,
                fps = pyDouble(item, "fps"),
                qualityTier = tier,
                videoCodec = videoCodec,
                audioCodec = audioCodec,
                protocol = pyString(item, "protocol"),
                videoDownloadSizeBytes = resolvedVideoSize,
                audioDownloadSizeBytes = resolvedAudioSize,
                videoBitrateKbps = pyDouble(item, "vbr") ?: pyDouble(item, "tbr")
            )

        }.distinctBy { it.formatId }

        videoCandidates.forEach { candidate ->
            Log.i(
                TAG,
                "VIDEO_CANDIDATE " +
                        "id=${candidate.formatId} " +
                        "height=${candidate.height} " +
                        "tier=${candidate.qualityTier.label} " +
                        "codec=${candidate.videoCodec} " +
                        "ext=${candidate.extension} " +
                        "sizeBytes=${candidate.fileSize} " +
                        "estimated=${candidate.sizeIsEstimate} " +
                        "hasAudio=${candidate.hasAudio} " +
                        "fps=${candidate.fps} " +
                        "protocol=${candidate.protocol}"
            )
        }

        // ثالثًا: نختار الدِّقة الأقرب للمستوى المطلوب،
        // ثم نختار أقل حجم بين الصيغ التي تقدم الدِّقَّة نفسها.
        fun chooseFormat(tier: QualityTier): VideoFormat? {

            val candidates = videoCandidates.filter {
                it.qualityTier == tier
            }

            if (candidates.isEmpty()) return null

            val preferredHeight = when (tier) {
                QualityTier.WEAK -> candidates.maxOfOrNull { it.height }
                QualityTier.MEDIUM -> candidates.map { it.height }
                    .distinct()
                    .minWithOrNull(compareBy<Int> { kotlin.math.abs(it - 360) }.thenByDescending { it })
                QualityTier.STRONG -> candidates.map { it.height }
                    .distinct()
                    .minWithOrNull(compareBy<Int> { kotlin.math.abs(it - 720) }.thenByDescending { it })
                QualityTier.ULTRA -> candidates.minOfOrNull { it.height }
            } ?: return null

            val matchingCandidates = candidates.filter { it.height == preferredHeight }

            // Keep the best original/language track preference. Within that track,
            // compare codec, transfer size, conversion and save costs together.
            val bestTrackRank = audioFormats.minOfOrNull { audioTrackRank(it) }
            val bestTrack = audioFormats.filter { bestTrackRank == null || audioTrackRank(it) == bestTrackRank }
            val bestLanguagePreference = bestTrack.mapNotNull { it.languagePreference }.maxOrNull()
            val routeAudioCandidates = if (bestLanguagePreference != null) {
                bestTrack.filter { it.languagePreference == bestLanguagePreference }
            } else {
                bestTrack
            }

            data class RouteChoice(
                val video: VideoFormat,
                val audio: AudioFormat?,
                val estimate: DownloadRoutePlanner.RouteEstimate
            )

            val choices = mutableListOf<RouteChoice>()

            matchingCandidates.forEach { candidate ->
                val audioChoices: List<AudioFormat?> = when {
                    candidate.hasAudio -> listOf(null)
                    routeAudioCandidates.isNotEmpty() -> routeAudioCandidates.map { it }
                    else -> listOf(null) // yt-dlp bestaudio fallback
                }

                audioChoices.forEach { audio ->
                    val estimate = DownloadRoutePlanner.estimate(
                        context = context,
                        sourceUrl = url,
                        video = candidate,
                        audio = audio,
                        durationSeconds = duration
                    )
                    choices += RouteChoice(candidate, audio, estimate)
                }
            }

            val best = choices.minWithOrNull(
                compareBy<RouteChoice> { it.estimate.totalMs }
                    .thenBy { videoCodecPreference(it.video.videoCodec) }
                    .thenBy { containerPreference(it.video.extension) }
                    .thenBy { it.estimate.totalDownloadBytes }
                    .thenBy { protocolPreference(it.video.protocol) }
            ) ?: return null

            val chosenAudioId = when {
                best.video.hasAudio -> null
                best.audio != null -> best.audio.formatId
                else -> "bestaudio"
            }

            return best.video.copy(
                fileSize = best.estimate.totalDownloadBytes,
                sizeIsEstimate = best.video.sizeIsEstimate || best.estimate.sizeIsApproximate,
                audioDownloadSizeBytes = best.estimate.audioDownloadBytes,
                plannedAudioFormatId = chosenAudioId,
                estimatedTotalMs = best.estimate.totalMs
            )
        }

        val qualityOptions = QualityTier.entries.map { tier ->

            chooseFormat(tier) ?: VideoFormat(
                formatId = "",
                height = 0,
                extension = "",
                fileSize = null,
                sizeIsEstimate = true,
                hasAudio = false,
                fps = null,
                qualityTier = tier,
                videoCodec = null,
                audioCodec = null,
                protocol = null
            )
        }
        qualityOptions.forEach { option ->

            Log.i(
                TAG,
                "QUALITY_OPTION " +
                        "label=${option.qualityTier.label}, " +
                        "available=${option.isAvailable}, " +
                        "height=${option.height}, " +
                        "formatId=${option.formatId}, " +
                        "extension=${option.extension}, " +
                        "estimatedSize=${option.fileSize}, " +
                        "sizeIsEstimate=${option.sizeIsEstimate}, " +
                        "plannedAudio=${option.plannedAudioFormatId}, " +
                        "estimatedTotalMs=${option.estimatedTotalMs}"
            )
        }
        return VideoInfo(
            title = title,
            formats = qualityOptions,
            audioFormats = audioFormats
        )

    } finally {
        runCatching {
            downloader.callAttr("close")
        }
    }
}


private fun readableSize(bytes: Long?): String {
    if (bytes == null || bytes <= 0L) return "الحجم غير متاح"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        String.format(Locale.US, "%.2f GB", mb / 1024.0)
    } else {
        String.format(Locale.US, "%.1f MB", mb)
    }
}

/** Preserve the useful messages from every cause, not only the deepest cause. */
internal fun errorDetails(error: Throwable): String {
    val messages = generateSequence(error) { it.cause }
        .mapNotNull { it.message?.trim()?.takeIf(String::isNotEmpty) }
        .distinct()
        .toList()
    return messages.joinToString(" | ").ifBlank { error.javaClass.simpleName }
}

private fun downloadOneFormat(
    context: Context,
    url: String,
    directory: File,
    filePrefix: String,
    formatSelector: String,
    onProgress: (Float) -> Unit
): File {
    val outputTemplate = File(directory, "$filePrefix.%(ext)s").absolutePath
    val request = YtDlpRequest(url)
        .setOutputTemplate(outputTemplate)
        .addOption("-f", formatSelector)
        .addOption("--no-playlist")
        .addOption("--retries", "infinite")
        .addOption("--fragment-retries", "infinite")
        .addOption("--retry-sleep", "http:exp=1:20")
        .addOption("--retry-sleep", "fragment:exp=1:20")
        .addOption("--abort-on-unavailable-fragments")
    if (isYouTubeUrl(url)) {
        request.addOption(
            "--js-runtimes",
            "quickjs:${quickJsExecutablePath(context)}"
        )
        request.addOption(
            "--extractor-args",
            "youtube:player_client=default,-visionos"
        )
    }
    val diagnosticLines = Collections.synchronizedList(mutableListOf<String>())

    val logger = LogCallback { level, message ->
        diagnosticLines.add("[$level] $message")
        while (diagnosticLines.size > 20) {
            diagnosticLines.removeAt(0)
        }
        Log.i(TAG, "yt-dlp $level: $message")
    }

    val progressCallback = DownloadProgressCallback { progress, eta, line ->
        val safeProgress = if (progress.isFinite()) progress.coerceIn(0f, 100f) else 0f
        Log.d(TAG, "yt-dlp progress=$safeProgress eta=$eta $line")
        onProgress(safeProgress)
    }

    val future = YtDlp.executeDebug(
        request,
        logger,
        progressCallback
    )

    val response = try {
        future.get()

    } catch (error: InterruptedException) {

        future.cancel(true)

        Log.w(
            TAG,
            "YTDLP_CANCEL_REQUESTED format=$formatSelector"
        )

        Thread.currentThread().interrupt()

        throw IOException(
            "تم طلب إيقاف تنزيل الصيغة $formatSelector.",
            error
        )

    } catch (error: ExecutionException) {

        throw IOException(
            "yt-dlp لم يستطع تنزيل الصيغة $formatSelector: " +
                    errorDetails(error),
            error.cause ?: error
        )
    }

    if (!response.isSuccess) {
        val logs = synchronized(diagnosticLines) {
            diagnosticLines.takeLast(8).joinToString(" ; ")
        }
        throw IOException(
            "yt-dlp أعاد Exit Code ${response.exitCode} للصيغة $formatSelector. $logs"
        )
    }

    return directory.listFiles()
        .orEmpty()
        .filter { file ->
            file.isFile &&
                    file.name.startsWith("$filePrefix.") &&
                    !file.name.endsWith(".part") &&
                    file.length() > 0L
        }
        .maxByOrNull { it.lastModified() }
        ?: throw IOException("اكتمل yt-dlp دون العثور على ملف الصيغة $formatSelector.")
}


private fun saveVideoToMovies(
    context: Context,
    source: File,
    title: String,
    onCopyProgress: (Float) -> Unit
): Uri {

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        throw IOException(
            "الحفظ المباشر في المعرض بهذه النسخة يتطلب Android 10 أو أحدث."
        )
    }

    if (!source.isFile || source.length() <= 0L) {
        throw IOException("ملف المصدر غير موجود أو فارغ.")
    }

    val sourceBytes = source.length()

    val safeTitle = title
        .replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
        .trim()
        .take(70)
        .ifBlank { "video" }

    val extension = source.extension
        .lowercase(Locale.ROOT)
        .ifBlank { "mp4" }

    val mimeType = when (extension) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "3gp" -> "video/3gpp"
        else -> "video/*"
    }

    val values = ContentValues().apply {
        put(
            MediaStore.MediaColumns.DISPLAY_NAME,
            "${safeTitle}_${System.currentTimeMillis()}.$extension"
        )

        put(
            MediaStore.MediaColumns.MIME_TYPE,
            mimeType
        )

        put(
            MediaStore.MediaColumns.RELATIVE_PATH,
            "${Environment.DIRECTORY_MOVIES}/Video Downloader"
        )

        put(
            MediaStore.MediaColumns.IS_PENDING,
            1
        )
    }

    val resolver = context.contentResolver

    val destinationUri = resolver.insert(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        values
    ) ?: throw IOException(
        "تعذر إنشاء ملف الفيديو داخل مجلد Movies."
    )

    try {

        val output = resolver.openOutputStream(
            destinationUri,
            "w"
        ) ?: throw IOException(
            "تعذر فتح ملف الفيديو للحفظ."
        )

        var copiedBytes = 0L

        output.use { destination ->

            source.inputStream().use { input ->

                val buffer = ByteArray(64 * 1024)

                var lastReportedPercent = -1

                while (true) {

                    if (Thread.currentThread().isInterrupted) {
                        throw IOException("تم إيقاف حفظ الفيديو.")
                    }

                    val bytesRead = input.read(buffer)

                    if (bytesRead == -1) break

                    destination.write(
                        buffer,
                        0,
                        bytesRead
                    )

                    copiedBytes += bytesRead

                    val percent = (
                            copiedBytes.toDouble() /
                                    sourceBytes * 100.0
                            ).toInt().coerceIn(0, 100)

                    if (percent != lastReportedPercent) {

                        onCopyProgress(percent.toFloat())

                        lastReportedPercent = percent
                    }
                }
            }
        }

        if (copiedBytes != sourceBytes) {
            throw IOException(
                "لم يكتمل نسخ الفيديو. " +
                        "المطلوب: $sourceBytes، المنسوخ: $copiedBytes."
            )
        }

        val publishValues = ContentValues().apply {
            put(
                MediaStore.MediaColumns.IS_PENDING,
                0
            )
        }

        val updatedRows = resolver.update(
            destinationUri,
            publishValues,
            null,
            null
        )

        Log.i(
            TAG,
            "SAVE_VERIFY " +
                    "copiedBytes=$copiedBytes, " +
                    "expectedBytes=$sourceBytes, " +
                    "updatedRows=$updatedRows"
        )

        if (updatedRows <= 0) {
            throw IOException(
                "تم نسخ الفيديو، لكن تعذر نشره داخل المعرض."
            )
        }

        return destinationUri

    } catch (error: Exception) {

        resolver.delete(destinationUri, null, null)

        throw error
    }
}



private fun estimateVideoFrameCount(
    file: File,
    fallbackFps: Double?
): Int {

    val startedAt = SystemClock.elapsedRealtime()

    val session = FFprobeKit.executeWithArguments(
        arrayOf(
            "-v", "error",
            "-select_streams", "v:0",
            "-show_entries",
            "stream=codec_type,codec_name,avg_frame_rate,duration:format=duration,size",
            "-of", "json",
            file.absolutePath
        )
    )

    val output = session.output.orEmpty()

    Log.i(TAG, "SOURCE_METADATA[VIDEO_INPUT]=$output")

    val estimatedFrames = if (
        ReturnCode.isSuccess(session.getReturnCode())
    ) {
        runCatching {
            val root = JSONObject(output)
            val streams = root.optJSONArray("streams")
                ?: throw IOException("لم يتم العثور على بيانات الفيديو.")

            val videoStream = (0 until streams.length())
                .asSequence()
                .mapNotNull { streams.optJSONObject(it) }
                .firstOrNull {
                    it.optString("codec_type") == "video"
                }
                ?: throw IOException("مسار الفيديو غير موجود.")

            val streamDuration = videoStream
                .optString("duration")
                .toDoubleOrNull()
                ?.takeIf { it.isFinite() && it > 0.0 }

            val formatDuration = root
                .optJSONObject("format")
                ?.optString("duration")
                ?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it > 0.0 }

            val duration = streamDuration
                ?: formatDuration
                ?: throw IOException("مدة الفيديو غير متاحة.")

            val frameRateParts = videoStream
                .optString("avg_frame_rate")
                .split("/")

            val probedFps = if (frameRateParts.size == 2) {
                val numerator = frameRateParts[0].toDoubleOrNull()
                val denominator = frameRateParts[1].toDoubleOrNull()

                if (
                    numerator != null &&
                    denominator != null &&
                    denominator != 0.0
                ) {
                    numerator / denominator
                } else {
                    null
                }
            } else {
                frameRateParts.firstOrNull()?.toDoubleOrNull()
            }

            val fps = probedFps
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?: fallbackFps?.takeIf {
                    it.isFinite() && it > 0.0
                }
                ?: throw IOException("معدل الفريمات غير متاح.")

            val estimatedTotal = duration * fps

            if (!estimatedTotal.isFinite() || estimatedTotal <= 0.0) {
                throw IOException("تعذر تقدير عدد الفريمات.")
            }

            ceil(estimatedTotal)
                .toLong()
                .coerceIn(1L, Int.MAX_VALUE.toLong())
                .toInt()

        }.getOrNull()

    } else {
        null
    }

    val totalFrames = estimatedFrames ?: run {
        Log.w(
            TAG,
            "SOURCE_FRAME_ESTIMATE_FALLBACK=exact_count"
        )
        countVideoFrames(file)
    }

    Log.i(TAG, "SOURCE_FRAME_ESTIMATE=$totalFrames")

    Log.i(
        TAG,
        "SOURCE_FRAME_ESTIMATE_TIME_MS=${SystemClock.elapsedRealtime() - startedAt}"
    )

    return totalFrames
}



private fun logMediaProbe(
    file: File
): MediaProbe {

    val startedAt = SystemClock.elapsedRealtime()

    val probe = probeMediaFile(
        file = file,
        countFrames = false
    )
    Log.i(TAG, "MEDIA_PROBE[FINAL_OUTPUT]_CONTAINER=${probe.formatNames}")
    Log.i(TAG, "MEDIA_PROBE[FINAL_OUTPUT]_VIDEO_CODEC=${probe.videoCodec}")
    Log.i(TAG, "MEDIA_PROBE[FINAL_OUTPUT]_AUDIO_CODEC=${probe.audioCodec}")
    Log.i(TAG, "MEDIA_PROBE[FINAL_OUTPUT]_VIDEO_PROFILE=${probe.videoProfile}")
    Log.i(TAG, "MEDIA_PROBE[FINAL_OUTPUT]_VIDEO_PIXEL_FORMAT=${probe.videoPixelFormat}")
    Log.i(TAG, "MEDIA_PROBE[FINAL_OUTPUT]_AUDIO_PROFILE=${probe.audioProfile}")
    Log.i(TAG, "MEDIA_PROBE[FINAL_OUTPUT]_WIDTH=${probe.videoWidth}")
    Log.i(TAG, "MEDIA_PROBE[FINAL_OUTPUT]_HEIGHT=${probe.videoHeight}")
    Log.i(TAG, "MEDIA_PROBE[FINAL_OUTPUT]_FPS=${probe.frameRate}")
    Log.i(TAG, "MEDIA_PROBE[FINAL_OUTPUT]_DURATION=${probe.durationSeconds}")
    Log.i(TAG, "MEDIA_PROBE[FINAL_OUTPUT]_VIDEO_FRAMES=${probe.videoFrameCount}")

    Log.i(
        TAG,
        "MEDIA_PROBE[FINAL_OUTPUT]_TIME_MS=" +
                (SystemClock.elapsedRealtime() - startedAt)
    )

    return probe
}
private fun isMp4CompatibleH264(
    probe: MediaProbe
): Boolean {

    val profile = probe.videoProfile
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?: return false

    val pixelFormat = probe.videoPixelFormat
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?: return false

    val supportedProfiles = setOf(
        "66",
        "578",
        "77",
        "100",
        "baseline",
        "constrained baseline",
        "main",
        "high"
    )

    return probe.videoCodec.equals(
        "h264",
        ignoreCase = true
    ) &&
            profile in supportedProfiles &&
            pixelFormat == "yuv420p"
}


private fun isMp4CompatibleAacLc(
    probe: MediaProbe
): Boolean {

    val profile = probe.audioProfile
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?: return false

    return probe.audioCodec.equals(
        "aac",
        ignoreCase = true
    ) &&
            profile in setOf("1", "lc")
}
private fun chooseH264BitrateKbps(
    probe: MediaProbe
): Int {

    val fallbackKbps = when (probe.videoHeight ?: 720) {
        in 2160..Int.MAX_VALUE -> 12000
        in 1440..2159 -> 8000
        in 1080..1439 -> 4000
        in 720..1079 -> 2200
        in 480..719 -> 1200
        else -> 600
    }

    val sourceKbps = probe.videoBitrateBps
        ?.takeIf { it > 0L }
        ?.div(1000.0)

    val targetKbps = sourceKbps
        ?.times(1.35)
        ?: fallbackKbps.toDouble()

    return targetKbps
        .coerceIn(350.0, 16000.0)
        .roundToLong()
        .toInt()
}
private fun checkFfmpegEncoders() {

    Log.i(TAG, "FFMPEG_ENCODER_CHECK_START")

    FFmpegKit.executeWithArgumentsAsync(
        arrayOf("-hide_banner", "-encoders")
    ) { session ->

        val output = session.output.orEmpty()
        val returnCode = session.getReturnCode()

        Log.i(
            TAG,
            "FFMPEG_ENCODER_CHECK_RETURN=$returnCode"
        )

        if (!ReturnCode.isSuccess(returnCode)) {

            Log.e(
                TAG,
                "FFMPEG_ENCODER_CHECK_ERROR=$output"
            )

        } else {

            val encoders = output.lineSequence()
                .filter {
                    it.contains("mediacodec", ignoreCase = true) ||
                            it.contains("openh264", ignoreCase = true) ||
                            it.contains("aac", ignoreCase = true)
                }
                .toList()

            if (encoders.isEmpty()) {
                Log.w(
                    TAG,
                    "FFMPEG_ENCODER_CHECK_NO_MATCH"
                )
            } else {
                encoders.forEach {
                    Log.i(TAG, "FFMPEG_ENCODER_CHECK=$it")
                }
            }
        }
    }
}

private fun checkYtDlpOptionMapping() {
    try {
        val runner = Python.getInstance()
            .getModule("ytdlp_runner")

        val flagMap = runner["_FLAG_MAP"]
            ?: throw IOException("yt-dlp flag map is unavailable.")

        val flags = listOf(
            "--retries",
            "--fragment-retries",
            "--retry-sleep",
            "--abort-on-unavailable-fragments",
            "--js-runtimes",
            "--extractor-args"
        )

        flags.forEach { flag ->
            val mapping = flagMap.callAttr("get", flag)

            val supported =
                mapping != null && mapping.toString() != "None"

            Log.i(
                TAG,
                "YTDLP_OPTION_MAPPING " +
                        "flag=$flag, " +
                        "supported=$supported, " +
                        "mapping=$mapping"
            )
        }

    } catch (error: Exception) {
        Log.e(
            TAG,
            "YTDLP_OPTION_MAPPING_ERROR",
            error
        )
    }
}
private fun checkAndroidAvcEncoders() {

    Log.i(
        TAG,
        "ANDROID_AVC_ENCODER_CHECK_START API=${Build.VERSION.SDK_INT}"
    )

    try {

        val codecInfos = MediaCodecList(
            MediaCodecList.REGULAR_CODECS
        ).codecInfos

        val avcEncoders = codecInfos.filter { info ->

            info.isEncoder &&
                    info.supportedTypes.any {
                        it.equals("video/avc", ignoreCase = true)
                    }
        }

        Log.i(
            TAG,
            "ANDROID_AVC_ENCODER_COUNT=${avcEncoders.size}"
        )

        if (avcEncoders.isEmpty()) {
            Log.w(
                TAG,
                "ANDROID_AVC_ENCODER_NONE"
            )
            return
        }

        avcEncoders.forEach { info ->

            val hardware = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            ) {
                info.isHardwareAccelerated.toString()
            } else {
                "unknown"
            }

            val software = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            ) {
                info.isSoftwareOnly.toString()
            } else {
                "unknown"
            }

            val vendor = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            ) {
                info.isVendor.toString()
            } else {
                "unknown"
            }

            try {

                val capabilities = info.getCapabilitiesForType(
                    "video/avc"
                )

                val videoCapabilities = capabilities.videoCapabilities
                    ?: throw IllegalStateException(
                        "Video capabilities unavailable for ${info.name}"
                    )

                val supports1080p30 = runCatching {
                    videoCapabilities.areSizeAndRateSupported(
                        1920,
                        1080,
                        30.0
                    )
                }.getOrDefault(false)

                val colorFormats =
                    capabilities.colorFormats.joinToString(",") {
                        "0x%08X".format(Locale.US, it)
                    }

                Log.i(
                    TAG,
                    "ANDROID_AVC_ENCODER " +
                            "name=${info.name}, " +
                            "hardware=$hardware, " +
                            "software=$software, " +
                            "vendor=$vendor, " +
                            "supports1080p30=$supports1080p30, " +
                            "colorFormats=[$colorFormats]"
                )

                Log.i(
                    TAG,
                    "ANDROID_AVC_ENCODER_LIMITS " +
                            "name=${info.name}, " +
                            "width=${videoCapabilities.supportedWidths}, " +
                            "height=${videoCapabilities.supportedHeights}, " +
                            "fps=${videoCapabilities.supportedFrameRates}"
                )

            } catch (error: Exception) {

                Log.e(
                    TAG,
                    "ANDROID_AVC_ENCODER_CAPABILITY_ERROR " +
                            "name=${info.name}",
                    error
                )
            }
        }

    } catch (error: Exception) {

        Log.e(
            TAG,
            "ANDROID_AVC_ENCODER_CHECK_ERROR",
            error
        )
    }
}


private fun checkAndroidAv1Decoders() {
    val mimeType = "video/av01"

    Log.i(
        TAG,
        "ANDROID_AV1_DECODER_CHECK_START API=${Build.VERSION.SDK_INT}"
    )

    try {
        val decoders = MediaCodecList(
            MediaCodecList.REGULAR_CODECS
        ).codecInfos.filter { info ->
            !info.isEncoder &&
                    info.supportedTypes.any {
                        it.equals(mimeType, ignoreCase = true)
                    }
        }

        Log.i(
            TAG,
            "ANDROID_AV1_DECODER_COUNT=${decoders.size}"
        )

        if (decoders.isEmpty()) {
            Log.w(TAG, "ANDROID_AV1_DECODER_NONE")
            return
        }

        decoders.forEach { info ->
            val hardware = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            ) {
                info.isHardwareAccelerated.toString()
            } else {
                "unknown"
            }

            val software = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            ) {
                info.isSoftwareOnly.toString()
            } else {
                "unknown"
            }

            val vendor = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            ) {
                info.isVendor.toString()
            } else {
                "unknown"
            }

            try {
                val capabilities = info.getCapabilitiesForType(
                    mimeType
                )

                val videoCapabilities = capabilities.videoCapabilities
                    ?: throw IllegalStateException(
                        "AV1 video capabilities unavailable for ${info.name}"
                    )

                val supports1080p30 = runCatching {
                    videoCapabilities.areSizeAndRateSupported(
                        1920,
                        1080,
                        30.0
                    )
                }.getOrDefault(false)

                val profiles = capabilities.profileLevels.joinToString(",") {
                    "profile=${it.profile}:level=${it.level}"
                }

                val colorFormats = capabilities.colorFormats.joinToString(",") {
                    "0x%08X".format(Locale.US, it)
                }

                Log.i(
                    TAG,
                    "ANDROID_AV1_DECODER " +
                            "name=${info.name}, " +
                            "hardware=$hardware, " +
                            "software=$software, " +
                            "vendor=$vendor, " +
                            "supports1080p30=$supports1080p30, " +
                            "profiles=[$profiles], " +
                            "colorFormats=[$colorFormats]"
                )

                Log.i(
                    TAG,
                    "ANDROID_AV1_DECODER_LIMITS " +
                            "name=${info.name}, " +
                            "width=${videoCapabilities.supportedWidths}, " +
                            "height=${videoCapabilities.supportedHeights}, " +
                            "fps=${videoCapabilities.supportedFrameRates}"
                )

            } catch (error: Exception) {
                Log.e(
                    TAG,
                    "ANDROID_AV1_DECODER_CAPABILITY_ERROR name=${info.name}",
                    error
                )
            }
        }

    } catch (error: Exception) {
        Log.e(
            TAG,
            "ANDROID_AV1_DECODER_CHECK_ERROR",
            error
        )
    }
}

private fun countVideoFrames(file: File): Int {
    Log.i(TAG, "FRAME_PROBE_EXISTS=${file.exists()}")
    Log.i(TAG, "FRAME_PROBE_SIZE=${file.length()}")
    Log.i(TAG, "FRAME_PROBE_PATH=${file.absolutePath}")

    val session = FFprobeKit.executeWithArguments(
        arrayOf(
            "-v", "error",
            "-select_streams", "v:0",
            "-count_frames",
            "-show_entries", "stream=nb_read_frames",
            "-of", "default=noprint_wrappers=1:nokey=1",
            file.absolutePath
        )
    )

    val output = session.output.orEmpty()
    val returnCode = session.getReturnCode()
    val probeLogs = session.allLogsAsString.orEmpty()

    Log.i(TAG, "FRAME_PROBE_RETURN_CODE=$returnCode")
    Log.i(TAG, "FRAME_PROBE_OUTPUT=[$output]")
    Log.i(TAG, "FRAME_PROBE_LOGS=[$probeLogs]")

    if (!ReturnCode.isSuccess(returnCode)) {
        throw IOException(
            "FFprobe failed. ReturnCode=$returnCode Output=$output"
        )
    }

    val frameCount = output
        .lineSequence()
        .mapNotNull { it.trim().toIntOrNull() }
        .firstOrNull { it > 0 }

    return frameCount
        ?: throw IOException(
            "تعذر تحديد عدد فريمات الفيديو. FFprobe Output=[$output]"
        )
}

// تقرير تشخيص لكل عملية تنزيل: أزمنة المراحل والأحجام والسرعات وقرار FFmpeg.
// يُكتب في المِلف reports/last-report.txt داخل مجلد التطبيق، وفي Logcat بالوسم DL_REPORT.
// لا يحتوي على رابط الفيديو الكامل، فقط اسم الموقع.
private class DownloadReport(
    context: Context,
    private val sourceHost: String
) {

    private val startedAt = SystemClock.elapsedRealtime()
    private val lines = mutableListOf<String>()
    private var errorText: String? = null
    private val environmentAtStart = snapshot(context)

    private fun offsetMs(): Long = SystemClock.elapsedRealtime() - startedAt

    private fun fmt(value: Double?): String {
        return if (value == null) {
            "n/a"
        } else {
            String.format(Locale.US, "%.2f", value)
        }
    }

    private fun snapshot(context: Context): String {
        return runCatching {
            val powerManager =
                context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager

            val thermal = if (Build.VERSION.SDK_INT >= 29) {
                powerManager?.currentThermalStatus
            } else {
                null
            }

            "powerSaveMode=${powerManager?.isPowerSaveMode} thermalStatus=$thermal"
        }.getOrDefault("powerSaveMode=n/a thermalStatus=n/a")
    }

    // معلومة عامة دون مدة.
    fun info(key: String, value: Any?) {
        lines.add("[+${offsetMs()}ms] $key: $value")
    }

    //هذه مرحلة لها بداية ونهاية.
    fun step(name: String, stepStartedAt: Long, details: String = "") {
        val took = SystemClock.elapsedRealtime() - stepStartedAt
        lines.add("[+${offsetMs()}ms] $name: took=${took}ms $details")
    }

    // مرحلة نقل مِلف (تنزيل أو حفظ) مع السرعة.
    // startupMs = الزمن من بَدء الأمر حتى أول تقدم، ويظهر غالبًا استخراج بيانات الرابط.
    fun transfer(
        name: String,
        stepStartedAt: Long,
        firstProgressAt: Long,
        bytes: Long,
        details: String = ""
    ) {
        val now = SystemClock.elapsedRealtime()
        val totalMs = now - stepStartedAt

        val startupMs = if (firstProgressAt > 0L) {
            firstProgressAt - stepStartedAt
        } else {
            null
        }

        val transferMs = if (firstProgressAt > 0L) {
            now - firstProgressAt
        } else {
            totalMs
        }

        val mib = bytes / 1048576.0

        val speed = if (transferMs > 0L) {
            mib / (transferMs / 1000.0)
        } else {
            null
        }

        lines.add(
            "[+${offsetMs()}ms] $name: total=${totalMs}ms " +
                    "startupBeforeFirstProgress=${startupMs ?: "n/a"}ms " +
                    "transfer=${transferMs}ms size=${fmt(mib)}MiB " +
                    "speed=${fmt(speed)}MiB/s $details"
        )
    }

    fun ffmpeg(
        ffmpegStartedAt: Long,
        videoSeconds: Double?,
        totalFrames: Int,
        outputBytes: Long
    ) {
        val elapsedMs = SystemClock.elapsedRealtime() - ffmpegStartedAt
        val seconds = elapsedMs / 1000.0

        val speedVsRealtime = if (videoSeconds != null && seconds > 0.0) {
            videoSeconds / seconds
        } else {
            null
        }

        val framesPerSecond = if (seconds > 0.0) {
            totalFrames / seconds
        } else {
            null
        }

        lines.add(
            "[+${offsetMs()}ms] FFMPEG_RUN: took=${elapsedMs}ms " +
                    "videoSeconds=${fmt(videoSeconds)} " +
                    "speedVsRealtime=${fmt(speedVsRealtime)}x " +
                    "avgFramesPerSecond=${fmt(framesPerSecond)} " +
                    "outputSize=${fmt(outputBytes / 1048576.0)}MiB"
        )
    }

    fun fail(error: Throwable) {
        errorText = (error.message ?: error.toString()).take(600)
    }

    fun finish(context: Context) {
        runCatching {
            val totalMs = offsetMs()
            val environmentAtEnd = snapshot(context)

            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(java.util.Date())

            val text = buildString {
                appendLine("=== DOWNLOAD REPORT ===")
                appendLine("time: $stamp")
                appendLine("status: ${if (errorText == null) "SUCCESS" else "FAILED"}")
                appendLine("total: ${totalMs}ms")
                errorText?.let { appendLine("error: $it") }
                appendLine("source: $sourceHost")
                appendLine(
                    "device: ${Build.MANUFACTURER} ${Build.MODEL}, " +
                            "android=${Build.VERSION.SDK_INT}, " +
                            "abi=${Build.SUPPORTED_ABIS.firstOrNull()}, " +
                            "cpuCores=${Runtime.getRuntime().availableProcessors()}"
                )
                appendLine("stateAtStart: $environmentAtStart")
                appendLine("stateAtEnd: $environmentAtEnd")
                lines.forEach { appendLine(it) }
                appendLine("=== END REPORT ===")
            }

            text.lineSequence()
                .filter { it.isNotBlank() }
                .forEach { Log.i(TAG, "DL_REPORT | $it") }

            val reportsDirectory = File(
                context.getExternalFilesDir(null) ?: context.filesDir,
                "reports"
            )

            reportsDirectory.mkdirs()

            File(reportsDirectory, "last-report.txt").writeText(text)
            File(reportsDirectory, "report-$stamp.txt").writeText(text)

            // نحتفظ بآخر 10 تقارير فقط.
            reportsDirectory.listFiles()
                .orEmpty()
                .filter { it.name.startsWith("report-") }
                .sortedByDescending { it.name }
                .drop(10)
                .forEach { it.delete() }
        }.onFailure {
            Log.w(TAG, "DL_REPORT_WRITE_FAILED", it)
        }
    }
}

private fun describeProbe(probe: MediaProbe): String {
    return "container=${probe.formatNames} " +
            "vcodec=${probe.videoCodec} vprofile=${probe.videoProfile} " +
            "pixelFormat=${probe.videoPixelFormat} " +
            "size=${probe.videoWidth}x${probe.videoHeight} " +
            "fps=${probe.frameRate} " +
            "vbitrateKbps=${probe.videoBitrateBps?.div(1000)} " +
            "acodec=${probe.audioCodec} aprofile=${probe.audioProfile} " +
            "durationSec=${probe.durationSeconds} frames=${probe.videoFrameCount}"
}


internal fun downloadSelectedVideo(
    context: Context,
    url: String,
    title: String,
    selectedFormat: VideoFormat,
    audioFormats: List<AudioFormat>,
    onProgress: (Float, String) -> Unit
): Uri {

    val report = DownloadReport(
        context,
        runCatching { url.toUri().host }.getOrNull() ?: "unknown"
    )

    report.info(
        "SELECTED_VIDEO",
        "id=${selectedFormat.formatId} height=${selectedFormat.height} " +
                "fps=${selectedFormat.fps} ext=${selectedFormat.extension} " +
                "vcodec=${selectedFormat.videoCodec} " +
                "acodec=${selectedFormat.audioCodec} " +
                "protocol=${selectedFormat.protocol} " +
                "hasAudio=${selectedFormat.hasAudio} " +
                "plannedAudio=${selectedFormat.plannedAudioFormatId} " +
                "estimatedTotalMs=${selectedFormat.estimatedTotalMs} " +
                "estimatedSizeMiB=${selectedFormat.fileSize?.div(1048576L)} " +
                "sizeIsEstimate=${selectedFormat.sizeIsEstimate}"
    )

    val baseDirectory = context.getExternalFilesDir(
        Environment.DIRECTORY_MOVIES
    ) ?: context.cacheDir

    val workDir = File(
        baseDirectory,
        "video-download-${System.currentTimeMillis()}"
    )

    if (!DownloadWorkspaceRegistry.createAndRegister(workDir)) {
        throw IOException("تعذر إنشاء مجلد العمل.")
    }

    // إذا استمر FFmpeg بعد طلب الإلغاء، نؤجل حذف الملفات
    // حتى يصل Callback انتهاء العملية.
    val cleanupAfterFfmpeg = AtomicBoolean(false)

    var videoDownloadAttempted = false
    var videoDownloadSucceeded = false

    try {

        val availableBytes = runCatching {
            StatFs(workDir.absolutePath).availableBytes
        }.getOrNull()

        report.info("FREE_SPACE_MiB", availableBytes?.div(1048576L))

        if (
            availableBytes != null &&
            availableBytes < 100L * 1024L * 1024L
        ) {
            throw IOException(
                "المساحة المتاحة أقل من 100 ميجابايت."
            )
        }

        // -------------------------------------------------
        // 1. تنزيل صيغة الفيديو المختارة
        // -------------------------------------------------

        val videoPrefix = if (selectedFormat.hasAudio) {
            "complete"
        } else {
            "video"
        }

        val videoProgressLimit = if (selectedFormat.hasAudio) {
            78f
        } else {
            42f
        }

        onProgress(1f, "جاري تنزيل الفيديو...")

        val videoStartedAt = SystemClock.elapsedRealtime()
        var videoFirstProgressAt = 0L
        videoDownloadAttempted = true

        val videoFile = downloadOneFormat(
            context = context,
            url = url,
            directory = workDir,
            filePrefix = videoPrefix,
            formatSelector = selectedFormat.formatId
        ) { progress ->

            if (videoFirstProgressAt == 0L) {
                videoFirstProgressAt = SystemClock.elapsedRealtime()
            }

            onProgress(
                progress * videoProgressLimit / 100f,
                "تنزيل الفيديو: ${progress.toInt()}%"
            )
        }

        videoDownloadSucceeded = true
        report.transfer(
            "VIDEO_DOWNLOAD",
            videoStartedAt,
            videoFirstProgressAt,
            videoFile.length(),
            "format=${selectedFormat.formatId}"
        )
        DownloadRoutePlanner.recordTransferSample(
            context = context,
            sourceUrl = url,
            protocol = selectedFormat.protocol,
            bytes = videoFile.length(),
            totalElapsedMs = SystemClock.elapsedRealtime() - videoStartedAt,
            startupElapsedMs = if (videoFirstProgressAt > 0L) videoFirstProgressAt - videoStartedAt else null
        )

        // -------------------------------------------------
        // 2. فحص مسار الفيديو والصوت المدمج فعليًا
        // -------------------------------------------------

        val videoProbeStartedAt = SystemClock.elapsedRealtime()
        val videoProbe = probeMediaFile(videoFile)

        report.step(
            "VIDEO_PROBE",
            videoProbeStartedAt,
            describeProbe(videoProbe)
        )

        if (videoProbe.videoCodec.isNullOrBlank()) {
            throw IOException(
                "الملف الذي تم تنزيله لا يحتوي على مسار فيديو صالح."
            )
        }

        var audioFile: File? = null
        var audioProbe: MediaProbe? = null

        // لا تعتمد على بينات التحليل وحدها
        // نفحص المِلف الحقيقي لمعرفة وجود الصوت.
        if (!videoProbe.audioCodec.isNullOrBlank()) {

            audioFile = videoFile
            audioProbe = videoProbe

            report.info(
                "AUDIO_SOURCE",
                "embedded codec=${videoProbe.audioCodec}"
            )

            Log.i(
                TAG,
                "DOWNLOAD_AUDIO_SOURCE=embedded " +
                        "codec=${videoProbe.audioCodec}"
            )

        } else {

            // -------------------------------------------------
            // 3. تنزيل الصوت عند عدم وجوده داخل المِلف
            // -------------------------------------------------

            val bestAudioFallback = AudioFormat(
                formatId = "bestaudio",
                bitrate = 0.0,
                extension = "auto",
                fileSize = null
            )
            val plannedAudio = when (selectedFormat.plannedAudioFormatId) {
                "bestaudio" -> bestAudioFallback
                null -> null
                else -> audioFormats.firstOrNull { it.formatId == selectedFormat.plannedAudioFormatId }
            }
            val rankedAudioCandidates = audioFormats
                .filter { audioTrackRank(it) < 2 }
                .ifEmpty { audioFormats }
            val candidates = (
                    listOfNotNull(plannedAudio) +
                            rankedAudioCandidates
                                .filterNot { it.formatId == plannedAudio?.formatId }
                                .take(if (plannedAudio == null) 3 else 2) +
                            bestAudioFallback
                    ).distinctBy { it.formatId }

            report.info(
                "AUDIO_CANDIDATES",
                candidates.joinToString(" , ") {
                    "${it.formatId}(rank=${audioTrackRank(it)}, lang=${it.language})"
                }
            )

            val audioStart = if (selectedFormat.hasAudio) {
                78f
            } else {
                42f
            }

            val audioSpan = 82f - audioStart

            val audioErrors = mutableListOf<String>()
            var highestAudioProgress = audioStart

            for ((index, candidate) in candidates.withIndex()) {

                val attemptPrefix = "audio_${index + 1}"

                val attemptStartProgress =
                    audioStart +
                            (index.toFloat() / candidates.size.toFloat()) * audioSpan

                highestAudioProgress = maxOf(
                    highestAudioProgress,
                    attemptStartProgress
                )

                onProgress(
                    highestAudioProgress.coerceIn(audioStart, 82f),
                    "جاري تجربة صيغة الصوت ${index + 1}/${candidates.size}..."
                )

                val audioStartedAt = SystemClock.elapsedRealtime()
                var audioFirstProgressAt = 0L
                var candidateDownloadCompleted = false

                try {

                    val candidateFile = downloadOneFormat(
                        context = context,
                        url = url,
                        directory = workDir,
                        filePrefix = attemptPrefix,
                        formatSelector = candidate.formatId
                    ) { progress ->

                        if (audioFirstProgressAt == 0L) {
                            audioFirstProgressAt = SystemClock.elapsedRealtime()
                        }

                        val ratio =
                            (index + progress / 100f) /
                                    candidates.size.toFloat()

                        val stageProgress =
                            audioStart + ratio * audioSpan

                        highestAudioProgress = maxOf(
                            highestAudioProgress,
                            stageProgress
                        )

                        onProgress(
                            highestAudioProgress.coerceIn(audioStart, 82f),
                            "تنزيل الصوت ${candidate.extension}: " +
                                    "${progress.toInt()}%"
                        )
                    }

                    candidateDownloadCompleted = true
                    report.transfer(
                        "AUDIO_DOWNLOAD",
                        audioStartedAt,
                        audioFirstProgressAt,
                        candidateFile.length(),
                        "format=${candidate.formatId} " +
                                "language=${candidate.language} " +
                                "note=${candidate.formatNote}"
                    )
                    DownloadRoutePlanner.recordTransferSample(
                        context = context,
                        sourceUrl = url,
                        protocol = candidate.protocol ?: selectedFormat.protocol,
                        bytes = candidateFile.length(),
                        totalElapsedMs = SystemClock.elapsedRealtime() - audioStartedAt,
                        startupElapsedMs = if (audioFirstProgressAt > 0L) audioFirstProgressAt - audioStartedAt else null
                    )

                    val candidateProbeStartedAt = SystemClock.elapsedRealtime()
                    val candidateProbe = probeMediaFile(candidateFile)

                    report.step(
                        "AUDIO_PROBE",
                        candidateProbeStartedAt,
                        "acodec=${candidateProbe.audioCodec} " +
                                "aprofile=${candidateProbe.audioProfile}"
                    )

                    if (candidateProbe.audioCodec.isNullOrBlank()) {
                        candidateFile.delete()

                        throw IOException(
                            "الصيغة ${candidate.formatId} لا تحتوي على مسار صوت."
                        )
                    }

                    audioFile = candidateFile
                    audioProbe = candidateProbe

                    report.info(
                        "AUDIO_SOURCE",
                        "separate format=${candidate.formatId} " +
                                "language=${candidate.language} " +
                                "note=${candidate.formatNote} " +
                                "codec=${candidateProbe.audioCodec}"
                    )

                    Log.i(
                        TAG,
                        "DOWNLOAD_AUDIO_SELECTED " +
                                "format=${candidate.formatId}, " +
                                "codec=${candidateProbe.audioCodec}"
                    )

                    break

                } catch (error: Exception) {

                    // لا تواصل المحاولات إذا كانت العملية قد أُلغيت.
                    if (Thread.currentThread().isInterrupted) {
                        throw IOException(
                            "تم إيقاف تنزيل الصوت.",
                            error
                        )
                    }

                    val detail = errorDetails(error)

                    report.step(
                        "AUDIO_ATTEMPT_FAILED",
                        audioStartedAt,
                        "format=${candidate.formatId} error=${detail.take(300)}"
                    )

                    if (!candidateDownloadCompleted) {
                        DownloadRoutePlanner.recordAttemptFailure(
                            context = context,
                            sourceUrl = url,
                            protocol = candidate.protocol ?: selectedFormat.protocol
                        )
                    }
                    audioErrors.add(
                        "${candidate.formatId}: $detail"
                    )

                    workDir.listFiles()
                        .orEmpty()
                        .filter {
                            it.isFile &&
                                    it.name.startsWith("$attemptPrefix.")
                        }
                        .forEach {
                            it.delete()
                        }

                    Log.w(
                        TAG,
                        "Audio format ${candidate.formatId} failed.",
                        error
                    )

                    val lowerDetail = detail.lowercase(Locale.ROOT)

                    if (
                        lowerDetail.contains("no space left") ||
                        lowerDetail.contains("errno 28") ||
                        lowerDetail.contains("not enough space")
                    ) {
                        throw IOException(
                            "لا توجد مساحة كافية لمتابعة تنزيل الصوت: $detail",
                            error
                        )
                    }
                }
            }

            if (audioFile == null) {
                throw IOException(
                    "فشل تنزيل الصوت بعد تجربة ${candidates.size} صيغ. " +
                            audioErrors.joinToString(" || ")
                )
            }
        }

        // -------------------------------------------------
        // 4. تأكيد وجود مساري الفيديو والصوت
        // -------------------------------------------------

        val finalAudioFile = audioFile

        val finalAudioProbe = audioProbe
            ?: throw IOException("تعذر فحص ملف الصوت.")

        if (finalAudioProbe.audioCodec.isNullOrBlank()) {
            throw IOException("ملف الصوت لا يحتوي على مسار صوت صالح.")
        }

        val sameInputFile =
            videoFile.absolutePath == finalAudioFile.absolutePath

        val audioInputIndex = if (sameInputFile) {
            0
        } else {
            1
        }

        // -------------------------------------------------
        // 5. تحديد هل نحتاج لإعادة الترميز أم Stream Copy
        // -------------------------------------------------

        val copyVideo = isMp4CompatibleH264(
            videoProbe
        )

        val copyAudio = isMp4CompatibleAacLc(
            finalAudioProbe
        )

        val targetVideoBitrate = if (copyVideo) {
            null
        } else {
            chooseH264BitrateKbps(videoProbe)
        }

        Log.i(
            TAG,
            "FFMPEG_PLAN " +
                    "sourceVideo=${videoProbe.videoCodec}, " +
                    "sourceVideoProfile=${videoProbe.videoProfile}, " +
                    "sourcePixelFormat=${videoProbe.videoPixelFormat}, " +
                    "sourceAudio=${finalAudioProbe.audioCodec}, " +
                    "sourceAudioProfile=${finalAudioProbe.audioProfile}, " +
                    "videoAction=${if (copyVideo) "copy" else "encode"}, " +
                    "audioAction=${if (copyAudio) "copy" else "encode"}, " +
                    "videoBitrateKbps=$targetVideoBitrate"
        )

        // -------------------------------------------------
        // 6. تقدير عدد فريمات المصدر
        // -------------------------------------------------

        report.info(
            "FFMPEG_PLAN",
            "videoAction=${if (copyVideo) "copy" else "encode"} " +
                    "audioAction=${if (copyAudio) "copy" else "encode"} " +
                    "videoBitrateKbps=$targetVideoBitrate " +
                    "sameInputFile=$sameInputFile"
        )

        val frameEstimateStartedAt = SystemClock.elapsedRealtime()

        val totalVideoFrames = estimateVideoFrameCount(
            videoFile,
            selectedFormat.fps
        )

        report.step(
            "SOURCE_FRAME_ESTIMATE",
            frameEstimateStartedAt,
            "frames=$totalVideoFrames"
        )

        onProgress(
            82f,
            "جاري تجهيز ملف MP4 النهائي..."
        )

        val mergedFile = File(
            workDir,
            "merged.mp4"
        )

        // -------------------------------------------------
        // 7. تجهيز أوامر FFmpeg
        // -------------------------------------------------

        val ffmpegArguments = mutableListOf(
            "-y",
            "-i",
            videoFile.absolutePath
        )

        if (!sameInputFile) {
            ffmpegArguments.addAll(
                listOf(
                    "-i",
                    finalAudioFile.absolutePath
                )
            )
        }

        ffmpegArguments.addAll(
            listOf(
                "-map", "0:v:0",
                "-map", "$audioInputIndex:a:0",
                "-map_metadata", "0"
            )
        )

        if (copyVideo) {

            ffmpegArguments.addAll(
                listOf(
                    "-c:v", "copy"
                )
            )

        } else {

            ffmpegArguments.addAll(
                listOf(
                    "-c:v", "libopenh264",
                    "-profile:v", "main",
                    "-b:v", "${targetVideoBitrate}k",
                    "-pix_fmt", "yuv420p",
                    "-fps_mode:v", "passthrough"
                )
            )
        }

        if (copyAudio) {

            // نقل الصوت كما هو دون إعادة ترميز.
            ffmpegArguments.addAll(
                listOf(
                    "-c:a", "copy"
                )
            )

        } else {

            // تحويل الصوت غير المتوافق إلى AAC-LC.
            ffmpegArguments.addAll(
                listOf(
                    "-c:a", "aac",
                    "-profile:a", "aac_low",
                    "-b:a", "128k"
                )
            )
        }

        ffmpegArguments.addAll(
            listOf(
                "-movflags", "+faststart",
                "-f", "mp4",
                mergedFile.absolutePath
            )
        )

        // -------------------------------------------------
        // 8. تنفيذ FFmpeg ومتابعة التقدم
        // -------------------------------------------------

        val mergeFinished = CountDownLatch(1)

        var completedFfmpegSession: FFmpegSession? = null

        val ffmpegStartedAt = SystemClock.elapsedRealtime()

        val runningFfmpegSession =
            FFmpegKit.executeWithArgumentsAsync(

                ffmpegArguments.toTypedArray(),

                { session ->

                    completedFfmpegSession = session

                    mergeFinished.countDown()

                    // لو تم إلغاء العملية ولم يصل Callback
                    // إلا بعد انتهاء مهلة الانتظار، ننظف المجلد هنا.
                    if (cleanupAfterFfmpeg.get()) {

                        try {
                            val deleted = workDir.deleteRecursively()
                            Log.i(
                                TAG,
                                "FFMPEG_TEMP_CLEANUP_AFTER_CANCEL=$deleted"
                            )
                        } finally {
                            DownloadWorkspaceRegistry.unregister(workDir)
                        }
                    }
                },

                { log ->

                    Log.i(
                        TAG,
                        "FFMPEG_TRANSCODE_LOG: ${log.message}"
                    )
                },

                { statistics ->

                    val processedFrames = statistics
                        .videoFrameNumber
                        .coerceIn(0, totalVideoFrames)

                    val ratio =
                        processedFrames.toFloat() /
                                totalVideoFrames.toFloat()

                    val progress = (82f + ratio * 11f)
                        .coerceIn(82f, 93f)

                    onProgress(
                        progress,
                        "جاري معالجة الفيديو: ${progress.toInt()}%"
                    )

                    Log.d(
                        TAG,
                        "FFMPEG_PROGRESS: " +
                                "$processedFrames / $totalVideoFrames"
                    )
                }
            )

        // المهلة تعتمد على مدة الفيديو، بحد أدنى 10 دقائق
        // وحد أقصى 3 ساعات.
        val timeoutMs = (
                (videoProbe.durationSeconds ?: 60.0) * 30_000.0
                ).coerceIn(
                600_000.0,
                10_800_000.0
            ).toLong()

        Log.i(
            TAG,
            "FFMPEG_TIMEOUT_MS=$timeoutMs"
        )

        try {

            val finishedInTime = mergeFinished.await(
                timeoutMs,
                TimeUnit.MILLISECONDS
            )

            if (!finishedInTime) {

                FFmpegKit.cancel(
                    runningFfmpegSession.getSessionId()
                )

                val stopped = mergeFinished.await(
                    30,
                    TimeUnit.SECONDS
                )

                if (!stopped) {

                    cleanupAfterFfmpeg.set(true)

                    if (mergeFinished.count == 0L) {
                        try {
                            workDir.deleteRecursively()
                        } finally {
                            DownloadWorkspaceRegistry.unregister(workDir)
                        }
                    }
                }

                throw IOException(
                    "تجاوزت معالجة الفيديو المهلة الزمنية المحددة. " +
                            "تم طلب إيقاف FFmpeg."
                )
            }

        } catch (error: InterruptedException) {

            runCatching {
                FFmpegKit.cancel(
                    runningFfmpegSession.getSessionId()
                )
            }

            val stopped = try {
                mergeFinished.await(
                    30,
                    TimeUnit.SECONDS
                )
            } catch (_: InterruptedException) {
                false
            }

            if (!stopped) {

                cleanupAfterFfmpeg.set(true)

                if (mergeFinished.count == 0L) {
                    try {
                        workDir.deleteRecursively()
                    } finally {
                        DownloadWorkspaceRegistry.unregister(workDir)
                    }
                }
            }

            Thread.currentThread().interrupt()

            throw IOException(
                "تم إيقاف عملية معالجة الفيديو.",
                error
            )
        }

        Log.i(
            TAG,
            "FFMPEG_TRANSCODE_TIME_MS=" +
                    (SystemClock.elapsedRealtime() - ffmpegStartedAt)
        )

        report.ffmpeg(
            ffmpegStartedAt,
            videoProbe.durationSeconds,
            totalVideoFrames,
            mergedFile.length()
        )
        if (!copyVideo) {
            DownloadRoutePlanner.recordVideoEncodeSample(
                context = context,
                sourceCodec = videoProbe.videoCodec,
                height = videoProbe.videoHeight,
                durationSeconds = videoProbe.durationSeconds,
                elapsedMs = SystemClock.elapsedRealtime() - ffmpegStartedAt
            )
        }

        val ffmpegSession = completedFfmpegSession
            ?: throw IOException(
                "لم يتم استلام نتيجة عملية FFmpeg."
            )

        Log.i(
            TAG,
            "FFMPEG_TRANSCODE_RETURN=${ffmpegSession.getReturnCode()}"
        )

        if (
            !ReturnCode.isSuccess(ffmpegSession.getReturnCode()) ||
            !mergedFile.exists() ||
            mergedFile.length() <= 0L
        ) {

            val details = ffmpegSession.getFailStackTrace()
                ?: ffmpegSession.output
                ?: "لم يُرجع FFmpeg تفاصيل إضافية."

            throw IOException(
                "فشل تحويل الفيديو: $details"
            )
        }

        // -------------------------------------------------
        // 9. التحقق من مواصفات المِلف النهائي
        // -------------------------------------------------

        val finalProbeStartedAt = SystemClock.elapsedRealtime()

        val outputProbe = logMediaProbe(
            file = mergedFile
        )

        report.step(
            "FINAL_PROBE_WITH_FULL_DECODE",
            finalProbeStartedAt,
            describeProbe(outputProbe)
        )

        if (
            !mergedFile.extension.equals("mp4", ignoreCase = true) ||
            !outputProbe.formatNames.contains("mp4", ignoreCase = true)
        ) {
            throw IOException(
                "الملف الناتج ليس حاوية MP4 صالحة."
            )
        }

        if (!isMp4CompatibleH264(outputProbe)) {
            throw IOException(
                "مواصفات الفيديو النهائي غير متوافقة. " +
                        "Codec=${outputProbe.videoCodec}, " +
                        "Profile=${outputProbe.videoProfile}, " +
                        "PixelFormat=${outputProbe.videoPixelFormat}."
            )
        }

        if (!isMp4CompatibleAacLc(outputProbe)) {
            throw IOException(
                "مواصفات الصوت النهائي غير متوافقة. " +
                        "Codec=${outputProbe.audioCodec}, " +
                        "Profile=${outputProbe.audioProfile}."
            )
        }

        val mergedVideoFrames = outputProbe.videoFrameCount ?: run {
            Log.w(
                TAG,
                "FINAL_FRAME_COUNT_METADATA_MISSING; using full frame scan"
            )

            val fallbackStartedAt = SystemClock.elapsedRealtime()
            val frames = countVideoFrames(mergedFile)

            report.step(
                "FINAL_FRAME_COUNT_FALLBACK",
                fallbackStartedAt,
                "frames=$frames"
            )

            frames
        }

        val minimumRequiredFrames = ceil(
            totalVideoFrames * 0.98
        ).toInt().coerceAtLeast(1)

        if (mergedVideoFrames < minimumRequiredFrames) {
            throw IOException(
                "الفيديو الناتج غير مكتمل. " +
                        "الفريمات الأصلية المقدرة: $totalVideoFrames، " +
                        "الفريمات الناتجة: $mergedVideoFrames."
            )
        }

        val sourceDuration = videoProbe.durationSeconds
        val outputDuration = outputProbe.durationSeconds

        if (
            sourceDuration != null &&
            outputDuration != null &&
            outputDuration < sourceDuration * 0.98
        ) {
            throw IOException(
                "مدة الفيديو الناتج أقصر من مدة المصدر بصورة غير مقبولة."
            )
        }

        Log.i(
            TAG,
            "FINAL_OUTPUT_VALIDATION=SUCCESS"
        )

        onProgress(
            94f,
            "اكتمل التحويل والتحقق من سلامة الفيديو..."
        )

        // -------------------------------------------------
        // 10. حفظ المِلف النهائي في Movies
        // -------------------------------------------------

        val saveStartedAt = SystemClock.elapsedRealtime()

        val savedUri = saveVideoToMovies(
            context,
            mergedFile,
            title
        ) { copyProgress ->

            val progress =
                94f + (copyProgress / 100f) * 6f

            onProgress(
                progress,
                "جاري حفظ الفيديو: ${copyProgress.toInt()}%"
            )
        }

        report.transfer(
            "SAVE_TO_MOVIES",
            saveStartedAt,
            0L,
            mergedFile.length()
        )
        DownloadRoutePlanner.recordSaveSample(
            context = context,
            bytes = mergedFile.length(),
            elapsedMs = SystemClock.elapsedRealtime() - saveStartedAt
        )

        onProgress(
            100f,
            "تم الحفظ بنجاح."
        )

        return savedUri

    } catch (error: Throwable) {

        if (videoDownloadAttempted && !videoDownloadSucceeded && !Thread.currentThread().isInterrupted) {
            DownloadRoutePlanner.recordAttemptFailure(
                context = context,
                sourceUrl = url,
                protocol = selectedFormat.protocol
            )
        }
        report.fail(error)

        throw error

    } finally {

        // لا تحذف الملفات إذا كان FFmpeg لا يزال يعمل بعد الإلغاء.
        // Callback الخاص به سيتولى التنظيف عند انتهاء العملية.
        if (!cleanupAfterFfmpeg.get()) {
            try {
                workDir.deleteRecursively()
            } finally {
                DownloadWorkspaceRegistry.unregister(workDir)
            }
        }

        report.finish(context)
    }
}



@Composable
private fun DownloadQueueTaskCard(
    task: DownloadQueueEntity,
    auroraText: Color,
    auroraMuted: Color,
    auroraAccent: Color,
    auroraAccentSoft: Color,
    auroraTrack: Color,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    isDragging: Boolean = false,
    dragHandleModifier: Modifier? = null
) {
    val active = task.status == DownloadTaskUiState.STATUS_QUEUED ||
            task.status == DownloadTaskUiState.STATUS_RUNNING ||
            task.status == DownloadTaskUiState.STATUS_WAITING_FOR_NETWORK

    val statusLabel = when (task.status) {
        DownloadTaskUiState.STATUS_QUEUED -> "في الانتظار"
        DownloadTaskUiState.STATUS_RUNNING -> "جاري التنزيل"
        DownloadTaskUiState.STATUS_WAITING_FOR_NETWORK -> "في انتظار الإنترنت"
        DownloadTaskUiState.STATUS_SUCCEEDED -> "اكتمل التنزيل"
        DownloadTaskUiState.STATUS_FAILED -> "فشل التنزيل"
        DownloadTaskUiState.STATUS_CANCELLED -> "تم الإلغاء"
        else -> task.status
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                val scale = if (isDragging) 1.025f else 1f
                scaleX = scale
                scaleY = scale
            }
            .background(
                color = Color(0x332E3A60),
                shape = RoundedCornerShape(15.dp)
            )
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = task.title.ifBlank { "فيديو بدون عنوان" },
                modifier = Modifier.weight(1f),
                color = auroraText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )

            if (dragHandleModifier != null) {
                Box(
                    modifier = dragHandleModifier.size(38.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "⠿",
                        color = auroraAccentSoft,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = statusLabel,
                color = auroraMuted,
                fontSize = 12.sp
            )

            Text(
                text = "${task.progress.toInt().coerceIn(0, 100)}%",
                color = auroraAccentSoft,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }

        FilmstripProgressBar(
            progress = (task.progress / 100f).coerceIn(0f, 1f),
            active = task.status == DownloadTaskUiState.STATUS_RUNNING,
            accent = auroraAccent,
            accentSoft = auroraAccentSoft,
            track = auroraTrack,
            modifier = Modifier.fillMaxWidth()
        )

        if (active) {
            Button(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF713D55)
                )
            ) {
                Text("إلغاء هذا التنزيل")
            }
        }
    }
}

@Composable
fun DownloaderScreen(engineReady: Boolean, context: Context) {
    val downloadUiState by DownloadUiStateStore.state.collectAsState()

    val queueRepository = remember {
        DownloadQueueRepository.getInstance(context)
    }

    val queueTasks by queueRepository.tasks.collectAsState(
        initial = emptyList()
    )

    val screenScope = rememberCoroutineScope()

    var queuedOrder by remember {
        mutableStateOf<List<DownloadQueueEntity>>(emptyList())
    }

    var isQueueReordering by remember {
        mutableStateOf(false)
    }

    var isSavingQueueOrder by remember {
        mutableStateOf(false)
    }

    var queueOrderBeforeDrag by remember {
        mutableStateOf<List<String>>(emptyList())
    }

    var queueOrderMessage by remember {
        mutableStateOf<String?>(null)
    }

    val queueLazyListState = rememberLazyListState()

    LaunchedEffect(queueTasks) {
        if (!isQueueReordering && !isSavingQueueOrder) {
            queuedOrder = queueTasks.filter {
                it.status == DownloadTaskUiState.STATUS_QUEUED
            }
        }
    }

    val reorderableQueueState = rememberReorderableLazyListState(
        queueLazyListState
    ) { from, to ->
        if (
            from.index in queuedOrder.indices &&
            to.index in queuedOrder.indices &&
            from.index != to.index
        ) {
            queuedOrder = queuedOrder.toMutableList().apply {
                add(to.index, removeAt(from.index))
            }
        }
    }

    var isStartingDownload by remember { mutableStateOf(false) }
    val isDownloading = downloadUiState.isRunning || isStartingDownload
    var downloadMessage by remember { mutableStateOf("") }
    var prayerMessage by remember { mutableStateOf("") }

    // Midnight Aurora palette stays local to this screen.
    val auroraCard = Color(0xE61A2341)
    val auroraAccent = Color(0xFF7164D8)
    val auroraAccentSoft = Color(0xFFA99CFF)
    val auroraText = Color(0xFFF1F2FF)
    val auroraMuted = Color(0xFFAAB7D7)
    val auroraBorder = Color(0x3DADB8FF)
    val auroraTrack = Color(0xFF303B61)
    val auroraDanger = Color(0xFFFF9AAB)
    val auroraPrayer = Color(0xFFE0D8FF)
    val auroraSelected = Color(0x664F569D)

    val selectedFormat = AnalysisStateStore.formats.firstOrNull {
        it.isAvailable && it.formatId == AnalysisStateStore.selectedFormatId
    }

    val analysisInteractionSource = remember { MutableInteractionSource() }
    val analysisPressed by analysisInteractionSource.collectIsPressedAsState()
    val analysisButtonScale by animateFloatAsState(
        targetValue = if (analysisPressed) 0.975f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "analysisButtonScale"
    )

    // Restored download button interaction and animation.
    val downloadInteractionSource = remember { MutableInteractionSource() }
    val downloadPressed by downloadInteractionSource.collectIsPressedAsState()
    val downloadButtonScale by animateFloatAsState(
        targetValue = if (downloadPressed) 0.975f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "downloadButtonScale"
    )

    // Preserve the exact prayer phrases and the original 3-second interval.
    LaunchedEffect(isDownloading) {
        if (isDownloading) {
            val phrases = listOf(
                "اللهم صل وسلم وبارك على نبينا محمد",
                "لا حول ولا قوة إلا بالله",
                "أستغفر الله الذي لا إله إلا هو الحي القيوم وأتوب إليه",
                "لا إله إلا الله وحده لا شريك له",
                "له الملك وله الحمد وهو على كل شيء قدير",
                "الحمد لله وسبحان الله والله أكبر ولا إله إلا الله"
            )

            var phraseIndex = 0
            while (true) {
                prayerMessage = phrases[phraseIndex]
                phraseIndex = (phraseIndex + 1) % phrases.size
                delay(3.seconds)
            }
        }
    }

    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    // Restored foreground-service launch and notification permission flow.
    var pendingDownloadIntent by remember { mutableStateOf<Intent?>(null) }

    val launchDownloadService: (Intent?) -> Unit = { request ->
        pendingDownloadIntent = null

        if (request == null) {
            isStartingDownload = false
        } else {
            try {
                ContextCompat.startForegroundService(context, request)
            } catch (error: Exception) {
                DownloadUiStateStore.failToStart(
                    "تعذر بدء التنزيل: ${errorDetails(error)}"
                )

                downloadMessage = "تعذر بدء التنزيل: ${errorDetails(error)}"

                Log.e(TAG, "Could not start download foreground service", error)
            } finally {
                isStartingDownload = false
            }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Denying notification permission does not prevent the foreground service.
        launchDownloadService(pendingDownloadIntent)
    }

    val lifecycleOwner = context as? LifecycleOwner
    var activityResumed by remember {
        mutableStateOf(
            lifecycleOwner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED)
                ?: true
        )
    }

    DisposableEffect(lifecycleOwner) {
        if (lifecycleOwner == null) {
            activityResumed = true
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { owner, _ ->
                activityResumed = owner.lifecycle.currentState
                    .isAtLeast(Lifecycle.State.RESUMED)
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            activityResumed = lifecycleOwner.lifecycle.currentState
                .isAtLeast(Lifecycle.State.RESUMED)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
    }

    LaunchedEffect(queueRepository, activityResumed) {
        if (!activityResumed) return@LaunchedEffect

        try {
            val storedTasks = queueRepository.getActiveTasks()

            val hasTasksToResume = storedTasks.any { task ->
                task.status == DownloadTaskUiState.STATUS_QUEUED ||
                        task.status == DownloadTaskUiState.STATUS_RUNNING ||
                        task.status == DownloadTaskUiState.STATUS_WAITING_FOR_NETWORK
            }

            if (hasTasksToResume) {
                ContextCompat.startForegroundService(
                    context,
                    DownloadForegroundService.createResumeIntent(context)
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "Could not resume download queue", error)
        }
    }

    var screenEntered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        screenEntered = true
    }

    LaunchedEffect(downloadUiState.finishedAt) {
        if (downloadUiState.finishedAt > 0L) {
            downloadMessage = downloadUiState.terminalMessage
        }
    }

    LaunchedEffect(downloadUiState.savedUri, activityResumed) {
        val savedUriString = downloadUiState.savedUri ?: return@LaunchedEffect
        if (!activityResumed) return@LaunchedEffect

        try {
            val savedUri = savedUriString.toUri()
            val mime = context.contentResolver.getType(savedUri) ?: "video/*"
            val playIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(savedUri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(playIntent, "اختار مشغل الفيديو")
            )
            DownloadUiStateStore.markSavedUriHandled(savedUriString)
        } catch (openError: Exception) {
            Log.w(TAG, "No video player available", openError)
            DownloadUiStateStore.markOpenFailed(
                savedUriString,
                "تم حفظ الفيديو، لكن لم يتم العثور على مشغل مناسب."
            )
        }
    }

    Scaffold(containerColor = Color.Transparent) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            AuroraAnimatedBackground(
                modifier = Modifier.fillMaxSize(),
                isActive = activityResumed
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp, vertical = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    // Header: keep the existing arrow and title unchanged.
                    AnimatedVisibility(
                        visible = screenEntered,
                        enter = fadeIn(tween(650)) + slideInVertically(
                            animationSpec = tween(
                                650,
                                easing = FastOutSlowInEasing
                            ),
                            initialOffsetY = { it / 9 }
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Surface(
                                color = Color(0x664D5EB8),
                                shape = RoundedCornerShape(20.dp),
                                border = BorderStroke(1.dp, auroraBorder),
                                tonalElevation = 0.dp
                            ) {
                                Box(
                                    modifier = Modifier.size(58.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "↓",
                                        color = Color.White,
                                        fontSize = 34.sp,
                                        fontWeight = FontWeight.Light
                                    )
                                }
                            }

                            Column(
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    text = "Video Downloader",
                                    fontSize = 23.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = auroraText
                                )
                                Text(
                                    text = "نزّل فيديوهاتك بسهولة",
                                    color = auroraMuted,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }

                    // Link card.
                    AnimatedVisibility(
                        visible = screenEntered,
                        enter = fadeIn(tween(650, delayMillis = 90)) +
                                slideInVertically(
                                    animationSpec = tween(
                                        650,
                                        delayMillis = 90,
                                        easing = FastOutSlowInEasing
                                    ),
                                    initialOffsetY = { it / 12 }
                                )
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = auroraCard
                            ),
                            border = BorderStroke(1.dp, auroraBorder),
                            elevation = CardDefaults.cardElevation(
                                defaultElevation = 1.dp
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                                ) {
                                    Text(
                                        text = "↗",
                                        color = auroraAccentSoft,
                                        fontSize = 21.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "رابط الفيديو",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = auroraText
                                    )
                                }

                                OutlinedTextField(
                                    value = AnalysisStateStore.videoUrl,
                                    onValueChange = { value ->
                                        AnalysisStateStore.videoUrl = value
                                        AnalysisStateStore.formats = emptyList()
                                        AnalysisStateStore.audioFormats = emptyList()
                                        AnalysisStateStore.selectedFormatId = null
                                        AnalysisStateStore.videoTitle = ""
                                        AnalysisStateStore.statusMessage = ""
                                        downloadMessage = ""
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = {
                                        Text(
                                            "https://example.com/video",
                                            color = auroraMuted
                                        )
                                    },
                                    label = { Text("ضع الرابط هنا") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(16.dp),
                                    enabled = !AnalysisStateStore.isAnalyzing &&
                                            !isStartingDownload,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = auroraText,
                                        unfocusedTextColor = auroraText,
                                        cursorColor = auroraAccentSoft,
                                        focusedContainerColor = Color(0x33101830),
                                        unfocusedContainerColor = Color(0x33101830),
                                        focusedBorderColor = auroraAccentSoft,
                                        unfocusedBorderColor = auroraBorder,
                                        focusedLabelColor = auroraAccentSoft,
                                        unfocusedLabelColor = auroraMuted,
                                        disabledTextColor = auroraMuted,
                                        disabledBorderColor = auroraBorder
                                    )
                                )

                                Button(
                                    onClick = {
                                        val input = AnalysisStateStore.videoUrl.trim()
                                        val uri = runCatching {
                                            input.toUri()
                                        }.getOrNull()

                                        val validUrl = uri != null &&
                                                (uri.scheme == "https" ||
                                                        uri.scheme == "http") &&
                                                !uri.host.isNullOrBlank()

                                        when {
                                            !validUrl -> {
                                                AnalysisStateStore.statusMessage =
                                                    "أدخل رابط HTTP أو HTTPS صحيحًا."
                                                AnalysisStateStore.statusIsError = true
                                            }
                                            !hasValidatedInternet(context) -> {
                                                AnalysisStateStore.statusMessage =
                                                    "لا يوجد اتصال بالإنترنت. اتصل بالشبكة ثم حاول مجددًا."
                                                AnalysisStateStore.statusIsError = true
                                            }
                                            !engineReady -> {
                                                AnalysisStateStore.statusMessage =
                                                    "محرك yt-dlp لم يبدأ بنجاح."
                                                AnalysisStateStore.statusIsError = true
                                            }

                                            else -> {
                                                AnalysisStateStore.isAnalyzing = true
                                                AnalysisStateStore.formats = emptyList()
                                                AnalysisStateStore.audioFormats = emptyList()
                                                AnalysisStateStore.selectedFormatId = null
                                                AnalysisStateStore.videoTitle = ""
                                                downloadMessage = ""
                                                AnalysisStateStore.statusMessage =
                                                    "جارٍ تحليل الرابط واستخراج الجودات..."
                                                AnalysisStateStore.statusIsError = false

                                                AnalysisStateStore.executor.execute {
                                                    try {
                                                        val result = extractVideoInfo(input, context)
                                                        mainHandler.post {
                                                            AnalysisStateStore.isAnalyzing = false
                                                            AnalysisStateStore.videoTitle = result.title
                                                            AnalysisStateStore.formats = result.formats
                                                            AnalysisStateStore.audioFormats = result.audioFormats

                                                            val availableOptions =
                                                                result.formats.filter {
                                                                    it.isAvailable
                                                                }

                                                            AnalysisStateStore.selectedFormatId =
                                                                availableOptions.firstOrNull()?.formatId

                                                            AnalysisStateStore.statusMessage =
                                                                if (availableOptions.isEmpty()) {
                                                                    "لم تتوفر مستويات جودة مناسبة لهذا الرابط."
                                                                } else {
                                                                    "تم تجهيز اختيارات الجودة المتاحة."
                                                                }

                                                            AnalysisStateStore.statusIsError =
                                                                availableOptions.isEmpty()
                                                        }
                                                    } catch (error: Exception) {
                                                        Log.e(
                                                            TAG,
                                                            "Video analysis failed",
                                                            error
                                                        )
                                                        mainHandler.post {
                                                            AnalysisStateStore.isAnalyzing = false
                                                            AnalysisStateStore.statusMessage =
                                                                "تعذر تحليل الرابط: ${errorDetails(error)}"
                                                            AnalysisStateStore.statusIsError = true
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    enabled = AnalysisStateStore.videoUrl.isNotBlank() &&
                                            !AnalysisStateStore.isAnalyzing &&
                                            !isStartingDownload,
                                    interactionSource = analysisInteractionSource,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp)
                                        .graphicsLayer {
                                            scaleX = analysisButtonScale
                                            scaleY = analysisButtonScale
                                        },
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = auroraAccent,
                                        contentColor = Color.White,
                                        disabledContainerColor = Color(0xFF414267),
                                        disabledContentColor = Color(0xFFB5B5CC)
                                    )
                                ) {
                                    Text(
                                        text = if (AnalysisStateStore.isAnalyzing) {
                                            "جاري التحليل..."
                                        } else {
                                            "تحليل الرابط  ↗"
                                        },
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                AnimatedVisibility(
                                    visible = AnalysisStateStore.isAnalyzing,
                                    enter = fadeIn(tween(220)) +
                                            expandVertically(
                                                expandFrom = Alignment.Top,
                                                animationSpec = tween(260)
                                            ),
                                    exit = fadeOut(tween(160)) +
                                            shrinkVertically(
                                                shrinkTowards = Alignment.Top,
                                                animationSpec = tween(200)
                                            )
                                ) {
                                    AnalysisAnimation(
                                        accent = auroraAccentSoft,
                                        muted = auroraMuted,
                                        track = auroraTrack,
                                        isActive = activityResumed
                                    )
                                }

                                AnimatedVisibility(
                                    visible = AnalysisStateStore.statusMessage.isNotBlank(),
                                    enter = fadeIn(tween(240)) +
                                            expandVertically(
                                                expandFrom = Alignment.Top,
                                                animationSpec = tween(280)
                                            ),
                                    exit = fadeOut(tween(160)) +
                                            shrinkVertically(
                                                shrinkTowards = Alignment.Top,
                                                animationSpec = tween(200)
                                            )
                                ) {
                                    AnimatedContent(
                                        targetState = AnalysisStateStore.statusMessage,
                                        transitionSpec = {
                                            (fadeIn(tween(220)) +
                                                    slideInVertically(
                                                        animationSpec = tween(220),
                                                        initialOffsetY = { it / 5 }
                                                    )) togetherWith
                                                    (fadeOut(tween(160)) +
                                                            slideOutVertically(
                                                                animationSpec = tween(160),
                                                                targetOffsetY = { -it / 6 }
                                                            ))
                                        },
                                        label = "analysisStatusText"
                                    ) { statusText ->
                                        Text(
                                            text = statusText,
                                            color = if (AnalysisStateStore.statusIsError) {
                                                auroraDanger
                                            } else {
                                                auroraAccentSoft
                                            },
                                            fontSize = 13.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Results section.
                    AnimatedVisibility(
                        visible = AnalysisStateStore.formats.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                        enter = fadeIn(tween(420, delayMillis = 70)) +
                                expandVertically(
                                    expandFrom = Alignment.Top,
                                    animationSpec = tween(
                                        480,
                                        delayMillis = 70,
                                        easing = FastOutSlowInEasing
                                    )
                                ) +
                                slideInVertically(
                                    animationSpec = tween(
                                        480,
                                        delayMillis = 70,
                                        easing = FastOutSlowInEasing
                                    ),
                                    initialOffsetY = { it / 14 }
                                ),
                        exit = fadeOut(tween(180)) +
                                shrinkVertically(
                                    shrinkTowards = Alignment.Top,
                                    animationSpec = tween(220)
                                )
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            // Available quality options.
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(24.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = auroraCard
                                ),
                                border = BorderStroke(1.dp, auroraBorder),
                                elevation = CardDefaults.cardElevation(
                                    defaultElevation = 1.dp
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                                    ) {
                                        Text(
                                            text = "✧",
                                            color = auroraAccentSoft,
                                            fontSize = 21.sp
                                        )
                                        Text(
                                            text = "الجودات المتاحة",
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = auroraText
                                        )
                                    }

                                    Text(
                                        text = AnalysisStateStore.videoTitle,
                                        color = auroraMuted,
                                        fontSize = 13.sp
                                    )

                                    AnalysisStateStore.formats.forEach { item ->
                                        key(item.formatId) {
                                            val sizeLabel = item.fileSize?.let {
                                                val prefix =
                                                    if (item.sizeIsEstimate) "تقريبًا " else ""
                                                prefix + readableSize(it)
                                            } ?: "الحجم غير متاح"

                                            val isSelected = item.isAvailable &&
                                                    AnalysisStateStore.selectedFormatId ==
                                                    item.formatId

                                            val qualityCardColor by animateColorAsState(
                                                targetValue = if (isSelected) {
                                                    auroraSelected
                                                } else {
                                                    Color(0x24101830)
                                                },
                                                animationSpec = tween(
                                                    durationMillis = 340,
                                                    easing = FastOutSlowInEasing
                                                ),
                                                label = "qualityCardColor"
                                            )

                                            val qualityBorderColor by animateColorAsState(
                                                targetValue = if (isSelected) {
                                                    auroraAccentSoft
                                                } else {
                                                    auroraBorder
                                                },
                                                animationSpec = tween(
                                                    durationMillis = 340,
                                                    easing = FastOutSlowInEasing
                                                ),
                                                label = "qualityBorderColor"
                                            )

                                            val qualityScale by animateFloatAsState(
                                                targetValue = if (isSelected) 1f else 0.985f,
                                                animationSpec = spring(
                                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                                    stiffness = Spring.StiffnessMedium
                                                ),
                                                label = "qualityCardScale"
                                            )

                                            Surface(
                                                onClick = {
                                                    if (item.isAvailable) {
                                                        AnalysisStateStore.selectedFormatId =
                                                            item.formatId
                                                        downloadMessage = ""
                                                    }
                                                },
                                                enabled = !AnalysisStateStore.isAnalyzing &&
                                                        !isStartingDownload &&
                                                        item.isAvailable,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .graphicsLayer {
                                                        scaleX = qualityScale
                                                        scaleY = qualityScale
                                                    }
                                                    .animateContentSize(),
                                                shape = RoundedCornerShape(16.dp),
                                                color = qualityCardColor,
                                                border = BorderStroke(
                                                    1.dp,
                                                    qualityBorderColor
                                                ),
                                                tonalElevation = 0.dp
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(
                                                            horizontal = 9.dp,
                                                            vertical = 8.dp
                                                        ),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    RadioButton(
                                                        selected = isSelected,
                                                        onClick = {
                                                            if (
                                                                !AnalysisStateStore.isAnalyzing &&
                                                                !isStartingDownload &&
                                                                item.isAvailable
                                                            ) {
                                                                AnalysisStateStore.selectedFormatId =
                                                                    item.formatId
                                                                downloadMessage = ""
                                                            }
                                                        },
                                                        enabled = !AnalysisStateStore.isAnalyzing &&
                                                                !isStartingDownload &&
                                                                item.isAvailable,
                                                        colors = RadioButtonDefaults.colors(
                                                            selectedColor = auroraAccentSoft,
                                                            unselectedColor = auroraMuted,
                                                            disabledSelectedColor =
                                                                auroraAccentSoft.copy(alpha = 0.55f),
                                                            disabledUnselectedColor =
                                                                auroraMuted.copy(alpha = 0.45f)
                                                        )
                                                    )

                                                    Column(
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .padding(start = 4.dp),
                                                        verticalArrangement = Arrangement.spacedBy(3.dp)
                                                    ) {
                                                        Text(
                                                            text = item.qualityTier.label,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = if (item.isAvailable) {
                                                                auroraText
                                                            } else {
                                                                auroraMuted
                                                            }
                                                        )

                                                        Text(
                                                            text = if (item.isAvailable) {
                                                                "الحجم المتوقع: $sizeLabel"
                                                            } else {
                                                                "غير متاحة لهذا الرابط"
                                                            },
                                                            color = auroraMuted,
                                                            fontSize = 12.sp
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Restored original download card.
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateContentSize(),
                                shape = RoundedCornerShape(24.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = auroraCard
                                ),
                                border = BorderStroke(1.dp, auroraBorder),
                                elevation = CardDefaults.cardElevation(
                                    defaultElevation = 1.dp
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(13.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                                    ) {
                                        Text(
                                            text = "↓",
                                            color = auroraAccentSoft,
                                            fontSize = 22.sp
                                        )
                                        Text(
                                            text = "تحميل الفيديو",
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = auroraText
                                        )
                                    }

                                    Text(
                                        text = selectedFormat?.let {
                                            "الجودة المختارة: ${it.qualityTier.label}"
                                        } ?: "اختار جودة متاحة أولًا.",
                                        color = auroraMuted,
                                        fontSize = 14.sp
                                    )

                                    Button(
                                        onClick = {
                                            if (isStartingDownload) return@Button

                                            if (!engineReady) {
                                                downloadMessage =
                                                    "تعذر الإضافة: محرك yt-dlp غير جاهز."
                                                return@Button
                                            }

                                            val chosen = selectedFormat ?: return@Button

                                            isStartingDownload = true

                                            screenScope.launch {
                                                try {
                                                    val existingTasks =
                                                        queueRepository.getActiveTasks()

                                                    val shouldOpenWhenFinished =
                                                        existingTasks.none { task ->
                                                            task.status ==
                                                                    DownloadTaskUiState.STATUS_QUEUED ||
                                                                    task.status ==
                                                                    DownloadTaskUiState.STATUS_RUNNING ||
                                                                    task.status ==
                                                                    DownloadTaskUiState.STATUS_WAITING_FOR_NETWORK
                                                        }

                                                    queueRepository.enqueue(
                                                        url = AnalysisStateStore.videoUrl.trim(),
                                                        title = AnalysisStateStore.videoTitle,
                                                        selectedFormat = chosen,
                                                        audioFormats = AnalysisStateStore.audioFormats.toList(),
                                                        openOnFinish = shouldOpenWhenFinished
                                                    )

                                                    downloadMessage =
                                                        "تمت إضافة الفيديو إلى قائمة التنزيلات."

                                                    val requestIntent =
                                                        DownloadForegroundService.createResumeIntent(
                                                            context
                                                        )

                                                    if (
                                                        Build.VERSION.SDK_INT >=
                                                        Build.VERSION_CODES.TIRAMISU &&
                                                        ContextCompat.checkSelfPermission(
                                                            context,
                                                            Manifest.permission.POST_NOTIFICATIONS
                                                        ) != PackageManager.PERMISSION_GRANTED
                                                    ) {
                                                        pendingDownloadIntent = requestIntent
                                                        notificationPermissionLauncher.launch(
                                                            Manifest.permission.POST_NOTIFICATIONS
                                                        )
                                                    } else {
                                                        launchDownloadService(requestIntent)
                                                    }

                                                } catch (cancelled: CancellationException) {
                                                    pendingDownloadIntent = null
                                                    isStartingDownload = false
                                                    throw cancelled

                                                } catch (error: Exception) {
                                                    pendingDownloadIntent = null
                                                    isStartingDownload = false
                                                    downloadMessage =
                                                        "فشلت إضافة الفيديو: ${errorDetails(error)}"
                                                    Log.e(
                                                        TAG,
                                                        "Could not enqueue download",
                                                        error
                                                    )
                                                }
                                            }
                                        },
                                        enabled = engineReady &&
                                                selectedFormat != null &&
                                                !isStartingDownload &&
                                                !AnalysisStateStore.isAnalyzing,
                                        interactionSource = downloadInteractionSource,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(54.dp)
                                            .graphicsLayer {
                                                scaleX = downloadButtonScale
                                                scaleY = downloadButtonScale
                                            },
                                        shape = RoundedCornerShape(16.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = auroraAccent,
                                            contentColor = Color.White,
                                            disabledContainerColor = Color(0xFF414267),
                                            disabledContentColor = Color(0xFFB5B5CC)
                                        )
                                    ) {
                                        Text(
                                            text = "إضافة إلى قائمة التنزيلات  +",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }

                                    AnimatedVisibility(
                                        visible = !isDownloading &&
                                                downloadMessage.isNotBlank(),
                                        enter = fadeIn(tween(260)) +
                                                expandVertically(
                                                    expandFrom = Alignment.Top,
                                                    animationSpec = tween(300)
                                                ),
                                        exit = fadeOut(tween(180)) +
                                                shrinkVertically(
                                                    shrinkTowards = Alignment.Top,
                                                    animationSpec = tween(220)
                                                )
                                    ) {
                                        Text(
                                            text = downloadMessage,
                                            color = if (
                                                downloadMessage.startsWith("فشل") ||
                                                downloadMessage.startsWith("لا توجد") ||
                                                downloadMessage.startsWith("تعذر")
                                            ) {
                                                auroraDanger
                                            } else {
                                                auroraAccentSoft
                                            },
                                            fontSize = 13.sp
                                        )
                                    }
                                }
                            }

                            // Floating prayer stays separate and keeps the original rotation.
                            AnimatedVisibility(
                                visible = isDownloading && prayerMessage.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                                enter = fadeIn(tween(360)) +
                                        slideInVertically(
                                            animationSpec = tween(
                                                420,
                                                easing = FastOutSlowInEasing
                                            ),
                                            initialOffsetY = { it / 5 }
                                        ),
                                exit = fadeOut(tween(220)) +
                                        slideOutVertically(
                                            animationSpec = tween(260),
                                            targetOffsetY = { -it / 6 }
                                        )
                            ) {
                                AnimatedContent(
                                    targetState = prayerMessage,
                                    transitionSpec = {
                                        (fadeIn(tween(420)) +
                                                slideInVertically(
                                                    animationSpec = tween(
                                                        420,
                                                        easing = FastOutSlowInEasing
                                                    ),
                                                    initialOffsetY = { it / 4 }
                                                )) togetherWith
                                                (fadeOut(tween(300)) +
                                                        slideOutVertically(
                                                            animationSpec = tween(300),
                                                            targetOffsetY = { -it / 5 }
                                                        ))
                                    },
                                    label = "floatingPrayerTransition"
                                ) { phrase ->
                                    Text(
                                        text = phrase,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(
                                                horizontal = 12.dp,
                                                vertical = 2.dp
                                            ),
                                        color = auroraPrayer,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }

                    // The single download queue card belongs here, after Results.
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = auroraCard
                        ),
                        border = BorderStroke(1.dp, auroraBorder)
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "قائمة التنزيلات (${queueTasks.size})",
                                    color = auroraText,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                val hasActiveTasks = queueTasks.any {
                                    it.status == DownloadTaskUiState.STATUS_QUEUED ||
                                            it.status == DownloadTaskUiState.STATUS_RUNNING ||
                                            it.status == DownloadTaskUiState.STATUS_WAITING_FOR_NETWORK
                                }

                                if (hasActiveTasks) {
                                    Button(
                                        onClick = {
                                            context.startService(
                                                DownloadForegroundService.createCancelAllIntent(
                                                    context
                                                )
                                            )
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFF713D55)
                                        )
                                    ) {
                                        Text("إلغاء الكل")
                                    }
                                }
                            }

                            if (queueTasks.isEmpty()) {
                                Text(
                                    text = "لا توجد تنزيلات في القائمة حاليًا.",
                                    color = auroraMuted,
                                    fontSize = 13.sp
                                )
                            } else {
                                val fixedQueueTasks = queueTasks.filter {
                                    it.status == DownloadTaskUiState.STATUS_RUNNING ||
                                            it.status == DownloadTaskUiState.STATUS_WAITING_FOR_NETWORK
                                }

                                // Keep the currently processing tasks fixed.
                                fixedQueueTasks.forEach { task ->
                                    DownloadQueueTaskCard(
                                        task = task,
                                        auroraText = auroraText,
                                        auroraMuted = auroraMuted,
                                        auroraAccent = auroraAccent,
                                        auroraAccentSoft = auroraAccentSoft,
                                        auroraTrack = auroraTrack,
                                        onCancel = {
                                            context.startService(
                                                DownloadForegroundService.createCancelIntent(
                                                    context,
                                                    task.id
                                                )
                                            )
                                        }
                                    )
                                }

                                if (queuedOrder.isNotEmpty()) {
                                    Text(
                                        text = "التنزيلات المنتظرة",
                                        color = auroraAccentSoft,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )

                                    Text(
                                        text = "اسحب علامة الترتيب لتحريك التنزيل إلى المكان الذي تريده.",
                                        color = auroraMuted,
                                        fontSize = 12.sp
                                    )

                                    LazyColumn(
                                        state = queueLazyListState,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 420.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        items(
                                            items = queuedOrder,
                                            key = { task -> task.id }
                                        ) { task ->

                                            ReorderableItem(
                                                reorderableQueueState,
                                                key = task.id
                                            ) { isDragging ->

                                                val handleModifier: Modifier? =
                                                    if (isSavingQueueOrder) {
                                                        null
                                                    } else {
                                                        Modifier.draggableHandle(
                                                            onDragStarted = {
                                                                isQueueReordering = true
                                                                queueOrderBeforeDrag =
                                                                    queuedOrder.map { it.id }
                                                                queueOrderMessage = null
                                                            },
                                                            onDragStopped = {
                                                                val orderToSave =
                                                                    queuedOrder.map { it.id }

                                                                if (
                                                                    orderToSave ==
                                                                    queueOrderBeforeDrag
                                                                ) {
                                                                    isQueueReordering = false
                                                                } else {
                                                                    isSavingQueueOrder = true

                                                                    screenScope.launch {
                                                                        try {
                                                                            val saved =
                                                                                queueRepository.reorderQueuedTasks(
                                                                                    orderToSave
                                                                                )

                                                                            val latestTasks =
                                                                                queueRepository.getActiveTasks()

                                                                            queuedOrder =
                                                                                latestTasks.filter {
                                                                                    it.status ==
                                                                                            DownloadTaskUiState.STATUS_QUEUED
                                                                                }

                                                                            queueOrderMessage =
                                                                                if (saved) {
                                                                                    "تم حفظ الترتيب الجديد."
                                                                                } else {
                                                                                    "تغيّرت قائمة الانتظار أثناء السحب؛ تم تحديث القائمة."
                                                                                }

                                                                        } catch (
                                                                            cancelled: CancellationException
                                                                        ) {
                                                                            throw cancelled

                                                                        } catch (error: Exception) {
                                                                            Log.e(
                                                                                TAG,
                                                                                "Could not save download queue order",
                                                                                error
                                                                            )

                                                                            val latestTasks =
                                                                                runCatching {
                                                                                    queueRepository.getActiveTasks()
                                                                                }.getOrNull()

                                                                            if (latestTasks != null) {
                                                                                queuedOrder =
                                                                                    latestTasks.filter {
                                                                                        it.status ==
                                                                                                DownloadTaskUiState.STATUS_QUEUED
                                                                                    }
                                                                            }

                                                                            queueOrderMessage =
                                                                                "تعذر حفظ ترتيب التنزيلات: ${errorDetails(error)}"

                                                                        } finally {
                                                                            isQueueReordering = false
                                                                            isSavingQueueOrder = false
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        )
                                                    }

                                                DownloadQueueTaskCard(
                                                    task = task,
                                                    auroraText = auroraText,
                                                    auroraMuted = auroraMuted,
                                                    auroraAccent = auroraAccent,
                                                    auroraAccentSoft = auroraAccentSoft,
                                                    auroraTrack = auroraTrack,
                                                    isDragging = isDragging,
                                                    dragHandleModifier = handleModifier,
                                                    onCancel = {
                                                        context.startService(
                                                            DownloadForegroundService.createCancelIntent(
                                                                context,
                                                                task.id
                                                            )
                                                        )
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                if (isSavingQueueOrder) {
                                    Text(
                                        text = "جارٍ حفظ الترتيب...",
                                        color = auroraMuted,
                                        fontSize = 12.sp
                                    )
                                }

                                queueOrderMessage?.let { message ->
                                    Text(
                                        text = message,
                                        color = if (message.startsWith("تعذر")) {
                                            auroraDanger
                                        } else {
                                            auroraAccentSoft
                                        },
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = !engineReady,
                        enter = fadeIn(tween(260)) +
                                expandVertically(
                                    expandFrom = Alignment.Top,
                                    animationSpec = tween(300)
                                ),
                        exit = fadeOut(tween(180)) +
                                shrinkVertically(
                                    shrinkTowards = Alignment.Top,
                                    animationSpec = tween(220)
                                )
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0x443E263D),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Color(0x66FF9AAB))
                        ) {
                            Text(
                                text = "تعذر تهيئة محرك yt-dlp",
                                modifier = Modifier.padding(14.dp),
                                color = auroraDanger,
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = screenEntered,
                        enter = fadeIn(tween(850, delayMillis = 220)) +
                                slideInVertically(
                                    animationSpec = tween(
                                        850,
                                        delayMillis = 220,
                                        easing = FastOutSlowInEasing
                                    ),
                                    initialOffsetY = { it / 16 }
                                )
                    ) {
                        Text(
                            text = "استخدم روابط محتوى تملك حق تنزيله أو يسمح مصدره بذلك.",
                            color = auroraMuted,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = 8.dp,
                                    vertical = 4.dp
                                )
                        )
                    }
                }
            }
        }
    }
}


/** Two calm Aurora waves only. Motion is deliberately slow and low-amplitude. */
@Composable
internal fun AuroraAnimatedBackground(
    modifier: Modifier = Modifier,
    isActive: Boolean
) {
    val motion = remember { Animatable(0f) }

    LaunchedEffect(isActive) {
        if (isActive) {
            while (true) {
                motion.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = 26000,
                        easing = LinearEasing
                    )
                )
                motion.snapTo(0f)
            }
        } else {
            motion.snapTo(0f)
        }
    }

    Box(
        modifier = modifier
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF0D1633),
                        Color(0xFF171D43),
                        Color(0xFF251B4A)
                    )
                )
            )
            .drawBehind {
                val phase = motion.value * (2f * Math.PI.toFloat())
                val driftA = kotlin.math.sin(phase)
                val driftB = kotlin.math.sin(phase + 2.1f)
                val w = size.width
                val h = size.height

                // Broad, restrained blue glow.
                val blueCenter = Offset(
                    w * (0.84f - 0.018f * driftA),
                    h * (0.08f + 0.009f * driftB)
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x665F6DDA), Color.Transparent),
                        center = blueCenter,
                        radius = w * 0.82f
                    ),
                    radius = w * 0.82f,
                    center = blueCenter
                )

                // Soft violet glow, with no extra wave layers.
                val violetCenter = Offset(
                    w * (0.06f + 0.020f * driftB),
                    h * (0.58f + 0.012f * driftA)
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x554A83C9), Color.Transparent),
                        center = violetCenter,
                        radius = w * 0.76f
                    ),
                    radius = w * 0.76f,
                    center = violetCenter
                )

                // Upper wave.
                val upperWave = Path().apply {
                    moveTo(0f, h * (0.08f + 0.008f * driftA))
                    cubicTo(
                        w * 0.28f, h * (0.01f + 0.009f * driftB),
                        w * 0.66f, h * (0.22f + 0.010f * driftA),
                        w, h * (0.09f + 0.008f * driftB)
                    )
                    lineTo(w, h * (0.34f + 0.008f * driftA))
                    cubicTo(
                        w * 0.70f, h * (0.43f + 0.008f * driftB),
                        w * 0.30f, h * (0.20f + 0.009f * driftA),
                        0f, h * (0.35f + 0.008f * driftB)
                    )
                    close()
                }
                drawPath(
                    path = upperWave,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0x224E75C9),
                            Color(0x447B59C9),
                            Color(0x114E75C9)
                        ),
                        start = Offset(0f, h * 0.08f),
                        end = Offset(w, h * 0.35f)
                    )
                )

                // Lower wave. No middle wave or extra ribbons.
                val lowerWave = Path().apply {
                    moveTo(0f, h * (0.78f + 0.008f * driftB))
                    cubicTo(
                        w * 0.30f, h * (0.65f + 0.008f * driftA),
                        w * 0.66f, h * (0.94f + 0.009f * driftB),
                        w, h * (0.77f + 0.008f * driftA)
                    )
                    lineTo(w, h)
                    lineTo(0f, h)
                    close()
                }
                drawPath(
                    path = lowerWave,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0x114E75C9),
                            Color(0x337B59C9),
                            Color(0x224E75C9)
                        ),
                        start = Offset(0f, h * 0.70f),
                        end = Offset(w, h)
                    )
                )
            }
    )
}

/**
 * Filmstrip Flow: this is the actual progress bar, not a decorative route.
 * The filled width is driven only by the smoothed foreground-service percentage.
 * Film perforations and frame dividers move inside the filled region; there are
 * no endpoint icons, no curved path, and no simulated progress beyond the service value.
 */

@Composable
private fun FilmstripProgressBar(
    progress: Float,
    active: Boolean,
    accent: Color,
    accentSoft: Color,
    track: Color,
    modifier: Modifier = Modifier
) {
    val phase = remember { Animatable(0f) }
    val safeProgress = progress.coerceIn(0f, 1f)
    val isComplete = safeProgress >= 0.999f

    LaunchedEffect(active, isComplete) {
        if (active && !isComplete) {
            while (true) {
                phase.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = 1750,
                        easing = LinearEasing
                    )
                )
                phase.snapTo(0f)
            }
        } else {
            phase.snapTo(0f)
        }
    }

    Canvas(
        modifier = modifier.height(22.dp)
    ) {
        val width = size.width
        val height = size.height
        val radius = height / 2f
        val fillWidth = width * safeProgress
        val fillLeft = width - fillWidth

        // Full track.
        drawRoundRect(
            color = track,
            size = Size(width, height),
            cornerRadius = CornerRadius(radius, radius)
        )

        if (fillWidth <= 0.5f) return@Canvas

        // Exact service progress: fill grows from RIGHT to LEFT.
        drawRoundRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    accentSoft.copy(alpha = 0.98f),
                    accent,
                    accent.copy(alpha = 0.92f)
                ),
                startX = fillLeft,
                endX = width
            ),
            topLeft = Offset(fillLeft, 0f),
            size = Size(fillWidth, height),
            cornerRadius = CornerRadius(radius, radius)
        )

        // Keep all film details inside the actual completed portion.
        clipRect(
            left = fillLeft,
            top = 0f,
            right = width,
            bottom = height
        ) {
            val dp = density
            val holeWidth = 4.2f * dp
            val holeHeight = 3.2f * dp
            val holeRadius = CornerRadius(1.2f * dp, 1.2f * dp)
            val holeStep = 18f * dp
            val horizontalShift = phase.value * holeStep

            // Film perforations move from RIGHT to LEFT.
            var holeX = width - 8f * dp - holeWidth - horizontalShift

            while (holeX > fillLeft + 5f * dp) {
                if (holeX < width - 4f * dp) {
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.34f),
                        topLeft = Offset(holeX, 3.0f * dp),
                        size = Size(holeWidth, holeHeight),
                        cornerRadius = holeRadius
                    )
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.34f),
                        topLeft = Offset(
                            holeX,
                            height - 3.0f * dp - holeHeight
                        ),
                        size = Size(holeWidth, holeHeight),
                        cornerRadius = holeRadius
                    )
                }

                holeX -= holeStep
            }

            // Film frame cells also move from RIGHT to LEFT.
            val frameWidth = 13f * dp
            val frameHeight = 7f * dp
            val frameStep = 23f * dp
            val frameShift = phase.value * frameStep

            var frameX = width - 8f * dp - frameWidth - frameShift

            while (frameX > fillLeft + 3f * dp) {
                if (frameX + frameWidth < width - 3f * dp) {
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.16f),
                        topLeft = Offset(
                            frameX,
                            (height - frameHeight) / 2f
                        ),
                        size = Size(frameWidth, frameHeight),
                        cornerRadius = CornerRadius(1.5f * dp, 1.5f * dp)
                    )

                    // Thin center divider.
                    drawLine(
                        color = Color.White.copy(alpha = 0.24f),
                        start = Offset(
                            frameX + frameWidth * 0.5f,
                            height * 0.39f
                        ),
                        end = Offset(
                            frameX + frameWidth * 0.5f,
                            height * 0.61f
                        ),
                        strokeWidth = 0.8f * dp
                    )
                }

                frameX -= frameStep
            }

            // Highlight travels from RIGHT to LEFT.
            if (active && !isComplete && fillWidth > 18f * dp) {
                val highlightX = (
                        width - phase.value * fillWidth
                        ).coerceIn(
                        fillLeft + 7f * dp,
                        width - 7f * dp
                    )

                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.38f),
                            Color.Transparent
                        ),
                        startX = highlightX - 7f * dp,
                        endX = highlightX + 7f * dp
                    ),
                    topLeft = Offset(highlightX - 7f * dp, 5f * dp),
                    size = Size(14f * dp, height - 10f * dp),
                    cornerRadius = CornerRadius(5f * dp, 5f * dp)
                )
            }
        }

        // Leading edge follows the real progress from RIGHT to LEFT.
        if (!isComplete && fillWidth > 3f) {
            drawLine(
                color = Color.White.copy(alpha = 0.78f),
                start = Offset(fillLeft, 5f * density),
                end = Offset(fillLeft, height - 5f * density),
                strokeWidth = 1.5f * density
            )
        }

        // Completed state remains static.
        if (isComplete) {
            drawRoundRect(
                color = Color.White.copy(alpha = 0.13f),
                topLeft = Offset(2f * density, 2f * density),
                size = Size(
                    width - 4f * density,
                    height - 4f * density
                ),
                cornerRadius = CornerRadius(radius, radius),
                style = Stroke(width = 1f * density)
            )
        }
    }
}

/** A compact film-frame scanner used only while extracting video metadata. */

@Composable
private fun AnalysisAnimation(
    accent: Color,
    muted: Color,
    track: Color,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val scanFraction = remember { Animatable(0f) }
    val scanGlow = remember { Animatable(0.58f) }

    LaunchedEffect(isActive) {
        if (isActive) {
            while (true) {
                scanFraction.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        1750,
                        easing = LinearEasing
                    )
                )
                scanFraction.snapTo(0f)

                scanGlow.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        650,
                        easing = FastOutSlowInEasing
                    )
                )
                scanGlow.animateTo(
                    targetValue = 0.58f,
                    animationSpec = tween(
                        650,
                        easing = FastOutSlowInEasing
                    )
                )
            }
        } else {
            scanFraction.snapTo(0f)
            scanGlow.snapTo(0.58f)
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
        ) {
            val left = size.width * 0.12f
            val top = size.height * 0.14f
            val panelWidth = size.width * 0.76f
            val panelHeight = size.height * 0.72f

            drawRoundRect(
                color = track.copy(alpha = 0.72f),
                topLeft = Offset(left, top),
                size = Size(panelWidth, panelHeight),
                cornerRadius = CornerRadius(9.dp.toPx())
            )

            drawRoundRect(
                color = accent.copy(alpha = 0.42f),
                topLeft = Offset(left, top),
                size = Size(panelWidth, panelHeight),
                cornerRadius = CornerRadius(9.dp.toPx()),
                style = Stroke(width = 1.dp.toPx())
            )

            val gap = 7.dp.toPx()
            val tileWidth = (panelWidth - 4f * gap) / 3f
            val tileTop = top + 9.dp.toPx()
            val tileHeight = panelHeight - 18.dp.toPx()

            val tileColors = listOf(
                Color(0xFF526FAE),
                Color(0xFF665A9D),
                Color(0xFF3D7194)
            )

            for (index in 0..2) {
                val tileLeft = left + gap + index * (tileWidth + gap)

                drawRoundRect(
                    color = tileColors[index].copy(alpha = 0.62f),
                    topLeft = Offset(tileLeft, tileTop),
                    size = Size(tileWidth, tileHeight),
                    cornerRadius = CornerRadius(4.dp.toPx())
                )

                drawLine(
                    color = Color.White.copy(alpha = 0.42f),
                    start = Offset(
                        tileLeft + 5.dp.toPx(),
                        tileTop + tileHeight * 0.68f
                    ),
                    end = Offset(
                        tileLeft + tileWidth - 5.dp.toPx(),
                        tileTop + tileHeight * 0.68f
                    ),
                    strokeWidth = 1.2.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }

            // Scanner starts at the RIGHT and moves to the LEFT.
            val scannerX = left + panelWidth * (1f - scanFraction.value)

            drawLine(
                color = accent.copy(alpha = 0.22f * scanGlow.value),
                start = Offset(scannerX, top - 2.dp.toPx()),
                end = Offset(scannerX, top + panelHeight + 2.dp.toPx()),
                strokeWidth = 12.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )

            drawLine(
                color = Color(0xFFE3DCFF).copy(
                    alpha = 0.90f * scanGlow.value
                ),
                start = Offset(scannerX, top - 1.dp.toPx()),
                end = Offset(scannerX, top + panelHeight + 1.dp.toPx()),
                strokeWidth = 1.8.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        }

        Text(
            text = "جاري فحص الفيديو واستخراج الجودات",
            color = muted,
            fontSize = 11.sp
        )
    }
}

