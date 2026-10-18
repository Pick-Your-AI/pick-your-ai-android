# Installed synthetic emulator verification

The existing **Unsigned synthetic Android preview** CI job builds, lints and runs
the named Robolectric regressions on Linux. After that job succeeds, **Installed
synthetic Android preview (hosted KVM)** verifies the exact unsigned APK handoff and
runs the same smoke harness on an explicitly selected Ubuntu 24.04 runner. The
new provisioning proposal retains the original non-root UID, GID and groups,
and uses the existing privileged-tooling policy for one temporary named-user KVM
ACL and its exact restoration. It uses one fresh AOSP API 35 x86_64 AVD. The
explicit 720×1280/280-dpi startup profile from the preceding HVF experiment is
retained and bound across the workflow, unprivileged child and final receipts.
The earlier RunAs-group denial and Intel macOS failures remain recorded below. The workflow
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
current installed job explicitly selects `ubuntu-24.04` and requires observed
Ubuntu 24.04/Linux/x86_64 with hosted Linux/X64 identity. The explicit provisioning
boundary described below must pass before the unchanged read-only KVM access gate.
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
directory. The outer ACL policy preflight records real/effective UID/GID,
supplementary groups, the existing named `kvm` group, device identity/access and
exact original ACL before SDK provisioning. The same-user process repeats those
observations after the named-user grant and requires read/write access. The
harness's existing read-only access predicates run again before acceleration.
Missing devices, symbolic links, unexpected groups, denied policy or unavailable
access fail. The explicit temporary ACL is the only device-access mutation;
device ownership/mode and process credentials stay unchanged. Normal finally
cleanup and the always-run step require exact restoration. Unproved command
termination or a foreign device/ACL change fails without a racing or unrelated
restoration. Group membership, udev and sudoers configuration are not changed.

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
When `PYAI_EMULATOR_MODE` is absent, KVM mode remains the settings helper's default.
The historical KVM lane supplied its explicit mode, pinned binary path and exact
APK digest through the boundary; the preceding `9ffd` workflow explicitly selected
HVF. The current ACL/KVM candidate explicitly selects KVM and still requires its
read-only preflight and complete emulator
`-accel-check`, with the unchanged 300-second boot deadline. Unknown mode names
fail and hardware failure never switches modes.

The Linux emulator host also needs Ubuntu's `libpulse0` runtime library even with emulator audio
disabled. The previous preparation step installed that specific package and the receipt
recorded its installed version. The first [hosted attempt](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39098235903)
passed compilation/lint and all 11 simulated cases, then failed loading
`libpulse.so.0` during emulator version inspection. Its retained receipt correctly
marks all four installed cases unexecuted and the original APK unchanged. This
failure is setup evidence; a later passing installed receipt is still required.

### Observed Intel HVF capability and preceding raster lane

