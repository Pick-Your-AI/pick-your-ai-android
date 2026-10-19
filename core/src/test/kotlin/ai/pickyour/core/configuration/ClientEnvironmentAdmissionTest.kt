package ai.pickyour.core.configuration

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ClientEnvironmentAdmissionTest {
    private val valid = """{
      "configuration_version":"pyai.android.environment.v1", "environment":"preview",
      "application_id":"ai.pickyour.android.preview", "application_version":"0.0.1-synthetic",
      "application_version_code":1, "api_base_url":null, "api_version":null,
      "contract_version":"1.0.0", "catalog_schema_version":"1.0.0",
      "catalog_id":"catalog.fixture.1", "catalog_revision":1,
      "capabilities":["local_drafts","synthetic_catalog"]
    }"""
    private fun read(text: String = valid, mode: String? = "preview", id: String = "ai.pickyour.android.preview",
                     version: String = "0.0.1-synthetic", code: Int = 1) =
        ClientEnvironmentAdmission.read(mode, id, version, code, text.byteInputStream())
    private fun denied(result: EnvironmentAdmission, reason: ConfigurationFailure) =
        assertEquals(EnvironmentAdmission.Rejected(reason), result)

    @Test fun `accepted preview exposes only finite configured identity and no remote authority`() {
        val result = assertInstanceOf(EnvironmentAdmission.SyntheticPreview::class.java, read())
        assertEquals(ClientEnvironment.PREVIEW, result.environment)
        assertEquals("ai.pickyour.android.preview", result.applicationId)
        assertEquals("1.0.0", result.contractVersion)
        assertEquals("1.0.0", result.catalogSchemaVersion)
        assertEquals("catalog.fixture.1", result.configuredCatalogId)
        assertEquals(1, result.configuredCatalogRevision)
        assertNull(result.apiBaseUrl)
        assertNull(result.apiVersion)
    }

    @Test fun `real modes reject even plausible self declared endpoint identity and capabilities`() {
        for (mode in listOf("staging", "production")) {
            val candidate = valid.replace("\"environment\":\"preview\"", "\"environment\":\"$mode\"")
                .replace("ai.pickyour.android.preview", "ai.pickyour.android")
                .replace("\"api_base_url\":null", "\"api_base_url\":\"https://unapproved.example.invalid/v1\"")
            denied(read(candidate, mode), ConfigurationFailure.REAL_ENVIRONMENT_UNCONFIGURED)
        }
    }

    @Test fun `unknown and missing mode never default to preview or consume configuration`() {
        val untouched = object : InputStream() { override fun read(): Int = error("configuration must not be consumed") }
        for (mode in listOf(null, "", "Preview", " preview", "production\n", "unknown"))
            denied(ClientEnvironmentAdmission.read(mode, "", "", 0, untouched), ConfigurationFailure.UNKNOWN_ENVIRONMENT)
        for (mode in listOf("staging", "production"))
            denied(ClientEnvironmentAdmission.read(mode, "", "", 0, untouched), ConfigurationFailure.REAL_ENVIRONMENT_UNCONFIGURED)
    }

    @Test fun `missing configuration never manufactures preview defaults`() {
        denied(ClientEnvironmentAdmission.read("preview", "ai.pickyour.android.preview", "0.0.1-synthetic", 1, null),
            ConfigurationFailure.MISSING_CONFIGURATION)
        denied(read("{}"), ConfigurationFailure.INVALID_CONFIGURATION)
    }

    @Test fun `environment mismatch rejects independently of valid preview metadata`() {
        for (value in listOf("production", "staging", "PREVIEW", "preview "))
            denied(read(valid.replace("\"environment\":\"preview\"", "\"environment\":\"$value\"")),
                ConfigurationFailure.ENVIRONMENT_MISMATCH)
    }

    @Test fun `actual package version and code must all match the admitted profile`() {
        denied(read(id = "ai.pickyour.android"), ConfigurationFailure.APPLICATION_MISMATCH)
        denied(read(version = "0.0.2"), ConfigurationFailure.APPLICATION_MISMATCH)
        denied(read(code = 2), ConfigurationFailure.APPLICATION_MISMATCH)
        denied(read(valid.replace("ai.pickyour.android.preview", "another.application")), ConfigurationFailure.APPLICATION_MISMATCH)
        denied(read(valid.replace("\"application_version_code\":1", "\"application_version_code\":1.0")), ConfigurationFailure.APPLICATION_MISMATCH)
    }

    @Test fun `wrong contract catalog schema and configured catalog identity reject before use`() {
        denied(read(valid.replace("\"contract_version\":\"1.0.0\"", "\"contract_version\":\"2.0.0\"")), ConfigurationFailure.CONTRACT_MISMATCH)
        denied(read(valid.replace("\"catalog_schema_version\":\"1.0.0\"", "\"catalog_schema_version\":\"2.0.0\"")), ConfigurationFailure.CATALOG_MISMATCH)
        denied(read(valid.replace("catalog.fixture.1", "unapproved.catalog")), ConfigurationFailure.CATALOG_MISMATCH)
        denied(read(valid.replace("\"catalog_revision\":1", "\"catalog_revision\":2")), ConfigurationFailure.CATALOG_MISMATCH)
    }

    @Test fun `preview forbids endpoints and API declarations including empty strings`() {
        for (field in listOf("api_base_url", "api_version")) for (value in listOf("\"\"", "\"https://user:CANARY@unapproved.example.invalid/v1?token=CANARY\"", "false", "{}")) {
            val result = read(valid.replace("\"$field\":null", "\"$field\":$value"))
            denied(result, ConfigurationFailure.PREVIEW_REMOTE_CONFIGURATION)
            assertFalse(result.toString().contains("CANARY"))
        }
    }

    @Test fun `capabilities cannot be added duplicated omitted or type coerced`() {
        val original = "[\"local_drafts\",\"synthetic_catalog\"]"
        for (value in listOf("[]", "[\"local_drafts\",\"local_drafts\"]", "[\"local_drafts\",\"remote_write\"]", "[true, false]", "null"))
            denied(read(valid.replace(original, value)), ConfigurationFailure.CAPABILITY_MISMATCH)
        assertSame(EnvironmentAdmission.SyntheticPreview, read(valid.replace(original, "[\"synthetic_catalog\",\"local_drafts\"]")))
    }

    @Test fun `closed fields reject credentials unknown keys and missing explicit nulls`() {
        for (field in listOf("token", "database_url", "signing_key", "extra")) {
            val result = read(valid.replaceFirst("{", "{\"$field\":\"PRIVATE_CANARY\","))
            denied(result, ConfigurationFailure.INVALID_CONFIGURATION)
            assertFalse(result.toString().contains("PRIVATE_CANARY"))
        }
        denied(read(valid.replace("\"api_version\":null,", "")), ConfigurationFailure.INVALID_CONFIGURATION)
    }

    @Test fun `duplicate escaped keys trailing roots invalid UTF8 and excessive nesting reject`() {
        for (text in listOf(valid.replaceFirst("{", "{\"environment\":\"production\","),
            valid.replaceFirst("{", "{\"\\u0065nvironment\":\"preview\","), valid + "{}",
            valid.replace("\"api_version\":null", "\"api_version\":{\"nested\":{}}")))
            denied(read(text), ConfigurationFailure.INVALID_CONFIGURATION)
        denied(ClientEnvironmentAdmission.read("preview", "ai.pickyour.android.preview", "0.0.1-synthetic", 1,
            ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28))), ConfigurationFailure.INVALID_CONFIGURATION)
    }

    @Test fun `actual byte budget bounds reads and malformed or IO failures contain no input`() {
        val exact = valid + " ".repeat(ClientEnvironmentAdmission.MAX_CONFIGURATION_BYTES - valid.toByteArray().size)
        assertSame(EnvironmentAdmission.SyntheticPreview, read(exact))
        denied(read(exact + " "), ConfigurationFailure.INVALID_CONFIGURATION)
        val failing = object : InputStream() { override fun read(): Int = throw IOException("PRIVATE_CANARY") }
        val result = ClientEnvironmentAdmission.read("preview", "ai.pickyour.android.preview", "0.0.1-synthetic", 1, failing)
        denied(result, ConfigurationFailure.INVALID_CONFIGURATION)
        assertFalse(result.toString().contains("PRIVATE_CANARY"))
    }

    @Test fun `format and required scalar types are never guessed or coerced`() {
        denied(read(valid.replace("pyai.android.environment.v1", "pyai.android.environment.v2")), ConfigurationFailure.UNSUPPORTED_FORMAT)
        denied(read(valid.replace("\"contract_version\":\"1.0.0\"", "\"contract_version\":null")), ConfigurationFailure.CONTRACT_MISMATCH)
        denied(read(valid.replace("\"catalog_revision\":1", "\"catalog_revision\":true")), ConfigurationFailure.CATALOG_MISMATCH)
    }

    @Test fun `rejected read leaves caller stream open and fresh valid read can recover`() {
        var closed = false
        val input = object : ByteArrayInputStream("{}".toByteArray()) { override fun close() { closed = true; super.close() } }
        denied(ClientEnvironmentAdmission.read("preview", "ai.pickyour.android.preview", "0.0.1-synthetic", 1, input), ConfigurationFailure.INVALID_CONFIGURATION)
        assertFalse(closed)
        assertSame(EnvironmentAdmission.SyntheticPreview, read())
    }
}
