package com.topz.stitchdraw.files

import com.topz.stitchdraw.core.MiniJson
import com.topz.stitchdraw.core.asBool
import com.topz.stitchdraw.core.asDouble
import com.topz.stitchdraw.core.asInt
import com.topz.stitchdraw.core.asList
import com.topz.stitchdraw.core.asMap
import com.topz.stitchdraw.core.asString
import com.topz.stitchdraw.embroidery.*
import com.topz.stitchdraw.vector.Vec2
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class StdFormatException(message: String) : Exception(message)

/** A loaded project: the editable design plus project-level metadata. */
data class StdProject(
    val name: String,
    val design: EmbroideryDesign,
    val createdMs: Long = 0L,
    val modifiedMs: Long = 0L,
    /** Optional VecDraw-compatible vector document JSON; kept opaque so this JVM module stays Android-free. */
    val vectorArtJson: String? = null,
)

/**
 * Native StitchDraw project format (.STD).
 *
 * Binary container (little endian):
 *   0   4  magic "STD\u0001"... see [MAGIC]
 *   4   2  format version (u16)
 *   6   2  flags (u16, reserved = 0)
 *   8   4  payload length (u32)
 *  12   4  CRC-32 of the payload bytes
 *  16   n  payload = GZIP(UTF-8 JSON)
 *
 * The JSON holds the editable design (hoop, threads, layers, objects with all digitizing properties)
 * AND the generated stitch data, so both survive a round trip. The stitch data is derived and is
 * regenerated from the objects when absent, so older/partial files remain openable.
 *
 * Compatibility rules (never break existing files):
 *  - Readers ignore unknown JSON fields; writers only ever add fields.
 *  - Missing fields take defaults. Unknown stitch types degrade to RUNNING instead of failing.
 *  - Files with a newer major version than [VERSION] are rejected with a clear message, not corrupted.
 */
object StdFormat {
    val MAGIC = byteArrayOf('S'.code.toByte(), 'T'.code.toByte(), 'D'.code.toByte(), 'W'.code.toByte())
    const val VERSION = 1
    const val HEADER_SIZE = 16
    const val MAX_PAYLOAD = 256 * 1024 * 1024

