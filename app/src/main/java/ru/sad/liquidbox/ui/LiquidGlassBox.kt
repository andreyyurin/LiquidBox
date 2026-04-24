package ru.sad.liquidbox.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.sad.liquidbox.R
import androidx.core.graphics.createBitmap

private val GlassBoxSize = 132.dp
private const val MaxBackgroundBitmapSize = 1600

private const val LIQUID_GLASS_SHADER = """
uniform shader u_image;
uniform float2 u_boxCenter;
uniform float u_boxSize;

const float M_E = 2.718281828459045;

float rand(float2 p) {
    return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453);
}

float sdRoundBox(float2 p, float2 bounds, float radius) {
    float2 q = abs(p) - bounds + radius;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
}

float refractionCurve(float distanceToEdge) {
    return 1.0 - 0.42 * exp(-8.0 * distanceToEdge);
}

half4 main(float2 fragCoord) {
    float halfSize = u_boxSize * 0.5;
    float2 localPx = fragCoord - u_boxCenter;
    float2 local = localPx / halfSize;
    float sdf = sdRoundBox(localPx, float2(halfSize), halfSize * 0.28) / halfSize;

    if (sdf > 0.0) {
        return half4(0.0);
    }

    float dist = -sdf;
    float refractPower = refractionCurve(dist);
    float2 sampleLocal = local * refractPower;
    float lens = length(sampleLocal - local);
    float2 sampleCoord = u_boxCenter + sampleLocal * halfSize;
    float2 normal = normalize(local + 0.0001);

    float3 refracted = u_image.eval(sampleCoord).rgb;

    float angleGlow = sin(atan(local.y, local.x) - 0.5);
    float edge = smoothstep(0.28, 0.0, dist);
    float rim = smoothstep(0.035, 0.0, abs(sdf));
    float highlight = smoothstep(0.58, 0.0, distance(local, float2(-0.42, -0.48)));
    float noise = rand(fragCoord) - 0.5;

    float3 color = refracted;
    color += float3(0.76, 0.92, 1.0) * (0.05 + lens * 0.28);
    color += noise * 0.025;
    color *= 1.0 + angleGlow * 0.22 * edge;
    color += float3(0.86, 0.96, 1.0) * rim * 0.34;
    color += float3(0.92, 0.98, 1.0) * highlight * 0.24;

    float alpha = smoothstep(0.0, -0.018, sdf) * 0.96;
    return half4(color, alpha);
}
"""

@Composable
fun LiquidGlassTouchBox(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val defaultBoxSizePx = with(density) { GlassBoxSize.toPx() }
    val runtimeShader = remember { RuntimeShader(LIQUID_GLASS_SHADER) }

    val paint = remember {
        Paint().apply {
            isAntiAlias = true
            shader = runtimeShader
        }
    }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var boxSizePx by remember { mutableStateOf(0f) }
    var targetOffset by remember { mutableStateOf(Offset.Zero) }
    var hasUserTouch by remember { mutableStateOf(false) }

    val backgroundBitmap by produceState<Bitmap?>(initialValue = null, context, containerSize) {
        value = null
        if (containerSize.width > 0 && containerSize.height > 0) {
            value = withContext(Dispatchers.IO) {
                context.loadScaledBitmapFromDrawable(
                    drawableId = R.drawable.background,
                    targetWidth = containerSize.width,
                    targetHeight = containerSize.height,
                )
            }
        }
    }

    val backgroundShader = remember(backgroundBitmap) {
        backgroundBitmap?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
    }

    val backgroundPaint = remember(backgroundShader) {
        Paint().apply {
            isAntiAlias = true
            shader = backgroundShader
        }
    }

    val animatedOffset by animateOffsetAsState(
        targetValue = targetOffset,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "liquid_glass_box_offset",
    )
    val animatedCenter = Offset(
        x = animatedOffset.x + boxSizePx / 2f,
        y = animatedOffset.y + boxSizePx / 2f,
    )

    LaunchedEffect(containerSize, boxSizePx, hasUserTouch) {
        if (!hasUserTouch && containerSize.width > 0 && containerSize.height > 0 && boxSizePx > 0f) {
            targetOffset = Offset(
                x = (containerSize.width - boxSizePx) / 2f,
                y = (containerSize.height - boxSizePx) / 2f,
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged {
                containerSize = it
                boxSizePx = defaultBoxSizePx
            }
            .pointerInput(containerSize, boxSizePx) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull() ?: continue

                        if (pointer.pressed && boxSizePx > 0f) {
                            hasUserTouch = true
                            targetOffset = pointer.position.centeredBoxOffset(
                                boxSize = boxSizePx,
                                containerSize = containerSize,
                            )
                            pointer.consume()
                        }
                    }
                }
            }
            .drawBehind {
                val bitmap = backgroundBitmap ?: return@drawBehind
                val shader = backgroundShader ?: return@drawBehind
                shader.setCropMatrix(
                    bitmapWidth = bitmap.width.toFloat(),
                    bitmapHeight = bitmap.height.toFloat(),
                    canvasWidth = size.width,
                    canvasHeight = size.height,
                )
                runtimeShader.setInputShader("u_image", shader)
                runtimeShader.setFloatUniform("u_boxCenter", animatedCenter.x, animatedCenter.y)
                runtimeShader.setFloatUniform("u_boxSize", boxSizePx)

                val boxLeft = animatedCenter.x - boxSizePx / 2f
                val boxTop = animatedCenter.y - boxSizePx / 2f
                val boxRight = animatedCenter.x + boxSizePx / 2f
                val boxBottom = animatedCenter.y + boxSizePx / 2f

                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawRect(
                        0f,
                        0f,
                        size.width,
                        size.height,
                        backgroundPaint,
                    )
                    canvas.nativeCanvas.drawRect(
                        boxLeft,
                        boxTop,
                        boxRight,
                        boxBottom,
                        paint,
                    )
                }
            }
    )
}

