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
An ordinary zero exit now requires the same complete message and empty stderr.
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

The installed job now explicitly selects `PYAI_EMULATOR_MODE=hvf`, a 600-second boot
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

The initial HVF adaptation added seven host guard methods to the 29-test baseline,
for 36 passing tests at that revision. They cover foreign/missing host identity, absent/ambiguous support, command
timeouts, acceleration output, exact APK handoff and final receipt rejection.
Those Linux-hosted unit tests do not execute Hypervisor.Framework or install Android.

### Observed HVF boot failure and bounded diagnosis

The first actual HVF [run 39106224144](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39106224144)
checked out `19bd133bee2581e9512a86e312e84fb8aef37877`, tree
`5373ea965c2f7a14d26338c5591d33d419b869d7`, with parents `53429869` and `414df7c9`.
Its core and Linux native jobs passed. The independently downloaded reports contain
260 core and 29 simulated Activity cases, zero failures/errors/skips, on this older
canonical readiness baseline. Later current-main features still require their own
receiving integration and named cases.

The installed job verified hosted macOS 15.7.9/X64, `kern.hv_support=1`, preinstalled
emulator 37.1.11.0 and the complete successful HVF acceleration output. Its downloaded
unsigned APK matched the Linux producer SHA-256
`c3e99c4783b5b71da2df1d6e665c9f6129509f5978f40de46075fe68dfc4449f`.
The owned emulator did not satisfy the existing boot-property check within 300
seconds. The original unsigned APK stayed unchanged; all four cases are `not_run`.
No guest-readiness, installation or launch observation was reached. The receipt's
`installed_apk_sha256` identifies the separately prepared temporary signed copy;
creating that copy does not demonstrate installation.

The retained installed artifact `12128991656` has verified ZIP SHA-256
`cf69bf5582807969431551ff8e3291dec2a9721d64be5e0063dc49e16aa8e01d`.
Its emulator log records graphics initialization, an increase to 2560 MB guest
memory and a reduction to one virtual CPU. Those observations do not establish
the reason for the boot failure. The old poll discarded every ADB response, and
the retained log had no guest kernel trace. It cannot distinguish a stalled boot
from an unavailable ADB transport or a rejected boot-property observation.

The initial diagnostic follow-up preserved the selected emulator, image, graphics,
CPU arguments and 300-second HVF boot limit. `boot_wait` records the attempt count,
elapsed time, first and last probe and at most 16 recent probes. Each result keeps
its exit code, separate stdout/stderr tails of at most 1200 characters and a
truncation indication. A command timeout retains bounded partial streams and
still fails immediately; an owned-process failure also remains the original
failure. Success uses the complete command result and the same boot-property
predicate, and must arrive before the deadline. Every command and sleep is capped
by the remaining budget. These observations do not replace the subsequent full
guest-readiness or installed-case gates.

After a failed boot, `boot_failure_observations` records only the owned serial's
ADB state, reported AVD name, emulator property and boot-animation property. These
read-only queries have a separate 20-second total budget and at most five seconds
per command. Missing, failed or timed-out diagnostics are retained and cannot
rescue the failed boot or replace its error. They perform no reconnect, reset,
authorization, permission change or shared-device selection.

