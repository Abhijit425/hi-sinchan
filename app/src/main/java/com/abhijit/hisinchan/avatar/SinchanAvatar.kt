package com.abhijit.hisinchan.avatar

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/** Visual parameters for one emotion. Values are animated between states. */
private data class Face(
    val eyeOpen: Float = 1f,
    val eyeSmile: Float = 0f,
    val browRaise: Float = 0f,
    val browTilt: Float = 0f,     // + = worried/empathetic (inner ends up)
    val browAsym: Float = 0f,     // curious: one brow higher
    val mouthCurve: Float = 0.6f, // -1 frown … +1 big smile
    val mouthOpen: Float = 0f,
    val mouthWidth: Float = 1f,
    val blush: Float = 0.35f,
    val headTilt: Float = 0f,     // degrees
    val bounce: Float = 0f,       // idle bounce amplitude
    val pupil: Float = 1f,        // pupil size
)

private fun faceFor(e: Emotion): Face = when (e) {
    Emotion.HAPPY -> Face(eyeOpen = 0.95f, mouthCurve = 0.85f, mouthOpen = 0.15f, blush = 0.55f)
    Emotion.EXCITED -> Face(eyeOpen = 1.12f, eyeSmile = 0f, browRaise = 0.6f, mouthCurve = 1f, mouthOpen = 0.55f, blush = 0.7f, bounce = 1f, pupil = 1.15f)
    Emotion.CURIOUS -> Face(eyeOpen = 1.05f, browRaise = 0.3f, browAsym = 1f, mouthCurve = 0.2f, mouthWidth = 0.6f, headTilt = 8f, pupil = 1.1f)
    Emotion.THINKING -> Face(eyeOpen = 0.8f, browAsym = 0.7f, browTilt = -0.2f, mouthCurve = -0.1f, mouthWidth = 0.45f, headTilt = -7f)
    Emotion.EMPATHETIC -> Face(eyeOpen = 0.8f, browTilt = 1f, browRaise = 0.2f, mouthCurve = 0.15f, mouthWidth = 0.7f, blush = 0.5f, headTilt = 5f, pupil = 1.2f)
    Emotion.SURPRISED -> Face(eyeOpen = 1.3f, browRaise = 1f, mouthCurve = 0f, mouthOpen = 0.85f, mouthWidth = 0.42f, pupil = 0.8f)
    Emotion.SLEEPY -> Face(eyeOpen = 0.22f, browRaise = -0.2f, mouthCurve = 0.25f, mouthWidth = 0.5f, blush = 0.25f, headTilt = -4f)
    Emotion.SPEAKING -> Face(eyeOpen = 1f, mouthCurve = 0.55f, blush = 0.45f)
    Emotion.LISTENING -> Face(eyeOpen = 1.08f, browRaise = 0.35f, mouthCurve = 0.45f, mouthWidth = 0.7f, headTilt = 6f, pupil = 1.15f)
    Emotion.CELEBRATING -> Face(eyeSmile = 1f, browRaise = 0.5f, mouthCurve = 1f, mouthOpen = 0.7f, blush = 0.8f, bounce = 1.4f)
    Emotion.NEUTRAL -> Face()
}

// Palette for Sinchan — an original "sprout buddy" character.
private val SkinLight = Color(0xFFFFF1E3)
private val Skin = Color(0xFFFFD9B8)
private val SkinShade = Color(0xFFEFA67E)
private val Hoodie = Color(0xFF7B5CFA)
private val HoodieDark = Color(0xFF4528C2)
private val HoodieLight = Color(0xFFA48CFF)
private val Leaf = Color(0xFF7CE0A3)
private val LeafDark = Color(0xFF2FA866)
private val Ink = Color(0xFF2B1B5E)
private val Iris = Color(0xFF6B4CE6)
private val MouthIn = Color(0xFF8E2B4E)
private val Tongue = Color(0xFFFF8BA7)
private val Cheek = Color(0xFFFF7FA3)

