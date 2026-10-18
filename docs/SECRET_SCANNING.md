# Pinned source-history secret scanning

The `Pinned secret scanning` job scans the current checked-out commit and its
reachable ancestors with the standalone Gitleaks CLI. This is a bounded
implementation of [Android #69](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/69).
It adds a source check to this Android repository; it does not complete the release
dependency inventory, license review, private reporting or vulnerability-response
work package.

## Inputs and execution

| Input | Exact pin |
| --- | --- |
| CLI release | [Gitleaks 8.30.1](https://github.com/gitleaks/gitleaks/releases/tag/v8.30.1) |
| Linux x86_64 archive | `gitleaks_8.30.1_linux_x64.tar.gz` |
| Archive SHA-256 | `551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb` |
| Published checksum-list SHA-256 | `061476c21adaf5441516f96f185c1a4706a83cd6329b9b38762271b3d4a52fae` |
| Extracted binary SHA-256 | `88f91962aa2f93ac6ab281d553b9e125f5197bbbce38f9f2437f7299c32e5509` |
| CLI license | [MIT at v8.30.1](https://github.com/gitleaks/gitleaks/blob/v8.30.1/LICENSE) |

The driver verifies the pinned checksum list, its exact archive entry, the archive
bytes, extracted binary bytes and executable version. Only the archive's selected
regular `gitleaks` and `LICENSE` entries are read into fixed paths in a new temporary
directory. No archive path or symbolic link is extracted. The license stays beside
the temporary CLI. The CLI is a development tool; it is not packaged into the APK.
This uses the standalone MIT tool, with no commercial action, license key or paid
scanner service.

The workflow uses the existing pinned checkout/upload actions on an Ubuntu 24.04
standard runner, with `contents: read`, no persisted checkout credentials and an
eight-minute job cap. Scanner calls have a 120-second internal timeout and a
140-second process deadline. A missing download, checksum mismatch, unsupported
host, tool error, missing report, malformed report or inconsistent exit/findings
result fails the check. Findings use a distinct scanner exit code of 10; other
nonzero scanner exits remain execution failures. The CLI runs at explicit error
log level, and either nonempty captured stream fails the scan even when its exit
and JSON report otherwise look successful. The pinned CLI can log a Git-source
read failure yet return exit 0 with an empty report; that is not a complete scan.
Raw diagnostic content remains private and is replaced by a fixed failure message.

`fetch-depth: 0` supplies reachable history. The driver rejects shallow history,
changed tracked source, a driver not present at the recorded commit, or a mismatch
between the workflow SHA and checkout. It records the exact source commit/tree and
reachable commit count. Gitleaks receives `--full-history --diff-merges=separate
--text --no-ext-diff --no-textconv HEAD` as its Git log options. Explicit per-parent merge
diffs include values introduced only by a merge resolution; the default Git patch
log can omit them. Forced text patches prevent repository `-diff`/`binary`
attributes or automatic binary classification from hiding contents behind a
`Binary files ... differ` marker. External diff and text-conversion helpers are disabled. History
unreachable from this checkout, other branches, developer-local untracked files
and external services are outside the scan. A deletion from the latest file does
not remove a finding in its reachable history.

The driver explicitly uses the built-in rules from the pinned CLI, an empty
temporary ignore file and `--ignore-gitleaks-allow`. Repository configuration,
inline allow comments, local baseline files and configuration environment overrides
cannot silently suppress findings. Before each normal scan, any `.gitleaksignore`
entry at the source root is rejected, including a directory or dangling symbolic
link; unreadable entry metadata also fails. This is necessary because the pinned
CLI additionally loads that source-root file despite an explicit ignore-file path.
The empty override alone does not disable it. There are currently no accepted exceptions.
Any future false-positive or revoked-history disposition requires a separate
independently reviewed change with exact immutable finding identity and its evidence;
it must not quietly make the scanner accept arbitrary bypass comments.

## Live detection and removal guard

Each scanner run creates one harmless, unissued token-shaped fixture inside the
private temporary directory. It is generated from a fixed synthetic label, is never
published to project history, authenticates to nothing and is not sent to a
credential-validation endpoint. An inline allow comment is deliberately included.

The real CLI must return exit 10 with exactly one `github-pat` finding despite that
comment. A deliberately isolated upstream probe then adds the fixture's exact
fingerprint to its own `.gitleaksignore`: the pinned CLI silently returns 0 with no
finding despite the empty override. The normal driver boundary must reject this
same private fixture before invoking the scanner. The ignore entry is removed,
then the fixture value is replaced with `removed`; a separate real scan must return
exit 0 with no finding. These are observations of the pinned upstream behavior and
our explicit rejection policy, not authorization to ignore project findings.

A second temporary fixture creates two empty parent
commits and a merge that alone introduces the unissued token. The real history scan
must identify the exact merge/line/rule. Those explicitly synthetic Git objects have
no remote and are never published as project contributions. The pre-correction
default patch log incorrectly returned no finding for this actual merge fixture;
the corrected merge-diff command detects it. These are seeded pattern and history
coverage checks, not a production credential-revocation exercise.

Another private Git fixture has two token-bearing files: plain text explicitly
marked `-diff` by its own `.gitattributes`, and a NUL-bearing file automatically
classified as binary. The real scanner must find both at their exact commit,
file and line. Independent reproduction without `--text` returned exit 0 with no
findings; the corrected command returns exit 10 with both findings. No project
attribute or binary is changed. The primary [Git attributes documentation](https://git-scm.com/docs/gitattributes/2.50.0)
describes binary diff suppression and the textual override. This pattern check
does not imply decryption, archive expansion or discovery of every encoded secret.

The final real fixtures exercise execution failures. A nonrepository and a new
two-commit bare repository whose clean tip has a missing reachable parent blob
both cause this pinned CLI to emit an error but return exit 0 and an empty report.
The normal driver must reject both through its empty-stream requirement. The
missing object is deleted only from this new private fixture, which has no remote;
its reachable commit count still succeeds and equals two. No project object is
changed. These fixtures prevent a successful commit count or empty findings list
from concealing unreadable historical source.

The relevant pinned upstream implementations are
[implicit source ignore loading](https://github.com/gitleaks/gitleaks/blob/v8.30.1/cmd/root.go)
and [logged source errors](https://github.com/gitleaks/gitleaks/blob/v8.30.1/detect/detect.go).
Independent review reproduced both behaviors with the actual pinned binary before
this driver was published. No raw error output or unissued fixture value is retained
in the public receipt.

Before source publication, the exact downloaded binary passed that live fixture
sequence. A separate actual history scan at Android commit
`14a89f088e477c2dff3c455a27994faf75d36f69` returned exit 0 and no findings. Those
preparation observations do not replace the hosted run of this new driver at its
published candidate and final main versions.

Nine host guard tests reject modified/oversized scanner bytes, unsafe or malformed
report shapes, missing/stale/oversized or inconsistent reports, alternate ignore
configuration, source-ignore entries and unreadable metadata, nonempty execution
streams, shallow/dirty source and workflow mismatch. They also verify that
matched text and author/message fields are not copied to the retained report. These
tests do not substitute for the real CLI fixture and history execution above.

## Retained evidence and finding triage

`build/security/secret-scan.json` records source identity, scanner pins, actual
fixture results, scan exit/count, elapsed time and explicit scope limitations. For
a finding, only the rule ID, file, starting line and exact commit are retained.
Matched source, secret values, complete lines, commit messages, author/email and
arbitrary report fields are excluded. Raw CLI streams are captured in memory and
not printed; raw JSON stays in the temporary directory and is removed on exit.
The CLI also receives `--redact=100` as a separate precaution.

The seven-day `secret-scan-<workflow commit>` artifact contains only that sanitized
receipt. The ordinary log prints the overall result and receipt path. Inspect the
job status together with the exact receipt before calling a version clean; an
absent or failed report is never evidence that no finding exists. Pattern detection
can miss credentials and can report false positives.

For a finding, the project maintainer reviewing the failing PR uses the recorded
immutable file/line/rule to classify it without reproducing a value in public
comments. A confirmed issued credential requires its authorized credential owner
to revoke or rotate it, assess use and document containment through the applicable
private process. Removing a string from current source alone cannot establish that
the credential is unusable. Any historical exception must carry the actual
containment or false-positive evidence and independent review before integration.
No automatic exception or history rewrite is performed by this check.

The corrected candidate must pass the source guard tests, real scanner fixture,
history check, existing core/native checks and independent review. A fresh main
receipt then binds the delivered source. Those checks produce the existing unsigned
diagnostic build; distribution under a product signing identity retains its own
release gates. A monitored private intake channel, an actual urgent-update/rotation
exercise and a distributable corrected release are not established here.

## Local reproduction and updates

On Linux x86_64, from a clean full checkout of the published driver:

```sh
python3 -m unittest discover -s scripts/tests -p 'test_check_secrets.py' -v
python3 scripts/check_secrets.py
```

The driver downloads only its pinned scanner release/checksum list. The scan and
fixture processing run locally; they are not configured to validate credentials
against live accounts. No match-containing report is uploaded by this driver.

For a scanner update, inspect the official release, CLI behavior and license at the
new tag, compare the published asset digest/checksum list, verify the downloaded
archive and extracted executable independently, update the explicit pins in source,
and repeat both negative guards and the real seeded/removal/history execution.
Retain the exact reviewed PR and hosted evidence. Do not automatically follow a
`latest` download or accept new bytes under an old pin.

Observed check execution is distinct from required-status enforcement. Android
main's last recorded readback showed protection disabled and no required checks;
[Android #59](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/59#issuecomment-6246184712)
keeps that gate open. This change alters no repository protection or website gate.

The canonical receiving path remains
`docs/delivery/android/work-packages/14.06.md` in `Pick-Your-AI/pickyour.ai`.
This source/run documentation does not relocate the acceptance record or claim
whole-issue completion.
