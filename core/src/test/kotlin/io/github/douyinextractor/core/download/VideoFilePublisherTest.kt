package io.github.douyinextractor.core.download

import java.io.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VideoFilePublisherTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture(): File = temporary.newFile().apply {
        DataOutputStream(outputStream()).use {
            it.writeInt(16); it.writeBytes("ftyp"); it.writeBytes("isom0000")
            it.writeInt(131080); it.writeBytes("mdat"); it.write(ByteArray(131072))
            it.writeInt(9); it.writeBytes("moov"); it.writeByte(0)
        }
    }
    private class Target(private val failAt: String = "") : VideoFileTarget {
        val bytes = ByteArrayOutputStream()
        var created = false
        var committed = false
        var removed = false
        var closed = false
        override fun create() { created = true; if (failAt == "create") throw IOException() }
        override fun open(): OutputStream {
            if (failAt == "open") throw IOException()
            return object : FilterOutputStream(bytes) {
                override fun write(b: ByteArray, off: Int, len: Int) { if (failAt == "write") throw IOException(); out.write(b, off, len) }
                override fun close() { closed = true; if (failAt == "close") throw IOException(); super.close() }
            }
        }
        override fun checkSize(expected: Long) { check(closed); if (failAt == "size") throw IOException(); check(bytes.size().toLong() == expected) }
        override fun commit(): String { check(closed); if (failAt == "commit") throw IOException(); committed = true; return "content://test/new" }
        override fun rollback() { removed = true; bytes.reset() }
    }
    @Test fun `only committed and closed complete output triggers success`() = runBlocking {
        val file = fixture(); val target = Target(); var completed = false
        val uri = VideoFilePublisher().publish(file, target, {}) { completed = true; assertTrue(target.committed) }
        assertEquals("content://test/new", uri)
        assertTrue(completed); assertFalse(target.removed)
        assertArrayEquals(file.readBytes(), target.bytes.toByteArray())
    }
    @Test fun `every failed save stage rolls back without success`() = runBlocking {
        for (stage in listOf("create", "open", "write", "close", "size", "commit")) {
            val target = Target(stage)
            try { VideoFilePublisher().publish(fixture(), target, {}) { fail("No success for $stage") }; fail() }
            catch (error: DownloadException) { assertEquals(DownloadProblem.STORAGE, error.problem) }
            assertTrue("No cleanup for $stage", target.removed); assertFalse(target.committed)
        }
    }
    @Test fun `cancelling during copy removes the destination`() = runBlocking {
        val target = Target()
        val job = launch {
            val self = currentCoroutineContext().job
            VideoFilePublisher().publish(fixture(), target, { self.cancel() }) { fail() }
        }
        job.join()
        assertTrue(job.isCancelled); assertTrue(target.removed); assertFalse(target.committed)
    }
    @Test fun `cancel at commit boundary preserves completed file and notification`() = runBlocking {
        val target = Target(); var completed = false
        val job = launch {
            val self = currentCoroutineContext().job
            VideoFilePublisher().publish(fixture(), object : VideoFileTarget by target {
                override fun commit(): String { self.cancel(); return target.commit() }
            }, {}) { completed = true }
        }
        job.join()
        assertTrue(completed); assertTrue(target.committed); assertFalse(target.removed)
    }
    @Test fun `invalid input never creates a public file`() = runBlocking {
        val target = Target()
        try { VideoFilePublisher().publish(temporary.newFile().apply { writeText("<html>error</html>") }, target, {}) { fail() }; fail() }
        catch (error: DownloadException) { assertEquals(DownloadProblem.NOT_VIDEO, error.problem) }
        assertFalse(target.created); assertFalse(target.committed)
    }
}