    fun encode(project: StdProject, plan: StitchPlan? = null): ByteArray {
        val p = plan ?: StitchPlanner.plan(project.design)
        val json = MiniJson.write(toJson(project, p)).toByteArray(Charsets.UTF_8)
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(json) }
        val payload = bos.toByteArray()
        val crc = CRC32().also { it.update(payload) }
        val out = ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put(MAGIC).putShort(VERSION.toShort()).putShort(0).putInt(payload.size).putInt(crc.value.toInt())
        out.put(payload)
        return out.array()
    }

    fun decode(bytes: ByteArray): StdProject {
        if (bytes.size < HEADER_SIZE) throw StdFormatException("File too small to be a StitchDraw project")
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { b.get(it) }
        if (!magic.contentEquals(MAGIC)) throw StdFormatException("Not a StitchDraw .STD file")
        val version = b.short.toInt() and 0xFFFF
        b.short // flags
        val len = b.int
        val crc = b.int
        if (version > VERSION) throw StdFormatException("Project was saved by a newer StitchDraw (format $version)")
        if (len < 0 || len > MAX_PAYLOAD || len > bytes.size - HEADER_SIZE) throw StdFormatException("Project file is truncated")
        val payload = bytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + len)
        val actual = CRC32().also { it.update(payload) }.value.toInt()
        if (actual != crc) throw StdFormatException("Project file is corrupted (checksum mismatch)")
        val text = try {
            GZIPInputStream(ByteArrayInputStream(payload)).use { String(it.readBytes(), Charsets.UTF_8) }
        } catch (e: Exception) { throw StdFormatException("Project data cannot be decompressed") }
        val root = try { MiniJson.parse(text).asMap() } catch (e: Exception) { throw StdFormatException("Project data is invalid") }
        return fromJson(root)
    }

    /** Quick check without decoding the payload. */
    fun looksLikeStd(bytes: ByteArray) =
        bytes.size >= HEADER_SIZE && bytes.copyOfRange(0, 4).contentEquals(MAGIC)

    // ---- JSON mapping -----------------------------------------------------------------------

    private fun toJson(p: StdProject, plan: StitchPlan): Map<String, Any?> {
        val d = p.design
        return linkedMapOf(
            "app" to "StitchDraw by Topz",
            "version" to VERSION,
            "name" to p.name,
            "created" to p.createdMs,
            "modified" to p.modifiedMs,
            "vectorArt" to p.vectorArtJson,
            "hoop" to mapOf("w" to d.hoop.widthMm, "h" to d.hoop.heightMm, "name" to d.hoop.name),
            "threads" to d.threads.map { mapOf("rgb" to it.rgb, "name" to it.name, "brand" to it.brand) },
            "layers" to d.layers.map { mapOf("id" to it.id, "name" to it.name, "visible" to it.visible, "locked" to it.locked) },
            "objects" to d.objects.map { o ->
                mapOf(
                    "id" to o.id, "name" to o.name, "type" to o.type.name,
                    "thread" to o.threadIndex, "layer" to o.layerId,
                    "path" to o.path.flatMap { listOf(it.x, it.y) },
                    "holes" to o.holes.map { h -> h.flatMap { listOf(it.x, it.y) } },
                    "params" to mapOf(
                        "length" to o.params.stitchLengthMm, "tieIn" to o.params.tieIn, "tieOut" to o.params.tieOut,
                        "density" to o.params.densityMm, "width" to o.params.widthMm, "angle" to o.params.angleDeg,
                        "underlay" to o.params.underlay, "pull" to o.params.pullCompMm,
                    ),
                )
            },
            "nextObjectId" to d.nextObjectId,
            "nextLayerId" to d.nextLayerId,
            "stitches" to stitchesToJson(plan),
        )
    }

    /** Delta-encoded to keep files small. */
    private fun stitchesToJson(plan: StitchPlan): Map<String, Any?> {
        val dx = ArrayList<Int>(plan.stitches.size); val dy = ArrayList<Int>(plan.stitches.size)
        val cmd = ArrayList<Int>(plan.stitches.size)
        var px = 0; var py = 0
        for (s in plan.stitches) {
            dx.add(s.x - px); dy.add(s.y - py); cmd.add(s.cmd.ordinal); px = s.x; py = s.y
        }
        return mapOf("dx" to dx, "dy" to dy, "cmd" to cmd, "thread" to plan.threadIndexOf.toList(), "obj" to plan.objectOf.toList())
    }

    private fun fromJson(r: Map<String, Any?>): StdProject {
        val defaults = EmbroideryDesign()
        val hoopM = r["hoop"].asMap()
        val hoop = if (hoopM.isEmpty()) defaults.hoop else
            Hoop(hoopM["w"].asDouble(100.0).toFloat(), hoopM["h"].asDouble(100.0).toFloat(), hoopM["name"].asString("Custom"))
        val threads = r["threads"].asList().map { t ->
            val m = t.asMap(); ThreadColor(m["rgb"].asInt(0) and 0xFFFFFF, m["name"].asString(), m["brand"].asString())
        }.ifEmpty { defaults.threads }
        val layers = r["layers"].asList().map { l ->
            val m = l.asMap(); Layer(m["id"].asInt(1), m["name"].asString("Layer"), m["visible"].asBool(true), m["locked"].asBool(false))
        }.ifEmpty { defaults.layers }
        val objects = r["objects"].asList().map { o ->
            val m = o.asMap()
            val coords = m["path"].asList().map { it.asDouble().toFloat() }
            val path = (0 until coords.size / 2).map { Vec2(coords[it * 2], coords[it * 2 + 1]) }
            val holes = m["holes"].asList().map { h ->
                val c = h.asList().map { it.asDouble().toFloat() }
                (0 until c.size / 2).map { Vec2(c[it * 2], c[it * 2 + 1]) }
            }.filter { it.size >= 3 }
            val pm = m["params"].asMap()
            val params = StitchParams(
                stitchLengthMm = pm["length"].asDouble(2.5).toFloat().coerceIn(StitchParams.MIN_LENGTH, StitchParams.MAX_LENGTH),
                tieIn = pm["tieIn"].asBool(true), tieOut = pm["tieOut"].asBool(true),
                // fields added after format v1 shipped: absent in older files, so they take the defaults
                densityMm = pm["density"].asDouble(0.4).toFloat().coerceIn(StitchParams.MIN_DENSITY, StitchParams.MAX_DENSITY),
                widthMm = pm["width"].asDouble(3.0).toFloat().coerceIn(StitchParams.MIN_WIDTH, StitchParams.MAX_WIDTH),
                angleDeg = pm["angle"].asDouble(45.0).toFloat(),
                underlay = pm["underlay"].asBool(true),
                pullCompMm = pm["pull"].asDouble(0.2).toFloat().coerceIn(0f, StitchParams.MAX_PULL),
            )
            val type = StitchType.values().firstOrNull { it.name == m["type"].asString() } ?: StitchType.RUNNING
            DesignObject(
                id = m["id"].asInt(), name = m["name"].asString("Object"), type = type, path = path,
                threadIndex = m["thread"].asInt(0).coerceIn(0, threads.size - 1),
                layerId = m["layer"].asInt(layers.first().id), params = params, holes = holes,
            )
        }
        val design = EmbroideryDesign(
            hoop = hoop, threads = threads, layers = layers, objects = objects,
            nextObjectId = maxOf(r["nextObjectId"].asInt(1), (objects.maxOfOrNull { it.id } ?: 0) + 1),
            nextLayerId = maxOf(r["nextLayerId"].asInt(2), (layers.maxOfOrNull { it.id } ?: 0) + 1),
        )
        return StdProject(r["name"].asString("Untitled"), design, r["created"].asDouble().toLong(), r["modified"].asDouble().toLong(), r["vectorArt"].asString().takeIf { it.isNotBlank() })
    }
}
