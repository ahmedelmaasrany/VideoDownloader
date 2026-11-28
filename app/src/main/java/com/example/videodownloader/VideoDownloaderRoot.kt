package com.example.videodownloader

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class AppPage {
    HOME,
    SINGLE_DOWNLOAD,
    MULTI_DOWNLOAD
}

private data class BatchVideoEntry(
    val id: String,
    val url: String,
    val title: String = "",
    val formats: List<VideoFormat> = emptyList(),
    val audioFormats: List<AudioFormat> = emptyList(),
    val selectedFormatId: String? = null,
    val status: String = "لم يتم التحليل",
    val error: String? = null,
    val analyzing: Boolean = false
)

private val AuroraBackground = Brush.linearGradient(
    colors = listOf(
        Color(0xFF0D1633),
        Color(0xFF171D43),
        Color(0xFF251B4A)
    )
)

private val AuroraCard = Color(0xE61A2341)
private val AuroraAccent = Color(0xFF7164D8)
private val AuroraAccentSoft = Color(0xFFA99CFF)
private val AuroraText = Color(0xFFF1F2FF)
private val AuroraMuted = Color(0xFFAAB7D7)
private val AuroraBorder = Color(0x3DADB8FF)
private val AuroraDanger = Color(0xFFFF9AAB)

@Composable
internal fun VideoDownloaderRoot(
    engineReady: Boolean,
    context: Context
) {
    var splashFinished by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(AppPage.HOME) }

    LaunchedEffect(Unit) {
        delay(1250.milliseconds)
        splashFinished = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AuroraBackground)
    ) {
        if (!splashFinished) {
            AuroraSplashScreen()
        } else {
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    (fadeIn(tween(260)) + slideInHorizontally(
                        animationSpec = tween(420, easing = FastOutSlowInEasing),
                        initialOffsetX = { fullWidth -> fullWidth }
                    )) togetherWith (fadeOut(tween(200)) + slideOutHorizontally(
                        animationSpec = tween(360, easing = FastOutSlowInEasing),
                        targetOffsetX = { fullWidth -> -fullWidth }
                    ))
                },
                label = "appPageTransition"
            ) { targetPage ->
                when (targetPage) {
                    AppPage.HOME -> DownloadHubScreen(
                        onSingleDownload = { page = AppPage.SINGLE_DOWNLOAD },
                        onMultiDownload = { page = AppPage.MULTI_DOWNLOAD }
                    )

                    AppPage.SINGLE_DOWNLOAD -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(AuroraBackground)
                    ) {
                        BackNavigationBar(onBack = { page = AppPage.HOME })
                        Box(modifier = Modifier.weight(1f)) {
                            DownloaderScreen(
                                engineReady = engineReady,
                                context = context
                            )
                        }
                    }

                    AppPage.MULTI_DOWNLOAD -> MultiDownloadScreen(
                        context = context,
                        engineReady = engineReady,
                        onBack = { page = AppPage.HOME }
                    )
                }
            }
        }
    }
}

@Composable
private fun AuroraSplashScreen() {
    val iconScale = remember { Animatable(0.78f) }
    val iconAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch { iconAlpha.animateTo(1f, animationSpec = tween(600)) }
        launch { iconScale.animateTo(1f, animationSpec = tween(820, easing = FastOutSlowInEasing)) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AuroraBackground),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_adaptive_foreground),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(112.dp)
                .scale(iconScale.value)
                .alpha(iconAlpha.value)
        )
    }
}

