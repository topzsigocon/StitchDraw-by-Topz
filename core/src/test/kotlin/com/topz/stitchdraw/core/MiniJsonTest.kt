package com.topz.stitchdraw.core

import org.junit.Assert.*
import org.junit.Test

class MiniJsonTest {
    @Test fun roundTripNested() {
        val v = mapOf("a" to listOf(1, 2.5, "x\n\"y\"", null, true), "b" to mapOf("c" to -3))
        val back = MiniJson.parse(MiniJson.write(v, pretty = true))
        assertEquals(listOf(1L, 2.5, "x\n\"y\"", null, true), back.asMap()["a"])
        assertEquals(-3, back.asMap()["b"].asMap()["c"].asInt())
    }
    @Test fun unicodeEscape() = assertEquals("é", MiniJson.parse("\"\\u00e9\""))
    @Test(expected = MiniJson.JsonException::class) fun rejectsTrailingGarbage() { MiniJson.parse("{} x") }
    @Test(expected = MiniJson.JsonException::class) fun rejectsUnterminated() { MiniJson.parse("[1,2") }
    @Test fun emptyContainers() {
        assertEquals(emptyList<Any?>(), MiniJson.parse("[ ]"))
        assertEquals(emptyMap<String, Any?>(), MiniJson.parse("{ }"))
    }
}
