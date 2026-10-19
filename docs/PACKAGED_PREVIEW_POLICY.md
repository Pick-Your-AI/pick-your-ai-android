# Packaged preview manifest and backup policy

This check supplies partial implementation evidence for [Android #89](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/89)
and its [CI baseline #59](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/59).
The adopted guest preview has no permission grant or remote service. It separately
supports explicit text-only diagnostic sharing through the system chooser. Its source
manifest disables cleartext traffic and backup and exposes only its launcher Activity.
A source review alone does not show whether manifest merging
or resource packaging preserved those declarations in the produced APK.

`scripts/check_preview_policy.py` reads the **binary manifest and referenced backup
resource in the exact built APK** using the pinned SDK Build Tools 35.0.0 AAPT2.
It fails if the declarations differ from the reviewed synthetic-preview boundary.
It does not modify the app, artifact, SDK, permissions, emulator or device.

## What is checked

| Packaged boundary | Required observation |
| --- | --- |
| Identity | Experimental package `ai.pickyour.android.preview`, version `0.0.1-synthetic` / code 1 and the adopted minimum/target SDK 26/35 |
| Manifest entries | One SDK declaration and one application; no requested/declared permissions, instrumentation, queries or additional root entries |
| Components | Only `PreviewActivity`, explicitly exported with the accepted `singleTop` launch mode, exactly the MAIN action and LAUNCHER category; no service, receiver, provider, alias, second Activity or inbound data filter |
| Application policy | Debuggable preview, `allowBackup=false`, `fullBackupContent=false`, `usesCleartextTraffic=false`; unreviewed application attributes, including network-security or backup-agent overrides, fail |
| Backup resource binding | The exact `dataExtractionRules` resource ID resolves to the single default `res/xml/backup_rules.xml` file; aliases, alternate resource IDs and configuration overrides fail |
| Backup declarations | Both cloud-backup and device-transfer exclude the root of all nine currently configured credential/device storage domains, without duplicate domains or narrowed paths |
| Artifact identity | APK bytes and SHA-256 match the existing runtime inventory; its recorded commit/tree match the clean checkout both before and after tool work; APK and inventory are unchanged through inspection |

Every accepted Android-qualified attribute must also carry its exact API 35
framework resource ID. Missing, zero or mismatched IDs cannot be replaced by a
safe-looking attribute name in AAPT2 output. Unqualified attributes cannot carry
framework IDs, and unexpected namespaces fail. Android's compiled attribute
lookup uses the resource ID; the package parser defaults `allowBackup` to true
when that framework attribute is absent. This binding prevents an unrecognized
compiled attribute from being reported as the explicit backup prohibition.
Framework IDs must also appear in strictly increasing order within each element,
because Android's attribute lookup advances through that ordered sequence. The
separate unqualified backup attributes remain valid and do not reset this order.

The fixed policy also checks each framework attribute's compiled value kind before
decoding strings. A reference-looking string is not an Android resource reference;
quoted `false` and decimal strings cannot replace compiled booleans or integers.
In particular, `dataExtractionRules` must be a reference and `fullBackupContent`
must be the explicit compiled boolean prohibition. The label's existing literal
string or resource-reference forms remain valid. Other existing value constraints
still apply after this kind check.

This is intentionally a fixed preview policy. A new feature, permission, component,
SDK pin or resource configuration needs its own source/design review and an explicit
policy update. The script is not a general Android manifest validator or release policy.
The default-empty external source-host policy and the OS/browser boundaries in the
[data inventory](security/DATA_INVENTORY_AND_THREAT_MODEL.md) retain their separate rules.

## Reproduce and retain evidence

First build and write the [runtime inventory](RUNTIME_INVENTORY.md) from a clean
committed checkout using the documented JDK 21 and Android SDK:

```sh
./gradlew --no-daemon --dependency-verification=strict \
  --init-script scripts/runtime_inventory.init.gradle.kts -PandroidPreview=true \
  :core:check :preview:assembleDebug :preview:lintDebug \
  :preview:testDebugUnitTest runtimeInventory
python -m unittest discover -s scripts/tests -p 'test_preview_policy.py'
python scripts/check_preview_policy.py \
  --aapt2 "$ANDROID_HOME/build-tools/35.0.0/aapt2"
python scripts/check_preview_policy_guards.py \
  --aapt2 "$ANDROID_HOME/build-tools/35.0.0/aapt2" \
  --android-jar "$ANDROID_HOME/platforms/android-35/android.jar"
```

Use `--offline` for Gradle when the locked, checksum-verified dependencies already
exist. The policy driver adds no dependency, permission or SDK download. Each tool
command has a 30-second deadline; failed, malformed or over-budget output cannot
produce a passing receipt. Binary-inspection stderr and arbitrary compiled string
values do not enter the policy report. The SDK's successful version command writes
to stderr; only its validated version string is accepted from that stream.
A failed current inspection removes an admitted previous policy receipt before
starting. The policy, packaged-policy guard and
[environment configuration](ENVIRONMENT_CONFIGURATION.md) commands share a fixed
receipt boundary for `build/inventory/preview-policy.json`,
`build/inventory/preview-policy-guards.json` and
`build/inventory/environment-configuration.json`. Existing `build` and
`inventory` entries must be ordinary directories; an existing receipt must be a
singly linked regular file. A symlink, hardlink, directory or other nonregular
destination is rejected before inspection or deletion, preserving the unsafe
entry and any unrelated target. Environment `--source-only` does not touch a
receipt.

After successful inspection, the destination is checked again and a complete
JSON document is written to a private sibling temporary file before replacement.
Serialization or replacement failure removes that temporary file and cannot
publish a new passing receipt. These checks assume the canonical checkout and its
output namespace remain stable during filesystem operations; they do not claim
protection against hostile concurrent directory replacement or power-loss
durability. Real-filesystem controls, including changes made between inspection
and publication, run with:

```sh
python -m unittest discover -s scripts/tests -p 'test_preview_policy.py'
```

The packaged guard admits its output before Git/tool metadata or fixture work.
Its real AAPT2 positive and eleven negative cases remain unchanged. The same
fixed-name helper also publishes the separate
[runtime source regression](RUNTIME_INVENTORY.md) report; that command admits its
whole fixed evidence set before retiring any member. The destination regressions
replace expensive inspection with controlled results and prohibit native child
commands. They establish filesystem behavior, separately from the actual packaged
fixture and Java-producer checks required by CI.

The existing native CI job executes all three Python commands after producing its
runtime inventory. The existing `android-runtime-inventory-<checkout>` archive retains
`build/inventory/preview-policy.json` and `preview-policy-guards.json` alongside
the APK and runtime inventory. The installed-emulator consumer's separate preview
archive layout is unchanged.

The successful artifact receipt records source commit/tree, runtime-inventory digest,
APK digest/size, AAPT2 version/digest, accepted declarations and digests of the three
tool observations. Its current status must be supported by the successful CI step;
an old artifact or a failed run is not new acceptance. The policy-fixture receipt
separately identifies the SDK tool/platform hashes, disposable package hashes and
expected/observed rejection codes.

## Executable negative controls

The host suite rejects added permissions/components, missing or enabled security
flags, alternate entry actions/data, unreviewed overrides, incomplete/duplicate backup
domains, aliases/configuration overrides, malformed trees and duplicate attributes.
Real temporary Git repositories also demonstrate that a tracked edit or a new HEAD
during inspection cannot receive a passing receipt while APK/inventory bytes remain
unchanged; the unchanged-source positive control still passes.
These assertions are supplemented by actual binary packaging: the AAPT2 guard driver
builds twelve disposable resource APKs with a positive control and eleven single-condition
changes. They add INTERNET, add an exported receiver, enable cleartext traffic, remove
one device-transfer exclusion, or redirect `dataExtractionRules` to a different
resource. Two additional controls change only the compiled `allowBackup` resource-map
entry: one removes its framework ID and one substitutes a different ID while retaining
the displayed name and false value. Both must fail at the attribute-ID boundary.
A third changes only the order of the compiled `allowBackup` and `fullBackupContent`
attribute records. Their valid names, IDs and values cannot conceal broken lookup order.
Three further controls preserve the displayed reference, boolean or integer spelling
while replacing the compiled `dataExtractionRules`, `fullBackupContent` or `launchMode`
value with a real string-pool entry and `TYPE_STRING`. Each must fail at the value-kind
boundary before a decoded string can masquerade as the required compiled value.
Each negative package must reach its exact policy rejection. Those fixtures
contain no application DEX and never install or execute; their result establishes
the exercised binary declaration boundary only.

## Primary references and remaining acceptance

Official Android references checked on 2026-10-18:

- [AAPT2](https://developer.android.com/tools/aapt2) documents compiled resource inspection and the `dump xmltree` / `dump resources` commands.
- [Application manifest attributes](https://developer.android.com/guide/topics/manifest/application-element) define the backup, cleartext and application configuration fields.
- [Auto Backup](https://developer.android.com/identity/data/autobackup) explains the separate backup/device-transfer controls and the OEM-dependent limitations of disabling backup.
- Android 15's [attribute resolver](https://github.com/aosp-mirror/platform_frameworks_base/blob/android15-release/libs/androidfw/AttributeResolution.cpp) matches compiled attributes by framework ID; its [package parser](https://github.com/aosp-mirror/platform_frameworks_base/blob/android15-release/core/java/com/android/internal/pm/pkg/parsing/ParsingPackageUtils.java) consumes those resolved application attributes and defines the backup default.
- Android 15's [resource resolver](https://github.com/aosp-mirror/platform_frameworks_base/blob/android15-release/libs/androidfw/AssetManager2.cpp) resolves only reference-typed values; [TypedArray](https://github.com/aosp-mirror/platform_frameworks_base/blob/android15-release/core/java/android/content/res/TypedArray.java) returns the resolved resource identity instead of interpreting a reference-looking string. The package parser separately consumes `fullBackupContent`'s typed data.

The observed declarations establish neither actual backup exclusion on a device nor
absence of every external transfer. OS input/accessibility services, an explicitly
opened browser and [reviewed diagnostic sharing](LOCAL_DIAGNOSTICS.md) remain separate
surfaces. This check does not exercise TLS/certificate
handling, Keystore/session isolation, seeded-secret logging, physical-device backup,
release signing, store delivery or any of #89's release-build criteria. Those work
packages remain open until their own accepted implementation and actual evidence exist.