@Composable
private fun DownloadHubScreen(
    onSingleDownload: () -> Unit,
    onMultiDownload: () -> Unit
) {
    val tasks by DownloadUiStateStore.tasks.collectAsState()
    val activeCount = tasks.count { it.isActive }
    val waitingCount = tasks.count { it.status == DownloadTaskUiState.STATUS_WAITING_FOR_NETWORK }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AuroraBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(14.dp))

        Surface(
            color = Color(0x554D5EB8),
            shape = RoundedCornerShape(24.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, AuroraBorder)
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_source),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(86.dp).padding(10.dp)
            )
        }

        Text(
            text = "Video Downloader",
            color = AuroraText,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "اختر طريقة التنزيل",
            color = AuroraMuted,
            fontSize = 14.sp
        )

        HubOptionCard(
            title = "تنزيل فيديو واحد",
            description = "حلّل الرابط، اختر الجودة، وابدأ التنزيل.",
            symbol = "↓",
            onClick = onSingleDownload
        )

        HubOptionCard(
            title = "تنزيل عدة فيديوهات",
            description = "أضف روابط متعددة، اختر جودة كل فيديو، واترك مدير التنزيلات ينظم التنفيذ.",
            symbol = "▤",
            onClick = onMultiDownload
        )

        if (activeCount > 0) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = AuroraCard),
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AuroraBorder)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("مدير التنزيلات", color = AuroraText, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = if (waitingCount > 0) {
                            "$activeCount مهام نشطة • $waitingCount في انتظار الإنترنت"
                        } else {
                            "$activeCount مهام نشطة"
                        },
                        color = if (waitingCount > 0) AuroraAccentSoft else AuroraMuted,
                        fontSize = 13.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "استخدم روابط محتوى تملك حق تنزيله أو يسمح مصدره بذلك.",
            color = AuroraMuted,
            fontSize = 11.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun HubOptionCard(
    title: String,
    description: String,
    symbol: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AuroraCard),
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, AuroraBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                modifier = Modifier.size(54.dp),
                color = Color(0x664D5EB8),
                shape = RoundedCornerShape(17.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(symbol, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Medium)
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, color = AuroraText, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(description, color = AuroraMuted, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Text("‹", color = AuroraAccentSoft, fontSize = 29.sp)
        }
    }
}

@Composable
private fun BackNavigationBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x33101830))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onBack,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0x554D5EB8),
                contentColor = AuroraText
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("رجوع")
        }
        Spacer(Modifier.width(10.dp))
        Text("Video Downloader", color = AuroraMuted, fontSize = 13.sp)
    }
}