/**
 * Sinchan, drawn entirely with Compose Canvas (no bitmaps) so it scales crisply on any tablet,
 * stays light on battery and animates every feature independently.
 *
 * @param wordPulse increments on every spoken word (TTS onRangeStart) to drive lip-sync.
 * @param look normalized gaze target (-1..1) or null to look at the viewer.
 * @param onTap normalized tap position relative to the avatar centre.
 */
@Composable
fun SinchanAvatar(
    emotion: Emotion,
    speaking: Boolean,
    wordPulse: Int,
    listeningLevel: Float,
    gesture: Gesture,
    gestureKey: Int,
    look: Offset?,
    modifier: Modifier = Modifier,
    onTap: (Offset) -> Unit = {},
) {
    val base = if (speaking && emotion == Emotion.SPEAKING) Emotion.SPEAKING else emotion
    val target = faceFor(base)
    val spec = spring<Float>(dampingRatio = 0.7f, stiffness = 180f)
    val eyeOpen by animateFloatAsState(target.eyeOpen, spec, label = "eyeOpen")
    val eyeSmile by animateFloatAsState(target.eyeSmile, spec, label = "eyeSmile")
    val browRaise by animateFloatAsState(target.browRaise, spec, label = "brow")
    val browTilt by animateFloatAsState(target.browTilt, spec, label = "browTilt")
    val browAsym by animateFloatAsState(target.browAsym, spec, label = "browAsym")
    val mouthCurve by animateFloatAsState(target.mouthCurve, spec, label = "mouthCurve")
    val mouthOpenBase by animateFloatAsState(target.mouthOpen, spec, label = "mouthOpen")
    val mouthWidth by animateFloatAsState(target.mouthWidth, spec, label = "mouthWidth")
    val blush by animateFloatAsState(target.blush, tween(600), label = "blush")
    val headTilt by animateFloatAsState(target.headTilt, spring(0.6f, 120f), label = "tilt")
    val bounceAmp by animateFloatAsState(target.bounce, tween(500), label = "bounce")
    val pupilSize by animateFloatAsState(target.pupil, spec, label = "pupil")
    val lookX by animateFloatAsState(look?.x?.coerceIn(-1f, 1f) ?: 0f, spring(0.8f, 220f), label = "lookX")
    val lookY by animateFloatAsState(look?.y?.coerceIn(-1f, 1f) ?: 0f, spring(0.8f, 220f), label = "lookY")
    val level by animateFloatAsState(listeningLevel.coerceIn(0f, 1f), tween(120), label = "level")

    // Ambient motion: breathing, leaf sway, particle clock.
    val infinite = rememberInfiniteTransition(label = "idle")
    val breathPeriod = if (emotion == Emotion.SLEEPY) 4200 else 2600
    val breath by infinite.animateFloat(0f, (2 * PI).toFloat(),
        infiniteRepeatable(tween(breathPeriod, easing = LinearEasing)), label = "breath")
    val clock by infinite.animateFloat(0f, 1f,
        infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Restart), label = "clock")
    val talkOsc by infinite.animateFloat(0f, (2 * PI).toFloat(),
        infiniteRepeatable(tween(420, easing = LinearEasing)), label = "talk")

    // Blinking at natural random intervals.
    val blink = remember { Animatable(0f) }
    LaunchedEffect(emotion) {
        while (true) {
            delay(Random.nextLong(2200, 5200))
            if (emotion == Emotion.SLEEPY || emotion == Emotion.CELEBRATING) continue
            blink.animateTo(1f, tween(70))
            blink.animateTo(0f, tween(110))
            if (Random.nextFloat() < 0.18f) { // occasional double blink
                delay(120); blink.animateTo(1f, tween(60)); blink.animateTo(0f, tween(100))
            }
        }
    }

    // Lip-sync: each spoken word opens the mouth, then it relaxes.
    val wordAmp = remember { Animatable(0f) }
    val lastPulseAt = remember { mutableLongStateOf(0L) }
    LaunchedEffect(wordPulse) {
        if (wordPulse == 0) return@LaunchedEffect
        lastPulseAt.longValue = System.currentTimeMillis()
        wordAmp.snapTo(0.95f)
        wordAmp.animateTo(0.25f, tween(230, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(speaking) { if (!speaking) wordAmp.animateTo(0f, tween(150)) }

    // One-shot gestures (tap reactions, greetings).
    val gestureProgress = remember { Animatable(1f) }
    LaunchedEffect(gestureKey) {
        if (gestureKey == 0) return@LaunchedEffect
        gestureProgress.snapTo(0f)
        gestureProgress.animateTo(1f, tween(1100, easing = LinearEasing))
    }

    val textMeasurer = rememberTextMeasurer()

    Canvas(
        modifier
            .aspectRatio(1f)
            .semantics { contentDescription = "Sinchan, feeling ${emotion.label.lowercase()}. Tap to interact." }
            .pointerInput(Unit) {
                detectTapGestures { p ->
                    val w = size.width.toFloat(); val h = size.height.toFloat()
                    onTap(Offset((p.x - w / 2) / (w / 2), (p.y - h / 2) / (h / 2)))
                }
            }
    ) {
        val s = size.minDimension
        val cx = size.width / 2f
        val gp = gestureProgress.value
        val gActive = gp < 1f
        val gEnv = if (gActive) sin(gp * PI).toFloat() else 0f // 0→1→0 envelope

        // Mouth openness from speech
        val recentPulse = System.currentTimeMillis() - lastPulseAt.longValue < 700
        val speechOpen = when {
            !speaking -> wordAmp.value
            recentPulse -> wordAmp.value
            else -> 0.25f + 0.35f * ((sin(talkOsc) + 1f) / 2f) // engine without word callbacks
        }
        val mouthOpen = max(mouthOpenBase, speechOpen * 0.85f)

        // Body motion
        val breathe = sin(breath)
        val bounceY = -bounceAmp * s * 0.025f * ((sin(breath * 2.4f) + 1f) / 2f)
        var gx = 0f; var gy = 0f; var gTilt = 0f
        when (gesture) {
            Gesture.GIGGLE -> { gx = sin(gp * 2 * PI * 5).toFloat() * s * 0.012f * gEnv; gTilt = sin(gp * 2 * PI * 3).toFloat() * 4f * gEnv }
            Gesture.BOUNCE -> gy = -gEnv * s * 0.07f
            Gesture.NOD -> gTilt = 0f
            Gesture.SHY -> gTilt = -6f * gEnv
            else -> {}
        }
        val nodY = if (gesture == Gesture.NOD) sin(gp * 2 * PI * 2).toFloat() * s * 0.015f * gEnv else 0f
        val baseY = bounceY + gy

        // Ground shadow
        val shadowScale = 1f - (-baseY / s) * 3f
        drawOval(
            brush = Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.22f), Color.Transparent),
                center = Offset(cx, s * 0.925f), radius = s * 0.26f),
            topLeft = Offset(cx - s * 0.24f * shadowScale, s * 0.9f),
            size = Size(s * 0.48f * shadowScale, s * 0.05f),
        )

        translate(left = gx, top = baseY) {
            // ----- Listening ripples -----
            if (emotion == Emotion.LISTENING) {
                for (i in 0 until 3) {
                    val t = (clock + i / 3f) % 1f
                    val r = s * (0.32f + 0.16f * t + 0.05f * level)
                    drawCircle(Color(0xFF7CE0A3).copy(alpha = (1f - t) * (0.25f + 0.5f * level)),
                        radius = r, center = Offset(cx, s * 0.44f), style = Stroke(width = s * 0.006f))
                }
            }

            val bodyTop = s * 0.6f
            val bodyH = s * 0.29f
            val bodyW = s * 0.42f + breathe * s * 0.004f

            // ----- Arms (behind body) -----
            val armLen = s * 0.15f
            val armW = s * 0.06f
            val celebrate = emotion == Emotion.CELEBRATING || emotion == Emotion.EXCITED
            val leftAngle = when {
                celebrate -> 140f + 15f * sin(breath * 3f)
                else -> 20f + 3f * breathe
            }
            val rightAngle = when {
                gesture == Gesture.WAVE && gActive -> -150f + 25f * sin(gp * 2 * PI * 3).toFloat()
                celebrate -> -140f - 15f * sin(breath * 3f)
                emotion == Emotion.THINKING -> -115f // hand to chin
                else -> -20f - 3f * breathe
            }
            drawArm(Offset(cx - bodyW * 0.42f, bodyTop + bodyH * 0.22f), leftAngle, armLen, armW)
            drawArm(Offset(cx + bodyW * 0.42f, bodyTop + bodyH * 0.22f), rightAngle, armLen, armW)

            // ----- Body: hoodie -----
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(HoodieLight, Hoodie, HoodieDark), startY = bodyTop, endY = bodyTop + bodyH),
                topLeft = Offset(cx - bodyW / 2, bodyTop),
                size = Size(bodyW, bodyH),
                cornerRadius = CornerRadius(bodyW * 0.42f, bodyW * 0.42f),
            )
            // pocket + drawstrings
            drawRoundRect(HoodieDark.copy(alpha = 0.35f), topLeft = Offset(cx - bodyW * 0.26f, bodyTop + bodyH * 0.55f),
                size = Size(bodyW * 0.52f, bodyH * 0.22f), cornerRadius = CornerRadius(s * 0.03f, s * 0.03f))
            drawLine(Color.White.copy(alpha = 0.8f), Offset(cx - s * 0.035f, bodyTop + s * 0.03f), Offset(cx - s * 0.04f, bodyTop + s * 0.1f),
                strokeWidth = s * 0.008f, cap = StrokeCap.Round)
            drawLine(Color.White.copy(alpha = 0.8f), Offset(cx + s * 0.035f, bodyTop + s * 0.03f), Offset(cx + s * 0.04f, bodyTop + s * 0.1f),
                strokeWidth = s * 0.008f, cap = StrokeCap.Round)
            // star badge
            drawStar(Offset(cx + bodyW * 0.22f, bodyTop + bodyH * 0.35f), s * 0.028f, Color(0xFFFFD166))

            // ----- Head group (tilts around the neck) -----
            val headC = Offset(cx, s * 0.40f + nodY + breathe * s * 0.003f)
            val r = s * 0.255f
            rotate(degrees = headTilt + gTilt, pivot = Offset(cx, bodyTop + s * 0.02f)) {
                // contact shadow on hoodie
                drawOval(Color.Black.copy(alpha = 0.12f), topLeft = Offset(cx - r * 0.75f, bodyTop - r * 0.08f), size = Size(r * 1.5f, r * 0.3f))

                // Antenna sprout
                val sway = sin(breath * 1.3f) * 9f + (if (gesture == Gesture.GIGGLE) 18f * gEnv * sin(gp * 30f) else 0f)
                rotate(sway, pivot = Offset(headC.x, headC.y - r * 0.92f)) {
                    val stemTop = Offset(headC.x + r * 0.05f, headC.y - r * 1.32f)
                    val stem = Path().apply {
                        moveTo(headC.x, headC.y - r * 0.9f)
                        quadraticTo(headC.x - r * 0.08f, headC.y - r * 1.12f, stemTop.x, stemTop.y)
                    }
                    drawPath(stem, LeafDark, style = Stroke(width = s * 0.012f, cap = StrokeCap.Round))
                    drawLeaf(stemTop, r * 0.36f, -35f)
                    drawLeaf(stemTop, r * 0.26f, 210f)
                }

                // Head sphere with soft 3D lighting
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(SkinLight, Skin, SkinShade),
                        center = Offset(headC.x - r * 0.32f, headC.y - r * 0.38f),
                        radius = r * 1.55f,
                    ),
                    radius = r, center = headC,
                )
                // rim light
                drawCircle(Color.White.copy(alpha = 0.18f), radius = r, center = headC, style = Stroke(width = s * 0.006f))
                // little ears
                drawCircle(Brush.radialGradient(listOf(Skin, SkinShade), center = Offset(headC.x - r * 0.98f, headC.y + r * 0.05f), radius = r * 0.2f),
                    radius = r * 0.16f, center = Offset(headC.x - r * 0.98f, headC.y + r * 0.05f))
                drawCircle(Brush.radialGradient(listOf(Skin, SkinShade), center = Offset(headC.x + r * 0.98f, headC.y + r * 0.05f), radius = r * 0.2f),
                    radius = r * 0.16f, center = Offset(headC.x + r * 0.98f, headC.y + r * 0.05f))
                // soft hair tuft
                val tuft = Path().apply {
                    moveTo(headC.x - r * 0.55f, headC.y - r * 0.78f)
                    cubicTo(headC.x - r * 0.3f, headC.y - r * 1.08f, headC.x + r * 0.25f, headC.y - r * 1.1f, headC.x + r * 0.6f, headC.y - r * 0.76f)
                    cubicTo(headC.x + r * 0.3f, headC.y - r * 0.86f, headC.x + r * 0.1f, headC.y - r * 0.7f, headC.x - r * 0.05f, headC.y - r * 0.82f)
                    cubicTo(headC.x - r * 0.22f, headC.y - r * 0.7f, headC.x - r * 0.4f, headC.y - r * 0.78f, headC.x - r * 0.55f, headC.y - r * 0.78f)
                    close()
                }
                drawPath(tuft, Brush.verticalGradient(listOf(Color(0xFF5B3A29), Color(0xFF8A5A3C)),
                    startY = headC.y - r * 1.1f, endY = headC.y - r * 0.7f))

                // Cheeks
                val cheekA = (blush + if (gesture == Gesture.SHY) 0.6f * gEnv else 0f).coerceIn(0f, 1f)
                listOf(-1f, 1f).forEach { side ->
                    val cc = Offset(headC.x + side * r * 0.55f, headC.y + r * 0.3f)
                    drawOval(Brush.radialGradient(listOf(Cheek.copy(alpha = 0.75f * cheekA), Color.Transparent), center = cc, radius = r * 0.22f),
                        topLeft = Offset(cc.x - r * 0.22f, cc.y - r * 0.14f), size = Size(r * 0.44f, r * 0.28f))
                }

                // Eyes
                val eyeY = headC.y - r * 0.02f
                val eyeDx = r * 0.36f
                val blinkV = blink.value
                listOf(-1f, 1f).forEach { side ->
                    var open = eyeOpen * (1f - blinkV)
                    if (gesture == Gesture.WINK && side > 0 && gActive) open *= (1f - gEnv)
                    val shyLook = if (gesture == Gesture.SHY) gEnv else 0f
                    val spin = if (gesture == Gesture.SPIN_EYES && gActive) Offset(cos(gp * 4 * PI).toFloat(), sin(gp * 4 * PI).toFloat()) * 0.8f else Offset.Zero
                    val gaze = Offset(lookX * 0.6f, lookY * 0.5f + shyLook * 0.6f) + spin
                    drawEye(Offset(headC.x + side * eyeDx, eyeY), r, open, eyeSmile, gaze, pupilSize)
                }

                // Eyebrows
                listOf(-1f, 1f).forEach { side ->
                    val bx = headC.x + side * eyeDx
                    val asym = if (side < 0) browAsym * 0.12f else 0f
                    val by = eyeY - r * (0.36f + 0.1f * eyeOpen.coerceAtMost(1.2f) + browRaise * 0.1f + asym)
                    val inner = Offset(bx - side * r * 0.14f, by - browTilt * r * 0.07f)
                    val outer = Offset(bx + side * r * 0.14f, by + browTilt * r * 0.05f)
                    val brow = Path().apply {
                        moveTo(inner.x, inner.y)
                        quadraticTo(bx, by - r * 0.05f, outer.x, outer.y)
                    }
                    drawPath(brow, Color(0xFF5B3A29), style = Stroke(width = r * 0.06f, cap = StrokeCap.Round))
                }

                // Mouth
                drawMouth(Offset(headC.x + if (emotion == Emotion.THINKING) r * 0.1f else 0f, headC.y + r * 0.42f), r,
                    mouthCurve, mouthOpen.coerceIn(0f, 1f), mouthWidth)

                // Thinking hand bubble dots
                if (emotion == Emotion.THINKING) {
                    for (i in 0 until 3) {
                        val ph = (clock * 3f + i * 0.33f) % 1f
                        val a = (sin(ph * PI).toFloat()).coerceAtLeast(0f)
                        drawCircle(Color.White.copy(alpha = 0.35f + 0.6f * a), radius = r * (0.05f + 0.025f * i),
                            center = Offset(headC.x + r * (0.95f + 0.18f * i), headC.y - r * (0.95f + 0.22f * i) - a * r * 0.05f))
                    }
                }
            }

            // ----- Emotion overlays -----
            when (emotion) {
                Emotion.CELEBRATING -> drawConfetti(clock, s)
                Emotion.EXCITED -> drawSparkles(clock, s, Offset(cx, s * 0.42f))
                Emotion.SURPRISED -> drawBurst(Offset(cx, s * 0.42f), s)
                Emotion.EMPATHETIC -> drawHeart(Offset(cx + s * 0.3f, s * 0.2f - clock * s * 0.08f), s * 0.035f, Color(0xFFFF6B8B).copy(alpha = 1f - clock))
                Emotion.SLEEPY -> {
                    val style = TextStyle(color = Color(0xFFB9A8FF).copy(alpha = 1f - clock), fontSize = (14 + 10 * clock).sp, fontWeight = FontWeight.Bold)
                    drawText(textMeasurer, "z", topLeft = Offset(cx + s * 0.22f + clock * s * 0.06f, s * 0.2f - clock * s * 0.12f), style = style)
                    val c2 = (clock + 0.5f) % 1f
                    drawText(textMeasurer, "Z", topLeft = Offset(cx + s * 0.25f + c2 * s * 0.06f, s * 0.2f - c2 * s * 0.12f),
                        style = style.copy(color = Color(0xFFB9A8FF).copy(alpha = 1f - c2), fontSize = (16 + 12 * c2).sp))
                }
                else -> {}
            }
        }
    }
}

