package com.example.telemetryoverlay

import android.content.Context
import android.graphics.*
import com.arthenica.mobileffmpeg.Config
import com.arthenica.mobileffmpeg.FFmpeg
import java.io.File
import java.io.FileOutputStream

object TelemetryOverlayRenderer {

    fun renderGreenScreenOverlay(
        context: Context,
        gpxFile: File,
        outputVideoFile: File,
        onComplete: (Boolean, String) -> Unit
    ) {
        Thread {
            try {
                val frameDir = File(context.cacheDir, "frames")
                if (frameDir.exists()) frameDir.deleteRecursively()
                frameDir.mkdirs()

                val fps = 30
                val totalSeconds = 10
                val totalFrames = fps * totalSeconds

                val width = 1280
                val height = 720
                
                // Reuse ONE single bitmap and canvas to prevent OutOfMemory crash
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)

                val greenPaint = Paint().apply { color = Color.GREEN }
                val textPaint = Paint().apply {
                    color = Color.WHITE
                    textSize = 34f
                    isAntiAlias = true
                    typeface = Typeface.DEFAULT_BOLD
                }
                val bgCardPaint = Paint().apply {
                    color = Color.parseColor("#CC000000")
                }

                for (frame in 0 until totalFrames) {
                    // Clear background with Chroma Green
                    canvas.drawColor(Color.GREEN)

                    val currentSec = frame / fps
                    val timeOfDay = "17:34:${String.format("%02d", currentSec)}"
                    val date = "10 SEP 2026"
                    val actTime = "00:${String.format("%02d", currentSec / 60)}:${String.format("%02d", currentSec % 60)}"
                    val distance = String.format("%.2f km", 0.05 + (currentSec * 0.003))
                    val pace = "5'12\" /km"
                    val heartRate = "${145 + (currentSec % 5)} bpm"
                    val cadence = "${168 + (currentSec % 3)} spm"
                    val elevation = "${45 + (currentSec / 2)} m"

                    val cardLeft = 40f
                    val cardTop = height - 260f
                    val cardRight = 580f
                    val cardBottom = height - 40f
                    canvas.drawRoundRect(cardLeft, cardTop, cardRight, cardBottom, 16f, 16f, bgCardPaint)

                    var yPos = cardTop + 40f
                    canvas.drawText("DATE/TIME: $date | $timeOfDay", cardLeft + 20f, yPos, textPaint)
                    yPos += 40f
                    canvas.drawText("TIME: $actTime   | DIST: $distance", cardLeft + 20f, yPos, textPaint)
                    yPos += 40f
                    canvas.drawText("PACE: $pace", cardLeft + 20f, yPos, textPaint)
                    yPos += 40f
                    canvas.drawText("HR: $heartRate | CADENCE: $cadence", cardLeft + 20f, yPos, textPaint)
                    yPos += 40f
                    canvas.drawText("ELEVATION: $elevation", cardLeft + 20f, yPos, textPaint)

                    // Write frame to file and release file stream immediately
                    val frameFile = File(frameDir, String.format("frame_%04d.png", frame))
                    FileOutputStream(frameFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 80, out)
                        out.flush()
                    }
                }

                // Recycle bitmap memory explicitly
                bitmap.recycle()

                // Encode with FFmpeg
                val ffmpegCmd = "-y -r $fps -i ${frameDir.absolutePath}/frame_%04d.png -c:v libx264 -pix_fmt yuv420p ${outputVideoFile.absolutePath}"
                val rc = FFmpeg.execute(ffmpegCmd)

                // Cleanup raw frames after encoding
                frameDir.deleteRecursively()

                if (rc == Config.RETURN_CODE_SUCCESS) {
                    onComplete(true, outputVideoFile.absolutePath)
                } else {
                    onComplete(false, "")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                onComplete(false, "")
            }
        }.start()
    }
}
