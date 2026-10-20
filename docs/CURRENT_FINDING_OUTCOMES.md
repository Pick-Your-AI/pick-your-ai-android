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
existing limits on deployment authority remain unchanged. This general outcome
guidance does not change an evaluator verdict, input admission, rule, stored byte
or readiness decision.

## Producer-observed unknown phase

Current results can additionally carry one of two observations from the same
private evaluator pass. The detail displays it after the unchanged general
outcome explanation:

- **Applicability:** whether the rule applies could not be determined, so its
  assertion was not evaluated.
- **Assertion:** applicability was true, but the assertion did not determine a
  pass or fail.

Both descriptions state that phase alone identifies neither a cause nor a
feasible next action. No rule message, remediation, code, affected kind or
selection is parsed to infer the phase. The existing recorded detail remains an
exact prefix, and pass, fail and skipped results carry no unknown-phase text.

The evaluator records only the branch already taken, without another expression
evaluation or work charge. Its public report and existing private finding shape
remain unchanged. An opaque trace is constructed only by actual evaluation of
the captured Current request. A completed result accepts that trace only for the
same request object and exact produced findings list, then copies its map.
Unknown findings from a bare result, a different request, or a copied/foreign
list retain the general explanation without a phase. A later work-limit or
unsupported-operation rejection exposes no partial phase result.

This is a Current-only result detail. It neither adds a frozen field nor changes
a ruleset, profile, input digest, verdict, summary, readiness decision, stored
draft, historical report, or existing selection-management authority. The
coordinator's existing request ownership and pre/post durable-source checks
continue to admit or retire the entire result together.

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

The first two cases also require their distinct producer-observed phase and its
exact separate paragraph. All five original method identities and their
recorded-field, binding, storage, navigation and summary assertions remain. The
first case also replaces the actual rules inputs, waits for a new Current result
with the opposite phase, and proves retired Back/Full text controls cannot
replace its new dialog, text, state or private bytes.

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

### Phase-specific Core controls

The eight named `DraftCurrentUnknownPhaseTest` methods use actual stored requests
and schema-admitted synthetic rules. They cover unknown applicability with an
assertion that really rejects if evaluated, true applicability with an unknown
assertion, skipped/pass/fail controls, absent/fabricated/foreign metadata, equal
input pins with a different request identity, and whole-attempt rejection after
an earlier unknown. Immutable copies and unchanged private file bytes are
checked. Exact 15/23-unit success and one-unit-short failure controls preserve
the existing single-rule work charges. Complete reference-report fields and
repeated report bytes, plus the legacy draft finding bytes, stay equal.

The additive Core guard requires all eight exact direct passing methods, one
suite/class identity, consistent totals and no nested, duplicate, extra, failed,
errored or skipped observations. The existing five-method Preview gate and every
earlier gate remain unchanged.

This follow-up has source and test definitions only until its own concrete
candidate is compiled and executed. The qualified #748 source and job originals
remain evidence for that unchanged predecessor, not execution of this addition.

## Acceptance boundary

This is a current-only explanation contribution to
[Android #101](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/101).
It leaves [current diagnostic authority](CURRENT_DRAFT_REEVALUATION.md), fixed
Findings, retained reports, provisional guidance policy and their owners intact.
Qualified evidence destinations, a particular missing-input or causal explanation,
and feasible next-step advice still require their own producer support and
receiving review. The observed phase above does not supply those facts. The
broader issue and its acceptance-record placement are not completed by this
change.

These cases use Robolectric SDK 28 and labelled synthetic inputs. They do not
establish installed-device pixels, TalkBack, physical-device behavior, hardware
evidence, release readiness or deployment authority. Configured CI must qualify
its own exact source and original outputs.