private fun DrawScope.drawArm(shoulder: Offset, angleDeg: Float, len: Float, w: Float) {
    rotate(angleDeg, pivot = shoulder) {
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(Hoodie, HoodieDark), startY = shoulder.y, endY = shoulder.y + len),
            topLeft = Offset(shoulder.x - w / 2, shoulder.y),
            size = Size(w, len),
            cornerRadius = CornerRadius(w / 2, w / 2),
        )
        drawCircle(Brush.radialGradient(listOf(SkinLight, Skin, SkinShade), center = Offset(shoulder.x - w * 0.2f, shoulder.y + len - w * 0.1f), radius = w),
            radius = w * 0.62f, center = Offset(shoulder.x, shoulder.y + len))
    }
}

private fun DrawScope.drawLeaf(base: Offset, len: Float, angle: Float) {
    rotate(angle, pivot = base) {
        val p = Path().apply {
            moveTo(base.x, base.y)
            quadraticTo(base.x + len * 0.5f, base.y - len * 0.45f, base.x + len, base.y)
            quadraticTo(base.x + len * 0.5f, base.y + len * 0.4f, base.x, base.y)
            close()
        }
        drawPath(p, Brush.linearGradient(listOf(Color(0xFFB4F5CB), Leaf, LeafDark), start = base, end = Offset(base.x + len, base.y)))
        drawLine(LeafDark.copy(alpha = 0.6f), base, Offset(base.x + len * 0.8f, base.y), strokeWidth = len * 0.05f, cap = StrokeCap.Round)
    }
}

