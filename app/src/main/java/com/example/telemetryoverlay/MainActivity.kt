package com.example.telemetryoverlay

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private var selectedUri: Uri? = null
    private var selectedFileName: String = "input_data.gpx"
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnSelectFile = findViewById<Button>(R.id.btnSelectFile)
        val btnGenerate = findViewById<Button>(R.id.btnGenerate)
        statusText = findViewById(R.id.statusText)

        btnSelectFile.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
            }
            startActivityForResult(intent, 1001)
        }

        btnGenerate.setOnClickListener {
            if (selectedUri == null) {
                Toast.makeText(this, "Please select a GPX/FIT file first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            processTelemetryOverlay()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == Activity.RESULT_OK) {
            selectedUri = data?.data
            selectedUri?.let { uri ->
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameIndex != -1) {
                        selectedFileName = cursor.getString(nameIndex)
                    }
                }
            }
            statusText.text = "Loaded File: $selectedFileName"
        }
    }

    private fun processTelemetryOverlay() {
        statusText.text = "Preparing telemetry data..."

        try {
            val localFile = File(cacheDir, selectedFileName)
            contentResolver.openInputStream(selectedUri!!)?.use { input ->
                FileOutputStream(localFile).use { output -> input.copyTo(output) }
            }

            val outputFile = File(getExternalFilesDir(null), "telemetry_overlay.mp4")

            TelemetryOverlayRenderer.renderGreenScreenOverlay(
                context = this,
                inputFile = localFile,
                outputVideoFile = outputFile,
                onProgress = { message ->
                    runOnUiThread {
                        statusText.text = message
                    }
                },
                onComplete = { success, path ->
                    runOnUiThread {
                        if (success) {
                            statusText.text = "Success!\nSaved MP4 to:\n$path\n\nImport into CapCut and apply Chroma Key (Green)!"
                        } else {
                            statusText.text = "Error generating overlay video."
                        }
                    }
                }
            )
        } catch (e: Exception) {
            e.printStackTrace()
            statusText.text = "Error reading selected file."
        }
    }
}
