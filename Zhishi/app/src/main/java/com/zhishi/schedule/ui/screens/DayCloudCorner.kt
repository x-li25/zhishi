package com.zhishi.schedule.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** 单日日程之后的小互动。动画只在点击时播放，离开页面时随组合取消。 */
@Composable
fun DayCloudCorner(date: LocalDate, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    Column(modifier.fillMaxWidth().padding(top = 2.dp, bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("日程之外", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起小云" else "叫醒小云") }
        }
        if (expanded) CloudPlayground(date)
    }
}

@Composable
private fun CloudPlayground(date: LocalDate) {
    var phraseIndex by rememberSaveable(date.toString()) {
        mutableIntStateOf(Math.floorMod(date.toEpochDay(), CLOUD_PHRASES.size.toLong()).toInt())
    }
    val motion = remember(date) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val primary = MaterialTheme.colorScheme.primary
    val cloud = MaterialTheme.colorScheme.primaryContainer
    val face = MaterialTheme.colorScheme.onPrimaryContainer
    val backdrop = MaterialTheme.colorScheme.surfaceVariant
    val ink = MaterialTheme.colorScheme.onSurface
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.fillMaxWidth().height(144.dp).clip(RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClickLabel = "摸摸小云，换一句话") {
                if (!motion.isRunning) scope.launch {
                    phraseIndex = (phraseIndex + 1) % CLOUD_PHRASES.size
                    motion.snapTo(0f)
                    motion.animateTo(1f, tween(900))
                }
            }.semantics { contentDescription = "一朵可以互动的小云，点一下会蹦跳并冒出星星" }) {
            val t = motion.value
            val lift = sin(PI * t).toFloat()
            val unit = min(size.width / 320f, size.height / 144f)
            val center = Offset(size.width / 2f, size.height * 0.43f)
            drawRoundRect(Brush.verticalGradient(listOf(backdrop.copy(alpha = 0.12f), cloud.copy(alpha = 0.42f))),
                cornerRadius = CornerRadius(24.dp.toPx()))
            // 地面投影与角落的小草保持安静，把视线留给小云。
            drawOval(primary.copy(alpha = 0.09f - lift * 0.025f),
                topLeft = Offset(center.x - 55f * unit, center.y + 57f * unit), size = Size(110f * unit, 12f * unit))
            withTransform({ translate(center.x, center.y); scale(unit, unit, Offset.Zero) }) {
                drawLine(primary.copy(alpha = 0.24f), Offset(-113f, 61f), Offset(-113f, 44f), 2f, StrokeCap.Round)
                drawOval(primary.copy(alpha = 0.16f), Offset(-124f, 43f), Size(12f, 7f))
                drawOval(primary.copy(alpha = 0.24f), Offset(-112f, 36f), Size(12f, 7f))
                drawLine(primary.copy(alpha = 0.24f), Offset(113f, 61f), Offset(113f, 49f), 2f, StrokeCap.Round)
                drawOval(primary.copy(alpha = 0.18f), Offset(102f, 47f), Size(12f, 7f))
                drawCircle(primary.copy(alpha = 0.13f), 3f, Offset(-119f, -30f))
                drawCircle(primary.copy(alpha = 0.18f), 2f, Offset(112f, -17f))
                for (index in 0..4) {
                    val angle = (-155 + index * 34) * PI / 180
                    val radius = 64f + lift * 12f
                    val point = Offset(cos(angle).toFloat() * radius, sin(angle).toFloat() * radius + 20f)
                    val sparkle = Path().apply {
                        moveTo(point.x, point.y - 5f)
                        lineTo(point.x + 1.5f, point.y - 1.5f)
                        lineTo(point.x + 5f, point.y)
                        lineTo(point.x + 1.5f, point.y + 1.5f)
                        lineTo(point.x, point.y + 5f)
                        lineTo(point.x - 1.5f, point.y + 1.5f)
                        lineTo(point.x - 5f, point.y)
                        lineTo(point.x - 1.5f, point.y - 1.5f)
                        close()
                    }
                    drawPath(sparkle, primary.copy(alpha = 0.16f + lift * 0.64f))
                }
            }
            withTransform({
                translate(center.x, center.y - lift * 10f * unit)
                scale(unit, unit, Offset.Zero)
                rotate(sin(2 * PI * t).toFloat() * 6f, Offset.Zero)
            }) {
                val silhouette = Path().apply {
                    moveTo(-39f, 28f)
                    cubicTo(-70f, 28f, -69f, -10f, -45f, -12f)
                    cubicTo(-44f, -39f, -10f, -45f, 3f, -24f)
                    cubicTo(24f, -44f, 49f, -27f, 48f, -6f)
                    cubicTo(73f, -3f, 69f, 28f, 43f, 28f)
                    close()
                }
                drawPath(silhouette, Brush.verticalGradient(listOf(Color.White, cloud), startY = -40f, endY = 30f))
                drawPath(silhouette, primary.copy(alpha = 0.15f), style = Stroke(1.3f))
                drawOval(primary.copy(alpha = 0.15f), Offset(-34f, 8f), Size(15f, 7f))
                drawOval(primary.copy(alpha = 0.15f), Offset(20f, 8f), Size(15f, 7f))
                if (lift > 0.2f) {
                    drawArc(face, 200f, 140f, false, Offset(-24f, -1f), Size(12f, 9f), style = Stroke(2.4f, cap = StrokeCap.Round))
                    drawArc(face, 200f, 140f, false, Offset(12f, -1f), Size(12f, 9f), style = Stroke(2.4f, cap = StrokeCap.Round))
                } else {
                    drawCircle(face, 2.8f, Offset(-18f, 4f))
                    drawCircle(face, 2.8f, Offset(18f, 4f))
                }
                drawArc(face, 15f, 150f, false, Offset(-7f, 7f), Size(14f, 10f), style = Stroke(2f, cap = StrokeCap.Round))
            }
        }
        Text(CLOUD_PHRASES[phraseIndex], modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }, textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium, color = ink.copy(alpha = 0.8f))
        Text("点点小云，换一份好心情", textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val CLOUD_PHRASES = listOf(
    "慢慢来，也是在向前走。",
    "小云替你保管一会儿烦恼。",
    "喝口水，下一件事等一小会儿。",
    "允许自己偶尔发一会儿呆。",
    "认真生活，也要好好歇一歇。",
    "不用把每一分钟都填满。",
    "给自己一点掌声，轻轻的也算。",
    "小云营业中：烦恼暂时存放处。"
)
