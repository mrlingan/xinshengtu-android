package com.lingan.imagestudio.data

import android.graphics.*
import java.io.File

data class MaskPoint(val x: Float, val y: Float)
data class MaskStroke(val points: List<MaskPoint>, val widthFraction: Float)

object MaskRenderer {
    fun save(width: Int, height: Int, strokes: List<MaskStroke>, output: File) {
        require(width in 1..2048 && height in 1..2048 && strokes.any { it.points.isNotEmpty() }) { "请先涂抹需要编辑的区域" }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            }
            strokes.forEach { stroke ->
                val points = stroke.points
                if (points.isEmpty()) return@forEach
                val brush = stroke.widthFraction.coerceIn(.005f, .3f) * width
                paint.style = Paint.Style.FILL
                points.forEach { canvas.drawCircle(it.x * width, it.y * height, brush / 2, paint) }
                if (points.size > 1) {
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = brush
                    val path = Path().apply {
                        moveTo(points.first().x * width, points.first().y * height)
                        points.drop(1).forEach { lineTo(it.x * width, it.y * height) }
                    }
                    canvas.drawPath(path, paint)
                }
            }
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            require(output.length() <= 4L * 1024 * 1024) { "遮罩超过 4MB，请减少复杂笔画" }
        } catch (e: Exception) { output.delete(); throw e }
        finally { bitmap.recycle() }
    }
}
