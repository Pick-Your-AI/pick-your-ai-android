package ai.pickyour.core.preview

import ai.pickyour.core.drafts.SelectionFixtures
import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Synthetic strings only. This classifier never fetches a URI or changes an editor/history record. */
class PreviewSelectionRecoveryPolicyTest {
    private val mapper = SelectionFixtures.mapper
    private val fields = linkedMapOf("model_provider" to "api_base_url", "model" to "weights_uri",
        "mcp_server" to "endpoint", "integration" to "endpoint", "fine_tuned_model" to "artifact_uri")
    private val userinfo = "https://local-user:synthetic-password@example.invalid/v1"
    private fun settings(field: String = "endpoint", value: String = userinfo) = mapper.createObjectNode().put(field, value)
    private fun recognized(text: String, kind: String = "mcp_server") = PreviewSelectionRecoveryPolicy.hasRecognizedUserinfo(kind, text)

    @Test fun `all five frozen URI fields share the positive authority policy without admitting another kind`() {
        val schema = mapper.readTree(SelectionFixtures.resource("/contracts/v1/schemas/selection.schema.json"))
        val inventory = schema["\$defs"].properties().flatMap { (name, definition) ->
            definition["properties"]?.properties().orEmpty().filter { (_, value) -> value["format"]?.textValue() == "uri" }
                .map { (field, _) -> name.removePrefix("settings_") to field }
        }.toMap()
        assertEquals(fields, inventory)
        val supported = PreviewCatalogKinds.all.filter { PreviewSelectionForms.supported(it.id) }
        for ((kind, field) in fields) {
            assertTrue(recognized(settings(field).toString(), kind), kind)
            assertFalse(PreviewSelectionSensitiveInput.allows(kind, settings(field)), kind)
            assertFalse(recognized("{}", kind), kind)
        }
        assertEquals(supported, PreviewCatalogKinds.all.filter { PreviewSelectionForms.supported(it.id) })
    }

    @Test fun `empty encoded IPv6 and registry authorities are positive userinfo`() {
        val values = listOf(userinfo, "https://@example.invalid/v1", "https://local-user@example.invalid/v1",
            "https://local%40user:synthetic%3Apassword@example.invalid/v1",
            "https://local-user:synthetic-password@[::1]:9443/v1",
            "custom+local://local-user:synthetic-password@host_name/v1")
        for ((index, value) in values.withIndex()) {
            assertTrue(recognized(settings(value = value).toString()), "authority case $index")
        }
    }

    @Test fun `custom schemes and at signs outside authority remain unclassified and unchanged`() {
        val values = listOf("custom+local://example.invalid:9443/a/%2F?q=exact#retained",
            "https://example.invalid/path@name?contact=local@example.invalid#tag@name",
            "urn:example:local@name", "custom+local:opaque@reference", "relative/path@name")
        for ((index, value) in values.withIndex()) {
            val node = settings(value = value).put("credential_ref", "secret://local/opaque:1")
            val original = node.toString()
            assertFalse(recognized(original), "legitimate case $index")
            assertTrue(PreviewSelectionSensitiveInput.allows("mcp_server", node))
            assertEquals(original, node.toString())
        }
    }

    @Test fun `invalid URI and wrong typed fields still fail admission but stay recoverable`() {
        val values = listOf(settings(value = "https://bad authority"), settings(value = "https://local-user@example.invalid/bad path"),
            mapper.createObjectNode().putNull("endpoint"), mapper.createObjectNode().put("endpoint", 42),
            mapper.createObjectNode().put("endpoint", true), mapper.createObjectNode().set<com.fasterxml.jackson.databind.node.ObjectNode>("endpoint", mapper.createArrayNode()))
        for ((index, node) in values.withIndex()) {
            assertFalse(PreviewSelectionSensitiveInput.allows("mcp_server", node), "invalid admission case $index")
            assertFalse(recognized(node.toString()), "unclassified recovery case $index")
        }
    }

    @Test fun `malformed duplicate trailing and nonobject JSON remains unclassified`() {
        val valid = settings().toString()
        val values = listOf("", "{endpoint:", valid.dropLast(1), "[$valid]", "null", valid + "{}",
            "{\"endpoint\":\"https://example.invalid\",\"endpoint\":\"$userinfo\"}",
            "{\"endpoint\":\"$userinfo\",\"endpoint\":\"https://example.invalid\"}")
        for ((index, value) in values.withIndex()) assertFalse(recognized(value), "invalid JSON case $index")
    }

    @Test fun `malformed raw and escaped Unicode remains unclassified without replacement encoding`() {
        val values = listOf("{\"endpoint\":\"$userinfo\",\"unfinished\":\"" + '\uD800' + "\"}",
            "{\"endpoint\":\"$userinfo\",\"unfinished\":\"\\uD800\"}",
            "{\"endpoint\":\"$userinfo\",\"unfinished\":\"\\uDC00\"}",
            "{\"endpoint\":\"$userinfo\",\"unfinished\":\"" + '\u0000' + "\"}")
        for ((index, value) in values.withIndex()) assertFalse(recognized(value), "invalid Unicode case $index")
        assertTrue(recognized(settings(value = "$userinfo/\uD83D\uDE00").toString()))
    }

    @Test fun `complete URI above editor limit is recognized throughout the recovery budget`() {
        val text = settings(value = userinfo + "/" + "a".repeat(16_400)).toString()
            .padEnd(PreviewSelectionRecoveryPolicy.MAX_RAW_CHARACTERS)
        assertEquals(20_000, text.length)
        assertTrue((mapper.readTree(text)["endpoint"].textValue()).length > 16_384)
        assertTrue(BoundedJsonReader().read(text.byteInputStream()) is JsonInputResult.Rejected,
            "The ordinary parser keeps its existing string budget")
        assertTrue(recognized(text))
        assertFalse(recognized(text + " "), "Over-budget input is never parsed or reduced to a valid prefix")
    }

    @Test fun `maximum valid Unicode text fits the explicit byte budget`() {
        val text = settings().put("unfinished", "界".repeat(19_000)).toString()
            .padEnd(PreviewSelectionRecoveryPolicy.MAX_RAW_CHARACTERS)
        assertEquals(20_000, text.length)
        assertTrue(text.toByteArray(Charsets.UTF_8).size > 50_000)
        assertTrue(recognized(text))
    }

    @Test fun `existing structural name and number budgets leave the whole input unclassified`() {
        val values = listOf("{\"endpoint\":\"$userinfo\",\"nested\":" + "[".repeat(33) + "0" + "]".repeat(33) + "}",
            "{\"endpoint\":\"$userinfo\",\"" + "n".repeat(257) + "\":0}",
            "{\"endpoint\":\"$userinfo\",\"n\":" + "1".repeat(129) + "}",
            "{\"endpoint\":\"$userinfo\",\"n\":1e10001}")
        for ((index, value) in values.withIndex()) assertFalse(recognized(value), "bounded parser case $index")
    }

    @Test fun `unrelated fields opaque references and unsupported kinds are not secret guesses`() {
        assertFalse(recognized(settings("credential_ref").toString()))
        assertFalse(recognized(settings("region").toString(), "model_provider"))
        assertFalse(recognized(settings().toString(), "workload"))
        assertFalse(recognized(settings().toString(), "unrecognized"))
        assertFalse(recognized("{\"nested\":" + settings().toString() + "}"))
        assertFalse(recognized("{\"credential_ref\":\"secret://local/opaque:1\"}"))
    }
}
