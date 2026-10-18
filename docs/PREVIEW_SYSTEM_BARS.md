# System bars around the scrolling preview

This correction is a bounded continuation of [Android #80](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/80)
and [Android #64](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/64).
It fixes the preview's fixed viewport insets and light-theme system-bar contrast.
The existing owners retain the separate scroll/focus/caret checkpoint and checked-choice work.

## Observed defect

The original installed [run 39166306506](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39166306506),
actual checkout `b6f7fa915c2e6460b3420e359110eade1deca813`, successfully exercised all four
lifecycle cases while retaining a separate display defect. Original artifact `12151541742`
is 1,028,453 bytes with SHA-256
`a7c2d060b00662756c023ddb35e93979b3fefcbcca655f95ae26d10ee6edec52`.
Its eight original PNG/XML pairs show scrolled form content behind the system bars;
the status icons are light against the preview's light background. This visual
observation does not change the passing lifecycle results or qualify later source.

The same setup remains in accepted main `e8241ef4ea75071339e6a945e48fc6d433819ffa`.
The inset listener pads the inner `LinearLayout`, so that protection moves with the
content when its `ScrollView` scrolls. The platform light theme does not explicitly
select dark status-bar icons. Android 15 enforces edge-to-edge for target SDK 35,
which is the preview's current target.

## Fixed viewport and system-bar contrast

The existing 24 dp form padding stays on the inner content. The same delivered
system-window inset values now pad the fixed `ScrollView` viewport, with clipping
to that padding. Scrolling moves the form within this protected viewport. Repeated
and changed inset dispatch replaces the four physical edge values, so orientation,
RTL and subsequent empty insets neither accumulate nor leave old padding. The
listener keeps dispatching the original insets to descendants and requests
insets after installing the viewport.

The preview theme still inherits `Theme.Material.Light.NoActionBar`. On API 26,
it selects a light status bar with dark icons and keeps the platform's inherited
navigation-bar background and icon appearance together. API 27 and later select
the qualified theme, which also uses a light navigation bar with dark icons. Both
light bar colors follow the theme background. The navigation-bar XML appearance
attribute is confined to `values-v27`; the minimum SDK remains 26. Android 15
retains its enforced transparent/translucent bar behavior.
No new UI framework, dependency or system-bar hiding is added.
The existing inset types and keyboard contract are retained. This change does not
implement focus/caret restoration, keyboard animation or a complete adaptive-window
experience; those remain in their existing work packages.

Primary platform references checked on 2026-10-18:

- [Android 15 edge-to-edge behavior](https://developer.android.com/about/versions/15/behavior-changes-15#edge-to-edge)
- [Insets for Views](https://developer.android.com/develop/ui/views/layout/edge-to-edge#system-bars-insets)
- [System-bar theme appearance](https://developer.android.com/develop/ui/views/layout/edge-to-edge-manually)

## Validation scope

Four mandatory `PreviewActivityTest` methods exercise the actual Activity's viewport
while scrolling, repeated/orientation/RTL inset updates, removal of prior insets,
and the actual manifest-selected light-bar theme attributes. The existing native
receipt gate requires all four names and retains every preceding required method;
missing, failed or skipped results remain failures.

Two mandatory `PreviewWindowInsetsActivityTest` methods additionally measure the
real form's visible clipping rectangle at the start, middle and end of scrolling,
and verify exact pending fields, focus/caret and acknowledged saved identity through
repeated inset dispatch and synthetic portrait, landscape and keyboard-height sizes.
Their separate receipt gate checks exact suite/class identity, required unique names,
matching counts and no failed, skipped or errored case. Fixture-only reflection
constructs API 28 `WindowInsets(Rect)` values because compileSdk 35 hides that
constructor; the actual listener receives those values through public dispatch.

These are API 28 Robolectric simulations using the existing pinned runtime.
Current-source build, lint, original reports and receiving review are required for
delivery. The canonical installed-emulator lane supplies separate API 35 screenshots
and lifecycle evidence when it consumes this source. The old screenshots above are
failure evidence only for the display behavior they show; they are not a new-source
visual pass. Physical devices, TalkBack, gesture-navigation/cutout matrices, keyboard
transitions and full #80/#64 acceptance remain separate.