The read-only diagnostic [#244](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/244)
ran on the existing `macos-15-intel` label in
[run 39104147868](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39104147868).
Its observed hosted macOS 15.7.9/X64 environment returned `kern.hv_support=1` and
the preinstalled emulator 37.1.11.0 returned exit 0 with
`Hypervisor.Framework OS X Version 15.7`. All bounded observations were complete,
without stderr or timeout. That diagnostic was closed without merging its temporary
workflow. It establishes capability on that observed host only; it booted no AVD.

The preceding `9ffd` raster experiment explicitly selected `PYAI_EMULATOR_MODE=hvf`
and preserved its earlier 600-second boot deadline and 25-minute job budget. Its
preparation and harness checked the
hosted Intel platform and read-only `/usr/sbin/sysctl -n kern.hv_support` result.
The retained HVF mode also requires a complete successful `emulator -accel-check`
result identifying Hypervisor.Framework, uses `-accel on`, and records the actual
host and acceleration observations. Missing, failed, truncated, contradictory or
timed-out observations fail; neither KVM probing nor software fallback follows an
HVF failure. That preceding workflow pinned the preinstalled emulator to 37.1.11.0,
installed only the selected AOSP image through the existing SDK manager and
verified existing adb and build-tools 35.0.0. It changed no host permission, group,
driver, hypervisor or SDK emulator binary. Linux-specific `dpkg-query` was not used
on macOS. The current ACL/KVM candidate has the separate Linux archive and
explicit temporary device-access boundary described above.

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

At that diagnostic checkpoint, `boot_failure_observations` recorded only the owned serial's
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
Only the complete unavailable-root response described in the later KVM receipt
below permits another HOME observation, with at most three attempts and no new
deadline or barrier. Every failed predecessor remains in the receipt.
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

### Consistent command observations in the final receipt

The framework/home receipt validator binds each fully retained command stream to
its recorded length, SHA-256, prefix, suffix and truncation flags. Empty stderr
must have the complete empty-stream observation. The hierarchy read must explicitly
contain available, nonempty text with consistent retained edges. When those edges
cover the whole text, the validator reconstructs it and verifies the digest. Longer
hierarchies retain their declared full-text digest and checked edge metadata; their
omitted middle cannot be reconstructed from that digest. The existing successful
XML parse and hierarchy-summary binding remain required.

The only hierarchy attempt must identify sequence 1 and contain no failure record.
Two added host regression methods reproduced nine assertion failures against the
unchanged `f180a53228b3206445328685c5011ccfd77f921d` driver, covering contradictory
stream fields, unavailable read evidence and a failed or misidentified sole probe.
After this validator correction, all 71 existing methods and both new methods pass
locally under normal and optimized Python. Valid XML around both retained-edge
boundaries also passes. These are mocked host checks; installed execution remains
pending. The command sequence, shared readiness deadline, original nine checks,
four app cases, launch predicate, host configuration and budgets are unchanged.

### Observed global ANR trace and the terminal RunAs-group experiment

The actual [run 39123226538](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39123226538)
checked out `cf26f89c749373ccdb3976bcc7bfb48ddb976e72`, tree
`77bcced552386e2c496ac7f12816194519eae4d6`, carrying source
`f180a53228b3206445328685c5011ccfd77f921d`. Its installed artifact `12136125002`
is 76,384 ZIP bytes with independently verified SHA-256
`5f8853b84b7680385b1e85046424c6c6a74210b25f6153f36a5a6e8fec1f0079`.
The [consolidated receiving observation](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/255#issuecomment-6249661795)
also records 410 actual core and 128 simulated native tests. Those successful
producer results do not replace the installed failure.

The owned guest booted in 569.390 seconds. All nine original readiness checks
passed together, but the single framework barrier took 91.882 seconds and ended
with Android's application-barrier give-up after reaching 22/23 pings. It returned
zero with empty stderr, yet lacked `Finished application barriers!`; the strict
predicate correctly failed. The readiness phase took 100.643 seconds of its
180-second budget. HOME capture, installation and every installed case remained
unexecuted. The original unsigned APK stayed unchanged.

All eight bounded failure observations completed in 8.269 seconds. The latest
global trace belongs to **phone PID 2641**, with startup failure and a Runnable
main thread in provider/APN insertion during application binding. Its schedstat
fields report 2.081075 seconds of cumulative CPU time and 18.223912 seconds of
cumulative runqueue wait; they are not the duration of the SQLite call. A separate
critical-event entry identifies SystemUI PID 1333's startup ANR, and the later
activity state focuses the SystemUI ANR window. No SystemUI thread stack was retained,
and the one unfinished application ping was not identified. The saved-state cache's
`<no ANR has occurred since boot>` output cannot erase those separately retained events.

The two ANR samples report CPU pressure `some avg10=100.00`, I/O pressure
`some avg10=34.98` and `25.36`, and lower nonzero memory pressure. CPU pressure
measures stalled time, separately from the sampled 100% total CPU utilization.
The emulator warning and kernel records again establish **one effective guest
processor**, despite the existing request for two. LMK counters are zero. The
package-process query itself contains two 3000 ms dump timeouts; its zero exit does
not establish a complete process inventory. These observations support one bounded
host-capacity experiment; they do not identify an app defect, OOM kill, renderer
failure or a single proven cause of the SystemUI ANR.

The `89585ee` candidate selected the ordinary `ubuntu-24.04` GitHub-hosted VM,
retaining the same image, renderer, requested two cores, memory argument, guest
readiness predicates, exact launch/XML/case assertions and cleanup. KVM keeps its
existing 300-second boot limit and readiness keeps its shared 180-second deadline;
the complete job remains limited to 25 minutes. No timer increase, ANR dismissal,
software fallback or macOS core-clamp bypass is included. The receipt adds the
guest's actual `/sys/devices/system/cpu/online` value after boot, alongside the
existing kernel log; host CPU count is observed at the process boundary.

That source introduced an explicit CI credential boundary with independent source
and receiving review. The earlier [scoped review](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/206#pullrequestreview-5561485748)
correctly rejected silently changing `/dev/kvm` ownership. The new wrapper makes
the process selection explicit and retains that device/configuration restriction.
It does not operate on a shared machine or select a larger paid host.

1. Before SDK provisioning, record the nonzero real/effective runner UID/GID,
   supplementary groups and existing `/dev/kvm` inode, device number, owner, mode
   and access. Require a real character device owned by root whose positive GID
   equals the existing group named `kvm`. A missing group or mismatch fails.
2. Run one bounded `sudo --non-interactive --list --user=#UID --group=#GID --`
   check for the exact absolute system Python 3.12 interpreter, `-I`, absolute
   wrapper path and `--child` argument. A different command's permission is not
   substituted. Nonzero, diagnostic stderr, timeout or truncated listing fails.
3. Prepare the pinned Linux emulator and selected SDK image as the original
   non-root runner. The emulator archive comes only from the official Android
   repository, with SHA-256
   `95771e0ae431897b2a4bd2d97fa095f29a8b0624a7b216baf529f9306161c266`
   for `emulator-linux_x64-15917651.zip`. Verify the digest before extracting into
   a newly created directory under `RUNNER_TEMP`; reject escaping archive paths
   or links. No preceding or SDK emulator directory is replaced. The exact
   archive identity, byte count and extracted executable hash are recorded;
   only the verified temporary archive is removed after preparation.
4. Invoke that exact sudo command once, now with an explicit environment-name
   allowlist. Policy permission to list the command does not establish permission
   to preserve those variables: an actual invocation denial remains terminal.
   No `-E`, loader/Python controls, token, credential or alternate policy command
   is forwarded. Fixed SDK/Java/temporary/workspace paths, source/run/APK identities,
   hosted identity, mode and the derived tool path must arrive unchanged.
5. Inside the selected process, verify the same non-root real/effective UID,
   real/effective GID equal to the unchanged named device group, sudo's original
   UID/GID, actual read/write access and the same source/APK/tool identity. Record
   those observations before `execve` of the fixed absolute harness/interpreter.
   Its original access, acceleration, owned-device and installed-case gates still run.
6. After the child, record the original invoking identity, supplementary groups,
   device and access again. Any change, child failure, missing inner observation
   or final source/APK revalidation failure makes the persisted boundary receipt
   fail. A later observation error does not replace an earlier invocation failure.

The existing sudo policy decides whether this exact same-UID/named-group command
is permitted. The source does not repair denial through root execution, another
group, ACL, `chown`, `chmod`, `usermod`, udev or sudoers edits. Sudo may initialize
the child's supplementary groups from that user's existing membership; the
selected primary GID is checked separately. This is a credential selection, not
a claim that every step is read-only. Only the original `libpulse0` package setup
uses the runner's existing administrative package-install policy.

The official Linux archive is a different OS-specific binary from the Mac build,
even though both identify emulator 37.1.11.0/build15917651. The retained KVM source pins that
version/build and requires complete successful KVM acceleration output, empty
stderr and no timeout/truncation. It accepts the exact Linux example in the
Android documentation and the same form with the historical emulator-check
`accel` footer; arbitrary extra output fails. Neither form is claimed as an
observed result of that candidate. The Linux host, hypervisor, runtime library,
system Python patch version and emulator package differ, so even a future pass
would evaluate this host profile rather than isolate CPU count as a causal variable.

Primary references for this boundary: GitHub's [fresh standard runner and sudo
policy documentation](https://docs.github.com/en/actions/reference/runners/github-hosted-runners),
its [Linux Android acceleration announcement](https://github.blog/changelog/2024-04-02-github-actions-hardware-accelerated-android-virtualization-now-available/),
Ubuntu 24.04's [`sudo` options](https://manpages.ubuntu.com/manpages/noble/man8/sudo.8.html)
and [`sudoers` Runas rules](https://manpages.ubuntu.com/manpages/noble/man5/sudoers.5.html),
the official [emulator archive](https://developer.android.com/studio/emulator_archive),
the [runner image's actual SDK install inputs](https://github.com/actions/runner-images/blob/ubuntu24/20261012.334/images/ubuntu/toolsets/toolset-2404.json),
Android's [acceleration output](https://developer.android.com/studio/run/emulator-acceleration),
the historical [emulator-check formatter](https://android.googlesource.com/platform/external/qemu/+/ae9d18d2b6261179fbd57fffec720a04f7bfb053/android/emulator-check/main-emulator-check.cpp),
and Linux's [schedstat field definitions](https://docs.kernel.org/scheduler/sched-stats.html)
and [pressure definitions](https://docs.kernel.org/accounting/psi.html), plus the
[online CPU sysfs definition](https://docs.kernel.org/admin-guide/cputopology.html).

### Observed terminal command-policy denial

The actual [run 39131251455](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39131251455)
checked out signed merge `f85b9bde8e25bdbac8356b85bd180d94995ca55d`, exact tree
`031f625a2cf07cd30030257b028ae3bbb7ba317a`, carrying candidate
`89585ee44c04bd77d187a0b771b8ba3c87be4f40`. The
[consolidated, independently checked result](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/255#issuecomment-6250555264)
records **589 core cases, 161 simulated native cases and 100 host guards passing**.
All eight mandatory native admission scripts matched their actual reports. Lint
reported zero errors and 15 warnings. The unsigned APK was 2,746,845 bytes with
SHA-256 `08bb1116d0738b3bd4817d27d209c1b3f9e60f7e1872205eebb2039c13a679d6`.
The separate runtime inventory matched the same source, ten source pins and both
actual archive outputs. Those producer results remain separate from installation.

At 13:26:56 UTC, the exact command listing for non-root UID 1001 and existing
device GID 993 returned integer **1**, complete empty stdout and empty stderr.
The wrapper retained `status: policy_failed` and stopped before its child,
emulator download, SDK provisioning or guest execution. There is no installed
receipt, installed JUnit, APK installation, launch or case result for this run.
Its observed Ubuntu 24.04 host had four CPUs; the unchanged root:kvm character
device was mode 0660 and inaccessible to the original runner credentials.

The sole `kvm-boundary/receipt.json` member is 2,742 bytes with SHA-256
`14047b4cd6814c4f0e5e85e44ac9796389c50e5a6a797ac1b5cd8d4f90e0766b`.
Artifact `12137842592` is 1,275 ZIP bytes with SHA-256
`841e058627fe0e62e2d278a2c4f52bd0f87630ce36f3e8b9281b288542087637`.
That denial is terminal for the tested RunAs-group experiment. It identifies the
denied command/user/group tuple; it does not reveal an unobserved sudoers rule or
establish that every privileged-tooling command is prohibited.

### Separate proposal: temporary named-user KVM ACL

The next source proposes one explicitly reviewed provisioning experiment on the
same fresh standard `ubuntu-24.04` job. It deliberately grants temporary access
to the existing KVM device through a named ACL for the original non-root job UID.
This is a new project engineering decision under the owner's mandate, requiring
independent source and concrete-scope review before publication or execution.
It is not a retry, permission inference or automatic fallback from the denied
RunAs-group command above. `android_kvm_process.py` and its old receipt semantics
remain unchanged; the workflow selects `android_kvm_acl.py` explicitly.

The current proposal preserves the original process's real/effective UID, GID and
supplementary groups. Only the fixed system `setfacl` tool runs through the
runner's existing privileged-tooling sudo policy. Python, SDK tools, emulator,
adb and the unchanged harness retain the original non-root identity. There is no
root Python/wrapper, RunAs-group substitution, privileged kill, device ownership
change, world-writable udev rule, persistent group membership or sudoers change.
The previously observed host/device-access restrictions are not silently edited.

Before any SDK provisioning, the new wrapper checks the same exact source, APK,
host, interpreter and allowlisted environment. It verifies root-owned, unlinked,
non-writable absolute `/usr/bin/sudo`, `/usr/bin/getfacl` and `/usr/bin/setfacl`,
including their directory ancestry, and records their hashes and ACL tool
versions. The observed image tag `ubuntu24/20261012.334` resolves to
`7e7dc1bcc0ef464e5e315a2b4c33609590c6cffe`; its official
[Ubuntu inventory](https://github.com/actions/runner-images/blob/7e7dc1bcc0ef464e5e315a2b4c33609590c6cffe/images/ubuntu/Ubuntu2404-Readme.md)
lists `acl 2.3.2-1build1.1`. This establishes an image input, not a claim that
getfacl or setfacl ran in the failed experiment. The new runtime must still find
both exact tools and confirm their `2.3.2` output.

The only admitted original ACL is the numeric three-entry base:

```text
user::rw-
group::rw-
other::---
```

The target must also be the same nonsymlink character device, owned by root with
the existing positive numeric group named `kvm`, mode 0660, and no original
runner read/write access. An existing named entry, mask, different permissions,
linked/replaced device or changed credential fails before a privileged mutation.
The normal numeric `getfacl --access --omit-header --no-effective --numeric
--absolute-names -- /dev/kvm` read is unprivileged. Its base output alone is not
proof of filesystem ACL support; the actual grant and exact readback must prove
that support.

Both exact commands must pass separate bounded `sudo --non-interactive
--user=#0 --list --` checks before provisioning. The grant uses fixed arguments
`/usr/bin/setfacl --no-mask --modify=user:UID:rw-,mask::rw- -- /dev/kvm`.
Restoration uses `/usr/bin/setfacl --no-mask
--set=user::rw-,group::rw-,other::--- -- /dev/kvm`. The UID comes only from the
validated original numeric non-root identity. `--no-mask` prevents implicit mask
recalculation. The explicit rw mask preserves the existing group rights and mode
0660 while allowing only that named user additional read/write access. Exact
ACL readback is mandatory: **mode 0660 can look the same with or without the
named grant**. The proposal does not use `--restore`, whose input comments can
also request owner/group and special-bit changes.

A denied, timed-out, diagnostic or truncated policy listing fails without an
alternate command. Policy listing does not guarantee a later grant or restore
will succeed. The actual tool invocations retain the same fixed arguments and a
minimal PATH/locale environment; they do not preserve tokens, loader/Python
controls, `POSIXLY_CORRECT` or arbitrary inherited variables. The unchanged,
checksum-pinned Linux emulator preparation happens only after both policies and
the exact original state pass, and before any ACL grant.

#### Proving that a permission command has finished

Killing only Python's immediate sudo child after a timeout cannot prove that its
privileged command has stopped. Also, sudo can return a nonzero frontend error
without that status alone proving its descendant has ended. The proposal keeps
command success and command-subtree termination as separate observations.

`android_acl_lifecycle.py` creates a dedicated child with the same non-root
credentials, separate from every harness/ADB/emulator process. The child enables
only its own Linux `PR_SET_CHILD_SUBREAPER` attribute and confirms it using
`PR_GET_CHILD_SUBREAPER` before starting the fixed sudo/setfacl command. It uses
one owner of wait status: `waitpid(-1, WNOHANG | __WALL)` records the direct command
and any adopted orphan descendants. A zero nonblocking result means a child is
still alive. Only a final kernel `ECHILD`, after all recorded children are reaped,
provides positive absence evidence; an exit code of zero is insufficient.

The command gets a ten-second completion budget and at most two further seconds
to establish termination. On timeout/interruption the supervisor may send an
ordinary SIGTERM only to its unreaped direct child, whose PID cannot be reused
while this sole waiter still owns it. It issues no privileged kill and does not
assume that a denied signal or dead sudo frontend killed another process. The
parent allows 15 seconds for this dedicated supervisor. Only the waiter's own
deadline exception, raised after a non-reaping wait result, permits SIGTERM to
that still-owned child and one bounded three-second join. A generic timeout or
asynchronous interruption may occur after the child was reaped but before its
status reached the caller; its numeric PID may then be reused. Such failures
retain uncertainty and the original exception without sending any signal or
performing an additional wait. The parent never uses SIGKILL on the supervisor
to manufacture completion evidence.

If the supervisor dies, output is missing, wait status is lost, the kernel
setting/readback fails or a descendant remains at the deadline, the boundary
records failed/uncertain cleanup and **refuses every later device mutation**.
No automatic restoration can race a grant whose termination is unproved. An
ordinary failed or timed-out grant may already have changed the ACL; when its
whole subtree is positively reaped, the normal cleanup can safely examine and
restore the exact owned state. A hard job/process kill can prevent further
receipts or cleanup altogether. The fresh VM's disposal is the remaining
containment in that case; it is not recorded as successful ACL restoration.

The supervisor captures tool streams into owned temporary files and retains at
most 8,192 bytes from each stream, decoded with replacement for invalid UTF-8.
Truncation blocks success; these files are not a claim of a disk-usage limit.
Read-only metadata/policy queries retain bounded decoded tails from ordinary
subprocess capture; that retention bound is not a process-memory bound. The
subtree receipt separately records the direct command's status, bounded reaped
PID/status inventory, subreaper readback and final absence result.

#### Exact restoration and receiving evidence

Before the grant can start, the wrapper atomically writes and fsyncs its own
receipt checkpoint. It verifies the exact named-user ACL, unchanged device
inode/device number/owner/group/mode and original credentials after the grant
and again in the ordinary workload child. Only then can that child exec the
unchanged harness. Success still requires real KVM access and acceleration,
the exact source/APK/emulator identities, the pinned image, 300-second boot
budget, shared 180-second readiness deadline, both barrier markers, functional
HOME focus, strict first launch/XML assertions and all four actual cases.
Two access-failure messages now describe only the read-only check, so they cannot
erase the outer receipt's explicit ACL change. Their access predicates and all
installed behavior remain unchanged.

The wrapper's finally path restores only the same owned device with either the
exact original base ACL or exactly this job's named-user ACL. A replaced device
or unrelated ACL change is retained as a cleanup failure and is never overwritten.
Restoration also requires proof that every preceding permission-command subtree
has ended. It then replaces the ACL with the original three entries, removing
the named grant and mask, and requires full original identity/device/ACL/access
equality after the command has finished.

An `always()` workflow step independently reads the restored state, or allows
one bounded recovery of a recorded interrupted restoration whose preceding
command is proved quiescent. Recovery never turns the interrupted or failed
run into a pass. It retains the earlier failed attempt and original error;
another cleanup or receipt error cannot replace an earlier harness failure.
The final receiving gate requires both the complete `kvm-acl/receipt.json` and
the unchanged installed receipt. A failed cleanup, missing proof, stale context,
remaining named ACL or missing case fails the job. The full job remains limited
to 25 minutes; this limit does not guarantee cleanup after forced cancellation.

Host verification includes actual harmless Python process trees for delayed
orphans after exit 1, double-fork descendants, direct-child termination and a
self-limiting lingering orphan. Those tests use only the test runner's existing
identity and temporary files; they do not invoke the production ACL entry or
establish KVM access. Separate mocked boundary tests cover strict policy/device
admission, partial grants, original identity, foreign changes, exact cleanup,
interruption, missing subtree proof and receipt contradictions. No new installed
result or successful ACL change is claimed by this proposal.

Independent review found that the original local proposal could signal the
supervisor's numeric PID after a post-reap interruption. Two mocked negative
replays against that unchanged proposal each observed the forbidden SIGTERM.
The correction distinguishes the waiter's explicit unreaped deadline from an
unclassified failure, preserves the same original exception and failed proof,
and includes both post-reap and ordinary-timeout rejection plus the owned-deadline
path. These are local process-control tests, not a privileged command or device
operation; the complete ACL proposal still requires independent source and
receiving review before any hosted provisioning experiment.

Primary semantics: Ubuntu's [`setfacl`](https://manpages.ubuntu.com/manpages/noble/man1/setfacl.1.html),
[`getfacl`](https://manpages.ubuntu.com/manpages/noble/man1/getfacl.1.html) and
[`ACL`](https://manpages.ubuntu.com/manpages/noble/man5/acl.5.html) manuals;
the Linux [subreaper](https://man7.org/linux/man-pages/man2/PR_SET_CHILD_SUBREAPER.2const.html)
and [wait](https://man7.org/linux/man-pages/man2/wait.2.html) definitions;
and [sudo 1.9.15p5's exact executor sources](https://github.com/sudo-project/sudo/tree/fc9369cb442c8c5f6612d1831294569f1db75028/src).
The reviewed [Ubuntu ACL 2.3.2 source](https://archive.ubuntu.com/ubuntu/pool/main/a/acl/acl_2.3.2-1build1.1.dsc)
performs the fixed-file update synchronously
without spawning children. The supervisor still verifies the whole subtree
instead of using that implementation detail as a substitute for actual completion.

## Retained startup display experiment and its actual HVF failure

The independently reviewed `9ffd094819d282d10a5c3d14c1f0d9d0e63e6d07`
source selected one startup-display variation on Intel HVF. Its actual result is
recorded below and failed before installation. The earlier baseline remains
relevant: [run 39123226538](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39123226538)
at `f180a53228b3206445328685c5011ccfd77f921d` used synthetic checkout
`cf26f89c749373ccdb3976bcc7bfb48ddb976e72`, tree
`77bcced552386e2c496ac7f12816194519eae4d6`. Its artifact `12136125002` has
verified ZIP SHA-256 `5f8853b84b7680385b1e85046424c6c6a74210b25f6153f36a5a6e8fec1f0079`.
The guest booted in 569.39 seconds and passed all nine user/storage checks.
The following framework command returned zero but gave up at 22 of 23 application
barriers; setup correctly failed and all four app cases remained `not_run`.

The later diagnostic capture showed Launcher3 resumed while both current/focused
window fields named a SystemUI ANR dialog. This is a post-failure observation,
not the focus at the failed barrier. The retained latest full ANR trace describes
the phone process during startup, with a runnable main thread in APN database
initialization; its critical-event history also records earlier SystemUI startup
failure. It is not a complete SystemUI stack. The available framework samples
record sustained CPU contention and graphics activity. These observations support
testing a smaller raster workload; they establish neither a unique cause nor a
proven cure. No ANR is dismissed and no failing qualification is removed.

The workflow explicitly selects `PYAI_EMULATOR_DISPLAY_PROFILE=raster-720x1280-280`.
The harness default, `pixel-2`, preserves the original configuration bytes.
The original raster source admitted only explicit HVF mode. The combined ACL
candidate now admits that same profile under explicit HVF or KVM mode; software
and unknown modes still reject it. This source admission is not evidence that
the profile has booted or passed on KVM. Immediately
after creating its own fresh Pixel 2 AVD, before starting the emulator, the harness
changes only these three values in that AVD's `config.ini`:

| Startup property | Baseline | Experiment |
| --- | --- | --- |
| `hw.lcd.width` | 1080 | 720 |
| `hw.lcd.height` | 1920 | 1280 |
| `hw.lcd.density` | 420 | 280 |

Both full displays have the same exact logical width/height, `2880/7` by `5120/7`
dp. The experiment requests four ninths as many display pixels. This preserves
the logical phone dimensions, not identical rasterization or resource selection:
Android can select different density assets. Insets, keyboard behavior, usable
content bounds and app correctness still require observations. The existing
selectors derive taps from actual hierarchy bounds; all case bodies stay intact.

Missing, duplicate, changed or invalid baseline keys fail before launch. The edit
rejects linked/shared or unexpected configuration paths and retains before/after
hashes, byte counts, exact display values and a hash of every untouched line. After
boot it also reads the emulator-generated `hardware-qemu.ini` in the same owned
AVD and requires its three display values to match. The existing read-only
`wm size` and `wm density` observations keep their 30-second command limits, now
with complete bounded output and stream-consistency validation. They must report
exactly the selected physical dimensions and density; overrides, mismatches,
extra output, missing files, timeout, nonzero status or stderr fail. No postboot
`wm` size/density mutation is used. Configuration hashes identify retained inputs;
they do not establish renderer performance or a complete hardware qualification.

The final workflow receipt explicitly requires this selected profile and its
owned AVD identity, as well as every original gate and all four successful cases.
Historical receipts without this field cannot satisfy the selected experiment.
In the original `9ffd` raster experiment, the API 35 revision-2 image, emulator
version, Intel macOS/HVF host, renderer, requested two CPU cores/2048 MiB,
fresh boot/no snapshots, 600-second boot, 180-second shared readiness and
25-minute job limits were unchanged. The
baseline emulator itself reported one effective guest CPU and increased RAM to
2560 MiB; unchanged requests do not assert a future emulator's effective allocation.
Inspect its actual log when comparing the experiment. That original `9ffd` raster
experiment did not disable services, reuse a warm guest, change host permissions,
select a larger runner or extend timeouts. The current ACL/KVM candidate
deliberately changes device access through its temporary named ACL and retains
KVM's 300-second budget.

Ten added host methods cover the explicit selector, private-file boundaries,
unchanged baseline bytes, three-value edit/readback, generated hardware, exact
physical observations, timeout/error retention and final receipt binding. One
method reproduced 12 assertion failures against the unchanged receipt-corrected
`36093d912334c7bbcfa0bf2aea016cba4a6bf875` source; that source did not yet validate
startup-display claims. The isolated raster checkpoint at `8e757e36e2db16ec30bd7c33a4bdc803f1c7a2e4`
retains 73 inherited methods plus those ten additions; its normal and optimized
Python runs both passed. The original composition with source `89585ee` separately
preserved its 76 harness methods and 24 KVM boundary methods, and added the same
ten raster cases. All 110 host methods passed under both normal and optimized
Python, with zero failures, errors or skips. These synthetic file/mocked-device
tests do not start an emulator or establish startup, installed-case or performance
results.

Primary Android sources checked 2026-10-18: [AVD hardware properties](https://developer.android.com/studio/run/managing-avds#hardware-profile)
describe startup profile/configuration overrides; the official emulator
[configuration loader](https://android.googlesource.com/platform/external/qemu/+/refs/heads/emu-master-dev/android/emu/avd/src/android/avd/info.c)
reads `config.ini` after skin hardware values during startup, and the published
[hardware property definitions](https://android.googlesource.com/platform/prebuilts/android-emulator/+/refs/heads/main/darwin-x86_64/lib/hardware-properties.ini)
identify the pixel and density keys. These published source snapshots are mechanism
references, not a verified build identity for the pinned emulator 37.1.11.
The [CLI reference](https://developer.android.com/studio/run/emulator-commandline#deprecated-options)
lists `-skin` and `-dpi-device` as deprecated; this experiment uses neither.
Android's [density guidance](https://developer.android.com/training/multiscreen/screendensities#dp-pixels)
defines the dp/pixel relationship, and [`DENSITY_280`](https://developer.android.com/reference/android/util/DisplayMetrics#DENSITY_280)
documents this intermediate density. Actual startup and app results remain a
separate hosted experiment after independent source review and coordination with
the existing installed-lane owner.

### Actual raster boot failure and framework capture gap

The raster experiment's [run 39136743463, attempt 1 / installed job 117703746313](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39136743463/job/117703746313)
executed signed prepared checkout `1505ed0b69fcc72b75eab1e1d14d920a50ac3aa7`,
tree `fcf41c02ba51fa38748698dc8fbba654d5994147`, from source
`9ffd094819d282d10a5c3d14c1f0d9d0e63e6d07`. The original installed artifact
`12140024756` is 54,797 ZIP bytes, SHA-256
`a0bf12b012d9011a8d6b790d77146d784080b76ebf4895d06f494a4b7f9eb6d7`.
Its receipt, JUnit and emulator log were independently downloaded and inspected;
the [canonical actual-result review](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/255#pullrequestreview-5563604391)
also preserves the separate producer artifacts and successful host checks.

The actual Intel macOS/HVF host and immutable APK handoff passed their existing
checks. Boot completion was not observed: the receipt records 400 probes over
600.019 seconds, `completed: false`, with the final command capped at the
remaining 2.023928962 seconds and then timing out. Recent complete probes returned
an empty boot property. The four post-failure queries observed ADB `device`, the
exact owned AVD name, emulator property `1` and stopped boot animation in 1.517
seconds. None of those observations replaces `sys.boot_completed=1`.

All four installed cases remain `not_run`; JUnit contains one environment failure
and four skipped cases. The script did not reach guest readiness, installation or
launch. The separately prepared signed-copy hash is not installation evidence.
The edited startup configuration and emulator log show 720 by 1280 pixels at 280
dpi, but `startup_display.verified` is false: the later generated-hardware and
physical `wm` checks did not execute. The emulator reported increasing guest memory
to 2560 MiB and reducing the requested CPU count to one; the retained kernel log
also reports one initialized CPU. Slow startup messages and later service records
do not establish the boot failure's cause or a performance benefit or harm from
the raster change. The earlier KVM policy-denial receipt remains a separate result.

The original boot-failure path retained only the four property/device observations
above, so it supplied no framework logcat before cleanup. The bounded diagnostic
follow-up adds two observations under `boot_failure_observations.framework`:

| Observation | Read-only command after the same owned identity is confirmed | Retained stdout tail |
| --- | --- | --- |
| Existing framework log | `logcat -b main -b system -b crash -b events -d -v threadtime -t 2000 SystemServer:V SystemServerTiming:V *:I` | At most 131,072 decoded characters |
| Existing service names | `shell dumpsys -l` | At most 32,768 decoded characters |

Both commands use the same fixed owned serial. They run only if the original ADB
state, generated AVD name and emulator-property observations all have successful,
complete, untruncated results with empty stderr and match the existing target
validator. An incomplete, failed, noisy or foreign identity leaves both commands
explicitly unattempted. Each attempted command receives at most five seconds and
no more than the time remaining in the original **20-second total diagnostic
budget**, including all four earlier queries. Exhausted time is recorded; no new
deadline, reconnect, retry or fallback is introduced.

Each new observation retains exact arguments, command timeout and elapsed time,
integer exit status or timeout/error, and separate stdout/stderr availability,
decoded-character count, decoded-text SHA-256, first/last 1200 characters and edge
truncation flags. The larger stdout tail has its own retention limit/truncation
flag. Timeout partials stay distinct from complete results; missing streams stay
unavailable, and every unattempted command remains named. Byte-valued partials
are decoded as UTF-8 with replacement before these text hashes are computed, so
the hashes do not assert original byte identity. These bounds limit retained
receipt text, not subprocess capture memory or total log history. The logs describe
existing global framework records and are not automatically bound to the preview
package, which was not installed in the failed run.

In the isolated `9ffd` to `24d0edc7` diagnostic delta, the original HVF
600-second boot poll, four observation records and readiness/install checks, all four installed case bodies, selected display/renderer/resource inputs,
workflow and cleanup remain unchanged. A diagnostic result cannot rescue boot or
replace the original failure. Eight added host tests cover the exact command
boundary, rejected identities, complete and partial stream retention, one shared
deadline, exhausted/unattempted work, owned-process exit, absent versus empty
streams and the actual main handler's original-exception propagation. The original
source failed the new six-command expectation before the implementation. All 118
focused Android host methods in that isolated diagnostic source then passed under both normal and optimized Python, with zero
failures, errors or skips. These fake-device/time tests do not run an emulator or
establish that a future guest will answer either diagnostic command. Native
execution remains coordinated through the existing #255 owner and normal gates.

Primary mechanism references checked on 2026-10-18: the pinned Android 15
[`logcat` implementation](https://android.googlesource.com/platform/system/logging/+/android-15.0.0_r1/logcat/logcat.cpp)
makes `-d`/numeric `-t` a nonblocking read of existing records; the tag filters
select recorded priorities without changing system logging. The pinned
[`dumpsys` implementation](https://android.googlesource.com/platform/frameworks/native/+/android-15.0.0_r1/cmds/dumpsys/dumpsys.cpp)
lists/checks existing services for `-l` and returns before starting service-dump
threads. The subprocess deadline still bounds an unresponsive service manager.
Neither command clears logs, starts a service or changes a host/guest permission.

### Explicit profile handoff for the new ACL candidate

The new standard-Ubuntu/KVM candidate keeps the same requested 720×1280/280
startup profile while changing the explicitly reviewed host/access boundary. It
retains the exact image and emulator pins, renderer, requested CPU/RAM, strict
readiness/launch/XML/four-case checks and cleanup. KVM retains its existing
300-second boot budget; the earlier HVF600 result is not used as a KVM success
or as authority to increase this budget.

The earlier shared environment selector did not preserve
`PYAI_EMULATOR_DISPLAY_PROFILE`. The ACL wrapper now requires the exact explicit
`raster-720x1280-280` value before any policy or device mutation and adds only
that reviewed variable to the existing narrow child environment. The retired
RunAs-group wrapper and its environment policy remain byte-for-byte unchanged.
The unprivileged child recomputes the complete context from the environment it
actually received; a missing, default, unknown or mismatched profile fails before
the device observation or harness exec. The final ACL validator requires the
same explicit profile even when given a self-consistent receipt/context pair.
The workflow then passes that bound profile to the installed-receipt validator,
which still requires the actual owned configuration, generated hardware,
physical-display observations, exact KVM acceleration/binary/APK and all cases.

The narrow integration changes only the hardware-mode admission for this profile,
its allowlisted handoff and receipt checks. It does not change the three-value
configuration edit, any display observation, boot polling, readiness, installed
case, process-lifecycle proof or restoration operation. Focused mocked tests
cover missing/wrong values at entry, actual child reception and final acceptance,
plus a valid KVM display receipt that still rejects missing hardware/readiness
or installed results. Neither these guards nor the prior HVF configuration
observation establish an actual ACL grant, KVM boot, performance improvement or
installed pass. The whole current-main composition requires independent review
before the one new hosted attempt.

### Actual KVM boot followed by an unavailable HOME hierarchy

[Run 39146579521, attempt 1 / installed job 117732554493](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39146579521/job/117732554493)
executed signed prepared checkout `5b6274b826c60c45b730d80e98776c5a86bb03cc`,
tree `8af9ad75108d206a1179c4f483f3629e3a6e900f`, from canonical source
`2b04d2ef258dc0f5d958f9dd2c52e54e35767e9b`. Its original installed artifact
`12143473819` is 66,125 ZIP bytes, SHA-256
`a14c88b89dea5a4988c1f77e2444d5c8890efa4e82cc47a19481c6a5709dbc2c`.
The receipt, JUnit, emulator log and separate ACL receipt were independently
downloaded and checked against the actual job and source.

The original KVM boot limit remained 300 seconds. Boot completed after 26 probes
in 25.301 seconds, the configured/generated/physical 720 by 1280 at 280 dpi
observations passed, and all nine user/storage predicates passed together on the
first readiness snapshot. The single framework barrier then returned exit zero
with both required completion lines and empty stderr. Its retained pending-work
lines remain part of that complete observation.

The first HOME snapshot removed the exact prior XML path successfully, then
`uiautomator dump` returned exit zero with empty stdout and this complete
58-byte stderr, including the final newline:

```text
ERROR: null root node returned by UiTestAutomationBridge.
```

The stderr text SHA-256 is
`406bd8df88b9c43b78dc2a75c35ee3f96f79a5b3ea922e8ead222b4c5b5752f9`.
The original source correctly rejected this result before reading XML or querying
HOME focus. Functional readiness failed after 3.652 seconds; installation,
launch and all four installed cases were never reached. Every case remains
`not_run`, with one environment failure and four skipped cases in JUnit. The
prepared signed-copy hash is not installation evidence.

Later bounded failure diagnostics observed the launcher as displayed, focused
and resumed, with no recorded ANR since boot and an LMK total of zero. Those later
observations do not establish why the earlier root was unavailable, whether it
would become available within another observation, or an application crash.
The separate ACL cleanup also failed: the observed device ACL differed from the
owned granted snapshot, so both cleanup attempts refused before a restoration
command. The named UID grant was absent and a mask remained; neither its cause
nor exact restoration was proved. This HOME-only change preserves that refusal
and does not change any ACL, permission, privilege or cleanup code.

The bounded follow-up can perform at most **three HOME snapshots** within the
original shared **180-second readiness deadline**. Only the exact recorded
null-root failure qualifies: successful removal of the exact prior path with
complete empty streams, then integer dump exit zero, complete empty stdout,
the exact complete stderr above, the original failure type/message, and no
timeout or other command error. A quarter-second interval between eligible
attempts is clamped to the remaining deadline; time is checked again before
continuing. Each new snapshot repeats the existing fresh-file removal, and no
framework barrier or application command is retried.

The receipt retains every attempt, the unchanged first failure, the final attempt
and the explicit cap. Acceptance requires sequential complete history, exact
command/stream metadata for every failed predecessor, and exactly one completed
final dump. The original fresh XML read/parse, portrait/visible launcher identity,
user-zero HOME focus, original command maxima and absolute deadline all remain
required. Unrelated errors, partial timeouts, stale or malformed XML, foreign
targets, changed stream digests and missing/reordered history cannot pass. App
snapshots, launch/install logic, all four case bodies, boot/resource/display
settings and both cleanup paths are unchanged.

The exact receipt-derived regression failed against unchanged `2b04` before this
implementation. All 17 new host methods then passed; the complete current
`scripts/tests` discovery passed **232 methods**, with zero failures, errors or
skips. These tests use fake command results and time to verify failure retention,
cap/deadline boundaries, command ordering and rejection predicates. They do not
execute an emulator or demonstrate that this source cures the observed failure.
No new installed attempt was started for this isolated contribution. Native
execution and the full current-main integration remain with the existing #255
owner and ordinary reviewed gates, including successful ACL restoration and
all four real installed cases.

Primary mechanism checked on 2026-10-18: pinned Android 15
[`DumpCommand`](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/cmds/uiautomator/cmds/uiautomator/src/com/android/commands/uiautomator/DumpCommand.java)
waits for idle, requests the active window root once, and prints this diagnostic
then returns before file writing if that root is null. This establishes the
observed command branch; it does not establish the underlying cause or the
sufficiency of another bounded observation.

## Inputs and provenance

The application keeps its existing JDK 21, Gradle 8.11.1, Kotlin 2.1.20, AGP 8.9.2,
build-tools 35.0.0, compile/target 35 and minSdk 26 pins. This lane selects
`system-images;android-35;default;x86_64` **revision 2** and rejects revision drift.
The upstream SDK manager resolves that package through the official SDK repository;
the AOSP image needs no Google account or Google Play image.

The runner's binaries can change independently of the image pin. The preceding
`9ffd` HVF workflow rejected a preinstalled emulator outside its version pin.
The current ACL/KVM workflow verifies its dedicated Linux archive and version/build
and binds that binary to the boundary and installed receipts. Adb remains an
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
`android-installed-kvm-<workflow commit>` artifact retains the `emulator-smoke/`
JSON/JUnit, bounded emulator log, synthetic UI XML/PNG checkpoints and failure
logcat when available, plus `kvm-acl/receipt.json`. Both directories are
relative to the common `preview/build/reports/` archive root. The final gate
requires the complete original-user ACL grant/restoration boundary, selected KVM
mode and exact raster profile, actual owned-AVD/physical-display agreement, observed
acceleration, exact emulator binary and producer APK digest in addition to guest
readiness and all four actual cases. Earlier HVF artifact names and failed
receipts remain historical evidence. Link the exact
run, commit and artifact before treating it as receiving evidence; artifact expiry
does not create a permanent acceptance record.

## Running and reviewing

The core lane checks the harness guards. The dependent installed lane executes the
same harness after the successful unsigned build/simulation and exact APK handoff:

```sh
python -B -m unittest discover -s scripts/tests -p 'test_android*.py' -v
```

This command validates target, selector, policy-boundary and receipt rejection
with local fixtures/mocks plus harmless Python subtree lifecycle tests. It executes
no sudo or ACL tool command, SDK provisioning or emulator. The hosted workflow
separately runs the ACL wrapper's `--check-policy`, `--prepare-emulator`, `--run`
and always-run `--restore` phases through the absolute Ubuntu Python 3.12
interpreter. Those phases reject local/self-hosted identity and expect the exact
workflow source, SDK paths and producer artifact. Use the ordinary independently
reviewed PR workflow to obtain one actual installed run. Keep the required
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
