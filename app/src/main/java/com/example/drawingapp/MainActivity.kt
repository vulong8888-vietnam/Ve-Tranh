package com.example.drawingapp

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                DrawingScreen()
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Mo hinh du lieu                                                    */
/* ------------------------------------------------------------------ */

// Goc cua dau but thu phap dep (do). -45 = nghieng len ben phai, giong but thu phap thuong
private const val NIB_ANGLE_DEG = -45.0

enum class BrushType(val label: String, val widthMult: Float, val alpha: Float) {
    PEN("Bút bi", 1f, 1f),
    PENCIL("Bút chì", 0.45f, 0.85f),
    MARKER("Bút dạ quang", 2.5f, 0.4f),
    FLAT_NIB("Thư pháp đầu dẹt", 1.6f, 1f),
    BRUSH_PEN("Bút lông", 1.3f, 1f),
    ERASER("Tẩy", 2.5f, 1f)
}

// Mot diem cham: vi tri, luc nhan (chi co khi dung but stylus), thoi gian
class Pt(val pos: Offset, val pressure: Float, val t: Long)

// Mau da noi suy muot: vi tri + do day net tai diem do
class Sample(val pos: Offset, val w: Float)

class DrawStroke(
    val points: List<Pt>,
    val brush: BrushType,
    val color: Color,
    val size: Float,
    val opacity: Float,
    val pxPerMm: Float   // ti le thu phong luc ve, dung de tinh toc do net (but long)
) {
    // Toa do diem va do day deu tinh bang mm tren to giay.
    // Path duoc tinh 1 lan khi tao net => ve lai rat nhe
    val path: Path = buildStrokePath(points, brush, size, pxPerMm)
}

/* ------------------------------------------------------------------ */
/*  Tinh toan hinh dang net                                            */
/* ------------------------------------------------------------------ */

private fun computeWidths(pts: List<Pt>, brush: BrushType, size: Float, pxPerMm: Float): List<Float> {
    val n = pts.size
    val base = size * brush.widthMult
    val minW = 1f / pxPerMm // toi thieu 1 pixel man hinh
    if (brush != BrushType.BRUSH_PEN) return List(n) { base.coerceAtLeast(minW) }

    // But long: di cham => net day, di nhanh => net manh; nhan manh (stylus) => day;
    // dau net va cuoi net thon nho lai nhu dau co long.
    val out = ArrayList<Float>(n)
    var prev = base
    for (i in 0 until n) {
        val speed = if (i == 0) 0f else {
            val d = (pts[i].pos - pts[i - 1].pos).getDistance() * pxPerMm
            val dt = (pts[i].t - pts[i - 1].t).coerceAtLeast(1L)
            d / dt // px / ms
        }
        val speedFactor = (1.1f - 0.25f * speed).coerceIn(0.25f, 1.1f)
        val pressureFactor = 0.35f + 0.65f * pts[i].pressure.coerceIn(0f, 1f)
        val target = base * speedFactor * pressureFactor
        prev = if (i == 0) target * 0.4f else prev * 0.75f + target * 0.25f
        val edge = minOf(i, n - 1 - i)
        val taper = (0.3f + 0.2f * edge).coerceAtMost(1f)
        out.add((prev * taper).coerceAtLeast(minW))
    }
    return out
}

private fun mid(a: Offset, b: Offset): Offset = (a + b) / 2f

