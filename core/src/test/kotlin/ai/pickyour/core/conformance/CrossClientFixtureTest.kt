package ai.pickyour.core.conformance

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class CrossClientFixtureTest {
    @TestFactory
    fun exactNeutralWireCases(): List<DynamicTest> {
        val adapter = CrossClientFixtureAdapter()
        val mapper = ObjectMapper()
        return CrossClientFixtures.load().map { fixture ->
            DynamicTest.dynamicTest("wire:${fixture.id}") {
                val actual = adapter.observe(fixture)
                assertEquals(fixture.expected, actual.accepted, fixture.id)
                assertEquals(if (fixture.expected) true else null, actual.preserved, fixture.id)
                println("ANDROID_WIRE_CASE " + mapper.writeValueAsString(actual))
            }
        }
    }

    @Test
    fun tamperedOrTruncatedFixtureTransportCannotReachTheAdapter() {
        val original = javaClass.getResourceAsStream("/conformance/cross-client-v1.json.gz")!!.use { it.readBytes() }
        assertThrows(IllegalArgumentException::class.java) { CrossClientFixtures.load(original.copyOf(original.size - 1).inputStream()) }
        val changed = original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) { CrossClientFixtures.load(changed.inputStream()) }
        assertThrows(IllegalArgumentException::class.java) { CrossClientFixtures.load((original + byteArrayOf(0)).inputStream()) }
    }

    @Test
    fun changedPayloadCannotReuseAQualifiedFixtureIdentity() {
        val fixture = CrossClientFixtures.load().first()
        assertThrows(IllegalArgumentException::class.java) {
            CrossClientFixtureAdapter().observe(fixture.copy(payloadJSON = fixture.payloadJSON + " "))
        }
    }
}
