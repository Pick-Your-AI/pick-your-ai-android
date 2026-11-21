# Agent framework settings preparation

This bounded part of Android [#99](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/99)
and [#128](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/128) prepares
the three existing frozen `agent_framework` settings in the SDK-independent core.
It reuses the helper and full Core regression suite from the unmerged
[#738](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/738) source
`8f248e8c1639488412219cdc589e7d8d56b1d83f` on current accepted main, with an
additional explicit whitespace regression. It does not make the native editor
reachable; its UI, recovery, routing and attendance integration remain separate.

| Frozen setting | Input and unit | Admission |
| --- | --- | --- |
| `agent_count` | Optional exact integer count | At least 1 |
| `max_iterations` | Optional exact integer count | At least 1 |
| `timeout_ms` | Optional exact integer, milliseconds | At least 1 |

The helper reads the existing [settings metadata](../core/src/main/kotlin/ai/pickyour/core/preview/PreviewSettingsMetadata.kt).
It adds no defaults, upper limits or relationships between fields. Null input
removes an optional field; supplied empty or whitespace-only text is an error.
JSON number spellings that represent exact integers, including decimal and
exponent aliases, remain supported by the existing bounded reader.

JSON surrounding whitespace is accepted and omitted from the stored numeric
node. For example, entering ` 5 ` or `5` followed by a newline produces integer
`5`. This is an explicit edit: replacing retained `5.00` with ` 5 ` normalizes
that field's numeric representation. Untouched controls retain their admitted
node class and decimal scale. All three fields receive this regression coverage.

Preparation returns independent field errors without their entered values and
admits the complete final Selection through the existing canonical form path.
Malformed input, duplicate-key or document injection, fractions, out-of-range
values and exceeded budgets cannot publish partial settings. Existing Selection
identity, unrelated draft fields, explicit managed writes and revision checks
remain authoritative; preparation itself performs no storage operation.

The [Core regressions](../core/src/test/kotlin/ai/pickyour/core/preview/PreviewAgentFrameworkSettingsTest.kt)
cover omission combinations, numeric bounds and representation, input budgets,
final sibling removal/shrink, no-op and conflicting managed writes, rejected
storage changes, and explicit frozen Build projection. Test definitions and local
execution do not establish installed Android, accessibility or release evidence.

No agent, tool, transport or permission is activated. Credential entry, new T4
contracts, native editor lifecycle, sharing acceptance and both issues' remaining
criteria require their own implementation and receiving evidence. Frozen v1,
the existing acceptance-record locations and current editor routing are unchanged.
