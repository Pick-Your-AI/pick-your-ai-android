# Android vulnerability response

Use this procedure for a reported vulnerability, dependency advisory or suspected
credential exposure in the Android project. It supplies the response-procedure
slice of [Android #69](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/69).
The canonical receiving path remains `docs/delivery/android/work-packages/14.06.md`
in `Pick-Your-AI/pickyour.ai`; this supporting document does not move that record.
A monitored private intake, agreed responders and distributable corrected release
still require their actual acceptance evidence.

## 1. Receive and preserve a report

Before sending sensitive details, verify the project's current security policy
and an accepted private destination. GitHub's **Report a vulnerability** form is
usable only when private vulnerability reporting is enabled; the presence of a
`SECURITY.md` file alone does not enable it. A repository administrator can instead
create a draft security advisory. If neither a private route nor a responsible
recipient is available, retain the details securely and request a preferred
security contact without publishing the finding. Public issues, preview Help
reports and a community code-of-conduct contact are not established private
security intake. Record this as an intake gap, never as an acknowledged report.

Once the destination and recipient are confirmed, record the received time,
affected component/version/configuration, source commit/tree, reproducible steps,
observed impact and restricted evidence location. Acknowledge receipt through that
route and agree the next update with the reporter. Record an actual acknowledgement
and commitment; this document assigns no response time or person. Keep raw matched
values, customer content, exploit details and private URLs out of public source,
issues and CI output. Retain original evidence and add corrections separately.

## 2. Triage and establish responsibility

Distinguish an observed exploit, a suspected exposure and an unverified scanner
match. Record the attacker prerequisites, affected build and reachable behavior;
state unknown scope explicitly. A pattern match does not establish that a value
was issued, active or used. An advisory severity alone does not establish this
application's exposure. Prioritize using observed impact and exposure, with the
reason, decision maker and next investigation recorded in the restricted record.

For a dependency, bind the advisory's affected and fixed versions to the actual
locked component and [runtime inventory](RUNTIME_INVENTORY.md), including its
transitive graph and artifact digests. Review the [SPDX export](RUNTIME_SBOM.md)
with its declared limits. Runtime resolution is not proof of packaged reachability
or license clearance; build plugins, tools and native SDKs need their own scope
assessment. Preserve unresolved provenance and license obligations.

| Required responsibility | Evidence to obtain for this incident |
| --- | --- |
| Response coordinator | Actual acceptance of triage, updates and unresolved-action tracking |
| Affected source/service owner | Scope agreement, containment and recovery decisions; preserve existing ownership |
| Credential or signing-key custodian | Actual provider access and authority, consumer inventory and recovery route |
| Independent security/receiving reviewer | Review of the exact repair, containment evidence and remaining exposure |
| Release operator and disclosure contact | Accepted distribution/communication authority and actual verification duties |

Leave a missing role unresolved. Do not treat a repository assignee, author,
successful build or local rehearsal as agreement to operate a response service.

## 3. Contain the actual exposure

For an issued credential, treat exposure as compromise. Have its authorized
custodian follow the provider's current revocation/replacement procedure, identify
all consumers and assess prior use through the available audit records. Coordinate
the replacement and interruption risk with affected owners. Record provider
confirmation that the old credential is unusable and verify the consumers use the
replacement. Keep new values in the approved secret store. Source deletion, a
clean pattern scan or guessed authentication result is not revocation evidence.

For a suspected false positive, preserve the original failing scan and inspect
the exact commit/file/line/rule, public provenance and actual matched bytes in a
restricted review. Follow the current [scanner policy](SECRET_SCANNING.md) and its
independent exception review; a triage label alone does not change the check.
Do not add an ignore, suppress diagnostics or rewrite project history to manufacture
a clean result. Historical issued-secret findings need actual containment evidence
and an approved disposition even after removal from current files.

Distinguish an Android app-signing key, Play upload key and local test key before
choosing recovery. Follow the accepted account's current Android/Play recovery
procedure and preserve update compatibility. Resetting an upload key does not
rotate the app-signing key. An unsigned preview or synthetic test key establishes
no product-key custody, recovery or distributable update.

If containment needs a feature disabled, rollout halted or service isolated,
identify the exact affected target, existing operator and recovery condition.
Record the action actually performed and its readback. A proposed action is still
pending; do not affect unrelated services or workloads.

## 4. Repair and verify

Prepare a focused repair against the current accepted source with the existing
owner. Keep embargoed repairs within an approved private collaboration route until
disclosure is agreed. Preserve the original reproduction and add a regression
that fails before the fix and exercises the affected behavior after it. For a
dependency correction, review versions, transitive changes, licenses and provider
advisories; update locks and verified checksums together, keeping strict checks.

Run the applicable commands and all actual gates in [CONTRIBUTING.md](../CONTRIBUTING.md)
and [CI_BASELINE.md](CI_BASELINE.md). A credential correction also requires the
pinned scanner's guards, real fixtures and reachable-history check. Review the
sanitized findings, complete job result and original artifacts for the exact
executed source. Failed, absent, cancelled, skipped or stale evidence does not
qualify the new source. Preserve the original failure when a correction passes.

Obtain independent receiving review of the repair and containment/disposition.
Use normal repository signing and merge gates. Verify the delivered commit/tree,
actual default-branch checks and clean local synchronization. Source delivery is
one step in the incident, not proof of distribution or removal of installed risk.

## 5. Deliver an urgent update and recover

The authorized release operator identifies the affected installed versions,
corrected version/package/signing identity, distribution lane and rollout decision.
Produce a traceable corrected artifact with its dependency inventory and notices;
resolve outstanding findings against the accepted release criteria. Preserve
artifact hashes and the actual signing, upload and distribution receipts.

Verify clean installation and upgrade from the affected version on the required
devices, including draft preservation, migration/recovery and the original failure
case. Confirm users can actually receive the corrected build. The unsigned
engineering APK does not satisfy this step. A merged backend fix also needs the
canonical service owner's deployment and recovery readback. If distribution or
operator access is unavailable, record that concrete remaining gate and affected
exposure; do not close the incident as delivered.

Monitor the agreed recovery condition and handle failed rollout through the
accepted halt/forward-fix plan; do not assume installed applications can be remotely
downgraded. The authorized disclosure contact coordinates an advisory and affected
user guidance through the agreed route. Retain the original report, triage basis,
accepted responsibilities, containment evidence, repair/review, distribution and
installed verification, disclosure decision and remaining actions. Reconcile #69
only against every literal criterion and its receiving review.

## Controlled local rehearsal

From the repository root on Linux x86_64, run:

```sh
python3 scripts/exercise_security_response.py
```

This reuses the existing pinned scanner download. For an already available binary,
pass `--scanner /absolute/path/to/gitleaks`; its exact bytes and version are checked
before use. `--output` selects a new receipt path. Existing files and symlinks are
refused so an earlier result is preserved. The default output is
`build/security/response-exercise.json`.

The driver generates a deterministic unissued pattern only inside a private,
temporary, remote-free Git repository. It creates a seeded commit and one removal
commit, then actually runs these four checks with the existing scanner:

| Observation | Required result |
| --- | --- |
| Seeded directory | One `github-pat` finding; scanner exit 10 |
| Seeded history | One finding at the exact seeded commit/file/line; exit 10 |
| Directory after removal | Zero findings; exit 0 |
| History after removal | The same original finding remains; exit 10 |

Inherited Git routing/configuration is removed for the fixture; source identity
reads disable replacement objects. Raw scanner reports and the generated value
remain temporary. The retained JSON contains only sanitized metadata, actual
executed-file digests, the base revision and explicit limitations. It does not
scan project history or contact a provider, responder, customer or distribution
service. A passing rehearsal confirms the four observations, including unresolved
history; it does not claim credential revocation or operational readiness.

Use the observations for a tabletop: decide whether the pattern was actually
issued; identify the evidence a custodian would need before claiming revocation;
explain why removal cannot clear the historical finding; then identify the exact
repair, release and installed-update evidence still needed. Record actual
participants and actions only when the tabletop occurs. The automated run itself
proves no human participation, accepted duty or completed release.

The [2026-10-19 automated exercise](security-response/exercises/2026-10-19.json)
records an actual four-scan run. Its executed-file digests identify the local
driver and reused scanner; the base revision is recorded separately because the
driver was not yet committed. It is automated fixture evidence, not a completed
human tabletop or a scan of the Android project's history.

Primary guidance checked 2026-10-19: [GitHub private reporting](https://docs.github.com/en/code-security/how-tos/report-and-fix-vulnerabilities/report-privately),
[leaked-secret response](https://docs.github.com/en/code-security/tutorials/remediate-leaked-secrets/remediating-a-leaked-secret)
and [Android app signing](https://developer.android.com/studio/publish/app-signing).
Recheck the provider procedure and actual account settings before an incident action.
