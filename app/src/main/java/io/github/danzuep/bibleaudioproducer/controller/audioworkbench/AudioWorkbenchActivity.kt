package io.github.danzuep.bibleaudioproducer.controller.audioworkbench

import android.Manifest
import android.media.MediaPlayer
import android.media.MediaRecorder
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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
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
import io.github.danzuep.bibleaudioproducer.workbench.AudioWorkbenchCard
import io.github.danzuep.bibleaudioproducer.workbench.AudioVerse
import io.github.danzuep.bibleaudioproducer.workbench.VerseWorkflow
import io.github.danzuep.bibleaudioproducer.workbench.VerseWord
import io.github.danzuep.bibleaudioproducer.workbench.Workbench
import java.io.File
import java.util.concurrent.Executors

class AudioWorkbenchActivity : AppCompatActivity() {
    private val repository by lazy {
        AudioWorkbenchRepository(File(filesDir, "audio-workbench"))
    }
    private val io = Executors.newSingleThreadExecutor()
    private var cards = emptyList<AudioWorkbenchCard>()
    private var projectState = AudioProjectState()
    private var selectedBook: String? = null
    private var bookCards = emptyList<AudioWorkbenchCard>()
    private var bookVerses = emptyList<AudioVerse>()
    private val verseCache = mutableMapOf<String, List<AudioVerse>>()
    private var step = Step.LANGUAGE
    private var busy = false
    private var activeLemma: String? = null
    private var recorder: MediaRecorder? = null
    private var temporaryRecording: File? = null
    private var player: MediaPlayer? = null
    private var playbackGeneration = 0

    private lateinit var language: EditText
    private lateinit var books: Spinner
    private lateinit var search: EditText
    private lateinit var coverage: TextView
    private lateinit var empty: TextView
    private lateinit var adapter: AudioWorkbenchAdapter

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

        adapter = AudioWorkbenchAdapter(::toggleRecording, ::playRecording)
        findViewById<RecyclerView>(R.id.audio_workbench_list).apply {
            layoutManager = LinearLayoutManager(this@AudioWorkbenchActivity)
            adapter = this@AudioWorkbenchActivity.adapter
        }

        language.setText(getPreferences(MODE_PRIVATE).getString(LANGUAGE_KEY, ""))
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
                        AudioWorkbenchPlan.parse(it.readText())
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

    override fun onStop() {
        pendingLemma = null
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
        assets.open("books/$book.json").bufferedReader().use { MaculaVerses.parse(it.readText()) }
    }

