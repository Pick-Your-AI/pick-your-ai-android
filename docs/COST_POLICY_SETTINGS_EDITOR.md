# Typed cost-policy CONFIGURE

Fresh **Configure settings** for an existing `cost_policy` selection presents the
four optional fields in the frozen
[Selection schema](../contracts/v1/schemas/selection.schema.json). ADD still uses
the existing explicit raw settings object. This contribution does not alter
selection, catalog or role identity, the enabled flag, replacement, optional-tier
intent, shared T6 guidance or the one-enabled-cost-policy cardinality rule.

This is a bounded contribution to
[Android #99](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/99),
coordinated in its
[cost-policy lane](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/99#issuecomment-6283527688).
It records local planning preferences. It does not supply prices, calculate a
cost estimate, create notifications, enforce a spending limit or establish
deployment readiness. Currency is a separate existing draft input; the four
settings contain no currency field, default currency or conversion.

## Exact fields and explicit absence

| Frozen field | Native input and admitted value |
| --- | --- |
| `monthly_budget_amount` | Optional literal decimal string |
| `per_request_budget_amount` | Optional literal decimal string |
| `enforce_hard_cap` | Explicit **Not supplied**, **No** or **Yes**, preserving absence, Boolean false and Boolean true |
| `notify_at_pct` | Optional exact JSON number, 0 through 100 inclusive |

Each text field has its own **Supply** checkbox. An unchecked field is absent;
checking it with empty text does not silently manufacture a value. Empty
supplied text requires correction or explicit omission. Clearing Supply removes
only that key. Zero, false and absence remain separate states.

Both amount fields use the frozen
`^(0|[1-9][0-9]*)(\.[0-9]{1,12})?$` string constraint. The editor does not parse
amounts through floating point, round them, narrow their integer portion,
normalize their scale or apply the numeric-token length limit to a string.
Currency symbols, signs, grouping separators, decimal commas, leading zeroes
and exponent notation are not newly admitted. The existing frozen receiver's
accepted terminal LF remains literal data; there is no trimming or new regex
implementation that changes its behavior.

The percentage uses the existing bounded JSON reader and exact decimal bounds.
It rejects a percent suffix, locale coercion, injected additional fields,
non-number tokens and values outside the inclusive range. Unchanged admitted
numeric nodes retain their scale and numeric representation. Existing parser
and combined settings-text limits still apply; no extra financial maximum,
minimum budget, relationship between the two amounts or notification schedule
is invented.

The helper obtains field definitions from `PreviewSettingsMetadata`, validates
each amount through the actual frozen `LocalSelection` receiver and calls the
existing whole-selection `PreviewSelectionForms.configure` adapter. Independent
field errors are fixed messages without copied input values. An invalid sibling
field prevents the entire preparation from producing an applicable command.

## Canonical persistence and lifecycle

The existing managed selection command and compare-and-swap operation remain
the only persistence path. A successful explicit confirmation records one
revision. Cancel, unchanged settings, stale baseline, deleted library and
uncertain in-flight state retain their existing distinct outcomes. Notes,
extensions, links, other selections, private draft fields, history and exact
catalog pins are preserved. Frozen contracts and the durable history format are
unchanged.

The new private version-8 `configure_cost_policy` checkpoint contains only exact
primitive fields: the original local draft and baseline, selection and catalog
identity, cost-policy kind, three optional text inputs, nullable Boolean input
and in-flight flag. Every key must be present with its exact primitive type;
the nullable Boolean entry distinguishes absence from false. This is not a
Selection envelope, a storage-version change or a recovered write token.

The three text inputs share the existing **20,000 UTF-16 code-unit** recovery
budget. Over-budget input produces the existing fixed unavailable marker
without retaining a prefix. View state saving, autofill and personalized
keyboard-learning requests are disabled for the typed controls; the bounded
checkpoint owns their recovery. This does not guarantee how an installed
keyboard behaves or implement a general secret detector for arbitrary input.

Actual Activity pause captures bounded input, retires the form and nested
checked-choice picker, and revokes their callbacks. Resume restores availability
without reopening or consenting to an edit. After recreation, exact matching
identity and a clean acknowledged record permit explicit review and a fresh
confirmation. Different draft/baseline/selection/catalog identity or a saved
in-flight flag keeps otherwise valid captured input read-only. Already admitted
writes retain their own acknowledgment; no uncertain write is retried.

Raw version-1 and version-2 CONFIGURE checkpoints continue through their
original raw reader and form. New typed input does not reinterpret them.
Existing context-policy/model readers and fixed unavailable/exclusion markers
retain their protocols. Other editors' private checkpoint tags remain separate
and are composed only through their reviewed receiving changes.

## Verification and remaining scope

`PreviewCostPolicySettingsTest` covers all four metadata definitions, independent
optional states, wide exact string amounts, terminal LF, malformed/locale
input, exact percentage bounds, parser budgets, immutable preparation,
managed no-op/conflict/deletion, private metadata/history and validated Build
projection. Its original focused Core run on `4aea70b5` executed all 15 cases
successfully; that result predates the native editor and is not a native or
current-main execution claim.

`CostPolicyFormCheckpointTest` exercises actual Parcel round trips, exact
primitive/null presence, complete identity, combined Unicode bounds, mixed
versions and compatibility with existing raw/context/model readers.
`CostPolicySettingsActivityTest` drives actual platform controls, the serial
worker and canonical local storage. Its cases retain exact values and immutable
lineage, distinguish omission/false/true/zero, require field correction, reject
stale or retired controls, preserve invalid input through recreation, withhold
new authority from foreign identities and in-flight checkpoints, and require
fresh confirmation after real simulated pause. The narrow RTL/large-text case
checks complete text and scrolling reachability. These authored tests require
their own actual execution; declaration does not establish a passing result.

Two existing operational CONFIGURE assertions now inspect the typed cost
controls while retaining their original exact settings, other-kind raw-input,
no-op, revision and disk-preservation assertions. Raw ADD, legacy recovery and
the eleven existing operational method identities remain intact. Separate
workflow guards require the named Core and native suites to execute without
failure, error, duplicate identity or skip; full configured build/lint/artifact
and receiving checks remain required.

This slice does not close #99, its canonical
[08.04 acceptance coordinate](https://github.com/Pick-Your-AI/pick-your-ai-website/blob/main/docs/delivery/android/work-packages/08.04.md),
or the separate #27/#83 handoffs. Typed ADD, other kinds' field controls,
broader answer-state storage/export, comprehensive localization and actual
installed/physical-device, keyboard, TalkBack, process-death and release
qualification keep their own acceptance boundaries. The generic four-case
installed harness is not a qualification of this new typed editor.

## Large-text identity presentation correction

The first complete hosted cost-policy preparation, `4189a2863acc8edfef9f24ab9135ff82320502a6`
(tree `3a423dea2bd65e7da9595f02748e0838a67344d1`), compiled the native tests and
executed 800 cases. Its narrow 320dp RTL / 2x-text case failed the existing
no-ellipsis assertion with 41 omitted characters; the other 799 cases passed.
The failure did not record the particular view, so the exact original control
is not established by that receipt. The separate Core original executed 6,619
passing cases. Installed execution was skipped after the native failure.

The typed cost-policy form had placed its complete selection identity in the
platform dialog title, whose standard layout permits end ellipsis. The form now
uses the short “Cost policy” title and displays the entire unchanged selection
label as selectable multiline text inside its existing scrollable content.
This changes presentation only: selection identity, settings parsing, recovery,
currentness, submission and storage authority remain unchanged.

The same native regression retains every existing visible-text, touch-target,
field/guidance reachability and exact-save assertion. It additionally scrolls
every line of the exact selection label into view. Any later ellipsis failure
now identifies its view and text. The original failed run remains distinct from
validation of this correction; this source change alone supplies no passing
native, installed-device, accessibility-service or release evidence.

## Independent decimal-field validation

Each decimal-string field is now checked through a small validation-only
Selection containing that field. The scaffold does not supply an editor value,
saved identity or command. The final complete edit still uses the original
Selection and the existing whole-settings and whole-payload admission checks.

The former probe retained the original sibling decimal field and unrelated
Selection data. Near the admitted payload limit, it could falsely reject a
valid field when the same edit removed or shortened that sibling. The new Core
regression covers both decimal fields and both sibling changes, preserves exact
namespaced extension data, and checks that a genuinely oversized complete edit
is still rejected without changing the original. Existing format, terminal-LF,
exact-value, false/zero/omission, history and managed-authority controls remain.

This correction does not change the frozen schema or document budgets, interpret
currency, enforce a financial limit or provide installed-editor qualification.
Earlier source-stage results remain attributed to their original source; the
corrected composition requires its own configured checks.
