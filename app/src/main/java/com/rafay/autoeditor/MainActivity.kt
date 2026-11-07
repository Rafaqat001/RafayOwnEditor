package com.rafay.autoeditor

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.rafay.autoeditor.engine.AutoEditPipeline
import com.rafay.autoeditor.engine.GalleryExporter
import com.rafay.autoeditor.engine.VideoAnalyzer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var btnUpload: Button; private lateinit var btnAuto: Button
    private lateinit var btnPreview: Button; private lateinit var btnSave: Button
    private lateinit var tvInfo: TextView; private lateinit var tvStatus: TextView; private lateinit var tvLog: TextView
    private lateinit var progress: ProgressBar; private lateinit var videoView: VideoView

    private var inputUri: Uri? = null
    private var output: File? = null
    private var saved = false
    private var job: Job? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) onPicked(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        btnUpload = findViewById(R.id.btnUpload); btnAuto = findViewById(R.id.btnAuto)
        btnPreview = findViewById(R.id.btnPreview); btnSave = findViewById(R.id.btnSave)
        tvInfo = findViewById(R.id.tvVideoInfo); tvStatus = findViewById(R.id.tvStatus); tvLog = findViewById(R.id.tvLog)
        progress = findViewById(R.id.progress); videoView = findViewById(R.id.videoView)

        btnUpload.setOnClickListener { picker.launch(arrayOf("video/*")) }
        btnAuto.setOnClickListener { startEdit() }
        btnPreview.setOnClickListener { preview() }
        btnSave.setOnClickListener { saveToGallery() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }
    }

    private fun onPicked(uri: Uri) {
        lifecycleScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { VideoAnalyzer.probe(applicationContext, uri) }
                inputUri = uri
                tvInfo.text = "Duration: %d:%02d  (%dx%d)".format((info.durationSec / 60).toInt(), (info.durationSec % 60).toInt(), info.width, info.height)
                if (info.durationSec > 181) { tvInfo.append("\nToo long: please use a video under 3 minutes."); btnAuto.isEnabled = false }
                else btnAuto.isEnabled = true
                btnPreview.isEnabled = false; btnSave.isEnabled = false; videoView.visibility = View.GONE
            } catch (e: Throwable) { tvInfo.text = "Cannot read video: ${e.message}"; btnAuto.isEnabled = false }
        }
    }

    private fun startEdit() {
        val uri = inputUri ?: return
        if (job?.isActive == true) { job?.cancel(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        btnAuto.text = "CANCEL"; btnUpload.isEnabled = false; btnPreview.isEnabled = false; btnSave.isEnabled = false
        tvLog.text = ""; progress.progress = 0; saved = false
        job = lifecycleScope.launch {
            try {
                val f = AutoEditPipeline(applicationContext).run(
                    uri, Prefs(this@MainActivity).load(),
                    stage = { runOnUiThread { tvStatus.text = it } },
                    progress = { runOnUiThread { progress.progress = it } },
                    log = { runOnUiThread { tvLog.append(it + "\n") } })
                output = f
                tvStatus.text = "Done. Saving to gallery..."
                withContext(Dispatchers.IO) { GalleryExporter.save(applicationContext, f) }
                saved = true
                tvStatus.text = "Done! Saved in Gallery > Movies/Rafay Own Editor"
                btnPreview.isEnabled = true; btnSave.isEnabled = true
                preview()
            } catch (e: CancellationException) { tvStatus.text = "Cancelled"; throw e }
            catch (e: Throwable) { tvStatus.text = "Error: ${e.message}" }
            finally {
                btnAuto.text = "AUTO EDIT"; btnUpload.isEnabled = true
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    private fun preview() {
        val f = output ?: return
        videoView.visibility = View.VISIBLE
        videoView.setMediaController(MediaController(this).also { it.setAnchorView(videoView) })
        videoView.setVideoURI(Uri.fromFile(f)); videoView.start()
    }

    private fun saveToGallery() {
        val f = output ?: return
        if (saved) { Toast.makeText(this, "Already saved in Gallery", Toast.LENGTH_SHORT).show(); return }
        lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { GalleryExporter.save(applicationContext, f) }; saved = true
                Toast.makeText(this@MainActivity, "Saved to Gallery", Toast.LENGTH_SHORT).show()
            } catch (e: Throwable) { Toast.makeText(this@MainActivity, "Save failed: ${e.message}", Toast.LENGTH_LONG).show() }
        }
    }
}
