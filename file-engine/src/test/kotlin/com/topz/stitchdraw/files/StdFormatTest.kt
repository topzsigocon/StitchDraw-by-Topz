package com.topz.stitchdraw.files

import com.topz.stitchdraw.embroidery.*
import com.topz.stitchdraw.vector.Vec2
import org.junit.Assert.*
import org.junit.Test

class StdFormatTest {
    private fun sample(): StdProject {
        var d = EmbroideryDesign().addLayer("Details").addThread(ThreadColor(0x123456, "Custom", "Madeira"))
        d = d.addObject(DesignObject(1, "Outline", StitchType.TRIPLE_RUNNING,
            listOf(Vec2(0f, 0f), Vec2(20f, 0f), Vec2(20f, 15.5f)), 2, 2, StitchParams(1.8f, false, true)))
        d = d.addObject(DesignObject(2, "Line", StitchType.RUNNING, listOf(Vec2(1f, 1f), Vec2(30f, 5f)), 8, 1))
        return StdProject("Test", d.copy(hoop = Hoop.HOOP_130))
    }

    @Test fun roundTripPreservesEverything() {
        val p = sample()
        val back = StdFormat.decode(StdFormat.encode(p))
        assertEquals(p.design, back.design)
        assertEquals("Test", back.name)
    }

    @Test fun stitchDataIsStoredAndRegeneratesIdentically() {
        val p = sample()
        val a = StitchPlanner.plan(p.design)
        val b = StitchPlanner.plan(StdFormat.decode(StdFormat.encode(p, a)).design)
        assertEquals(a.stitches, b.stitches)
    }

    @Test fun corruptionIsDetected() {
        val bytes = StdFormat.encode(sample())
        bytes[bytes.size - 3] = (bytes[bytes.size - 3] + 1).toByte()
        assertThrows(StdFormatException::class.java) { StdFormat.decode(bytes) }
    }

    @Test fun rejectsForeignAndTruncatedFiles() {
        assertThrows(StdFormatException::class.java) { StdFormat.decode(ByteArray(40)) }
        assertThrows(StdFormatException::class.java) { StdFormat.decode(ByteArray(3)) }
        val bytes = StdFormat.encode(sample())
        assertThrows(StdFormatException::class.java) { StdFormat.decode(bytes.copyOf(bytes.size - 10)) }
    }

    @Test fun newerVersionRejectedCleanly() {
        val bytes = StdFormat.encode(sample())
        bytes[4] = 9
        val e = assertThrows(StdFormatException::class.java) { StdFormat.decode(bytes) }
        assertTrue(e.message!!.contains("newer"))
    }

    @Test fun unknownStitchTypeDegradesAndMissingFieldsDefault() {
        // hand-built minimal JSON as an "old/foreign" file
        val json = """{"name":"Old","objects":[{"id":5,"type":"FUTURE_SATIN","path":[0,0,10,0],"extra":1}]}"""
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(json.toByteArray()) }
        val payload = bos.toByteArray()
        val crc = java.util.zip.CRC32().also { it.update(payload) }
        val buf = java.nio.ByteBuffer.allocate(16 + payload.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.put(StdFormat.MAGIC).putShort(1).putShort(0).putInt(payload.size).putInt(crc.value.toInt()).put(payload)
        val p = StdFormat.decode(buf.array())
        assertEquals(StitchType.RUNNING, p.design.objects[0].type)
        assertEquals(6, p.design.nextObjectId)
        assertTrue(p.design.threads.isNotEmpty())
    }

    private class MemStore : ByteStore {
        val files = LinkedHashMap<String, ByteArray>()
        override fun read(location: String) = files[location] ?: throw java.io.FileNotFoundException(location)
        override fun write(location: String, data: ByteArray) { files[location] = data }
        override fun exists(location: String) = location in files
        override fun delete(location: String) { files.remove(location) }
        override fun list(dir: String) = files.keys.filter { it.startsWith("$dir/") }.map { it.removePrefix("$dir/") }
    }

    @Test fun repositoryKeepsRollingBackupsAndRecovers() {
        val store = MemStore(); var t = 1000L
        val repo = ProjectRepository(store, "bk", keepBackups = 3, clock = { t++ })
        repeat(6) { repo.save("a/x.std", sample().copy(name = "v$it")) }
        assertEquals(3, repo.backups("a/x.std").size)
        assertEquals("v5", repo.open("a/x.std").name)
        store.files["a/x.std"] = ByteArray(50) // corrupt main
        assertEquals("v4", repo.recover("a/x.std")!!.name)
    }

