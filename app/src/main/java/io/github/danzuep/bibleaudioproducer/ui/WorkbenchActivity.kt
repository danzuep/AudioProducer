package io.github.danzuep.bibleaudioproducer.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import io.github.danzuep.bibleaudioproducer.R
import io.github.danzuep.bibleaudioproducer.audio.AndroidAudioPlayer
import io.github.danzuep.bibleaudioproducer.audio.AndroidAudioRecorder
import io.github.danzuep.bibleaudioproducer.audio.AndroidAudioDecoder
import io.github.danzuep.bibleaudioproducer.audio.AndroidAudioEncoder
import io.github.danzuep.bibleaudioproducer.audio.AndroidNeuralSmoother
import io.github.danzuep.bibleaudioproducer.audio.NeuralModelStore
import io.github.danzuep.bibleaudioproducer.audio.NeuralStrength
import io.github.danzuep.bibleaudioproducer.audio.VerseAudio
import io.github.danzuep.bibleaudioproducer.audio.VerseExportFormat
import io.github.danzuep.bibleaudioproducer.data.RecordingPlanJson
import io.github.danzuep.bibleaudioproducer.data.AudioProjectRepository
import io.github.danzuep.bibleaudioproducer.data.MaculaVerseJson
import io.github.danzuep.bibleaudioproducer.workbench.RecordingCard
import io.github.danzuep.bibleaudioproducer.workbench.AudioVerse
import io.github.danzuep.bibleaudioproducer.workbench.AudioProjectState
import io.github.danzuep.bibleaudioproducer.workbench.VerseWorkflow
import io.github.danzuep.bibleaudioproducer.workbench.RecordingPlan
import java.io.File
import java.util.concurrent.Executors

