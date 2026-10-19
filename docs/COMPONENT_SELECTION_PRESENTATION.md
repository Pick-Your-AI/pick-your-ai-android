# Component selection text presentation

The explicit component editor uses string resources for catalog-choice rows,
catalog identity titles and saved-selection labels. The patterns own punctuation
and placeholder order. Translators can reorder the placeholders without changing
the canonical component kind, catalog ID, version, role or local selection ID.

`ComponentSelectionText` formats each value against the current Activity's resource
layout direction. It uses Android's [`BidiFormatter.unicodeWrap`](https://developer.android.com/reference/android/text/BidiFormatter)
with the value's natural direction. In particular, a catalog version is a
nonempty string in the frozen contract; it is not restricted to an LTR semantic
version. An Arabic version keeps its own direction in an English label, while
ASCII identifiers are wrapped when displayed on an RTL screen. The Activity's
layout direction also governs a forced-RTL screen whose resource locale is still
English; the process-wide default locale does not select this context.

The same formatting applies to the existing add, replacement and role-edit
explanations. Already formatted saved-selection labels remain whole resource
arguments when used in the replacement menu. Translated prose is not treated as
a technical identifier.

## Authority boundary

Formatting happens only while constructing display text. The selected list index,
captured catalog item, raw role/settings editors, catalog hash and exact stored
record continue through the existing callback and command paths. Display strings
are not parsed into a selection key, catalog version, command or save token.
Cancellation, section closure, current-record checks and the existing guarded save
remain separate from presentation.

The formatter does not add text truncation, normalize a catalog value, sanitize
arbitrary supplied direction controls, translate canonical tokens, convert units
or currencies, or change the existing recovered-input presentation. Those are
distinct boundaries from these explicit selection dialogs.

## Regression coverage

`ComponentSelectionPresentationTest` uses the actual Android resources and dialogs
under Robolectric API 28. Four cases cover an Arabic catalog version on an English
screen, English and Arabic values on an Arabic screen, a forced-RTL English
resource context independent of the default locale, and unchanged LTR labels.
They retain exact captured replacement item/version/hash/record identities, raw
role input, canceled and closed-dialog callback retirement, and the byte-for-byte
local draft file map. The command-capture callback reports no dispatched write;
that suite does not claim a successful durable replacement.

Existing component-form RTL suites locate their fixture kind/ID using a small
test-only direction-aware token helper. Their original save, recovery, target-size,
reachability and text-completeness assertions remain intact. The new presentation
suite asserts literal expected direction marks independently of that lookup helper.

The preview CI workflow requires all four new class-qualified cases exactly once,
without failures, errors or skips, after the ordinary preview unit-test command.
For a focused local run using the established Android toolchain:

```sh
./gradlew -PandroidPreview=true :preview:testDebugUnitTest \
  --tests ai.pickyour.android.preview.ComponentSelectionPresentationTest
```

These simulated checks do not establish installed-device rendering, TalkBack
behavior, a new language release, or completion of all localization/branding
criteria in issue #65.
