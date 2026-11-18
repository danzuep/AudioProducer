package io.github.danzuep.bibleaudioproducer.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import io.github.danzuep.bibleaudioproducer.R
import io.github.danzuep.bibleaudioproducer.audio.AndroidAudioDecoder
import io.github.danzuep.bibleaudioproducer.audio.AndroidAudioEncoder
import io.github.danzuep.bibleaudioproducer.audio.AndroidAudioPlayer
import io.github.danzuep.bibleaudioproducer.audio.AndroidAudioRecorder
import io.github.danzuep.bibleaudioproducer.audio.AndroidGreekTts
import io.github.danzuep.bibleaudioproducer.audio.AndroidNeuralSmoother
import io.github.danzuep.bibleaudioproducer.audio.AzureGreekSpeech
import io.github.danzuep.bibleaudioproducer.audio.NeuralModelStore
import io.github.danzuep.bibleaudioproducer.audio.NeuralStrength
import io.github.danzuep.bibleaudioproducer.audio.SpeechCredentials
import io.github.danzuep.bibleaudioproducer.audio.VerseAudio
import io.github.danzuep.bibleaudioproducer.audio.VerseExportFormat
import io.github.danzuep.bibleaudioproducer.data.RecordingPlanJson
import io.github.danzuep.bibleaudioproducer.data.AudioProjectRepository
import io.github.danzuep.bibleaudioproducer.data.MaculaVerseJson
import io.github.danzuep.bibleaudioproducer.workbench.RecordingCard
import io.github.danzuep.bibleaudioproducer.workbench.AudioVerse
import io.github.danzuep.bibleaudioproducer.workbench.AudioProjectState
import io.github.danzuep.bibleaudioproducer.workbench.VerseWorkflow
import io.github.danzuep.bibleaudioproducer.workbench.VerseWord
import io.github.danzuep.bibleaudioproducer.workbench.RecordingPlan
import java.io.File
import java.util.concurrent.Executors

