# Literal and bounded findings presentation

The synthetic findings inspector now separates recorded text from application
labels. This is partial Android [#101](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/101)
and [#65](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/65) work. The
existing `ReferenceReportAdapter` still validates the same frozen report profile;
`FindingsInspectionText` consumes its copied inspection view without executing
rules, modifying the accepted JSON snapshot or writing a guest draft.

## Recorded values and status

Identifiers, timestamps, revisions, explanations, remediation, selection IDs and
evidence fields use the existing typed `PreviewComparedValue` display boundary.
Null optional values use the externalized absent label. A present empty string
uses empty quotes, and a literal string saying `Not supplied` stays quoted text.
The old native renderer collapsed empty remediation into absence; its exact
regression failed against that renderer before this correction.

Observed and expected evidence values remain the adapter's serialized JSON.
Quoted presentation escapes that JSON as text: JSON null, the JSON string
`"null"`, absent JSON, false and wide numeric values remain distinguishable. This
does not reparse them into floating-point display numbers or establish their
correctness. The complete accepted snapshot and projection retain their values.

Quotes, backslashes, controls, directional formatting characters and U+2028/U+2029
are shown as escapes. Each recorded value is limited to 160 UTF-16 units with an
explicit truncation notice. Directional wrapping follows the screen's resource
locale after source controls have been escaped. Only the application adds active
directional formatting around a displayed fragment.

Outcome, severity, reported result and reported readiness remain separate
canonical values. The outcome-count copy is externalized, and its counts include
every finding in the accepted report. Warning failures remain failures with
warning severity; unknown and skipped are not passes. Explanation or remediation
text cannot replace these labels or confer production readiness.

## Display limits and navigation

An individual detail shows up to eight affected selection IDs and eight evidence
entries, with translatable displayed/retained counts. It retains the complete
lists and accepted report. Empty selections and empty evidence have explicit
status text; neither proves completeness. These display limits do not alter
outcome counts, evidence applicability, source authority or retention policy.

All existing finding rows remain available in the existing selector. Finding
navigation, the receiver's bounded input budgets, worker/lifecycle checks and
the fixed-fixture scope are unchanged. Selection links and remediation execution
are not introduced by this presentation change.

## Reproduction and remaining acceptance

Seven mandatory simulated resource tests cover absence/empty/literal sentinels,
typed JSON and exact wide values, hostile source controls, truncation, nested
counts, independent outcome/severity, complete report counts, exact identities
and revisions, and RTL resource context. The existing Activity tests continue to
exercise actual inspector adoption, no draft writes and retired callbacks.

```sh
./gradlew --offline --no-daemon --dependency-verification=strict \
  -PandroidPreview=true :core:check :core:assemble \
  :preview:testDebugUnitTest :preview:assembleDebug :preview:lintDebug
```

These are source and simulated-resource observations. Installed TalkBack order,
announcement behavior, physical RTL layout, accepted real evidence, current-draft
evaluation, required-presence versus conflict classification, exact role/version
navigation and receiving review remain separate acceptance work. Canonical
acceptance records remain `docs/delivery/android/work-packages/09.03.md` and
`06.06.md` in `Pick-Your-AI/pickyour.ai`; neither whole issue is closed here.
