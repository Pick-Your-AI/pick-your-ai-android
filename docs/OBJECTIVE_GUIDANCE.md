# Required objective input guidance (#83, partial)

The workload editor identifies the three required objective properties in the
frozen BuildSnapshot schema: region, currency and data classification. A core
regression compares this exact set against the pinned schema. These are
user-entered assumptions, not verified region support or security suitability.

Unanswered fields are named explicitly as incomplete input, separately from
compatibility failure. Invalid or rejected form input suppresses the answered
state. Entered optional budget or zero request count never substitutes for a
missing required objective. All three answered means only those fields are
answered: the UI explicitly says overall build readiness, compatibility and
pricing have not been evaluated. No T0/T1 completion, deployment placement,
validated BuildSnapshot or budget pass is inferred.

A direct action focuses the next missing region/currency field or opens the
classification choice. It uses the current form, preserves pending edits and
performs no save or selection on the user's behalf. Busy or retired controls
cannot navigate or open another dialog. A polite text status exposes the same
incomplete/invalid/answered distinction without color dependence.

Core tests cover exact schema requirements, unanswered/zero/invalid/rejected
states and immutable missing-input lists. Two mandatory simulated Activity cases
cover destinations, explicit classification, no implicit writes, recreation and
retired callbacks. Exact hosted build/lint/UI execution is required before merge;
manual TalkBack, keyboard and installed-device layout evidence remain separate.

Placement guidance, complete required T0/T1 selection/readiness, real catalog
support and receiving acceptance remain unfinished #83 scope. No issue is closed.