private fun Context.loadScaledBitmapFromDrawable(
    drawableId: Int,
    targetWidth: Int,
    targetHeight: Int,
): Bitmap {
    val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
        inScaled = false
    }
    BitmapFactory.decodeResource(resources, drawableId, options)

    if (options.outWidth > 0 && options.outHeight > 0) {
        return BitmapFactory.decodeResource(
            resources,
            drawableId,
            BitmapFactory.Options().apply {
                inScaled = false
                inSampleSize = calculateInSampleSize(
                    sourceWidth = options.outWidth,
                    sourceHeight = options.outHeight,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                )
            },
        )
    }

    val drawable = requireNotNull(ContextCompat.getDrawable(this, drawableId)) {
        "Drawable resource $drawableId was not found"
    }.mutate()
    val width = drawable.intrinsicWidth.coerceAtLeast(1)
    val height = drawable.intrinsicHeight.coerceAtLeast(1)
    val bitmap = createBitmap(width, height)
    val canvas = Canvas(bitmap)

    drawable.setBounds(0, 0, width, height)
    drawable.draw(canvas)

    return bitmap
}

private fun calculateInSampleSize(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
): Int {
    var sampleSize = 1
    val maxTargetDimension = maxOf(targetWidth, targetHeight)
        .coerceAtMost(MaxBackgroundBitmapSize)
        .coerceAtLeast(1)
    val maxSourceDimension = maxOf(sourceWidth, sourceHeight)

    while (maxSourceDimension / (sampleSize * 2) >= maxTargetDimension) {
        sampleSize *= 2
    }

    return sampleSize
}

private fun BitmapShader.setCropMatrix(
    bitmapWidth: Float,
    bitmapHeight: Float,
    canvasWidth: Float,
    canvasHeight: Float,
) {
    val scale = maxOf(canvasWidth / bitmapWidth, canvasHeight / bitmapHeight)
    val offsetX = (canvasWidth - bitmapWidth * scale) / 2f
    val offsetY = (canvasHeight - bitmapHeight * scale) / 2f
    val matrix = Matrix().apply {
        setScale(scale, scale)
        postTranslate(offsetX, offsetY)
    }

    setLocalMatrix(matrix)
}

private fun Offset.centeredBoxOffset(
    boxSize: Float,
    containerSize: IntSize,
): Offset {
    val maxX = (containerSize.width - boxSize).coerceAtLeast(0f)
    val maxY = (containerSize.height - boxSize).coerceAtLeast(0f)

    return Offset(
        x = (x - boxSize / 2f).coerceIn(0f, maxX),
        y = (y - boxSize / 2f).coerceIn(0f, maxY),
    )
}
