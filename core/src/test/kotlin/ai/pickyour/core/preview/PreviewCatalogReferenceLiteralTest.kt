package ai.pickyour.core.preview

import ai.pickyour.core.contracts.FrozenDocument
import ai.pickyour.core.contracts.FrozenSchemaValidator
import ai.pickyour.core.contracts.FrozenValidation
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PreviewCatalogReferenceLiteralTest {
    private val mapper = ObjectMapper()
    private fun root() = requireNotNull(javaClass.getResourceAsStream("/contracts/v1/examples/catalog.json")).use {
        mapper.readTree(it) as ObjectNode
    }
    private fun item(root: ObjectNode, kind: String) = root["items"].single { it["kind"].textValue() == kind } as ObjectNode
    private fun read(root: ObjectNode): PreviewCatalogBrowser {
        val bytes = mapper.writeValueAsBytes(root)
        assertEquals(FrozenValidation.Valid, FrozenSchemaValidator().validate(FrozenDocument.CATALOG, bytes.inputStream()))
        return requireNotNull(PreviewCatalogBrowser.read(bytes))
    }
    private fun field(browser: PreviewCatalogBrowser, kind: String, name: String): PreviewCatalogField {
        val selected = browser.items.single { it.kind == kind }
        return requireNotNull(browser.details(selected, browser.hash)).specs.single { it.name == name }
    }

    @Test fun `existing provider ID spelled unknown is a supplied reference`() {
        val root = root()
        item(root, "model_provider").put("id", "unknown")
        for (kind in listOf("model", "embedding_model"))
            (item(root, kind)["spec"] as ObjectNode).put("provider_ref", "unknown")
        val browser = read(root)
        assertEquals("unknown", browser.items.single { it.kind == "model_provider" }.id)
        for (kind in listOf("model", "embedding_model")) {
            val reference = field(browser, kind, "provider_ref")
            assertEquals("\"unknown\"", reference.valueJson)
            assertEquals(PreviewCatalogValueState.SUPPLIED, reference.state)
        }
    }

    @Test fun `reference arrays retain literal IDs while empty and missing fields stay distinct`() {
        val root = root()
        item(root, "tool").put("id", "unknown")
        (item(root, "mcp_server")["spec"] as ObjectNode).putArray("tool_refs").add("unknown")
        val integration = item(root, "integration")["spec"] as ObjectNode
        integration.putArray("associated_tool_refs").add("unknown")
        val browser = read(root)
        assertEquals("unknown", browser.items.single { it.kind == "tool" }.id)
        for ((kind, name) in listOf("mcp_server" to "tool_refs", "integration" to "associated_tool_refs")) {
            val reference = field(browser, kind, name)
            assertEquals("[\"unknown\"]", reference.valueJson)
            assertEquals(PreviewCatalogValueState.SUPPLIED, reference.state)
        }
        integration.putArray("associated_tool_refs")
        val empty = field(read(root), "integration", "associated_tool_refs")
        assertEquals(PreviewCatalogValueState.EMPTY_COLLECTION, empty.state)
        assertEquals("[]", empty.valueJson)
        integration.remove("associated_tool_refs")
        val missing = field(read(root), "integration", "associated_tool_refs")
        assertEquals(PreviewCatalogValueState.MISSING, missing.state)
        assertNull(missing.valueJson)
    }
}
