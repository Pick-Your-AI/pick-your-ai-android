#!/usr/bin/env python3
"""Hash-first reference and actual XML attendance for typed-comparison/0.1.

This is a separate synthetic test profile. It never replaces frozen dispatch,
lowers Answer states, selects source eligibility, or qualifies product behavior.
"""
import argparse
from contextlib import contextmanager
from dataclasses import dataclass
import hashlib
import io
import json
from pathlib import Path
import re
import stat
import sys
import types
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PROFILE = "typed-comparison/0.1"
LOCK_PATH = "core/src/test/resources/conformance/typed-comparison-inputs.lock.json"
LOCK_BYTES = 1811
LOCK_SHA256 = "a7fce7106d2d2135214576b062769ad557164471ac2915fc3b84820157dd90cb"
MARKER = "ANDROID_TYPED_COMPARISON_CASE"
FIXTURE_CLASS = "ai.pickyour.core.conformance.typedcomparison.TypedComparisonFixtureTest"
ADMISSION_CLASS = "ai.pickyour.core.conformance.typedcomparison.TypedComparisonAdmissionTest"
MAX_XML_BYTES = 2 * 1024 * 1024
FIXTURE_GUARDS = {
    "changedTruncatedOrExtendedCorpusCannotSupplyAnExpectedOutcome()",
    "reorderedCorpusCannotReuseTheReviewedCaseIdentity()",
    "changedInputLockCannotRedirectTheNamedProfile()",
}
ADMISSION_METHODS = [
    "profileAndOperatorAreExactBeforeEitherMalformedOperand",
    "allProvidedMembersPrecedeUnknownEmptyOrKnownMatchResults",
    "duplicateAdmissionWaitsForItsSuppliedChildrenButPrecedesLaterSiblings",
    "objectKeysRetainEscapesUntilCompleteReferenceAdmission",
    "numericPrefixCallbacksPrecedeOnlyLaterSyntaxFailures",
    "byteCeilingIncludesOriginalWhitespaceSeparatelyForEachOperand",
    "depthIsContainerNestingAndQuotedEscapesDoNotSpendIt",
    "nodesCountEveryKeyAndValueEvenWithoutComparableRootTypes",
    "textBudgetCountsScalarsIncludingDecodedObjectKeys",
    "numberTokenAndCanonicalStringUseTheSameFinitePrecisionAdmission",
    "exponentAndResultingScaleAreIndependentlyBoundedWithoutClamping",
    "workChargesOriginalWhitespaceAndEscapesBeforeParsing",
    "exactWorkBoundariesPreserveIntegerDecimalAndElementDispatchKinds",
    "fullCartesianWorkCannotBeBypassedByTheFirstKnownMatch",
    "substringWorkPrecedesKnownMatchAndEmptyNeedle",
    "limitConstructionRejectsExpansionAndEntryRevalidatesChangedState",
    "exactOrderingDoesNotPassThroughBinaryFloatOrUnsignedMachineLimits",
    "knownNullUnresolvedAndTheBooleanNumberDomainsStayDistinct",
    "recursiveKeysRemainLiteralAndArraysKeepOrderWhileSetsUseMembership",
    "callerArraysAndLocaleDoNotBecomeRetainedOrAmbientComparisonAuthority"
]
MALFORMED_NAMES = [
    "empty",
    "trailing-root",
    "trailing-array-comma",
    "leading-zero",
    "raw-control",
    "bom",
    "non-utf8",
    "surrogate-utf8",
    "high-surrogate",
    "low-surrogate-key",
    "duplicate-key",
    "decoded-duplicate-key",
    "nan",
    "infinity",
    "negative-infinity",
    "nested-nan",
    "plus",
    "short-infinity",
    "plus-infinity",
    "missing-value",
    "single-quote",
    "comment",
    "unquoted-key",
    "trailing-point"
]


class CheckError(ValueError):
    """A fixed checker diagnostic; no raw input or parser exception is returned."""


