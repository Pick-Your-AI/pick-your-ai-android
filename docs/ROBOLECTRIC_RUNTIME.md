# Verified simulated Android runtime

Partial implementation for [#69](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/69)
and [#59](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/59).

Robolectric normally fetches its simulated Android SDK at test execution time.
That download does not pass through Gradle's dependency lock and verification
metadata. The preview now declares its API 28 SDK as the nontransitive,
nonconsumable `robolectricRuntime` configuration, resolves it through the existing
repositories and checksum policy, and copies it with `prepareRobolectricRuntime`
into `preview/build/robolectric-runtime`.

Every preview `Test` task depends on that preparation and declares the verified
runtime as a task input. The task sets `robolectric.offline=true` and
`robolectric.dependency.dir` to the prepared directory. Robolectric therefore
cannot silently fetch an additional or changed SDK outside Gradle. Its runtime
configuration is separate from the production and test compilation classpaths;
the SDK is not an application dependency. See the upstream
[Robolectric system-property documentation](https://robolectric.org/configuring/#system-properties).

## Pinned input and provenance

The existing tests explicitly select API 28. With Robolectric 4.17, that uses
`org.robolectric:android-all-instrumented:9-robolectric-4913185-2-i7`.
Both the JAR and POM are pinned in `gradle/verification-metadata.xml`; the version
is locked only to the new configuration in `preview/gradle.lockfile`. Existing
lock entries and checksums are retained.

| Artifact | SHA-256 |
| --- | --- |
| `android-all-instrumented-9-robolectric-4913185-2-i7.jar` | `dbb1c88987b54cef3132695a1b4d2f4851ba06286a6fa7d15772f98fd1f0cdf7` |
| `android-all-instrumented-9-robolectric-4913185-2-i7.pom` | `2b53c723d90304666cc9c3332f260e58b40e13a25713de025783d46080128a66` |

The files were retrieved from their
[Maven Central coordinate](https://repo.maven.apache.org/maven2/org/robolectric/android-all-instrumented/9-robolectric-4913185-2-i7/)
and checked against its published SHA-512 values before computing these SHA-256
pins. The publisher POM identifies the project as the Android framework compiled
with Robolectric instrumentation and declares Apache License 2.0. This is
provenance for a test input, not a complete dependency license inventory or a
software bill of materials. No downloaded runtime binary is committed.

## Reproduce and update

With the documented JDK 21 and Android API 35/build-tools 35 toolchain available,
the ordinary test command resolves the verified runtime on its first run:

```sh
./gradlew --no-daemon --dependency-verification=strict -PandroidPreview=true \
  :preview:testDebugUnitTest
```

After the required Gradle dependencies have been cached, the same task also runs
with Gradle's `--offline` flag. Gradle offline mode governs build dependency
resolution; Robolectric's separate offline property is always enabled by the
preview build script. The hosted preview workflow explicitly selects strict
dependency verification and retains its existing JUnit report gate.

When a reviewed test change needs another simulated SDK or a Robolectric version
with a different instrumentation revision:

1. Select the exact runtime coordinate required by the new test configuration.
2. Review the publisher's artifact provenance and POM/license, verify the actual
   bytes against published checksums, then add their SHA-256 pins. Investigate a
   changed artifact at an existing coordinate rather than accepting new bytes.
3. Add the test-only runtime dependency and regenerate the new configuration's
   lock with `:preview:prepareRobolectricRuntime --write-locks`. Review the diff
   and preserve unrelated locks and verification entries.
4. Run the simulated tests with strict verification, then replay from the cached
   dependencies using `--offline`. Keep runtime fetching disabled. A missing
   required SDK should fail until its dependency and pins are reviewed.

## Bounded validation

On the source baseline `a4e7df280ed0cf2b3d4bee629364761647bac89d`, a clean
`--offline --dependency-verification=strict` replay completed
`:core:check`, `:preview:testDebugUnitTest`, `:preview:assembleDebug` and
`:preview:lintDebug`: 218 core tests and ten simulated Activity tests passed
with zero skipped tests. Lint reported zero errors and two existing warnings
(a plural-string candidate and a custom-view constructor). The preview included catalog search, native graphics
large-text/RTL and forward-focus cases. A disposable Gradle project using this exact verification
metadata and runtime coordinate rejected a deliberately corrupted synthetic JAR
before copying it into the runtime directory. The shared dependency cache was not
modified for that tamper check.

This closes the runtime-download integrity gap in the local simulated test path.
It does not establish installed-device behavior, a release support matrix,
reproducible signed release output, Play readiness, a complete SBOM/license review,
or an exercised vulnerability-response process. Those acceptance criteria remain
open in #69/#59 and their dependent issues.
