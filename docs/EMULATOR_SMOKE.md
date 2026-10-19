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
The first ACL/KVM attempt booted and verified that display, then failed its HOME
root acquisition and exact cleanup check. The second acquired fresh HOME XML and
focus but rejected its embedded search package; that attempt restored the exact
original ACL. The third passed HOME readiness and the installed launch case,
then failed to locate Save locally; rotation and deletion did not run. Its exact
original ACL was also restored. These failed installed results are recorded below.
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

After the barrier, a fresh home hierarchy must pass the existing prior
file removal, exact dump completion, clean read and XML requirements. Only the
exact observed null-root response described below permits another HOME attempt;
at most three attempts share the original readiness deadline. The same
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

At the original synchronization checkpoint, all 69 host guards passed locally, including deadline-boundary results, exact
completion versus terminal failure, partial timeout output, the observed empty
137 dump, ANR focus despite valid home XML, wrong or invisible home nodes,
incomplete receipt evidence and original-error preservation. The ordinary
installed snapshot calls retain their existing command maxima, acceptance checks
and failure behavior. Boot limits, launch status, all four case bodies, selected
image/emulator/graphics, APK handoff and owned-process cleanup are unchanged.
This is one measurable startup-synchronization experiment, with no passing
installed result yet. A new exact-source hosted run must still pass all four
installed cases before the lane can be accepted.

### Observed KVM HOME-root absence and bounded acquisition

[Run `39146579521`](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39146579521)
executed signed checkout `5b6274b826c60c45b730d80e98776c5a86bb03cc`, tree
`8af9ad75108d206a1179c4f483f3629e3a6e900f`, from preparation
`2b04d2ef258dc0f5d958f9dd2c52e54e35767e9b`. Its original installed artifact
`12143473819` contains 66,125 ZIP bytes with verified SHA-256
`a14c88b89dea5a4988c1f77e2444d5c8890efa4e82cc47a19481c6a5709dbc2c`.
The named-user ACL was observed present before and inside the unchanged non-root
workload. The selected emulator reported usable KVM, and the guest kernel
recorded two CPUs. Boot completed in 25.301 seconds after 26 probes; the physical
720×1280 display and 280-dpi density were positively verified. These observations
do not by themselves establish an installed-case result or a causal speedup.

All nine initial user/storage predicates passed. The one framework barrier
completed in 1.338 seconds with both required positive markers. The first HOME
dump then returned integer exit 0, empty stdout and exactly
`ERROR: null root node returned by UiTestAutomationBridge.` followed by a newline,
after 2.117 seconds. No dump-completion marker, XML read or functional focus gate
passed. Readiness failed in 3.652 seconds, before installation, and all four app
cases remained `not_run`. The separate cleanup failure is described in the ACL
section; neither failure is reclassified by a later source change.

The later diagnostic Activity dump records Launcher3 resumed, drawn and holding
current focus. The retained framework log records its first draw in 2.588 seconds;
the global ANR reports say no ANR since boot, and LMK counters are zero. These are
later observations. The receipt does not give the failed dump an absolute wall
timestamp, so it does not establish the exact ordering of root acquisition and
first draw, or prove why the accessibility root was absent.

The [Android 15 dump implementation](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/cmds/uiautomator/cmds/uiautomator/src/com/android/commands/uiautomator/DumpCommand.java)
connects its automation service, calls `waitForIdle(1000, 10000)`, and then queries
the active root once. Its null branch prints that exact diagnostic and returns
before the success marker. [UiAutomation](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/app/UiAutomation.java)
defines idle as a quiet accessibility event stream; its root query is nullable.
This source explains the observed failed command path without proving the exact
system-image binary/source binding or that another acquisition will succeed.

The follow-up permits at most three HOME acquisition attempts within the same
original 180-second user/storage/framework/HOME deadline. The existing per-command
30/20/30-second remove/dump/read caps remain clamped to that deadline. There is no
new timer, added sleep, repeated barrier or app warmup. Only a complete exit-zero
dump with empty stdout and the exact null-root stderr, following a clean successful
prior-file removal, permits another attempt. Any other failed response, including
timeout, transport/process failure, exit 137 without its original completion
proof, another diagnostic, malformed XML or a read error, stops immediately.
Each new attempt removes the previous dump before writing and reading fresh XML.
All attempted sequences and each failed decoded stderr remain in the receipt,
including `first_failed_attempt`; the three-attempt limit fits within its existing
eight-entry retention bound. The receiving validator requires every earlier
attempt to match that exact failed path and the final attempt to satisfy all
original XML, visible HOME, focus and barrier requirements. A failed-only or
inconsistently counted sequence cannot pass.

The generic `Ui.snapshot` implementation and all four installed cases are
unchanged; installed snapshots still fail this response without retry. Nine
focused host methods cover one/two unavailable roots followed by fresh evidence,
three persistent failures, unchanged deadline/caps, exact diagnostic admission,
original timeout/process errors, malformed XML/read errors/ANR focus, inconsistent
receipts and unchanged installed behavior. These mocked host results establish
source behavior only. The original producer artifacts independently contain
2,627 core and 211 simulated native cases with zero failures/errors/skips, but
the installed run above remains failed and the revised source needs its own
reviewed hosted result.

### Observed embedded HOME search package and exact ACL restoration

[Run 39151071213](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39151071213),
attempt 1 / installed job `117745927337`, used signed prepared checkout
`2dcc12c8756b3bba2c5ab71d5b86cf8101c3b4b0`, exact source tree
`e9e1b084054557c7d1a6e5aef6a7adaefd332437`, from preparation head
`b7641b5d229f2369e1712182615eec9a015d2251`. The original installed artifact
`12145128712` is 72,295 bytes with verified ZIP SHA-256
`34a3649981e06be8c594062d6a8390c8b9786252c677983cf39893353d565672`.
Its receipt and JUnit remain failed, with all four installed cases `not_run`.

All nine basic readiness observations and the one complete framework barrier
passed. The first HOME snapshot also completed its remove, dump, read and XML
parse. The retained hierarchy summary records rotation `0`, 22 positive-bounds
Launcher3 nodes and the sorted packages `com.android.launcher3` and
`com.android.quicksearchbox`. The read contained 9,261 decoded characters, with
SHA-256 `37a0cfa9cc77d7b88053c69e08049bff7f2191a31b9457679447fb313ef9a7b0`.
Both required focus entries named the exact user-0 Quickstep HOME window. The
24,885-character focus result was fully retained below its 65,536-character bound;
its smaller prefix/suffix summaries do not mean that the retained focus was
truncated.

The previous final package-list predicate admitted Launcher3 alone or with System
UI, so it rejected this HOME summary. A read-only reconstruction of the exact
pre-guard state confirms that every other final predicate passes; changing only
that package-list input to the formerly admitted HOME-only list makes the old
predicate pass. This is a counterfactual source diagnosis, not a change to the
original receipt. Readiness ended after 9.298 seconds, not exhaustion of its
180-second deadline. The generic failure message combines readiness and deadline
requirements and does not itself identify a timeout. There was only one HOME
attempt; this run did not exercise the null-root retry, and it never reached APK
installation or an installed application case.