private fun DrawScope.drawEye(c: Offset, r: Float, open: Float, smile: Float, gaze: Offset, pupilScale: Float) {
    val ew = r * 0.25f
    val eh = r * 0.3f
    val openness = (open * (1f - smile)).coerceIn(0f, 1.35f)
    if (openness > 0.1f) {
        scale(scaleX = 1f, scaleY = openness, pivot = c) {
            val eye = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c.x - ew, c.y - eh, c.x + ew, c.y + eh)) }
            drawPath(eye, Brush.verticalGradient(listOf(Color.White, Color(0xFFEDE8FF)), startY = c.y - eh, endY = c.y + eh))
            clipPath(eye) {
                val ic = Offset(c.x + gaze.x * ew * 0.45f, c.y + gaze.y * eh * 0.35f + eh * 0.08f)
                val ir = ew * 0.78f * pupilScale.coerceIn(0.7f, 1.25f)
                drawCircle(Brush.radialGradient(listOf(Color(0xFF9C86FF), Iris, Ink), center = Offset(ic.x, ic.y + ir * 0.3f), radius = ir * 1.2f),
                    radius = ir, center = ic)
                drawCircle(Color(0xFF140A33), radius = ir * 0.5f, center = ic)
                drawCircle(Color.White, radius = ir * 0.28f, center = Offset(ic.x - ir * 0.35f, ic.y - ir * 0.38f))
                drawCircle(Color.White.copy(alpha = 0.85f), radius = ir * 0.12f, center = Offset(ic.x + ir * 0.35f, ic.y + ir * 0.3f))
            }
            drawPath(eye, Ink, style = Stroke(width = r * 0.025f))
        }
        // upper lash line
        drawArc(Ink, startAngle = 200f, sweepAngle = 140f, useCenter = false,
            topLeft = Offset(c.x - ew, c.y - eh * openness), size = Size(ew * 2, eh * 2 * openness),
            style = Stroke(width = r * 0.045f, cap = StrokeCap.Round))
    } else if (smile < 0.5f) {
        // closed (blink / sleepy)
        drawArc(Ink, startAngle = 10f, sweepAngle = 160f, useCenter = false,
            topLeft = Offset(c.x - ew, c.y - eh * 0.25f), size = Size(ew * 2, eh * 0.5f),
            style = Stroke(width = r * 0.045f, cap = StrokeCap.Round))
    }
    if (smile > 0.05f) {
        // happy "^" eyes
        drawArc(Ink.copy(alpha = smile.coerceIn(0f, 1f)), startAngle = 200f, sweepAngle = 140f, useCenter = false,
            topLeft = Offset(c.x - ew, c.y - eh * 0.45f), size = Size(ew * 2, eh * 1.2f),
            style = Stroke(width = r * 0.055f, cap = StrokeCap.Round))
    }
}