The owned emulator also receives the documented
[`-show-kernel` option](https://developer.android.com/studio/run/emulator-commandline#common),
which exposes guest kernel messages for boot diagnosis in the existing bounded
emulator log. The retained log still contains at most its last 1 MiB. No kernel,
system image, renderer or hypervisor configuration is replaced by this option.

Seven additional host tests verify transport-failure retention, successful and
late boot results, deadlines, bounded history/streams, original timeout/process
errors and read-only diagnostic failure handling. That reviewed diagnostic
checkpoint, commit `7f01a523427a62bf6d8f077c6ddf4bcb2ee48aee`, tree
`b370992f6a42156b3c151f36e0c62d4d441feae4`, passed all 43 host guards locally.
This is a diagnostic source correction, not a demonstrated boot repair; an actual
fresh run and its receipt are still required before changing the failure's attribution.

### Retired all-macOS build and combined guard checks

A separate all-macOS build experiment at candidate
`899e917842bc62aedd9040c9622c87d17e12f1cf` used actual checkout
`498a439ece0b0a2b7bba3923091bd2467653a107` in
[run 39107708484](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39107708484).
Core passed, but native job `117617045242` failed at
`:preview:processDebugResources`: strict dependency verification rejected the
newly required `com.android.tools.build:aapt2:8.9.2-12782657` artifact
`aapt2-8.9.2-12782657-osx.jar`. The job failed before Activity simulations or
emulator preparation and uploaded no unsigned-preview or installed artifact.
This host-specific build dependency failure is separate from the earlier HVF
boot failure; neither is an installed success.

That all-macOS build direction is retired. The combined source retains the
successful Linux build boundary, same-run single-artifact transfer and Intel
consumer from the reviewed port. No dependency verification setting or checksum
is relaxed. The combined source also preserves useful stricter host guards from
the retired experiment, using one receipt representation:

- Before a host query, bind the selected `emulation` settings and host observation
  dictionary into the receipt. Require the actual hosted Intel platform and an
  existing regular, non-symlink emulator before attempting the host preflight.
- Retain the read-only sysctl result in `emulation.host.support_check`, with
  stdout/stderr limited to 1024 characters each. Retain the emulator query in
  `host_acceleration`, limited to 2048 characters per stream. Timeouts and other
  exceptions retain bounded partial evidence and rethrow the original error;
  failed preflights also print their bounded observations. The command budgets
  remain ten seconds for sysctl and thirty seconds for the acceleration query.
- Require successful integer exit codes, empty stderr, explicitly untruncated
  complete observations and no timeout/error fields before accepting capability.
  The complete four-line HVF output requires numeric dotted version components;
  malformed strings such as `15..7` cannot pass. The separate exact emulator
  version pin and producer APK hash remain mandatory.
- Validate the explicit mode, acceleration argument, boot budget and unsupported
  opt-in together even when the receipt validator's caller omits `expected_mode`.
  A supplied expected mode must additionally match. Remove any inherited
  `ANDROID_I_WANT_MY_TCG` from a hardware child; only explicit software mode adds
  the existing unsupported opt-in to a fresh child environment.

Five additional focused host tests and expanded negative cases bring this local
combined source to 48 passing guards. They exercise missing/directory/symlink
emulator paths without running them, receipt binding before failed queries,
bounded partial output, original error identity, malformed capability output,
inconsistent mode metadata and inherited software opt-in. The reviewed boot
poll/diagnostics, guest readiness, all four installed cases and cleanup semantics
are unchanged. Its first actual installed run is recorded below.

### Observed cold startup and one bounded HVF budget experiment

The combined-source [run 39110300905](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39110300905)
checked out `ec524074ca7a8e6c6028382bd7cb93a08de45ff8`, exact reviewed tree
`38b136452b8b815c9ebd4b4d2e0f81efe25ac30e`, with parents `899e9178` and `264eaaca`.
Core and native jobs passed, including all 48 host guards. Independently downloaded
reports again contain 260 core and 29 simulated Activity cases with zero failures,
errors or skips on that older canonical baseline. The producer APK retained SHA-256
`c3e99c4783b5b71da2df1d6e665c9f6129509f5978f40de46075fe68dfc4449f`.

Installed artifact `12130288364` has verified ZIP SHA-256
`aa1c536d52d4e0e979de04c20cf35964ff41777f15f8be0e3a293d4fc57c96f7`.
Its receipt records failed boot after 300.052 seconds and 229 observations. Every
retained late probe returned exit 0, empty stderr and an empty boot property; the
four final same-serial diagnostics completed in 0.803 seconds and confirmed the
responsive owned AVD. They do not establish framework readiness. The boot animation
being stopped is also insufficient because this lane explicitly disables it.

The kernel trace identifies a complete cold startup and the emulator's existing
reduction to one virtual CPU on this host. Zygote starts at guest time 90.85 seconds,
SurfaceFlinger at 105.34, and system_server PID 600 registers services from 162.68
through the later artd/idmap2d activity around 198 seconds. The idmap2d termination
follows an explicit system_server stop request. Later healthd heartbeats at 217.03
and 277.10 seconds do not prove continued framework progress. This kernel trace
records no demonstrated cause for the missing boot-complete property; no framework
logcat was retained to distinguish slow initialization from a userspace stall.
Guest timestamps are not a measured conversion to elapsed host time. All four
installed cases remain `not_run`, with no guest readiness, APK install or launch.

The next reviewed experiment changed only the explicit HVF cold-boot limit from 300 to 600
seconds. KVM remains 300 seconds and software mode 900; the receipt validator must
match the exact budget for its selected mode. This single bounded increase tested
whether the observed late cold startup could finish. It was not a verified repair or
a performance qualification, and a failure does not authorize further
budget increases without diagnosing the retained evidence.

The installed job retains its 25-minute limit. The 600-second boot allocation,
unchanged 180-second readiness limit, 60-second installation command, 20-second
boot-failure diagnostics and at most 25 seconds of process cleanup leave 615
seconds for setup, other guest observations, the unchanged UI cases and artifact
upload. This is scheduling room, not evidence that every case will finish; the
observed preceding job spent 58 seconds before entering the harness. Every boot
result must still satisfy the complete original predicate before its absolute
deadline, followed by all nine readiness checks and all four actual installed
cases. The host, binary/image/APK pins, graphics/CPU arguments, command limits,
failure handling, cleanup and artifact handoff remain unchanged. If a subsequent
run fails at boot, its original failure must be preserved while a separately reviewed
read-only framework diagnostic identifies the wait before another configuration
change. The expanded existing host tests reject the old 300-second and software
900-second budgets for HVF, and reject 600 seconds for KVM or software receipts.

### Observed HVF launch and first-capture failure

The [600-second-budget run 39112194859](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39112194859)
checked out `159cf4c6a1bd6dcde1c4863c74e2c8c319bf8e13`, exact reviewed tree
`eecf3179270b81726ed3c3395aa5d7e0777c6102`, with parents `899e9178` and `91341f85`.
Core and native jobs passed, including all 48 host guards. Downloaded reports contain
260 core and 29 simulated Activity cases with zero failures, errors or skips on
that older canonical baseline. The exact producer APK again has SHA-256
`c3e99c4783b5b71da2df1d6e665c9f6129509f5978f40de46075fe68dfc4449f`.
Installed artifact `12131331327` has verified ZIP SHA-256
`7b59904e3473df26a4bde3c74e3b2dc550b476d4c27721ed4edab0e23711d33f`.

Boot completed after 281.918 seconds and 230 probes. That is within the preceding
300-second limit too, so this result does not establish that the larger budget
caused success. All nine readiness checks passed together on attempt four after
10.155 seconds; the first full observation had reported user 0 still locked.
Installation and the exact preview Activity launch succeeded. The launch command
returned exit 0, empty stderr, `Status: ok` and `LaunchState: COLD` after 14.415
seconds. These observations establish one actual HVF boot, readiness, install and
launch, without establishing repeatability or completion of any installed case.

The first case then failed after 24.363 seconds with `ParseError: syntax error:
line 1, column 0`. The receipt and JUnit agree on one failed case and three
`not_run` cases. At that source revision, reaching the parser implies an admitted
dump result and a successful `exec-out cat`, but their original streams were not
retained. Their missing contents prevent a diagnosis of that first XML input.
The later failure checkpoint is a distinct successful capture: its 7,782-byte XML
contains 20 nodes from the preview package, and its screenshot shows the synthetic
draft. It cannot replace the failed first capture. These artifacts do not establish
a selector problem, truncation, renderer fault or the original malformed text.

### Bounded UI capture observations and complete dump results

Each snapshot now records its sequence, exact dump path, current stage and elapsed
time in `ui_hierarchy_observations`. Removal, dump and read have separate command
arguments, elapsed times and returned exit codes or original timeout/process
errors. Their existing command limits remain 30, 20 and 30 seconds respectively.
Each available stdout/stderr observation retains its decoded character count,
SHA-256 of the UTF-8 encoded decoded text, first 1,200 characters, last 1,200
characters and whether those retained edges omit a middle section. Byte streams
from a timeout are decoded with replacement and explicitly identified; the digest
is text-derived, not a claim about the original subprocess bytes. These are bounds
on retained receipt text, not on the existing `capture_output` memory allocation.

The receipt retains the latest eight attempts and the first failed attempt
separately, so a later successful failure checkpoint cannot overwrite the failed
input. Parser failures include the original exception type, message, code and
position. Observation does not alter or suppress that exception. No retry, XML
prefix removal or other input repair is performed: the complete returned read
stdout goes directly to the same XML parser and hierarchy predicate.

Both an ordinary dump exit 0 and the existing narrowly accepted exit 137 now
require the complete expected-path `UI hierchary dumped to:` message and empty
stderr. An exit alone cannot authorize the read. Prior-file removal and the fresh
read must each succeed; the read must also have no nonwhitespace stderr before
parsing. Valid-looking XML cannot override a read-command diagnostic. This stronger
read check is not a diagnosis of the missing first input in the preceding run.
The exact marker still cannot replace valid hierarchy XML
with a recognized rotation and nodes. The existing dump counters retain their
meaning, including no completed dump or exit-137 completion until XML passes.

Host regressions exercise zero-exit failures, wrong or contaminated completion
messages, nonzero removal/read or read stderr despite XML-like output, retained malformed parser
input, later successful checkpoints, bounded history and timeout streams, and
preservation of the original timeout/process/parser exceptions. All 52 host tests
pass locally. These tests do not run a device or establish an installed repair.
The emulator/image/APK selection, boot and readiness requirements, launch check,
UI selectors, all four installed case bodies and cleanup are unchanged. An actual
changed-source run must still execute and pass all four cases before the installed
lane can be accepted.

### Observed framework launch timeout and separate empty dump exit

The [changed capture-source run 39114550191](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39114550191)
checked out `b62bbca8a7a54313d00e4c9a3a42ee28c6a9f8d3`, exact reviewed tree
`34c15fdd0b5258e01db0e14011fba3a6e77d6162`, with parents `899e9178` and `b6ceb598`.
Core and native jobs passed, including all 52 host guards; downloaded reports
contain 260 core and 29 simulated Activity cases with no failures, errors or skips
on that older canonical baseline. Installed artifact `12132028568` has verified
ZIP SHA-256 `56273d6c16e93ab4e028c1edccd741ae84028843824d51267c4659d2a3cc9602`.
Its exact unsigned APK still matches
`c3e99c4783b5b71da2df1d6e665c9f6129509f5978f40de46075fe68dfc4449f`.

Boot completed after 343.879 seconds and 272 probes. All nine readiness checks
passed together after 9.628 seconds, and installation proceeded. The original
failure is the subsequent Activity launch: Android returned exit 0 and empty
stderr, but reported `Status: timeout`, `LaunchState: UNKNOWN (-1)` and
`WaitTime: 18546` after 18.819 seconds. The unchanged launch predicate rejected
that response. The first case failed; the remaining three are `not_run`.

The separate failure checkpoint removed the old dump file successfully, then
received dump exit 137 after 7.585 seconds with both stdout and stderr exactly
empty. The new observations retain that complete result. The completion marker
was absent, so no read or parser step ran and no XML or screenshot was accepted.
This checkpoint failure did not replace the original launch timeout. It is a
different boundary from the discarded first XML input in run 39112194859.

The retained framework log shows process/provider startup delays and a contacts
provider explicitly killed for timing out while publishing providers. It does not
identify a memory kill. The kernel log has no explicit OOM/LMKD kill record;
unrelated service-cleanup signal 9 entries do not establish why the dump exited
137. No retained process binding establishes an app crash or an app ANR. The
emulator also reports overriding the requested two cores to one and increasing
guest RAM from 2048 to 2560 MB, but those observations do not establish a CPU,
RAM, renderer or image correction. Both commands returned before their existing
Python deadlines. The original logcat is collected before the failure checkpoint,
so that log alone cannot explain its later dump exit.

### Bounded state after a failed case checkpoint

After preserving the initial `failure-logcat.txt` and attempting the existing
failure checkpoint, the harness now retains a separate
`case_failure_observations` section in the receipt. It identifies the exact owned
serial, preview package and `after_failure_checkpoint` phase. It attempts six
read-only observations in order: a later filtered framework/event log, the package
PID, package exit history, server-side Activity stacks, package process state and
low-memory-killer statistics. These are observations, not additional case
acceptance predicates. A missing PID or empty exit history cannot prove that a
process never ran or never failed, and unrelated process events need an explicit
package/PID binding before attribution.

All six calls share one 20-second diagnostic deadline; each ADB call receives at
most five seconds and is clamped to the remaining time. Each `dumpsys` also uses
its documented three-second service timeout. Commands not attempted before the
deadline are named in the receipt. Each attempted command retains its arguments,
start offset, elapsed time, exit code or timeout/error, and the same bounded,
explicitly decoded stream observations used for UI capture. The receipt also
retains at most 131,072 characters of the later log and 32,768 characters of each
other stdout, with separate truncation flags. Partial timeout text remains marked
as a timeout. These limits bound retained text, not subprocess capture memory.

The later log requests a 2,000-record tail from the main, system, crash and
events buffers, using an explicit allowlist for Activity/process lifecycle,
Android runtime errors and LMKD events with `*:S` for other tags. Activity stacks
and LMK statistics describe this fresh owned guest; PID, exit-history and process
queries name the exact package. The Activity-stack query omits client-side dump
flags. The [Android dumpsys documentation](https://developer.android.com/tools/dumpsys)
defines the service timeout, and the
[Android 15 ActivityManager dump dispatch](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/services/core/java/com/android/server/am/ActivityManagerService.java)
defines the read-only `activities`, `processes`, `exit-info` and `lmk` queries.
The [logcat documentation](https://developer.android.com/tools/logcat) describes
the buffers and tag/priority filtering. These source references establish command
syntax, not a diagnosis of the observed guest failure.

Host regressions check finite deadline clamping, omitted observations, partial
timeout text, bounded retention, process errors and the actual exception-handler
ordering. They verify that the initial log and original failed case survive both
checkpoint and diagnostic failures. All 58 host tests pass locally; no host test
executes a device. The new evidence collection does not retry launch or capture,
change any of the four case bodies, raise their command limits, or change the
boot/readiness/image/renderer requirements. The installed job remains 25 minutes,
including this bounded failure-only collection and owned-process cleanup. A
reviewed changed-source run must still pass all four actual installed cases.

### Observed delayed draw, System UI ANR focus and automation connection timeout

The [runtime-state run 39117620126](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39117620126)
checked out `58ebfef09560330d8d5c14484b817bd05478fda7`, reviewed tree
`c33c689d464607e0f691028ab159fd3addefbd46`, with parents `899e9178` and `83bb5c14`.
This run includes the accepted main `35f5a602` application and test composition;
later main changes are separate. Its actual reports contain 400 core cases,
81 simulated native cases and 58 host guards, all passing without skipped cases.
The independent runtime-inventory artifact also passed the ignored-input negative
guard and binds its source pins and output hashes to that checkout and tree.
The exact unsigned APK is
`fac6a57d407c21643f5aaecd373aff42c2f3f7566f375d9bcfbef335639f9ec1`.
Installed artifact `12133206782` has verified ZIP SHA-256
`6ac9f691045a423df2d980bed6b6249dff06dacb140d841be0881354df0a81c4`.

Boot completed after 279.746 seconds and 228 probes; all nine user/storage checks
passed after 8.983 seconds. The first installed case then failed at the unchanged
launch predicate: the command returned exit 0 and empty stderr after 17.388
seconds, but reported `Status: timeout`, `LaunchState: UNKNOWN (-1)` and
`WaitTime: 16774`. The later checkpoint's dump returned 137 after 6.818 seconds
with both streams empty, so no read, XML parse or screenshot ran. The first case
remains failed and the other three remain `not_run`.

All six new post-checkpoint diagnostic commands completed successfully in a total
1.225 seconds, with no retained stdout truncation. The complete filtered framework
tail reports first draw for `ai.pickyour.android.preview/.PreviewActivity` at
10:13:30.975 UTC, with Android's `Displayed` duration of 18.642 seconds. The later
Activity state binds live PID 2088 to the exact preview package: it is resumed,
reports its first window drawn and all windows drawn, and remains a foreground
process. However, the actual current and focused window is
`Application Not Responding: com.android.systemui`. A drawn app beneath that
system dialog does not retroactively pass the failed launch or prove usable UI.

The same tail now identifies the automation failure: at 10:13:36.357, PID 2207
throws a fatal `TimeoutException` while `UiAutomation.connectWithTimeout` is
called through `UiAutomationShellWrapper.connect` and `DumpCommand.run`. Crash
reporting then fails with a bad file descriptor. The
[Android 15 UiAutomation source](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/app/UiAutomation.java)
uses an internal five-second connection wait. The
[same-tag RuntimeInit handler](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/com/android/internal/os/RuntimeInit.java)
finally kills its own process even if reporting the fatal exception fails. These
source mechanisms are consistent with the captured dump exit; they do not prove
an exact source-hash match for the installed system image. Increasing the outer
20-second dump limit would not change that internal connection limit.

The log also records a separate `com.android.phone` startup ANR and an explicit
`bg anr` kill. Its diagnostic sample reports CPU pressure `some avg10=99.87`,
I/O pressure `some avg10=41.91`, memory pressure `some avg10=0.03`, and a separate
CPU-usage sample of 100% total, including 86% kernel time. Kernel
[pressure-stall documentation](https://docs.kernel.org/accounting/psi.html)
defines `some` as the share of time when some tasks stall on a resource; that
metric is distinct from CPU utilization. The guest kernel confirms one activated
processor. LMK statistics report zero kills, and the package exit-history query
contains no exit record. These observations support delayed interactive-framework
availability under severe CPU/I/O pressure; they do not establish an app crash,
an OOM kill, a particular Binder defect, or a sufficient CPU/image/renderer change.

### One bounded framework synchronization and functional home check

After all nine user/storage observations pass, readiness now performs one
pre-installation sequence within the **same 180-second total deadline**. It first
invokes `am wait-for-broadcast-barrier --flush-application-threads`, with an outer
limit of 120 seconds further clamped to the remaining shared deadline. There is
no retry of this command. Its complete, bounded stdout must contain the ordered
positive completion lines `Test barrier passed` and
`Finished application barriers!`, with exit 0 and empty stderr. The explicitly
recognized pending-queue and application-barrier progress lines may precede
completion. Missing, duplicated, unexpected, truncated or timed-out output fails;
in particular, a zero exit with `Gave up waiting for application barriers!` fails.

The [Android 15 shell dispatch](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/services/core/java/com/android/server/am/ActivityManagerShellCommand.java),
[ActivityManager implementation](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/services/core/java/com/android/server/am/ActivityManagerService.java)
and [broadcast queue](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/services/core/java/com/android/server/am/BroadcastQueueModernImpl.java)
define this synchronization. It prioritizes pending queues and temporarily
unfreezes processes while pinging application threads, so it is not described as
a read-only observation. The broadcast barrier covers its captured queue boundary;
the application barrier covers a momentary process set and even counts a remote
exception as a returned ping. These limits make independent functional UI
observation necessary. The queue wait has no internal deadline, and ending a
timed-out shell does not guarantee cancellation of server work; a failed setup
still terminates only this job's owned emulator through its existing cleanup.

After the barrier, exactly one fresh home hierarchy must pass the existing prior
file removal, exact dump completion, clean read and XML requirements. The same
30/20/30-second command maxima are clamped to the remaining readiness deadline.
No application is launched to warm it up. The hierarchy must be in the configured
portrait rotation, contain a launcher node with positive visible bounds, and
contain only launcher or System UI packages. A final server-side Activity query
must report both current focus and input focus on user 0's exact
`com.android.launcher3.uioverrides.QuickstepLauncher` window. Missing, ambiguous,
wrong-user or ANR/dialog focus fails even if a valid-looking hierarchy exists.
The focus result also rejects an in-band `DUMP TIMEOUT` or service-error message;
the [native dumpsys implementation](https://android.googlesource.com/platform/frameworks/native/+/77e84a09ead77fdf0dd4b08704ab00970a4d3da6/cmds/dumpsys/dumpsys.cpp)
can write its timeout banner into stdout, so empty stderr is not enough.
Nothing dismisses a system dialog, taps the home UI, changes an image or renderer,
or accepts the preview's previous failed launch.

The receipt keeps this sequence under `guest_readiness.interactive`, with its
pre-installation phase, owned serial, exact command arguments, elapsed/budget
values, decoded stream observations, complete-result truncation flags and the
home hierarchy's package/bounds summary bound to the actual read-text SHA-256.
It retains at most 16,384 characters of barrier stdout and 65,536 of focus stdout;
partial results remain failed. The separate home snapshot counters cannot replace
any installed-case snapshot. The final receipt validator requires the complete
framework/home evidence in addition to every existing user/storage and case gate.
On readiness failure the existing six bounded runtime observations are retained
under `guest_readiness_failure_observations`, explicitly before any app case;
diagnostic failure preserves the original setup exception and all four `not_run`
rows. These are retained-text limits, not subprocess capture-memory limits.

All 69 host guards pass locally, including deadline-boundary results, exact
completion versus terminal failure, partial timeout output, the observed empty
137 dump, ANR focus despite valid home XML, wrong or invisible home nodes,
incomplete receipt evidence and original-error preservation. The ordinary
installed snapshot calls retain their existing command maxima, acceptance checks
and failure behavior. Boot limits, launch status, all four case bodies, selected
image/emulator/graphics, APK handoff and owned-process cleanup are unchanged.
This is one measurable startup-synchronization experiment, with no passing
installed result yet. A new exact-source hosted run must still pass all four
installed cases before the lane can be accepted.

### Observed pre-installation SystemUI ANR and retained global ANR records

Changed-source [run `39120315926`](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39120315926)
tested checkout `54028ba3ddb7d3d569a01ff87b7d4d971b483685`, exact reviewed tree
`ed57cdf3c7789fd1369d3b4c16ef49fe3eca1a48`, with ordered `899e9178` and
`50fad459` parents. The Linux artifacts contain 406 actual core cases and all 121
required simulated native cases, with zero failures, errors or skips. The hosted
69-method harness guard suite also passed. The producer and separate runtime
inventory agree on unsigned APK SHA-256
`e7527c350d50d92f4b3a29cee4fa5b9086a4da83fb1e6c910cf67546f2594f2b`.
These producer results do not establish installed behavior.

Installed artifact `12134710907` is 71,163 ZIP bytes with independently verified
SHA-256 `58b0b70eac14ea04b7f0db986376d5349603dc10eaccc6562503bbb1549e9d24`.
Boot completed in 307.206 seconds after 251 probes. The fourth user/storage
readiness snapshot passed all nine existing predicates. The subsequent framework
barrier returned a complete, clean exit-zero result in 48.720 seconds, including
both ordered positive markers. Fresh hierarchy removal, dump and read also
succeeded: the dump returned zero and its exact completion line in 4.237 seconds,
and the clean read completed in 0.051 seconds. Its XML parsed successfully.

That functional hierarchy contained only `android` nodes and no visible launcher
node. Its retained text includes the system dialog's **Close app** and **Wait**
controls. Both complete focus fields identify
`Application Not Responding: com.android.systemui`. The launcher was the resumed
Activity behind that dialog, which does not satisfy the required HOME window
focus. The readiness phase correctly failed after 61.503 seconds, within its
180-second limit; this was a failed functional predicate, not budget exhaustion.
The preview was not installed or launched, and all four installed cases remained
`not_run`. The original unsigned APK was unchanged. A signed disposable APK file
in preparation is not evidence of an installation.

The six later observations finished in 1.929 seconds. Their filtered framework
tail begins only at 10:59:00 and records a phone-process restart, launcher draw
after 21.290 seconds, a Bluetooth-process death/restart and the application-barrier
completion at 10:59:13.471. The SystemUI ANR's earlier onset, reason and thread
stacks are absent from that tail. The captured LMK count is zero, and no preview
PID is present before installation. The emulator again records its fewer-than-six
host-logical-core heuristic selecting one virtual CPU; the kernel confirms one
CPU. None of these facts identifies a CPU, renderer, image or memory correction.
The successful barrier/dump also does not show that this source cured the earlier
UiAutomation failure in a different run.

The narrow follow-up reads `dumpsys -t 3 activity lastanr` and
`dumpsys -t 3 activity lastanr-traces` after a guest-readiness failure, immediately
after the existing framework-log observation. The
[Android 15 ActivityTaskManagerService implementation](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/services/core/java/com/android/server/wm/ActivityTaskManagerService.java)
prints its saved ANR time/reason/state and the most recently modified existing
file in `/data/anr`, respectively. These commands do not generate a new trace,
send a process signal, dismiss a dialog or start the preview. This is a primary
source reference, not a reconstruction of the exact SDK image's source hash.

The two observations are **latest global records**. Their process identities and
times must be read before attributing either to SystemUI; the saved state and
latest trace can also describe different events. Missing records, command
timeouts, partial output and read errors remain explicit observations. Each ANR
result retains at most 131,072 decoded characters from its beginning and 131,072
from its end, with separate limits/truncation flags plus the existing full-text
character count and decoded-text SHA-256. Keeping the beginning preserves the
available identity and initial stacks when a long trace's tail alone would lose
them. These are retention limits, not a bound on subprocess capture memory or a
claim of a complete trace.

All eight readiness-failure reads share the existing 20-second total deadline,
at most five seconds per subprocess and three seconds for each dumpsys service
request. Unattempted reads are listed if the budget expires. The six ordinary
post-case observations retain their exact previous order and arguments. The
71-method host guard suite covers the new command order, bounded start/end,
timeout partials, process errors, shared deadline and preservation of the
original failure and unrun cases. No readiness predicate, case, launch, cleanup,
image, renderer, CPU setting or budget changes; a new actual result is required.

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

The original setup observed only `sys.boot_completed` before continuing. The
user/storage portion of setup now also requires a complete read-only snapshot
after the existing wake and keyguard-dismissal commands, before the framework/home
sequence described above, installation or any application case:

| Observation | Required result on this pinned AOSP API 35 guest |
| --- | --- |
| `getprop sys.boot_completed` | Exact `1`, successful command, no stderr |
| `getprop init.svc.bootanim` | `stopped`, or absent for the existing `-no-boot-anim` launch |
| `am get-current-user` | Exact `0`; no user is created or switched |
| `am get-started-user-state 0` | Exact `RUNNING_UNLOCKED`; no unlock command or credential is supplied |
| `pm path android` | The pinned image's `package:/system/framework/framework-res.apk` |
| `test -d/-r/-w/-x /data/local/tmp` | All four commands succeed with empty output/stderr |

This snapshot and the later framework/home sequence share a single 180-second
deadline. Each of these nine read-only commands
gets at most ten seconds and never more than the remaining deadline. Incomplete
observations can be retried within that deadline; command timeouts, nonzero exits,
unexpected output, stderr and truncated diagnostic output cannot establish
readiness. The receipt retains the number of attempts, elapsed time, first
incomplete and last observations, including separate bounded output and command
timeout metadata, and the exact `ui_dump_path`. Missing, false or differently
scoped readiness is rejected by the final receipt validator even if its case
rows were marked passed.

These nine probes observe access; they do not create/delete a file, change guest
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

### Actual temp-directory readiness followed by failed software launch

The temp-directory follow-up
[run 39105283009](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39105283009)
used actual checkout `550e9afcabd398dcd9d70e8265cf1ab8d64269d0`, tree
`5b70bfba360cfe5749d574ba3ea6576a76ece896`. Core, strict unsigned assembly/lint
and all 29 pinned-base simulations passed. The owned software AVD booted in
464.816 seconds. All nine guest readiness observations passed together on
attempt 1 in 11.175 seconds, including unlocked user 0 and every
`/data/local/tmp` access test. APK installation proceeded.

Launch still returned exit 0 with `Status: timeout`, `LaunchState: UNKNOWN (-1)`
and `WaitTime: 23374` after 25.914 seconds. The failure checkpoint passed exact
prior-file removal but its UI dump timed out at 20 seconds. No hierarchy
completed; the first case failed and the other three were `not_run`. The original
unsigned APK stayed unchanged. Artifact `12128790765` has independently verified
ZIP SHA-256 `2472902025c672a59de0d9a9fd8b923eb5925311f9617d79958c460aa25a9233`.
This establishes the chosen directory's actual readiness in that software-mode
run; changing the XML staging path did not cure its observed launch timeout.
The later HVF receipt described above is separate and did not reach readiness or
installation. Both failures remain evidence when evaluating a new owned guest.