    private fun showChapters() {
        search.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(search.windowToken, 0)
        val chapters = bookVerses.map { it.chapter }.distinct()
        AlertDialog.Builder(this)
            .setTitle(R.string.audio_choose_chapter)
            .setItems(chapters.map { getString(R.string.audio_chapter, it) }.toTypedArray()) { _, index ->
                showVerses(chapters[index])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showVerses(chapter: Int) {
        val verses = bookVerses.filter { it.chapter == chapter }
        val labels = verses.map { verse ->
            val words = VerseWorkflow.spokenWords(
                verse.words, projectState.rules, projectState.seedOrders[verse.reference], projectState.unspoken
            )
            val missing = words.map { it.lemma }.distinct().count { !repository.clipFile(it).isFile }
            val status = when {
                verse.words.none { !it.proper } -> getString(R.string.audio_verse_no_words)
                missing == 0 -> getString(R.string.audio_verse_ready)
                else -> getString(R.string.audio_verse_missing, missing)
            }
            "${verse.reference} · $status\n${words.map { it.gloss }.filter { it.isNotBlank() && it != "-" }.joinToString(" ")}"
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.audio_chapter, chapter))
            .setItems(labels.toTypedArray()) { _, index -> showVerseMenu(verses[index]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showVerseMenu(verse: AudioVerse) {
        val actions = arrayOf(
            getString(R.string.audio_play_verse),
            getString(R.string.audio_arrange_verse),
            getString(R.string.audio_record_verse_words)
        )
        AlertDialog.Builder(this)
            .setTitle(verse.reference)
            .setItems(actions) { _, index ->
                when (index) {
                    0 -> playVerse(verse)
                    1 -> arrangeVerse(verse)
                    2 -> showVerseWords(verse)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun arrangeVerse(verse: AudioVerse) {
        if (busy) return
        val chunks = VerseWorkflow.chunks(verse.words)
        val saved = projectState.seedOrders[verse.reference]
        val order = (saved?.takeIf {
            it.size == chunks.size && it.toSet() == chunks.indices.toSet()
        } ?: chunks.indices.toList()).toMutableList()
        val unspoken = projectState.unspoken.toMutableSet()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
        }
        lateinit var draw: () -> Unit
        draw = {
            content.removeAllViews()
            order.forEachIndexed { position, chunkIndex ->
                val chunk = chunks[chunkIndex]
                val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val earlier = Button(this).apply {
                    text = "←"
                    contentDescription = getString(R.string.audio_move_earlier)
                    isEnabled = position > 0
                    setOnClickListener {
                        order.add(position - 1, order.removeAt(position))
                        draw()
                    }
                }
                val later = Button(this).apply {
                    text = "→"
                    contentDescription = getString(R.string.audio_move_later)
                    isEnabled = position < order.lastIndex
                    setOnClickListener {
                        order.add(position + 1, order.removeAt(position))
                        draw()
                    }
                }
                val label = TextView(this).apply {
                    text = "${chunk.words.joinToString(" ") { it.surface }}\n" +
                        chunk.words.joinToString(" ") { it.gloss }
                }
                header.addView(earlier)
                header.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                header.addView(later)
                content.addView(header)
                chunk.words.filterNot { it.proper }.forEach { word ->
                    content.addView(CheckBox(this).apply {
                        text = getString(R.string.audio_speak_word, word.surface)
                        isChecked = word.unspokenKey !in unspoken
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) unspoken.remove(word.unspokenKey) else unspoken.add(word.unspokenKey)
                        }
                    })
                }
            }
        }
        draw()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.audio_arrange_title, verse.reference))
            .setMessage(R.string.audio_arrange_description)
            .setView(dialogScroll(content))
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.audio_reset_order) { _, _ ->
                saveVerseOrder(verse.reference, null, unspoken)
            }
            .setPositiveButton(R.string.audio_save_order) { _, _ ->
                saveVerseOrder(verse.reference, order.toList(), unspoken)
            }
            .show()
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

    private fun showVerseWords(verse: AudioVerse) {
        val words = VerseWorkflow.spokenWords(
            verse.words, projectState.rules, projectState.seedOrders[verse.reference], projectState.unspoken
        )
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
        }
        for (word in words) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val card = AudioWorkbenchCard(selectedBook.orEmpty(), word.lemma, word.gloss, 0)
            row.addView(TextView(this).apply {
                text = "${word.surface}\n${word.gloss}"
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply {
                text = getString(R.string.audio_record_stop)
                setOnClickListener { toggleRecording(card) }
            })
            row.addView(Button(this).apply {
                text = getString(R.string.audio_workbench_play)
                setOnClickListener {
                    if (repository.clipFile(word.lemma).isFile) playRecording(card)
                    else Toast.makeText(this@AudioWorkbenchActivity, R.string.audio_word_not_recorded, Toast.LENGTH_SHORT).show()
                }
            })
            content.addView(row)
        }
        AlertDialog.Builder(this)
            .setTitle(verse.reference)
            .setView(dialogScroll(content))
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener { pendingLemma = null; stopRecording(); stopPlayback() }
            .show()
    }

    private fun dialogScroll(content: View): View {
        val scroll = ScrollView(this).apply { addView(content) }
        return FrameLayout(this).apply {
            addView(
                scroll,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    (resources.displayMetrics.heightPixels * 0.4f).toInt()
                )
            )
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
        playVerseWord(words, 0, playbackGeneration)
    }

    private fun playVerseWord(words: List<VerseWord>, index: Int, generation: Int) {
        if (index >= words.size || generation != playbackGeneration) return
        try {
            val mediaPlayer = MediaPlayer()
            player = mediaPlayer
            mediaPlayer.setDataSource(repository.clipFile(words[index].lemma).absolutePath)
            mediaPlayer.setOnPreparedListener { if (player === it) it.start() }
            mediaPlayer.setOnCompletionListener {
                if (player === it) {
                    player = null
                    it.release()
                    playVerseWord(words, index + 1, generation)
                }
            }
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
                        check(!File(filesDir, "audio-workbench").exists() ||
                            File(filesDir, "audio-workbench").deleteRecursively()) {
                            getString(R.string.audio_clear_failed)
                        }
                        val defaultCards = assets.open("recording-plan.json").bufferedReader().use {
                            AudioWorkbenchPlan.parse(it.readText())
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
                val imported = AudioWorkbenchPlan.parse(json)
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
        val bookList = Workbench.books(cards)
        books.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, bookList)
        val preferred = if (preferIncomplete) {
            Workbench.nextIncompleteBook(cards) { it in projectState.unspoken || repository.clipFile(it).isFile }
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
            Workbench.search(requiredCards, currentBook, search.text.toString())
        }
        val completed = Workbench.coverage(requiredCards) { repository.clipFile(it).isFile }
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
            AudioWorkbenchRow(
                card,
                repository.clipFile(card.lemma).isFile,
                activeLemma == card.lemma
            )
        })
    }

    private fun toggleRecording(card: AudioWorkbenchCard) {
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

    private fun createMediaRecorder(): MediaRecorder {
        return if (android.os.Build.VERSION.SDK_INT >= 31) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
    }

    private fun startRecording(lemma: String) {
        stopRecording()
        stopPlayback()
        val output = repository.clipFile(lemma)
        val clipsDirectory = checkNotNull(output.parentFile)
        val temporary = File(clipsDirectory, "${output.nameWithoutExtension}.tmp.m4a")
        val newRecorder = createMediaRecorder()
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
                repository.recordingSaved(lemma)
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
        if (!Workbench.coverage(bookCards) { it in projectState.unspoken || repository.clipFile(it).isFile }.complete) return
        val nextBook = Workbench.nextIncompleteBook(cards) {
            it in projectState.unspoken || repository.clipFile(it).isFile
        } ?: return
        val nextIndex = Workbench.books(cards).indexOf(nextBook)
        if (nextIndex >= 0 && nextBook != selectedBook) books.setSelection(nextIndex)
    }

    private fun playRecording(card: AudioWorkbenchCard) {
        if (busy) return
        stopRecording()
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
        playbackGeneration++
        player?.release()
        player = null
    }

    companion object {
        private const val LANGUAGE_KEY = "audio_workbench_language"
    }

    private enum class Step { LANGUAGE, BOOKS, EXPORT, SETTINGS }
}
