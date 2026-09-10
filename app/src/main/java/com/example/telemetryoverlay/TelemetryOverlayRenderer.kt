package com.example.telemetryoverlay

import android.content.Context
import android.graphics.*
import com.arthenica.mobileffmpeg.Config
import com.arthenica.mobileffmpeg.FFmpeg
import com.garmin.fit.*
import org.w3c.dom.Element
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import javax.xml.parsers.DocumentBuilderFactory

data class TelemetryPoint(
    val timeIso: String = "",
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
        onComplete: (Boolean, String) -> Unit
    ) {
        Thread {
            try {
                val frameDir = File(context.cacheDir, "frames")
                if (frameDir.exists()) frameDir.deleteRecursively()
                frameDir.mkdirs()

                // Parse GPX or FIT file safely
                val points = if (inputFile.name.endsWith(".fit", ignoreCase = true)) {
                    parseFitFile(inputFile)
                } else {
                    parseGpxFile(inputFile)
                }

                if (points.isEmpty()) {
                    onComplete(false, "No valid telemetry data parsed from file.")
                    return@Thread
                }

                val fps = 30
                val totalFrames = points.size * fps

                val width = 1280
                val height = 720
                
                // Single bitmap memory recycling to eliminate OutOfMemory crash
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

                var currentFrame = 0
                for (point in points) {
                    for (f in 0 until fps) {
                        canvas.drawColor(Color.GREEN) // Chroma key green

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

                        yPos += 40f
                        val paceStr = if (point.speedMs > 0.5) {
                            val secPerKm = (1000 / point.speedMs).toInt()
                            String.format(Locale.US, "PACE: %d'%02d\" /km", secPerKm / 60, secPerKm % 60)
                        } else {
                            "PACE: --'--\""
                        }
                        canvas.drawText(paceStr, cardLeft + 20f, yPos, textPaint)

                        yPos += 40f
                        val hrStr = if (point.heartRate > 0) "${point.heartRate} bpm" else "-- bpm"
                        canvas.drawText("CADENCE: ${point.cadence} spm | HR: $hrStr", cardLeft + 20f, yPos, textPaint)

                        val frameFile = File(frameDir, String.format("frame_%05d.png", currentFrame))
                        FileOutputStream(frameFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 80, out)
                            out.flush()
                        }
                        currentFrame++
                    }
                }

                bitmap.recycle()

                val ffmpegCmd = "-y -r $fps -i ${frameDir.absolutePath}/frame_%05d.png -c:v libx264 -pix_fmt yuv420p ${outputVideoFile.absolutePath}"
                val rc = FFmpeg.execute(ffmpegCmd)

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
                    cadence = (cadElem?.textContent?.toIntOrNull() ?: 0) * 2 // Coros reports single leg cadence
                    speedMs = speedElem?.textContent?.toDoubleOrNull() ?: 0.0
                }

                val formattedTime = if (timeRaw.contains("T")) timeRaw.substringAfter("T").substringBefore("Z") else timeRaw

                points.add(
                    TelemetryPoint(
                        timeIso = formattedTime,
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

    private fun parseFitFile(file: File): List<TelemetryPoint> {
        val points = mutableListOf<TelemetryPoint>()
        try {
            val decode = Decode()
            val mesgBroadcaster = MesgBroadcaster(decode)

            mesgBroadcaster.addListener(RecordMesgListener { mesg ->
                val distM = mesg.getDistance() ?: 0f
                val speed = mesg.getSpeed() ?: 0f
                val cad = mesg.getCadence() ?: 0
                val altitude = mesg.getAltitude() ?: 0f
                val hr = mesg.getHeartRate() ?: 0
                val timestamp = mesg.getTimestamp()

                val timeStr = if (timestamp != null) {
                    val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)
                    sdf.timeZone = TimeZone.getTimeZone("UTC")
                    sdf.format(timestamp.date)
                } else ""

                points.add(
                    TelemetryPoint(
                        timeIso = timeStr,
                        distanceKm = (distM / 1000.0),
                        speedMs = speed.toDouble(),
                        cadence = cad * 2,
                        elevationM = altitude.toDouble(),
                        heartRate = hr.toInt()
                    )
                )
            })

            FileInputStream(file).use { input ->
                decode.read(input, mesgBroadcaster, mesgBroadcaster)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return points
    }
}
