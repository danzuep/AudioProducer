package org.sil.storyproducer.model

// import org.mockito.Mockito

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.sil.storyproducer.tools.file.getChildDocuments
import java.io.File
import java.util.zip.ZipInputStream

// See below for some background to Roboelectric
// https://www.vogella.com/tutorials/Robolectric/article.html

@RunWith(RobolectricTestRunner::class)
class ParseBloomTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private var result: Story? = null

    @Before
    fun setUp() {
        val workspace = temporaryFolder.newFolder("workspace")
        val story = File(workspace, "000 Storm - demo").apply { mkdirs() }
        val assets = File("src/main/assets").takeIf { it.isDirectory }
            ?: File("app/src/main/assets")
        ZipInputStream(File(assets, "000 Storm - demo.bloomSource").inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = File(story, entry.name).canonicalFile
                check(target.toPath().startsWith(story.canonicalFile.toPath()))
                if (entry.isDirectory) {
                    check(target.mkdirs() || target.isDirectory)
                } else {
                    check(target.parentFile.mkdirs() || target.parentFile.isDirectory)
                    target.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        Workspace.workdocfile = androidx.documentfile.provider.DocumentFile.fromFile(workspace)
        result = parseBloomHTML(
            ApplicationProvider.getApplicationContext(),
            checkNotNull(Workspace.workdocfile.findFile(story.name))
        )
    }

    @Test
    fun parsed_SPAT_Should_get_getChildDocuments() {
        Assert.assertTrue(getChildDocuments(ApplicationProvider.getApplicationContext(), "000 Storm - demo").size > 0)
    }

    @Test
    fun parsed_SPAT_Should_ReturnAStory() {
        Assert.assertNotNull(result)
        Assert.assertEquals(Story::class.java, result!!.javaClass)
    }

//    @Test
    fun parsed_SPAT_Should_get_isSPAuthored_True() {
        // This currently cannot be tested as isSPAuthored has not been added as a member of the Story class
        // Assert.assertTrue(result!!.???)
    }

    @Test
    fun parsed_SPAT_Should_get_StoryTitleFromBookFrontCover() {
        Assert.assertEquals("000 Storm - demo", result!!.title)
    }

    @Test
    fun parsed_SPAT_Should_get_ProvidedPagesPlusSong() {
        // should be 9 slides 6 numbered 1 front cover 1 song and 1 copyright
        Assert.assertEquals(9, result!!.slides.size.toLong())
    }

}
