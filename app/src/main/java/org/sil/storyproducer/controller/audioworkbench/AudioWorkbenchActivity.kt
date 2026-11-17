package org.sil.storyproducer.controller.audioworkbench

import android.Manifest
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.sil.storyproducer.R
import org.sil.storyproducer.activities.BaseActivity
import java.io.File
import java.util.concurrent.Executors

class AudioWorkbenchActivity : BaseActivity() {
    private val repository by lazy {
        AudioWorkbenchRepository(File(filesDir, "audio-workbench"))
    }
    private val io = Executors.newSingleThreadExecutor()
    private var cards = emptyList<AudioWorkbenchCard>()
    private var selectedBook: String? = null
    private var activeLemma: String? = null
    private var recorder: MediaRecorder? = null
    private var temporaryRecording: File? = null
    private var player: MediaPlayer? = null

    private lateinit var language: EditText
    private lateinit var books: Spinner
    private lateinit var search: EditText
    private lateinit var coverage: TextView
    private lateinit var empty: TextView
    private lateinit var adapter: AudioWorkbenchAdapter

    private val importPlan = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importPlan(uri)
    }
    private val chooseExportFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) exportArchives(uri)
    }
    private val requestAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) pendingLemma?.let(::startRecording)
        else Toast.makeText(this, R.string.audio_workbench_permission_denied, Toast.LENGTH_LONG).show()
        pendingLemma = null
    }
    private var pendingLemma: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio_workbench)
        setSupportActionBar(findViewById<Toolbar>(R.id.audio_workbench_toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.audio_workbench_title)

        language = findViewById(R.id.audio_workbench_language)
        books = findViewById(R.id.audio_workbench_books)
        search = findViewById(R.id.audio_workbench_search)
        coverage = findViewById(R.id.audio_workbench_coverage)
        empty = findViewById(R.id.audio_workbench_empty)

        adapter = AudioWorkbenchAdapter(::toggleRecording, ::playRecording)
        findViewById<RecyclerView>(R.id.audio_workbench_list).apply {
            layoutManager = LinearLayoutManager(this@AudioWorkbenchActivity)
            adapter = this@AudioWorkbenchActivity.adapter
        }

        language.setText(getPreferences(MODE_PRIVATE).getString(LANGUAGE_KEY, ""))
        language.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveLanguage()
                true
            } else {
                false
            }
        }
        language.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveLanguage() }
        books.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedBook = books.adapter?.getItem(position) as? String
                updateRows()
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updateRows()
            override fun afterTextChanged(s: Editable?) = Unit
        })

        findViewById<Button>(R.id.audio_workbench_import).setOnClickListener {
            importPlan.launch(arrayOf("application/json", "text/*"))
        }
        findViewById<Button>(R.id.audio_workbench_export).setOnClickListener {
            if (language.text.toString().trim().isEmpty()) {
                Toast.makeText(this, R.string.audio_workbench_language_required, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (cards.none { repository.clipFile(it.lemma).isFile }) {
                Toast.makeText(this, R.string.audio_workbench_no_clips, Toast.LENGTH_LONG).show()
            } else {
                chooseExportFolder.launch(null)
            }
        }

        cards = try {
            repository.loadPlan()
        } catch (exception: Exception) {
            Toast.makeText(
                this,
                exception.message ?: getString(R.string.audio_workbench_import_failed),
                Toast.LENGTH_LONG
            ).show()
            emptyList()
        }
        refreshBooks(preferIncomplete = true)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onStop() {
        stopRecording()
        stopPlayback()
        super.onStop()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun saveLanguage() {
        getPreferences(MODE_PRIVATE).edit()
            .putString(LANGUAGE_KEY, language.text.toString().trim())
            .apply()
    }

    private fun importPlan(uri: Uri) {
        io.execute {
            try {
                val json = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: throw IllegalArgumentException(getString(R.string.audio_workbench_import_failed))
                val imported = AudioWorkbenchPlan.parse(json)
                repository.savePlan(imported)
                runOnUiThread {
                    cards = imported
                    refreshBooks(preferIncomplete = true)
                    Toast.makeText(
                        this,
                        getString(R.string.audio_workbench_imported, imported.size),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (exception: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        exception.message ?: getString(R.string.audio_workbench_import_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun exportArchives(uri: Uri) {
        val currentCards = cards
        val currentLanguage = language.text.toString().trim()
        val directory = DocumentFile.fromTreeUri(this, uri)
        if (directory?.isDirectory != true) {
            Toast.makeText(this, R.string.audio_workbench_export_failed, Toast.LENGTH_LONG).show()
            return
        }
        io.execute {
            try {
                val parts = repository.exportParts(currentCards, currentLanguage)
                val timestamp = System.currentTimeMillis()
                parts.forEachIndexed { index, part ->
                    val partName = "macula-scripta-audio-$timestamp-${(index + 1).toString().padStart(3, '0')}.zip"
                    val document = directory.createFile("application/zip", partName)
                        ?: throw IllegalStateException(getString(R.string.audio_workbench_export_failed))
                    val output = contentResolver.openOutputStream(document.uri)
                        ?: throw IllegalStateException(getString(R.string.audio_workbench_export_failed))
                    output.use {
                        repository.writeExport(part.cards, currentLanguage, it, part.firstClipNumber)
                    }
                }
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.audio_workbench_exported, parts.size),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (exception: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        exception.message ?: getString(R.string.audio_workbench_export_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun refreshBooks(preferIncomplete: Boolean) {
        val bookList = AudioWorkbenchPlan.books(cards)
        books.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, bookList)
        val preferred = if (preferIncomplete) {
            AudioWorkbenchPlan.nextIncompleteBook(cards) { repository.clipFile(it).isFile }
        } else {
            selectedBook
        }
        val index = bookList.indexOf(preferred).takeIf { it >= 0 } ?: 0
        if (bookList.isNotEmpty()) books.setSelection(index)
        else {
            selectedBook = null
            updateRows()
        }
    }

    private fun updateRows() {
        val currentBook = selectedBook
        val query = search.text.toString().trim().lowercase()
        val visibleCards = cards.filter { card ->
            card.book == currentBook &&
                (query.isEmpty() || card.lemma.lowercase().contains(query) || card.gloss.lowercase().contains(query))
        }
        val bookCards = cards.filter { it.book == currentBook }
        val completed = AudioWorkbenchPlan.coverage(bookCards) { repository.clipFile(it).isFile }
        coverage.text = getString(R.string.audio_workbench_coverage, completed.recorded, completed.total)
        empty.visibility = if (cards.isEmpty()) View.VISIBLE else View.GONE
        findViewById<RecyclerView>(R.id.audio_workbench_list).visibility =
            if (cards.isEmpty()) View.GONE else View.VISIBLE
        adapter.submitList(visibleCards.map { card ->
            AudioWorkbenchRow(
                card,
                repository.clipFile(card.lemma).isFile,
                activeLemma == card.lemma
            )
        })
    }

    private fun toggleRecording(card: AudioWorkbenchCard) {
        if (activeLemma == card.lemma) {
            stopRecording()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            startRecording(card.lemma)
        } else {
            pendingLemma = card.lemma
            requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecording(lemma: String) {
        stopRecording()
        stopPlayback()
        val output = repository.clipFile(lemma)
        val clipsDirectory = checkNotNull(output.parentFile)
        val temporary = File(clipsDirectory, "${output.nameWithoutExtension}.tmp.m4a")
        val newRecorder = MediaRecorder()
        try {
            check(clipsDirectory.mkdirs() || clipsDirectory.isDirectory)
            newRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            newRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            newRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            newRecorder.setAudioEncodingBitRate(96000)
            newRecorder.setAudioSamplingRate(44100)
            newRecorder.setAudioChannels(1)
            newRecorder.setOutputFile(temporary.absolutePath)
            newRecorder.prepare()
            newRecorder.start()
            recorder = newRecorder
            temporaryRecording = temporary
            activeLemma = lemma
            updateRows()
        } catch (exception: Exception) {
            temporary.delete()
            Toast.makeText(this, R.string.audio_workbench_recording_failed, Toast.LENGTH_LONG).show()
            newRecorder.release()
            activeLemma = null
            updateRows()
        }
    }

    private fun stopRecording() {
        val currentRecorder = recorder ?: return
        val temporary = temporaryRecording
        val lemma = activeLemma
        recorder = null
        temporaryRecording = null
        activeLemma = null
        var savedLemma: String? = null
        try {
            currentRecorder.stop()
            if (temporary != null && lemma != null) {
                temporary.copyTo(repository.clipFile(lemma), overwrite = true)
                temporary.delete()
                savedLemma = lemma
            }
        } catch (exception: Exception) {
            temporary?.delete()
            Toast.makeText(this, R.string.audio_workbench_recording_failed, Toast.LENGTH_LONG).show()
        } finally {
            currentRecorder.release()
            updateRows()
            savedLemma?.let(::advanceToNextBookIfComplete)
        }
    }

    private fun advanceToNextBookIfComplete(lemma: String) {
        val book = cards.firstOrNull { it.lemma == lemma }?.book ?: return
        val bookCards = cards.filter { it.book == book }
        if (!AudioWorkbenchPlan.coverage(bookCards) { repository.clipFile(it).isFile }.complete) return
        val nextBook = AudioWorkbenchPlan.nextIncompleteBook(cards) { repository.clipFile(it).isFile } ?: return
        val nextIndex = AudioWorkbenchPlan.books(cards).indexOf(nextBook)
        if (nextIndex >= 0 && nextBook != selectedBook) books.setSelection(nextIndex)
    }

    private fun playRecording(card: AudioWorkbenchCard) {
        stopPlayback()
        try {
            val mediaPlayer = MediaPlayer()
            player = mediaPlayer
            mediaPlayer.setDataSource(repository.clipFile(card.lemma).absolutePath)
            mediaPlayer.setOnPreparedListener {
                if (player === it) it.start()
            }
            mediaPlayer.setOnCompletionListener { stopPlayback() }
            mediaPlayer.setOnErrorListener { _, _, _ ->
                stopPlayback()
                Toast.makeText(this, R.string.audio_workbench_playback_failed, Toast.LENGTH_LONG).show()
                true
            }
            mediaPlayer.prepareAsync()
        } catch (exception: Exception) {
            stopPlayback()
            Toast.makeText(this, R.string.audio_workbench_playback_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun stopPlayback() {
        player?.run {
            if (isPlaying) stop()
            release()
        }
        player = null
    }

    companion object {
        private const val LANGUAGE_KEY = "audio_workbench_language"
    }
}