def require(condition, reason):
    if not condition:
        raise CheckError(reason)


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def _pairs(items):
    value = {}
    for key, child in items:
        require(key not in value, "duplicate_metadata_key")
        value[key] = child
    return value


def _not_metadata_number(_value):
    raise CheckError("non_integer_metadata_number")


def strict_json(raw):
    try:
        return json.loads(raw, object_pairs_hook=_pairs, parse_float=_not_metadata_number,
                          parse_constant=_not_metadata_number)
    except (UnicodeError, json.JSONDecodeError, RecursionError) as error:
        raise CheckError("invalid_metadata_json") from error


def same(actual, expected):
    # JSON type identity matters: Boolean, integer and textual observations differ.
    if type(actual) is not type(expected):
        return False
    if type(expected) is dict:
        return actual.keys() == expected.keys() and all(same(actual[k], v) for k, v in expected.items())
    if type(expected) is list:
        return len(actual) == len(expected) and all(same(a, b) for a, b in zip(actual, expected))
    return actual == expected


def read_regular(path, maximum):
    require(stat.S_ISREG(path.lstat().st_mode), "input_not_regular")
    with path.open("rb") as stream:
        raw = stream.read(maximum + 1)
    require(len(raw) <= maximum, "input_size_limit")
    return raw


def load_inputs(root=ROOT):
    lock_raw = read_regular(root / LOCK_PATH, LOCK_BYTES)
    require(len(lock_raw) == LOCK_BYTES and digest(lock_raw) == LOCK_SHA256, "input_lock_identity")
    lock = strict_json(lock_raw)
    # Every executable/reference byte is captured and verified before any exec.
    # Input paths come only from this exact pinned lock, never from XML or operands.
    captured = {}
    for role, pin in lock["inputs"].items():
        raw = read_regular(root / pin["path"], pin["bytes"])
        require(len(raw) == pin["bytes"] and digest(raw) == pin["sha256"], "reference_input_identity")
        require(hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest()
                == pin["gitBlob"], "reference_git_blob_identity")
        captured[role] = raw
    require(set(captured) == {"prototype", "tests", "corpus", "frozen_reference"}, "reference_input_set")
    corpus = strict_json(captured["corpus"])
    require(corpus["profile"] == PROFILE and len(corpus["cases"]) == 104, "corpus_identity")
    return lock, captured, corpus["cases"]


@dataclass(frozen=True)
class _OriginalBytes:
    """Only path relocation: the original test reads already verified, exact bytes."""
    path: str
    raw: bytes

    def read_bytes(self):
        return self.raw

    def __str__(self):
        return self.path


@contextmanager
def _reference_modules(lock, captured):
    names = ("t0_typed_comparison_prototype", "android_typed_comparison_reference_tests")
    require(all(name not in sys.modules for name in names), "reference_namespace_busy")
    modules = {}
    try:
        for role, name in zip(("prototype", "tests"), names):
            path = lock["inputs"][role]["path"]
            module = types.ModuleType(name)
            module.__file__ = path
            module.__package__ = ""
            modules[name] = module
            sys.modules[name] = module
            exec(compile(captured[role], path, "exec"), module.__dict__)
        tests = modules[names[1]]
        tests.FIXTURE = _OriginalBytes(lock["inputs"]["corpus"]["path"], captured["corpus"])
        tests.REFERENCE = _OriginalBytes(lock["inputs"]["frozen_reference"]["path"], captured["frozen_reference"])
        yield modules[names[0]], tests
    finally:
        for name, module in modules.items():
            if sys.modules.get(name) is module:
                del sys.modules[name]


def _test_ids(suite):
    for item in suite:
        if isinstance(item, unittest.TestSuite):
            yield from _test_ids(item)
        else:
            yield item.id()


