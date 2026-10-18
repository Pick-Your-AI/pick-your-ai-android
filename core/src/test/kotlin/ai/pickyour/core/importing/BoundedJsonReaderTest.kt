package ai.pickyour.core.importing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.math.BigDecimal

class BoundedJsonReaderTest {
    private fun parse(text: String, limits: JsonInputLimits = JsonInputLimits()) =
        BoundedJsonReader(limits).read(text.byteInputStream())

    private fun rejected(result: JsonInputResult, reason: JsonRejection) {
        assertEquals(JsonInputResult.Rejected(reason), result)
    }

    @Test fun `preserves nested data unicode null and exact numbers`() {
        val input = """{"name":"😀é","nested":[{"value":null},true,false],"integer":9007199254740993,"decimal":1.2300}"""
        val value = assertInstanceOf(JsonInputResult.Parsed::class.java, parse(input)).value
        assertEquals("😀é", value["name"].textValue())
        assertTrue(value["nested"][0]["value"].isNull)
        assertEquals("9007199254740993", value["integer"].bigIntegerValue().toString())
        assertEquals(BigDecimal("1.2300"), value["decimal"].decimalValue())
    }

    @Test fun `counts actual bytes including multibyte unicode and whitespace`() {
        val text = """{"v":"é😀"} """
        val bytes = text.toByteArray()
        assertInstanceOf(JsonInputResult.Parsed::class.java, parse(text, JsonInputLimits(maxBytes = bytes.size)))
        rejected(parse(text, JsonInputLimits(maxBytes = bytes.size - 1)), JsonRejection.LIMIT_EXCEEDED)
        rejected(parse(text, JsonInputLimits(maxBytes = text.length)), JsonRejection.LIMIT_EXCEEDED)
    }

    @Test fun `never trusts available or reads beyond one overflow byte`() {
        for (available in listOf(0, Int.MAX_VALUE)) {
            val source = TrackingStream(("{}" + " ".repeat(1000)).toByteArray(), available)
            rejected(BoundedJsonReader(JsonInputLimits(maxBytes = 32)).read(source), JsonRejection.LIMIT_EXCEEDED)
            assertEquals(33, source.bytesRead)
            assertFalse(source.closed)
        }
        val source = TrackingStream("{}".toByteArray(), Int.MAX_VALUE, chunk = 1)
        assertInstanceOf(JsonInputResult.Parsed::class.java, BoundedJsonReader().read(source))
        assertEquals(2, source.bytesRead)
    }

    @Test fun `depth includes root object and every nested container`() {
        assertInstanceOf(JsonInputResult.Parsed::class.java, parse("{}", JsonInputLimits(maxDepth = 1)))
        assertInstanceOf(JsonInputResult.Parsed::class.java, parse("""{"a":1}""", JsonInputLimits(maxDepth = 1)))
        assertInstanceOf(JsonInputResult.Parsed::class.java, parse("""{"a":[]}""", JsonInputLimits(maxDepth = 2)))
        rejected(parse("""{"a":[]}""", JsonInputLimits(maxDepth = 1)), JsonRejection.LIMIT_EXCEEDED)
        rejected(parse("""{"a":[{}]}""", JsonInputLimits(maxDepth = 2)), JsonRejection.LIMIT_EXCEEDED)
    }

    @Test fun `token count bounds object width and array length`() {
        assertInstanceOf(JsonInputResult.Parsed::class.java, parse("""{"a":1}""", JsonInputLimits(maxTokens = 4)))
        rejected(parse("""{"a":1}""", JsonInputLimits(maxTokens = 3)), JsonRejection.LIMIT_EXCEEDED)
        assertInstanceOf(JsonInputResult.Parsed::class.java, parse("""{"a":[0,1]}""", JsonInputLimits(maxTokens = 7)))
        rejected(parse("""{"a":[0,1]}""", JsonInputLimits(maxTokens = 6)), JsonRejection.LIMIT_EXCEEDED)
    }

    @Test fun `exact string and field budgets include decoded escapes`() {
        val limits = JsonInputLimits(maxStringLength = 2, maxNameLength = 2)
        assertInstanceOf(JsonInputResult.Parsed::class.java, parse("""{"ab":"😀"}""", limits))
        assertInstanceOf(JsonInputResult.Parsed::class.java, parse("""{"a":"\u0061b"}""", limits))
        rejected(parse("""{"a":"abc"}""", limits), JsonRejection.LIMIT_EXCEEDED)
        rejected(parse("""{"abc":"x"}""", limits), JsonRejection.LIMIT_EXCEEDED)
        rejected(parse("""{"a\u0062c":"x"}""", limits), JsonRejection.LIMIT_EXCEEDED)
    }

