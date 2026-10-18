#!/usr/bin/env python3
"""Check pinned fixture inputs and qualify complete, per-test Gradle XML observations.

This is a fixture receipt, not command/source authentication or device/evaluator
acceptance. CI separately retains the actual command and clean-source inventory.
"""

from __future__ import annotations

import argparse
from collections import Counter
from decimal import Decimal
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import re
import stat
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RESOURCE = Path("core/src/test/resources/conformance")
LOCK_SHA256 = "9b794c27b0f162a86b5729bdb6cd29b8dc68fa78d67af1ba691af98c34f6a057"
WIRE_CLASS = "ai.pickyour.core.conformance.CrossClientFixtureTest"
MIGRATION_CLASS = "ai.pickyour.core.conformance.LocalMigrationFixtureTest"
GUARDS = {
    WIRE_CLASS: {
        "changedPayloadCannotReuseAQualifiedFixtureIdentity()",
        "tamperedOrTruncatedFixtureTransportCannotReachTheAdapter()",
    },
    MIGRATION_CLASS: {"migrationInputTransportCannotChangeOrGrowBehindItsVersion()"},
}
MARKERS = ("ANDROID_WIRE_CASE", "ANDROID_MIGRATION_CASE")
MAX_REPORT_BYTES = 2_000_000


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(value):
    return hashlib.sha256(value).hexdigest()


def unique_object(pairs):
    value = {}
    for key, item in pairs:
        require(key not in value, "Duplicate decoded JSON key")
        value[key] = item
    return value


def reject_constant(value):
    raise ValueError("Nonfinite JSON constant: " + value)


def strict_json(data):
    try:
        value = json.loads(data, object_pairs_hook=unique_object,
                           parse_float=Decimal, parse_constant=reject_constant)
        # Reject escaped unpaired surrogates too; payloadJSON remains original
        # JSON text, whose own escaped negative vectors are not decoded here.
        def check_strings(item):
            if isinstance(item, str):
                item.encode("utf-8", errors="strict")
            elif isinstance(item, dict):
                for key, child in item.items():
                    check_strings(key)
                    check_strings(child)
            elif isinstance(item, list):
                for child in item:
                    check_strings(child)
        check_strings(value)
        return value
    except (UnicodeError, RecursionError) as error:
        raise ValueError("Invalid bounded JSON text") from error


def strictly_equal(actual, expected):
    if type(actual) is not type(expected):
        return False
    if isinstance(expected, dict):
        return actual.keys() == expected.keys() and all(
            strictly_equal(actual[key], value) for key, value in expected.items())
    if isinstance(expected, list):
        return len(actual) == len(expected) and all(
            strictly_equal(a, b) for a, b in zip(actual, expected))
    return actual == expected


def read_regular(path, limit):
    info = path.lstat()
    require(stat.S_ISREG(info.st_mode) and info.st_size <= limit,
            "Input must be a bounded regular file: " + str(path))
    with path.open("rb") as stream:
        data = stream.read(limit + 1)
    require(len(data) <= limit, "Input grew beyond its limit")
    return data


def pinned(path, pin):
    data = read_regular(path, pin["bytes"])
    require(len(data) == pin["bytes"] and digest(data) == pin["sha256"],
            "Pinned input differs: " + str(path))
    return data


