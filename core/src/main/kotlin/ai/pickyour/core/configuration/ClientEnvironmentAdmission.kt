package ai.pickyour.core.configuration

import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputLimits
import ai.pickyour.core.importing.JsonInputResult
import java.io.InputStream

/** Local application configuration, not a shared API or an authorization credential. */
enum class ClientEnvironment { PREVIEW, STAGING, PRODUCTION }

enum class ConfigurationFailure {
    UNKNOWN_ENVIRONMENT, REAL_ENVIRONMENT_UNCONFIGURED, MISSING_CONFIGURATION,
    INVALID_CONFIGURATION, UNSUPPORTED_FORMAT, ENVIRONMENT_MISMATCH,
    APPLICATION_MISMATCH, CONTRACT_MISMATCH, CATALOG_MISMATCH,
    CAPABILITY_MISMATCH, PREVIEW_REMOTE_CONFIGURATION,
}

sealed interface EnvironmentAdmission {
    /** Every field is from the finite accepted profile, never copied from rejected input. */
    data object SyntheticPreview : EnvironmentAdmission {
        val environment = ClientEnvironment.PREVIEW
        val applicationId = "ai.pickyour.android.preview"
        val applicationVersion = "0.0.1-synthetic"
        val applicationVersionCode = 1
        val configurationVersion = "pyai.android.environment.v1"
        val contractVersion = "1.0.0"
        val catalogSchemaVersion = "1.0.0"
        // These describe the configured example, not a claim that it loaded or is current.
        val configuredCatalogId = "catalog.fixture.1"
        val configuredCatalogRevision = 1
        val apiVersion: String? = null
        val apiBaseUrl: String? = null
        override fun toString() = "EnvironmentAdmission.SyntheticPreview"
    }
    data class Rejected(val reason: ConfigurationFailure) : EnvironmentAdmission
}

/**
 * Admits only the current #34/#35/#48 profile. Staging/production have no accepted
 * deployed endpoint, application identity or capability handoff, so fail closed.
 * A caller cannot self-approve one by putting plausible strings in this file.
 * The caller owns and closes the input; no storage, fixture or network is opened here.
 */
object ClientEnvironmentAdmission {
    const val MAX_CONFIGURATION_BYTES = 4096
    private val fields = setOf(
        "configuration_version", "environment", "application_id", "application_version",
        "application_version_code", "api_base_url", "api_version", "contract_version",
        "catalog_schema_version", "catalog_id", "catalog_revision", "capabilities",
    )

    fun read(
        selectedEnvironment: String?,
        applicationId: String,
        applicationVersion: String,
        applicationVersionCode: Int,
        input: InputStream?,
    ): EnvironmentAdmission {
        fun reject(reason: ConfigurationFailure) = EnvironmentAdmission.Rejected(reason)
        when (selectedEnvironment) {
            "preview" -> Unit
            "staging", "production" -> return reject(ConfigurationFailure.REAL_ENVIRONMENT_UNCONFIGURED)
            else -> return reject(ConfigurationFailure.UNKNOWN_ENVIRONMENT)
        }
        if (input == null) return reject(ConfigurationFailure.MISSING_CONFIGURATION)
        val parsed = BoundedJsonReader(JsonInputLimits(
            maxBytes = MAX_CONFIGURATION_BYTES, maxDepth = 2, maxTokens = 64,
            maxStringLength = 128, maxNameLength = 40, maxNumberLength = 10,
        )).read(input) as? JsonInputResult.Parsed
            ?: return reject(ConfigurationFailure.INVALID_CONFIGURATION)
        val root = parsed.value
        if (root.fieldNames().asSequence().toSet() != fields)
            return reject(ConfigurationFailure.INVALID_CONFIGURATION)
        fun text(name: String, expected: String) = root[name].isTextual && root[name].textValue() == expected
        fun integer(name: String, expected: Int) = root[name].isIntegralNumber && root[name].bigIntegerValue() == expected.toBigInteger()
        val accepted = EnvironmentAdmission.SyntheticPreview
        if (!text("configuration_version", accepted.configurationVersion)) return reject(ConfigurationFailure.UNSUPPORTED_FORMAT)
        if (!text("environment", "preview")) return reject(ConfigurationFailure.ENVIRONMENT_MISMATCH)
        if (applicationId != accepted.applicationId || applicationVersion != accepted.applicationVersion ||
            applicationVersionCode != accepted.applicationVersionCode ||
            !text("application_id", accepted.applicationId) || !text("application_version", accepted.applicationVersion) ||
            !integer("application_version_code", accepted.applicationVersionCode))
            return reject(ConfigurationFailure.APPLICATION_MISMATCH)
        if (!text("contract_version", accepted.contractVersion)) return reject(ConfigurationFailure.CONTRACT_MISMATCH)
        if (!text("catalog_schema_version", accepted.catalogSchemaVersion) ||
            !text("catalog_id", accepted.configuredCatalogId) || !integer("catalog_revision", accepted.configuredCatalogRevision))
            return reject(ConfigurationFailure.CATALOG_MISMATCH)
        if (!root["api_base_url"].isNull || !root["api_version"].isNull)
            return reject(ConfigurationFailure.PREVIEW_REMOTE_CONFIGURATION)
        val capabilities = root["capabilities"]
        if (!capabilities.isArray || capabilities.size() != 2 || capabilities.any { !it.isTextual } ||
            capabilities.map { it.textValue() }.toSet() != setOf("local_drafts", "synthetic_catalog"))
            return reject(ConfigurationFailure.CAPABILITY_MISMATCH)
        return accepted
    }
}
