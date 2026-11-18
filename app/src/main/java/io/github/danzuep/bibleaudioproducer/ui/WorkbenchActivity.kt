package io.github.danzuep.bibleaudioproducer.ui

import android.Manifest
import android.net.Uri
import android.os.Bundle
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
import io.github.danzuep.bibleaudioproducer.R
import io.github.danzuep.bibleaudioproducer.audio.AndroidAudioPlayer
import io.github.danzuep.bibleaudioproducer.audio.AndroidAudioRecorder
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
    private val audioPlayer by lazy {
        AndroidAudioPlayer {
            Toast.makeText(this, R.string.audio_workbench_playback_failed, Toast.LENGTH_LONG).show()
        }
    }
    private val verseDialogs by lazy {
        VerseDialogs(this, repository, object : VerseDialogs.Actions {
            override val state get() = projectState
            override val busy get() = this@WorkbenchActivity.busy
            override fun playVerse(verse: AudioVerse) = this@WorkbenchActivity.playVerse(verse)
            override fun saveOrder(reference: String, order: List<Int>?, unspoken: Set<String>) =
                saveVerseOrder(reference, order, unspoken)
            override fun toggleRecording(card: RecordingCard) = this@WorkbenchActivity.toggleRecording(card)
            override fun playRecording(card: RecordingCard) = this@WorkbenchActivity.playRecording(card)
            override fun closeRecording() {
                pendingLemma = null
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
    private var reviewDialog: AlertDialog? = null

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

        adapter = RecordingCardAdapter(::toggleRecording, ::playRecording)
        findViewById<RecyclerView>(R.id.audio_workbench_list).apply {
            layoutManager = LinearLayoutManager(this@WorkbenchActivity)
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
        findViewById<Button>(R.id.audio_listen).setOnClickListener { showChapters() }
        findViewById<Button>(R.id.audio_listen_stop).setOnClickListener { stopPlayback() }
        findViewById<Button>(R.id.audio_attribution).setOnClickListener {
            AlertDialog.Builder(this).setTitle(R.string.audio_attribution)
                .setMessage(R.string.audio_data_attribution)
                .setNeutralButton(R.string.audio_view_license) { _, _ ->
                    try {
                        val license = assets.open("MaculaScripta-LICENSE.txt").bufferedReader().use { it.readText() }
                        AlertDialog.Builder(this).setTitle(R.string.audio_view_license)
                            .setMessage(license).setPositiveButton(android.R.string.ok, null).show()
                    } catch (exception: Exception) {
                        Toast.makeText(this, R.string.audio_license_failed, Toast.LENGTH_LONG).show()
                    }
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
        pendingLemma = null
        stopRecording(allowReview = false)
        stopPlayback()
        super.onStop()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun saveLanguage() {
        preferences.edit()
            .putString(LANGUAGE_KEY, language.text.toString().trim())
            .apply()
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
        pendingLemma = null
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
                activeLemma == card.lemma
            )
        })
    }

    private fun toggleRecording(card: RecordingCard) {
        if (busy) return
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
            updateRows()
        } catch (exception: Exception) {
            Toast.makeText(this, R.string.audio_workbench_recording_failed, Toast.LENGTH_LONG).show()
            activeLemma = null
            updateRows()
        }
    }

    private fun stopRecording(allowReview: Boolean = true) {
        val lemma = activeLemma ?: return
        activeLemma = null
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
        stopRecording()
        if (pendingTake() == null) audioPlayer.play(listOf(repository.clipFile(card.lemma)))
    }

    private fun stopPlayback() {
        audioPlayer.stop()
    }

    companion object {
        private const val LANGUAGE_KEY = "audio_workbench_language"
        private const val PENDING_TAKE_KEY = "pending_recording_lemma"
        private const val PREFERENCES_NAME = "audio-project"
    }

    private enum class Step { LANGUAGE, BOOKS, EXPORT, SETTINGS }
}
