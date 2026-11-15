package com.ytube.player.ui

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.ytube.player.engine.SponsorSegment
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun SuperVideoView(
    player: Player,
    sponsorSegments: List<SponsorSegment>,
    modifier: Modifier = Modifier,
    autoSkipSponsors: Boolean = true
) {
    val context = LocalContext.current
    var currentPosMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(1L) }
    var isHoldingSpeed by remember { mutableStateOf(false) }
    var dynamicHoldSpeed by remember { mutableFloatStateOf(2.0f) }
    var normalSpeed by remember { mutableFloatStateOf(1.0f) }
    var gestureIndicatorText by remember { mutableStateOf<String?>(null) }

    // SponsorBlock Auto-Skip & Position Tracker Loop
    LaunchedEffect(player, sponsorSegments, autoSkipSponsors) {
        while (true) {
            if (player.isPlaying) {
                currentPosMs = player.currentPosition
                durationMs = player.duration.coerceAtLeast(1L)

                if (autoSkipSponsors) {
                    val activeSegment = sponsorSegments.firstOrNull {
                        currentPosMs in it.startMs..(it.endMs - 200)
                    }
                    if (activeSegment != null) {
                        player.seekTo(activeSegment.endMs)
                        gestureIndicatorText = "تم تخطي: ${activeSegment.category}"
                    }
                }
            }
            delay(250L)
        }
    }

    // Clear temporary HUD indicator
    LaunchedEffect(gestureIndicatorText) {
        if (gestureIndicatorText != null && !isHoldingSpeed) {
            delay(1200L)
            gestureIndicatorText = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { offset ->
                        val isRightSide = offset.x > size.width / 2
                        val delta = if (isRightSide) 10_000L else -10_000L
                        player.seekTo((player.currentPosition + delta).coerceIn(0L, player.duration.coerceAtLeast(0L)))
                        gestureIndicatorText = if (isRightSide) "⏩ +10s" else "⏪ -10s"
                    },
                    onLongPress = {
                        normalSpeed = player.playbackParameters.speed
                        isHoldingSpeed = true
                        dynamicHoldSpeed = 2.0f
                        player.setPlaybackSpeed(dynamicHoldSpeed)
                        gestureIndicatorText = "⚡ سرعة مضاعفة ${dynamicHoldSpeed}x"
                    },
                    onPress = {
                        tryAwaitRelease()
                        if (isHoldingSpeed) {
                            isHoldingSpeed = false
                            player.setPlaybackSpeed(normalSpeed)
                            gestureIndicatorText = null
                        }
                    }
                )
            }
            .pointerInput(isHoldingSpeed) {
                detectDragGestures(
                    onDragEnd = {
                        if (!isHoldingSpeed) gestureIndicatorText = null
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (isHoldingSpeed) {
                            // Slide up/down while holding to adjust speed between 1.25x and 4.0x
                            val speedDelta = (-dragAmount.y / size.height) * 2.5f
                            dynamicHoldSpeed = ((dynamicHoldSpeed + speedDelta).coerceIn(1.25f, 4.0f) * 4).roundToInt() / 4f
                            player.setPlaybackSpeed(dynamicHoldSpeed)
                            gestureIndicatorText = "⚡ سرعة ${dynamicHoldSpeed}x"
                        } else {
                            val isLeftScreen = change.position.x < size.width / 2
                            if (isLeftScreen) {
                                adjustScreenBrightness(context, -dragAmount.y / size.height) { pct ->
                                    gestureIndicatorText = "☀️ السطوع: $pct%"
                                }
                            } else {
                                adjustDeviceVolume(context, -dragAmount.y / size.height) { pct ->
                                    gestureIndicatorText = "🔊 الصوت: $pct%"
                                }
                            }
                        }
                    }
                )
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                }
            },
            update = { view ->
                view.player = player
            },
            modifier = Modifier.fillMaxSize()
        )

        // Gesture & SponsorBlock HUD Badge
        AnimatedVisibility(
            visible = gestureIndicatorText != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp)
        ) {
            Box(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = gestureIndicatorText.orEmpty(),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        // SponsorBlock Colored Seekbar Overlay at Bottom
        SponsorBlockSeekbarOverlay(
            currentPositionMs = currentPosMs,
            durationMs = durationMs,
            segments = sponsorSegments,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(4.dp)
        )
    }
}

@Composable
fun SponsorBlockSeekbarOverlay(
    currentPositionMs: Long,
    durationMs: Long,
    segments: List<SponsorSegment>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val totalDur = durationMs.coerceAtLeast(1L).toFloat()
        // Base track
        drawRect(color = Color.White.copy(alpha = 0.25f), size = size)

        // Played progress
        val progressWidth = (currentPositionMs / totalDur).coerceIn(0f, 1f) * size.width
        drawRect(color = Color(0xFFE53935), size = Size(progressWidth, size.height))

        // SponsorBlock Colored Segments
        for (seg in segments) {
            val startX = (seg.startMs / totalDur).coerceIn(0f, 1f) * size.width
            val endX = (seg.endMs / totalDur).coerceIn(0f, 1f) * size.width
            val segColor = when (seg.category) {
                "sponsor" -> Color(0xFF00D400)     // Green for Sponsors
                "intro" -> Color(0xFF00FFFF)       // Cyan for Intros
                "outro" -> Color(0xFF0202ED)       // Blue for Outros
                "selfpromo" -> Color(0xFFFFFF00)   // Yellow for Self-Promo
                else -> Color(0xFFCC00FF)          // Purple for Interaction
            }
            drawRect(
                color = segColor,
                topLeft = Offset(startX, 0f),
                size = Size((endX - startX).coerceAtLeast(2f), size.height)
            )
        }
    }
}

private fun adjustScreenBrightness(context: Context, deltaRatio: Float, onChanged: (Int) -> Unit) {
    val activity = context as? Activity ?: return
    val layoutParams = activity.window.attributes
    val current = if (layoutParams.screenBrightness < 0f) 0.5f else layoutParams.screenBrightness
    val updated = (current + deltaRatio).coerceIn(0.05f, 1.0f)
    layoutParams.screenBrightness = updated
    activity.window.attributes = layoutParams
    onChanged((updated * 100).roundToInt())
}

private fun adjustDeviceVolume(context: Context, deltaRatio: Float, onChanged: (Int) -> Unit) {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    val step = (deltaRatio * maxVol).roundToInt()
    val newVol = (currentVol + step).coerceIn(0, maxVol)
    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
    onChanged(((newVol.toFloat() / maxVol) * 100).roundToInt())
}