def verify_reference(root=ROOT):
    lock, captured, cases = load_inputs(root)
    with _reference_modules(lock, captured) as (typed, tests):
        suite = unittest.defaultTestLoader.loadTestsFromModule(tests)
        identities = list(_test_ids(suite))
        require(len(identities) == len(set(identities)) == 19, "reference_test_inventory")
        stream = io.StringIO()
        result = unittest.TextTestRunner(stream=stream, verbosity=2).run(suite)
        require(result.wasSuccessful() and result.testsRun == 19 and not result.skipped
                and not result.expectedFailures and not result.unexpectedSuccesses, "reference_tests_failed")
        actual = []
        for row in cases:
            operands = [None if row[key] is None else row[key].encode("utf-8")
                        for key in ("left_json", "right_json")]
            outcome = typed.compare(PROFILE, row["operator"], *operands)
            require(type(outcome) is typed.Outcome and outcome.value == row["expected"], "reference_vector_failed")
            actual.append({"case": row["name"], "operator": row["operator"], "outcome": outcome.value})
    return {
        "profile": PROFILE, "inputLockSHA256": LOCK_SHA256,
        "scope": "original Python reference tests and immutable synthetic vectors only",
        "referenceTests": 19, "referenceMethods": identities, "referenceLog": stream.getvalue(),
        "vectors": actual, "frozenReferenceSHA256": lock["inputs"]["frozen_reference"]["sha256"],
    }


def expected_native(lock, cases):
    expected = {FIXTURE_CLASS: {}, ADMISSION_CLASS: {}}
    for row in cases:
        operands = {field: None if row[key] is None else digest(row[key].encode("utf-8"))
                    for field, key in (("leftSHA256", "left_json"), ("rightSHA256", "right_json"))}
        expected[FIXTURE_CLASS]["typed-comparison:" + row["name"]] = {
            "profile": PROFILE, "case": row["name"], "operator": row["operator"],
            "outcome": row["expected"], **operands,
            "corpusSHA256": lock["inputs"]["corpus"]["sha256"],
            "prototypeSHA256": lock["inputs"]["prototype"]["sha256"],
            "inputsLockSHA256": LOCK_SHA256,
        }
    expected[FIXTURE_CLASS].update({name: None for name in FIXTURE_GUARDS})
    expected[ADMISSION_CLASS].update({name + "()": None for name in ADMISSION_METHODS})
    expected[ADMISSION_CLASS].update({
        "malformed:" + name + ":" + side: None for name in MALFORMED_NAMES for side in ("left", "right")})
    require(len(expected[FIXTURE_CLASS]) == 107 and len(expected[ADMISSION_CLASS]) == 68,
            "native_expected_inventory")
    return expected


def _no_observation(text):
    require(MARKER not in (text or ""), "observation_outside_case")


