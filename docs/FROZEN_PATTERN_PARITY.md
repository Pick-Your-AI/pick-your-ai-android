# Frozen pattern receiving parity

`FrozenSchemaValidator` keeps the existing bundled schemas and configures the
networknt 1.5.6 regular-expression hook with `Pattern.UNIX_LINES`. It retains the
library's `find()` matching behavior. This fixes a demonstrated difference between
the JVM validator and the pinned Python reference for the finite frozen-v1
pattern set. It does not select a new schema dialect or change contract bytes.

## Exact boundary

The 14 bundled schema files contain nine distinct patterns: eight ASCII patterns
ending in `$`, and one prefix-only `^/` pattern. Their roles are ordinary IDs,
selection IDs, semantic versions, currencies, decimal strings, secret references,
selection roles, finding codes and JSON-pointer prefixes. None uses an unescaped
dot wildcard, inline flags or Unicode character classes.

[Python 3.12's `$` semantics](https://docs.python.org/3.12/library/re.html) permit a
match immediately before a single final LF. The pinned
[networknt implementation](https://github.com/networknt/json-schema-validator/blob/1.5.6/src/main/java/com/networknt/schema/regex/JDKRegularExpression.java)
compiles Java patterns with default flags and calls `find()`. Java's default
line-ending rules additionally admitted final CR, CRLF, NEL, U+2028 and U+2029 in
the exercised frozen documents. These were valid JVM results but invalid Python
reference results. [Java 21's `UNIX_LINES`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/regex/Pattern.html#UNIX_LINES)
restricts the line terminator used by `.`, `^` and `$` to LF.

The correction therefore preserves the existing final-LF reference behavior,
prefix-only pointer matching and unconstrained strings. It never trims supplied
characters or substitutes full-string `matches()` for schema pattern search.
The [Android Pattern reference](https://developer.android.com/reference/java/util/regex/Pattern#UNIX_LINES)
documents the same flag since API 1, and additionally lists vertical tab and form
feed among Android's default line terminators. Those characters have explicit
regression cases too; JVM checks do not establish execution on Android's regex
implementation.

This is conformance work for the exact imported pattern set, not a claim that Java
and Python implement arbitrary regular expressions identically. A successor
pattern set needs renewed compatibility review. Fixed schema resolution, format
assertions and all bounded-parser limits remain in effect.

## Shared regression evidence

[`frozen-pattern-cases.json`](../core/src/test/resources/contracts/frozen-pattern-cases.json)
pins all 14 schemas and the six exercised canonical examples from the existing
[frozen import](CONTRACT_ADOPTION.md). Both the Python checker and Kotlin test
require that complete input inventory, verify its SHA-256 bytes and compare all
nine discovered patterns with the covered pattern set.

The 160 named cases mutate actual canonical documents. Each of the nine pattern
families and one unconstrained string has 16 controls covering final LF, CR, CRLF,
NEL, Unicode line/paragraph separators, vertical tab, form feed, repeated or
embedded LF, leading characters, space and NUL. Characters are JSON-escaped so
syntax rejection cannot masquerade as schema rejection. The Python checker uses
the pinned format-enabled reference validator and local schema registry; the
Kotlin suite invokes the actual `FrozenSchemaValidator` receiving boundary.

Before the production correction, the initial 140-case JVM matrix had exactly 40
failures: five additional Java terminators across each of the eight end-anchored
families. Its other 100 controls passed. After correction, all initial 140 cases
passed alongside the 66 quality-run and 20 cost-reader tests. The later 20
vertical-tab/form-feed controls extend that matrix without changing production
code. Full and hosted results belong to the exact PR/run receipts.

## Controlled typed-money rejection

The frozen schema's terminal-LF acceptance does not make that string a supported
typed-money lexeme. Existing `ContractMoney` and `ContractDecimal` use a stricter
whole-string boundary. `CostEstimateAdapter` now checks all recorded money nodes
before arithmetic or immutable wrapper construction and translates only
`ContractRejected` into `INVALID_ESTIMATE`. This includes every line's unused
unit price as well as its amount, subtotal, tax and total. Exact currency
consistency still runs first and retains `MIXED_CURRENCY` precedence.

Six actual adapter regressions first reproduced escaping exceptions for matching
currencies with final LF and each of the five amount locations. They now receive
the sanitized rejection, retain caller stream ownership and permit a subsequent
normal read. A separate positive case retains a final LF in the declared quantity
string: the adapter does not parse quantity as money or derive prices from it.
Original strings, exact recorded-total arithmetic and the
[cost inspection limits](COST_ESTIMATE_INSPECTION.md) remain unchanged.

Run `scripts/check_contracts.py` in the documented Python environment, then the
strict core checks in [CORE_FOUNDATION.md](CORE_FOUNDATION.md). This supplies
partial receiving evidence for Android #21, #49 and #32 and the existing #103
cost boundary. It does not close those issues or establish full cross-platform
parity, a local evaluator, installed-device behavior or release acceptance.
