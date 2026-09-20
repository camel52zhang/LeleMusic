package com.lelemusic.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 播放器自绘图标集（Canvas 实现）。
 *
 * **为什么不用 `androidx.compose.material.icons`**：
 * `material3` 传递依赖的只有 `material-icons-core`，其中**只含约 50 个最常用的图标**，
 * `Pause` / `SkipNext` / `SkipPrevious` / `Repeat` / `Shuffle` 都在
 * `material-icons-extended`（未声明依赖）里。架构约束要求「不新增未声明依赖」，
 * 因此播放器这几个必需图标全部用 `Canvas` 在 24×24 设计空间内自绘——
 * 零依赖、零矢量资源、深色模式自动跟随 `tint`。
 *
 * 所有图标只用到 `drawPath` / `drawRect` / `drawLine` / `drawCircle` 四个基础 API，
 * 不使用 `drawText`（需 `TextMeasurer`，API 稳定性风险高）。
 */
object PlayerGlyphDefaults {

    /** 设计画布边长：所有坐标都按 24×24 网格书写，绘制时等比缩放 */
    const val DESIGN_SIZE = 24f

    /** 默认图标尺寸 */
    val Size: Dp = 24.dp
}

/**
 * 给自绘图标（以及承载它们的 `IconButton`）挂无障碍描述。
 *
 * `Icon(imageVector, contentDescription)` 有内建参数，但 Canvas 自绘的图标没有，
 * 编码规范要求「所有交互式元素必须有 `contentDescription`」，因此统一用这个扩展补上。
 */
fun Modifier.glyphContentDescription(value: String): Modifier =
    this.then(Modifier.semantics { contentDescription = value })

/**
 * 播放 ▶：实心三角形。
 */
@Composable
fun PlayGlyph(
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified
) {
    GlyphCanvas(modifier = modifier, tint = tint) { s, color ->
        drawPath(
            path = Path().apply {
                moveTo(7f * s, 5f * s)
                lineTo(19f * s, 12f * s)
                lineTo(7f * s, 19f * s)
                close()
            },
            color = color
        )
    }
}

/**
 * 暂停 ❙❙：两根圆角竖条。
 */
@Composable
fun PauseGlyph(
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified
) {
    GlyphCanvas(modifier = modifier, tint = tint) { s, color ->
        drawRoundRectCompat(
            left = 6f * s,
            top = 5f * s,
            right = 10f * s,
            bottom = 19f * s,
            radius = 1.5f * s,
            color = color
        )
        drawRoundRectCompat(
            left = 14f * s,
            top = 5f * s,
            right = 18f * s,
            bottom = 19f * s,
            radius = 1.5f * s,
            color = color
        )
    }
}

/**
 * 下一首 ⏭：三角形 + 右侧竖条。
 */
@Composable
fun SkipNextGlyph(
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified
) {
    GlyphCanvas(modifier = modifier, tint = tint) { s, color ->
        drawPath(
            path = Path().apply {
                moveTo(5f * s, 6f * s)
                lineTo(16f * s, 12f * s)
                lineTo(5f * s, 18f * s)
                close()
            },
            color = color
        )
        drawRoundRectCompat(
            left = 17f * s,
            top = 6f * s,
            right = 20f * s,
            bottom = 18f * s,
            radius = 1f * s,
            color = color
        )
    }
}

/**
 * 上一首 ⏮：左侧竖条 + 三角形。
 */
@Composable
fun SkipPreviousGlyph(
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified
) {
    GlyphCanvas(modifier = modifier, tint = tint) { s, color ->
        drawRoundRectCompat(
            left = 4f * s,
            top = 6f * s,
            right = 7f * s,
            bottom = 18f * s,
            radius = 1f * s,
            color = color
        )
        drawPath(
            path = Path().apply {
                moveTo(19f * s, 6f * s)
                lineTo(19f * s, 18f * s)
                lineTo(8f * s, 12f * s)
                close()
            },
            color = color
        )
    }
}

/**
 * 循环 ⟳：圆角矩形环 + 右上方箭头（**列表循环**专用）。
 *
 * 单曲循环用 [RepeatOneGlyph]（同款图形 + 内侧「1」），二者形状接近但一眼可区分。
 */
