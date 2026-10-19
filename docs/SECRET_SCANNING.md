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

Empty diagnostics alone cannot establish that the Git history child succeeded.
An actual private counterexample at source `4c753bd69f3857d9b15051be86078948e61074ac`
made that child silently exit 73 while the pinned CLI returned exit 0, no findings
and empty streams. The corrected driver also requires a new private
[Git Trace2 event stream](https://git-scm.com/docs/api-trace2) for each history scan.
Exactly one version-4 session must identify the exact repository and unchanged
history command, with ordered `version`, `start`, `exit` and `atexit` control
events, successful integer exit codes, and `atexit` as its final event. Separate
remote-metadata lookups cannot substitute for the history process. Missing,
truncated, ambiguous, failed or malformed completion evidence fails with a fixed
message. The parser bounds total bytes, individual lines, sessions and control
events; it never copies raw trace content into a diagnostic or retained receipt.
This counterexample establishes a driver defect, not a historical failure of an
earlier project scan.

`fetch-depth: 0` supplies reachable history. The driver rejects shallow history,
changed tracked source, a driver not present at the recorded commit, or a mismatch
between the workflow SHA and checkout. It records the exact source commit/tree and
reachable commit count. Scanner traversal and source metadata disable local Git
replacement objects, matching the immutable checksum-evidence reader. Replacement
refs cannot substitute a different source tree or ancestry for those checks.
Gitleaks receives `--full-history --diff-merges=separate
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
The empty override alone does not disable it. The only reviewed content disposition
is the exact public contract checksum described below; it does not enable any source
ignore file or rule suppression. Any additional false-positive or revoked-history disposition requires a separate
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

The same temporary history is scanned again with local replacement metadata
present. Its original finding identities must remain identical. A separate real
Git regression checks that source metadata still names the literal tree and
reachable commit count. These fixtures never change project references.

Another private Git fixture has two token-bearing files: plain text explicitly
marked `-diff` by its own `.gitattributes`, and a NUL-bearing file automatically
classified as binary. The real scanner must find both at their exact commit,
file and line. Independent reproduction without `--text` returned exit 0 with no
findings; the corrected command returns exit 10 with both findings. No project
attribute or binary is changed. The primary [Git attributes documentation](https://git-scm.com/docs/gitattributes/2.50.0)
describes binary diff suppression and the textual override. This pattern check
does not imply decryption, archive expansion or discovery of every encoded secret.

Further real fixtures exercise execution failures. A nonrepository and a new
two-commit bare repository whose clean tip has a missing reachable parent blob
both cause this pinned CLI to emit an error but return exit 0 and an empty report.
The normal driver must reject both through its empty-stream requirement. The
missing object is deleted only from this new private fixture, which has no remote;
its reachable commit count still succeeds and equals two. No project object is
changed. These fixtures prevent a successful commit count or empty findings list
from concealing unreadable historical source.

A final clean, remote-free fixture exercises silent Git failure through the real
CLI. Both history and directory scans must pass before a temporary private `git`
wrapper is placed on that fixture's process path. A direct probe must establish
exit 73 with no output; an invocation marker must prove the real scanner used the
same failing history child. The driver must reject it specifically for missing
successful Git completion. The original process path is then restored and both
history and directory controls must pass again. No project Git executable or
object is modified. Only the established fault exit and rejection/control flags
enter the sanitized fixture results.

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

Host guard tests reject modified/oversized scanner bytes, unsafe or malformed
report shapes, missing/stale/oversized or inconsistent reports, alternate ignore
configuration, source-ignore entries and unreadable metadata, nonempty execution
streams, shallow/dirty source and workflow mismatch. Completion tests exercise
missing, failed, signaled, reordered, duplicate and post-shutdown events; wrong
repository, history scope, merge policy, session, format or thread; stale trace
entries; malformed or unreadable JSON; and resource limits. Clean and detected
reports both require successful history completion. The tests also verify that
matched text and author/message fields are not copied to the retained report. These
tests do not substitute for the real CLI fixture and history execution above.

## Retained evidence and finding triage

`build/security/secret-scan.json` records source identity, scanner pins, actual
fixture results, scan exit/count, elapsed time and explicit scope limitations. For
a finding, only the rule ID, file, starting line and exact commit are retained.
Matched source, secret values, complete lines, commit messages, author/email and
arbitrary report fields are excluded. Raw CLI streams are captured in memory and
not printed; raw finding JSON and Git traces stay in the temporary directory and
are removed on exit.
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

### Exact public contract checksum disposition

The original [#451](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/451)
source `4df1006fd5204989456deb24fda1f116289b6491` and its signed prepared receiver
`46b0056a370b9472acf84196a8782b0cf2a34ae7` both produced one `generic-api-key`
finding at line 5 of `tests/fixtures/android-api-consumer-intake-v1.json`.
That line records the SHA-256 of the already public frozen OpenAPI document.
It is an integrity checksum, not an issued credential or an authentication claim.
The fixture and its metadata key remain unchanged.

The original [scanner run](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39169746580)
is still failed. Artifact `12151314101` has ZIP SHA-256
`47cfe22a999f073cd7c74aef7ed09d544e036a502541049378684a95de5b0a7b`;
its sanitized receipt has SHA-256
`6ecdad3a8260f63c6da4ceed14c248609a3c30ecf893408d58576d2fcf874d80`.
It records raw exit 10, both immutable findings, 171 reachable commits and the
passing seeded/history/error controls. The original failure is not replaced or
relabeled by this later policy. Independent byte inspection proved that both
finding commits contain exactly the accepted frozen inputs from signed main
`e8241ef4ea75071339e6a945e48fc6d433819ffa`.

The disposition `android36-public-frozen-contract-checksum` requires all four
complete regular-file objects at each actual finding commit:

| Input | Git blob | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| `tests/fixtures/android-api-consumer-intake-v1.json` | `d2f15c794bf5a4b73b6dbb843006434f0bac84e2` | 17,594 | `48df93adc749ef07f81abe37e4d54a2a412558d0fd8c305ec6a3ab36b29e1879` |
| `contracts/v1/api/openapi.yaml` | `117483451e00ac7d87260565b509f81bf383e405` | 12,079 | `6a08d7230c23576889167da6cff48d95093da9dc04d4bfa6ece443ff35089a79` |
| `contracts/v1/IMPORT_MANIFEST.json` | `955704740fa9b16eb9b319726bfcb9ed1f8bbf47` | 8,426 | `ef6d5801eec10f3169e2bf84653e7026b33088e305a987991ec52faadcd7f554` |
| `contracts/v1/UPSTREAM_SHA256SUMS` | `26fd660b5161a9b93607bab7d3279991a2e90fa4` | 4,044 | `0587a133436268bc03684ad61ff29f3bd1a12eb83db8a497eabdbb8fd8803e15` |

The rule, path and starting line must also match exactly. Git reads disable
replacement objects and require a real commit reachable from the recorded source
HEAD, mode `100644`, the exact blob ID, exact byte count and full SHA-256 for every
input. The fixture's fifth line is separately hashed, and its checksum must equal
the independently hashed schema and the unique entry in the pinned upstream
checksum list. A different fixture byte, file mode, object, provenance input,
rule, path or line cannot receive this disposition. Even an unrelated edit to the
fixture needs a newly reviewed content identity. Identical reviewed bytes carried
through a later ordinary merge or signed squash may qualify, but that finding's
actual immutable commit is always recorded; the original two commit IDs are never
substituted for a new occurrence.

The added evidence reads have five-second process deadlines and a 64 KiB output
bound, with at most 32 distinct reviewed finding commits per scan. Unknown,
duplicate, malformed or inconsistent finding metadata fails closed; an unknown
well-formed finding remains unresolved. Source-provided allow comments, ignore
files, generic path/rule exceptions and automatic policy updates remain forbidden.
The scanner's existing rules, Git history command, report limits, private Trace2
limits, disclosure boundary and seeded controls are unchanged.

The CLI still scans every reachable patch and returns its actual raw result.
`scan.exit_code`, `scan.finding_count` and every sanitized `scan.findings` entry
remain intact. The separate `triage` object records reviewed findings with their
content identity and unresolved findings; their counts must account for all raw
findings. `passed` still means zero raw findings. The distinct
`passed_with_reviewed_findings` status means raw findings were retained, every one
met this reviewed public-checksum proof, and zero findings remain unresolved.
Any mixed or unreviewed result stays `failed`. Neither successful status asserts
universal secret absence, and consumers must not present the reviewed status as a
zero-finding scan.

The disposition supplement requires independent source review, meaningful negative
controls and the ordinary current-source checks before integration. Unit guards
cover altered mode/blob/size/bytes, wrong metadata, missing or changed frozen
bindings, replaced-object/error boundaries, resource limits and mixed-result
receipt behavior. Actual pinned-CLI controls must separately exercise the original
public finding and an additional unissued token-shaped finding; mocked evidence
reads do not substitute for those real scanner results.

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