// Noi suy duong cong bac 2 qua cac diem de net muot, kem do day tai tung diem
private fun buildSamples(pts: List<Pt>, widths: List<Float>): List<Sample> {
    val n = pts.size
    if (n == 1) return listOf(Sample(pts[0].pos, widths[0]))
    if (n == 2) return listOf(Sample(pts[0].pos, widths[0]), Sample(pts[1].pos, widths[1]))

    val out = ArrayList<Sample>()
    out.add(Sample(pts[0].pos, widths[0]))
    val steps = 6
    for (i in 1 until n - 1) {
        val a = mid(pts[i - 1].pos, pts[i].pos)
        val p = pts[i].pos
        val c = mid(pts[i].pos, pts[i + 1].pos)
        val wa = (widths[i - 1] + widths[i]) / 2f
        val wp = widths[i]
        val wc = (widths[i] + widths[i + 1]) / 2f
        for (s in 1..steps) {
            val t = s / steps.toFloat()
            val u = 1f - t
            val pos = a * (u * u) + p * (2f * u * t) + c * (t * t)
            val w = wa * (u * u) + wp * (2f * u * t) + wc * (t * t)
            out.add(Sample(pos, w))
        }
    }
    out.add(Sample(pts[n - 1].pos, widths[n - 1]))
    return out
}

private fun buildStrokePath(points: List<Pt>, brush: BrushType, size: Float, pxPerMm: Float): Path {
    val path = Path()
    if (points.isEmpty()) return path
    val widths = computeWidths(points, brush, size, pxPerMm)
    val s = buildSamples(points, widths)

    when (brush) {
        BrushType.FLAT_NIB -> {
            // Dau but dep: moi doan la mot hinh binh hanh quet theo huong dau but.
            val a = Math.toRadians(NIB_ANGLE_DEG)
            val half = size * brush.widthMult / 2f
            val d = Offset(cos(a).toFloat(), sin(a).toFloat()) * half
            if (s.size == 1) {
                path.moveTo(s[0].pos.x - d.x, s[0].pos.y - d.y)
                path.lineTo(s[0].pos.x + d.x, s[0].pos.y + d.y)
            }
            for (i in 1 until s.size) {
                val p0 = s[i - 1].pos
                val p1 = s[i].pos
                val qa = p0 + d
                val qb = p1 + d
                val qc = p1 - d
                val qd = p0 - d
                // Dong nhat chieu quay de khi to day khong bi thung lo
                val cross = (qb.x - qa.x) * (qc.y - qb.y) - (qb.y - qa.y) * (qc.x - qb.x)
                path.moveTo(qa.x, qa.y)
                if (cross >= 0f) {
                    path.lineTo(qb.x, qb.y); path.lineTo(qc.x, qc.y); path.lineTo(qd.x, qd.y)
                } else {
                    path.lineTo(qd.x, qd.y); path.lineTo(qc.x, qc.y); path.lineTo(qb.x, qb.y)
                }
                path.close()
            }
        }

        BrushType.BRUSH_PEN -> {
            if (s.size == 1) {
                path.addOval(Rect(s[0].pos, s[0].w / 2f))
            } else {
                // Duong vien: ben trai di toi, ben phai di lui, theo do day tung diem
                val n = s.size
                val left = ArrayList<Offset>(n)
                val right = ArrayList<Offset>(n)
                for (i in 0 until n) {
                    val a = s[maxOf(i - 1, 0)].pos
                    val b = s[minOf(i + 1, n - 1)].pos
                    val dir = b - a
                    val len = dir.getDistance().coerceAtLeast(0.001f)
                    val nx = -dir.y / len
                    val ny = dir.x / len
                    val h = s[i].w / 2f
                    left.add(Offset(s[i].pos.x + nx * h, s[i].pos.y + ny * h))
                    right.add(Offset(s[i].pos.x - nx * h, s[i].pos.y - ny * h))
                }
                path.moveTo(left[0].x, left[0].y)
                for (i in 1 until n) path.lineTo(left[i].x, left[i].y)
                for (i in n - 1 downTo 0) path.lineTo(right[i].x, right[i].y)
                path.close()
            }
        }

        else -> {
            // But bi / chi / da quang / tay: mot duong net co do day deu
            path.moveTo(s[0].pos.x, s[0].pos.y)
            for (i in 1 until s.size) path.lineTo(s[i].pos.x, s[i].pos.y)
        }
    }
    return path
}

