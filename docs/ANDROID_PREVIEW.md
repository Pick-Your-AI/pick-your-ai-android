# Opt-in synthetic Android diagnostic

The `preview` module is an engineering experiment using platform Android Views,
not an approved product UI/framework, production package registration, support
matrix or release policy. It validates the unmodified frozen-v1 `build.json` example
and reports only structural validation; compatibility stays **unknown**. Fixtures
are bundled directly from the verified import, never copied into a real catalog.
There is no real-data mode or promotion path, account, network, import, draft,
telemetry or persistent state. Release variants are disabled.

The launcher is the only exported component. The manifest requests no permissions,
disables backup and cleartext traffic. Validation runs on a worker thread; closing
or recreating the Activity discards its result. Reopening returns to Ready and the
user can repeat validation. This is deliberate for disposable diagnostics, not a
claim of durable editing or process-death recovery for a future product.

## Reproduce

First run the frozen-import checks in [CORE_FOUNDATION.md](CORE_FOUNDATION.md).
Install JDK21 and Android SDK platform35 and build-tools35.0.0 from Google's official
SDK tools, reviewing the SDK license. Set `JAVA_HOME` and `ANDROID_HOME` locally;
no machine-specific path or proxy belongs in source. Use the pinned Gradle8.11.1
wrapper and AGP8.9.2. Kotlin2.1.20 is inherited from the root plugin pin. Both the
core and preview emit Java17-compatible bytecode while running builds on JDK21.
The SDK numbers bound this experiment; they are not asserted to meet future Play
submission policy.

```sh
./gradlew --no-daemon :core:check :core:assemble
./gradlew --no-daemon -PandroidPreview=true :preview:assembleDebug :preview:lintDebug
```

Without the property the Android module is not included and no SDK is required.
The debug APK is `preview/build/outputs/apk/debug/preview-debug-unsigned.apk`, with the
unregistered experimental identity `ai.pickyour.android.preview`. Debug signing
is disabled: this unsigned build creates no key and cannot be installed directly. Do not
publish this diagnostic as the product.

After separately authorized signing with existing suitable debug material,
on an available emulator/device install using `adb install -r` and explicitly
launch the diagnostic. Verify the synthetic notice, validation result, font
scaling, TalkBack order/announcements, keyboard access, RTL, system insets,
rotation, backgrounding and repeated validation. A successful build or lint check
alone does not establish those results.

## Issue acceptance remains partial

| Exact criterion | This slice | Remaining prerequisite and why engineering discretion is insufficient |
| --- | --- | --- |
| #48: Pinned compatible JDK/Gradle/Android/Kotlin dependencies, package ID decision, modular build, documented developer setup and clean-checkout build | Pinned opt-in experimental module, setup, bytecode compatibility and build evidence | #34 receiving acceptance must select product framework/package/support policy; this diagnostic cannot claim that stakeholder decision |
| #48: A fresh checkout builds with the documented pinned toolchain and installs/launches under the approved package identity | Debug build can be independently reproduced with the documented SDK | Approved production identity is absent; actual emulator/device installation and launch evidence must be collected rather than inferred |
| #48: Existing website checks remain independently runnable; no developer-specific configuration is required in source | No website edits; SDK opt-in and environment-only local configuration | Website CI remains separate; no website checks are claimed by this Android run |
| #77: Each preview visibly identifies synthetic data and validates against its version or is marked as a negative test | Persistent synthetic notice, frozen-v1 structural validation and explicit unknown compatibility | This bounded build-fixture diagnostic is not all required empty-draft, unknown, failed-rule and partial-quote product previews; #58 product UI/repository integration remains pending |
| #77: Explicitly synthetic sample data, isolated test contracts, no accidental promotion of fictional prices/components into real-data mode | Only bundled fixture assets, no real-data or publication path | Production catalog/promotion adapter must enforce contamination rejection when implemented; absence of a path is not a tested rejection at a real boundary |
| #77: Real-data mode rejects fixture contamination rather than displaying it as vendor evidence | No real-data mode exists | Actual real-data publication contract/adapter and #58 integrated architecture are required for a meaningful promotion test |

Canonical receiving records remain `docs/delivery/android/work-packages/02.01.md`
and `02.06.md` in `pickyour.ai`. These partial artifacts may be linked there by the
coordinator; they do not move acceptance paths or close either issue.

Primary toolchain reference: [AGP8.9 release compatibility](https://developer.android.com/build/releases/agp-8-9-0-release-notes),
reviewed 2026-10-18: API35, Gradle8.11.1, build-tools35.0.0 and JDK17 minimum.
Google's [SDK repository metadata](https://dl.google.com/android/repository/repository2-1.xml)
provides archive checksums. Local setup verified commandlinetools build13114758,
platform35 revision2, and build-tools35 against that metadata. SDK manager source
list fetching failed behind the environment proxy; checksum-verified official ZIP
installation supplied the same build inputs. This workaround is environment-local.

A separate restricted-token CI job compiles the unsigned diagnostic and runs lint,
retaining APK/lint reports for seven days. It uses the hosted runner's SDK and
installs the named experimental SDK inputs only when missing. The independent
core job still runs without opting into Android. A hosted job result must be
observed on the exact PR commit; the workflow definition alone is not evidence.

## Observed local evidence (2026-10-18)

The pinned toolchain produced an unsigned debug APK. A subsequent strict
verification replay of `:preview:assembleDebug :preview:lintDebug :core:check`
passed (49 tasks, 23 seconds); lint reported **No issues found**. The JVM suite
passed 33 tests, and the frozen-import verifier plus 21 Python reference tests
passed. APK inspection confirmed all 11 synthetic example assets and all 14
schema resources match their frozen source bytes; license/notice resources are
retained. The merged manifest has no permission requests and only the launcher
exported. No debug signing key was created.

No emulator/device install, render, TalkBack, rotation, process-death, performance,
release signing or Play result was obtained. The unsigned artifact cannot prove
any of those outcomes. The local SDK lacks platform-tools (a build warning only);
local analytics initialization also warns because the home directory is read-only.
Neither warning prevented strict compilation or lint. No source-level proxy,
trust-store bypass or machine path was added.
