package io.github.cubsync.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The two-pane browser, over real folders.
 *
 * The copy is the part worth testing: it is the only place in the product where a
 * person can overwrite something by pressing one button, and it reuses the write
 * contract the synchroniser uses without reusing the comparison that makes the
 * synchroniser careful.
 */
class FolderBrowserTest {

    private val temporaries = mutableListOf<Path>()
    private val scopes = mutableListOf<CoroutineScope>()
    private val pools = mutableListOf<BackendPool>()

    private fun tempDir(prefix: String): Path = Files.createTempDirectory(prefix).also { temporaries.add(it) }

    private fun pool(): BackendPool = BackendPool(
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes.add(it) },
        nodeName = "test",
        log = AppLog(),
    ).also { pools.add(it) }

    @AfterTest
    fun cleanUp() {
        pools.forEach { runCatching { it.close() } }
        pools.clear()
        scopes.forEach { runCatching { it.cancel() } }
        scopes.clear()
        for (dir in temporaries) {
            runCatching { Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
        temporaries.clear()
    }

    @Test
    fun a_folder_is_listed_with_its_entries() = runBlocking {
        val dir = tempDir("browse-")
        Files.writeString(dir.resolve("a.txt"), "a")
        Files.createDirectories(dir.resolve("sub"))

        val browser = FolderBrowser(pool(), AppLog())
        val entries = browser.list(EndpointId.LOCAL, PathTools.normalizeRoot(dir.toString()))

        assertEquals(listOf("a.txt", "sub"), entries.map { it.relPath }.sorted())
        assertTrue(entries.first { it.relPath == "sub" }.isDirectory)
        assertEquals(1L, entries.first { it.relPath == "a.txt" }.size)
    }

    @Test
    fun the_selected_files_are_copied_and_nothing_else_is() = runBlocking {
        val source = tempDir("from-")
        val target = tempDir("to-")
        Files.writeString(source.resolve("keep.txt"), "keep")
        Files.writeString(source.resolve("wanted.txt"), "wanted")

        val browser = FolderBrowser(pool(), AppLog())
        val outcome = browser.copy(
            fromBackendId = EndpointId.LOCAL,
            fromDirectory = PathTools.normalizeRoot(source.toString()),
            toBackendId = EndpointId.LOCAL,
            toDirectory = PathTools.normalizeRoot(target.toString()),
            names = listOf("wanted.txt"),
        )

        assertTrue(outcome.ok, outcome.failures.toString())
        assertEquals(1, outcome.copied)
        assertEquals("wanted", Files.readString(target.resolve("wanted.txt")))
        assertTrue(!Files.exists(target.resolve("keep.txt")), "only the selection travels")
    }

    @Test
    fun a_folder_is_copied_with_everything_inside_it() = runBlocking {
        val source = tempDir("from-")
        val target = tempDir("to-")
        Files.createDirectories(source.resolve("album/nested"))
        Files.writeString(source.resolve("album/one.jpg"), "1")
        Files.writeString(source.resolve("album/nested/two.jpg"), "2")

        val browser = FolderBrowser(pool(), AppLog())
        val outcome = browser.copy(
            fromBackendId = EndpointId.LOCAL,
            fromDirectory = PathTools.normalizeRoot(source.toString()),
            toBackendId = EndpointId.LOCAL,
            toDirectory = PathTools.normalizeRoot(target.toString()),
            names = listOf("album"),
        )

        assertTrue(outcome.ok, outcome.failures.toString())
        assertEquals(2, outcome.copied)
        assertEquals("1", Files.readString(target.resolve("album/one.jpg")))
        assertEquals("2", Files.readString(target.resolve("album/nested/two.jpg")))
    }

    @Test
    fun copying_a_folder_into_itself_is_refused_before_anything_is_written() = runBlocking {
        val dir = tempDir("same-")
        Files.writeString(dir.resolve("a.txt"), "a")
        val normalized = PathTools.normalizeRoot(dir.toString())

        val browser = FolderBrowser(pool(), AppLog())
        val outcome = browser.copy(
            fromBackendId = EndpointId.LOCAL,
            fromDirectory = normalized,
            toBackendId = EndpointId.LOCAL,
            toDirectory = normalized,
            names = listOf("a.txt"),
        )

        assertTrue(!outcome.ok)
        assertTrue(outcome.failures.single().contains("同一个"), outcome.failures.toString())
    }

    @Test
    fun an_empty_selection_does_nothing_at_all() = runBlocking {
        val source = tempDir("from-")
        val target = tempDir("to-")
        val browser = FolderBrowser(pool(), AppLog())
        val outcome = browser.copy(
            fromBackendId = EndpointId.LOCAL,
            fromDirectory = PathTools.normalizeRoot(source.toString()),
            toBackendId = EndpointId.LOCAL,
            toDirectory = PathTools.normalizeRoot(target.toString()),
            names = emptyList(),
        )
        assertEquals(TransferOutcome(), outcome)
    }

    @Test
    fun a_pane_reports_what_it_cannot_read_instead_of_throwing() = runBlocking {
        val browser = FolderBrowser(pool(), AppLog())
        val failure = runCatching { browser.list(EndpointId.LOCAL, "/definitely/not/here") }
        assertTrue(failure.isFailure)
    }

    @Test
    fun the_session_opens_a_folder_and_remembers_what_is_selected() = runBlocking {
        val left = tempDir("left-")
        Files.writeString(left.resolve("a.txt"), "a")
        Files.writeString(left.resolve("b.txt"), "b")

        val log = AppLog()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes.add(it) }
        val pool = pool()
        val session = BrowserSession(FolderBrowser(pool, log), scope, log = log)

        session.open(Side.LEFT, EndpointId.LOCAL, PathTools.normalizeRoot(left.toString()))
        awaitUntil("the pane to list") { session.left.value.entries.size == 2 }

        session.toggle(Side.LEFT, "a.txt")
        assertTrue(session.left.value.isSelected("a.txt"))
        session.toggle(Side.LEFT, "a.txt")
        assertTrue(!session.left.value.isSelected("a.txt"))

        session.selectAll(Side.LEFT)
        assertEquals(setOf("a.txt", "b.txt"), session.left.value.selection)
        session.clearSelection(Side.LEFT)
        assertTrue(session.left.value.selection.isEmpty())
    }

    @Test
    fun the_arrow_copies_the_selection_into_the_other_pane() = runBlocking {
        val left = tempDir("left-")
        val right = tempDir("right-")
        Files.writeString(left.resolve("holiday.jpg"), "jpg")

        val log = AppLog()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes.add(it) }
        val session = BrowserSession(FolderBrowser(pool(), log), scope, log = log)

        session.open(Side.LEFT, EndpointId.LOCAL, PathTools.normalizeRoot(left.toString()))
        session.open(Side.RIGHT, EndpointId.LOCAL, PathTools.normalizeRoot(right.toString()))
        awaitUntil("both panes to list") {
            session.left.value.entries.isNotEmpty() && !session.right.value.loading
        }

        var outcome: TransferOutcome? = null
        session.transfer(Side.LEFT) { outcome = it }
        awaitUntil("the copy to finish") { outcome != null && !session.busy.value }

        assertTrue(assertNotNull(outcome).ok, outcome!!.failures.toString())
        assertEquals("jpg", Files.readString(right.resolve("holiday.jpg")))
        // The target was re-listed, so the new file is visible without a manual refresh.
        awaitUntil("the target to show the copy") { session.right.value.entries.any { it.relPath == "holiday.jpg" } }
    }

    @Test
    fun an_empty_pane_transfers_nothing_and_says_so() = runBlocking {
        val empty = tempDir("empty-")
        val target = tempDir("target-")
        val log = AppLog()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes.add(it) }
        val session = BrowserSession(FolderBrowser(pool(), log), scope, log = log)

        session.open(Side.LEFT, EndpointId.LOCAL, PathTools.normalizeRoot(empty.toString()))
        session.open(Side.RIGHT, EndpointId.LOCAL, PathTools.normalizeRoot(target.toString()))
        awaitUntil("the empty pane to list") { !session.left.value.loading }

        var outcome: TransferOutcome? = null
        session.transfer(Side.LEFT) { outcome = it }
        awaitUntil("the refusal") { outcome != null }
        assertTrue(!assertNotNull(outcome).ok)
    }

    @Test
    fun going_up_stops_at_the_root() = runBlocking {
        val log = AppLog()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes.add(it) }
        val session = BrowserSession(FolderBrowser(pool(), log), scope, log = log)

        session.open(Side.LEFT, EndpointId.LOCAL, "/")
        awaitUntil("the root to list") { !session.left.value.loading }
        session.up(Side.LEFT)
        assertEquals("/", session.left.value.path, "the root is its own parent")
    }

    @Test
    fun a_new_folder_is_created_and_a_second_one_does_not_overwrite_it() = runBlocking {
        val dir = tempDir("mkdir-")
        val session = BrowserSession(FolderBrowser(pool()), CoroutineScope(Dispatchers.IO + SupervisorJob()))
        session.open(Side.LEFT, EndpointId.LOCAL, dir.toAbsolutePath().toString().replace('\\', '/'))
        awaitUntil("the folder to list") { !session.left.value.loading }

        session.makeFolder(Side.LEFT)
        awaitUntil("the first folder") { Files.list(dir).use { it.count() } == 1L }
        session.makeFolder(Side.LEFT)
        awaitUntil("the second folder") { Files.list(dir).use { it.count() } == 2L }

        val names = Files.list(dir).use { stream -> stream.map { it.fileName.toString() }.toList() }
        assertEquals(2, names.toSet().size, "the second folder must not reuse the first one's name")
    }

    @Test
    fun a_deleted_row_goes_to_the_trash_and_a_renamed_one_keeps_its_content() = runBlocking {        val dir = tempDir("edit-")
        Files.write(dir.resolve("draft.txt"), "draft".toByteArray())
        val session = BrowserSession(FolderBrowser(pool()), CoroutineScope(Dispatchers.IO + SupervisorJob()))
        session.open(Side.LEFT, EndpointId.LOCAL, dir.toAbsolutePath().toString().replace('\\', '/'))
        awaitUntil("the file to list") { session.left.value.entries.any { it.relPath == "draft.txt" } }

        session.renameEntry(Side.LEFT, "draft.txt", "final.txt")
        awaitUntil("the rename") { Files.exists(dir.resolve("final.txt")) }
        assertEquals("draft", Files.readAllBytes(dir.resolve("final.txt")).decodeToString())

        session.delete(Side.LEFT, listOf("final.txt"), toTrash = true)
        awaitUntil("the delete") { !Files.exists(dir.resolve("final.txt")) }
        assertTrue(Files.isDirectory(dir.resolve(".cub_trash")), "the default delete has to be recoverable")
    }

    /** 多选 changes what a tap means, for folders as well as files. */
    @Test
    fun a_tap_enters_a_folder_unless_multi_select_is_on() = runBlocking {
        val dir = tempDir("multi-")
        Files.createDirectories(dir.resolve("sub"))
        Files.write(dir.resolve("a.txt"), "a".toByteArray())
        val root = PathTools.normalizeRoot(dir.toAbsolutePath().toString().replace('\\', '/'))
        val session = BrowserSession(FolderBrowser(pool()), CoroutineScope(Dispatchers.IO + SupervisorJob()))

        session.open(Side.LEFT, EndpointId.LOCAL, root)
        awaitUntil("the folder to list") { session.left.value.entries.any { it.relPath == "sub" } }

        val folder = session.left.value.entries.first { it.relPath == "sub" }
        session.tap(Side.LEFT, folder)
        awaitUntil("the folder to open") { session.left.value.path.endsWith("/sub") }

        // Back to the parent, and wait for its listing rather than for the address: the
        // address arrives synchronously, the entries do not.
        session.open(Side.LEFT, EndpointId.LOCAL, root)
        awaitUntil("the parent to list again") {
            session.left.value.path == root && session.left.value.entries.any { it.relPath == "sub" }
        }

        session.toggleMultiSelect()
        session.tap(Side.LEFT, session.left.value.entries.first { it.relPath == "sub" })

        assertEquals(setOf("sub"), session.left.value.selection, "in 多选 a folder is ticked, not entered")
        assertEquals(root, session.left.value.path, "in 多选 a folder tap must not navigate")
    }

    /** 取消 stops between chunks, and says so rather than looking like a failure. */
    @Test
    fun a_cancelled_copy_reports_itself_as_cancelled() = runBlocking {
        val from = tempDir("cancel-from-")
        val to = tempDir("cancel-to-")
        Files.write(from.resolve("big.bin"), ByteArray(4 * 1024 * 1024) { it.toByte() })

        val control = io.github.cubsync.core.engine.PauseGate()
        control.cancel()
        val outcome = FolderBrowser(pool(), AppLog()).copy(
            fromBackendId = EndpointId.LOCAL,
            fromDirectory = PathTools.normalizeRoot(from.toString()),
            toBackendId = EndpointId.LOCAL,
            toDirectory = PathTools.normalizeRoot(to.toString()),
            names = listOf("big.bin"),
            control = control,
        )

        assertTrue(outcome.cancelled, "the outcome has to say it was cancelled, not that it failed")
        assertTrue(outcome.ok, "a cancel is not a failure")
        assertEquals(0, outcome.copied)
        assertTrue(!Files.exists(to.resolve("big.bin")), "nothing is left behind under the real name")
    }

    private suspend fun awaitUntil(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(25)
        }
        throw AssertionError("timed out waiting for $what")
    }
}