def load_inputs(root=ROOT):
    lock_bytes = read_regular(root / RESOURCE / "consumer-lock.json", 20_000)
    require(digest(lock_bytes) == LOCK_SHA256, "Consumer lock differs from its reviewed version")
    lock = strict_json(lock_bytes)
    require(lock["version"] == "android-fixture-consumer-1", "Unsupported consumer version")
    neutral, private = lock["neutralProducer"], lock["privateAndroidStorage"]
    packed = pinned(root / neutral["transport"]["path"], neutral["transport"])
    with gzip.GzipFile(fileobj=io.BytesIO(packed)) as stream:
        raw = stream.read(neutral["bytes"] + 1)
    require(len(raw) == neutral["bytes"] and digest(raw) == neutral["sha256"],
            "Transport does not recover the exact neutral producer")
    require(hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest()
            == neutral["originalBlob"], "Neutral Git blob differs")
    wire = strict_json(raw)
    require(wire["bundleVersion"] == neutral["bundleVersion"]
            and wire["contractVersion"] == neutral["sharedContractVersion"]
            and wire["referenceLock"]["source_revision"] == neutral["referenceRevision"]
            and wire["websiteRevision"] == neutral["websiteRevision"], "Neutral provenance differs")
    cases = wire["cases"]
    require(len(cases) == 223 and len({case["id"] for case in cases}) == 223,
            "Neutral cases are missing or repeated")
    require(dict(Counter(case["contract"] for case in cases)) == neutral["contracts"],
            "Neutral contract profile differs")
    for case in cases:
        require(type(case["valid"]) is bool and isinstance(case["payloadJSON"], str),
                "Wire expectation or original payload type differs")
        require(digest(case["payloadJSON"].encode("utf-8")) == case["payloadSHA256"],
                "Wire payload differs from its case identity")
    require(sum(case["valid"] for case in cases) == 141, "Wire admission profile differs")

    migration = strict_json(pinned(root / private["path"], private))
    require(migration["bundleVersion"] == private["bundleVersion"]
            and migration["source"] == private["sourceBasis"], "Private fixture provenance differs")
    inputs = {item["id"]: item for item in migration["inputs"]}
    require(len(inputs) == len(migration["inputs"]) == 10, "Private input set differs")
    for item in inputs.values():
        raw_input = item["payloadJSON"].encode("utf-8")
        require(len(raw_input) == item["bytes"] and digest(raw_input) == item["sha256"],
                "Original private input bytes differ")
    private_cases = migration["cases"]
    require(len(private_cases) == len({case["id"] for case in private_cases}) == 34,
            "Private scenarios are missing or repeated")
    for case in private_cases:
        require(case["input"] in inputs and case["expected"]["originalPreserved"] is True,
                "Private input or original-retention expectation differs")

    expected = {WIRE_CLASS: {}, MIGRATION_CLASS: {}}
    for case in cases:
        expected[WIRE_CLASS]["wire:" + case["id"]] = {
            "bundleVersion": neutral["bundleVersion"], "id": case["id"],
            "accepted": case["valid"], "preserved": True if case["valid"] else None,
        }
    for case in private_cases:
        expected[MIGRATION_CLASS]["migration:" + case["id"]] = {
            "bundleVersion": private["bundleVersion"], "id": case["id"],
            "inputSha256": inputs[case["input"]]["sha256"], **case["expected"],
        }
    for class_name, guards in GUARDS.items():
        expected[class_name].update({name: None for name in guards})
    return lock, expected


def no_observations(text):
    require(not any(marker in (text or "") for marker in MARKERS),
            "An observation occurs outside its exact test case")


def qualify_xml(raw, class_name, expected):
    require(len(raw) <= MAX_REPORT_BYTES, "Report exceeds the bounded XML budget")
    # Gradle emits UTF-8. Bind the declaration guard to exactly the text parsed:
    # Expat otherwise auto-detects UTF-16 and can expand a byte-guarded entity.
    # Literal NUL is invalid XML, including in an otherwise ASCII-looking input.
    require(b"\0" not in raw, "Report must be UTF-8 without raw NUL")
    try:
        text = raw.decode("utf-8", errors="strict")
    except UnicodeError as error:
        raise ValueError("Report must be genuine UTF-8") from error
    declaration = re.match(r"^\ufeff?<\?xml\b.*?\?>", text, re.DOTALL)
    if declaration is not None:
        encoding = re.search(r"\bencoding\s*=\s*(['\"])([^'\"]+)\1", declaration[0])
        require(encoding is None or encoding[2].lower() == "utf-8",
                "Report encoding declaration must be UTF-8")
    require(not re.search(r"<!\s*(?:DOCTYPE|ENTITY)\b", text, re.IGNORECASE),
            "DTD/entity declarations cannot qualify a report")
    suite = ET.fromstring(text)
    require(suite.tag == "testsuite" and suite.get("name") == class_name,
            "Wrong test suite identity")
    require(set(suite.attrib) == {"name", "tests", "skipped", "failures", "errors",
                                  "timestamp", "hostname", "time"}, "Unexpected suite metadata")
    require(suite.get("tests") == str(len(expected)) and all(
        suite.get(field) == "0" for field in ("failures", "errors", "skipped")),
        "The exact test suite did not fully pass")
    require(re.fullmatch(r"[0-9]+(?:\.[0-9]+)?", suite.get("time", "")), "Invalid suite duration")
    require(all(child.tag in {"properties", "testcase", "system-out", "system-err"}
                for child in suite), "Unexpected suite result element")
    require(not (suite.text or "").strip() and all(not (child.tail or "").strip() for child in suite),
            "Unexpected text outside suite elements")
    cases = suite.findall("testcase")
    require(len(cases) == len(expected), "Reported count does not match actual test cases")
    for tag in ("system-out", "system-err", "properties"):
        require(len(suite.findall(tag)) <= 1, "Repeated suite metadata or output")
        for node in suite.findall(tag):
            no_observations("".join(node.itertext()))
            if tag == "properties":
                require(not (node.text or "").strip(), "Unexpected properties text")
                for prop in node:
                    require(prop.tag == "property" and set(prop.attrib) == {"name", "value"}
                            and len(prop) == 0 and not (prop.text or "").strip()
                            and not (prop.tail or "").strip(), "Malformed report property")
                    no_observations(prop.get("name"))
                    no_observations(prop.get("value"))
            else:
                require(len(node) == 0, "Nested suite output cannot qualify")
    seen, observations = set(), []
    for case in cases:
        name = case.get("name")
        require(set(case.attrib) == {"name", "classname", "time"}
                and case.get("classname") == class_name and name in expected and name not in seen,
                "Unknown, repeated or incorrectly attributed test identity")
        require(re.fullmatch(r"[0-9]+(?:\.[0-9]+)?", case.get("time", "")), "Invalid case duration")
        require(all(child.tag in {"system-out", "system-err"} for child in case),
                "A case failed, skipped, retried or contains an unknown result")
        require(not (case.text or "").strip() and all(not (child.tail or "").strip() for child in case),
                "Unexpected text outside case output")
        for child in case:
            require(len(child) == 0, "Nested result output cannot qualify")
        require(len(case.findall("system-out")) <= 1 and len(case.findall("system-err")) <= 1,
                "Repeated case output")
        no_observations(case.findtext("system-err"))
        wanted = expected[name]
        stdout = case.findtext("system-out", "")
        if wanted is None:
            require(not stdout.strip(), "A guard cannot stand in for a fixture observation")
        else:
            marker = MARKERS[0 if class_name == WIRE_CLASS else 1] + " "
            lines = stdout.splitlines()
            require(len(lines) == 1 and lines[0].startswith(marker),
                    "Missing, extra or malformed per-case observation")
            actual = strict_json(lines[0][len(marker):])
            require(strictly_equal(actual, wanted), "Observation differs from the exact test expectation")
            observations.append(actual)
        seen.add(name)
    require(seen == expected.keys(), "The exact fixture and guard set did not execute")
    return {"class": class_name, "methods": sorted(seen),
            "observations": sorted(observations, key=lambda item: item["id"]),
            "xmlBytes": len(raw), "xmlSHA256": digest(raw)}