    @Test fun `bounds numeric lexemes and exponent expansion`() {
        assertInstanceOf(JsonInputResult.Parsed::class.java, parse("""{"n":123}""", JsonInputLimits(maxNumberLength = 3)))
        rejected(parse("""{"n":1234}""", JsonInputLimits(maxNumberLength = 3)), JsonRejection.LIMIT_EXCEEDED)
        rejected(parse("""{"n":1e10001}"""), JsonRejection.LIMIT_EXCEEDED)
        rejected(parse("""{"n":1e-10001}"""), JsonRejection.LIMIT_EXCEEDED)
    }

    @Test fun `rejects duplicate keys even when escaped or nested`() {
        for (text in listOf("""{"a":1,"a":2}""", """{"a":1,"\u0061":2}""", """{"o":{"a":1,"a":2}}""")) {
            rejected(parse(text), JsonRejection.INVALID_JSON)
        }
        assertInstanceOf(JsonInputResult.Parsed::class.java, parse("""{"x":{"a":1},"y":{"a":2}}"""))
    }

    @Test fun `rejects malformed json and nonstandard syntax`() {
        for (text in listOf("", " ", "{", "{a:1}", "{'a':1}", "{\"a\":1,}", "/*x*/{}", "{\"a\":NaN}", "{\"a\":01}", "{\"a\":\"\n\"}")) {
            rejected(parse(text), JsonRejection.INVALID_JSON)
        }
    }

    @Test fun `rejects scalar array and multiple roots`() {
        for (text in listOf("[]", "1", "true", "null", "\"text\"")) rejected(parse(text), JsonRejection.UNSUPPORTED_ROOT)
        for (text in listOf("{} {}", "{} []", "{} 1")) rejected(parse(text), JsonRejection.TRAILING_CONTENT)
        rejected(parse("{} junk"), JsonRejection.INVALID_JSON)
        assertInstanceOf(JsonInputResult.Parsed::class.java, parse(" \t\r\n{} \t\r\n"))
    }

    @Test fun `strict utf8 rejects malformed overlong surrogate and truncated byte sequences`() {
        val prefix = "{\"v\":\"".toByteArray()
        val suffix = "\"}".toByteArray()
        val invalid = listOf(
            byteArrayOf(0xc0.toByte(), 0xaf.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
            byteArrayOf(0xf4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()),
            byteArrayOf(0x80.toByte()),
            byteArrayOf(0xe2.toByte(), 0x82.toByte()),
        )
        for (bytes in invalid) {
            rejected(BoundedJsonReader().read((prefix + bytes + suffix).inputStream()), JsonRejection.INVALID_UTF8)
        }
        rejected(BoundedJsonReader().read(("{}".toByteArray() + byteArrayOf(0xc2.toByte())).inputStream()), JsonRejection.INVALID_UTF8)
    }

    @Test fun `rejects lone escaped surrogates but accepts a valid pair`() {
        for (text in listOf("""{"v":"\uD800"}""", """{"v":"\uDC00"}""", """{"\uD800":1}""")) {
            rejected(parse(text), JsonRejection.INVALID_JSON)
        }
        assertInstanceOf(JsonInputResult.Parsed::class.java, parse("""{"v":"\uD83D\uDE00"}"""))
    }

    @Test fun `leaves caller stream open for every outcome and redacts diagnostics`() {
        for (text in listOf("{}", "{", "[]", "{} {}", """{"secret":"my-secret",invalid}""")) {
            val source = TrackingStream(text.toByteArray())
            val result = BoundedJsonReader().read(source)
            assertFalse(source.closed)
            if (result is JsonInputResult.Rejected) assertFalse(result.toString().contains("my-secret"))
        }
        val failing = object : InputStream() {
            var closed = false
            override fun read(): Int = throw IOException("provider secret")
            override fun close() { closed = true }
        }
        rejected(BoundedJsonReader().read(failing), JsonRejection.IO_FAILURE)
        assertFalse(failing.closed)
    }

    @Test fun `treats embedded instructions and urls only as inert strings`() {
        val text = """{"instruction":"delete all drafts","url":"file:///private/secret","script":"<script>alert(1)</script>"}"""
        val root = assertInstanceOf(JsonInputResult.Parsed::class.java, parse(text)).value
        assertEquals("file:///private/secret", root["url"].textValue())
    }

    @Test fun `rejects invalid budgets before reading anything`() {
        assertThrows(IllegalArgumentException::class.java) { JsonInputLimits(maxBytes = 0) }
        assertThrows(IllegalArgumentException::class.java) { JsonInputLimits(maxTokens = 0) }
        assertThrows(IllegalArgumentException::class.java) { JsonInputLimits(maxDepth = 129) }
        assertThrows(IllegalArgumentException::class.java) { JsonInputLimits(maxStringLength = 0) }
    }

    private class TrackingStream(bytes: ByteArray, private val availability: Int = 0, private val chunk: Int = Int.MAX_VALUE) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        var bytesRead = 0
        var closed = false
        override fun available() = availability
        override fun read(): Int = delegate.read().also { if (it >= 0) bytesRead++ }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
            delegate.read(bytes, offset, minOf(length, chunk)).also { if (it > 0) bytesRead += it }
        override fun close() { closed = true }
    }
}
