package com.sjstudioz.parkingpin.trace

import com.sjstudioz.parkingpin.domain.trace.TraceEvent
import com.sjstudioz.parkingpin.domain.trace.TraceLabel
import com.sjstudioz.parkingpin.domain.trace.TraceMode
import com.sjstudioz.parkingpin.domain.trace.TraceSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The rolling cap and the durability of the traces directory.
 *
 * docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §9 requires a cap so a recorder left on all
 * day cannot fill storage, and requires the eviction to be visible rather than silent —
 * a field run that looks thin because the cap ate half of it has to be able to say so.
 */
class FileTraceStoreTest {

    @get:Rule val temporaryFolder = TemporaryFolder()

    private val startMillis = 1_700_000_000_000L

    private fun directory(): File = temporaryFolder.newFolder("traces")

    private fun session(id: String, startedAt: Long = startMillis, eventCount: Int = 1) = TraceSession(
        sessionId = id,
        deviceModel = "SM-G996N",
        osVersion = "15 (SDK 35)",
        appVersion = "0.1.0 (1)",
        startedAt = startedAt,
        endedAt = startedAt,
        events = List(eventCount) { TraceEvent.location(startedAt + it, 10f, null, null) },
    )

    /**
     * `prune` orders by file modification time, which the filesystem reports at a coarser
     * resolution than the test writes at. Written explicitly so the oldest session is
     * unambiguously the oldest.
     */
    private fun writeAged(store: FileTraceStore, id: String, ageMillis: Long, eventCount: Int = 1) {
        store.write(session(id, eventCount = eventCount))
        File(temporaryFolder.root, "traces/$id.json").setLastModified(System.currentTimeMillis() - ageMillis)
    }

    @Test
    fun `a written session reads back whole`() {
        // Arrange
        val store = FileTraceStore(directory())
        val original = session("session-a", eventCount = 3)

        // Act
        val failure = store.write(original)
        val readBack = store.read("session-a")

        // Assert
        assertNull(failure)
        assertEquals(original, readBack)
    }

    @Test
    fun `sessions list newest first`() {
        // Arrange — the labelling UI shows the trip that just ended at the top.
        val store = FileTraceStore(directory())
        store.write(session("older", startedAt = startMillis))
        store.write(session("newer", startedAt = startMillis + 60_000L))

        // Act
        val listed = store.list()

        // Assert
        assertEquals(listOf("newer", "older"), listed.map { it.sessionId })
    }

    @Test
    fun `the session cap discards the oldest and reports how many`() {
        // Arrange — two over a cap of three.
        val store = FileTraceStore(directory(), maxSessions = 3)
        listOf(500L, 400L, 300L, 200L, 100L).forEachIndexed { index, age ->
            writeAged(store, "session-$index", ageMillis = age * 1_000L)
        }

        // Act
        val discarded = store.prune(keepSessionId = null)

        // Assert
        assertEquals(2, discarded)
        assertEquals(3, store.list().size)
        assertNull(store.read("session-0"))
        assertNull(store.read("session-1"))
        assertNotNull(store.read("session-4"))
    }

    @Test
    fun `the byte cap binds even when the session count does not`() {
        // Arrange — the case §9 is actually written against: a handful of recordings that
        // each grew all day. A count cap alone lets them grow without limit.
        val store = FileTraceStore(directory(), maxSessions = 100, maxTotalBytes = 4_000L)
        writeAged(store, "fat-old", ageMillis = 200_000L, eventCount = 200)
        writeAged(store, "fat-new", ageMillis = 100_000L, eventCount = 200)

        // Act
        val discarded = store.prune(keepSessionId = null)

        // Assert — the newest survives even though it alone is over the byte cap. A cap
        // that empties the directory to satisfy a limit it cannot satisfy is not a cap.
        assertEquals(1, discarded)
        assertEquals(listOf("fat-new"), store.list().map { it.sessionId })
        assertTrue(store.list().single().events.isNotEmpty())
    }

    @Test
    fun `the session being recorded into is never the one discarded`() {
        // Arrange — evicting the open session would lose the trip in progress, which is
        // the only one that cannot be re-recorded.
        val store = FileTraceStore(directory(), maxSessions = 1)
        writeAged(store, "finished", ageMillis = 200_000L)
        writeAged(store, "open-now", ageMillis = 100_000L)

        // Act
        val discarded = store.prune(keepSessionId = "open-now")

        // Assert
        assertEquals(1, discarded)
        assertEquals(listOf("open-now"), store.list().map { it.sessionId })
    }