private fun DrawScope.drawMouth(c: Offset, r: Float, curve: Float, open: Float, widthScale: Float) {
    val w = r * 0.42f * widthScale.coerceIn(0.3f, 1.3f)
    val h = r * 0.22f
    val left = Offset(c.x - w / 2, c.y)
    val right = Offset(c.x + w / 2, c.y)
    if (open < 0.06f) {
        val p = Path().apply {
            moveTo(left.x, left.y)
            quadraticTo(c.x, c.y + curve * h, right.x, right.y)
        }
        drawPath(p, MouthIn, style = Stroke(width = r * 0.05f, cap = StrokeCap.Round))
        return
    }
    val p = Path().apply {
        moveTo(left.x, left.y)
        quadraticTo(c.x, c.y + curve * h * 0.35f - open * h * 0.35f, right.x, right.y)
        quadraticTo(c.x, c.y + curve * h * 0.9f + open * h * 1.7f, left.x, left.y)
        close()
    }
    drawPath(p, MouthIn)
    clipPath(p) {
        drawOval(Tongue, topLeft = Offset(c.x - w * 0.3f, c.y + h * (0.3f + open)), size = Size(w * 0.6f, h * 1.2f))
        drawRect(Color.White.copy(alpha = 0.9f), topLeft = Offset(c.x - w * 0.35f, c.y - h), size = Size(w * 0.7f, h * 0.95f - open * h * 0.25f))
    }
    drawPath(p, Color(0xFF6A1F3A), style = Stroke(width = r * 0.03f))
}

