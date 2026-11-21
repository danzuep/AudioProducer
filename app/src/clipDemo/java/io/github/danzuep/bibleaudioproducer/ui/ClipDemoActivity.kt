package io.github.danzuep.bibleaudioproducer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import io.github.danzuep.bibleaudioproducer.audio.VerseAudio
import io.github.danzuep.bibleaudioproducer.data.AudioProjectRepository
import io.github.danzuep.bibleaudioproducer.workbench.VerseRecordingWord
import io.github.danzuep.bibleaudioproducer.workbench.VerseWord
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/** Standalone, asset-free preview of the production editor. */
class ClipDemoActivity : AppCompatActivity() {
    private lateinit var editor: VerseTakeEditor
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) Toast.makeText(this, "Tap Record again to start.", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val greek = listOf("Οὕτως", "γὰρ", "ἠγάπησεν", "ὁ", "θεὸς", "τὸν", "κόσμον", "ὥστε",
            "τὸν", "υἱὸν", "τὸν", "μονογενῆ", "ἔδωκεν", "ἵνα", "πᾶς", "ὁ", "πιστεύων",
            "εἰς", "αὐτὸν", "μὴ", "ἀπόληται", "ἀλλ᾽", "ἔχῃ", "ζωὴν", "αἰώνιον")
        val gloss = listOf("thus", "for", "loved", "the", "God", "the", "world", "so that",
            "the", "Son", "the", "only", "gave", "that", "everyone", "who", "believes",
            "in", "him", "not", "perish", "but", "have", "life", "eternal")
        val words = greek.mapIndexed { index, surface ->
            VerseRecordingWord(VerseWord(surface, surface, "", "", "", "", gloss[index]), index)
        }
        fun closeEditor() {
            if (editor.close()) finish()
        }
        editor = VerseTakeEditor(
            this, AudioProjectRepository(File(filesDir, "clip-demo")), words,
            {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) true
                else {
                    permission.launch(Manifest.permission.RECORD_AUDIO)
                    false
                }
            },
            {}, { closeEditor() }, { closeEditor() }, "JHN 3:16"
        )
        setContentView(editor.view())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = closeEditor()
        })
        val segment = VerseAudio.SAMPLE_RATE / 3
        val samples = FloatArray(segment * words.size) { i ->
            val phase = i % segment
            val envelope = if (phase < segment * 3 / 4) sin(PI * phase / (segment * 3 / 4)) else 0.0
            (0.28 * envelope * sin(2 * PI * (150 + (i / segment % 5) * 35) * i / VerseAudio.SAMPLE_RATE)).toFloat()
        }
        editor.loadDemoTake(samples)
        Toast.makeText(this, "Demo waveform is synthetic. Record your own verse to replace it.", Toast.LENGTH_LONG).show()
    }

    override fun onStop() {
        if (::editor.isInitialized) editor.stopForBackground()
        super.onStop()
    }

    override fun onDestroy() {
        if (::editor.isInitialized) editor.dispose()
        super.onDestroy()
    }
}
