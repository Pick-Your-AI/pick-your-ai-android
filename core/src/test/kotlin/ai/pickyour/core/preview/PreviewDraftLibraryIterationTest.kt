package ai.pickyour.core.preview

import ai.pickyour.core.drafts.DraftStorageFailure
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class PreviewDraftLibraryIterationTest {
    @TempDir lateinit var root: Path

    enum class IteratorCall { HAS_NEXT, NEXT }

    @ParameterizedTest @EnumSource(IteratorCall::class)
    fun rootScanFailureRejectsPartialListingAndAllowsRetry(call: IteratorCall) {
        val saved = seed()
        checkListingFailure(root, call, saved)
    }

    @ParameterizedTest @EnumSource(IteratorCall::class)
    fun deletionScanFailureRejectsPartialListingAndAllowsRetry(call: IteratorCall) {
        val saved = seed()
        val pending = stageDeletions(saved.take(2))
        checkListingFailure(pending, call, saved)
        assertEquals(saved.take(2).map { it.id }.toSet(),
            (PreviewDraftLibrary(root).list() as PreviewLibraryRead.Available).entries
                .filter { it.deletionPending }.map { it.id }.toSet())
    }

    @ParameterizedTest @EnumSource(IteratorCall::class)
    fun rootScanFailurePreventsNewIdentityAndPreservesSavedContent(call: IteratorCall) {
        checkCreationFailure(root, call, seed())
    }

    @ParameterizedTest @EnumSource(IteratorCall::class)
    fun deletionScanFailurePreventsNewIdentityAndPreservesSavedContent(call: IteratorCall) {
        val saved = seed()
        checkCreationFailure(stageDeletions(saved.take(2)), call, saved)
    }

    private fun seed(): List<PreviewLibraryCreate.Saved> = (1..3).map { number ->
        val library = PreviewDraftLibrary(root)
        val created = library.create() as PreviewLibraryCreate.Saved
        val saved = library.open(created.id).save(created.record, PreviewDraftFields("saved draft $number"))
            as PreviewDraftSave.Saved
        PreviewLibraryCreate.Saved(created.id, saved.record)
    }

    private fun stageDeletions(saved: List<PreviewLibraryCreate.Saved>): Path {
        val interrupted = PreviewDraftLibrary(root, {}, { point ->
            if (point == LibraryDeleteCheckpoint.TOMBSTONE_DURABLE) throw IOException("controlled interruption")
        })
        for (entry in saved) assertEquals(PreviewLibraryDelete.Unconfirmed(DraftStorageFailure.IO_FAILURE),
            interrupted.delete(entry.id, entry.record))
        return root.resolve(".preview-deletions")
    }

    private fun checkListingFailure(directory: Path, call: IteratorCall, saved: List<PreviewLibraryCreate.Saved>) {
        val before = contents()
        val fault = FailingScan(directory, call)
        assertEquals(PreviewLibraryRead.Failed(DraftStorageFailure.IO_FAILURE), fault.library.list())
        fault.assertConsumedAndClosed()
        assertEquals(before, contents(), "A failed scan must not change any retained file")
        // A fresh ordinary operation also verifies that the library's JVM and OS locks were released.
        val retried = PreviewDraftLibrary(root).list() as PreviewLibraryRead.Available
        assertEquals(saved.map { it.id }.toSet(), retried.entries.map { it.id }.toSet())
        assertEquals(before, contents())
        assertSaved(saved.last())
    }

    private fun checkCreationFailure(directory: Path, call: IteratorCall, saved: List<PreviewLibraryCreate.Saved>) {
        val before = contents()
        val fault = FailingScan(directory, call)
        assertEquals(PreviewLibraryCreate.Failed(DraftStorageFailure.IO_FAILURE), fault.library.create())
        fault.assertConsumedAndClosed()
        assertEquals(before, contents(), "A failed scan must not create an identity or change retained data")
        val retried = PreviewDraftLibrary(root).create() as PreviewLibraryCreate.Saved
        assertFalse(retried.id in saved.map { it.id })
        val after = contents()
        assertEquals(before, after.filterKeys { it in before }, "Retry must preserve every prior file")
        assertSaved(saved.last())
    }

    private fun assertSaved(entry: PreviewLibraryCreate.Saved) {
        val actual = PreviewDraftLibrary(root).open(entry.id).load() as PreviewDraftLoad.Available
        assertEquals(entry.record.baselineIdentity, actual.record.baselineIdentity)
        assertEquals(entry.record.fields, actual.record.fields)
    }

    /** The real stream still opens, yields an entry and closes; only the next directory read fails. */
    private inner class FailingScan(private val directory: Path, private val call: IteratorCall) {
        private var delivered = 0
        private var opened = 0
        private var closed = 0
        val library = PreviewDraftLibrary(root, {}, {}, { path ->
            val real = Files.newDirectoryStream(path)
            if (path != directory) real else {
                opened++
                object : DirectoryStream<Path> {
                    override fun iterator(): MutableIterator<Path> {
                        val iterator = real.iterator()
                        return object : MutableIterator<Path> {
                            override fun hasNext(): Boolean {
                                if (delivered == 1 && call == IteratorCall.HAS_NEXT) fail()
                                return iterator.hasNext()
                            }
                            override fun next(): Path {
                                if (delivered == 1 && call == IteratorCall.NEXT) fail()
                                return iterator.next().also { delivered++ }
                            }
                            override fun remove(): Unit = throw UnsupportedOperationException()
                        }
                    }
                    override fun close() { closed++; real.close() }
                }
            }
        })
        private fun fail(): Nothing = throw DirectoryIteratorException(IOException("private provider/path sentinel"))
        fun assertConsumedAndClosed() {
            assertEquals(1, delivered)
            assertEquals(1, opened)
            assertEquals(1, closed)
        }
    }

    private fun contents(): Map<String, String> = Files.walk(root).use { paths ->
        paths.filter(Files::isRegularFile).toList().associate { path ->
            root.relativize(path).toString() to MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}
