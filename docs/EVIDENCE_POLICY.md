# Evidence presentation policy

`EvidencePresentationPolicy` is a pure Kotlin projection for Android-local evidence labels and freshness. It consumes already validated frozen-v1 provenance plus an optional, separately reviewed claim assessment. It does not change frozen files, evaluate compatibility, resolve conflicts, grant source-use rights, or establish source authenticity. No UI integration or production evidence acceptance is claimed.

## Accepted inputs and exact scope

Research inputs are pinned to these merged revisions, verified against GitHub on 2026-10-18:

- [Research PR #126](https://github.com/Pick-Your-AI/pick-your-ai-research/pull/126), merge `565f4512ed6a38dfc21c83c92babe5b3bed64e24`: [component schema and compatibility record](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/565f4512ed6a38dfc21c83c92babe5b3bed64e24/research/T0-configuration/component-schema-compatibility.md). Its separation of evidence strength from rule outcomes does not authorize this display policy to compute a compatibility verdict or adopt proposed successor semantics.
- [Research PR #127](https://github.com/Pick-Your-AI/pick-your-ai-research/pull/127), merge `92111483b8d52b1b05152391b2d73e9f59ca2fba`: [catalog evidence and freshness record](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/92111483b8d52b1b05152391b2d73e9f59ca2fba/research/T0-configuration/catalog-data-sources.md). The exact freshness instruction is “Version-pinned facts don't expire.” Its checked/verified distinction requires independent support for verification and preserves `estimated` and `user_supplied` meanings.
- [Research #41 receiving receipt](https://github.com/Pick-Your-AI/pick-your-ai-research/issues/41#issuecomment-6242626857), fetched directly through GitHub's comment API on 2026-10-18. The receipt says a claim is source-checked only when its cited text was actually read. Historical raw `verified` alone therefore proves neither an actual source reading in this adapter nor independent verification. The receipt accepts current-state treatment of unversioned hosted services, preservation of the selected snapshot and no TTL.
- [Source-rights decisions](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/92111483b8d52b1b05152391b2d73e9f59ca2fba/research/market/rights-validation.md) and the same #41 receipt remain a separate use-specific gate. A source URL, evidence grade, independent verification or a registry listing grants no display/export/cache permission or security certification. This policy makes no rights decision and must not be used as permission to display the underlying claim.

These accepted distinctions do not adopt the records' pending TTL, successor-schema, community-evidence or conflict-execution decisions.

## Inputs and outputs

An `EvidenceIdentity` contains claim ID, selected version, snapshot ID and snapshot revision. These are client-local binding metadata, not new frozen-v1 fields. Adapters must preserve the source claim's identity, not synthesize support from a URL or a raw enum. `ReviewedEvidenceAssessment` must come from the separate reviewed handoff: it carries an exact matching identity, a review reference, and explicit source-check and independent-support dispositions. A reference alone leaves both dispositions unestablished. The policy does not authenticate or perform that review; untrusted imports must never be allowed to assert this assessment. A mismatched assessment is ignored conservatively.

| Raw frozen status | Without applicable independent support | With applicable reviewed independent support |
| --- | --- | --- |
| `verified` | Verification unestablished | Independently verified |
| `unverified` | Unverified | Independently verified; raw status retained |
| `estimated` | Estimated | Estimated; separate support field retained |
| `user_supplied` | User supplied | User supplied; separate support field retained |

These are presentation labels, not serialized contract values. The output always preserves the raw status. Source-check status is separate: neither independent reproduction nor raw `verified` automatically establishes that the cited source text was read. `NOT_ESTABLISHED` means the needed assessment is absent, not that the claim is false.

Version-pinned facts return their complete selected identity and do not expire. Current-state facts return `AsOf` with the selected evidence's capture/access timestamp, or `DateUnavailable` when absent. Capture-date absence is also retained explicitly for pinned facts. There is no clock input, TTL, automatic age warning, “current” assertion, or expiration calculation. Even a future timestamp is retained as supplied metadata, not endorsed as a credible source date. Date plausibility and source eligibility belong to the upstream reviewed evidence boundary.

A new capture creates a different identity. It cannot mutate an earlier immutable presentation or inherit its reviewed assessment. It does not invalidate any build by itself. Evidence rechecks, corrections, rights changes, scheduling and reevaluation remain the consuming adapter's responsibility.

## Validation and remaining integration

The unit tests cover every raw status, source-check versus independent support, reference-only input, claim/version/snapshot mismatches, missing dates, old and future timestamps, and retained historical presentation after a new capture. The function performs no IO and reads no wall clock.

The current slice supplies the testable policy only. A UI adapter still needs accessible localized labels, a reviewed evidence-assessment source, explicit upstream validation and rights authorization, and canonical receiving acceptance. Compatibility, capacity eligibility, disputed-conflict resolution, source correction workflows and historical/shared-view rights enforcement remain separate work.

Local validation on 2026-10-18: JDK 21.0.12.1 and the pinned Gradle 8.11.1 toolchain passed `:core:test --offline`: **57 tests, zero failures**, including 10 evidence-policy tests. `git diff --check` also passed. This is JVM unit evidence, not emulator or physical-device validation. The first run inherited `JAVA_TOOL_OPTIONS`; its JVM startup banner broke an existing subprocess test's exact-output assertion. The subprocess regression test now separates stdout protocol responses from stderr diagnostics while retaining timeout and exit assertions. All 14 cache tests also passed with the environment option set, and the complete 57-test suite passed without it.