private fun DrawScope.drawDrawStroke(s: DrawStroke, scale: Float) {
    val color = if (s.brush == BrushType.ERASER) Color.White
    else s.color.copy(alpha = s.opacity * s.brush.alpha)
    val w = (s.size * s.brush.widthMult).coerceAtLeast(1f / scale)

    when (s.brush) {
        BrushType.FLAT_NIB -> {
            drawPath(s.path, color)
            // Net mong de nhung doan di doc theo luoi but van con vet muc
            drawPath(s.path, color, style = Stroke(width = 1.2f / scale, join = StrokeJoin.Round))
        }

        BrushType.BRUSH_PEN -> drawPath(s.path, color)

        else -> {
            if (s.points.size == 1) {
                drawCircle(color, radius = w / 2f, center = s.points[0].pos)
            } else {
                drawPath(
                    s.path, color,
                    style = Stroke(
                        width = w,
                        cap = if (s.brush == BrushType.MARKER) StrokeCap.Square else StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }
    }
}


/* ------------------------------------------------------------------ */
/*  Pha mau (de tham khao truoc khi ve)                                */
/* ------------------------------------------------------------------ */

private fun srgbToLinear(c: Float) =
    if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

private fun linearToSrgb(c: Float) =
    if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f

/**
 * Tron mau A va B. t = ti le cua mau B (0..1).
 * pigment = true : mo phong tron mau son/mau nuoc (tru mau), vd xanh duong + vang = xanh la.
 * pigment = false: tron deu theo RGB (nhu gradient trong phan mem do hoa).
 * Day chi la xap xi de tham khao; mau son that con phu thuoc loai son.
 */
fun mixColors(a: Color, b: Color, t: Float, pigment: Boolean): Color {
    if (t <= 0.001f) return a
    if (t >= 0.999f) return b
    if (!pigment) return lerp(a, b, t)
    fun ch(x: Float, y: Float): Float {
        val la = srgbToLinear(x).coerceAtLeast(0.02f)
        val lb = srgbToLinear(y).coerceAtLeast(0.02f)
        return linearToSrgb(la.pow(1f - t) * lb.pow(t)).coerceIn(0f, 1f)
    }
    return Color(ch(a.red, b.red), ch(a.green, b.green), ch(a.blue, b.blue))
}

private fun hexOf(c: Color) = String.format("#%06X", c.toArgb() and 0xFFFFFF)

// Doi ti le thanh "cong thuc" so phan de pha ngoai doi
private fun recipeText(tB: Float): String {
    val wa = 1f - tB
    for (total in 2..10) {
        for (a in 0..total) {
            if (abs(a.toFloat() / total - wa) < 0.05f) {
                val b = total - a
                return when {
                    b == 0 -> "Chỉ dùng màu A"
                    a == 0 -> "Chỉ dùng màu B"
                    else -> "Công thức: $a phần A + $b phần B"
                }
            }
        }
    }
    return ""
}

/* ------------------------------------------------------------------ */
/*  Luu anh ra thu vien                                                */
/* ------------------------------------------------------------------ */

fun saveToGallery(context: Context, bitmap: Bitmap): Uri? {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "Tranh_${System.currentTimeMillis()}.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/VeTranh")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
    return try {
        val ok = resolver.openOutputStream(uri)?.use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        } ?: false
        if (!ok) {
            resolver.delete(uri, null, null)
            null
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            uri
        }
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        null
    }
}

/* ------------------------------------------------------------------ */
/*  Giao dien                                                          */
/* ------------------------------------------------------------------ */

private val presetColors = listOf(
    0xFF000000, 0xFF1A1A1A, 0xFF424242, 0xFF9E9E9E, 0xFFFFFFFF,
    0xFF3E2723, 0xFF795548, 0xFFB71C1C, 0xFFC62828, 0xFFF44336,
    0xFFFF5722, 0xFFFF9800, 0xFFFFC107, 0xFFFFEB3B, 0xFFCDDC39,
    0xFF8BC34A, 0xFF4CAF50, 0xFF1B5E20, 0xFF009688, 0xFF00BCD4,
    0xFF03A9F4, 0xFF2196F3, 0xFF0D47A1, 0xFF3F51B5, 0xFF673AB7,
    0xFF9C27B0, 0xFFE91E63, 0xFFFF80AB, 0xFFFFCCBC, 0xFFB2EBF2
).map { Color(it) }

// Ve luoi o ke tren to giay. stepMm = khoang cach o (mm), linePx = do day duong ke theo pixel.
// Khi thu nho qua, luoi tu thua ra (nhan doi khoang cach) de khong bi day dac.
private fun DrawScope.drawGrid(pageW: Float, pageH: Float, stepMm: Float, scale: Float, linePx: Float) {
    var step = stepMm
    while (step * scale < 6f) step *= 2f
    val minor = Color(0x405C7C99)
    val major = Color(0x805C7C99)
    val minorW = linePx / scale
    val majorW = linePx * 1.5f / scale

    var i = 0
    var x = 0f
    while (x <= pageW + 0.001f) {
        val isMajor = i % 5 == 0
        drawLine(if (isMajor) major else minor, Offset(x, 0f), Offset(x, pageH),
            strokeWidth = if (isMajor) majorW else minorW)
        i++; x = i * step
    }
    i = 0
    var y = 0f
    while (y <= pageH + 0.001f) {
        val isMajor = i % 5 == 0
        drawLine(if (isMajor) major else minor, Offset(0f, y), Offset(pageW, y),
            strokeWidth = if (isMajor) majorW else minorW)
        i++; y = i * step
    }
}

enum class Panel { NONE, PICKER, MIXER, PAPER }

// Kho giay theo mm (dang dung doc)
enum class PaperSize(val label: String, val wMm: Float, val hMm: Float) {
    A4("A4", 210f, 297f),
    A3("A3", 297f, 420f),
    A2("A2", 420f, 594f),
    A1("A1", 594f, 841f),
    A0("A0", 841f, 1189f)
}

@Composable
fun DrawingScreen() {
    val context = LocalContext.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current

    val strokes = remember { mutableStateListOf<DrawStroke>() }
    val redoStack = remember { mutableStateListOf<DrawStroke>() }
    var currentPts by remember { mutableStateOf<List<Pt>>(emptyList()) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // Thanh cong cu tu an khi bat dau ve; bam nut "Cong cu" de hien lai
    var barsVisible by remember { mutableStateOf(true) }
    var showPaper by remember { mutableStateOf(false) }

    // To giay: toa do net ve tinh bang mm
    var paper by remember { mutableStateOf(PaperSize.A4) }
    var landscape by remember { mutableStateOf(false) }
    var gridOn by remember { mutableStateOf(false) }
    var gridMm by remember { mutableFloatStateOf(5f) }
    var gridExport by remember { mutableStateOf(false) }
    val pageW = if (landscape) paper.hMm else paper.wMm
    val pageH = if (landscape) paper.wMm else paper.hMm

    // Khung nhin: viewScale = pixel man hinh tren 1 mm, viewOffset = vi tri goc tren-trai cua to giay
    var viewScale by remember { mutableFloatStateOf(1f) }
    var viewOffset by remember { mutableStateOf(Offset.Zero) }
    var fitScale by remember { mutableFloatStateOf(1f) }

    // Anh xuat ra: toi da 300 dpi, canh dai khong qua 4096 px de khong het bo nho
    val exportPpm = min(300f / 25.4f, 4096f / max(pageW, pageH))
    val exportW = (pageW * exportPpm).toInt()
    val exportH = (pageH * exportPpm).toInt()
    val exportDpi = (exportPpm * 25.4f).toInt()

    var brush by remember { mutableStateOf(BrushType.PEN) }
    var sizeT by remember { mutableFloatStateOf(0.3f) } // 0..1, doi sang mm theo kho giay
    val maxBrushMm = min(pageW, pageH) / 6f
    val brushSize = 0.3f + (maxBrushMm - 0.3f) * sizeT * sizeT
    var opacity by remember { mutableFloatStateOf(1f) }

    var hue by remember { mutableFloatStateOf(0f) }
    var sat by remember { mutableFloatStateOf(0f) }
    var value by remember { mutableFloatStateOf(0f) }
    var selectedPreset by remember { mutableStateOf<Color?>(presetColors[0]) }
    val customColors = remember { mutableStateListOf<Color>() }
    var panel by remember { mutableStateOf(Panel.NONE) }
    val color = Color.hsv(hue * 360f, sat, value)

    // Bang pha mau
    var mixA by remember { mutableStateOf(Color(0xFF2196F3)) }
    var mixB by remember { mutableStateOf(Color(0xFFFFEB3B)) }
    var mixT by remember { mutableFloatStateOf(0.5f) }
    var mixPigment by remember { mutableStateOf(true) }
    var mixTarget by remember { mutableStateOf(0) } // 0 = A, 1 = B
    val mixResult = mixColors(mixA, mixB, mixT, mixPigment)

    fun setDrawColor(c: Color) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(c.toArgb(), hsv)
        hue = hsv[0] / 360f; sat = hsv[1]; value = hsv[2]
    }

    // Dua ca to giay vua khung nhin (chua cho cho thanh cong cu o tren)
    val topPx = with(density) { 52.dp.toPx() }
    fun fitView() {
        if (canvasSize.width == 0 || canvasSize.height == 0) return
        val availH = canvasSize.height - topPx - 12f
        val sc = min((canvasSize.width - 24f) / pageW, availH / pageH)
        fitScale = sc
        viewScale = sc
        viewOffset = Offset(
            (canvasSize.width - pageW * sc) / 2f,
            topPx + (availH - pageH * sc) / 2f
        )
    }
    LaunchedEffect(canvasSize, paper, landscape) { fitView() }

    fun doSave(share: Boolean) {
        if (strokes.isEmpty()) {
            Toast.makeText(context, "Chưa có gì để lưu", Toast.LENGTH_SHORT).show()
            return
        }
        val bmp = try {
            val img = ImageBitmap(exportW, exportH)
            val canvas = androidx.compose.ui.graphics.Canvas(img)
            CanvasDrawScope().draw(
                density, layoutDirection, canvas,
                Size(exportW.toFloat(), exportH.toFloat())
            ) {
                drawRect(Color.White)
                withTransform({ scale(exportPpm, exportPpm, Offset.Zero) }) {
                    if (gridOn && gridExport) drawGrid(pageW, pageH, gridMm, exportPpm, 2f)
                    strokes.forEach { drawDrawStroke(it, exportPpm) }
                }
            }
            img.asAndroidBitmap()
        } catch (e: OutOfMemoryError) {
            Toast.makeText(context, "Không đủ bộ nhớ để xuất ${paper.label}", Toast.LENGTH_LONG).show()
            return
        }
        val uri = saveToGallery(context, bmp)
        if (uri == null) {
            Toast.makeText(context, "Lưu ảnh thất bại", Toast.LENGTH_SHORT).show()
            return
        }
        if (share) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Chia sẻ tranh qua..."))
        } else {
            Toast.makeText(context, "Đã lưu vào thư viện (Pictures/VeTranh)", Toast.LENGTH_SHORT).show()
        }
    }

    // Android 9 tro xuong can xin quyen ghi bo nho
    var pendingShare by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) doSave(pendingShare)
        else Toast.makeText(context, "Cần cấp quyền để lưu ảnh", Toast.LENGTH_SHORT).show()
    }

    fun requestSave(share: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingShare = share
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else doSave(share)
    }

    val onPickColor: (Color) -> Unit = { c ->
        if (panel == Panel.MIXER) {
            if (mixTarget == 0) mixA = c else mixB = c
        } else {
            setDrawColor(c)
            selectedPreset = c
        }
    }

    // Canvas phu kin man hinh; thanh cong cu la lop noi ben tren nen to giay khong bi nhay khi an/hien
    Box(modifier = Modifier
        .fillMaxSize()
        .safeDrawingPadding()) {

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF9E9E9E))
                .clipToBounds()
                .onSizeChanged { canvasSize = it }
                .pointerInput(brush, color, brushSize, opacity) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        barsVisible = false // bat dau ve => an thanh cong cu
                        fun toPage(p: Offset) = (p - viewOffset) / viewScale
                        fun pressureOf(type: PointerType, p: Float) =
                            if (type == PointerType.Stylus) p else 1f

                        val list = ArrayList<Pt>()
                        var lastScreen = down.position
                        var multi = false
                        list.add(Pt(toPage(down.position), pressureOf(down.type, down.pressure), down.uptimeMillis))
                        currentPts = list.toList()

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                // Hai ngon: huy net dang ve, chuyen sang thu phong / di chuyen
                                if (!multi) {
                                    multi = true
                                    list.clear()
                                    currentPts = emptyList()
                                }
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val centroid = event.calculateCentroid()
                                val newScale = (viewScale * zoom).coerceIn(fitScale * 0.5f, 80f)
                                val k = newScale / viewScale
                                viewOffset = centroid - (centroid - viewOffset) * k + pan
                                viewScale = newScale
                                event.changes.forEach { it.consume() }
                            } else if (!multi) {
                                val ch = pressed[0]
                                if ((ch.position - lastScreen).getDistance() >= 2f) {
                                    lastScreen = ch.position
                                    list.add(Pt(toPage(ch.position), pressureOf(ch.type, ch.pressure), ch.uptimeMillis))
                                    currentPts = list.toList()
                                }
                                ch.consume()
                            }
                        }
                        if (!multi && list.isNotEmpty()) {
                            strokes.add(DrawStroke(list.toList(), brush, color, brushSize, opacity, viewScale))
                            redoStack.clear()
                        }
                        currentPts = emptyList()
                    }
                }
        ) {
            val pw = pageW * viewScale
            val ph = pageH * viewScale
            drawRect(Color(0x33000000), topLeft = viewOffset + Offset(5f, 5f), size = Size(pw, ph))
            drawRect(Color.White, topLeft = viewOffset, size = Size(pw, ph))
            withTransform({
                translate(viewOffset.x, viewOffset.y)
                scale(viewScale, viewScale, Offset.Zero)
                clipRect(0f, 0f, pageW, pageH)
            }) {
                if (gridOn) drawGrid(pageW, pageH, gridMm, viewScale, 1f)
                strokes.forEach { drawDrawStroke(it, viewScale) }
                if (currentPts.isNotEmpty()) {
                    drawDrawStroke(
                        DrawStroke(currentPts, brush, color, brushSize, opacity, viewScale),
                        viewScale
                    )
                }
            }
        }

        // Khi thanh cong cu dang an: hien nhan thong tin nho + nut goi lai
        if (!barsVisible) {
            val zoomPct = (viewScale / (density.density * 160f / 25.4f) * 100f).toInt()
            Text(
                "${paper.label} · $zoomPct%",
                fontSize = 11.sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .background(Color(0x99000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
            Text(
                "Công cụ",
                fontSize = 12.sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x99000000))
                    .clickable { barsVisible = true }
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            )
        }

        // Thanh tren (gon, mot hang)
        AnimatedVisibility(
            visible = barsVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xEEFFFFFF))
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ToolButton("Giấy ${paper.label}", true) { showPaper = true }
                ToolButton("Ô kẻ", true, selected = gridOn) { gridOn = !gridOn }
                ToolButton("↩", strokes.isNotEmpty()) {
                    if (strokes.isNotEmpty()) redoStack.add(strokes.removeAt(strokes.lastIndex))
                }
                ToolButton("↪", redoStack.isNotEmpty()) {
                    if (redoStack.isNotEmpty()) strokes.add(redoStack.removeAt(redoStack.lastIndex))
                }
                ToolButton("Xóa", true) { strokes.clear(); redoStack.clear() }
                ToolButton("Vừa", true) { fitView() }
                ToolButton("Lưu", true) { requestSave(false) }
                ToolButton("Gửi", true) { requestSave(true) }
                ToolButton("Ẩn", true) { barsVisible = false }
            }
        }

        // Bang dieu khien duoi (cuon doc duoc neu man hinh nho)
        AnimatedVisibility(
            visible = barsVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .background(Color(0xF2F5F5F5))
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    BrushType.values().forEach { b ->
                        Chip(b.label, brush == b) { brush = b }
                    }
                }

                // Hang mau
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.sweepGradient(
                                    listOf(
                                        Color.Red, Color.Yellow, Color.Green,
                                        Color.Cyan, Color.Blue, Color.Magenta, Color.Red
                                    )
                                )
                            )
                            .border(if (panel == Panel.PICKER) 3.dp else 1.dp, Color.DarkGray, CircleShape)
                            .clickable { panel = if (panel == Panel.PICKER) Panel.NONE else Panel.PICKER }
                    )
                    Chip("Pha màu", panel == Panel.MIXER) {
                        panel = if (panel == Panel.MIXER) Panel.NONE else Panel.MIXER
                    }
                    customColors.forEach { c ->
                        ColorDot(c, selectedPreset == c && panel != Panel.MIXER) { onPickColor(c) }
                    }
                    presetColors.forEach { c ->
                        ColorDot(c, selectedPreset == c && panel != Panel.MIXER) { onPickColor(c) }
                    }
                }

                // Bang chon mau HSV
                if (panel == Panel.PICKER) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        val h = hue * 360f
                        val vv = value.coerceAtLeast(0.3f)
                        GradientBar(hue, (0..6).map { Color.hsv(it * 60f, 1f, 1f) }) {
                            hue = it; selectedPreset = null
                        }
                        GradientBar(sat, listOf(Color.hsv(h, 0f, vv), Color.hsv(h, 1f, vv))) {
                            sat = it; selectedPreset = null
                        }
                        GradientBar(value, listOf(Color.Black, Color.hsv(h, sat, 1f))) {
                            value = it; selectedPreset = null
                        }
                    }
                }

                // Bang pha mau (gon)
                if (panel == Panel.MIXER) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text("Chạm A hoặc B, rồi chọn màu ở hàng màu", fontSize = 11.sp, color = Color.Gray)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            MixSwatch(mixA, "A", mixTarget == 0) { mixTarget = 0 }
                            Text("+", fontSize = 13.sp)
                            MixSwatch(mixB, "B", mixTarget == 1) { mixTarget = 1 }
                            Text("=", fontSize = 13.sp)
                            MixSwatch(mixResult, "", false)
                            Text(hexOf(mixResult), fontSize = 12.sp)
                            Chip(if (mixPigment) "Màu sơn" else "RGB", true) { mixPigment = !mixPigment }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("A", fontSize = 12.sp)
                            Slider(value = mixT, onValueChange = { mixT = it }, modifier = Modifier.weight(1f))
                            Text("B", fontSize = 12.sp)
                        }
                        Text(recipeText(mixT), fontSize = 12.sp)
                        Row(modifier = Modifier.fillMaxWidth()) {
                            for (i in 0..6) {
                                val t = i / 6f
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(24.dp)
                                        .background(mixColors(mixA, mixB, t, mixPigment))
                                        .clickable { mixT = t }
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ToolButton("Lấy màu vẽ", true) { if (mixTarget == 0) mixA = color else mixB = color }
                            ToolButton("Dùng màu này", true) {
                                setDrawColor(mixResult)
                                selectedPreset = null
                                if (brush == BrushType.ERASER) brush = BrushType.PEN
                                panel = Panel.NONE
                            }
                            ToolButton("+ Bảng màu", true) {
                                if (mixResult !in customColors) customColors.add(0, mixResult)
                            }
                        }
                    }
                }

                // Co but va do dam tren cung mot hang
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Cỡ " + (if (brushSize < 10f) String.format("%.1f", brushSize) else brushSize.toInt().toString()) + "mm",
                        fontSize = 12.sp
                    )
                    Slider(value = sizeT, onValueChange = { sizeT = it }, modifier = Modifier.weight(1f))
                    Text("Đậm ${(opacity * 100).toInt()}%", fontSize = 12.sp)
                    Slider(
                        value = opacity, onValueChange = { opacity = it },
                        valueRange = 0.1f..1f, modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Hop thoai chon kho giay: liet ke ro A4, A3, A2, A1, A0
        if (showPaper) {
            AlertDialog(
                onDismissRequest = { showPaper = false },
                confirmButton = { TextButton(onClick = { showPaper = false }) { Text("Xong") } },
                title = { Text("Khổ giấy") },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PaperSize.values().forEach { p ->
                            val sel = p == paper
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (sel) Color(0xFFE3F2FD) else Color(0xFFF5F5F5))
                                    .border(
                                        if (sel) 2.dp else 0.dp,
                                        if (sel) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        RoundedCornerShape(10.dp)
                                    )
                                    .clickable { paper = p }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(p.label, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(48.dp))
                                Text("${p.wMm.toInt()} × ${p.hMm.toInt()} mm", modifier = Modifier.weight(1f))
                                if (sel) Text("✓", fontWeight = FontWeight.Bold)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip("Giấy dọc", !landscape) { landscape = false }
                            Chip("Giấy ngang", landscape) { landscape = true }
                        }
                        Text("Ô kẻ", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Chip("Tắt", !gridOn) { gridOn = false }
                            listOf(5f, 10f, 20f).forEach { g ->
                                Chip("${g.toInt()} mm", gridOn && gridMm == g) { gridMm = g; gridOn = true }
                            }
                            Chip("Xuất kèm", gridExport) { gridExport = !gridExport }
                        }
                        Text(
                            "Ảnh xuất ra: $exportW × $exportH px (khoảng $exportDpi dpi)",
                            fontSize = 12.sp, color = Color.DarkGray
                        )
                        Text(
                            "Dùng hai ngón để thu phóng. Đổi sang khổ nhỏ hơn thì nét ngoài trang bị cắt khi lưu.",
                            fontSize = 12.sp, color = Color.DarkGray
                        )
                    }
                }
            )
        }
    }
}

