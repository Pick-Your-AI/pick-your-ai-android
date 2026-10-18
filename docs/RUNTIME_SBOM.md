# Runtime SPDX export

The [resolved runtime inventory](RUNTIME_INVENTORY.md) is the source for a standard
SPDX 2.3 JSON maintenance document. This bounded addition supports the SBOM work
in [Android #69](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/69).
It describes the core runtime classpath, the optional unsigned synthetic preview
runtime classpath, and the exact recorded build outputs. It does not complete
the issue's release inventory, license review, monitored vulnerability intake,
urgent update/rotation exercise or receiving-owner acceptance.

## Generate and retain

Use Python 3.12 and the repository's existing pinned reference-validation
dependencies. From a clean committed checkout, first complete the applicable
strict Gradle command in [RUNTIME_INVENTORY.md](RUNTIME_INVENTORY.md), then run:

```sh
python -m pip install -r requirements-contracts.txt
python scripts/runtime_sbom.py
```

The command reads `build/inventory/runtime-inventory.json` and writes
`build/sbom/runtime.spdx.json`. It performs no network requests or dependency
resolution. Missing validator dependencies fail qualification; they are never
installed by the exporter. Both CI jobs use the same existing Python pins. The
core job runs the host regressions before its build, and both jobs validate their
own actual inventory and JAR/APK bytes after the existing ignored-input checks.

The core archive retains the SPDX document in `build/sbom/` alongside its existing
inventory and test/build outputs. The native `android-runtime-inventory-<commit>`
archive adds that same repository-relative path beside its original inventory,
core JAR and unsigned APK. The existing `android-preview-<commit>` archive and its
installed-emulator consumer layout remain unchanged. Retention is seven days.
Accept an archive only with its successful current-source workflow result,
immutable artifact ID and archive digest; an artifact upload alone is not a pass.

The exporter removes only an existing regular, single-link output at its owned
`build/sbom/runtime.spdx.json` path before qualification. A malformed or stale
inventory, changed source/output, unavailable schema, or failed atomic write
leaves no replacement success document. Symlinks, hardlinks and non-directory
output parents are rejected without touching their targets. A command that never
runs after an earlier build failure cannot clear old files: a successfully
completed build and export in the identified run remain required.

## Mapping and evidence

| Inventory observation | SPDX representation |
| --- | --- |
| Resolved component in one project/configuration | A distinct logical package with the exact component record in JSON `sourceInfo`; Maven coordinates supply the package name and version |
| Same component resolved in two classpaths | Separate package identities, preserving each graph's actual dependency/constraint edges |
| Non-constraint dependency | `DEPENDS_ON` between that classpath's logical packages |
| Gradle resolution constraint | `OTHER` with an explicit constraint comment; it is not promoted to a runtime dependency |
| Resolved artifact | A separate package with its observed filename, SHA-256 and byte-count metadata; an `OTHER` relation identifies the originating component without claiming APK packaging |
| Metadata-only component, such as a BOM | Retained as a logical package even when no runtime artifact exists |
| Built core JAR or unsigned APK | A document-described package with its own exact output path and SHA-256; an `OTHER` relation identifies the associated runtime classpath |
| Source commit/tree, selected pins, toolchain, limitations | Exact recorded values in the document's JSON `comment`, alongside the original inventory byte hash and exporter/schema hashes |
| POM hash, direct license declarations and parent metadata | Exact unreviewed component `sourceInfo`; never used as an artifact checksum or a concluded license |

Each artifact package has one checksum for its own bytes. Multiple artifacts
from a component remain separate; the exporter does not pick an arbitrary JAR or
combine unrelated file hashes into a single package checksum. Logical packages
have no checksum for an unobserved aggregate. Duplicate dependency observations
remain observations; constraint and dependency relations between the same pair
remain distinct. Cyclic resolved graphs are supported without recursive traversal.

`filesAnalyzed` is `false`, and package verification codes are omitted as required
by the [SPDX package rules](https://github.com/spdx/spdx-spec/blob/aadf3b0b8dbbabdb4d880b0fc714255fea436ff7/chapters/package-information.md).
All package license conclusions, declared license expressions, suppliers,
download locations and copyright determinations are `NOASSERTION`. Raw POM
license names/URLs remain available as unreviewed evidence, including absent
direct declarations and unevaluated parents. No license URL is fetched. Maven
repository URLs, classifiers and package URLs are not invented from a coordinate
when the source receipt does not observe them. License/notice intake can add
reviewed identity and distribution decisions separately.

The creation timestamp is actual UTC export time, not a reconstructed build time.
The document namespace includes a SHA-256 of the complete canonical document
before insertion of the namespace itself. A changed timestamp, source receipt,
exporter, schema or mapping therefore gets a different namespace. Supplying an
explicit timestamp to the pure Python conversion API supports deterministic
replay in tests; the production command has no timestamp override.

## Qualification boundaries

Input admission rejects duplicate JSON object keys, non-finite numbers, invalid
Unicode, unsupported schema/scope/fields, malformed digests and counts, missing
or duplicated pins/outputs/scopes, inconsistent license status, dangling or
disconnected component graphs, orphaned artifacts and conflicting cross-classpath
POM/artifact evidence. Limits are 8 MiB of input JSON, 4,096 components per
classpath, 16,384 artifacts per classpath, and 65,536 total dependency observations.
The known core/preview output paths and source pin sets must match inventory
schema version 1. Changed inventory semantics require a reviewed exporter update.

The command requires the inventory's exact current Git commit/tree and a clean
checkout, with Git replacement objects disabled for every source inspection.
It compares every tracked file's actual bytes against its committed Git
blob, including changes hidden by index flags, then verifies every source pin and
recorded output size/hash. Evidence paths cannot traverse symlinks. The executing
exporter must match the committed exporter, and the pinned schema must be part of
that same source. This rechecks source and output identity; it does not rerun
Gradle resolution, authenticate downloaded POM/JAR bytes independently, or replace
the inventory producer's actual configured-input and ignored-source checks.

The official [SPDX 2.3 schema and attribution](../scripts/vendor/spdx-2.3/README.md)
are vendored at an exact upstream revision. Its checksum is checked before local
Draft 7 validation, and it has no external schema references. Additional checks
require unique package identities, existing relationship targets, a matching
document namespace and the bounded file/license/provenance assertions above.
Schema-valid JSON establishes this export format; it does not establish package
completeness or legal, security, device or release acceptance. SPDX's `CC0-1.0`
document-data designation is separate from the rights to the inventoried software.

## Focused regressions

```sh
python -m unittest discover -s scripts/tests -p 'test_runtime_sbom.py' -v
```

The tests exercise actual isolated Git checkouts and command invocation with
synthetic output bytes; they do not run Gradle or install an APK. They cover
per-classpath graph fidelity, constraints and cyclic/BOM graphs, multiple artifact
hashes, unreviewed license/parent preservation, deterministic namespace identity,
the pinned standard schema, malformed inventory and misleading SPDX rejection,
stale source/pins/output, hidden tracked changes and Git replacements, unsafe evidence/output paths,
old-success removal and recovery from an actual atomic-replace failure. CI's
later exporter invocations qualify the real outputs from that exact run.