class WorkbenchActivity : AppCompatActivity() {
    private val repository by lazy {
        AudioProjectRepository(File(filesDir, "audio-project"))
    }
    private val io = Executors.newSingleThreadExecutor()
    private val preferences by lazy { getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE) }
    private val audioRecorder by lazy { AndroidAudioRecorder(this) }
    private val neuralModels by lazy { NeuralModelStore(File(filesDir, "audio-models")) }
    private val audioPlayer by lazy {
        AndroidAudioPlayer {
            playingLemma = null
            updateRows()
            Toast.makeText(this, R.string.audio_workbench_playback_failed, Toast.LENGTH_LONG).show()
        }
    }
    private val verseDialogs by lazy {
        VerseDialogs(this, repository, object : VerseDialogs.Actions {
            override val state get() = projectState
            override val busy get() = this@WorkbenchActivity.busy
            override fun playVerse(verse: AudioVerse) = this@WorkbenchActivity.playVerse(verse)
            override fun downloadVerse(verse: AudioVerse, strength: NeuralStrength?, format: VerseExportFormat) =
                this@WorkbenchActivity.downloadVerse(verse, strength, format)
            override fun saveOrder(reference: String, order: List<Int>?, unspoken: Set<String>) =
                saveVerseOrder(reference, order, unspoken)
            override fun toggleRecording(card: RecordingCard) = this@WorkbenchActivity.toggleRecording(card)
            override fun pressRecording(card: RecordingCard) = pressRecord(card)
            override fun releaseRecording(card: RecordingCard) = releaseRecord(card)
            override fun cancelRecording(card: RecordingCard) = cancelRecord(card)
            override fun isRecording(card: RecordingCard) = activeLemma == card.lemma
            override fun isPlaying(card: RecordingCard) = playingLemma == card.lemma
            override fun playRecording(card: RecordingCard) = this@WorkbenchActivity.playRecording(card)
            override fun closeRecording() {
                stopRecording()
                stopPlayback()
            }
        })
    }
    private var cards = emptyList<RecordingCard>()
    private var projectState = AudioProjectState()
    private var selectedBook: String? = null
    private var bookCards = emptyList<RecordingCard>()
    private var bookVerses = emptyList<AudioVerse>()
    private val verseCache = mutableMapOf<String, List<AudioVerse>>()
    private var step = Step.LANGUAGE
    private var busy = false
    private var activeLemma: String? = null
    private var playingLemma: String? = null
    private val waveformHandler = Handler(Looper.getMainLooper())
    private val waveformSampler = object : Runnable {
        override fun run() {
            val lemma = activeLemma ?: return
            try {
                val levels = adapter.addLevel(lemma, audioRecorder.amplitude())
                verseDialogs.showWaveform(lemma, levels)
                waveformHandler.postDelayed(this, 100)
            } catch (exception: IllegalStateException) {
                stopRecording()
                Toast.makeText(this@WorkbenchActivity, R.string.audio_workbench_recording_failed, Toast.LENGTH_LONG).show()
            }
        }
    }
    private var reviewDialog: AlertDialog? = null
    private data class VerseExport(
        val reference: String, val lemmas: List<String>, val missing: Int,
        val strength: NeuralStrength?, val format: VerseExportFormat
    )
    private var pendingVerseExport: VerseExport? = null

    private lateinit var language: EditText
    private lateinit var books: Spinner
    private lateinit var search: EditText
    private lateinit var coverage: TextView
    private lateinit var empty: TextView
    private lateinit var adapter: RecordingCardAdapter

    private val importPlan = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importPlan(uri)
    }
    private val importArchive = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importArchive(uri)
    }
    private val importNeuralModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importNeuralModel(uri)
    }
    private val chooseExportFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) exportArchives(uri)
    }
    private val saveVerseWav = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) {
        completeVerseSave(it)
    }
    private val saveVerseAac = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/mp4")) {
        completeVerseSave(it)
    }
    private val saveVerseOpus = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/ogg")) {
        completeVerseSave(it)
    }

    private fun completeVerseSave(uri: Uri?) {
        val export = pendingVerseExport
        pendingVerseExport = null
        if (uri != null && export != null) exportVerse(uri, export)
        else if (uri != null) Toast.makeText(this, R.string.audio_download_failed, Toast.LENGTH_LONG).show()
    }
    private val requestAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) Toast.makeText(this, R.string.audio_permission_retry, Toast.LENGTH_LONG).show()
        else Toast.makeText(this, R.string.audio_workbench_permission_denied, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pendingReference = savedInstanceState?.getString("verse_export_reference")
        val pendingLemmas = savedInstanceState?.getStringArrayList("verse_export_lemmas")
        if (pendingReference != null && pendingLemmas != null) {
            pendingVerseExport = VerseExport(
                pendingReference, pendingLemmas, savedInstanceState?.getInt("verse_export_missing") ?: 0,
                savedInstanceState?.getString("verse_export_strength")?.let(NeuralStrength::valueOf),
                savedInstanceState?.getString("verse_export_format")?.let(VerseExportFormat::valueOf)
                    ?: VerseExportFormat.WAV
            )
        }
        setContentView(R.layout.activity_audio_workbench)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.audio_workbench_root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setSupportActionBar(findViewById<Toolbar>(R.id.audio_workbench_toolbar))
        supportActionBar?.title = getString(R.string.audio_workbench_title)

        language = findViewById(R.id.audio_workbench_language)
        books = findViewById(R.id.audio_workbench_books)
        search = findViewById(R.id.audio_workbench_search)
        coverage = findViewById(R.id.audio_workbench_coverage)
        empty = findViewById(R.id.audio_workbench_empty)

        adapter = RecordingCardAdapter(
            ::pressRecord, ::releaseRecord, ::cancelRecord, ::toggleRecording, ::playRecording
        )
        findViewById<RecyclerView>(R.id.audio_workbench_list).apply {
            layoutManager = LinearLayoutManager(this@WorkbenchActivity)
            (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
            adapter = this@WorkbenchActivity.adapter
        }

        language.setText(preferences.getString(LANGUAGE_KEY, ""))
        language.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                continueToBooks()
                true
            } else {
                false
            }
        }
        language.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveLanguage() }
        findViewById<Button>(R.id.audio_step_language).setOnClickListener { showStep(Step.LANGUAGE) }
        findViewById<Button>(R.id.audio_step_books).setOnClickListener { showStep(Step.BOOKS) }
        findViewById<Button>(R.id.audio_step_export).setOnClickListener { showStep(Step.EXPORT) }
        findViewById<Button>(R.id.audio_step_settings).setOnClickListener { showStep(Step.SETTINGS) }
        findViewById<Button>(R.id.audio_language_next).setOnClickListener { continueToBooks() }
        books.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedBook = books.adapter?.getItem(position) as? String
                bookCards = cards.filter { it.book == selectedBook }
                bookVerses = emptyList()
                updateRows()
                selectedBook?.let(::loadBook)
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updateRows()
            override fun afterTextChanged(s: Editable?) = Unit
        })

        findViewById<Button>(R.id.audio_workbench_import).setOnClickListener {
            if (busy) return@setOnClickListener
            stopRecording()
            stopPlayback()
            if (pendingTake() != null) return@setOnClickListener
            importPlan.launch(arrayOf("application/json", "text/*"))
        }
        findViewById<Button>(R.id.audio_workbench_export).setOnClickListener {
            if (busy) return@setOnClickListener
            if (language.text.toString().trim().isEmpty()) {
                Toast.makeText(this, R.string.audio_workbench_language_required, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (repository.recordedCards(cards).isEmpty()) {
                Toast.makeText(this, R.string.audio_workbench_no_clips, Toast.LENGTH_LONG).show()
            } else {
                chooseExportFolder.launch(null)
            }
        }
        findViewById<Button>(R.id.audio_import_archive).setOnClickListener {
            if (busy) return@setOnClickListener
            stopRecording()
            stopPlayback()
            if (pendingTake() != null) return@setOnClickListener
            importArchive.launch(arrayOf("application/zip", "application/octet-stream"))
        }
        findViewById<Button>(R.id.audio_clear_data).setOnClickListener { confirmClearData() }
        findViewById<Button>(R.id.audio_import_neural_model).setOnClickListener {
            if (busy) return@setOnClickListener
            if (Build.VERSION.SDK_INT < 24) {
                Toast.makeText(this, R.string.audio_neural_version, Toast.LENGTH_LONG).show()
            } else {
                importNeuralModel.launch(arrayOf("application/octet-stream", "*/*"))
            }
        }
        findViewById<Button>(R.id.audio_listen).setOnClickListener { showChapters() }
        findViewById<Button>(R.id.audio_listen_stop).setOnClickListener { stopPlayback() }
        findViewById<Button>(R.id.audio_attribution).setOnClickListener {
            AlertDialog.Builder(this).setTitle(R.string.audio_attribution)
                .setMessage(R.string.audio_data_attribution)
                .setNeutralButton(R.string.audio_view_license) { _, _ ->
                    showLicense("MaculaScripta-LICENSE.txt", R.string.audio_view_license)
                }
                .setNegativeButton(R.string.audio_runtime_license) { _, _ ->
                    showLicense("ONNX-Runtime-LICENSE.txt", R.string.audio_runtime_license)
                }
                .setPositiveButton(android.R.string.ok, null).show()
        }

        showStep(if (language.text.toString().isBlank()) Step.LANGUAGE else Step.BOOKS)
        io.execute {
            try {
                val loaded = repository.loadPlan().ifEmpty {
                    assets.open("recording-plan.json").bufferedReader().use {
                        RecordingPlanJson.parse(it.readText())
                    }
                }
                val loadedState = repository.loadState()
                runOnUiThread {
                    cards = loaded
                    projectState = loadedState
                    refreshBooks(preferIncomplete = true)
                }
            } catch (exception: Exception) {
                runOnUiThread {
                    empty.setText(R.string.audio_workbench_load_failed)
                    Toast.makeText(
                        this,
                        exception.message ?: getString(R.string.audio_workbench_load_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        reviewPendingTake()
    }

    override fun onStop() {
        stopRecording(allowReview = false)
        stopPlayback()
        super.onStop()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingVerseExport?.let {
            outState.putString("verse_export_reference", it.reference)
            outState.putStringArrayList("verse_export_lemmas", ArrayList(it.lemmas))
            outState.putInt("verse_export_missing", it.missing)
            outState.putString("verse_export_strength", it.strength?.name)
            outState.putString("verse_export_format", it.format.name)
        }
        super.onSaveInstanceState(outState)
    }

    private fun saveLanguage() {
        preferences.edit()
            .putString(LANGUAGE_KEY, language.text.toString().trim())
            .apply()
    }

    private fun showLicense(asset: String, title: Int) {
        try {
            val license = assets.open(asset).bufferedReader().use { it.readText() }
            AlertDialog.Builder(this).setTitle(title).setMessage(license)
                .setPositiveButton(android.R.string.ok, null).show()
        } catch (exception: Exception) {
            Toast.makeText(this, R.string.audio_license_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun continueToBooks() {
        if (language.text.toString().isBlank()) {
            Toast.makeText(this, R.string.audio_workbench_language_required, Toast.LENGTH_LONG).show()
        } else {
            saveLanguage()
            showStep(Step.BOOKS)
        }
    }

    private fun showStep(next: Step) {
        stopRecording()
        stopPlayback()
        if (next != Step.LANGUAGE) {
            language.clearFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(language.windowToken, 0)
        }
        if (step != next) search.text.clear()
        step = next
        findViewById<View>(R.id.audio_language_panel).visibility = if (next == Step.LANGUAGE) View.VISIBLE else View.GONE
        findViewById<View>(R.id.audio_export_panel).visibility = if (next == Step.EXPORT) View.VISIBLE else View.GONE
        findViewById<View>(R.id.audio_settings_panel).visibility = if (next == Step.SETTINGS) View.VISIBLE else View.GONE
        books.visibility = if (next == Step.BOOKS) View.VISIBLE else View.GONE
        coverage.visibility = if (next == Step.BOOKS) View.VISIBLE else View.GONE
        findViewById<View>(R.id.audio_listen_controls).visibility = if (next == Step.BOOKS) View.VISIBLE else View.GONE
        updateRows()
    }

    private fun loadBook(book: String) {
        io.execute {
            try {
                val verses = versesFor(book)
                runOnUiThread {
                    if (selectedBook == book) {
                        bookVerses = verses
                        bookCards = VerseWorkflow.chapterOrder(cards.filter { it.book == book }, verses)
                        updateRows()
                    }
                }
            } catch (exception: Exception) {
                runOnUiThread {
                    if (selectedBook == book) {
                        Toast.makeText(this, R.string.audio_verses_load_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun versesFor(book: String): List<AudioVerse> = verseCache.getOrPut(book) {
        assets.open("books/$book.json").bufferedReader().use { MaculaVerseJson.parse(it.readText()) }
    }

    private fun showChapters() {
        search.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(search.windowToken, 0)
        verseDialogs.showChapters(bookVerses)
    }

    private fun saveVerseOrder(reference: String, order: List<Int>?, unspoken: Set<String>) {
        busy = true
        val orders = if (order == null) projectState.seedOrders - reference
            else projectState.seedOrders + (reference to order)
        io.execute {
            try {
                val examples = orders.map { (verseRef, savedOrder) ->
                    val verse = versesFor(verseRef.substringBefore(' '))
                        .firstOrNull { it.reference == verseRef }
                        ?: throw IllegalArgumentException(getString(R.string.audio_verses_load_failed))
                    VerseWorkflow.chunks(verse.words) to savedOrder
                }
                val updated = AudioProjectState(VerseWorkflow.learnRules(examples), orders, unspoken)
                repository.saveState(updated)
                runOnUiThread {
                    busy = false
                    projectState = updated
                    updateRows()
                    Toast.makeText(this, R.string.audio_order_saved, Toast.LENGTH_SHORT).show()
                }
            } catch (exception: Exception) {
                runOnUiThread {
                    busy = false
                    Toast.makeText(
                        this, exception.message ?: getString(R.string.audio_order_failed), Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun playVerse(verse: AudioVerse) {
        if (busy) return
        stopRecording()
        stopPlayback()
        val spoken = VerseWorkflow.spokenWords(
            verse.words, projectState.rules, projectState.seedOrders[verse.reference], projectState.unspoken
        )
        val words = spoken.filter { repository.clipFile(it.lemma).isFile }
        if (words.isEmpty()) {
            Toast.makeText(this, R.string.audio_word_not_recorded, Toast.LENGTH_SHORT).show()
            return
        }
        if (words.size != spoken.size) {
            Toast.makeText(this, R.string.audio_playback_missing_words, Toast.LENGTH_SHORT).show()
        }
        if (pendingTake() == null) audioPlayer.play(words.map { repository.clipFile(it.lemma) })
    }

    private fun downloadVerse(verse: AudioVerse, strength: NeuralStrength?, format: VerseExportFormat) {
        if (busy) return
        stopRecording()
        stopPlayback()
        if (pendingTake() != null) return
        if (strength != null && Build.VERSION.SDK_INT < 24) {
            Toast.makeText(this, R.string.audio_neural_version, Toast.LENGTH_LONG).show()
            return
        }
        if (strength != null && !neuralModels.isInstalled()) {
            AlertDialog.Builder(this).setTitle(R.string.audio_neural_model_title)
                .setMessage(R.string.audio_neural_model_required)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.audio_neural_import) { _, _ ->
                    importNeuralModel.launch(arrayOf("application/octet-stream", "*/*"))
                }.show()
            return
        }
        val words = VerseWorkflow.spokenWords(
            verse.words, projectState.rules, projectState.seedOrders[verse.reference], projectState.unspoken
        )
        val recorded = words.filter { repository.clipFile(it.lemma).isFile }
        if (recorded.isEmpty()) {
            Toast.makeText(this, R.string.audio_word_not_recorded, Toast.LENGTH_LONG).show()
            return
        }
        pendingVerseExport = VerseExport(
            verse.reference, recorded.map { it.lemma }, words.size - recorded.size, strength, format
        )
        val name = "${verse.reference.replace(Regex("[^A-Za-z0-9]+"), "-")}.${format.extension}"
        when (format) {
            VerseExportFormat.WAV -> saveVerseWav.launch(name)
            VerseExportFormat.AAC -> saveVerseAac.launch(name)
            VerseExportFormat.OPUS -> saveVerseOpus.launch(name)
        }
    }

    private fun exportVerse(uri: Uri, export: VerseExport) {
        busy = true
        Toast.makeText(this, R.string.audio_download_preparing, Toast.LENGTH_SHORT).show()
        io.execute {
            try {
                val decoder = AndroidAudioDecoder()
                val decoded = mutableMapOf<String, FloatArray>()
                var sampleCount = 0L
                val clips = export.lemmas.map { lemma ->
                    decoded.getOrPut(lemma) { decoder.decode(repository.clipFile(lemma)) }.also {
                        sampleCount += it.size
                        require(sampleCount <= VerseAudio.SAMPLE_RATE * 180L) {
                            getString(R.string.audio_download_too_long)
                        }
                    }
                }
                val assembled = VerseAudio.assemble(clips)
                val samples = export.strength?.let {
                    AndroidNeuralSmoother().smooth(assembled, neuralModels.modelFile, it)
                } ?: assembled
                if (export.format == VerseExportFormat.WAV) {
                    val wav = VerseAudio.encodeWav(samples)
                    val output = contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException(getString(R.string.audio_download_failed))
                    output.use { it.write(wav) }
                } else {
                    val temporary = File.createTempFile("verse-export-", ".${export.format.extension}", cacheDir)
                    try {
                        AndroidAudioEncoder().encode(samples, temporary, export.format)
                        val output = contentResolver.openOutputStream(uri)
                            ?: throw IllegalStateException(getString(R.string.audio_download_failed))
                        output.use { destination -> temporary.inputStream().use { it.copyTo(destination) } }
                    } finally {
                        temporary.delete()
                    }
                }
                runOnUiThread {
                    busy = false
                    Toast.makeText(
                        this,
                        if (export.missing == 0) getString(R.string.audio_download_complete, export.reference)
                        else getString(R.string.audio_download_incomplete, export.reference, export.missing),
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (exception: Exception) {
                runOnUiThread {
                    busy = false
                    Toast.makeText(
                        this, exception.message ?: getString(R.string.audio_download_failed), Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun importNeuralModel(uri: Uri) {
        busy = true
        io.execute {
            try {
                val input = contentResolver.openInputStream(uri)
                    ?: throw IllegalArgumentException(getString(R.string.audio_neural_import_failed))
                input.use { neuralModels.install(it) }
                runOnUiThread {
                    busy = false
                    Toast.makeText(this, R.string.audio_neural_imported, Toast.LENGTH_LONG).show()
                }
            } catch (exception: Exception) {
                runOnUiThread {
                    busy = false
                    Toast.makeText(
                        this, exception.message ?: getString(R.string.audio_neural_import_failed), Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun importArchive(uri: Uri) {
        busy = true
        val languageName = language.text.toString().trim()
        val state = projectState
        io.execute {
            try {
                val result = contentResolver.openInputStream(uri)?.use {
                    repository.importArchive(it, languageName, state)
                } ?: throw IllegalArgumentException(getString(R.string.audio_archive_import_failed))
                repository.saveState(result.state)
                runOnUiThread {
                    busy = false
                    projectState = result.state
                    language.setText(result.language)
                    saveLanguage()
                    refreshBooks(preferIncomplete = true)
                    Toast.makeText(
                        this, getString(R.string.audio_archive_imported, result.added, result.skipped),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (exception: Exception) {
                runOnUiThread {
                    busy = false
                    Toast.makeText(
                        this, exception.message ?: getString(R.string.audio_archive_import_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun confirmClearData() {
        if (busy) return
        AlertDialog.Builder(this)
            .setTitle(R.string.audio_clear_title)
            .setMessage(R.string.audio_clear_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.audio_clear_confirm) { _, _ ->
                stopRecording()
                stopPlayback()
                busy = true
                io.execute {
                    try {
                        check(!File(filesDir, "audio-project").exists() ||
                            File(filesDir, "audio-project").deleteRecursively()) {
                            getString(R.string.audio_clear_failed)
                        }
                        val defaultCards = assets.open("recording-plan.json").bufferedReader().use {
                            RecordingPlanJson.parse(it.readText())
                        }
                        check(preferences.edit().remove(PENDING_TAKE_KEY).commit()) {
                            getString(R.string.audio_clear_failed)
                        }
                        runOnUiThread {
                            busy = false
                            language.setText("")
                            saveLanguage()
                            cards = defaultCards
                            projectState = AudioProjectState()
                            refreshBooks(preferIncomplete = true)
                            showStep(Step.LANGUAGE)
                        }
                    } catch (exception: Exception) {
                        runOnUiThread {
                            busy = false
                            Toast.makeText(
                                this, exception.message ?: getString(R.string.audio_clear_failed),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
            .show()
    }

    private fun importPlan(uri: Uri) {
        busy = true
        io.execute {
            try {
                val json = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: throw IllegalArgumentException(getString(R.string.audio_workbench_import_failed))
                val imported = RecordingPlanJson.parse(json)
                repository.savePlan(imported)
                runOnUiThread {
                    busy = false
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
                    busy = false
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
        val currentState = projectState
        val directory = DocumentFile.fromTreeUri(this, uri)
        if (directory?.isDirectory != true) {
            Toast.makeText(this, R.string.audio_workbench_export_failed, Toast.LENGTH_LONG).show()
            return
        }
        busy = true
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
                        repository.writeExport(part.cards, currentLanguage, it, part.firstClipNumber, currentState)
                    }
                }
                runOnUiThread {
                    busy = false
                    Toast.makeText(
                        this,
                        getString(R.string.audio_workbench_exported, parts.size),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (exception: Exception) {
                runOnUiThread {
                    busy = false
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
        val bookList = RecordingPlan.books(cards)
        books.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, bookList)
        val preferred = if (preferIncomplete) {
            RecordingPlan.nextIncompleteBook(cards) { it in projectState.unspoken || repository.clipFile(it).isFile }
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
        val requiredCards = bookCards.filter { it.lemma !in projectState.unspoken }
        val visibleCards = if (step == Step.SETTINGS) {
            val term = search.text.toString().trim()
            repository.recordedCards(cards).filter {
                term.isEmpty() || it.lemma.contains(term, ignoreCase = true) ||
                    it.gloss.contains(term, ignoreCase = true)
            }
        } else {
            RecordingPlan.search(requiredCards, currentBook, search.text.toString())
        }
        val completed = RecordingPlan.coverage(requiredCards) { repository.clipFile(it).isFile }
        coverage.text = getString(R.string.audio_workbench_coverage, completed.recorded, completed.total)
        val noPlan = cards.isEmpty()
        val showList = step == Step.BOOKS || step == Step.SETTINGS
        findViewById<View>(R.id.audio_workbench_controls).visibility =
            if (!noPlan && showList) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.audio_workbench_export).isEnabled = !noPlan
        findViewById<Button>(R.id.audio_listen).isEnabled = bookVerses.isNotEmpty()
        empty.setText(if (noPlan) R.string.audio_workbench_empty else R.string.audio_workbench_no_results)
        empty.visibility = if (showList && (noPlan || visibleCards.isEmpty())) View.VISIBLE else View.GONE
        findViewById<RecyclerView>(R.id.audio_workbench_list).visibility =
            if (showList && !noPlan && visibleCards.isNotEmpty()) View.VISIBLE else View.GONE
        adapter.submitList(visibleCards.map { card ->
            RecordingCardRow(
                card,
                repository.clipFile(card.lemma).isFile,
                activeLemma == card.lemma,
                playingLemma == card.lemma
            )
        })
        verseDialogs.refreshControls()
    }

    private fun toggleRecording(card: RecordingCard) {
        if (busy) return
        if (activeLemma == card.lemma) {
            stopRecording()
            return
        }
        pressRecord(card)
    }

    private fun pressRecord(card: RecordingCard) {
        if (busy || activeLemma == card.lemma) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            startRecording(card.lemma)
        } else {
            requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun releaseRecord(card: RecordingCard) {
        if (activeLemma == card.lemma) stopRecording()
    }

    private fun cancelRecord(card: RecordingCard) {
        if (activeLemma != card.lemma) return
        activeLemma = null
        waveformHandler.removeCallbacks(waveformSampler)
        try {
            val candidate = audioRecorder.stop()
            check(candidate.delete()) { getString(R.string.audio_review_failed) }
            Toast.makeText(this, R.string.audio_recording_cancelled, Toast.LENGTH_SHORT).show()
        } catch (exception: Exception) {
            Toast.makeText(this, exception.message ?: getString(R.string.audio_workbench_recording_failed), Toast.LENGTH_LONG).show()
        } finally {
            updateRows()
        }
    }

    private fun startRecording(lemma: String) {
        if (pendingTake() != null) {
            reviewPendingTake()
            return
        }
        stopRecording()
        if (pendingTake() != null) return
        stopPlayback()
        try {
            val candidate = repository.recordingCandidateFile(lemma)
            if (candidate.isFile) {
                check(preferences.edit().putString(PENDING_TAKE_KEY, lemma).commit()) {
                    getString(R.string.audio_review_failed)
                }
                reviewPendingTake()
                return
            }
            audioRecorder.start(candidate)
            activeLemma = lemma
            adapter.clearWaveform()
            updateRows()
            waveformHandler.post(waveformSampler)
        } catch (exception: Exception) {
            Toast.makeText(this, R.string.audio_workbench_recording_failed, Toast.LENGTH_LONG).show()
            activeLemma = null
            updateRows()
        }
    }

    private fun stopRecording(allowReview: Boolean = true) {
        val lemma = activeLemma ?: return
        activeLemma = null
        waveformHandler.removeCallbacks(waveformSampler)
        try {
            audioRecorder.stop()
            check(preferences.edit().putString(PENDING_TAKE_KEY, lemma).commit()) {
                getString(R.string.audio_review_failed)
            }
            if (repository.clipFile(lemma).isFile) {
                if (allowReview) reviewPendingTake()
            } else {
                repository.commitRecording(lemma)
                check(preferences.edit().remove(PENDING_TAKE_KEY).commit()) {
                    getString(R.string.audio_review_failed)
                }
                advanceToNextBookIfComplete(lemma)
            }
        } catch (exception: Exception) {
            Toast.makeText(this, R.string.audio_workbench_recording_failed, Toast.LENGTH_LONG).show()
            if (allowReview && pendingTake() != null) reviewPendingTake()
        } finally {
            updateRows()
        }
    }

    private fun pendingTake(): String? = preferences.getString(PENDING_TAKE_KEY, null)

    private fun reviewPendingTake() {
        val lemma = pendingTake() ?: return
        if (reviewDialog?.isShowing == true) return
        val candidate = repository.recordingCandidateFile(lemma)
        if (!candidate.isFile) {
            if (!preferences.edit().remove(PENDING_TAKE_KEY).commit()) {
                Toast.makeText(this, R.string.audio_review_failed, Toast.LENGTH_LONG).show()
            }
            Toast.makeText(this, R.string.audio_review_missing, Toast.LENGTH_LONG).show()
            return
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.audio_review_title)
            .setMessage(getString(R.string.audio_review_message, lemma))
            .setCancelable(false)
            .setNegativeButton(R.string.audio_review_keep) { _, _ ->
                finishReview(lemma, useNew = false)
            }
            .setPositiveButton(R.string.audio_review_use) { _, _ ->
                finishReview(lemma, useNew = true)
            }
            .setNeutralButton(R.string.audio_review_play, null)
            .create()
        reviewDialog = dialog
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                audioPlayer.play(listOf(candidate))
            }
        }
        dialog.setOnDismissListener {
            reviewDialog = null
            stopPlayback()
        }
        dialog.show()
    }

    private fun finishReview(lemma: String, useNew: Boolean) {
        try {
            if (useNew) {
                repository.commitRecording(lemma)
            } else {
                val candidate = repository.recordingCandidateFile(lemma)
                check(!candidate.exists() || candidate.delete()) { getString(R.string.audio_review_failed) }
            }
            check(preferences.edit().remove(PENDING_TAKE_KEY).commit()) {
                getString(R.string.audio_review_failed)
            }
            updateRows()
            if (useNew) advanceToNextBookIfComplete(lemma)
        } catch (exception: Exception) {
            Toast.makeText(this, exception.message ?: getString(R.string.audio_review_failed), Toast.LENGTH_LONG).show()
            window.decorView.post { reviewPendingTake() }
        }
    }

    private fun advanceToNextBookIfComplete(lemma: String) {
        val book = cards.firstOrNull { it.lemma == lemma }?.book ?: return
        val bookCards = cards.filter { it.book == book }
        if (!RecordingPlan.coverage(bookCards) { it in projectState.unspoken || repository.clipFile(it).isFile }.complete) return
        val nextBook = RecordingPlan.nextIncompleteBook(cards) {
            it in projectState.unspoken || repository.clipFile(it).isFile
        } ?: return
        val nextIndex = RecordingPlan.books(cards).indexOf(nextBook)
        if (nextIndex >= 0 && nextBook != selectedBook) books.setSelection(nextIndex)
    }

    private fun playRecording(card: RecordingCard) {
        if (busy) return
        if (playingLemma == card.lemma) {
            stopPlayback()
            return
        }
        stopRecording()
        if (pendingTake() == null) {
            stopPlayback()
            playingLemma = card.lemma
            updateRows()
            audioPlayer.play(listOf(repository.clipFile(card.lemma))) {
                playingLemma = null
                updateRows()
            }
        }
    }

    private fun stopPlayback() {
        audioPlayer.stop()
        if (playingLemma != null) {
            playingLemma = null
            updateRows()
        }
    }

    companion object {
        private const val LANGUAGE_KEY = "audio_workbench_language"
        private const val PENDING_TAKE_KEY = "pending_recording_lemma"
        private const val PREFERENCES_NAME = "audio-project"
    }

    private enum class Step { LANGUAGE, BOOKS, EXPORT, SETTINGS }
}