@Composable
fun RepeatGlyph(
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified
) {
    GlyphCanvas(modifier = modifier, tint = tint) { s, color ->
        drawRepeatLoop(s, color)
    }
}

/**
 * 单曲循环 ⟳¹：[RepeatGlyph] 的圆角矩形环内侧画一个「1」。
 *
 * 用两段线（顶部小斜旗 + 竖笔）拼出数字 1，不用 `drawText`（需 TextMeasurer，
 * 与本文件「只用四个基础 API」的约束一致）。「1」放在环内左中位置，
 * 避开右上角的循环箭头。
 */
@Composable
fun RepeatOneGlyph(
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified
) {
    GlyphCanvas(modifier = modifier, tint = tint) { s, color ->
        drawRepeatLoop(s, color)
        // 数字 1：斜旗 + 竖笔
        drawLine(
            color = color,
            start = Offset(8.8f * s, 10.8f * s),
            end = Offset(10.4f * s, 9.2f * s),
            strokeWidth = 2f * s,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(10.4f * s, 9.2f * s),
            end = Offset(10.4f * s, 15f * s),
            strokeWidth = 2f * s,
            cap = StrokeCap.Round
        )
    }
}

/** RepeatGlyph / RepeatOneGlyph 共用的环 + 箭头主体。 */
private fun DrawScope.drawRepeatLoop(s: Float, color: Color) {
    drawPath(
        path = Path().apply {
            addRoundRect(
                RoundRect(
                    left = 3f * s,
                    top = 8f * s,
                    right = 21f * s,
                    bottom = 17f * s,
                    radiusX = 3.5f * s,
                    radiusY = 3.5f * s
                )
            )
        },
        color = color,
        style = Stroke(width = 2f * s)
    )
    drawPath(
        path = Path().apply {
            moveTo(14f * s, 3f * s)
            lineTo(21f * s, 7.5f * s)
            lineTo(14f * s, 12f * s)
            close()
        },
        color = color
    )
}

/**
 * 收藏加歌 ♥⁺：**线框**爱心 + 右下角融合的小加号（2026-09-12 图 022 风格）。
 *
 * 心形用两段对称贝塞尔手绘：宽 1.6~16.4、高 2.6~17.4 的标准「圆瓣心」，
 * 占画布主体（比旧版 Material favorite 缩放版更饱满居中）；
 * 加号圆帽短线画在右下角 (19.4, 17.4)，与心缘轻微相接——「融为一体」。
 */
