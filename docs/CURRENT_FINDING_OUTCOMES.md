# Meaning of current unknown and skipped findings

In **Saved draft checks**, open **Inspect current rule observations** and choose
an observation. A current `unknown` or `skipped` finding adds a separate
**Outcome meaning** paragraph after the existing recorded detail:

- `unknown`: “The local diagnostic did not determine a pass or fail for this rule.
  This is not a pass or proof of readiness.”
- `skipped`: “This rule was not evaluated for these captured inputs. A skipped
  result is not a pass or proof of readiness.”

This is general interface guidance selected only from that captured finding's
outcome token. It does not infer a particular missing input, failed compatibility
check, cause, or feasible correction. The rule's raw outcome and independent
severity remain visible. Its recorded observation and guidance remain quoted,
escaped data, even when they contain words such as “pass”, “Ready”, “unknown” or
“skipped”. A supplied rule message is not used to choose this paragraph.

The complete original detail remains an exact prefix. Pass and fail observations
retain their original detail without an extra paragraph or separator. Summary
counts, the absence of external evidence or cost/resource estimates, and the
existing limits on deployment authority remain unchanged. This renderer addition
does not change any evaluator, input admission, rule, result, stored byte or
readiness decision.

## Existing destinations and lifetime

**Close**, **Back to observations**, **Full text** and **Inspect affected
selections** remain available. [Complete text](CURRENT_FINDING_TEXT.md) still
means the captured raw observation or recorded guidance, not the new interface
paragraph. [Affected selection context](CURRENT_FINDING_CONTEXT.md) still uses
the exact IDs reported by that same finding and the captured saved record.

The paragraph is part of the ordinary scrollable message. It adds no new window,
callback, action or source authority. Existing captured-source, current-analysis
and showing-window checks continue to control navigation and retirement. Back
and fresh inspection reproduce the explanation for that same admitted finding.

## Focused regression and CI attendance

`CurrentDraftFindingOutcomeActivityTest` saves a real empty local draft and
supplies explicitly synthetic schema-admitted inputs through the existing
current-input seam. The real worker, canonical source reads and evaluator must
produce a distinct `Current` before presentation is checked. No result or Current
state is injected.

Its five methods cover:

1. Unknown applicability from an absent primary deployment selection, retaining
   warning severity and the supplied observation and guidance.
2. An eligible but unknown assertion from an absent selected model, retaining
   info severity and the same general explanation without inventing a cause.
3. False applicability producing a real skipped result with error severity,
   evaluator-synthesized text and empty guidance, without promoting it to failure
   or pass.
4. The natural required-foundation failure with misleading status words in its
   supplied text, preserving the original detail exactly.
5. An explicit test-only literal comparison producing a real pass with error
   severity, again preserving the original detail exactly.

Every case checks actual input hashes, raw outcome/severity/message/guidance,
source revision/hash, saved identity, summary counts and private draft/library
file hashes before the explanation assertion. Raw displayed fields are
independently JSON-decoded. Back, close and fresh inspection then preserve the
same current binding, text, counts and files. The file assertions cover regular
files under `noBackupFilesDir`, not every application directory.

The additive **Require actual current-finding outcome-meaning regressions** step
requires all five distinct names from the actual Preview JUnit report, correct
suite/class identities and consistent totals. It rejects missing, repeated,
blank, nested, failed, errored or skipped cases; additional distinct passing
methods are permitted. All prior attendance programs remain unchanged. The
focused corrected cohort combines these five methods with the unchanged 43
context, full-text, current-check, presentation and sibling methods.

## Acceptance boundary

This is a current-only explanation contribution to
[Android #101](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/101).
It leaves [current diagnostic authority](CURRENT_DRAFT_REEVALUATION.md), fixed
Findings, retained reports, provisional guidance policy and their owners intact.
Qualified evidence destinations, an actually reported applicability reason and
feasible next-step advice require their own supplied metadata and receiving
review. The broader issue and its acceptance-record placement are not completed
by this change.

These cases use Robolectric SDK 28 and labelled synthetic inputs. They do not
establish installed-device pixels, TalkBack, physical-device behavior, hardware
evidence, release readiness or deployment authority. Configured CI must qualify
its own exact source and original outputs.