    @Test
    fun `nothing is discarded while inside both caps`() {
        // Arrange
        val store = FileTraceStore(directory(), maxSessions = 10)
        writeAged(store, "only", ageMillis = 1_000L)

        // Act
        val discarded = store.prune(keepSessionId = null)

        // Assert
        assertEquals(0, discarded)
        assertEquals(1, store.list().size)
    }

    @Test
    fun `a corrupt file is skipped, never thrown at a detection callback`() {
        // Arrange — a half-written file from a process that died, or a hand-edited one.
        val folder = directory()
        val store = FileTraceStore(folder)
        store.write(session("good"))
        File(folder, "corrupt.json").writeText("{ not json")

        // Act
        val listed = store.list()

        // Assert
        assertEquals(listOf("good"), listed.map { it.sessionId })
        assertNull(store.read("corrupt"))
    }

    @Test
    fun `a session written at another schema version is not half-read`() {
        // Arrange — an upgrade must reject an old payload outright, not decode the fields
        // that happen to still line up.
        val folder = directory()
        val store = FileTraceStore(folder)
        store.write(session("stale"))
        val stale = File(folder, "stale.json")
        stale.writeText(stale.readText().replace("\"schemaVersion\": 1", "\"schemaVersion\": 99"))

        // Act & Assert
        assertNull(store.read("stale"))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `a session id that could escape the traces directory is refused`() {
        // Arrange — ids are generated UUIDs, so this can only be a caller that made one
        // up; the id still reaches the filesystem.
        val store = FileTraceStore(directory())

        // Act
        val failure = store.write(session("../../escape"))

        // Assert
        assertEquals("InvalidSessionId", failure)
        assertFalse(File(temporaryFolder.root, "escape.json").exists())
    }

    @Test
    fun `a label applied later replaces the stored one`() {
        // Arrange
        val store = FileTraceStore(directory())
        store.write(session("session-a"))

        // Act
        val stored = requireNotNull(store.read("session-a"))
        store.write(stored.copy(label = TraceLabel(TraceMode.BUS, parked = false, note = "환승 포함")))

        // Assert
        val relabelled = requireNotNull(store.read("session-a"))
        assertEquals(TraceMode.BUS, relabelled.label.mode)
        assertEquals(false, relabelled.label.parked)
        assertEquals("환승 포함", relabelled.label.note)
        assertTrue(relabelled.isLabelled)
    }
    @Test
    fun `deleting a session removes it once, and says which call did it`() {
        // Arrange — the counter on the other side of this must not move twice for the same
        // session, so the store has to report whether this call was the one that removed
        // the file. A file already gone is the wanted outcome, never an error.
        val store = FileTraceStore(directory())
        store.write(session("session-a", eventCount = 2))

        // Act
        val first = store.delete("session-a")
        val second = store.delete("session-a")

        // Assert
        assertTrue(first)
        assertFalse(second)
        assertNull(store.read("session-a"))
        assertFalse(store.delete("../escape"))
    }

    @Test
    fun `replacing a session writes the fragments before it removes the original`() {
        // Arrange — §9 "원본은 조각으로 대체된다". Fragments first: a process death between
        // the two leaves the events on disk twice, which a person can see and undo, while
        // the other order would lose a recorded trip outright.
        val store = FileTraceStore(directory())
        val parent = session("session-parent", eventCount = 4)
        store.write(parent)
        val leading = session("fragment-a", eventCount = 2)
        val trailing = session("fragment-b", startedAt = startMillis + 2, eventCount = 2)

        // Act
        val failure = store.replace("session-parent", listOf(leading, trailing))

        // Assert
        assertNull(failure)
        assertNull(store.read("session-parent"))
        assertNotNull(store.read("fragment-a"))
        assertNotNull(store.read("fragment-b"))
        assertEquals(2, store.list().size)
    }

    @Test
    fun `replacing a session that is not there reports it and writes nothing`() {
        // Arrange
        val store = FileTraceStore(directory())

        // Act
        val failure = store.replace("never-recorded", listOf(session("fragment-a", eventCount = 2)))

        // Assert
        assertEquals("NotFound", failure)
        assertTrue(store.list().isEmpty())
    }
}
