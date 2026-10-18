package ai.pickyour.core.preview

import ai.pickyour.core.cache.ArtifactHash
import ai.pickyour.core.cache.CacheFailure
import ai.pickyour.core.cache.CacheRead
import ai.pickyour.core.importing.BoundedJsonReader
import ai.pickyour.core.importing.JsonInputResult
import ai.pickyour.core.importing.JsonRejection
import java.security.MessageDigest

enum class PreviewCatalogPinSource { CAPTURED_CATALOG, RETAINED_SNAPSHOT }
enum class PreviewCatalogPinProblem {
    SNAPSHOT_MISSING, HASH_MISMATCH, INVALID_SNAPSHOT, UNSUPPORTED_SCHEMA, PREVIEW_LIMIT,
    EXACT_VERSION_MISSING, DETAIL_UNAVAILABLE, READ_BUSY, READ_FAILED, CACHE_CORRUPT, CANCELLED,
}

sealed interface PreviewCatalogPinResolution {
    class Available(val detail: PreviewCatalogDetails, val source: PreviewCatalogPinSource) : PreviewCatalogPinResolution {
        override fun toString() = "PreviewCatalogPinResolution.Available"
    }
    data class Unavailable(val problem: PreviewCatalogPinProblem) : PreviewCatalogPinResolution
}

/**
 * Resolves only the supplied byte digest and opaque item ID/version. The captured catalog is already
 * immutable; retained bytes must independently match the requested digest before version/schema reads.
 * A read callback supplies exact local bytes only, never its active pointer or a latest-version match.
 * No cache write, pin replacement, pricebook, clock, publication status or source-rights inference.
 */
class PreviewCatalogPinResolver(private val readRetained: (ArtifactHash) -> CacheRead = { CacheRead.Missing }) {
    fun resolve(pin: PreviewCatalogPin, captured: PreviewCatalogBrowser?): PreviewCatalogPinResolution {
        if (Thread.currentThread().isInterrupted) return unavailable(PreviewCatalogPinProblem.CANCELLED)
        if (captured?.hash == pin.snapshotHash)
            return exact(pin, captured, PreviewCatalogPinSource.CAPTURED_CATALOG)

        val expected = ArtifactHash(pin.snapshotHash)
        val read = try { readRetained(expected) }
            catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return unavailable(PreviewCatalogPinProblem.CANCELLED)
            }
            catch (_: Exception) { return unavailable(PreviewCatalogPinProblem.READ_FAILED) }
        if (Thread.currentThread().isInterrupted) return unavailable(PreviewCatalogPinProblem.CANCELLED)
        val value = when (read) {
            CacheRead.Missing -> return unavailable(PreviewCatalogPinProblem.SNAPSHOT_MISSING)
            is CacheRead.Failed -> return unavailable(when (read.reason) {
                CacheFailure.BUSY -> PreviewCatalogPinProblem.READ_BUSY
                CacheFailure.TOO_LARGE -> PreviewCatalogPinProblem.PREVIEW_LIMIT
                CacheFailure.HASH_MISMATCH -> PreviewCatalogPinProblem.HASH_MISMATCH
                CacheFailure.CORRUPT -> PreviewCatalogPinProblem.CACHE_CORRUPT
                CacheFailure.CANCELLED -> PreviewCatalogPinProblem.CANCELLED
                CacheFailure.IO_FAILURE, CacheFailure.RETENTION_LIMIT -> PreviewCatalogPinProblem.READ_FAILED
            })
            is CacheRead.Available -> read
        }
        if (value.hash != expected) return unavailable(PreviewCatalogPinProblem.HASH_MISMATCH)
        if (value.bytes.size > MAX_BYTES) return unavailable(PreviewCatalogPinProblem.PREVIEW_LIMIT)
        val bytes = value.bytes.copyOf()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        if (digest != expected.hex) return unavailable(PreviewCatalogPinProblem.HASH_MISMATCH)
        val root = when (val parsed = BoundedJsonReader().read(bytes.inputStream())) {
            is JsonInputResult.Parsed -> parsed.value
            is JsonInputResult.Rejected -> return unavailable(if (parsed.reason == JsonRejection.LIMIT_EXCEEDED)
                PreviewCatalogPinProblem.PREVIEW_LIMIT else PreviewCatalogPinProblem.INVALID_SNAPSHOT)
        }
        val version = root.path("schema_version")
        if (!version.isTextual || version.textValue().isEmpty()) return unavailable(PreviewCatalogPinProblem.INVALID_SNAPSHOT)
        // An unknown envelope is uninterpreted, not accepted as a valid future schema or migrated.
        if (version.textValue() != "1.0.0") return unavailable(PreviewCatalogPinProblem.UNSUPPORTED_SCHEMA)
        if (root.path("items").isArray && root.path("items").size() > 256)
            return unavailable(PreviewCatalogPinProblem.PREVIEW_LIMIT)
        val browser = PreviewCatalogBrowser.read(bytes) ?: return unavailable(PreviewCatalogPinProblem.INVALID_SNAPSHOT)
        if (Thread.currentThread().isInterrupted) return unavailable(PreviewCatalogPinProblem.CANCELLED)
        return exact(pin, browser, PreviewCatalogPinSource.RETAINED_SNAPSHOT)
    }

    private fun exact(pin: PreviewCatalogPin, browser: PreviewCatalogBrowser, source: PreviewCatalogPinSource): PreviewCatalogPinResolution {
        val item = browser.items.singleOrNull { it.id == pin.id && it.version == pin.version }
            ?: return unavailable(PreviewCatalogPinProblem.EXACT_VERSION_MISSING)
        val detail = browser.details(item, pin.snapshotHash)
            ?: return unavailable(PreviewCatalogPinProblem.DETAIL_UNAVAILABLE)
        return PreviewCatalogPinResolution.Available(detail, source)
    }

    private fun unavailable(problem: PreviewCatalogPinProblem) = PreviewCatalogPinResolution.Unavailable(problem)

    companion object { const val MAX_BYTES = 1_048_576 }
}
