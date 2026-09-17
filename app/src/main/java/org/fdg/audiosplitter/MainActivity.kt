package org.fdg.audiosplitter

import android.app.Activity
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import org.fdg.audiosplitter.databinding.ActivityMainBinding
import java.io.File
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var pickedUri: Uri? = null
    private var pickedFileName: String = "audio"
    private var decodedAudio: DecodedAudio? = null
    private var intervalSec: Int = 10

    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null

    private val filePickerLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri -> onFilePicked(uri) }
            }
        }

    private val folderPickerLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { treeUri -> doExport(treeUri) }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSelectFile.setOnClickListener { pickFile() }
        binding.btnPlay.setOnClickListener { togglePlay() }
        binding.btnPreview.setOnClickListener { previewSplit() }
        binding.btnExport.setOnClickListener { pickExportFolder() }

        binding.seekInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                intervalSec = progress + 5
                binding.tvInterval.text = "$intervalSec sec"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.tvInterval.text = "$intervalSec sec"
    }

    // ---------------- File picking ----------------
    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        filePickerLauncher.launch(intent)
    }

    private fun onFilePicked(uri: Uri) {
        pickedUri = uri
        pickedFileName = queryFileName(uri)
        binding.tvFileName.text = pickedFileName
        binding.btnPlay.isEnabled = false
        binding.btnPreview.isEnabled = false
        binding.btnExport.isEnabled = false
        binding.tvPreview.text = "Decoding audio, please wait..."

        thread {
            try {
                val audio = AudioEngine.decode(this, uri)
                decodedAudio = audio
                val envelope = AudioEngine.buildEnvelope(audio)

                handler.post {
                    binding.waveform.setSamples(envelope)
                    binding.btnPlay.isEnabled = true
                    binding.btnPreview.isEnabled = true
                    binding.btnExport.isEnabled = true
                    setupPlayer(uri)
                    previewSplit()
                }
            } catch (e: Exception) {
                handler.post {
                    binding.tvPreview.text = ""
                    AlertDialog.Builder(this)
                        .setTitle("Could not load audio")
                        .setMessage(e.message ?: "Unknown error")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun queryFileName(uri: Uri): String {
        var name = "audio"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(idx)
            }
        }
        return name
    }

    // ---------------- Playback ----------------
    private fun setupPlayer(uri: Uri) {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(this@MainActivity, uri)
            prepare()
            setOnCompletionListener {
                binding.btnPlay.text = "Play"
                stopProgressUpdates()
                binding.waveform.setProgress(0f)
            }
        }
    }

    private fun togglePlay() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) {
            mp.pause()
            binding.btnPlay.text = "Play"
            stopProgressUpdates()
        } else {
            mp.start()
            binding.btnPlay.text = "Pause"
            startProgressUpdates()
        }
    }

    private fun startProgressUpdates() {
        stopProgressUpdates()
        updateRunnable = object : Runnable {
            override fun run() {
                val mp = mediaPlayer ?: return
                val dur = mp.duration.coerceAtLeast(1)
                val pos = mp.currentPosition
                binding.waveform.setProgress(pos.toFloat() / dur)
                binding.tvTime.text = "${fmt(pos / 1000)} / ${fmt(dur / 1000)}"
                handler.postDelayed(this, 150)
            }
        }
        handler.post(updateRunnable!!)
    }

    private fun stopProgressUpdates() {
        updateRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun fmt(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return String.format("%02d:%02d", m, s)
    }

    // ---------------- Split preview / export ----------------
    private fun previewSplit() {
        val audio = decodedAudio ?: return
        val total = audio.durationSec
        val nClips = Math.ceil(total / intervalSec).toInt().coerceAtLeast(1)
        binding.tvPreview.text = "Length: ${fmt(total.toInt())}  ->  Will create $nClips clip(s) of ${intervalSec}s each."
    }

    private fun pickExportFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        folderPickerLauncher.launch(intent)
    }

    private fun doExport(treeUri: Uri) {
        val audio = decodedAudio ?: return
        val baseName = pickedFileName.substringBeforeLast(".")

        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.progressBar.progress = 0
        binding.btnExport.isEnabled = false

        thread {
            try {
                val tempDir = File(cacheDir, "splits").apply { mkdirs() }
                // clear any old temp files
                tempDir.listFiles()?.forEach { it.delete() }

                val files = AudioEngine.splitToWavFiles(audio, intervalSec, tempDir, baseName) { done, totalCount ->
                    handler.post {
                        binding.progressBar.max = totalCount
                        binding.progressBar.progress = done
                    }
                }

                val destDir = DocumentFile.fromTreeUri(this, treeUri)
                    ?: throw RuntimeException("Could not access chosen folder.")

                for (f in files) {
                    val doc = destDir.createFile("audio/wav", f.name)
                        ?: throw RuntimeException("Could not create ${f.name}")
                    contentResolver.openOutputStream(doc.uri)?.use { out ->
                        f.inputStream().use { input -> input.copyTo(out) }
                    }
                    f.delete()
                }

                handler.post {
                    binding.progressBar.visibility = android.view.View.GONE
                    binding.btnExport.isEnabled = true
                    AlertDialog.Builder(this)
                        .setTitle("Done")
                        .setMessage("Exported ${files.size} clip(s) successfully.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            } catch (e: Exception) {
                handler.post {
                    binding.progressBar.visibility = android.view.View.GONE
                    binding.btnExport.isEnabled = true
                    Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
    }
}