class WorkbenchActivity : AppCompatActivity() {
    private val projectsRoot by lazy { File(filesDir, "projects") }
    private var activeProjectId: String? = null
    private val repository: AudioProjectRepository
        get() = AudioProjectRepository(projectDirectory(checkNotNull(activeProjectId)))
    private val io = Executors.newSingleThreadExecutor()
    private val preferences by lazy { getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE) }
    private val audioRecorder by lazy { AndroidAudioRecorder(this) }
    private val neuralModels by lazy { NeuralModelStore(File(filesDir, "audio-models")) }
    private val speechCredentials by lazy { SpeechCredentials(preferences) }
    private var androidGreekTts: AndroidGreekTts? = null
    private var speechGeneration = 0
    private val audioPlayer by lazy {
        AndroidAudioPlayer {
            playingLemma = null
            playingVerseReference = null
            playingChapterKey = null
            clearPlaybackTempFile()
            updateRows()
            Toast.makeText(this, R.string.audio_workbench_playback_failed, Toast.LENGTH_LONG).show()
        }
    }
    private val verseDialogs by lazy {
        VerseDialogs(this, repository, object : VerseDialogs.Actions {
            override val state get() = projectState
            override val busy get() = this@WorkbenchActivity.busy
            override fun playVerse(verse: AudioVerse, smoothOverride: Boolean?) =
                this@WorkbenchActivity.playVerse(verse, smoothOverride)
            override fun isPlayingVerse(verse: AudioVerse) = playingVerseReference == verse.reference
            override fun playChapter(chapter: Int, verses: List<AudioVerse>) =
                this@WorkbenchActivity.playChapter(chapter, verses)
            override fun isPlayingChapter(chapter: Int) = playingChapterKey == chapterKey(chapter)
            override fun downloadVerse(verse: AudioVerse, strength: NeuralStrength?, format: VerseExportFormat) =
                this@WorkbenchActivity.downloadVerse(verse, strength, format)
            override fun saveVerseState(
                reference: String, order: List<Int>?, unspoken: Set<String>, neuralOff: Set<String>
            ) = this@WorkbenchActivity.saveVerseState(reference, order, unspoken, neuralOff)
            override fun toggleRecording(card: RecordingCard) = this@WorkbenchActivity.toggleRecording(card)
            override fun pressRecording(card: RecordingCard) = pressRecord(card)
            override fun releaseRecording(card: RecordingCard) = releaseRecord(card)
            override fun cancelRecording(card: RecordingCard) = cancelRecord(card)
            override fun isRecording(card: RecordingCard) = activeLemma == card.lemma
            override fun isPlaying(card: RecordingCard) = playingLemma == card.lemma
            override fun playRecording(card: RecordingCard) = this@WorkbenchActivity.playRecording(card)
            override fun speakGreek(text: String) = this@WorkbenchActivity.speakGreek(text)
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
    private var step = Step.PROJECTS
    private var busy = false
    private var activeLemma: String? = null
    private var playingLemma: String? = null
    private var playingVerseReference: String? = null
    private var playingChapterKey: String? = null
    private var playbackRenderGeneration = 0
    private var playbackTempFile: File? = null
    private var pendingCardFocus: String? = null
    private var pendingCardFocusOffset = 0
    private var updatingAudioSettings = false
    private val waveformHandler = Handler(Looper.getMainLooper())
    private val waveformSampler = object : Runnable {
        override fun run() {
            val lemma = activeLemma ?: return
            try {
                adapter.addLevel(lemma, audioRecorder.amplitude())
                waveformHandler.postDelayed(this, 100)
            } catch (exception: IllegalStateException) {
                stopRecording()
                Toast.makeText(this@WorkbenchActivity, R.string.audio_workbench_recording_failed, Toast.LENGTH_LONG).show()
            }
        }
    }
    private var reviewDialog: AlertDialog? = null
    private data class ProjectInfo(val id: String, val name: String)
    private data class VerseExport(
        val reference: String, val greekForms: List<String>, val missing: Int,
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
        val pendingGreekForms = savedInstanceState?.getStringArrayList("verse_export_lemmas")
        if (pendingReference != null && pendingGreekForms != null) {
            pendingVerseExport = VerseExport(
                pendingReference, pendingGreekForms, savedInstanceState?.getInt("verse_export_missing") ?: 0,
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
        val toolbar = findViewById<Toolbar>(R.id.audio_workbench_toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { showProjects() }

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

        language.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveLanguage()
                language.clearFocus()
                true
            } else {
                false
            }
        }
        language.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveLanguage() }
        findViewById<Button>(R.id.audio_project_new).setOnClickListener { promptNewProject() }
        ensureDefaultProject()
        renderProjects()
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
        findViewById<Button>(R.id.audio_speech_settings).setOnClickListener { showSpeechSettings() }
        findViewById<Button>(R.id.audio_tts_voice_settings).setOnClickListener { showDeviceVoicePicker() }
        findViewById<SwitchMaterial>(R.id.audio_neural_enabled).setOnCheckedChangeListener { _, checked ->
            if (!updatingAudioSettings && activeProjectId != null && checked != projectState.neuralAudio) {
                stopPlayback()
                saveProjectSettings(projectState.copy(neuralAudio = checked))
            }
        }
        findViewById<Button>(R.id.audio_neural_strength).setOnClickListener { showNeuralStrengthPicker() }
        refreshAudioSettingControls()
        findViewById<Button>(R.id.audio_import_neural_model).setOnClickListener {
            if (busy) return@setOnClickListener
            if (Build.VERSION.SDK_INT < 24) {
                Toast.makeText(this, R.string.audio_neural_version, Toast.LENGTH_LONG).show()
            } else {
                importNeuralModel.launch(arrayOf("application/octet-stream", "*/*"))
            }
        }
        findViewById<Button>(R.id.audio_choose_verse).setOnClickListener { showChapters() }
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

        showProjects()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.audio_workbench, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.audio_menu_settings) {
            showStep(if (step == Step.SETTINGS) {
                if (activeProjectId == null) Step.PROJECTS else Step.BOOKS
            } else {
                Step.SETTINGS
            })
            return true
        }
        return super.onOptionsItemSelected(item)
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
        androidGreekTts?.shutdown()
        io.shutdown()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingVerseExport?.let {
            outState.putString("verse_export_reference", it.reference)
            outState.putStringArrayList("verse_export_lemmas", ArrayList(it.greekForms))
            outState.putInt("verse_export_missing", it.missing)
            outState.putString("verse_export_strength", it.strength?.name)
            outState.putString("verse_export_format", it.format.name)
        }
        super.onSaveInstanceState(outState)
    }

    private fun saveLanguage() {
        val projectId = activeProjectId ?: return
        preferences.edit().putString(languageKey(projectId), language.text.toString().trim()).apply()
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

    private fun showProjects() {
        if (busy) return
        if (activeLemma != null || pendingTake() != null) stopRecording()
        if (pendingTake() != null) {
            showStep(Step.PROJECTS)
            renderProjects()
            reviewPendingTake()
            return
        }
        saveLanguage()
        stopPlayback()
        activeProjectId = null
        selectedBook = null
        showStep(Step.PROJECTS)
        renderProjects()
    }

    private fun ensureDefaultProject() {
        if (listProjects().isEmpty()) createProject(null, open = false)
    }

    private fun listProjects(): List<ProjectInfo> =
        projectsRoot.listFiles()?.filter { File(it, PROJECT_NAME_FILE).isFile }
            ?.map { ProjectInfo(it.name, File(it, PROJECT_NAME_FILE).readText().trim()) }
            ?.sortedBy { it.id } ?: emptyList()

    private fun renderProjects() {
        val container = findViewById<LinearLayout>(R.id.audio_project_cards)
        container.removeAllViews()
        listProjects().forEach { project ->
            val card = MaterialCardView(this).apply {
                radius = dp(16).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1)
                strokeColor = ContextCompat.getColor(this@WorkbenchActivity, R.color.workbench_outline)
                setCardBackgroundColor(ContextCompat.getColor(this@WorkbenchActivity, R.color.workbench_surface))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
                isClickable = true
                isFocusable = true
                setOnClickListener { selectProject(project) }
            }
            val details = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
            details.addView(TextView(this).apply {
                text = project.name
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            val clips = File(projectDirectory(project.id), "clips").listFiles()
                ?.count { it.extension == "m4a" && !it.name.endsWith(".tmp.m4a") } ?: 0
            details.addView(TextView(this).apply {
                text = getString(R.string.audio_project_recordings, clips)
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@WorkbenchActivity, R.color.workbench_primary_dark))
            })
            card.addView(details)
            container.addView(card)
        }
    }

    private fun promptNewProject() {
        val name = EditText(this).apply {
            hint = getString(R.string.audio_project_name_hint)
            setSingleLine(true)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.audio_project_name_title)
            .setView(name)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.audio_project_new) { _, _ ->
                createProject(name.text.toString().trim(), open = true)
            }
            .show()
    }

    private fun createProject(name: String?, open: Boolean) {
        try {
            check(projectsRoot.mkdirs() || projectsRoot.isDirectory)
            val number = (listProjects().mapNotNull { it.id.substringAfterLast('-').toIntOrNull() }
                .maxOrNull() ?: 0) + 1
            val id = "project-${number.toString().padStart(3, '0')}"
            val directory = projectDirectory(id)
            check(directory.mkdirs() || directory.isDirectory)
            File(directory, PROJECT_NAME_FILE).writeText(name?.takeIf { it.isNotBlank() } ?: "Project${
                number.toString().padStart(3, '0')
            }")
            renderProjects()
            if (open) selectProject(ProjectInfo(id, File(directory, PROJECT_NAME_FILE).readText().trim()))
        } catch (exception: Exception) {
            Toast.makeText(
                this, exception.message ?: getString(R.string.audio_project_create_failed), Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun selectProject(project: ProjectInfo) {
        if (busy) return
        if (activeLemma != null) stopRecording()
        if (pendingTake() != null) {
            Toast.makeText(this, R.string.audio_review_missing, Toast.LENGTH_LONG).show()
            return
        }
        stopPlayback()
        activeProjectId = project.id
        cards = emptyList()
        projectState = AudioProjectState()
        refreshAudioSettingControls()
        selectedBook = null
        bookCards = emptyList()
        bookVerses = emptyList()
        language.setText(preferences.getString(languageKey(project.id), ""))
        showStep(Step.BOOKS)
        val selectedId = project.id
        val projectRepository = repositoryFor(selectedId)
        io.execute {
            try {
                val loaded = projectRepository.loadPlan().ifEmpty {
                    assets.open("recording-plan.json").bufferedReader().use {
                        RecordingPlanJson.parse(it.readText())
                    }
                }
                val loadedState = projectRepository.loadState()
                val hasAudio = projectRepository.recordedCards(loaded).isNotEmpty()
                val initialVerse = if (hasAudio) null else
                    versesFor("JHN").firstOrNull { it.reference == "JHN 1:1" }
                runOnUiThread {
                    if (activeProjectId != selectedId) return@runOnUiThread
                    cards = loaded
                    projectState = loadedState
                    refreshAudioSettingControls()
                    refreshBooks(preferIncomplete = true)
                    initialVerse?.let(verseDialogs::openVerse)
                }
            } catch (exception: Exception) {
                runOnUiThread {
                    if (activeProjectId == selectedId) {
                        empty.setText(R.string.audio_workbench_load_failed)
                        Toast.makeText(
                            this,
                            exception.message ?: getString(R.string.audio_project_load_failed),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun showStep(next: Step) {
        stopRecording()
        stopPlayback()
        language.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(language.windowToken, 0)
        if (step != next) search.text.clear()
        step = next
        val projects = next == Step.PROJECTS
        val settings = next == Step.SETTINGS
        findViewById<View>(R.id.audio_projects_panel).visibility = if (projects) View.VISIBLE else View.GONE
        findViewById<View>(R.id.audio_project_panel).visibility =
            if (next == Step.BOOKS) View.VISIBLE else View.GONE
        val projectSettings = settings && activeProjectId != null
        findViewById<View>(R.id.audio_language_panel).visibility =
            if (projectSettings) View.VISIBLE else View.GONE
        findViewById<View>(R.id.audio_export_panel).visibility =
            if (projectSettings) View.VISIBLE else View.GONE
        findViewById<View>(R.id.audio_settings_panel).visibility =
            if (settings) View.VISIBLE else View.GONE
        findViewById<View>(R.id.audio_neural_controls).visibility =
            if (projectSettings) View.VISIBLE else View.GONE
        listOf(R.id.audio_workbench_import, R.id.audio_import_neural_model, R.id.audio_clear_data)
            .forEach { id ->
                findViewById<View>(id).visibility = if (projectSettings) View.VISIBLE else View.GONE
            }
        supportActionBar?.title = when {
            projects -> getString(R.string.audio_projects_heading)
            settings -> getString(R.string.audio_step_settings)
            else -> listProjects().firstOrNull { it.id == activeProjectId }?.name
                ?: getString(R.string.audio_workbench_title)
        }
        val toolbar = findViewById<Toolbar>(R.id.audio_workbench_toolbar)
        toolbar.navigationIcon = if (projects) null else
            ContextCompat.getDrawable(this, android.R.drawable.ic_menu_revert)
        updateRows()
    }

    private fun projectDirectory(id: String) = File(projectsRoot, id)

    private fun repositoryFor(id: String) = AudioProjectRepository(projectDirectory(id))

    private fun languageKey(id: String) = "${LANGUAGE_KEY}_$id"

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun loadBook(book: String) {
        val selectedProject = activeProjectId
        io.execute {
            try {
                val verses = versesFor(book)
                runOnUiThread {
                    if (selectedBook == book && activeProjectId == selectedProject) {
                        bookVerses = verses
                        bookCards = VerseWorkflow.chapterOrder(cards.filter { it.book == book }, verses)
                        updateRows()
                    }
                }
            } catch (exception: Exception) {
                runOnUiThread {
                    if (selectedBook == book && activeProjectId == selectedProject) {
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

    private fun saveVerseState(
        reference: String,
        order: List<Int>?,
        unspoken: Set<String>,
        neuralOff: Set<String>
    ) {
        busy = true
        val orders = if (order == null) projectState.wordOrders - reference
            else projectState.wordOrders + (reference to order)
        val updated = projectState.copy(wordOrders = orders, unspoken = unspoken, neuralOff = neuralOff)
        val targetRepository = repository
        io.execute {
            try {
                targetRepository.saveState(updated)
                runOnUiThread {
                    busy = false
                    projectState = updated
                    refreshAudioSettingControls()
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

    private fun playVerse(verse: AudioVerse, smoothOverride: Boolean? = null) {
        if (busy) return
        if (playingVerseReference == verse.reference) {
            stopPlayback()
            return
        }
        stopRecording()
        stopPlayback()
        val spoken = VerseWorkflow.spokenWords(
            verse.words, projectState.rules, projectState.seedOrders[verse.reference], projectState.unspoken,
            projectState.wordOrders[verse.reference]
        )
        val words = spoken.filter { repository.clipFile(it.surface).isFile }
        if (words.isEmpty()) {
            Toast.makeText(this, R.string.audio_word_not_recorded, Toast.LENGTH_SHORT).show()
            return
        }
        if (words.size != spoken.size) {
            Toast.makeText(this, R.string.audio_playback_missing_words, Toast.LENGTH_SHORT).show()
        }
        if (pendingTake() == null) {
            playingVerseReference = verse.reference
            updateRows()
            renderAndPlay(listOf(verse.reference to words), mapOf(verse.reference to smoothOverride))
        }
    }

    private fun playChapter(chapter: Int, verses: List<AudioVerse>) {
        val key = chapterKey(chapter)
        if (busy) return
        if (playingChapterKey == key) {
            stopPlayback()
            return
        }
        stopRecording()
        stopPlayback()
        val spokenGroups = verses.map { verse ->
            verse.reference to VerseWorkflow.spokenWords(
                verse.words, projectState.rules, projectState.seedOrders[verse.reference], projectState.unspoken,
                projectState.wordOrders[verse.reference]
            )
        }
        val groups = spokenGroups.map { (reference, words) ->
            reference to words.filter { repository.clipFile(it.surface).isFile }
        }
        val recorded = groups.flatMap { it.second }
        if (recorded.isEmpty()) {
            Toast.makeText(this, R.string.audio_word_not_recorded, Toast.LENGTH_SHORT).show()
            return
        }
        if (recorded.size != spokenGroups.sumOf { it.second.size }) {
            Toast.makeText(this, R.string.audio_playback_missing_words, Toast.LENGTH_SHORT).show()
        }
        if (pendingTake() == null) {
            playingChapterKey = key
            updateRows()
            renderAndPlay(groups.filter { it.second.isNotEmpty() })
        }
    }

    private fun chapterKey(chapter: Int) = "${selectedBook.orEmpty()}:$chapter"

    private fun renderAndPlay(
        groups: List<Pair<String, List<VerseWord>>>,
        smoothOverrides: Map<String, Boolean?> = emptyMap()
    ) {
        val renderGeneration = ++playbackRenderGeneration
        val neuralReferences = groups.map { it.first }
            .filter { reference ->
                smoothOverrides[reference] ?: (projectState.neuralAudio && reference !in projectState.neuralOff)
            }
            .toSet()
        val smoothingStrength = projectState.neuralStrength
        val targetRepository = repository
        val applyNeural = neuralReferences.isNotEmpty()
        if (applyNeural && Build.VERSION.SDK_INT < 24) {
            Toast.makeText(this, R.string.audio_neural_version, Toast.LENGTH_LONG).show()
            playingVerseReference = null
            playingChapterKey = null
            updateRows()
            return
        }
        if (applyNeural && !neuralModels.isInstalled()) {
            playingVerseReference = null
            playingChapterKey = null
            updateRows()
            showNeuralModelRequired()
            return
        }
        io.execute {
            var rendered: File? = null
            try {
                val decoder = AndroidAudioDecoder()
                val decoded = mutableMapOf<String, FloatArray>()
                var sampleCount = 0L
                val maxPlaybackSamples = VerseAudio.SAMPLE_RATE * if (groups.size > 1) 600L else 180L
                val renderedVerses = groups.map { (reference, words) ->
                    val clips = words.map { word ->
                        val surface = word.surface
                        decoded.getOrPut(surface) { decoder.decode(targetRepository.clipFile(surface)) }.also {
                            sampleCount += it.size
                            require(sampleCount <= maxPlaybackSamples) {
                                getString(R.string.audio_playback_too_long)
                            }
                        }
                    }
                    val assembled = VerseAudio.assemble(clips)
                    if (reference in neuralReferences) {
                        AndroidNeuralSmoother().smooth(
                            assembled, neuralModels.modelFile, smoothingStrength
                        )
                    } else {
                        assembled
                    }
                }
                val samples = if (renderedVerses.size == 1) renderedVerses.single()
                else VerseAudio.assemble(renderedVerses)
                rendered = File.createTempFile("verse-playback-", ".wav", cacheDir)
                rendered.writeBytes(VerseAudio.encodeWav(samples))
                runOnUiThread {
                    if (renderGeneration != playbackRenderGeneration || isFinishing || isDestroyed) {
                        rendered?.delete()
                        return@runOnUiThread
                    }
                    playbackTempFile = rendered
                    audioPlayer.play(listOf(checkNotNull(rendered))) {
                        if (renderGeneration == playbackRenderGeneration) {
                            playingVerseReference = null
                            playingChapterKey = null
                            clearPlaybackTempFile()
                            updateRows()
                        }
                    }
                }
            } catch (exception: Exception) {
                rendered?.delete()
                runOnUiThread {
                    if (renderGeneration == playbackRenderGeneration && !isFinishing && !isDestroyed) {
                        playingVerseReference = null
                        playingChapterKey = null
                        updateRows()
                        Toast.makeText(
                            this, exception.message ?: getString(R.string.audio_workbench_playback_failed),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
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
            verse.words, projectState.rules, projectState.seedOrders[verse.reference], projectState.unspoken,
            projectState.wordOrders[verse.reference]
        )
        val recorded = words.filter { repository.clipFile(it.surface).isFile }
        if (recorded.isEmpty()) {
            Toast.makeText(this, R.string.audio_word_not_recorded, Toast.LENGTH_LONG).show()
            return
        }
        pendingVerseExport = VerseExport(
            verse.reference, recorded.map { it.surface }, words.size - recorded.size, strength, format
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
                val clips = export.greekForms.map { surface ->
                    decoded.getOrPut(surface) { decoder.decode(repository.clipFile(surface)) }.also {
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
                    refreshAudioSettingControls()
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
        if (busy || activeProjectId == null) return
        AlertDialog.Builder(this)
            .setTitle(R.string.audio_clear_title)
            .setMessage(R.string.audio_clear_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.audio_clear_confirm) { _, _ ->
                stopRecording()
                stopPlayback()
                busy = true
                val projectId = checkNotNull(activeProjectId)
                val directory = projectDirectory(projectId)
                io.execute {
                    try {
                        check(directory.listFiles().orEmpty()
                            .filter { it.name != PROJECT_NAME_FILE }.all { it.deleteRecursively() }) {
                            getString(R.string.audio_clear_failed)
                        }
                        val defaultCards = assets.open("recording-plan.json").bufferedReader().use {
                            RecordingPlanJson.parse(it.readText())
                        }
                        check(preferences.edit().remove(PENDING_TAKE_KEY).remove(PENDING_PROJECT_KEY).commit()) {
                            getString(R.string.audio_clear_failed)
                        }
                        preferences.edit().remove(languageKey(projectId)).apply()
                        runOnUiThread {
                            busy = false
                            language.setText("")
                            saveLanguage()
                            cards = defaultCards
                            projectState = AudioProjectState()
                            refreshAudioSettingControls()
                            refreshBooks(preferIncomplete = true)
                            showStep(Step.BOOKS)
                            renderProjects()
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
        if (bookList.isNotEmpty()) {
            selectedBook = bookList[index]
            books.setSelection(index)
            bookCards = cards.filter { it.book == selectedBook }
            bookVerses = emptyList()
            loadBook(bookList[index])
        }
        else {
            selectedBook = null
            updateRows()
        }
    }

    private fun updateRows() {
        if (activeProjectId == null) {
            adapter.submitList(emptyList())
            return
        }
        val currentBook = selectedBook
        val requiredCards = bookCards.filter { it.lemma !in projectState.unspoken }
        val visibleCards = RecordingPlan.search(requiredCards, currentBook, search.text.toString())
        val completed = RecordingPlan.coverage(requiredCards) { repository.clipFile(it).isFile }
        coverage.text = getString(R.string.audio_workbench_coverage, completed.recorded, completed.total)
        val noPlan = cards.isEmpty()
        val showList = step == Step.BOOKS
        findViewById<View>(R.id.audio_workbench_controls).visibility =
            if (!noPlan && showList) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.audio_workbench_export).isEnabled = !noPlan
        findViewById<TextView>(R.id.audio_project_heading).text =
            listProjects().firstOrNull { it.id == activeProjectId }?.name.orEmpty()
        empty.setText(if (noPlan) R.string.audio_workbench_empty else R.string.audio_workbench_no_results)
        empty.visibility = if (showList && (noPlan || visibleCards.isEmpty())) View.VISIBLE else View.GONE
        findViewById<RecyclerView>(R.id.audio_workbench_list).visibility =
            if (showList && !noPlan && visibleCards.isNotEmpty()) View.VISIBLE else View.GONE
        val rows = visibleCards.map { card ->
            RecordingCardRow(
                card,
                repository.clipFile(card.lemma).isFile,
                activeLemma == card.lemma,
                playingLemma == card.lemma,
                bookVerses.asSequence().flatMap { it.words.asSequence() }
                    .firstOrNull { it.lemma == card.lemma }
                    ?.let { listOf(it.pos, it.grammaticalCase, it.mood).filter(String::isNotBlank).joinToString(" · ") }
                    .orEmpty()
            )
        }
        adapter.submitList(rows) {
            val focus = pendingCardFocus ?: return@submitList
            val position = adapter.currentList.indexOfFirst { it.card.lemma == focus }
            if (position >= 0) {
                (findViewById<RecyclerView>(R.id.audio_workbench_list).layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(position, pendingCardFocusOffset)
                pendingCardFocus = null
            }
        }
        verseDialogs.refreshControls()
    }

    private fun focusNextUnrecordedCard(currentGreek: String) {
        pendingCardFocus = null
        val currentList = adapter.currentList
        if (currentList.isEmpty()) return
        val currentIndex = currentList.indexOfFirst { it.card.lemma == currentGreek }
        val recycler = findViewById<RecyclerView>(R.id.audio_workbench_list)
        pendingCardFocusOffset = currentIndex.takeIf { it >= 0 }
            ?.let { recycler.findViewHolderForAdapterPosition(it)?.itemView?.top }
            ?: dp(16)
        val next = (currentIndex + 1 until currentList.size).firstNotNullOfOrNull { index ->
            currentList[index].card.lemma.takeIf {
                it !in projectState.unspoken && !repository.clipFile(it).isFile
            }
        } ?: (0 until currentIndex.coerceAtLeast(0)).firstNotNullOfOrNull { index ->
            currentList[index].card.lemma.takeIf {
                it !in projectState.unspoken && !repository.clipFile(it).isFile
            }
        }
        pendingCardFocus = next
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
                check(preferences.edit().putString(PENDING_TAKE_KEY, lemma)
                    .putString(PENDING_PROJECT_KEY, activeProjectId).commit()
                ) {
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
            check(preferences.edit().putString(PENDING_TAKE_KEY, lemma)
                .putString(PENDING_PROJECT_KEY, activeProjectId).commit()
            ) {
                getString(R.string.audio_review_failed)
            }
            if (repository.clipFile(lemma).isFile) {
                if (allowReview) reviewPendingTake()
            } else {
                repository.commitRecording(lemma)
                check(preferences.edit().remove(PENDING_TAKE_KEY).remove(PENDING_PROJECT_KEY).commit()) {
                    getString(R.string.audio_review_failed)
                }
                focusNextUnrecordedCard(lemma)
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
        if (activeProjectId == null) {
            activeProjectId = preferences.getString(PENDING_PROJECT_KEY, null)
                ?.takeIf { File(projectDirectory(it), PROJECT_NAME_FILE).isFile }
        }
        if (reviewDialog?.isShowing == true) return
        val candidate = repository.recordingCandidateFile(lemma)
        if (!candidate.isFile) {
            if (!preferences.edit().remove(PENDING_TAKE_KEY).remove(PENDING_PROJECT_KEY).commit()) {
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
            check(preferences.edit().remove(PENDING_TAKE_KEY).remove(PENDING_PROJECT_KEY).commit()) {
                getString(R.string.audio_review_failed)
            }
            if (useNew) focusNextUnrecordedCard(lemma)
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
        if (nextIndex >= 0 && nextBook != selectedBook) {
            pendingCardFocus = cards.firstOrNull {
                it.book == nextBook && it.lemma !in projectState.unspoken && !repository.clipFile(it.lemma).isFile
            }?.lemma
            pendingCardFocusOffset = dp(16)
            books.setSelection(nextIndex)
        }
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

    private fun speakGreek(text: String) {
        if (busy) return
        stopRecording()
        if (pendingTake() != null) return
        stopPlayback()
        val key = try {
            speechCredentials.key()
        } catch (exception: Exception) {
            Toast.makeText(this, exception.message ?: getString(R.string.audio_speech_setup_failed), Toast.LENGTH_LONG).show()
            return
        }
        if (key.isBlank()) {
            Toast.makeText(this, R.string.audio_speech_fallback_notice, Toast.LENGTH_LONG).show()
            getAndroidGreekTts().speak(text)
            return
        }
        val region = speechCredentials.region()
        val generation = ++speechGeneration
        io.execute {
            val audioFile = File(cacheDir, "greek-speech.mp3")
            try {
                AzureGreekSpeech().synthesize(text, region, key, audioFile)
                runOnUiThread {
                    if (generation == speechGeneration && !isFinishing && !isDestroyed) {
                        audioPlayer.play(listOf(audioFile))
                    }
                }
            } catch (exception: Exception) {
                runOnUiThread {
                    if (generation == speechGeneration && !isFinishing && !isDestroyed) {
                        Toast.makeText(
                            this,
                            getString(R.string.audio_speech_failed, exception.message ?: ""),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun showSpeechSettings() {
        val region = EditText(this).apply {
            hint = getString(R.string.audio_speech_region)
            setSingleLine(true)
            setText(speechCredentials.region())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        val key = EditText(this).apply {
            hint = getString(R.string.audio_speech_key)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(region)
            addView(key)
        }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.audio_speech_title)
            .setView(form)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.audio_speech_use_device_voice, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                try {
                    speechCredentials.clearKey()
                    Toast.makeText(this, R.string.audio_speech_device_voice_enabled, Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                } catch (exception: Exception) {
                    Toast.makeText(this, exception.message ?: getString(R.string.audio_speech_setup_failed), Toast.LENGTH_LONG).show()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    speechCredentials.save(region.text.toString().trim(), key.text.toString().trim())
                    Toast.makeText(this, R.string.audio_speech_saved, Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                } catch (exception: Exception) {
                    Toast.makeText(this, exception.message ?: getString(R.string.audio_speech_setup_failed), Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    private fun refreshAudioSettingControls() {
        updatingAudioSettings = true
        findViewById<SwitchMaterial>(R.id.audio_neural_enabled).isChecked = projectState.neuralAudio
        updatingAudioSettings = false
        findViewById<Button>(R.id.audio_neural_strength).setText(
            if (projectState.neuralStrength == NeuralStrength.STRONG) {
                R.string.audio_neural_strength_strong
            } else {
                R.string.audio_neural_strength_standard
            }
        )
        val savedVoice = preferences.getString(DEVICE_TTS_VOICE_KEY, null)
        val selectedVoice = androidGreekTts?.selectedVoiceName() ?: savedVoice
        findViewById<Button>(R.id.audio_tts_voice_settings).text = selectedVoice?.let {
            getString(R.string.audio_tts_voice_current, it)
        } ?: getString(R.string.audio_tts_voice_choose)
    }

    private fun saveProjectSettings(updated: AudioProjectState) {
        if (busy || activeProjectId == null) {
            refreshAudioSettingControls()
            return
        }
        busy = true
        val selectedProject = activeProjectId
        val targetRepository = repository
        io.execute {
            try {
                targetRepository.saveState(updated)
                runOnUiThread {
                    if (activeProjectId == selectedProject) projectState = updated
                    busy = false
                    refreshAudioSettingControls()
                    updateRows()
                }
            } catch (exception: Exception) {
                runOnUiThread {
                    busy = false
                    refreshAudioSettingControls()
                    Toast.makeText(
                        this, exception.message ?: getString(R.string.audio_order_failed), Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showNeuralStrengthPicker() {
        val selected = if (projectState.neuralStrength == NeuralStrength.STRONG) 1 else 0
        AlertDialog.Builder(this)
            .setTitle(R.string.audio_neural_strength_title)
            .setSingleChoiceItems(
                arrayOf(
                    getString(R.string.audio_neural_strength_standard),
                    getString(R.string.audio_neural_strength_strong)
                ),
                selected
            ) { dialog, index ->
                val strength = if (index == 1) NeuralStrength.STRONG else NeuralStrength.STANDARD
                if (strength != projectState.neuralStrength) {
                    stopPlayback()
                    saveProjectSettings(projectState.copy(neuralStrength = strength))
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showNeuralModelRequired() {
        AlertDialog.Builder(this).setTitle(R.string.audio_neural_model_title)
            .setMessage(R.string.audio_neural_model_required)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.audio_neural_import) { _, _ ->
                importNeuralModel.launch(arrayOf("application/octet-stream", "*/*"))
            }.show()
    }

    private fun getAndroidGreekTts(): AndroidGreekTts =
        androidGreekTts ?: AndroidGreekTts(
            this,
            preferences.getString(DEVICE_TTS_VOICE_KEY, null),
            onVoiceChanged = {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) refreshAudioSettingControls()
                }
            }
        ) { message ->
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
        }.also { androidGreekTts = it }

    private fun showDeviceVoicePicker() {
        val tts = getAndroidGreekTts()
        tts.availableVoices { voices ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (voices.isEmpty()) {
                    Toast.makeText(this, R.string.audio_tts_voice_unavailable, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val options = voices.map { "${it.name} — ${it.locale.displayName}" }.toTypedArray()
                val selected = voices.indexOfFirst { it.name == tts.selectedVoiceName() }
                AlertDialog.Builder(this)
                    .setTitle(R.string.audio_tts_voice_title)
                    .setSingleChoiceItems(options, selected) { dialog, index ->
                        val oldName = tts.selectedVoiceName()
                        if (!tts.selectVoice(voices[index].name)) {
                            Toast.makeText(this, R.string.audio_tts_voice_unavailable, Toast.LENGTH_LONG).show()
                            return@setSingleChoiceItems
                        }
                        if (!preferences.edit().putString(DEVICE_TTS_VOICE_KEY, voices[index].name).commit()) {
                            oldName?.let(tts::selectVoice)
                            Toast.makeText(this, R.string.audio_tts_voice_save_failed, Toast.LENGTH_LONG).show()
                            return@setSingleChoiceItems
                        }
                        refreshAudioSettingControls()
                        Toast.makeText(this, R.string.audio_tts_voice_selected, Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun stopPlayback() {
        speechGeneration++
        playbackRenderGeneration++
        audioPlayer.stop()
        androidGreekTts?.stop()
        clearPlaybackTempFile()
        if (playingLemma != null || playingVerseReference != null || playingChapterKey != null) {
            playingLemma = null
            playingVerseReference = null
            playingChapterKey = null
            updateRows()
        }
    }

    private fun clearPlaybackTempFile() {
        val temporary = playbackTempFile
        playbackTempFile = null
        if (temporary?.exists() == true) temporary.delete()
    }

    companion object {
        private const val LANGUAGE_KEY = "audio_workbench_language"
        private const val PENDING_TAKE_KEY = "pending_recording_lemma"
        private const val PENDING_PROJECT_KEY = "pending_recording_project"
        private const val PREFERENCES_NAME = "audio-project"
        private const val DEVICE_TTS_VOICE_KEY = "android_greek_tts_voice"
        private const val PROJECT_NAME_FILE = "project-name.txt"
    }

    @Deprecated("Use project navigation.")
    override fun onBackPressed() {
        when (step) {
            Step.SETTINGS -> showStep(if (activeProjectId == null) Step.PROJECTS else Step.BOOKS)
            Step.BOOKS -> showProjects()
            Step.PROJECTS -> super.onBackPressed()
        }
    }

    private enum class Step { PROJECTS, BOOKS, SETTINGS }
}