def qualify_xml(raw, class_name, expected):
    require(len(raw) <= MAX_XML_BYTES and b"\0" not in raw, "invalid_xml_bytes")
    try:
        text = raw.decode("utf-8", errors="strict")
    except UnicodeError as error:
        raise CheckError("invalid_xml_utf8") from error
    declaration = re.match(r"^\ufeff?<\?xml\b.*?\?>", text, re.DOTALL)
    if declaration:
        encoding = re.search(r"\bencoding\s*=\s*(['\"])([^'\"]+)\1", declaration[0])
        require(encoding is None or encoding[2].lower() == "utf-8", "invalid_xml_encoding")
    require(not re.search(r"<!\s*(?:DOCTYPE|ENTITY)\b", text, re.IGNORECASE), "xml_entity_declaration")
    try:
        suite = ET.fromstring(text)
    except ET.ParseError as error:
        raise CheckError("invalid_xml_document") from error
    require(suite.tag == "testsuite" and suite.get("name") == class_name
            and set(suite.attrib) == {"name", "tests", "skipped", "failures", "errors",
                                      "timestamp", "hostname", "time"}, "suite_identity")
    require(suite.get("tests") == str(len(expected))
            and all(suite.get(field) == "0" for field in ("failures", "errors", "skipped")), "suite_result")
    require(re.fullmatch(r"[0-9]+(?:\.[0-9]+)?", suite.get("time", "")), "suite_duration")
    require(all(node.tag in {"properties", "testcase", "system-out", "system-err"} for node in suite)
            and not (suite.text or "").strip()
            and all(not (node.tail or "").strip() for node in suite), "suite_shape")
    for tag in ("properties", "system-out", "system-err"):
        nodes = suite.findall(tag)
        require(len(nodes) <= 1, "repeated_suite_metadata")
        for node in nodes:
            _no_observation("".join(node.itertext()))
            if tag == "properties":
                require(not (node.text or "").strip(), "properties_text")
                for prop in node:
                    require(prop.tag == "property" and set(prop.attrib) == {"name", "value"}
                            and len(prop) == 0 and not (prop.text or "").strip()
                            and not (prop.tail or "").strip(), "property_shape")
                    _no_observation(prop.get("name"))
                    _no_observation(prop.get("value"))
            else:
                require(len(node) == 0, "nested_suite_output")
    cases = suite.findall("testcase")
    require(len(cases) == len(expected), "case_count")
    seen, observations = set(), []
    for case in cases:
        name = case.get("name")
        require(set(case.attrib) == {"name", "classname", "time"}
                and case.get("classname") == class_name and name in expected and name not in seen,
                "case_identity")
        require(re.fullmatch(r"[0-9]+(?:\.[0-9]+)?", case.get("time", "")), "case_duration")
        require(all(child.tag in {"system-out", "system-err"} and len(child) == 0 for child in case)
                and not (case.text or "").strip()
                and all(not (child.tail or "").strip() for child in case), "case_result")
        require(len(case.findall("system-out")) <= 1 and len(case.findall("system-err")) <= 1,
                "repeated_case_output")
        _no_observation(case.findtext("system-err"))
        stdout = case.findtext("system-out", "")
        wanted = expected[name]
        if wanted is None:
            require(not stdout.strip(), "guard_observation")
        else:
            lines = stdout.splitlines()
            require(len(lines) == 1 and lines[0].startswith(MARKER + " "), "case_observation")
            actual = strict_json(lines[0][len(MARKER) + 1:])
            require(same(actual, wanted), "case_observation_identity")
            observations.append(actual)
        seen.add(name)
    require(seen == expected.keys(), "native_attendance")
    return {"class": class_name, "methods": sorted(seen),
            "observations": sorted(observations, key=lambda row: row["case"]),
            "xmlBytes": len(raw), "xmlSHA256": digest(raw)}


def qualify_native(reports, exit_code, root=ROOT):
    require(type(exit_code) is int and exit_code == 0, "test_command_failed")
    lock, _captured, cases = load_inputs(root)
    expected = expected_native(lock, cases)
    suites = [qualify_xml(read_regular(reports / ("TEST-" + name + ".xml"), MAX_XML_BYTES), name, rows)
              for name, rows in expected.items()]
    return {"profile": PROFILE, "inputLockSHA256": LOCK_SHA256, "testCommandExitCode": exit_code,
            "scope": "exact two Gradle XML suites and typed observations only",
            "tests": 175, "vectors": 104, "suites": suites,
            "limits": "No checkout/toolchain authentication, whole-issue, installed, default-evaluator or product acceptance."}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reports", type=Path)
    parser.add_argument("--test-exit-code", type=int)
    args = parser.parse_args(argv)
    try:
        require((args.reports is None) == (args.test_exit_code is None), "paired_report_arguments")
        result = (verify_reference() if args.reports is None
                  else qualify_native(args.reports, args.test_exit_code))
    except (OSError, CheckError) as error:
        print("Typed comparison check failed: " + (str(error) if isinstance(error, CheckError) else "input_io"),
              file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    sys.exit(main())