The independently read Android 15 release-tag
[Launcher3 QsbContainerView](https://android.googlesource.com/platform/packages/apps/Launcher3/+/refs/tags/android-15.0.0_r1/src/com/android/launcher3/qsb/QsbContainerView.java)
resolves the configured or global-search package, binds its search AppWidget and
adds that provider view inside HOME. The same tag's
[QuickSearchBox manifest](https://android.googlesource.com/platform/packages/apps/QuickSearchBox/+/refs/tags/android-15.0.0_r1/AndroidManifest.xml)
declares the exact `com.android.quicksearchbox` package and its
`SearchWidgetProvider`. These primary sources support the observed package set
as compatible with an embedded HOME search widget; they do not establish an
exact source/binary match for the installed system image or reconstruct XML
characters omitted from the bounded read summaries.

The admission correction adds only this known package to the two existing HOME
forms: each of the four explicit sorted, unique package lists still contains
Launcher3, with optional `com.android.quicksearchbox` and `com.android.systemui`.
Any other package, missing Launcher3, duplicate or unordered receipt list fails.
Positive-bounds HOME nodes, rotation zero, fresh exact-path XML, both exact HOME
focus entries, the complete framework barrier and the original deadline remain
mandatory. The search app itself in the foreground and any ANR/dialog focus
still fail. No installed snapshot, case, retry limit, timer, image, renderer or
permission operation changes. Six focused host methods cover both embedded-widget
forms, exact 0/137 dump completion, retained null-root history, foreign or missing
HOME content, foreground/ANR focus, malformed package lists, read stderr and the
unchanged deadline. Host tests do not establish a new installed result.

The same original artifact also supplies a separate successful cleanup
observation. Before cleanup, the original device had the exact three base ACL
entries plus the sole `mask::rw-`, no named entries and no runner read/write
access. The reviewed fixed BASE restoration command returned integer zero with
complete empty streams, proven permission-command subtree quiescence and final
`ECHILD`. Its entire final observation equals the original before observation:
device identity, root ownership, existing KVM group, mode, original non-root
UID/GID/supplementary groups, exact three-entry ACL and denied access. The boundary
still records failed child exit 1 and the original HOME failure. This observes
the bounded cleanup normalization working in that run; it does not identify the
actor that previously removed the temporary named entry or turn the failed
installed attempt into a pass.

The four original producer archives for the same signed checkout independently
verify 2,635 core cases in 59 JUnit reports and 222 simulated native cases in 16
reports, with no failures, errors or skipped cases. Their 8 core and 10 preview
source pins and output hashes match the exact tree. Both APK copies are identical
at 2,859,217 bytes / SHA-256
`dd3b533ed79ddbffeee1ea2b6eac580e8b5c88f463c9eee884b65cc897081d69`.
Lint reports 15 warnings and no errors; the pinned scanner inspects 173 reachable
commits with no findings. These passing producer results are distinct from the
failed installed job and every physical-device or release requirement.

### Observed installed launch and bounded wheel navigation

[Run 39153757625](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39153757625),
attempt 1 / installed job `117753770213`, checked out signed prepared commit
`0c1a0bcf1de1c0744f6a9c77131536f9185ad1f1`, exact tree
`13a8434780a611b7912ddafe94bbbd4d1ec9e03a`, from preparation head
`beff1a6e3e08fe142672a3135549d9bd9bd305df`. Original installed artifact
`12146102587` is 343,956 bytes with verified ZIP SHA-256
`43f0cfe50f220f320e1f40a23af9226b71b2bcf3ae92f2a5864215d48bc7c67d`.
Its four-case receipt and JUnit remain failed:

| Actual case | Observed result |
| --- | --- |
| `installed_synthetic_launch` | Passed in 27.995 seconds |
| `acknowledged_save_survives_force_stop` | Failed in 38.271 seconds: `UI control not found: Save locally` |
| `pending_fields_survive_rotation` | `not_run` |
| `confirmed_deletion_does_not_resurrect` | `not_run` |

The owned guest booted in 33.696 seconds, passed the nine readiness checks,
framework barrier and fresh HOME/focus gate in 9.629 seconds, and verified the
explicit 720×1280/280-dpi profile. The strict initial Activity launch returned
integer zero, empty stderr and `Status: ok` / cold launch in 1.201 seconds. All
27 installed hierarchy captures completed with exit zero; none used the 137
exception. The unchanged case sequence entered and verified the three synthetic
fields before attempting Save. It did not reach the Save acknowledgement or
the save case's process restart.

The retained launch XML/screenshot show the top of the form. The separate failed
checkpoint shows its catalog, validation and diagnostic controls near the bottom;
Save is absent from that viewport. The last two scan reads and the later failure
checkpoint have the same decoded-text digest. The complete retained Activity
state names the app as resumed and focused. These observations do not reconstruct
the intervening XML omitted from the bounded stream summaries, prove that a
particular gesture skipped Save, or establish a layout defect in the application.

There is nevertheless a concrete navigation risk in the previous source. Its
250-millisecond touch swipe covers 60% of the reported ScrollView height. Android
15's release-tag
[InputShellCommand](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/services/core/java/com/android/server/input/InputShellCommand.java)
finishes that moving gesture with `ACTION_UP`; the same tag's
[ScrollView](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/widget/ScrollView.java)
can then fling above its minimum velocity. Consecutive hierarchy scans therefore
have no source-level guarantee of overlapping viewports.

The correction sends one `input mouse scroll X Y --axis VSCROLL,-2` event when
scanning down and `VSCROLL,2` when scanning up. The cited input implementation
injects one `ACTION_SCROLL` and waits for its dispatch. ScrollView applies the
vertical factor, clips the resulting offset to its range and scrolls
synchronously. Although it calls
[DifferentialMotionFlingHelper](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/widget/DifferentialMotionFlingHelper.java),
the same release's
[VelocityTracker](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/view/VelocityTracker.java)
and [native tracker](https://android.googlesource.com/platform/frameworks/native/+/refs/tags/android-15.0.0_r1/libs/input/VelocityTracker.cpp)
track X, Y and SCROLL, without VSCROLL velocity. The
[JNI bridge](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/jni/android_view_VelocityTracker.cpp)
returns zero for that unsupported velocity. This supports the selected mouse
wheel path without a touch-release fling in the inspected source. The source's
[default vertical factor](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/res/res/values/config.xml)
is 64dp, making two units 128dp before clipping. The actual image's factor,
resource overlays and viewport are not measured by these source reads; an exact
source/binary equivalence or successful installed navigation is not asserted.

Each search inspects a fresh initial hierarchy and the result of every wheel
event, including the final permitted event. It allows at most 64 events in each
direction, resets its previous-viewport comparison before scanning down, and
stops a direction when consecutive parsed hierarchies are identical. A missing
control still fails after the bounded scan. Non-scrolling searches retain their
eight fresh observations and 250-millisecond waits. There is no timeout, launch,
HOME-readiness, case, selector, app, image, display-profile or ACL change. The
wheel command itself must return an integer zero and complete empty stdout and
stderr; command errors and failed fresh captures are not retried by navigation.

The original XML also reports the full `[0,0][720,1280]` ScrollView and an
app-decor `android:id/navigationBarBackground` at `[0,1196][720,1280]`. Thus a
positive-bounds control can have its center beneath a reported bar. Each fresh
search now checks the two exact navigation/status-bar resource IDs, requires
unique app-owned positive bounds, and continues searching when the matched
control's center lies in either reported bar. It does not rewrite the target
node or weaken its exact selector. Wheel coordinates use the part of the
ScrollView left after excluding unambiguous edge bars; this includes landscape
side bars. Duplicate, foreign or malformed bar identities, a bar intersecting
the interior without defining an edge, or a fully covered scroll viewport fail
before wheel input. Unknown or unreported overlays remain an actual-device
limitation; the source does not claim general hit-testing or accessibility
acceptance. The existing tap continues to use the returned observed center.

Navigation observations retain the first failed search and the most recent
eight searches, each with at most 16 recent frames. Each frame records direction,
step, existing snapshot sequence, at most 24 visible app labels of 120 characters,
explicit truncation flags, reported bar bounds and the bounded wheel command
result when attempted. `parsed_xml_sha256` hashes ElementTree's serialization;
it is separate from the unchanged original decoded-read digest and does not
reconstruct discarded XML. A later successful search cannot erase the retained
first failure. These are retention limits, not a new subprocess stream-memory
bound or a replacement for original artifact evidence.

Sixteen focused host methods exercise the intervening-target model, the fresh
post-64 viewport, both event caps, direction/end handling, no stale-match reuse,
reported-bar clipping in both orientations, ambiguous/foreign/invalid bounds,
command and capture failures, and bounded observation retention. In the same
synthetic document the actual previous finder observes offsets 0, 1000 and 1800
and misses a target between them; the new finder observes 128 then 256 and finds
it. Separate clipped-button models expose the previous tap inside a reported bar
and require a fresh unoccluded center before the new tap. These are host-only
counterexamples with disclosed movement assumptions, not replayed emulator
gestures or reconstruction of the original missing viewport.
All 210 current host methods passed with no failures, errors or skips. The
unchanged lifecycle tests include harmless private subprocess fixtures; no
production ACL, SDK, emulator or Android input command ran in this verification.

The original run's ACL cleanup remains independently verifiable: the one fixed
BASE restoration command completed with clean zero status, proven permission
subtree quiescence and final `ECHILD`; its entire after observation equals the
original before observation. The actor that had removed the temporary named
entry remains unidentified. The boundary records the original failed child,
and the two unexecuted installed cases remain unexecuted. Passing producer
artifacts for this tree contain 2,635 core and 222 simulated native cases with no
failures/errors/skips; those results do not supply the missing installed cases.

### Staged wheel pass and receiver rotation-state failure

The wheel source reached a complete installed result in
[run 39158610938](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39158610938).
Its actual signed checkout `063b22df79a49e5836c854015b21888d71ae6425` and
preparation `86b1faba1c33825fb71ec664c1f409f5ef596394` share tree
`5c674a2ddea78ff94c79c22391c3f778ed95a48d`. All four installed cases passed,
including both rotations and confirmed deletion without resurrection. The original
installed artifact `12148168724` is 978,543 bytes with SHA-256
`5f1fa5d045f01610c6116c6747e503efd1076beaebc468650387695435104b69`.
Independent reviews inspected the original receipts, all eight XML/PNG pairs,
the four producer artifacts and exact original ACL restoration. This is an
emulator result on that source, separate from physical-device qualification.

The ordinary reviewed squash of [#255](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/255)
produced valid-signed `5c07715b3553023b02ee3ccf61d1886bc0245ae1`, retaining that
exact tree. Its sole parent is `899e917842bc62aedd9040c9622c87d17e12f1cf`.
The next receiver, [#237](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/237),
then executed signed checkout `ef6fd60782dec344b9132f4cfbf33d603c80b654`, with
ordered parents `14a89f088e477c2dff3c455a27994faf75d36f69` and `5c07715b`, and
the same `5c674a2d` source tree. Its own
[run 39161826073](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39161826073)
failed the installed rotation case:

| Mandatory case | Actual receiver result |
| --- | --- |
| Installed synthetic launch | Passed, 56.408 seconds |
| Acknowledged save and force-stop/cold relaunch | Passed, 130.201 seconds; process 2301 replaced by 3614 |
| Pending fields across both rotations | Failed, 57.421 seconds: `Emulator did not reach the requested rotation` |
| Confirmed deletion without resurrection | `not_run` |

The receiver's original installed artifact `12149815317` is 623,985 bytes with
SHA-256 `4b73ad3e5d336e7c7119aff2a7fab0a115aa2e420a1190d03d63a2cc11a33ed3`.
Boot completed in 28.474 seconds; all nine prerequisites, the complete positive
barrier, fresh HOME/QSB hierarchy and exact HOME focus passed in 7.260 seconds.
Both actual `am start -W` commands reported `Status: ok` and `LaunchState: COLD`,
in 1.198 and 1.284 seconds. All 118 hierarchy dumps completed with exit 0.
The failure XML and PNG still show the pending title/objective and unchanged
context in portrait. They establish neither a landscape transition nor the
unexecuted deletion case. The receiver's own producer originals separately
contain 2,635 passing core cases, 222 simulated cases, strict inventories, both
packaged-input negative guards, lint and the 91-commit zero-finding scanner.
Its hosted core-job log separately records all 210 host methods passing.

The complete retained Activity stack is 30,976 characters and is not truncated.
It identifies the resumed, drawn and focused `PreviewActivity`, whose requested
orientation is `SCREEN_ORIENTATION_UNSPECIFIED`. Its default `DisplayRotation`
block reports current `mRotation=0`, requested `mUserRotation=ROTATION_90`, and
`mUserRotationMode=USER_ROTATION_FREE`. All eight retained rotation-lock history
entries from 20:18:36 through 20:18:50 name
`UiAutomationConnection#restoreRotationStateLocked` restoring `FREE` with the
stored 90-degree angle. The first writer or timing that established free mode
is not retained. These observations show why an angle alone cannot establish a
locked landscape state; they do not prove the origin of the free mode.

ACL cleanup completed independently of the failed case. The original non-root
UID/GID/groups and character-device identity stayed constant through all five
observations. Cleanup admitted only the exact base ACL plus its sole redundant
`rw-` mask, executed the fixed BASE restoration with exit 0 and verified subtree
quiescence, then read back an observation exactly equal to the original one.
The actor that removed the named entry remains unidentified. The wrapper retains
child exit 1 and the original rotation failure; its receiving gate remains failed.
The earlier `063b22df` pass remains a separate historical result.

#### Explicit lock and observed rotation before a fresh capture

The bounded correction changes `Ui.rotate` and adds a parser for the current
default-display state. It makes one explicit owned-guest request,
`wm user-rotation -d 0 lock N`, selecting both lock mode and the requested angle.
Exit 0 alone does not establish the applied state. Before opening another
UiAutomation connection, it reads the existing bounded
`dumpsys -t 3 activity activities` observation and requires a single unambiguous
default-display block with `USER_ROTATION_LOCKED`, the requested user angle,
the same current rotation and no deferred rotation pause.

Only then does it request a fresh hierarchy through the existing remove/dump/read
path. Success still requires the requested XML rotation and positive visible
owned-app nodes. A second complete state observation after capture must retain
the same lock and current rotation. A new snapshot is required if an earlier
fresh XML observation does not meet the existing rotation predicate; old XML is
never reused. The request is issued once, without a repeated lock or a different
orientation mechanism if state does not converge.

The original 30-second rotation budget now covers the request, state observations,
fresh capture and final state check together. Each new command receives at most
five seconds and the remaining shared budget; the internal `dumpsys` timeout stays
three seconds. Fresh capture uses its existing per-command caps clamped to that
same remaining deadline. Ordinary incomplete state is polled with waits of at most
0.25 seconds. Failed/noisy/malformed/ambiguous or internally timed-out queries,
capture failures and results completing at or after the deadline remain failures.
The four case bodies, selectors, app implementation, startup readiness, workflow,
image/renderer/display pins, ACL boundary and owned cleanup are unchanged.

The receipt retains each lock command, the first and last full state observations,
the last observation after capture, and 16 compact state summaries per request.
Full state stdout retention is capped at 65,536 decoded characters per retained
observation; the shared command helper separately bounds decoded stream edges.
There are at most eight recent requests plus the first failed request. Snapshot
sequence and parsed-XML SHA-256 distinguish the tested hierarchy from the retained
command text and original checkpoint files. These are retention limits; subprocess
capture memory is not claimed to be streaming-bounded. Timeout/error observations
and an earlier failed request survive later observations.

Primary source checked on 2026-10-18 is Android 15 tag `android-15.0.0_r1`, resolving
to framework commit `4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3`:

- [UiAutomationConnection](https://github.com/aosp-mirror/platform_frameworks_base/blob/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3/core/java/android/app/UiAutomationConnection.java)
  stores frozen state and the current rotation when it connects, and restores that
  frozen angle or thaws rotation when it disconnects.
- [DisplayRotation](https://github.com/aosp-mirror/platform_frameworks_base/blob/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3/services/core/java/com/android/server/wm/DisplayRotation.java)
  writes both system settings for a default-display lock; a settings observer
  subsequently updates its internal mode/angle. Its dump prints the current
  rotation, deferred pause count and user mode/angle used by the parser.
- [WindowManagerShellCommand](https://github.com/aosp-mirror/platform_frameworks_base/blob/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3/services/core/java/com/android/server/wm/WindowManagerShellCommand.java)
  implements the explicit display lock request. Its separate user-rotation query
  reports requested user state, which does not by itself prove the current angle.
- [WindowManagerService](https://github.com/aosp-mirror/platform_frameworks_base/blob/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3/services/core/java/com/android/server/wm/WindowManagerService.java)
  and [DisplayContent](https://github.com/aosp-mirror/platform_frameworks_base/blob/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3/services/core/java/com/android/server/wm/DisplayContent.java)
  bind UiAutomation's current-rotation query to the same `DisplayRotation` value.

These sources establish the mechanism, without claiming exact system-image
binary/source equivalence or reconstructing the first free-mode transition.
Nineteen new host-only methods cover the observed free-mode mismatch, capture
before an applied rotation, both directions, distinct fresh XML, post-capture
reversion, malformed/foreign/duplicate state, nonpositive bounds, command failures,
partial timeouts, deadline edges and bounded failure retention. The two actual
unchanged-source witnesses fail against the original `Ui.rotate` and pass against
the candidate using deterministic state interleavings. All 229 host methods pass
locally, including the existing harmless process-lifecycle fixtures; no production
ACL entry, SDK or Android device is invoked by this validation. Current-source
independent review and a complete new installed receiving result remain required
before normal staged delivery and separate current-main integration.

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

The wrapper's finally path restores only the same owned device with the exact
original base ACL, exactly this job's named-user ACL, or the exact base entries
plus a sole `mask::rw-` and observed original-user access `false`. That third state
contains no named user or group entry and grants exactly the original rights;
it is accepted only for cleanup, never for initial provisioning or child entry.
A replaced device, any other ACL or contradictory access observation is retained
as a cleanup failure and is never overwritten.
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

#### Observed redundant-mask cleanup state

The first ACL/KVM [run 39146579521](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39146579521)
used source `2b04d2ef258dc0f5d958f9dd2c52e54e35767e9b`, actual signed checkout
`5b6274b826c60c45b730d80e98776c5a86bb03cc`, tree
`8af9ad75108d206a1179c4f483f3629e3a6e900f`. Original artifact `12143473819`
is 66,125 bytes with ZIP SHA-256
`a14c88b89dea5a4988c1f77e2444d5c8890efa4e82cc47a19481c6a5709dbc2c`.
Both exact policy checks and the supervised grant passed. The named UID 1001 ACL
was present with access `true` after the grant and inside the unchanged non-root
child. KVM activated two guest CPUs and boot completed in 25.301 seconds. The
first HOME dump then returned exit 0 with the exact null-root diagnostic on stderr;
no hierarchy completed, installation did not run and all four cases were `not_run`.

Before either cleanup attempt could execute its restore command, readback showed
the same device and process identities but no named entry: `user::rw-`,
`group::rw-`, `mask::rw-`, `other::---`, with access `false`. Both attempts stopped
at the previous two-state precondition; neither contains a restore command result.
The receipt therefore does not show that our restore removed the named entry or
left the mask. The actor and timing of that intervening ACL change are unknown.

The Linux ACL project's [validity and access algorithm](https://man7.org/linux/man-pages/man5/acl.5.html),
checked 2026-10-18, permits an optional mask when no named entries exist. The mask
does not restrict the owner or other entry; group `rw-` intersected with mask
`rw-` is still `rw-`. This exact third state is permission-equivalent to the
original three entries for every user/group identity. The existing fixed restore
can remove only that redundant mask, subject to unchanged device/credential,
original-state, trusted-tool and completed-command checks, followed by exact
original-state readback. It does not regrant access or identify the unknown writer.
A read followed by `setfacl` is not an atomic comparison with a concurrent writer.
Every different mask, permission, named entry or device identity still rejects.
The actual failed run remains failed; cleanup normalization does not supply HOME
readiness or rescue an interrupted, failed or incomplete installed receipt.

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
and unknown modes still reject it. Source admission alone supplies no runtime
qualification; the first actual ACL/KVM attempt above verified the display but
failed before any installed case. Immediately
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

## Installed navigation diagnostics and a separate pixel gate (2026-10-19)

This is a bounded source proposal on the existing receiver 76a48d2a / tree 450f9f82.
It does not correct Activity, theme or WindowManager behavior. The canonical
Activity/window owner retains that source. Both the earlier 444 and later 929
installed runs passed their four functional cases while original modal 06/08
retained dark navigation glyphs on a dimmed background. The new 929 original
qualification is recorded in [6257903467](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/467#issuecomment-6257903467).
Its observed 1.582846:1 modal failure is not changed by this proposal or by the
passing API 28 focus simulations. A new actual source/receiving run is required.

### Original images and bounded window observations

The existing checkpoint first writes its original XML and PNG exactly as before.
Immediately after the PNG, checkpoints 05,06,07 and08 each make one read-only call
on the already-owned emulator serial: `adb -s emulator-5554 shell dumpsys -t 3 window`.
The host allowance is 5 seconds per call, four calls maximum, 20 seconds total
requested command allowance. These are additional, explicit diagnostic allowances;
there is no timer increase to boot, guest readiness, screenshot capture, rotation,
case navigation or owned cleanup. The screenshot command still has its 15-second
limit. There is no retry, extra UI input or appearance mutation.

The receipt binds each observation to its checkpoint PNG/XML hashes, exact argv,
exit/timeout/error, observed duration, decoded stream metadata and the separately
retained `.window.txt` hash. Up to 262,144 decoded characters are retained. A timeout
preserves its available partial output; failed or truncated output remains
unqualified. This retention limit does not claim to bound the existing subprocess
capture buffer. A complete command is still an observation, not proof of an
identified causal window. Review the actual focus, frames, visible navigation
insets, color/background-window candidates and effective appearance in those raw
results before accepting another production hypothesis. XML/PNG/window reads are
ordered observations, not an atomic WindowManager snapshot.

A command failure does not discard the later original modal checkpoint. Both
modal pairs are assessed after the four existing functional cases and ordinary
owned emulator cleanup. A contrast or diagnostic failure then fails the overall
installed harness and final receiving gate while preserving each case's actual
status. Thus four passing product rows coexist with the existing overall-failure JUnit
row when this final gate fails: five total JUnit tests with one failure. The four
product interactions are not relabeled as failures.
Ordinary output-write failures retain the existing fail-closed behavior.

### Fixed-rig image identity and contrast

The automatic image qualification is deliberately restricted to the actual
`raster-720x1280-280` portrait configuration, with the existing source/APK/device
and startup-display observations still required. It covers ordinary 05 with
modal 06 and ordinary 07 with modal 08; it is not a gesture-navigation, landscape,
cutout, physical-device or whole-accessibility assessment. The two modal
hierarchies must match their existing owned-package text/button identities, using
the same case-insensitive text matching as the product harness.

The paired ordinary hierarchy must identify exactly one owned
`android:id/navigationBarBackground` at `[0,1196][720,1280]`. Modal hierarchies omit
that underlying bar. Their same-size, same-rotation images are admitted only when
the ordinary bar's three separate solid-glyph components remain identifiable at
the same positions. This is an explicit fixed-rig pairing; it does not infer a
missing modal hierarchy node or identify the bar-owning WindowManager window.

The decoder admits only the observed non-interlaced 8-bit RGBA PNG with explicit
sRGB intent 0 and 8-bit component precision. It checks every chunk CRC, exact chunk
order, terminal IEND, dimensions and bounded inflation; at most 8 MiB of compressed
input is admitted and the inflated size must exactly equal the fixed canvas.
Unknown color profiles/chunks, unsupported encodings, truncated/concatenated
streams, transparent bar pixels and wrong geometry are unqualified. Original
files are not edited or rewritten by the analysis.

Within the 60,480-pixel bar, the ordinary background must cover at least 95 percent,
with 1,000–2,700 solid foreground pixels and no similarly large third population.
The foreground must form exactly three connected components, 250–900 pixels each,
within the three separate fixed-rig horizontal thirds and 12–48-pixel bounds away
from the bar edges. Each modal glyph must preserve at least 95 percent of its
paired component's same-color interior pixels, and the paired background must
remain at least 95 percent uniform. All three glyphs must share their solid color.
Missing, shifted or inconsistent controls and ambiguous regions fail qualification.
These geometry/population limits are fixed-rig admission rules, not a reduction of
the contrast threshold or a general visual-recognition claim.

The unrounded sRGB relative-luminance ratio must be at least 3:1 for both ordinary
controls and all three modal glyphs. The [W3C non-text contrast explanation](https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html)
supplies the 3:1 reference and warns against rounding a result up to the threshold.
This check uses solid original pixel populations, excludes their antialiased
fringes from the measured pair, and makes no claim of complete WCAG compliance.
A receipt cannot replace the computed ratio with a larger scalar: final validation
recomputes each ratio from its bounded integer RGB channels and checks population,
geometry, source/APK and checkpoint-hash relationships.

Both the running harness and the final workflow's `complete_receipt` call explicitly
require the new image evidence. The helper's default remains compatible with
historical receipt fixtures; any present image evidence is still validated, and
the current installed/receiving calls cannot omit the required field. The workflow
change is only that explicit keyword argument: no job, action, dependency,
producer, attendance name or original product assertion is removed or weakened.

### Actual local witnesses and retained runtime limits

The already-retained original 444 archive is 985,537 bytes with SHA-256
`e55d231e280b551c876595d4b4a9bf632664d0d4565ec530221480296f985577`.
Its unmodified 05/06 and 07/08 originals were read directly through the candidate
decoder. The ordinary controls measure 8.97386058887348:1. Each of the three modal
components measures 1.5828461084972716:1 and both pairs return `failed_contrast`.
All 418/601/619 ordinary solid-glyph coordinates retain the original dark modal
color. This reproduces the actual pixel defect rather than a command-text check.

The portable host tests embed only exact decoded bar-strip pixels from original 05
and 06, with explicit strip and original-image hashes. They construct clearly
synthetic canvases/XML around those strips, and include a separately labeled
synthetic white-glyph positive. Neither fixture is an edited original screenshot
or evidence of a corrected installed modal. The ordinary original is the actual
available positive control; a future corrected modal original is still required.
Tests also cover missing/shifted/mixed controls, foreign/duplicate hierarchies,
PNG parsing/inflation failures, all five filters, timeout partials, one-command
ordering, retained product results and forged/missing evidence.

No actual guest WindowManager observation, corrected modal pixel, new hosted pass
or main-delivery result is claimed for this proposal. Current 76/929 and all earlier
failed originals remain preserved. Normal exact-source review, current accepted-main
composition and configured receiving checks remain required before any subsequent
normal delivery. The historical interval ending 01:05 is not extended by this work.


### Targeted SystemUI observation and explicit display binding

The separately identified follow-up to the frozen `56789030` proposal adds one
additional read after each of the four original PNG and WindowManager observations:

```text
adb -s emulator-5554 shell dumpsys -t 3 activity service com.android.systemui/.SystemUIService LightBarController
```

Each targeted service read has its own five-second host allowance and the same
262,144-character decoded retention bound. There are at most four WindowManager
and four SystemUI calls: eight calls and 40 seconds of total requested command
allowance. The original proposal's 20-second figure describes its four WMS calls;
this addition explicitly adds at most 20 seconds. Existing boot, readiness,
rotation, screenshot, navigation and cleanup timers and product assertions remain
unchanged. A failure or timeout retains partial output and the subsequent original
checkpoints; it is neither retried nor treated as a zero/default state.

The fixed Android 15 r1 [DumpHandler source](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/packages/SystemUI/src/com/android/systemui/dump/DumpHandler.kt)
documents this targeted service form. Its [LightBarController](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/packages/SystemUI/src/com/android/systemui/statusbar/phone/LightBarController.java)
registers this name for the default display and dumps appearance, navigation-light,
scrim-force and IME-management fields. The included
[transition controller](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/packages/SystemUI/src/com/android/systemui/statusbar/phone/LightBarTransitionsController.java)
dumps pending, current and next dark intensity and transition state. In that source,
`mNavigationLight=true` requests dark icons; it does not mean that the photographed
icons are white. These are primary-source semantics, without an exact source-tag
to installed-image identity claim.

The final receipt requires the exact service query, complete successful streams,
the named LightBar target, its explicit state fields, a navigation transition
section and the final dump-completion line. A present empty appearance-flags value
is retained as such; missing or malformed state is unqualified. The raw state is
not converted into a causal conclusion or used instead of pixel measurement.
Each `.lightbar.txt` and its retained text hash is bound to the original checkpoint
PNG/XML hashes and ordered phase. None of the three sequential observations is
claimed to be atomic. Both original modal failures remain authoritative.

Independent review also found that the first proposal's reusable final helper
could admit raster image evidence with a valid `pixel-2` startup record, or no
startup record, when its optional display argument was omitted. The actual
workflow already supplied the raster display guard, so this was not an observed
hosted bypass. The follow-up requires a fully qualified startup display whose
profile matches image evidence whenever that evidence is required or present.
Meaningful missing/mismatch witnesses now reject, a matching raster control passes,
and historical receipts without image evidence retain their earlier admission.

All additions remain local source preparation. A later accepted-main autosave
change requires explicit preservation and installed-flow reconciliation; these
new observation delays do not establish compatibility with a 750-millisecond
pending-form autosave behavior that is absent from the frozen source76 baseline.
No Activity/theme correction, new actual observation or passing modal is claimed.

The decoder continuation also fixes the first proposal's repeated-IDAT bookkeeping:
one constant-size parser marker now represents consecutive data chunks. Valid
split and empty IDAT chunks retain identical decoded bytes without repeatedly
copying/scanning an ever-growing prior-chunk list. A counted-work regression fails
the original decoder and passes the continuation; CRC, order, profile, dimensions
and inflation limits remain intact. This addresses input-processing cost, not an
observed defect in the retained screenshot pixels.

A further raw-format correction admits the finite scientific notation emitted by
Java's `PrintWriter.print(float)`, including `1.0E-4`. The three retained navigation
intensity tokens are individually bounded to 32 characters, matched as decimal or
scientific notation, and checked with exact decimal arithmetic for the closed
range zero through one. Nonfinite, malformed, repeated, oversized or out-of-range
fields remain unqualified; raw spellings remain unchanged. This avoids rejecting a
legitimate animation-state observation and does not round, interpret or replace
the independently measured screenshot contrast. New tests reproduce the prior
scientific-format rejection and retain ordinary/end-point positive controls.


### Launcher Taskbar observation after the current modal failure

The fixed `e947a23c` source ran as signed `0a1ca2a`, tree
`2cad77104aadd76d31a3eed0b2462fcff9e3f109`, in run39196075393,
installed job117880718926. The [independent original review5566890465](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/467#pullrequestreview-5566890465),
[second original review5566924067](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/467#pullrequestreview-5566924067)
and [source-owner qualification6259592161](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/467#issuecomment-6259592161)
bind original artifact12162198782: 1,069,644 bytes, SHA-256
`35b2dcf98e487f2dc68b94230610be4de752e49f28ffef6b693ed3a1bafa9395`.
All28 original members and eight raw diagnostic files were qualified. This
continuation relies on those attributed original-byte reviews; it does not
claim another local materialization or emulator run.

All four product cases passed. The two modal pairs still have dark glyph
RGB69/70/79 over RGB100/100/100: each of the three matched glyphs in each
modal has unrounded contrast1.5828461084972716:1. Ordinary05/07 remain
8.97386058887348:1. The original JUnit contains four passed product rows
plus the existing overall-failure row, five rows with one failure. Its
`environment_setup_or_cleanup` label does not identify a cleanup cause:
the independently qualified full ACL after-object equals the original
before-object, while child exit1 and the failed installed result remain.

The eight complete raw reads are sequential after each original PNG.
Modal WindowManager and SystemUI report no LIGHT_NAVIGATION request,
SystemUI navigation current/next intensity0.0 and no pending transition;
the visible navigation window belongs to Launcher3's Taskbar, PID1332 /
UID10122. These records do not include the actual Taskbar controller's
internal intensity/override values. They establish neither a forced-light
override nor an app callback or specific renderer defect. The previous
PNG file/ZIP times cannot reconstruct capture boundaries or synchronize
the host and guest clocks.

The independently reviewed [header donor a72eb23c](https://github.com/Pick-Your-AI/pick-your-ai-android/commit/a72eb23c5c0d5be9cc41b0b1d770f466ece08050)
admits the existing exact WindowManager prefix and exactly one preceding
LF, as observed in all four originals. It preserves the complete raw text
and its hashes, rejects unsupported prefixes, and leaves the failed
modal-pixel predicate unchanged. Its own original test-only baseline
has one causal failure and two controls passing; its source-owner full
host command has265 distinct passes. Those executed donor results
are separate from this diagnostic continuation's eventual checks.

#### Bounded observation of the actual navigation-window owner

The selected diagnostic addition uses the exact owned serial and:

```text
shell dumpsys -t 3 activity service com.android.launcher3/com.android.quickstep.TouchInteractionService
```

It follows each original05/06/07/08 PNG, existing WindowManager query and
existing SystemUI query, once per checkpoint. Each query retains its
5-second host budget, Android dumpsys3-second bound and262,144-character
decoded-text retention cap. The added four queries contribute at most20
requested timeout seconds; all12 queries total60 requested timeout
seconds. There is no polling, alternate command or post-failure retry.
These are retention limits, not a new subprocess stream-memory bound.
The screenshot15-second timeout, boot/readiness/rotation/case assertions,
owned cleanup and configured job timeout remain unchanged.

The [qualified proposal6259688443](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/467#issuecomment-6259688443)
traces the official Android15-r1
[TouchInteractionService dump](https://android.googlesource.com/platform/packages/apps/Launcher3/+/refs/tags/android-15.0.0_r1/quickstep/src/com/android/quickstep/TouchInteractionService.java)
through TaskbarManager and its context/controllers to
[NavbarButtonsViewController](https://android.googlesource.com/platform/packages/apps/Launcher3/+/refs/tags/android-15.0.0_r1/quickstep/src/com/android/launcher3/taskbar/NavbarButtonsViewController.java).
The qualified readers retain76107-byte/SHA256
`efdb0c50645b5c77ca417b1b69faaed4c459a591800bd64dff046ac2954e45a2`
and57876-byte/SHA256
`fd5ef3f4a12986334648be44ce29d589fcbcbed084c501e9a0e51ca1233e8fbd`
source files respectively. The publicly readable immutable
[898b9af9 reference controller](https://android.googlesource.com/platform/packages/apps/Launcher3/+/898b9af9a28663b5bd4ab19e1db8fc43adb5fb52/quickstep/src/com/android/launcher3/taskbar/NavbarButtonsViewController.java)
also exposes the same four numeric fields and color computation.
Neither reference is claimed as an exact source mapping for the actual
AE3A build12368160 image.

Admission requires one named running user0 Launcher TouchInteractionService
and its unique Client wrapper, followed by the nested TaskbarManager/context/
controllers/NavbarButtonsViewController chain. The context must retain its
explicit navigation mode, IME/setup/bind flags and integer window height.
Its own later DeviceProfile, at the context's prefix, must provide finite
positive density and px/dp dimensions plus explicit profile/Taskbar flags.
A later peer AssistStateManager heading establishes that TaskbarManager's
dump returned; it is not a whole-service footer. An earlier overview
activity's DeviceProfile cannot supply the context's fields. The selected
controller still requires explicit raw `mState` and one finite value in
[0,1] for each of:

- `mTaskbarNavButtonDarkIntensity`
- `mSlideInViewVisibleNavButtonColorOverride`
- `mOnTaskbarBackgroundNavButtonColorOverride`
- `mOnBackgroundNavButtonColorOverrideMultiplier`

Raw values are retained, including finite Java scientific notation;
missing, duplicate, blank, malformed, out-of-range or foreign-context
values remain unqualified. The parser does not predict successful pixels
from a tint value. A complete intensity0 with an active override could
support a different next hypothesis from a retained intensity1 while
SystemUI reports0; neither is an observation from the failed0a1 run.

Each raw Taskbar result has the same source/APK/serial/profile and original
PNG/XML hash binding as the existing two observations, a distinct
`.taskbar.txt` member, exact decoded-text SHA, command/status/elapsed
fields, and retained partial/error output. Missing service, stderr,
timeout, truncation or ambiguous state remains unqualified. Errors do not
erase later original checkpoints or substitute for a product-case result.
The four product cases finish before final image/observation qualification.

The same host process now records explicit `host_monotonic` start/end
intervals around each original screencap and each separate query.
Admission verifies finite ordered intervals within the existing15-second
capture/5-second query limits, in the exact PNG → WMS → SystemUI →
Taskbar order. It retains failure capture boundaries before rethrowing
the original capture failure. These numbers have no UTC or guest-clock
meaning; guest dump labels remain raw text. No clock-calibration query is
added, and no cross-clock subtraction or atomic-rendering claim is made.
The checks bound individual operation durations and their order; they do
not impose a maximum idle gap between operations or a capture-to-query
freshness ceiling. Actual recorded gaps remain part of the evidence.

The continuation's focused tests use the unchanged original444 bad
strips, explicit synthetic positive canvases and reference-shaped mocked
service text. A corrected installed modal remains required. The header
donor, reviewed62df padding source and any accepted-main carry keep their
separate source/execution attribution. No current run, fixed pixels,
physical-device matrix or release acceptance is granted here.


#### Taskbar subsection completeness correction

The [post-publication review6260149710](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/467#issuecomment-6260149710)
identified a real admission gap in the published41e/e792 source. The
signed298 attempt39202932375 was already executing and remained fixed.
Pure host replays of unchanged smoke blob
`ad0c57cf8df81b4035674b7414fb44486e9e56fb` admitted both Android service-dump
failure suffixes and a prefix ending immediately after the required
navigation values, even with the complete retained stream metadata and
its correctly rebound hashes. These witnesses use synthetic positive
pixels; they do not change or reattribute any installed original.

The exact Android15-r1
[ActiveServices selected-service dump](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/services/core/java/com/android/server/am/ActiveServices.java)
prints Client before forwarding application output and prints
`Failure while dumping the service:` or
`Got a RemoteException while dumping the service` to stdout when forwarding
fails. Its outer success status and empty stderr are insufficient. The
corrected parser rejects both markers anywhere in the retained Taskbar
text, in addition to the existing timeout/error markers, while retaining
that original text and its failure provenance unchanged.

The directly read immutable898b9af9
[TaskbarActivityContext](https://android.googlesource.com/platform/packages/apps/Launcher3/+/898b9af9a28663b5bd4ab19e1db8fc43adb5fb52/quickstep/src/com/android/launcher3/taskbar/TaskbarActivityContext.java)
dumps context fields, then nested controllers, then its own
[DeviceProfile](https://android.googlesource.com/platform/packages/apps/Launcher3/+/898b9af9a28663b5bd4ab19e1db8fc43adb5fb52/src/com/android/launcher3/DeviceProfile.java)
at the context's same indentation. The
[TouchInteractionService](https://android.googlesource.com/platform/packages/apps/Launcher3/+/898b9af9a28663b5bd4ab19e1db8fc43adb5fb52/quickstep/src/com/android/quickstep/TouchInteractionService.java)
can emit a separate earlier overview profile and emits AssistStateManager
only after TaskbarManager returns. These reference formats corroborate
the separately qualified Android15-r1 read; neither maps the actual
build12368160 binary to an exact source revision.

Only that selected context's later profile may satisfy admission.
Density and width/height/available-width/available-height retain their
literal finite positive float px/dp forms, including Java exponents.
The ten required booleans include both explicit Taskbar-present flags;
their values are recorded, not forced to an expected phone/tablet or tint
state. Context navigation mode must be a present bounded enum-shaped
token, its three flags must be explicit booleans, and its window height
must be an integer in Java's signed-int range. The height remains a raw
layout parameter: a negative match-parent value is not defaulted to a
pixel height. Unknown fields remain in the original dump.

The changed parser rejects missing, blank, duplicate, null, malformed,
mis-indented or foreign-context fields, missing profile/completion
boundaries, nonfinite/nonpositive dimensions and both wrapper failures.
It preserves the earlier overview profile as unrelated evidence.
No fixed whole-service success footer is invented: later
AssistStateManager/SystemUiProxy output is outside this Taskbar-subsection
claim. All capture/query commands, retention limits, timing intervals,
four product cases, original PNG/XML, decoder/contrast assertions,
rotation and ACL cleanup are unchanged.

The isolated source continuation's normal discovery ran all53 system-bar
methods with zero failures/errors/skips in8.110seconds, including all46
previous method bodies unchanged and seven new completeness methods.
Eight full-gate causal controls independently admit under unchangedad0c
and reject under the candidate, with the complete reference-shaped
synthetic positive admitted by both. Separate malformed/profile/flag
controls retain real blank-assignment and earlier-profile witnesses.
No new SDK/ADB/emulator or privileged command was executed for these
tests. Current hosted originals and any future corrected modal remain
separate receiving requirements.

#### Observed optional DeviceProfile scalar prefix

The [original298 compatibility read6260624912](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/467#issuecomment-6260624912)
replayed the immutable a0e completeness parser against all four unchanged
Taskbar streams. Checkpoints05,06 and07 qualified; checkpoint08 did not.
Its complete service/context/controllers, required own-profile fields and
later peer boundary were present. One unrelated line retained two literal
leading tabs without the service wrapper's four spaces:
`folderChildIconSizePx: 98.0px (56.0dp)`. The broad low-indentation test
mistook that scalar for a structural escape. Adding only the four wrapper
spaces in a separately labelled causal derivative admitted it; that
derivative does not replace the original25,648-byte stream
SHA256 `bf0509a34ae8a73a0d781df927b3968393295b7187cce194d69dc90ac20e5238`.

The follow-up admits at most one occurrence of that exact optional field
with the observed two-tab lexical prefix, only between the selected own
DeviceProfile and its completed peer boundary. Both px/dp values must
retain bounded finite positive numeric forms. Only that one unrelated
scalar may be excluded from the two broad indentation scans. The parser
does not normalize, discard or rewrite raw text, use this value as a
required field, or relax any required heading, field, depth, completion or
service-error check. A duplicate scalar, unexpected prefix, foreign
boundary, missing own required field, or either actual service forwarding
failure still rejects. Ordinary correctly prefixed optional fields remain
unrelated retained evidence.

Two focused methods cover the observed prefix in both space and mixed-tab
synthetic reference layouts, unchanged raw receipt objects, and eighteen
structural/value/error negatives. The surrounding reference layouts are
synthetic; only the stated scalar spelling and prefix come from the
qualified original. These new methods have not executed locally: the
first isolated source write failed when the workspace filled, followed by
sandbox startup ENOSPC before process creation. The earlier53-method a0e
result remains attributed only to that source. The new source requires
its configured host checks and a separately recorded parser-only replay
of all four unchanged original298 streams.

This compatibility correction does not alter the actual failed modal
pixels or grant installed acceptance. The original four passed product
rows and existing overall-failure JUnit row remain failed receiving
evidence. Query order/budgets, image thresholds, source/APK bindings,
rotation, viewport and ACL cleanup are unchanged.

#### Optional measurement data at a TransferPipe read boundary

The [complete original555 qualification6261810524](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/467#issuecomment-6261810524)
independently recomputed both navigation pairs from original PNG/XML bytes and
qualified all four product cases on exact555242a4 / e683cddb. Its separate
status-bar measurement used each original WMS42-pixel visible rectangle;
shown-dialog attributes omitted both DIM_BEHIND and BLUR_BEHIND. The overall job remains failed: the current parser admits
05/06/07 and rejects08 because a different unrelated own-profile scalar,
`desiredWorkspaceHorizontalMarginPx: 14.0px (8.0dp)`, retains only its two client
tabs. Original06 has the same property/value with the four wrapper spaces.
The old folder-specific exception therefore does not address the transport
boundary. The exact original ZIP remains1,137,755bytes, SHA256
`b0b2f023c5a0d4e0432c332298d4c77ee6e1dd5a2ef52a552f74a3c30a2baa1d`.
The full original qualification and new-source receiving gate remain separate.

The directly read pinned
[Android15-r1 TransferPipe](https://github.com/aosp-mirror/platform_frameworks_base/blob/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3/core/java/com/android/internal/os/TransferPipe.java),
Git blob `1c09bd6a2a66978a55447492ef9b29e598a778b4`,10,000bytes,
provides a concrete reference mechanism. Its1024-byte read loop does not reset
`needPrefix` when a continuation read starts with LF. If the preceding read
ended before that LF, the next client line can lose the wrapper prefix. This
is a byte-framing property, not a property-name exception. The source is a
reference; it is not an exact installed-binary identity.

The parser now recognizes optional measurement-shaped data in the completed
own-profile interval: exactly two tabs, a bounded lower-camel identifier ending
in `Px`, and the existing bounded finite-positive px/dp syntax. Per-key
occurrence counts are collected once; two linear passes avoid rescanning the
entire section for each property. Distinct optional measurements may coexist,
but a repaired key duplicated with either lexical prefix remains unqualified.
Raw lines and all original hashes stay unchanged.

The four required dimension names are explicitly excluded from this allowance.
An outlying line cannot supply density, a required dimension or boolean,
context/controller fields, any heading or the later peer. Their original exact
depth, uniqueness and value checks remain. Wrong prefixes, malformed or
nonpositive measurements, structural escapes, missing boundaries and both
service-forwarding failures still reject. This is an allowance for ignorable
positive measurement data, not general whitespace repair or a claim of complete
service output. It changes no product appearance, pixel threshold, image/data
binding, command/budget, rotation, viewport or ACL behavior.

All55 prior method bodies are retained. Three new methods cover both observed
properties, a clearly synthetic additional measurement, simultaneous optional
measurements, authoritative-field and malformed-input controls, and a direct
in-memory transcription of the pinned TransferPipe loop. The reference framing
control produces the exact lost-prefix form by moving a read boundary across
one LF; it opens no process, pipe, device or network. Normal local discovery
ran all58 methods in15.103seconds with zero failures/errors/skips. The same three
new methods, executed against unchanged4007 and then this candidate, produce
seven causal subcase failures on the unchanged parser and zero failures on the
candidate. The correctly prefixed reference remains a passing control; the
required-field/malformed-input method passes under both implementations. All55
previous test bodies are byte-identical. These are host-only synthetic receipt
and image-fixture checks, without SDK/ADB/emulator or privilege execution.

All four unchanged original555 parser replays and a new configured receiving
result remain separately required. The earlier efe55-pass
qualification6260931149 remains attributed to its own source and original298
streams; it does not qualify this continuation automatically.

#### Finite optional scalar correction after original555 replay

The [exact3e0 original replay6262252847](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/467#issuecomment-6262252847)
admits all eight unchanged555/298 Taskbar streams with the initial72ec parser,
while both original failed receipts remain failed. The same qualified reader
reproduced five additional prefix-only compatibility failures using valid
measurements from original555: iconSizePx and mHotseatWidthPx at zero,
taskbarHeight at zero, workspacePadding.bottom at135px, and windowX at zero.
These are explicitly synthetic derivatives of unchanged original data, not
new installed captures. The TransferPipe prefix mechanism does not depend on
the field name or the sign of its dimensional value.

The final rule therefore admits finite optional measurements, including zero
and signed values, with a bounded lower-case/camel-case dotted property name.
Names contain at most64 ASCII characters; each dot separates nonempty
lower-case-initial alphanumeric components. Each px/dp token retains the
existing32-character literal finite-number limit. The allowance still requires
exactly two client tabs inside the completed own DeviceProfile, and a repaired
name must occur exactly once across prefixed and unprefixed forms. It does not
normalize original bytes or reinterpret any scalar as authoritative data.

Every existing required name is explicitly excluded, including the four
dimensions, profile flags, context state and controller fields. Required
density and dimensions remain strictly positive with their exact original
depth and uniqueness checks. Names with empty/foreign/oversized components,
nonliteral/nonfinite values, duplicates, missing fields, structural escapes,
incomplete sections and forwarding errors remain unqualified.

The two old optional zero/signed negative expectations and the three3e0
name/sign restrictions move to explicit positive coverage; their surrounding
structural and malformed-input controls remain. Two new methods cover all
five qualified observed spellings, those precise formerly rejected values,
a clearly synthetic signed dotted offset, all authoritative names and further
malformed/duplicate/oversized controls. All other56 prior method bodies remain
exact. The3e0 checkpoint and its actual58-pass/seven-causal-failure records are
preserved at their original source identities. Final host results and all eight
unchanged-original parser replays are recorded separately before accepting
the one new configured receiving result. No product/image/timer/ACL change is
introduced.


## Fixed-raster navigation readiness before installation

The original receiving preparation `3fc46164018635c791e8966979ae59cfae4309a3`
(source `d7904025150015474998f927260444ee13a30bc2`, run `39220377892`)
passed its four product cases but failed the installed navigation evidence gate.
All four original Launcher service observations explicitly reported
`TaskbarActivityContext: null`; the screenshots and window observations had no
navigation provider. The earlier retained HOME observation already lacked that
provider **before the preview package was installed**. Both that run and the
earlier 555 preparation had explicitly observed user 0 `RUNNING_UNLOCKED` and
completed the framework/application barrier. These facts do not establish a
missed callback or the precise cause of Launcher initialization failure.

For the selected `raster-720x1280-280` profile, startup now follows the existing
user/storage checks and one framework/HOME sequence with a bounded navigation
readiness observation. It uses the same owned serial and the **same original
180-second absolute readiness deadline**. Each attempt obtains the existing
complete HOME activity/window query and the complete Launcher
TouchInteractionService query, with at most five seconds for each command and
no more than 36 attempts, separated by at most five seconds. Every attempt,
including negative state and partial/error command evidence, stays in the
receipt. Transport errors, timeout, truncation, printed service-dump failures or
loss of HOME focus fail the readiness boundary; they cannot be rescued by a
later installed case.

A qualifying attempt must observe the fixed default display, its visible
84-pixel bottom navigation source, matching InsetsState/provider source and
frame, and the same Taskbar window in display policy and provider ownership.
The provider must have a ready leash and no pending position; its historical
`mInitiallyVisible` control flag is not substituted for actual source
visibility. The same attempt must separately observe the three initialized
TouchInteractionService peer sections in order and the existing fully qualified
Taskbar context, controllers, own DeviceProfile and subsequent AssistStateManager
boundary. An earlier overview profile cannot supply the Taskbar profile.
The observations are sequential and do not establish simultaneous rendered
state or a correspondence to a particular AOSP source revision.

The receipt binds this admission to its source commit, unsigned APK, selected
profile and pre-installation phase. The whole gate also requires the recorded
original guest start, deadline and timeout to match the navigation observations,
so its remaining budget cannot be represented as a new 180-second interval. The current whole-receipt gate requires it
whenever installed image evidence is required or present. Historical receipts
without image evidence retain their previous admission, and the separate
`pixel-2` path does not acquire fixed-raster geometry requirements.

The observation loop performs no HOME launch, service reset, framework setting
change or repeated app case. Its deadline failure leaves the app cases unrun and
retains the original cleanup path. The existing post-install screenshots,
dialog checks, rotation checks, navigation geometry and **3:1** minimum contrast
remain required. This source correction and its host tests do not make the
original 3fc or 555 whole receipts pass; the final receiving source still needs
its own configured native run and original artifact qualification.

## Current-main autosave scenario reconciliation

Accepted main `e7c8d98302e0b6620830d0e9bd9846dcc201b944` introduces the reviewed
750 ms automatic valid-form save through the existing canonical store. Its
`draft_unsaved` text also replaces the earlier explicit-save-only sentence.
The preceding installed source `89c9e67e /68eaaf9f` and actual signed
`78f401a9` run retain their own case semantics. That run's configured checks
passed; the current original-artifact qualification is separately pending. No
prior screenshot, receipt or passing case is assigned to this new composition.

The four case IDs remain, with the following explicit current-product inputs:

1. **Installed synthetic launch** retains the exact package, launch, source,
   active-draft and visible editor checks.
2. **Acknowledged save survives force-stop** enters the complete fixed valid
   title, objective and context, then requires the actual **Saved locally.**
   acknowledgment and disabled Save control. It uses the product's automatic
   valid-form save, records `save_trigger=automatic-valid-form`, and does not race
   a manual button click against that timer. The original force-stop, distinct
   process identity, complete three-field cold-reopen and acknowledgment checks
   remain. This is automatic-save evidence; it does not claim an installed
   manual-save action occurred.
3. **Pending fields survive rotation** enters the fixed literal `invalid` into
   **Requests per day**, then requires that complete exact raw value, the visible
   invalid-workload explanation and disabled Save. Only then
   are the pending title/objective suffixes entered. Existing validation excludes
   this incomplete form from manual and automatic persistence; the harness
   changes no autosave setting, scheduler or application clock. Both strict
   default-display rotations must retain the complete pending fields, exact
   invalid raw input, invalid-workload explanation, current unsaved-state text
   and disabled Save. The receipt records
   `pending_input_constraint=invalid-workload-requests`.
4. **Confirmed deletion does not resurrect** still requires the library row to
   show the previously acknowledged title, an explicit confirmation, actual
   deletion acknowledgment, retained pending title/objective/context and invalid
   workload text, disabled persistence controls, an empty library and a cold
   relaunch that cannot reopen or resurrect the deleted draft.

All eight original checkpoint identities remain. In particular, the original
05/06 and07/08 PNG pairs continue through the unchanged fixed-rig pixel and
window/provider gates. Their filenames retain historical “pending” wording:
these new pending edits are deliberately invalid and are never presented as
acknowledged stored input. No image threshold, readiness predicate, launch or
rotation deadline, subprocess limit, failure diagnostic, source/APK pin or ACL
grant/restoration path changes. Failures still stop the current case, leave later
cases unrun and retain the original failure before later diagnostics.

The isolated host controls exercise the complete scenario against a modeled UI
whose one-second observation steps cross the product's750 ms debounce. All ten
methods pass. The same positive journey run against unchanged89c fails at the
disabled manual Save action; the candidate passes. Negative controls retain
failure for absent acknowledgment, wrong cold-reopened data, a nonempty initial
numeric editor, an incorrectly enabled Save, lost invalid or pending text on
rotation, a wrong saved library snapshot, lost invalid input after deletion and
deletion resurrection. These are host scenario/control results, not execution of
Kotlin, Android, the emulator or actual pixel validation.

Three previously authored native modal/focus methods inspect pending valid
fields before autosave is due. They now select the already accepted
`AutosaveUiClock` immediately before that edit and assert elapsed uptime stays
below750 ms. Existing shared wait/frame helpers use its ordinary-mode-preserving
`idle()`; every original modal, pending-value, selection, saved-baseline,
recreation, cancellation and system-bar assertion remains. This fixture clock
does not run in the installed app or harness, and the candidate native methods
remain unexecuted locally. The configured native task must qualify their exact
new source before the installed job is eligible.

The accepted catalog/autosave/current-main source reviews remain separately
attributed. The combined source, configured execution, original artifacts and
ordinary fresh receiving guards are required for final delivery; this section
does not close an issue, establish physical-device behavior, or convert the
earlier missing-navigation or failed-contrast originals into successes.

### Empty input and accessibility hint correction

The first autosave-compatible receiving source `948f3d58` executed as signed
`8c1a9ec663fb082289394b256ba65f1c5d49533f`, tree
`a096e3189de2dc6c586e0a0862065978a360b61a`. Core, Preview and scanner jobs
passed, but installed job 118023567630 in run 39241000776 failed at
2026-10-19 11:07:47 UTC. Its decoded log reports
`The synthetic pending-input control must start unanswered`.
Original artifact 12181822552 is 565,371 bytes, SHA256
`794ea0a2d71c94318974791c11154cfb917b4ec85dcd862aa548f34bdf7466d5`.
That run remains failed. Its actual Requests-per-day XML value and complete
original case, image and ACL qualification were not available when this
source correction was prepared; no specific observed value or cause is inferred.

The existing WorkloadSection sets the empty editor's hint to **Unanswered**.
[Android 15 TextView at immutable 4e43ad12](https://github.com/aosp-mirror/platform_frameworks_base/blob/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3/core/java/android/widget/TextView.java)
sets accessibility node text through `getTextForAccessibility()`, which returns
the hint when the underlying text is empty. The independently retrieved source
blob is `f7e0ec8158f4e359457cb294d978b7e2d313aacb`. Thus requiring empty
accessibility text before entering the fixed synthetic value is not a valid
empty-input test, independently of the still-unverified 8c observation. The
reference does not establish exact guest binary/source equivalence.

The correction removes only that premature two-line assertion. The existing
input command still appends the fixed literal, and the immediately following
fresh editor observation must equal exactly **invalid**. It does not accept
hint words, discard prefixes, clear unknown input or assume replacement. A
nonempty underlying `1` or literal `Unanswered` becomes `1invalid` or
`Unansweredinvalid` and fails before either pending title/objective suffix is
entered. The invalid-workload message, disabled Save, exact complete pending
fields, both locked rotations, original saved-library row, deletion,
non-resurrection, pixel/provider and ACL gates remain unchanged.

The corrected host UI model separates empty raw input from its displayed hint.
All 12 methods passed in 0.012 seconds, with no failures, errors or skips. A
separate paired replay uses the same model against unchanged 948 and the
correction: the hinted empty value fails the old premature assertion and
completes all four cases with the correction; the unhinted empty control passes
both. Both sources reject actual nonempty numeric and literal hint values. The
correction observes their complete appended values and stops before pending
title edits. The other negative controls still require failures for lost saved
or pending data, an incorrectly enabled Save, wrong library content and
resurrection after deletion.

These are host-only scenario results, with runtime process, fork and signal
calls prohibited by the controls. They are separate from the earlier
ten-method empty-text model and its hosted 311-method result. This source change
does not turn any earlier installed failure into a pass or replace the next
actual configured receiving and applicable original-output qualification.

After this source was prepared, the independent original reader returned
[qualification 6266220712](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/467#issuecomment-6266220712)
for that same 565,371-byte archive and verified all 13 members. The retained
`failure.xml` reports **Unanswered** in the exact Requests-per-day editor; its
SHA256 is `54aef762a8d414fbf6e6b89dda3ac98295064062e58a65e9e1adb470df8314ec`.
The reader also inspected the matching original PNG and found grey Unanswered,
the saved acknowledgment and disabled Save. Both failure files equal their
respective 03-cold-relaunch originals. JSON and JUnit agree on two passed cases,
the pending-input case failing at the initial guard, and deletion not run.
The complete ACL before and cleanup-after objects match, with child exit 1 and
successful quiescent restoration. This is attributed original qualification,
not another ZIP download or byte audit by the correction author. The XML does
not expose the internal Editable value; it establishes the representation
rejected by the old guard without assigning a persistence defect. The actual
8c run remains failed, and no rotation, deletion or new-source pass is inferred.

### Optional Taskbar inset record prefix correction

The actual signed receiving commit `fb621e769594f147f35779699af0a2c59b6e4e80`
(tree `b29cdb1f3bebff00b1c218e6035e7122b04c286c`) failed the final installed
evidence gate in run 39247758487. The complete
[original qualification 6267487148](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/467#issuecomment-6267487148)
binds artifact 12186225289 to 1,117,873 bytes and SHA256
`ba612437bddd0a573001b6a2534670275928fea0dcd2916eda2541a8a44e41be`.
That original run remains failed.

Its four product cases passed, including acknowledged automatic persistence,
the exact invalid raw workload during pending-input checks, both locked
rotations and deletion without resurrection. Both original navigation image
pairs passed the unchanged fixed-rig pixel gate at contrast
8.97386058887348:1; grant/restoration records returned the complete ACL
observation to its original state. JUnit retains four passed product rows and
the separate overall-failure row, for five entries and one failure. These
case, image and cleanup findings are attributed to that original qualifier.

The isolated rejection is in `taskbar_state_fields_pass`. Original05/06/07
Taskbar stdout is byte-identical, SHA256
`7f6fe68e171c640205f60b71a5b519c7fad92af146ca2c75f34ad32e8d4d36c2`.
One complete optional `tappableElement` record inside
`TaskbarInsetsController` starts with four literal TABs, without the four
SPACE wrapper prefix present on adjacent records. The original parser mistakes
that low indentation for an escape from the selected Taskbar context. Original08
has the ordinary prefix and passes. The qualifier's explicitly derived control
adds only those four spaces and makes the three rejected streams pass; neither
that derivative nor a reconstructed pre-final-error receipt changes the
original run's failed status. No original text is rewritten by this correction.

The immutable corroborating
[Launcher3 TaskbarInsetsController.kt at 898b9af9](https://android.googlesource.com/platform/packages/apps/Launcher3/+/898b9af9a28663b5bd4ab19e1db8fc43adb5fb52/quickstep/src/com/android/launcher3/taskbar/TaskbarInsetsController.kt)
prints one navigation, tappable and mandatory-gesture provider and two
system-gesture providers. The non-system providers have the two typed overrides
for INPUT_METHOD and VOICE_INTERACTION. Its printer closes the outer record
only in the non-null override branch, so the observed system-gesture lines
legitimately end after the Insets closing brace. This primary source supports
the finite record grammar; it does not establish exact guest/source identity.
Every source line receives the same passed-in prefix. The actual reason one
retained line lacks the wrapper spaces remains unknown.

The correction admits that four-TAB form only for a complete optional record
inside the unique own Insets controller, after the selected Navbar controller
and before the next controller boundary and this context's own DeviceProfile.
It checks the four provider types, their finite occurrence limits, canonical
signed 32-bit Insets values and the two expected override types. Missing,
malformed, duplicate, foreign or displaced records cannot use the exception.
It does not normalize text, infer values, replace an authoritative field or
treat optional Insets as proof of an attached or visible navigation window.

The service identity, context/controller ownership, required fields and their
exact depths, own DeviceProfile, subsequent AssistStateManager boundary,
failure markers, command/raw hashes, capture ordering and all pixel thresholds
remain unchanged. No image, window, provider, readiness, case, timer, rotation
or ACL requirement is relaxed.

Eight new host methods cover the complete gate and raw immutability, finite
signed optional values, malformed grammar/prefixes, provider counts,
controller ownership, every required field, completion/transport failures and
the retained older bad-modal pixels. All 29 Taskbar-focused methods passed,
with zero failures, errors or skips. The complete test module declares 68
methods, preserving all 60 existing method bodies; the other 39 methods were
not rerun in this focused check. The paired unchanged-source run retains nine failing subcases across the two
positive methods; those same two methods pass with the correction. The normally
prefixed control passes both sources. A replay against all four unaltered
original streams remains separate and was not completed at this source freeze. A future configured receiving run must still
qualify its own exact source and originals; this source correction cannot
convert the earlier installed failure into a pass.
