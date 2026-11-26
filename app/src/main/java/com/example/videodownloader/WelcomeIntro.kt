package com.example.videodownloader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

@Composable
internal fun WelcomeIntroScreen(
    isLaunching: Boolean,
    onStart: () -> Unit
) {
    var showBrand by remember { mutableStateOf(false) }
    var showDescription by remember { mutableStateOf(false) }
    var showDedication by remember { mutableStateOf(false) }
    var showButton by remember { mutableStateOf(false) }

    val sweepProgress = remember { Animatable(-0.35f) }

    LaunchedEffect(Unit) {
        delay(120.milliseconds)
        showBrand = true
        delay(280.milliseconds)
        showDescription = true
        delay(330.milliseconds)
        showDedication = true
        delay(260.milliseconds)
        showButton = true
    }

    LaunchedEffect(Unit) {
        delay(260.milliseconds)
        sweepProgress.animateTo(
            targetValue = 1.2f,
            animationSpec = tween(
                durationMillis = 1250,
                easing = FastOutSlowInEasing
            )
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AuroraAnimatedBackground(
            modifier = Modifier.fillMaxSize(),
            isActive = true
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AnimatedVisibility(
                visible = showBrand,
                enter = fadeIn(tween(700)) +
                        scaleIn(
                            initialScale = 0.84f,
                            animationSpec = tween(750, easing = FastOutSlowInEasing)
                        )
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(104.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        Color(0xFF637CE0),
                                        Color(0xFF7164D8),
                                        Color(0xFF9B75D1)
                                    )
                                )
                            )
                            .border(
                                width = 1.dp,
                                color = Color(0x80DCE5FF),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "↓",
                            color = Color.White,
                            fontSize = 68.sp,
                            lineHeight = 72.sp,
                            fontWeight = FontWeight.Light
                        )
                    }

                    Spacer(modifier = Modifier.height(22.dp))

                    Text(
                        text = "VideoDownloader",
                        color = Color(0xFFF5F6FF),
                        fontSize = 29.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.2.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Box(
                modifier = Modifier
                    .width(176.dp)
                    .height(2.dp)
                    .clip(CircleShape)
                    .background(Color(0x554C5C85))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.28f)
                        .graphicsLayer {
                            translationX = 176.dp.toPx() * sweepProgress.value
                        }
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color(0xFFDCE7FF),
                                    Color.Transparent
                                )
                            )
                        )
                )
            }

            Spacer(modifier = Modifier.height(30.dp))

            AnimatedVisibility(
                visible = showDescription,
                enter = fadeIn(tween(650)) +
                        slideInVertically(
                            initialOffsetY = { it / 5 },
                            animationSpec = tween(650, easing = FastOutSlowInEasing)
                        )
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "فيديوهاتك، بجودتك المفضلة.",
                        color = Color(0xFFF2F3FF),
                        fontSize = 21.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "نزّل من YouTube وFacebook وInstagram وTikTok، واختر الجودة المناسبة.",
                        color = Color(0xFFD1D8EB),
                        fontSize = 15.sp,
                        lineHeight = 25.sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "تابع تقدّم التنزيل، واحفظ الفيديو مباشرةً على جهازك.",
                        color = Color(0xFFD1D8EB),
                        fontSize = 15.sp,
                        lineHeight = 25.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(34.dp))

            AnimatedVisibility(
                visible = showDedication,
                enter = fadeIn(tween(850)) +
                        slideInVertically(
                            initialOffsetY = { it / 6 },
                            animationSpec = tween(850, easing = FastOutSlowInEasing)
                        )
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .width(42.dp)
                            .height(1.dp)
                            .background(Color(0x88DCC79F))
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "إهداء إلى من أحب",
                        color = Color(0xFFE8D6B5),
                        fontFamily = FontFamily.Serif,
                        fontSize = 27.sp,
                        fontWeight = FontWeight.Normal,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(34.dp))

            AnimatedVisibility(
                visible = showButton,
                enter = fadeIn(tween(600)) +
                        slideInVertically(
                            initialOffsetY = { it / 4 },
                            animationSpec = tween(600, easing = FastOutSlowInEasing)
                        )
            ) {
                Button(
                    onClick = onStart,
                    enabled = !isLaunching,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF7164D8),
                        disabledContainerColor = Color(0x997164D8)
                    )
                ) {
                    if (isLaunching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(19.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(11.dp))
                    }
                    Text(
                        text = if (isLaunching) "جارٍ تجهيز التطبيق..." else "بدء استخدام التطبيق",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
