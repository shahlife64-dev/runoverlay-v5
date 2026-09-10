package com.example.telemetryoverlay

import android.content.Context
import android.graphics.*
import com.arthenica.mobileffmpeg.Config
import com.arthenica.mobileffmpeg.FFmpeg
import org.w3c.dom.Element
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import javax.xml.parsers.DocumentBuilderFactory

data class TelemetryPoint(
    val timeIso: String = "",
    val timeMillis: Long = 0L,
    val distanceKm: Double = 0.0,
    val speedMs: Double = 0.0,
    val cadence: Int = 0,
    val elevationM: Double = 0.0,
    val heartRate: Int = 0
)

object TelemetryOverlayRenderer {

    fun renderGreenScreenOverlay(
        context: Context,
        inputFile: File,
        outputVideoFile: File,
        onProgress: (String) -> Unit,
        onComplete: (Boolean, String) -> Unit
    ) {
        Thread {
            try {
                val frameDir = File(context.cacheDir, "frames")
                if (frameDir.exists()) frameDir.deleteRecursively()
                frameDir.mkdirs()

                onProgress("Parsing GPX telemetry...")
                val points = parseGpxFile(inputFile)

                if (points.isEmpty()) {
                    onComplete(false, "No valid telemetry points found.")
                    return@Thread
                }

                val width = 1280
                val height = 720
                
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)

                val textPaint = Paint().apply {
                    color = Color.WHITE
                    textSize = 32f
                    isAntiAlias = true
                    typeface = Typeface.DEFAULT_BOLD
                }
                val bgCardPaint = Paint().apply {
                    color = Color.parseColor("#CC000000")
                }

                val totalPoints = points.size
                for ((index, point) in points.withIndex()) {
                    if (index % 5 == 0 || index == totalPoints - 1) {
                        onProgress("Generating overlay frame ${index + 1} / $totalPoints...")
                    }

                    canvas.drawColor(Color.GREEN) // Chroma Key Green

                    val cardLeft = 40f
                    val cardTop = height - 260f
                    val cardRight = 620f
                    val cardBottom = height - 40f
                    canvas.drawRoundRect(cardLeft, cardTop, cardRight, cardBottom, 16f, 16f, bgCardPaint)

                    var yPos = cardTop + 45f
                    canvas.drawText("TIME: ${point.timeIso}", cardLeft + 20f, yPos, textPaint)
                    
                    yPos += 40f
                    val distStr = String.format(Locale.US, "DIST: %.2f km", point.distanceKm)
                    val elevStr = String.format(Locale.US, "ELEV: %.0f m", point.elevationM)
                    canvas.drawText("$distStr | $elevStr", cardLeft + 20f, yPos, textPaint)

                    // Calculate Pace safely
                    yPos += 40f
                    var currentSpeedMs = point.speedMs

                    if (currentSpeedMs <= 0.1 && index > 0) {
                        val prevPoint = points[index - 1]
                        val distDiffKm = point.distanceKm - prevPoint.distanceKm
                        val timeDiffSec = if (point.timeMillis > 0 && prevPoint.timeMillis > 0) {
                            (point.timeMillis - prevPoint.timeMillis) / 1000.0
                        } else {
                            1.0 // fallback to 1 sec step
                        }
                        
                        if (timeDiffSec > 0 && distDiffKm > 0) {
                            currentSpeedMs = (distDiffKm * 1000.0) / timeDiffSec
                        }
                    }

                    val paceStr = if (currentSpeedMs > 0.4) {
                        val totalSecondsPerKm = (1000.0 / currentSpeedMs).toInt()
                        val minutes = totalSecondsPerKm / 60
                        val seconds = totalSecondsPerKm % 60
                        if (minutes in 1..29) {
                            String.format(Locale.US, "PACE: %02d:%02d /km", minutes, seconds)
                        } else {
                            "PACE: --:-- /km"
                        }
                    } else {
                        "PACE: --:-- /km"
                    }

                    canvas.drawText(paceStr, cardLeft + 20f, yPos, textPaint)

                    yPos += 40f
                    canvas.drawText("CADENCE: ${point.cadence} spm", cardLeft + 20f, yPos, textPaint)

                    val frameFile = File(frameDir, String.format("frame_%05d.png", index))
                    FileOutputStream(frameFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 80, out)
                        out.flush()
                    }
                }

                bitmap.recycle()

                onProgress("Encoding MP4 video with FFmpeg...")

                val ffmpegCmd = "-y -r 1 -i ${frameDir.absolutePath}/frame_%05d.png -c:v libx264 -preset ultrafast -pix_fmt yuv420p ${outputVideoFile.absolutePath}"
                val rc = FFmpeg.execute(ffmpegCmd)

                frameDir.deleteRecursively()

                if (rc == Config.RETURN_CODE_SUCCESS) {
                    onComplete(true, outputVideoFile.absolutePath)
                } else {
                    onComplete(false, "FFmpeg failed with return code $rc")
                }
            } catch (t: Throwable) {
                t.printStackTrace()
                onComplete(false, t.localizedMessage ?: "Unknown Error")
            }
        }.start()
    }

    private fun parseGpxFile(file: File): List<TelemetryPoint> {
        val points = mutableListOf<TelemetryPoint>()

        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(file)
            val trkpts = doc.getElementsByTagName("trkpt")

            for (i in 0 until trkpts.length) {
                val node = trkpts.item(i) as Element
                val ele = node.getElementsByTagName("ele").item(0)?.textContent?.toDoubleOrNull() ?: 0.0
                val timeRaw = node.getElementsByTagName("time").item(0)?.textContent ?: ""
                
                var timeMs = 0L
                val cleanTimeStr = timeRaw.replace("Z", "").replace("T", " ")
                try {
                    // Safe timestamp parsing across different ISO formats
                    val timePart = cleanTimeStr.substringAfter(" ").substringBefore(".")
                    val parts = timePart.split(":")
                    if (parts.size == 3) {
                        val hours = parts[0].toLongOrNull() ?: 0L
                        val mins = parts[1].toLongOrNull() ?: 0L
                        val secs = parts[2].toLongOrNull() ?: 0L
                        timeMs = (hours * 3600 + mins * 60 + secs) * 1000L
                    }
                } catch (_: Exception) {}

                var distKm = 0.0
                var cadence = 0
                var speedMs = 0.0

                val extensions = node.getElementsByTagName("extensions")
                if (extensions.length > 0) {
                    val ext = extensions.item(0) as Element
                    
                    val distElem = ext.getElementsByTagNameNS("*", "distance").item(0)
                        ?: ext.getElementsByTagName("gpxdata:distance").item(0)
                    val cadElem = ext.getElementsByTagNameNS("*", "cadence").item(0)
                        ?: ext.getElementsByTagName("gpxdata:cadence").item(0)
                    val speedElem = ext.getElementsByTagNameNS("*", "speed").item(0)
                        ?: ext.getElementsByTagName("gpxdata:speed").item(0)

                    distKm = (distElem?.textContent?.toDoubleOrNull() ?: 0.0) / 1000.0
                    cadence = (cadElem?.textContent?.toIntOrNull() ?: 0) * 2
                    
                    val parsedSpeed = speedElem?.textContent?.toDoubleOrNull() ?: 0.0
                    speedMs = if (parsedSpeed > 30.0) parsedSpeed / 3.6 else parsedSpeed
                }

                val formattedTime = if (timeRaw.contains("T")) {
                    timeRaw.substringAfter("T").substringBefore(".").substringBefore("Z")
                } else timeRaw

                points.add(
                    TelemetryPoint(
                        timeIso = formattedTime,
                        timeMillis = timeMs,
                        distanceKm = distKm,
                        speedMs = speedMs,
                        cadence = cadence,
                        elevationM = ele,
                        heartRate = 0
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return points
    }
}
