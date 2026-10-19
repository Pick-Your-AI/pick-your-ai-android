# Resolved runtime inventory

The inventory task supplies a bounded maintenance input for
[Android #69](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/69).
It records the runtime dependency graph actually resolved by the pinned Gradle
build and links those inputs to the exact core JAR and optional unsigned
synthetic-preview APK produced in that checkout. This is a custom diagnostic
inventory, not a release SBOM or a completed license review.

The companion [SPDX runtime export](RUNTIME_SBOM.md) converts a qualified receipt
to standard SPDX 2.3 JSON. The original inventory remains the source observation;
the export preserves its scope and does not establish release or license acceptance.

## Reproduce from a clean checkout

Use the [JVM core toolchain](CORE_FOUNDATION.md) and, for the optional preview, the
[Android SDK setup](ANDROID_PREVIEW.md). The init script uses Gradle's built-in
resolution and Maven POM APIs; it adds no plugin, dependency or repository.

```sh
./gradlew --no-daemon --dependency-verification=strict \
  --init-script scripts/runtime_inventory.init.gradle.kts \
  :core:clean :core:check :core:assemble runtimeInventory
```

With the configured SDK, include the unsigned preview:

```sh
./gradlew --no-daemon --dependency-verification=strict \
  --init-script scripts/runtime_inventory.init.gradle.kts \
  -PandroidPreview=true :core:clean :preview:clean \
  :core:check :preview:assembleDebug :preview:lintDebug \
  :preview:testDebugUnitTest runtimeInventory
```

Append `--offline` when the locked artifacts and POMs are already in the local
Gradle cache. Missing metadata fails the task rather than producing an incomplete
inventory. Strict checksum verification remains required. Do not use dependency
lock or verification-writing flags to create an acceptance receipt; review those
source changes separately and run again from the resulting clean commit.

Both existing CI jobs run the task and retain
`build/inventory/runtime-inventory.json` beside the corresponding build artifacts
for seven days. The native job's separate `android-runtime-inventory-<commit>`
archive includes the inventory, core JAR and unsigned APK with repository-relative
paths. Its existing `android-preview-<commit>` diagnostic archive keeps its
`preview/build` root, including `outputs/apk/...`, for the installed-emulator
consumer. The existing artifact's layout and immutable-ID/APK handoff are not
changed by adding the inventory archive.
Only a successfully completed command establishes a new receipt; an older local
receipt may still exist after a failed build. Record the run, artifact archive
digest and exact source commit when consuming an archived receipt.

## Recorded evidence

| Field | Meaning |
| --- | --- |
| `source.commit` and `source.tree` | Git commit/tree of the checkout; all tracked file bytes must match committed blobs, and configured project source/copy/task inputs are checked for ignored/untracked files and symlinks |
| `source.pins` | SHA-256 hashes of selected build, lock, verification and wrapper inputs plus the inventory driver |
| `toolchain` | Gradle and Java versions actually running the inventory task |
| `outputs` | Repository-relative output names, byte sizes and SHA-256 hashes of the built core JAR and, when enabled, unsigned preview APK |
| `classpaths` | Core `runtimeClasspath` and optional preview `debugRuntimeClasspath`, their selected component identities, dependency/constraint edges, and resolved artifact names/hashes/sizes |
| Component `pom` | Exact POM SHA-256, direct license declarations and parent coordinates; project components have no Maven POM |

Metadata-only components such as the Jackson BOM appear in the graph even though
they contribute no runtime JAR. A core project dependency is identified as a
project component, and its selected artifact is hashed like external artifacts.
Artifact resolution errors, missing or ambiguous POMs, non-strict verification,
changed tracked bytes, source symlinks, and untracked source fail the task. The
task always reruns its inspection; it does not reuse a cached inventory output.
JSON is written atomically only after every required input has been collected.

The source guard inspects module source folders, declared JVM source directories,
and the selected Gradle tasks' project-local file and copy inputs. This includes
the core's `contracts/v1/schemas` import and the preview's
`contracts/v1/examples` assets, which are outside the module folders. It checks
ancestor symlinks as well as individual files. Generated project build directories,
Git/Gradle/Kotlin bookkeeping and local SDK configuration are outside this source
guard; external build-tool inputs are outside its project-source claim. This does
not turn the build into a hermetic environment attestation.

Each CI job also runs an actual ignored-input rejection regression. The helper
creates a private disposable Git checkout, confirms clean Git status with an
ignored schema or asset, executes the real pinned offline build, verifies the
fixture bytes reached the JAR/APK, and requires the inventory to fail at the source
guard without writing a fresh receipt. Its sanitized result is retained beside
the positive inventory. Reproduce after warming the matching build/POM caches:

```sh
python scripts/check_runtime_inventory_guards.py
python scripts/check_runtime_inventory_guards.py --preview
```

The second command uses the configured Android SDK. These are packaging/source
rejection checks; they do not install the APK.

The core CI job also requires a source-identity regression against the actual
inventory producer. A private Java-only Gradle project first produces a clean
inventory. The helper then installs a Git replacement object that makes changed
tracked Java source appear clean under the original commit. It requires the real
task to build the changed class and reject that deceptive source identity without
writing a new inventory. Finally, it removes the replacement, actually commits
the changed source in the private repository, and requires a clean inventory for
that new commit. This positive control prevents a blanket build failure from
satisfying the rejection check.

```sh
python scripts/check_runtime_inventory_source.py
```

This check uses the existing pinned wrapper with a warmed Gradle cache, offline
mode, strict verification and no persistent daemon. It adds no runtime dependency
and needs no Android SDK. The fixture is removed afterward; the ordinary core
artifact retains `build/inventory/source-identity-regression.json` plus the three
original Gradle logs, actual fixture JARs and any newly written fixture inventories
under `build/inventory/source-identity-regression/`. This checks the producer's
source identity boundary, without establishing installed or release acceptance.

The explicit POM artifact query needs five metadata files that the prior runtime
resolution used Gradle module metadata instead of directly verifying. Their
new SHA-256 pins below were calculated from the exact Maven Central POM bytes
and matched against the cached bytes on 2026-10-18. The four Jackson files also
matched Central's published `.sha256` and `.sha1`; the Kotlin file matched its
published `.sha1` (no `.sha256` was published at that URL). These checks identify
the new metadata inputs; they are not a publisher-signature or license review.

| POM input | SHA-256 |
| --- | --- |
| [Jackson annotations 2.22](https://repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-annotations/2.22/jackson-annotations-2.22.pom) | `38c3ba52a0eade3e3eacc5b13499d56e1038ccbad8ec4bb79426dc21432b46d0` |
| [Jackson core 2.22.4](https://repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-core/2.22.4/jackson-core-2.22.4.pom) | `371b6859b58c3fc1c0823fe66e1e6d6ab251ba99b26f7dce883bbb7c43906c21` |
| [Jackson databind 2.22.4](https://repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-databind/2.22.4/jackson-databind-2.22.4.pom) | `81867c3c99c0e014d133554f2d30cdad78ec58cf1b56087088204e1e5eb34baa` |
| [Jackson YAML 2.22.4](https://repo.maven.apache.org/maven2/com/fasterxml/jackson/dataformat/jackson-dataformat-yaml/2.22.4/jackson-dataformat-yaml-2.22.4.pom) | `cb7695e43f99ae8bb240f1e4301357c7b1e7f55b0a76e99bc959d51aeded5e41` |
| [Kotlin standard library 2.1.20](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.1.20/kotlin-stdlib-2.1.20.pom) | `6750e1799ee502077dae5c3d599796f667606f60d35e95cb790448dc7912b40b` |

POM XML parsing rejects document types and external entities, restricts POM size
and field lengths, and never follows a license URL. License declarations retain
their upstream text; they are not interpreted as SPDX identifiers or legal
clearance. `declared-unreviewed` means direct metadata exists;
`no-direct-declaration` means it does not. A declared parent is recorded with
`parent_license_inheritance: not-evaluated`. Parent licenses, project properties,
multi-license choices, bundled notices and distribution obligations still need
their own review. In particular, an empty direct license list is not permission
to redistribute and is not proof that a dependency is unlicensed.

## Review boundaries

The graph describes resolved runtime inputs. It does not prove that every input
byte is packaged or reachable after Android transforms, nor is it a vulnerability
scan. Build plugins, test-only runtimes, native SDK tools and Gradle itself are
outside the graph; selected source hashes and observed tool versions do not make
this a complete build-tool inventory. The existing
[verified Robolectric runtime](ROBOLECTRIC_RUNTIME.md) remains a separate test
input.

The receipt links the artifact bytes actually built. It does not establish a
hermetic build or promise identical JAR/APK bytes across machines or times. It
also establishes no signing, installation, physical-device result, release
notices, Play distribution or security/privacy receiving-owner acceptance.
Before release, expand the inventory to the accepted release configuration and
all packaged/native components, review provenance and required notices, and
reconcile findings against the release criteria in Android #69. Its broader
vulnerability intake, urgent-update/rotation exercise and canonical receiving
record remain required; this source prerequisite alone does not close the issue.
