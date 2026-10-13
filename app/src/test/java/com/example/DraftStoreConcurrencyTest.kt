package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.ui.EditDraftStore
import com.example.ui.FilmLabUiState
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DraftStoreConcurrencyTest {
    private lateinit var context: Context
    private lateinit var directory: File

    @Before fun prepare() {
        context = ApplicationProvider.getApplicationContext()
        directory = File(context.noBackupFilesDir, "edit-draft")
        directory.deleteRecursively()
    }

    @After fun cleanup() { directory.deleteRecursively() }

    private fun photo(color: Int) = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test fun overlappingEditorsKeepTheNewestCompleteDraftReadable() {
        val oldEditor = EditDraftStore(context)
        oldEditor.save(FilmLabUiState(baseBitmap = photo(Color.RED)))
        val beforeOldCommit = CountDownLatch(1)
        val releaseOldCommit = CountDownLatch(1)
        val newStarted = CountDownLatch(1)
        val newFinished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val latest = photo(Color.GREEN)
        val oldWorker = thread(isDaemon = true, name = "old-editor-draft") {
            try {
                var checks = 0
                oldEditor.save(FilmLabUiState(baseBitmap = photo(Color.BLUE))) {
                    if (++checks == 2) {
                        beforeOldCommit.countDown()
                        check(releaseOldCommit.await(10, TimeUnit.SECONDS))
                    }
                    true
                }
            } catch (error: Throwable) { failure.compareAndSet(null, error) }
        }
        var newWorker: Thread? = null
        try {
            assertTrue(beforeOldCommit.await(10, TimeUnit.SECONDS))
            newWorker = thread(isDaemon = true, name = "new-editor-draft") {
                try {
                    val newEditor = EditDraftStore(context)
                    newStarted.countDown()
                    assertNotNull(newEditor.load())
                    newEditor.save(FilmLabUiState(baseBitmap = latest, intensity = .65f))
                } catch (error: Throwable) { failure.compareAndSet(null, error) }
                finally { newFinished.countDown() }
            }
            assertTrue(newStarted.await(10, TimeUnit.SECONDS))
            assertFalse("Another editor must wait for the pending draft commit", newFinished.await(100, TimeUnit.MILLISECONDS))
            releaseOldCommit.countDown()
            oldWorker.join(10_000)
            newWorker.join(10_000)
            assertFalse(oldWorker.isAlive)
            assertFalse(newWorker.isAlive)
            failure.get()?.let { throw it }

            val restored = EditDraftStore(context).load()!!
            assertTrue(latest.sameAs(restored.baseBitmap))
            assertEquals(.65f, restored.intensity, .0001f)
            assertEquals(1, directory.listFiles()!!.count { it.extension == "png" })
        } finally {
            releaseOldCommit.countDown()
            oldWorker.join(10_000)
            newWorker?.join(10_000)
        }
    }

    @Test fun cancellationBeforePublicationKeepsTheLastCompleteDraft() {
        val store = EditDraftStore(context)
        val previous = photo(Color.RED)
        store.save(FilmLabUiState(baseBitmap = previous, intensity = .4f))
        var checks = 0
        store.save(FilmLabUiState(baseBitmap = photo(Color.BLUE))) { ++checks < 3 }

        val restored = EditDraftStore(context).load()!!
        assertTrue(previous.sameAs(restored.baseBitmap))
        assertEquals(.4f, restored.intensity, .0001f)
        assertEquals(1, directory.listFiles()!!.count { it.extension == "png" })
    }

    @Test fun aCachedPhotoRemovedByAnotherEditorIsRewrittenBeforeReuse() {
        val firstEditor = EditDraftStore(context)
        val original = photo(Color.RED)
        firstEditor.save(FilmLabUiState(baseBitmap = original))
        val secondEditor = EditDraftStore(context)
        val cachedEdit = secondEditor.load()!!
        firstEditor.save(FilmLabUiState(baseBitmap = photo(Color.BLUE)))
        secondEditor.save(cachedEdit.copy(intensity = .35f))

        val restored = EditDraftStore(context).load()!!
        assertTrue(original.sameAs(restored.baseBitmap))
        assertEquals(.35f, restored.intensity, .0001f)
        assertEquals(1, directory.listFiles()!!.count { it.extension == "png" })
    }
}