private fun DrawScope.drawStar(c: Offset, r: Float, color: Color) {
    val p = Path()
    for (i in 0 until 10) {
        val rad = if (i % 2 == 0) r else r * 0.45f
        val a = -PI / 2 + i * PI / 5
        val pt = Offset(c.x + (rad * cos(a)).toFloat(), c.y + (rad * sin(a)).toFloat())
        if (i == 0) p.moveTo(pt.x, pt.y) else p.lineTo(pt.x, pt.y)
    }
    p.close()
    drawPath(p, color)
}

private fun DrawScope.drawHeart(c: Offset, r: Float, color: Color) {
    val p = Path().apply {
        moveTo(c.x, c.y + r * 0.9f)
        cubicTo(c.x - r * 1.6f, c.y - r * 0.2f, c.x - r * 0.6f, c.y - r * 1.3f, c.x, c.y - r * 0.4f)
        cubicTo(c.x + r * 0.6f, c.y - r * 1.3f, c.x + r * 1.6f, c.y - r * 0.2f, c.x, c.y + r * 0.9f)
        close()
    }
    drawPath(p, color)
}

private val confettiColors = listOf(Color(0xFFFFD166), Color(0xFFFF6B8B), Color(0xFF7CE0A3), Color(0xFF7B5CFA), Color(0xFF4CC9F0))
private val confettiSeeds = List(26) { Triple(Random(it * 7 + 3).nextFloat(), Random(it * 13 + 1).nextFloat(), Random(it * 5 + 11).nextFloat()) }