    @Test fun backupListShowsAllProjectsNewestFirstAndFlagsDamaged() {
        val store = MemStore(); var t = 5000L
        val repo = ProjectRepository(store, "bk", keepBackups = 5, clock = { t++ })
        repeat(3) { repo.save("a/one.std", sample().copy(name = "one-v$it")) }
        repeat(2) { repo.save("a/two-part.std", sample().copy(name = "two-v$it")) }       // a name with a dash in it
        val list = repo.allBackups()
        assertEquals(3, list.size)                                                          // 2 backups of one, 1 of two-part
        assertTrue(list.zipWithNext().all { (a, b) -> a.timeMs >= b.timeMs })
        assertEquals("two-part", list.first().base)
        assertTrue(list.all { it.valid && it.objectCount == sample().design.objects.size })
        val victim = list.last().file
        store.files["bk/$victim"] = ByteArray(40)
        val again = repo.allBackups()
        assertFalse(again.first { it.file == victim }.valid)
        assertEquals("one-v1", repo.restore(list.first { it.base == "one" && it.file != victim }.file).name)
    }

    @Test fun restoreReturnsTheProjectAndRejectsBadNames() {
        val store = MemStore(); var t = 9000L
        val repo = ProjectRepository(store, "bk", clock = { t++ })
        repo.save("a/x.std", sample().copy(name = "first")); repo.save("a/x.std", sample().copy(name = "second"))
        val b = repo.allBackups().single()
        assertEquals("first", repo.restore(b.file).name)
        for (bad in listOf("../x.std.bak", "a/b.std.bak", "x.txt")) try { repo.restore(bad); fail() } catch (e: IllegalArgumentException) { }
        repo.deleteBackup(b.file); assertTrue(repo.allBackups().isEmpty())
        repo.deleteBackup("../a/x.std"); assertTrue(store.exists("a/x.std"))               // never deletes outside the backup folder
    }
}

class StdFormatFillTest {
    @Test fun newParamsRoundTrip() {
        val params = StitchParams(2.0f, true, false, densityMm = 0.55f, widthMm = 6.5f, angleDeg = 120f, underlay = false, pullCompMm = 0.45f)
        val d = EmbroideryDesign().addObject(DesignObject(1, "S", StitchType.SATIN, listOf(Vec2(0f, 0f), Vec2(10f, 0f)), 0, 1, params))
            .addObject(DesignObject(2, "T", StitchType.TATAMI, listOf(Vec2(0f, 0f), Vec2(10f, 0f), Vec2(5f, 8f)), 1, 1, params))
        val back = StdFormat.decode(StdFormat.encode(StdProject("x", d))).design
        assertEquals(d, back)
    }

    @Test fun v1FilesWithoutNewFieldsStillOpenWithDefaults() {
        val json = """{"name":"Old","objects":[{"id":1,"type":"SATIN","path":[0,0,10,0],"params":{"length":2.0,"tieIn":true,"tieOut":true}}]}"""
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(json.toByteArray()) }
        val payload = bos.toByteArray()
        val crc = java.util.zip.CRC32().also { it.update(payload) }
        val buf = java.nio.ByteBuffer.allocate(16 + payload.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.put(StdFormat.MAGIC).putShort(1).putShort(0).putInt(payload.size).putInt(crc.value.toInt()).put(payload)
        val o = StdFormat.decode(buf.array()).design.objects[0]
        assertEquals(StitchType.SATIN, o.type)
        assertEquals(StitchParams().densityMm, o.params.densityMm, 0f)
        assertEquals(StitchParams().widthMm, o.params.widthMm, 0f)
    }

    @Test fun holesRoundTrip() {
        val outer = listOf(Vec2(0f, 0f), Vec2(30f, 0f), Vec2(30f, 30f), Vec2(0f, 30f))
        val hole = listOf(Vec2(10f, 10f), Vec2(20f, 10f), Vec2(20f, 20f), Vec2(10f, 20f))
        val d = EmbroideryDesign().addObject(DesignObject(1, "ring", StitchType.TATAMI, outer, 0, 1, holes = listOf(hole)))
        assertEquals(d, StdFormat.decode(StdFormat.encode(StdProject("x", d))).design)
    }
}
