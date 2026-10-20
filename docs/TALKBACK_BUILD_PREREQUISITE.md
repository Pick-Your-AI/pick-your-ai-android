# TalkBack x86_64 build prerequisite

This isolated workflow asks one build question: can the pinned official TalkBack
phone variant produce an APK whose packaged native libraries support the existing
x86_64 Android guest? It does not install or enable TalkBack, exercise speech,
change the guest image, or establish the accessibility acceptance criteria.

## Selected source and exact changes

The source is [google/talkback at 229212fdf5842191d0a93fc95d9ca1423b346866](https://github.com/google/talkback/tree/229212fdf5842191d0a93fc95d9ca1423b346866),
native tree `3b119bb75fb2b2839ddc7d8d68c0aa2516375dbc`, release label
`TfPu_release_16_2`. The selected upstream commit is unsigned; its immutable
identity is checked without treating it as a signature attestation.

The machine-readable specification is
[scripts/talkback_build_prerequisite.json](../scripts/talkback_build_prerequisite.json).
Preparation verifies the full actual tracked file map and modes. It applies
exactly three literal replacements across two upstream files:

- `shared.gradle`: replace the two ARM ABI filters with `x86_64`.
- `shared.gradle`: replace the dynamic Volley `1.0.+` dependency with `1.0.19`.
- `build.gradle`: replace the wall-clock version suffix with
  `prerequisite_229212fdf584`.

Both the original and resulting native blobs and SHA-256 values are fixed in the
specification. No service, speech, Java/Kotlin, native interpreter, or Android
manifest source is changed. The complete tracked map is checked again after the
build, so any additional source modification fails qualification. Generated
untracked build outputs are not mistaken for reviewed source.

## Isolated toolchain and dependency observation

The upstream build selects Android Gradle Plugin 8.11.1 and Java 17. The
[AGP compatibility table](https://developer.android.com/build/releases/agp-8-11-0-release-notes)
requires Gradle 8.13 and supports API 36. This workflow copies the existing project
wrapper launcher, JAR, and properties into its own temporary directory. Only
the copied distribution URL and checksum are changed. The tracked project
wrapper and existing Android workflows remain unchanged.

The copied wrapper requires the official Gradle 8.13 binary distribution SHA-256
`20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`.
The build uses compile SDK 36, build tools 35.0.0, and the upstream native modules'
NDK 21.4.7075529. JDK 17 is selected by major version; its actual version output
and the installed SDK package revisions are recorded. These choices do not
claim byte-for-byte reproducibility across future runner or JDK updates.

A separate Gradle user home and a two-worker, four-gigabyte JVM budget keep this
build apart from the application's normal build. The workflow runs only
`:assemblePhoneDebug`; it neither substitutes an ARM guest nor uses translation.
The upstream native sources are built for x86_64, and the packaged ELF machine
identities are checked independently afterward.

The Gradle observation script rejects dynamic and changing dependency resolution.
It records the external components and actual resolved artifact hashes used by
this first build, including the pinned build plugin and Volley identities.
This inventory is **observed resolution**, not a previously reviewed dependency
checksum lock. It is a concrete input for later reproducibility review. A missing
dependency, incompatible source, or unsupported ABI remains a build failure.

## What a successful prerequisite establishes

The final guard binds the unchanged project source, pinned upstream source,
two exact modified blobs, copied wrapper, actual tool outputs, dependency
observation, and APK hash. It requires one phone debug APK with DEX code and:

- the expected application/package/version and TalkBack accessibility-service
  declaration, checked within the decoded manifest's actual element hierarchy;
- the accessibility service's own permission, exported flag, intent action,
  and configuration reference;
- only x86_64 native libraries under the APK's native-library directory, with
  ELF64 little-endian shared-object headers and the x86_64 machine identifier;
- the packaged source-built `libbrlttywrap.so`, `liblouis.so`, and
  `liblouiswrap.so` libraries;
- a successful SDK APK-signature verification with one recorded signer
  certificate digest. This is the upstream phoneDebug variant; no release
  signing identity or unsigned-APK claim is made.

The artifact contains the debug APK, source/tool/package receipt, decoded
manifest, signing output, and dependency observation. The receipt explicitly
records that no installation, enabled service, speech observation, or TalkBack
acceptance took place. All 17 synthetic Python guard methods must execute with
no failures or skips. They challenge the verifier and are not Android or
TalkBack execution.

## Subsequent service validation remains separate

The existing four-case installed smoke test, KVM/ACL wrapper, emulator pin,
source/APK handoff, and permission-restoration logic are unchanged. A subsequent
dependent proposal must establish the actual service and a real TTS engine
before exercising traversal and repeated updates.

TalkBack-generated fragment submission is distinct from a raw engine completion.
Any later observer must preserve real service/engine behavior and distinguish
raw start/done/stop/error callbacks from synthetic empty-text, request-failure,
cache, and queue-processing notifications. App accessibility events, overlay
text, or a fake TTS engine cannot stand in for that evidence. This build-only
prerequisite neither resolves those observations nor asserts a physical-device
requirement.