@Composable
private fun MultiDownloadScreen(
    context: Context,
    engineReady: Boolean,
    onBack: () -> Unit
) {
    val entries = remember { mutableStateListOf<BatchVideoEntry>() }
    val tasks by DownloadUiStateStore.tasks.collectAsState()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var urlsInput by remember { mutableStateOf("") }
    var batchAnalyzing by remember { mutableStateOf(false) }
    var preferredTier by remember { mutableStateOf(QualityTier.STRONG) }
    var screenMessage by remember { mutableStateOf("") }
    var screenError by remember { mutableStateOf(false) }

    fun addLinks() {
        val parsed = urlsInput.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("https://") || it.startsWith("http://") }
            .distinct()
            .toList()
        var added = 0
        parsed.forEach { url ->
            if (entries.none { it.url == url }) {
                entries.add(BatchVideoEntry(id = UUID.randomUUID().toString(), url = url))
                added++
            }
        }
        urlsInput = ""
        screenMessage = if (added > 0) "تمت إضافة $added روابط إلى القائمة." else "لم يتم العثور على روابط HTTP أو HTTPS جديدة."
        screenError = added == 0
    }

    fun analyzeAll() {
        if (!engineReady) {
            screenMessage = "محرك yt-dlp لم يبدأ بنجاح."
            screenError = true
            return
        }
        val pending = entries.filter { it.formats.isEmpty() && !it.analyzing }
        if (pending.isEmpty()) {
            screenMessage = "لا توجد روابط جديدة للتحليل."
            screenError = true
            return
        }

        batchAnalyzing = true
        screenMessage = "جارٍ تحليل الروابط واحدًا تلو الآخر..."
        screenError = false
        var remaining = pending.size

        pending.forEach { entry ->
            val index = entries.indexOfFirst { it.id == entry.id }
            if (index >= 0) entries[index] = entries[index].copy(analyzing = true, status = "جاري التحليل")

            AnalysisStateStore.executor.execute {
                try {
                    val info = extractVideoInfo(entry.url, context)
                    mainHandler.post {
                        val currentIndex = entries.indexOfFirst { it.id == entry.id }
                        if (currentIndex >= 0) {
                            val available = info.formats.filter { it.isAvailable }
                            val selected = available.firstOrNull { it.qualityTier == preferredTier }
                                ?: available.firstOrNull()
                            entries[currentIndex] = entries[currentIndex].copy(
                                title = info.title,
                                formats = available,
                                audioFormats = info.audioFormats,
                                selectedFormatId = selected?.formatId,
                                status = if (available.isEmpty()) "لا توجد جودة متاحة" else "جاهز للتنزيل",
                                error = if (available.isEmpty()) "لم تتوفر جودة مناسبة لهذا الرابط." else null,
                                analyzing = false
                            )
                        }
                        remaining--
                        if (remaining == 0) {
                            batchAnalyzing = false
                            val readyCount = entries.count { it.selectedFormatId != null }
                            screenMessage = "اكتمل التحليل. $readyCount فيديوهات جاهزة للتنزيل."
                            screenError = readyCount == 0
                        }
                    }
                } catch (error: Exception) {
                    mainHandler.post {
                        val currentIndex = entries.indexOfFirst { it.id == entry.id }
                        if (currentIndex >= 0) {
                            entries[currentIndex] = entries[currentIndex].copy(
                                status = "فشل التحليل",
                                error = errorDetails(error),
                                analyzing = false
                            )
                        }
                        remaining--
                        if (remaining == 0) {
                            batchAnalyzing = false
                            screenMessage = "انتهى تحليل القائمة مع وجود روابط لم يتم تحليلها."
                            screenError = true
                        }
                    }
                }
            }
        }
    }

    fun startReadyDownloads() {
        val ready = entries.filter { entry ->
            entry.formats.any { it.isAvailable && it.formatId == entry.selectedFormatId }
        }
        if (ready.isEmpty()) {
            screenMessage = "حلّل رابطًا واحدًا على الأقل واختر له جودة متاحة."
            screenError = true
            return
        }

        var started = 0
        ready.forEach { entry ->
            val chosen = entry.formats.firstOrNull { it.formatId == entry.selectedFormatId } ?: return@forEach
            val request = DownloadForegroundService.createStartIntent(
                context = context,
                url = entry.url,
                title = entry.title,
                selectedFormat = chosen,
                audioFormats = entry.audioFormats,
                taskId = entry.id,
                openOnFinish = false
            )
            try {
                ContextCompat.startForegroundService(context, request)
                started++
                val index = entries.indexOfFirst { it.id == entry.id }
                if (index >= 0) entries[index] = entries[index].copy(status = "تمت الإضافة إلى قائمة التنزيلات")
            } catch (error: Exception) {
                val index = entries.indexOfFirst { it.id == entry.id }
                if (index >= 0) entries[index] = entries[index].copy(status = "تعذر بدء المهمة", error = errorDetails(error))
            }
        }

        screenMessage = if (started > 0) {
            "تم إرسال $started فيديوهات إلى مدير التنزيلات. الحد الأقصى الجاري تنفيذه اثنان، والبقية في الانتظار."
        } else {
            "تعذر إرسال التنزيلات إلى الخدمة."
        }
        screenError = started == 0
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        startReadyDownloads()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AuroraBackground)
    ) {
        BackNavigationBar(onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("تنزيل عدة فيديوهات", color = AuroraText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                "أضف رابطًا في كل سطر. سيحلل التطبيق الروابط ثم يمكنك اختيار جودة مستقلة لكل فيديو.",
                color = AuroraMuted,
                fontSize = 13.sp
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = AuroraCard),
                shape = RoundedCornerShape(22.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AuroraBorder)
            ) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = urlsInput,
                        onValueChange = { urlsInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6,
                        placeholder = { Text("https://example.com/video-1\nhttps://example.com/video-2", color = AuroraMuted) },
                        label = { Text("روابط الفيديوهات") },
                        shape = RoundedCornerShape(15.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = AuroraText,
                            unfocusedTextColor = AuroraText,
                            cursorColor = AuroraAccentSoft,
                            focusedBorderColor = AuroraAccentSoft,
                            unfocusedBorderColor = AuroraBorder,
                            focusedLabelColor = AuroraAccentSoft,
                            unfocusedLabelColor = AuroraMuted
                        )
                    )
                    Button(
                        onClick = ::addLinks,
                        enabled = urlsInput.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AuroraAccent)
                    ) { Text("إضافة الروابط إلى القائمة") }

                    Text("الجودة الافتراضية المقترحة", color = AuroraText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        QualityTier.entries.forEach { tier ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                RadioButton(
                                    selected = preferredTier == tier,
                                    onClick = {
                                        preferredTier = tier
                                        entries.indices.forEach { index ->
                                            val item = entries[index]
                                            val best = item.formats.firstOrNull { it.qualityTier == tier }
                                            if (best != null) entries[index] = item.copy(selectedFormatId = best.formatId)
                                        }
                                    }
                                )
                                Text(tier.label, color = AuroraMuted, fontSize = 9.sp, textAlign = TextAlign.Center)
                            }
                        }
                    }
                    Button(
                        onClick = ::analyzeAll,
                        enabled = engineReady && entries.isNotEmpty() && !batchAnalyzing,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4E75C9))
                    ) {
                        Text(if (batchAnalyzing) "جاري تحليل الروابط..." else "تحليل كل الروابط")
                    }
                }
            }

            if (screenMessage.isNotBlank()) {
                Text(
                    screenMessage,
                    color = if (screenError) AuroraDanger else AuroraAccentSoft,
                    fontSize = 12.sp
                )
            }

            if (entries.isNotEmpty()) {
                Text("قائمة الفيديوهات (${entries.size})", color = AuroraText, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                entries.forEach { entry ->
                    BatchVideoCard(
                        entry = entry,
                        onSelectFormat = { formatId ->
                            val index = entries.indexOfFirst { it.id == entry.id }
                            if (index >= 0) entries[index] = entries[index].copy(selectedFormatId = formatId)
                        },
                        onRemove = {
                            entries.removeAll { it.id == entry.id }
                        }
                    )
                }

                Button(
                    onClick = {
                        if (
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            startReadyDownloads()
                        }
                    },
                    enabled = entries.any { it.selectedFormatId != null } && !batchAnalyzing,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AuroraAccent)
                ) {
                    Text("بدء تنزيل الفيديوهات المختارة", fontWeight = FontWeight.SemiBold)
                }
            }

            if (tasks.isNotEmpty()) {
                Text("حالة مدير التنزيلات", color = AuroraText, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                tasks.asReversed().take(12).forEach { task ->
                    DownloadTaskCard(task)
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun BatchVideoCard(
    entry: BatchVideoEntry,
    onSelectFormat: (String) -> Unit,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AuroraCard),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, AuroraBorder)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(entry.title.ifBlank { entry.url }, color = AuroraText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(entry.status, color = if (entry.error == null) AuroraAccentSoft else AuroraDanger, fontSize = 11.sp)
                }
                Button(
                    onClick = onRemove,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0x553E263D)),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("حذف", color = AuroraDanger, fontSize = 11.sp) }
            }

            entry.error?.let { Text(it, color = AuroraDanger, fontSize = 11.sp) }

            if (entry.analyzing) {
                Text("يتم استخراج الجودات المتاحة...", color = AuroraMuted, fontSize = 11.sp)
            }

            entry.formats.forEach { format ->
                val selected = entry.selectedFormatId == format.formatId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (selected) Color(0x554F569D) else Color(0x22101830),
                            RoundedCornerShape(12.dp)
                        )
                        .border(
                            width = 1.dp,
                            color = if (selected) AuroraAccentSoft else AuroraBorder,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selected, onClick = { onSelectFormat(format.formatId) })
                    Column(modifier = Modifier.weight(1f).padding(vertical = 5.dp)) {
                        Text(format.qualityTier.label, color = AuroraText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        val size = format.fileSize?.let { batchReadableSize(it) } ?: "الحجم غير متاح"
                        Text("${format.height}p • $size • ${format.extension}", color = AuroraMuted, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadTaskCard(task: DownloadTaskUiState) {
    val statusColor = when (task.status) {
        DownloadTaskUiState.STATUS_WAITING_FOR_NETWORK -> AuroraAccentSoft
        DownloadTaskUiState.STATUS_FAILED -> AuroraDanger
        DownloadTaskUiState.STATUS_SUCCEEDED -> Color(0xFF9ED9BD)
        else -> AuroraMuted
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xCC1A2341)),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, AuroraBorder)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(task.title.ifBlank { "فيديو" }, color = AuroraText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(task.message, color = statusColor, fontSize = 11.sp)
            androidx.compose.material3.LinearProgressIndicator(
                progress = { task.progress.coerceIn(0f, 100f) / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = AuroraAccentSoft,
                trackColor = Color(0xFF303B61)
            )
            Text("${task.progress.roundToInt().coerceIn(0, 100)}%", color = AuroraMuted, fontSize = 10.sp)
        }
    }
}


private fun batchReadableSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        String.format(Locale.US, "%.2f GB", mb / 1024.0)
    } else {
        String.format(Locale.US, "%.1f MB", mb)
    }
}
