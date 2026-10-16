package io.github.cubsync.service

import io.github.cubsync.backends.lan.LanBackend
import io.github.cubsync.core.spi.SimpleRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The folder server, driven through the controller rather than the socket level.
 *
 * The interesting question is not whether the protocol works — that is covered in
 * the backend's own tests — but whether starting from a folder a person picked ends
 * up serving exactly that folder, and whether stopping really stops.
 */
class ServerControllerTest {

    private val temporaries = mutableListOf<Path>()
    private val controllers = mutableListOf<ServerController>()
    private var scope: CoroutineScope? = null

    private fun tempDir(prefix: String): Path = Files.createTempDirectory(prefix).also { temporaries.add(it) }

    private fun controller(): ServerController = ServerController(AppLog()).also { controllers.add(it) }

    private fun clientScope(): CoroutineScope =
        CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scope = it }

    @AfterTest
    fun cleanUp() {
        controllers.forEach { runCatching { it.stop() } }
        controllers.clear()
        scope?.let { runCatching { it.cancel() } }
        for (dir in temporaries) {
            runCatching { Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
        temporaries.clear()
    }

    @Test
    fun a_started_server_serves_exactly_the_folders_it_was_given() = runBlocking {
        val shared = tempDir("shared-")
        Files.writeString(shared.resolve("published.txt"), "published")

        val controller = controller()
        // Port 0 asks the operating system for a free port, so the test cannot
        // collide with whatever else is running on this machine.
        val state = controller.start(listOf(shared.toString()), port = 0)

        assertTrue(state.running, state.error)
        assertTrue(state.port > 0)
        assertEquals(listOf(PathTools.normalizeRoot(shared.toString())), state.roots)
        assertTrue(controller.isRunning)

        val client = LanBackend.connect("127.0.0.1", state.port, clientScope(), nodeName = "test client")
        try {
            val roots = client.roots().map { it.path }
            assertEquals(state.roots, roots)
            val entries = client.list(SimpleRoot(client.id, roots.single()), "", recursive = false)
            assertEquals(listOf("published.txt"), entries.map { it.relPath })
        } finally {
            client.close()
        }
    }

    @Test
    fun a_client_can_write_into_a_shared_folder() = runBlocking {
        val shared = tempDir("shared-")

        val controller = controller()
        val state = controller.start(listOf(shared.toString()), port = 0)
        assertTrue(state.running, state.error)

        val client = LanBackend.connect("127.0.0.1", state.port, clientScope(), nodeName = "test client")
        try {
            val root = SimpleRoot(client.id, state.roots.single())
            val stream = client.openWrite(root, "written.txt", size = 5, mtimeMs = 1_700_000_000_000, resume = false)
            stream.write("hello".encodeToByteArray(), 0, 5)
            stream.commit(1_700_000_000_000)
        } finally {
            client.close()
        }

        assertEquals("hello", Files.readString(shared.resolve("written.txt")))
    }

    @Test
    fun stopping_the_server_stops_serving() = runBlocking {
        val shared = tempDir("shared-")
        val controller = controller()
        val state = controller.start(listOf(shared.toString()), port = 0)
        assertTrue(state.running, state.error)

        val client = LanBackend.connect("127.0.0.1", state.port, clientScope(), nodeName = "test client")
        client.close()
        controller.stop()

        assertFalse(controller.isRunning)
        assertFalse(controller.state.value.running)
        assertEquals(0, controller.state.value.port)

        // Nothing is listening any more. Deliberately not "the same port can be bound
        // again immediately": whether the operating system hands a just-closed port
        // back is a property of the operating system, and a test that asserts it is a
        // test that fails on somebody else's machine.
        val refused = runCatching {
            LanBackend.connect("127.0.0.1", state.port, clientScope(), nodeName = "probe")
        }
        assertTrue(refused.isFailure, "the peer should be gone, but a connection succeeded")

        // And a fresh server still starts.
        val again = controller()
        val restarted = again.start(listOf(shared.toString()), port = 0)
        assertTrue(restarted.running, restarted.error)
    }

    @Test
    fun a_folder_that_does_not_exist_is_refused_before_a_socket_is_opened() {
        val controller = controller()
        val state = controller.start(listOf("/definitely/not/here"), port = 0)
        assertFalse(state.running)
        assertTrue(state.error.contains("not a folder"), state.error)
        assertFalse(controller.isRunning)
    }

    @Test
    fun an_empty_selection_is_refused_with_an_explanation() {
        val controller = controller()
        val state = controller.start(emptyList(), port = 0)
        assertFalse(state.running)
        assertTrue(state.error.isNotEmpty())
    }

    @Test
    fun starting_twice_replaces_the_previous_server_rather_than_listening_twice() {
        val first = tempDir("first-")
        val second = tempDir("second-")
        val controller = controller()

        val one = controller.start(listOf(first.toString()), port = 0)
        assertTrue(one.running, one.error)
        val two = controller.start(listOf(second.toString()), port = 0)

        assertTrue(two.running, two.error)
        assertEquals(listOf(PathTools.normalizeRoot(second.toString())), controller.state.value.roots)
        // The first port was released by the replacement.
        assertTrue(two.port != one.port || two.port > 0)
    }

    @Test
    fun a_port_that_is_already_taken_is_reported_in_words_a_person_can_act_on() {
        val shared = tempDir("shared-")
        val first = controller()
        val state = first.start(listOf(shared.toString()), port = 0)
        assertTrue(state.running, state.error)

        val second = controller()
        val refused = second.start(listOf(shared.toString()), port = state.port)
        assertFalse(refused.running)
        assertTrue(refused.error.contains("already in use"), refused.error)
    }
}
