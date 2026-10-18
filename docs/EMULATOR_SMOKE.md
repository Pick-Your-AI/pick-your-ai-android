# Installed synthetic emulator verification

The existing **Unsigned synthetic Android preview** CI job builds, lints and runs
the named Robolectric regressions on Linux. After that job succeeds, **Installed
synthetic Android preview (Intel HVF)** verifies the exact unsigned APK handoff and
runs the same smoke harness on an explicitly selected Intel macOS runner. It uses
one fresh AOSP API 35 x86_64 AVD. The workflow
definition and host-side harness tests are preparation; only an observed passing
run and its exact receipt establish installed-emulator evidence.

This is a bounded continuation of [Android #59](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/59),
with partial installed behavior for [#48](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/48),
[#67](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/67) and
[#86](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/86).
It exercises the existing synthetic guest preview; it does not select a production
package, support matrix, service contract, signing policy or release.

## Cases and evidence limits

| Required installed case | Assertions made by the harness | Limit |
| --- | --- | --- |
| `installed_synthetic_launch` | Fresh install launches the exact experimental Activity; the synthetic notice and active `guest-draft` identity are visible | One AOSP image and diagnostic package |
| `acknowledged_save_survives_force_stop` | Enter fixed synthetic title/objective/context; Save displays its acknowledgement and disables; HOME then force-stop terminates the process; a new process reopens the same acknowledged fields and local ID | Force-stop/cold relaunch, not low-memory process death, autosave or power-loss durability |
| `pending_fields_survive_rotation` | Pending title/objective edits survive observed landscape and portrait rotations; context stays unchanged; Save remains enabled and the form remains explicitly unsaved | Two orientations in one emulator configuration, not the support matrix or general adaptive/accessibility qualification |
| `confirmed_deletion_does_not_resurrect` | Select the exact saved library row, inspect the ID-specific deletion confirmation, confirm removal, retain pending editor fields with Save/history disabled, then verify an empty library before and after another cold relaunch | Current readable local draft, not every corrupt/interrupted cleanup, storage-full, another-draft, secure-erasure or remote-deletion case |

The harness drives the installed app through `adb` input and UI Automator hierarchy
dumps. It locates current visible labels, adjacent editors and exact button text;
taps derive from the observed bounds. Ambiguous selectors, disabled targets,
missing controls, changed values and missing/failed/unexecuted cases fail the lane.
Rotation is accepted only after the hierarchy reports the requested orientation.
The fixed inputs contain only synthetic ASCII text. These checks do not measure
TalkBack traversal, real keyboard usability, contrast, performance or energy.

On the [second hosted attempt](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39098735808),
the owned emulator booted and the APK installed, but the first UI dump returned
exit 137 with only its exact completed-file message. The launch case failed and
the remaining three cases were unexecuted. The harness now allows that particular
status only with the exact expected-path completion message and empty stderr.
Every snapshot first successfully removes the prior dump, independently reads
the new file and requires complete hierarchy XML with a valid rotation and nodes.
Missing, stale, truncated or invalid XML and every other nonzero/error result fail.
The receipt counts attempted/completed dumps and completed dumps with exit 137;
the same installed selectors and behavioral assertions must still pass. This
handling does not establish why Android terminated that utility or demonstrate
performance stability.

The [first explicit software attempt](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39100152906)
checked out `c3fcacae081ffd4a7f6b6d55fb5a20c7aab3d846`, with the reviewed
`89ce14ae9b6be91af9a49b7a43c162c2e79dde42` tree. Core checks, build, lint and all
11 simulated cases passed. The emulator log recorded boot in 679.642 seconds and
the synthetic APK installed. The initial Activity launch check then failed; the
remaining three cases were not run and no UI hierarchy completed. Artifact
`12126154523` has verified ZIP SHA-256
`741d882b39b4a990994ef18463b4ab4a4d7506c2432b5d14d28c74f1fb9cbe33`.
The old launch predicate discarded the command's output, so this receipt does not
establish a timeout, crash or other cause. No installed case passed.

Launch attempts now retain separate bounded stdout/stderr tails, exit code or
command timeout, elapsed time and the result of the unchanged launch check in
`activity_launches`. A failed case also attempts a filtered 400-line logcat read
from the owned fresh emulator, retaining at most 131,072 characters and its command
result. Failed log reads and failed UI checkpoint capture are recorded explicitly
without replacing the original case failure. These diagnostics do not accept a
launch timeout or relax any installed assertion; another observed run is required.

## Isolation and diagnostic signing

Provisioning is restricted to a standard disposable GitHub-hosted runner; the
current installed job explicitly selects `macos-15-intel` and requires observed
Darwin/x86_64 with hosted macOS/X64 identity and existing Hypervisor.Framework support.
The workflow retains `contents: read`, checkout credential persistence stays off,
and no production, publisher, signing or deployment secret is supplied. This uses
the existing standard runner, not a larger paid runner or external coding job.
The independent JVM/contract lane and the existing website workflows remain separate.

The script refuses to proceed if any adb device is already connected. It creates a
new randomly named AVD in its own temporary directory and starts one emulator
process group on `emulator-5554`. Before installation and process-control actions,
it verifies the running process, emulator property and exact generated AVD name.
The fresh AVD must not already contain the preview. There is no shared-device
selection, overwrite install, user-data clear, shared adb shutdown or host-VM reset.
All app mutation occurs inside that owned synthetic installation.

The normal Gradle build continues to disable debug signing and all release
variants. The smoke stage verifies its unsigned input's package, launcher, debug
flag, SDK bounds and absence of requested permissions. It creates a two-day
disposable PKCS12 certificate in the job's temporary directory and uses
`apksigner --out` to sign a separate copy. It verifies that signature, records the
certificate fingerprint and both APK hashes, and checks the original unsigned APK
is unchanged. This certificate is unrelated to any publisher or product identity.
Neither the key nor the installable copy is committed or uploaded.

The emulator uses the explicit CPU mode below, headless software graphics, no saved snapshot, no audio or
camera, and disabled emulator metrics. Only this job's process group is terminated
during normal completion or failure, and its AVD/key/copy are removed with the
temporary directory. Hard job cancellation additionally relies on disposal of the
hosted VM. Artifact retention never includes AVD userdata or the temporary signing
directory. In hardware mode a read-only KVM preflight reports the current effective UID, groups and
device ownership/mode, requires a real character device and checks existing
read/write access. It runs before SDK provisioning and again before the emulator
acceleration probe. Missing devices, symbolic links and unavailable access fail
clearly; this lane changes no KVM ownership, permissions, groups or udev rules.

The preceding Linux workflow explicitly selected `PYAI_EMULATOR_MODE=software`.
That remains an opt-in experimental diagnostic setting, not an automatic fallback or a vendor-supported
CI performance profile. The official emulator command-line reference documents
`-accel off` as unsupported and very slow and requires the explicit
`ANDROID_I_WANT_MY_TCG=yes` opt-in; that variable is supplied only to the owned
emulator process. Software mode accesses no KVM device and grants no host access.
The [read-only hardware probe](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39099340825)
found `/dev/kvm` mode `crw-rw----`, owner 0/group 993, while the hosted job's
effective UID 1001 had groups 4, 100, 118, 999 and 1001. It failed before provisioning
because existing KVM access was unavailable; its permissions were left unchanged.

The preceding software lane used a 900-second boot deadline inside a 40-minute job budget. It
still must boot the pinned image, install the APK and execute every required case;
timeout or failure cannot become successful or skipped acceptance. The receipt
records the selected mode, exact acceleration argument, boot budget and unsupported
opt-in alongside the actual tool/platform versions and case results. Feasible
execution has not been established by this configuration or its host guard tests.
When `PYAI_EMULATOR_MODE` is absent, KVM mode remains the default: the strict
read-only KVM preflight and emulator `-accel-check` must pass, with a 300-second
boot deadline. Unknown mode names fail and hardware failure never switches modes.

The Linux emulator host also needs Ubuntu's `libpulse0` runtime library even with emulator audio
disabled. The previous preparation step installed that specific package and the receipt
recorded its installed version. The first [hosted attempt](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39098235903)
passed compilation/lint and all 11 simulated cases, then failed loading
`libpulse.so.0` during emulator version inspection. Its retained receipt correctly
marks all four installed cases unexecuted and the original APK unchanged. This
failure is setup evidence; a later passing installed receipt is still required.

### Observed Intel HVF capability and selected lane

The read-only diagnostic [#244](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/244)
ran on the existing `macos-15-intel` label in
[run 39104147868](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39104147868).
Its observed hosted macOS 15.7.9/X64 environment returned `kern.hv_support=1` and
the preinstalled emulator 37.1.11.0 returned exit 0 with
`Hypervisor.Framework OS X Version 15.7`. All bounded observations were complete,
without stderr or timeout. That diagnostic was closed without merging its temporary
workflow. It establishes capability on that observed host only; it booted no AVD.

The installed job now explicitly selects `PYAI_EMULATOR_MODE=hvf`, a 300-second boot
deadline and a 25-minute job budget. Both its preparation and harness check the
hosted Intel platform and read-only `/usr/sbin/sysctl -n kern.hv_support` result.
The harness also requires a complete successful `emulator -accel-check` result
identifying Hypervisor.Framework, uses `-accel on`, and records the actual host and
acceleration observations. Missing, failed, truncated, contradictory or timed-out
observations fail; neither KVM probing nor software fallback follows an HVF failure.
The preinstalled emulator version is pinned to 37.1.11.0 for this bounded adaptation;
runner version drift requires a reviewed pin update. The workflow installs only the
selected AOSP image through the existing SDK manager and verifies existing adb and
build-tools 35.0.0. It changes no host permission, group, driver, hypervisor or SDK
emulator binary. Linux-specific `dpkg-query` is not used on macOS.

The successful Linux job exposes the unsigned APK's SHA-256 and its immutable
uploaded artifact ID as job outputs. The installed job requires a single nonempty
numeric artifact ID, downloads only that ID from the same workflow run with pinned
`actions/download-artifact` v4.3.0, and compares the APK bytes against the producer's
separate digest before signing or installation. Both checkouts must match the same
workflow commit. The build/lint/simulation job must succeed before the installed
job can start. A missing handoff or a different APK cannot become installed evidence;
the digest alone is not a publisher attestation or release signature.

The download explicitly sets `merge-multiple: true` while still accepting only one
numeric artifact ID. In the pinned action's
[extraction implementation](https://github.com/actions/download-artifact/blob/d3f86a106a0bac45b974a628896c90dbdf5c8093/src/download-artifact.ts),
an ID-only lookup otherwise adds the artifact-name directory even when the list
contains one ID. The explicit setting restores the archive's `outputs/...` path
directly under `preview/build`, matching the harness; it broadens no artifact selection.
The workflow layout regression applies those pinned extraction rules and reproduces
the wrong nested APK path when the setting is removed.

This change preserves the exact guest user/storage readiness gate, successful
launch predicate, fresh UI hierarchy rules and all four installed case bodies.
The prior software receipt [39103295994](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39103295994)
still records inaccessible `/sdcard` with an unlocked user and all four cases
unexecuted. Hardware support does not establish the cause of that storage failure
or guarantee a cure. A fresh installed receipt must determine the result.

The additional seven host guard methods bring the local harness suite to 36 passing
tests. They cover foreign/missing host identity, absent/ambiguous support, command
timeouts, acceleration output, exact APK handoff and final receipt rejection.
Those Linux-hosted unit tests do not execute Hypervisor.Framework or install Android.

## Inputs and provenance

The application keeps its existing JDK 21, Gradle 8.11.1, Kotlin 2.1.20, AGP 8.9.2,
build-tools 35.0.0, compile/target 35 and minSdk 26 pins. This lane selects
`system-images;android-35;default;x86_64` **revision 2** and rejects revision drift.
The upstream SDK manager resolves that package through the official SDK repository;
the AOSP image needs no Google account or Google Play image.

The runner's binaries can change independently of the image pin. The HVF lane
rejects a preinstalled emulator version outside its separate pin; adb remains an
observed runner input. The receipt records the actual emulator and adb versions,
emulator binary hash, image package metadata hash, `system.img` hash, runtime API,
ABI, build fingerprint, persisted/default locale properties, display size and density. The runner image and
these tool versions are recorded inputs, not an assertion that every runner byte
is immutable. Review unexpected platform/tool behavior before changing this gate.

`preview/build/reports/emulator-smoke/receipt.json` also records the exact checked
out commit/tree, workflow commit, run ID/attempt, generated AVD identity, required
case results, process restart observations and unchanged unsigned input status.
Its UI-dump counters expose the narrowly handled utility exit described above.
The script refuses a checkout/workflow mismatch or tracked source modifications.
A second workflow step requires the exact four successful case IDs and matching
workflow commit, preventing an absent, stale or incomplete report from passing.
Setup failure is an explicit JUnit failure with the four cases unexecuted.

The existing seven-day `android-preview-<workflow commit>` artifact retains the
unsigned APK, lint reports and simulated-test reports from Linux. The separate
`android-installed-hvf-<workflow commit>` artifact retains this JSON receipt, JUnit
XML, bounded emulator log, synthetic UI XML/PNG checkpoints and bounded failure
logcat when available. The final receipt requires the selected HVF mode, observed
host/acceleration and producer APK digest in addition to guest readiness and all
four actual cases. Link the exact
run, commit and artifact before treating it as receiving evidence; artifact expiry
does not create a permanent acceptance record.

## Running and reviewing

The core lane checks the harness guards. The dependent installed lane executes the
same harness after the successful unsigned build/simulation and exact APK handoff:

```sh
python -m unittest discover -s scripts/tests -p 'test_android_emulator_smoke.py' -v
python scripts/android_emulator_smoke.py
```

The first command is safe host-side validation of target, selector and receipt
rejection. It does not execute an emulator. The second intentionally refuses a
local/self-hosted environment and expects the workflow's SDK inputs and explicit CPU mode.
Use the ordinary PR workflow to obtain its actual installed run. Keep the required
contract/domain failure gates in the independent core lane; do not turn a missing
emulator, image download, install or case result into a skipped-success job.

For failures, inspect the explicit failed phase/case, bounded emulator log and
available failure screenshot/hierarchy first. Then distinguish tool download or
boot problems from app behavior or selector drift. Preserve the required expected
behavior when repairing a harness failure. Re-run the exact reviewed combined
source after concurrent application changes; do not infer a new result from an
earlier tree.

## Receiving gates that remain separate

This lane does not itself close #59. The independently reviewed, isolated
[negative probe #223](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/223)
added one indentation byte to the frozen example without changing its parsed JSON
or checker. In [run 39100443483](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39100443483),
core job `117595446217` failed the ordinary contract step with exactly
`Contract integrity failure: frozen bytes changed: examples/build.json`; the native
job passed its build, lint and all 25 simulations. The probe was closed without
merge, and main retained the original fixture bytes. This is observed frozen-byte
enforcement, not semantic schema/domain coverage or evidence of merge protection.

Actual protected required-status enforcement and the canonical CI receiving
record still require their completion evidence. The read-only inspection at
2026-10-18 05:34 UTC on Android main `06a6c28c546c86b237eb8118008dfd28a378d70b`
reported `protected: false`, protection disabled, enforcement `off`, empty required
contexts/checks and an empty repository/parent rulesets list. The connected
integration received a 403 for the detailed protection endpoint; the per-branch
rules endpoint was unsupported by that connector and supplied no rules evidence.
The observed main check names `Frozen contracts and JVM core` and `Unsigned
synthetic Android preview` both passed [run 39100480068](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39100480068),
but observed names are not configured requirements. The historical application
protection statement in the migrated issue is not evidence of Android's current
settings. [Issue #59's review receipt](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/59#issuecomment-6246184712)
records this unmet gate; no Android or website protection setting was changed.

Physical devices, low-memory death, interrupted writes/deletes, storage-full cases,
supported OS/window/locale coverage, TalkBack and other manual accessibility,
release signing, optimized builds, Play delivery and installed production results
remain distinct. Canonical acceptance paths remain the existing work-package
records in `Pick-Your-AI/pickyour.ai`; these source and run receipts do not relocate
them or authorize production publication.

Primary references checked 2026-10-18: [GitHub-hosted runner Android acceleration](https://docs.github.com/en/actions/reference/runners/github-hosted-runners),
[Android emulator command line](https://developer.android.com/studio/run/emulator-commandline),
[KVM acceleration](https://developer.android.com/studio/run/emulator-acceleration),
[apksigner](https://developer.android.com/tools/apksigner), and the official
[AOSP system-image metadata](https://dl.google.com/android/repository/sys-img/android/sys-img2-1.xml).
Ubuntu's [libpulse0 file list](https://packages.ubuntu.com/noble/amd64/libpulse0/filelist)
identifies the required `libpulse.so.0` library on the selected runner distribution.
The SDK metadata lists API 35 default x86_64 revision 2 as `x86_64-35_r02.zip`,
782,404,023 bytes, with its published archive checksum. These references establish
tool usage and available inputs; they do not establish a passing app run.

Additional primary references checked 2026-10-18:
[Android macOS acceleration](https://developer.android.com/studio/run/emulator-acceleration#vm-mac),
[Intel macOS runner inventory](https://github.com/actions/runner-images/blob/main/images/macos/macos-15-Readme.md)
and pinned [download-artifact v4.3.0 inputs](https://github.com/actions/download-artifact/blob/d3f86a106a0bac45b974a628896c90dbdf5c8093/action.yml).
The download action's exact tag resolves to `d3f86a106a0bac45b974a628896c90dbdf5c8093`;
no cross-repository/run token is supplied. Current Android documentation identifies
`swiftshader_indirect` as deprecated; this adaptation preserves that existing
graphics selection for the isolated capability change. Actual graphics results
still require the fresh installed run, with no claimed renderer qualification.

## Owned guest readiness after boot

The diagnostic run [39101681533](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39101681533)
at source tree `fc21847c0af12a9a5cad00249e8f65befd526a33` reached boot and installed
the APK, but its first launch failed. The retained `am start -W` result was exit 0
with `Status: timeout`, `LaunchState: UNKNOWN (-1)` and `WaitTime: 24344` after
26.775 seconds. This was Android's launch-wait timeout, not a Python subprocess
timeout or demonstrated application crash. A failed UI checkpoint then could not
remove `/sdcard/pyai-smoke-window.xml` because access was denied. The other three
installed cases did not run. Artifact `12126642819` has ZIP SHA-256
`088224b76255521ff996a9790f5f282c28f96ac52593632609589c5547ef4556`.

The original setup observed only `sys.boot_completed` before continuing. Setup now
also requires a complete read-only readiness snapshot after the existing wake and
keyguard-dismissal commands, before installation or any application case:

| Observation | Required result on this pinned AOSP API 35 guest |
| --- | --- |
| `getprop sys.boot_completed` | Exact `1`, successful command, no stderr |
| `getprop init.svc.bootanim` | `stopped`, or absent for the existing `-no-boot-anim` launch |
| `am get-current-user` | Exact `0`; no user is created or switched |
| `am get-started-user-state 0` | Exact `RUNNING_UNLOCKED`; no unlock command or credential is supplied |
| `pm path android` | The pinned image's `package:/system/framework/framework-res.apk` |
| `test -d/-r/-w/-x /data/local/tmp` | All four commands succeed with empty output/stderr |

The full snapshot must complete within a single 180-second deadline. Each command
gets at most ten seconds and never more than the remaining deadline. Incomplete
observations can be retried within that deadline; command timeouts, nonzero exits,
unexpected output, stderr and truncated diagnostic output cannot establish
readiness. The receipt retains the number of attempts, elapsed time, first
incomplete and last observations, including separate bounded output and command
timeout metadata, and the exact `ui_dump_path`. Missing, false or differently
scoped readiness is rejected by the final receipt validator even if its case
rows were marked passed.

These probes observe access; they do not create/delete a file, change guest
permissions, claim usable UI, or measure app performance. The
existing fresh XML write/read, successful `Status: ok` launch and all four
behavioral cases remain mandatory. The observed software-mode system-service
ANRs and inaccessible shared storage suggest a readiness investigation; they do
not prove this additional gate cures the launch timeout. A new exact-source
hosted result must supply that evidence. Software/KVM selection, host access,
owned-target identity and temporary-signing boundaries are unchanged.

The [Android Direct Boot guide](https://developer.android.com/privacy-and-security/direct-boot)
(checked 2026-10-18) distinguishes credential-encrypted storage availability after
user unlock from the powered-on device state. The pinned Android 15
[ActivityManager shell source](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/services/core/java/com/android/server/am/ActivityManagerShellCommand.java),
[ActivityManager service](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/services/core/java/com/android/server/am/ActivityManagerService.java)
and [UserState](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/services/core/java/com/android/server/am/UserState.java)
define the read-only started-user-state query and its `RUNNING_UNLOCKED` spelling.
The API 35 UserManager shell does not expose `cmd user is-user-unlocked`, so this
harness uses the actual command available in its pinned platform.

Host-side regressions verify complete/partial snapshots, locked or different
users, denied storage, failed services, timeout/truncated output, exact owned
target, the absolute deadline and final receipt rejection. These are harness
tests, not installed-case acceptance.


### Observed unlocked user and UI-dump directory correction

The first readiness run [39103295994](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39103295994)
used source `750c75b5a4c1c636a76f3d90932d22bb20d0b4bf`, tree
`fa85f8ddf2167ac21cb5e3eb4512c784f57b089e`. Build, lint and all 29 pinned-base
simulated Activity cases passed. The owned software AVD booted, but readiness
failed after 180.002 seconds and 15 observation attempts, before APK installation
or any of the four installed cases. In its first full snapshot, boot, stopped
animation, current user 0, `RUNNING_UNLOCKED` and the platform package all passed;
the four `/sdcard` directory/access tests each returned 1 with empty output and
stderr. Its last partial snapshot timed out querying the current user with 1.169
seconds left. No full snapshot passed. Artifact `12127459274` has ZIP SHA-256
`571214cf59312c1c2cb8b1119993455e0435ece14f14b505d6d1b6ca764c83bf`.

That receipt shows the user was already unlocked at the first observation; a
locked-user explanation does not account for that observed `/sdcard` failure.
It does not establish why shared storage was unavailable or explain the earlier
launch timeout. All four cases remain `not_run`, and the earlier launch-failure
receipt remains relevant.

The synthetic app stores drafts in its existing internal `noBackupFilesDir`.
The harness used `/sdcard` only to stage UI hierarchy XML, adding an unnecessary
external-storage prerequisite. It now requests the explicit file
`/data/local/tmp/pyai-smoke-window.xml` and observes directory/read/write/search
access to that existing directory on the verified owned guest. It does not create
the directory or change any permissions. Every snapshot still requires successful
removal of that exact prior path, a fresh explicit-path dump, and an independent
read and parse of complete hierarchy XML. An exit-137 completion marker naming the
old `/sdcard` path is rejected. The receipt binds readiness to the exact new path.
The successful `Status: ok` launch predicate and all four behavioral case bodies
are unchanged.

Pinned Android 15 sources checked 2026-10-18 support this path choice:
[DumpCommand](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/cmds/uiautomator/cmds/uiautomator/src/com/android/commands/uiautomator/DumpCommand.java)
uses a supplied non-option argument as the `File` passed to the dumper;
[AccessibilityNodeInfoDumper's explicit-file overload](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/cmds/uiautomator/library/core-src/com/android/uiautomator/core/AccessibilityNodeInfoDumper.java)
writes that file without changing directory permissions; and
[platform init](https://github.com/aosp-mirror/platform_system_core/blob/android-15.0.0_r1/rootdir/init.rc)
provisions `/data/local/tmp` for the shell user. Source establishes the platform
mechanism, not the capabilities of a future guest. The previous owned AVD was
destroyed by cleanup, so actual access must be observed in the next fresh hosted
run. This staging correction does not claim a cure for the launch timeout or
installed acceptance; both access and all installed assertions must still pass.