private fun DrawScope.drawConfetti(clock: Float, s: Float) {
    confettiSeeds.forEachIndexed { i, (sx, sy, sr) ->
        val t = (clock + sy) % 1f
        val x = sx * size.width + sin((t * 6 + i) * 1.0).toFloat() * s * 0.02f
        val y = t * s * 0.95f
        rotate(t * 720f * (if (i % 2 == 0) 1 else -1), pivot = Offset(x, y)) {
            drawRect(confettiColors[i % confettiColors.size].copy(alpha = 0.9f), topLeft = Offset(x, y),
                size = Size(s * (0.012f + 0.01f * sr), s * 0.022f))
        }
    }
}

private fun DrawScope.drawSparkles(clock: Float, s: Float, center: Offset) {
    for (i in 0 until 7) {
        val a = i * (2 * PI / 7) + clock * 0.6
        val pulse = ((sin((clock * 2 + i * 0.37) * 2 * PI) + 1) / 2).toFloat()
        val p = Offset(center.x + (cos(a) * s * 0.36).toFloat(), center.y + (sin(a) * s * 0.33).toFloat())
        drawStar(p, s * (0.01f + 0.018f * pulse), Color(0xFFFFE08A).copy(alpha = 0.4f + 0.6f * pulse))
    }
}

private fun DrawScope.drawBurst(center: Offset, s: Float) {
    for (i in 0 until 3) {
        val a = -PI / 2 - 0.5 + i * 0.5
        val p1 = Offset(center.x + (cos(a) * s * 0.33).toFloat(), center.y + (sin(a) * s * 0.33).toFloat())
        val p2 = Offset(center.x + (cos(a) * s * 0.4).toFloat(), center.y + (sin(a) * s * 0.4).toFloat())
        drawLine(Color(0xFFFFD166), p1, p2, strokeWidth = s * 0.012f, cap = StrokeCap.Round)
    }
}