@Composable
fun HeartAddGlyph(
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified
) {
    GlyphCanvas(modifier = modifier, tint = tint) { s, color ->
        drawPath(
            path = Path().apply {
                moveTo(9f * s, 17.4f * s) // 底部尖端
                // 左半：尖端 → 左上圆瓣 → 顶部凹口
                cubicTo(
                    4.4f * s, 14.0f * s,
                    1.6f * s, 10.6f * s,
                    1.6f * s, 7.2f * s
                )
                cubicTo(
                    1.6f * s, 4.4f * s,
                    3.6f * s, 2.6f * s,
                    5.9f * s, 2.6f * s
                )
                cubicTo(
                    7.2f * s, 2.6f * s,
                    8.3f * s, 3.3f * s,
                    9f * s, 4.4f * s
                )
                // 右半：顶部凹口 → 右上圆瓣 → 回到尖端（与左半对称）
                cubicTo(
                    9.7f * s, 3.3f * s,
                    10.8f * s, 2.6f * s,
                    12.1f * s, 2.6f * s
                )
                cubicTo(
                    14.4f * s, 2.6f * s,
                    16.4f * s, 4.4f * s,
                    16.4f * s, 7.2f * s
                )
                cubicTo(
                    16.4f * s, 10.6f * s,
                    13.6f * s, 14.0f * s,
                    9f * s, 17.4f * s
                )
                close()
            },
            color = color,
            style = Stroke(width = 2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        // 右下角小加号：圆帽短线，与爱心右下边缘相接
        drawLine(
            color = color,
            start = Offset(17.2f * s, 17.4f * s),
            end = Offset(21.6f * s, 17.4f * s),
            strokeWidth = 2f * s,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(19.4f * s, 15.2f * s),
            end = Offset(19.4f * s, 19.6f * s),
            strokeWidth = 2f * s,
            cap = StrokeCap.Round
        )
    }
}

/**
 * LRC 歌词开关（2026-09-12 图 022）：「LRC」字样 + 关闭态的左上→右下斜杠。
 *
 * 字样直接用 [Text] 排（字母字形交给系统字体，比手绘线段更准），
 * 斜杠用 Canvas 叠加在文字上层。`lyricsOn = true` 时不画斜杠。
 */
@Composable
fun LrcGlyph(
    lyricsOn: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified
) {
    val paintColor = if (tint == Color.Unspecified) Color(0xFF000000) else tint
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = "LRC",
            color = paintColor,
            style = TextStyle(
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp
            )
        )
        if (!lyricsOn) {
            Canvas(modifier = Modifier.matchParentSize()) {
                drawLine(
                    color = paintColor,
                    start = Offset(size.width * 0.08f, size.height * 0.10f),
                    end = Offset(size.width * 0.92f, size.height * 0.90f),
                    strokeWidth = 1.8.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

/**
 * 随机 🔀：两条交叉箭头。
 */
@Composable
fun ShuffleGlyph(
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified
) {
    GlyphCanvas(modifier = modifier, tint = tint) { s, color ->
        drawLine(
            color = color,
            start = Offset(4f * s, 7f * s),
            end = Offset(9f * s, 7f * s),
            strokeWidth = 2f * s,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(9f * s, 7f * s),
            end = Offset(19f * s, 18f * s),
            strokeWidth = 2f * s,
            cap = StrokeCap.Round
        )
        drawPath(
            path = Path().apply {
                moveTo(16.5f * s, 15f * s)
                lineTo(21f * s, 19.5f * s)
                lineTo(15f * s, 19.5f * s)
                close()
            },
            color = color
        )
        drawLine(
            color = color,
            start = Offset(4f * s, 17f * s),
            end = Offset(9f * s, 17f * s),
            strokeWidth = 2f * s,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(9f * s, 17f * s),
            end = Offset(19f * s, 6f * s),
            strokeWidth = 2f * s,
            cap = StrokeCap.Round
        )
        drawPath(
            path = Path().apply {
                moveTo(16.5f * s, 9f * s)
                lineTo(21f * s, 4.5f * s)
                lineTo(15f * s, 4.5f * s)
                close()
            },
            color = color
        )
    }
}

/**
 * 音符 ♪：封面缺失时的占位图形。
 */
@Composable
fun NoteGlyph(
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified
) {
    GlyphCanvas(modifier = modifier, tint = tint) { s, color ->
        drawCircle(
            color = color,
            radius = 3.2f * s,
            center = Offset(9.5f * s, 17f * s)
        )
        drawRect(
            color = color,
            topLeft = Offset(12.2f * s, 6f * s),
            size = Size(1.8f * s, 11f * s)
        )
        drawPath(
            path = Path().apply {
                moveTo(14f * s, 6f * s)
                lineTo(14f * s, 10.5f * s)
                lineTo(19f * s, 8.5f * s)
                lineTo(19f * s, 6f * s)
                close()
            },
            color = color
        )
    }
}

/**
 * 统一绘制入口：把 24×24 设计坐标按实际画布尺寸等比缩放后交给 [block]。
 *
 * @param s 缩放系数（实际边长 / 24）
 */
@Composable
private fun GlyphCanvas(
    modifier: Modifier,
    tint: Color,
    block: DrawScope.(scale: Float, color: Color) -> Unit
) {
    Canvas(modifier = modifier) {
        val scale = size.minDimension / PlayerGlyphDefaults.DESIGN_SIZE
        val paintColor = if (tint == Color.Unspecified) Color(0xFF000000) else tint
        block(scale, paintColor)
    }
}

/**
 * 实心圆角矩形。
 *
 * 单独抽出来是因为 [DrawScope.drawRoundRect] 的重载在 Compose 各版本间参数顺序有微调，
 * 统一走 `Path.addRoundRect` 更稳。
 */
private fun DrawScope.drawRoundRectCompat(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    radius: Float,
    color: Color
) {
    drawPath(
        path = Path().apply {
            addRoundRect(
                RoundRect(
                    left = left,
                    top = top,
                    right = right,
                    bottom = bottom,
                    radiusX = radius,
                    radiusY = radius
                )
            )
        },
        color = color
    )
}