def qualify(reports, exit_code, root=ROOT):
    require(type(exit_code) is int and exit_code == 0, "The actual test command did not succeed")
    lock, expected = load_inputs(root)
    suites = []
    for class_name, cases in expected.items():
        path = reports / ("TEST-" + class_name + ".xml")
        suites.append(qualify_xml(read_regular(path, MAX_REPORT_BYTES), class_name, cases))
    return {
        "receiptVersion": "android-fixture-receipt-1", "commandExitCode": exit_code,
        "consumerLockSHA256": LOCK_SHA256,
        "wireBundleVersion": lock["neutralProducer"]["bundleVersion"],
        "wireSHA256": lock["neutralProducer"]["sha256"],
        "migrationBundleVersion": lock["privateAndroidStorage"]["bundleVersion"],
        "migrationSHA256": lock["privateAndroidStorage"]["sha256"],
        "tests": 260, "wireObservations": 223, "migrationObservations": 34, "suites": suites,
        "limitations": [
            "Qualification binds exact fixture bytes, named cases and typed per-case observations.",
            "Actual command, checkout, compiled output and inventory must be independently retained and checked.",
            "No installed Android, evaluator, service or product acceptance follows from this receipt.",
        ],
    }


def prepare_output(output, reports, root=ROOT):
    output = output.parent.resolve(strict=True) / output.name
    source = root.resolve()
    require(not output.is_relative_to(source) or output.is_relative_to(source / "build/conformance"),
            "Receipt output must not replace source")
    if reports is not None:
        require(not output.is_relative_to(reports.resolve()), "Receipt output must not replace its reports")
    try:
        info = output.lstat()
    except FileNotFoundError:
        return output
    require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1,
            "Existing output must be an unshared regular file")
    output.unlink()  # A later failed attempt must not leave an older successful receipt.
    return output


def publish(output, result):
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=output.parent,
                                         prefix=".fixture-receipt-", suffix=".tmp", delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(json.dumps(result, sort_keys=True, indent=2) + "\n")
        os.replace(temporary, output)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reports", type=Path, help="Actual Gradle per-test JUnit XML directory")
    parser.add_argument("--exit-code", type=int, help="Actual command status, not inferred from XML")
    parser.add_argument("--output", type=Path, help="Receipt path; old success is invalidated before checking")
    args = parser.parse_args()
    try:
        output = prepare_output(args.output, args.reports) if args.output is not None else None
        if args.reports is None and args.exit_code is None and output is None:
            load_inputs()
            print("PASS pinned 223 neutral cases and 34 private Android migration scenarios; fixture-only scope")
            return
        require(args.reports is not None and args.exit_code is not None and output is not None,
                "Qualification requires reports, actual exit code and output together")
        result = qualify(args.reports, args.exit_code)
        publish(output, result)
    except (ValueError, OSError, EOFError, ET.ParseError, KeyError, TypeError) as error:
        parser.exit(1, "Conformance receipt rejected: " + str(error) + "\n")
    print("PASS 260 exact tests, 223 wire observations and 34 private migration observations; no device claim")


if __name__ == "__main__":
    main()
