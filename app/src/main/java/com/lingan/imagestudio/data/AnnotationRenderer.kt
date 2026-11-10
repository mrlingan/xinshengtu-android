package com.lingan.imagestudio.data

import android.graphics.*
import java.io.File

/** Normalized coordinates make the preview and exported reference use the same geometry. */
enum class AnnotationTool { BRUSH, TEXT, RECTANGLE, ELLIPSE, ERASER, MOVE }
data class AnnotationMark(
    val tool: AnnotationTool, val points: List<MaskPoint>,
    val color: Int = Color.RED, val width: Float = .012f, val text: String = "",
)

object AnnotationRenderer {
    fun draw(canvas: Canvas, width: Int, height: Int, source: Bitmap?, marks: List<AnnotationMark>) {
        val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val clipped = canvas.save()
        canvas.clipRect(bounds)
        if (source != null) canvas.drawBitmap(source, null, bounds, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        else canvas.drawColor(Color.WHITE)
        // Erase only annotations, preserving the reference or white background beneath them.
        val layer = canvas.saveLayer(bounds, null)
        for (mark in marks) {
            if (mark.points.isEmpty()) continue
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = mark.color; strokeWidth = mark.width.coerceIn(.003f, .15f) * width
                strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            }
            fun x(p: MaskPoint) = p.x.coerceIn(0f, 1f) * width
            fun y(p: MaskPoint) = p.y.coerceIn(0f, 1f) * height
            val first = mark.points.first(); val last = mark.points.last()
            when (mark.tool) {
                AnnotationTool.BRUSH, AnnotationTool.ERASER -> {
                    if (mark.tool == AnnotationTool.ERASER) paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                    if (mark.points.size == 1) canvas.drawCircle(x(first), y(first), paint.strokeWidth / 2, paint)
                    else {
                        paint.style = Paint.Style.STROKE
                        val path = Path().apply { moveTo(x(first), y(first)); mark.points.drop(1).forEach { lineTo(x(it), y(it)) } }
                        canvas.drawPath(path, paint)
                    }
                }
                AnnotationTool.RECTANGLE, AnnotationTool.ELLIPSE -> {
                    paint.style = Paint.Style.STROKE
                    val rect = RectF(minOf(x(first), x(last)), minOf(y(first), y(last)), maxOf(x(first), x(last)), maxOf(y(first), y(last)))
                    if (mark.tool == AnnotationTool.RECTANGLE) canvas.drawRect(rect, paint) else canvas.drawOval(rect, paint)
                }
                AnnotationTool.TEXT -> {
                    paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    paint.textSize = maxOf(mark.width * width * 4, width * .025f)
                    mark.text.take(200).split('\n').take(8).forEachIndexed { index, line ->
                        canvas.drawText(line, x(first), y(first) - paint.fontMetrics.ascent + index * paint.fontSpacing, paint)
                    }
                }
                AnnotationTool.MOVE -> Unit
            }
        }
        canvas.restoreToCount(layer)
        canvas.restoreToCount(clipped)
    }

    fun save(width: Int, height: Int, source: Bitmap?, marks: List<AnnotationMark>, output: File) {
        require(width in 1..2048 && height in 1..2048) { "画板最大为 2048×2048" }
        require(marks.size <= 1000 && marks.sumOf { it.points.size.toLong() } <= 100000) { "画板笔画过多，请分次保存" }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            draw(Canvas(bitmap), width, height, source, marks)
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "画板保存失败" } }
            require(output.length() <= 16L * 1024 * 1024) { "画板图片超过 16MB" }
        } catch (e: Exception) { output.delete(); throw e }
        finally { bitmap.recycle() }
    }
}
