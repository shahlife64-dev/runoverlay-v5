package com.example.telemetryoverlay

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private var selectedGpxUri: Uri? = null
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
            if (selectedGpxUri == null) {
                Toast.makeText(this, "Please select a GPX/FIT file first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            processTelemetryOverlay()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode: Int, resultCode: data)
        if (requestCode == 1001 && resultCode == Activity.RESULT_OK) {
            selectedGpxUri = data?.data
            statusText.text = "Selected File: ${selectedGpxUri?.path}"
        }
    }

    private fun processTelemetryOverlay() {
        statusText.text = "Generating Overlay Video with Chroma Green Background..."
        
        val gpxFile = File(cacheDir, "input.gpx")
        contentResolver.openInputStream(selectedGpxUri!!)?.use { input ->
            FileOutputStream(gpxFile).use { output -> input.copyTo(output) }
        }

        val outputFile = File(getExternalFilesDir(null), "telemetry_overlay.mp4")

        TelemetryOverlayRenderer.renderGreenScreenOverlay(
            context = this,
            gpxFile = gpxFile,
            outputVideoFile = outputFile
        ) { success, path ->
            runOnUiThread {
                if (success) {
                    statusText.text = "Success! Saved to:\n$path\n\nImport into CapCut and apply 'Chroma Key' on Green!"
                } else {
                    statusText.text = "Failed to render video."
                }
            }
        }
    }
}