@Composable
fun ToolButton(text: String, enabled: Boolean, selected: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                when {
                    !enabled -> Color(0xFFBDBDBD)
                    selected -> Color(0xFF1A237E)
                    else -> MaterialTheme.colorScheme.primary
                }
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = Color.White, fontSize = 12.sp) }
}

@Composable
fun ColorDot(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                if (selected) 3.dp else 1.dp,
                if (selected) Color.DarkGray else Color.LightGray,
                CircleShape
            )
            .clickable(onClick = onClick)
    )
}

@Composable
fun MixSwatch(color: Color, label: String, active: Boolean, onClick: (() -> Unit)? = null) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(color)
            .border(
                if (active) 3.dp else 1.dp,
                if (active) Color.DarkGray else Color.LightGray,
                RoundedCornerShape(8.dp)
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.BottomEnd
    ) {
        Text(
            label, fontSize = 11.sp,
            color = if (color.luminance() > 0.5f) Color.Black else Color.White,
            modifier = Modifier.padding(4.dp)
        )
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else Color(0xFFE0E0E0))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text, color = if (selected) Color.White else Color.Black, fontSize = 13.sp)
    }
}

// Thanh keo co nen gradient (Hue / Saturation / Value)
@Composable
fun GradientBar(value: Float, colors: List<Color>, onChange: (Float) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.horizontalGradient(colors))
            .pointerInput(Unit) {
                detectTapGestures { onChange((it.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    onChange((change.position.x / size.width).coerceIn(0f, 1f))
                }
            }
    ) {
        Canvas(Modifier.matchParentSize()) {
            val center = Offset(value * size.width, size.height / 2f)
            val r = size.height / 2f - 2f
            drawCircle(Color.White, radius = r, center = center)
            drawCircle(Color.DarkGray, radius = r, center = center, style = Stroke(2f))
        }
    }
}
