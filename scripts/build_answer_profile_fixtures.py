"""Reproduce synthetic CD1-0.2 observations from the exact accepted Research input.

No network, input upgrade, field adoption, evaluator adoption or device execution.
The reviewed original modules run in a private copy after every source hash is
checked. This helper deliberately requires the Research verifier's Python and
jsonschema versions; Android CI consumes the resulting fixed fixture offline.
"""
import argparse
import base64
from dataclasses import asdict
from decimal import Decimal, localcontext
import hashlib
import importlib
import importlib.metadata
import json
from pathlib import Path
import struct
import sys
import tempfile
import types
from jsonschema.exceptions import SchemaError
from reference_fixture_output import output_path, write_binary_output

RESEARCH_COMMIT = "cebbf59345e01d34b8ad4c20d0b57782f6e05369"
RESEARCH_TREE = "6e9f196d091fb0fb89443c3fc0e6adcf05607c55"
PROFILE = "cd1-0.2"
FIXTURE_VERSION = "android-cd1-reference-1"
SOURCE_PINS = {
    "tests/t0_answer_attribution_prototype.py": {
        "sha256": "e3caec2f7db53cce66c3778a5faf31c04cc52aca7f555a3fba2ec0da174d3469",
        "bytes": 6364,
        "blob": "4f17b43f330b65294525d24c16a219cb59e75419"
    },
    "tests/t0_answer_state_prototype.py": {
        "sha256": "722fa8af1e3453413f2cd3a65161fbf9102ea1c1d57725e3dcd03c6167cd5631",
        "bytes": 13218,
        "blob": "01a713cc7021a6f42e9b7b3850b334527904f652"
    },
    "tests/t0_known_value_schema.py": {
        "sha256": "1d7878298f4c3c3b8de5a2ade13a8c3b2f408d52b79df2d082e8102153da99dd",
        "bytes": 6426,
        "blob": "2691fac7def1590c8e4a4ad20258cd630f206d98"
    },
    "tests/selection_quantifier_prototype.py": {
        "sha256": "2db82645589d292ac17c89413bae340187ddad7b7751c9dc5b670d1713b677f7",
        "bytes": 4953,
        "blob": "922cc7a882cc2cccbe122e98127253d71c12205f"
    },
    "tests/fixtures/t0-answer-attribution.schema.json": {
        "sha256": "958e090b22c3ec6f58da4e0a52740a6b9693d5237467a1b9cfd8ab0c795089ac",
        "bytes": 1546,
        "blob": "7302418b45c9d53d4c54e534a05b07358ae7a4b1"
    },
    "tests/fixtures/t0-answer-states.schema.json": {
        "sha256": "51e18c1b5694daf905aee539712dd0a9e46e78be0ca8e438d758628d88357e0d",
        "bytes": 1370,
        "blob": "91aab5e6bae86f3b7dfb8e504a65c02502c6defb"
    },
    "tests/fixtures/t0-answer-attribution.json": {
        "sha256": "b5c198899830d9c4e7e0acce4f72903be1eb69497e935c0238725d1d2ec4dc8b",
        "bytes": 786,
        "blob": "d7f0c9f33d88cb17d18fa1c443a8630a0c9b1061"
    },
    "research/T0-configuration/component-schema-compatibility.md": {
        "sha256": "e000ea7c05fb53b061ce7af9060f072eae89544079a62bd3626928e222a9a640",
        "bytes": 275985,
        "blob": "8ee5a7b7a960ae63c073c996cfdc73b80d939e7c"
    }
}
ROOT = Path(__file__).resolve().parents[1]
DESTINATION = ROOT / "core/src/test/resources/evidence/answers"


def require(value, message):
    if not value:
        raise SystemExit(message)


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def encoded(value):
    return json.dumps(value, ensure_ascii=True, allow_nan=False, separators=(",", ":"))


def original(answers):
    return encoded({"x_t0_answer_states": {"proposal_version": PROFILE, "answers": answers}}).encode()


def provided(value, **metadata):
    return {"state": "provided", "value": value, "assumption": False, **metadata}


def generate(research):
    require(sys.version_info[:2] == (3, 12), "Python 3.12 is required for the selected reference")
    require(importlib.metadata.version("jsonschema") == "4.25.1", "jsonschema 4.25.1 is required")
    raw_sources = {}
    require(len(SOURCE_PINS) == 8, "The exact source manifest is missing")
    for path, pin in SOURCE_PINS.items():
        raw = (research / path).read_bytes()
        require(len(raw) == pin["bytes"] and digest(raw) == pin["sha256"], "Source pin differs: " + path)
        require(hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest() == pin["blob"],
                "Source Git blob differs: " + path)
        raw_sources[path] = raw
    with tempfile.TemporaryDirectory(prefix="android-cd1-reference-") as directory:
        private = Path(directory)
        for path, raw in raw_sources.items():
            if not path.startswith("tests/"):
                continue
            output = private / path
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_bytes(raw)
        # A private namespace avoids importing an unrelated installed 'tests'
        # package. The six original executable/schema files are unchanged.
        require(not any(name == "tests" or name.startswith("tests.") for name in sys.modules),
                "Run this generator in a fresh process")
        namespace = types.ModuleType("tests")
        namespace.__path__ = [str(private / "tests")]
        sys.modules["tests"] = namespace
        reference = importlib.import_module("tests.t0_answer_attribution_prototype")
        state = importlib.import_module("tests.t0_answer_state_prototype")
        cases = make_cases(reference, state, raw_sources["tests/fixtures/t0-answer-attribution.json"])
        for path, raw in raw_sources.items():
            if path.startswith("tests/"):
                require((private / path).read_bytes() == raw, "Executed source changed: " + path)
    require(len({case["id"] for case in cases}) == len(cases), "Repeated fixture identity")
    for path, raw in raw_sources.items():
        require((research / path).read_bytes() == raw, "Source changed during execution: " + path)
    document = {"fixtureVersion": FIXTURE_VERSION, "answerProfile": PROFILE,
                "researchCommit": RESEARCH_COMMIT, "cases": cases}
    fixture = (json.dumps(document, ensure_ascii=True, allow_nan=False, indent=2) + "\n").encode()
    lock = {"fixtureVersion": FIXTURE_VERSION, "answerProfile": PROFILE,
            "researchRepository": "https://github.com/Pick-Your-AI/pick-your-ai-research",
            "researchCommit": RESEARCH_COMMIT, "researchTree": RESEARCH_TREE,
            "sourceFiles": SOURCE_PINS,
            "referenceRuntime": {"python": "3.12", "jsonschema": "4.25.1"},
            "fixture": {"path": "cd1-reference.json", "bytes": len(fixture), "sha256": digest(fixture),
                        "cases": len(cases), "readCases": sum(c["operation"] == "read" for c in cases),
                        "ruleCases": sum(c["operation"] == "rule" for c in cases)},
            "scope": "Synthetic normalized answer/profile observations; no released wire, editor, evidence or evaluator adoption."}
    return fixture, (json.dumps(lock, ensure_ascii=True, indent=2) + "\n").encode()


def make_cases(reference, state, canonical_fixture):
    cases = []

    def read_case(identity, raw=None, *, value=None, schema=None, pointer="/answer", answers=None):
        if raw is None:
            raw = original(answers if answers is not None else {"/answer": provided(value)})
        schema = {} if schema is None else schema
        row = {"id": identity, "operation": "read", "inputBase64": base64.b64encode(raw).decode(),
               "inputSHA256": digest(raw), "pointer": pointer, "schema": encoded(schema)}
        try:
            index = reference.AttributedAnswerIndex(raw)
            answer = index.read(pointer, schema)
            kind = ("absent" if not answer.provided else
                    {type(None): "null", bool: "boolean", int: "integer", float: "number",
                     str: "string", list: "array", dict: "object"}[type(answer.value)])
            row["expected"] = {"status": "read", "state": answer.state.value, "valueKind": kind,
                               "value": answer.value, "sourcePath": answer.source_path,
                               "presetSource": answer.source, "assumption": answer.assumption}
            require(index.original_bytes == raw, "Reference changed original input")
        except (ValueError, UnicodeError, SchemaError):
            row["expected"] = {"status": "rejected"}
        cases.append(row)

    for index, value in enumerate((False, True, 0, 0.0, None, [], {}, "", "unknown", "Unknown", "未知")):
        read_case(f"literal-{index}", value=value)
        read_case(f"typed-literal-{index}", value=value, schema={"const": value})
    for answer_state in ("unknown", "missing", "withheld", "not_applicable"):
        for pointer in ("/answer", "/answer/child"):
            read_case(f"{answer_state}-{pointer.count('/')}", pointer=pointer,
                      answers={"/answer": {"state": answer_state}}, schema={"enum": ["unknown"]})
        for extra, value in (("value", None), ("assumption", False), ("source", "preset:sample")):
            read_case(f"unresolved-extra-{answer_state}-{extra}", answers={"/answer": {"state": answer_state, extra: value}})
    for index, path in enumerate(("/synthetic/workload/requests/count", "/synthetic/workload/network",
                                  "/synthetic/workload/external_access", "/synthetic/workload/organization/child",
                                  "/synthetic/workload/budget", "/synthetic/workload/training",
                                  "/synthetic/workload/absent", "/synthetic/workload")):
        read_case(f"accepted-synthetic-{index}", canonical_fixture, pointer=path)
    parent = provided({"false": False, "null": None, "values": [0, "unknown"], "a/b~c": {"": 7}},
                      source="preset:parent-v2", assumption=True)
    for index, pointer in enumerate(("/answer", "/answer/false", "/answer/null", "/answer/values/0",
                                    "/answer/values/1", "/answer/values/2", "/answer/values/999999999999999999999",
                                    "/answer/values/00", "/answer/values/-", "/answer/false/child",
                                    "/answer/a~1b~0c/", "/answer/absent")):
        read_case(f"provided-parent-{index}", answers={"/answer": parent}, pointer=pointer)
    for index, answers in enumerate(({}, {"/a/x": provided(1), "/a/y": provided(2)},
                                    {"/a/\ue000": {"state": "withheld"}, "/a/\U00010000": {"state": "withheld"}},
                                    {"/a/withheld": {"state": "withheld"}, "/ab": provided(1)},
                                    {"/a": provided({}), "/a/x": provided(2)})):
        read_case(f"parent-materialization-{index}", answers=answers, pointer="/a")
    for index, pointer in enumerate(("", "answer", "#/answer", "/bad~", "/bad~2x", "/", "/a//")):
        read_case(f"pointer-{index}", pointer=pointer)
        read_case(f"record-pointer-{index}", answers={pointer: provided(1)}, pointer="/answer")
    for index, source in enumerate(("preset:a", "preset:" + "x" * 64, "preset:" + "x" * 65,
                                    "preset:", "preset:a\n", " preset:a", "preset:a/b", "preset:é", None, True)):
        read_case(f"source-{index}", answers={"/answer": provided(1, source=source)})
    for index, assumption in enumerate((False, True, None, 0, 1, "false", [], {})):
        read_case(f"assumption-{index}", answers={"/answer": provided(1, assumption=assumption)})
    valid = original({"/answer": provided(1)})
    invalid = [valid.replace(b'cd1-0.2', b'cd1-0.1'), valid.replace(b',"assumption":false', b''),
               valid.replace(b'"state":"provided"', b'"state":"provided","\\u0073tate":"provided"'),
               valid.replace(b'"value":1', b'"value":1,"value":2'),
               valid + b'{}', b'\xef\xbb\xbf' + valid, valid.decode().encode('utf-16'),
               valid.replace(b'"value":1', b'"value":"\\ud800"'),
               valid.replace(b'"value":1', b'"value":"\xff"'),
               valid.replace(b'"state":"provided"', b'"state":"not_selected"')]
    for index, raw in enumerate(invalid):
        read_case(f"original-admission-{index}", raw)

    known = {"type": "string", "enum": ["none", "session", "persistent"]}
    unsafe = {"type": "string", "enum": ["none", "persistent", "unknown"]}
    for index, schema in enumerate((unsafe, {"const": "unknown"}, {"type": 1}, False, True,
                                    {"$ref": "#"}, {"$ref": "https://example.invalid/schema"},
                                    {"$schema": "http://json-schema.org/draft-07/schema#", **known},
                                    {"$dynamicRef": "#memory"},
                                    {"$ref": "#/default", "default": known},
                                    {"$ref": "#/$defs", "$defs": {"memory": known}},
                                    {"$ref": "#/$defs/bad%FF", "$defs": {"bad": known}},
                                    {"$ref": "#/$defs/bad~2name", "$defs": {"bad~2name": known}},
                                    {"$ref": "#anchor", "$defs": {"memory": {"$anchor": "anchor", **known}}})):
        read_case(f"known-profile-{index}", value="persistent", schema=schema)
    for index, (name, reference_path) in enumerate((("memory", "#/$defs/memory"),
                                                  ("a/b~c", "#/$defs/a~1b~0c"),
                                                  ("memory mode", "#/$defs/memory%20mode"),
                                                  ("$id", "#/$defs/$id"))):
        for label, vocabulary in (("safe", known), ("unsafe", unsafe)):
            read_case(f"reference-{index}-{label}", value="persistent",
                      schema={"$ref": reference_path, "$defs": {name: vocabulary}})
    read_case("root-id", value="persistent", schema={"$id": "urn:synthetic:field",
              "$ref": "#/$defs/memory", "$defs": {"memory": known}})
    read_case("nested-id-jump", value="persistent", schema={"$ref": "#/$defs/nested/properties/value",
              "$defs": {"nested": {"$id": "urn:nested", "properties": {"value": known}}}})
    read_case("unused-definition", value="persistent", schema={"$ref": "#/$defs/memory",
              "$defs": {"memory": known, "unused": unsafe}})
    read_case("recursive-progress", value={"memory": "persistent", "next": {"memory": "session"}},
              schema={"type": "object", "properties": {"memory": known, "next": {"$ref": "#"}}})
    for index, keyword in enumerate(("items", "contains", "prefixItems", "additionalProperties",
                                    "unevaluatedProperties", "unevaluatedItems", "if", "then", "else",
                                    "allOf", "anyOf", "oneOf")):
        child = [unsafe] if keyword in ("prefixItems", "allOf", "anyOf", "oneOf") else unsafe
        read_case(f"schema-reachable-{index}", value="persistent", schema={keyword: child})
    for index, schema in enumerate(({"not": {"const": "unknown"}}, {"not": {"enum": ["unknown", "legacy"]}},
                                    {"not": {"not": {"const": "unknown"}}},
                                    {"not": {"oneOf": [{"const": "unknown"}, {"const": "unknown"}]}})):
        for value in ("persistent", "unknown"):
            read_case(f"negation-schema-{index}-{value}", value=value, schema=schema)
    read_case("property-name-is-not-value", value={"unknown": "known"}, schema={"type": "object",
              "propertyNames": {"enum": ["unknown"]}, "properties": {"unknown": {"type": "string"}}})
    read_case("property-ref-context", value={"unknown": "unknown"}, schema={"type": "object",
              "$defs": {"shared": {"const": "unknown"}}, "propertyNames": {"$ref": "#/$defs/shared"},
              "properties": {"unknown": {"$ref": "#/$defs/shared"}}})
    for index, value in enumerate(({"x": "unknown"}, ["unknown"], {"x": ["unknown"]}, {"unknown": "known"})):
        read_case(f"structured-vocabulary-{index}", value=value, schema={"const": value})
    read_case("annotations-remain-data", value="unknown", schema={"type": "string", "default": "unknown",
              "examples": ["unknown"], "x-synthetic": {"const": "unknown"}})
    read_case("formats-are-not-asserted", value="not-a-date", schema={"type": "string", "format": "date-time"})
    for index, (pattern, value) in enumerate((("", "未知"), (r"^memory\.[A-Za-z_]{1,12}$", "memory.persistent"),
            (r"^memory\.[A-Za-z_]{1,12}$", "memory.unknown1"), ("^a.*z$", "a\nz"), ("^a.*z$", "a\rz"),
            ("^a.*z$", "a\u2028z"), ("^a.*z$", "a😀z"), ("[A-Za-z]", "123x"),
            ("^a{2,4}$", "aaa"), ("^a{2,4}$", "aaaaa"), ("^a{2,4}$", "aa\n"),
            ("^unknown$", "unknown"), (r"^a\[b\]$", "a[b]"), (r"^[A-Za-z0-9_.:-]{1,64}$", "id_1:a-b"))):
        read_case(f"portable-pattern-{index}", value=value, schema={"type": "string", "pattern": pattern})
    read_case("portable-pattern-property", value={"mode.primary": "persistent"}, schema={"type": "object",
              "patternProperties": {r"^mode\.": known}, "additionalProperties": False})
    read_case("portable-pattern-property-invalid", value={"mode.primary": "legacy"}, schema={"type": "object",
              "patternProperties": {r"^mode\.": known}, "additionalProperties": False})
    for index, (value, schema) in enumerate((("😀", {"minLength": 1, "maxLength": 1}),
            ([1], {"maxItems": 2147483647}), ([], {"minItems": 2147483647}),
            ([], {"contains": {"type": "integer"}, "minContains": 0, "maxContains": 2147483647}),
            ({"a": 1}, {"maxProperties": 2147483647}), ([1, 1.0], {"uniqueItems": True}),
            ([True, 1], {"uniqueItems": True}), ([{"n": 1}, {"n": 1.0}], {"uniqueItems": True}),
            ([{"n": True}, {"n": 1}], {"uniqueItems": True}))):
        read_case(f"structural-schema-{index}", value=value, schema=schema)

    tokens = ["0.0", "-0.0", "0", "-0", "0.1", "1.25", "1e3", "1e-300", "5e-324", "4.9e-324",
              "9007199254740992.0", "9007199254740993", "9007199254740993.0", "1e23", "1e20",
              "1e-400", "1e999", "NaN", "Infinity", "0.10000000000000000001", "0.99999999999999999"]
    # Fixed IEEE bit patterns and their shortest decimal controls, derived from
    # SHA-256 identities rather than the runtime's random-number implementation.
    for index in range(96):
        bits = hashlib.sha256(f"SYNTHETIC-CD1-NUMBER-{index}".encode()).digest()[:8]
        value = struct.unpack(">d", bits)[0]
        if not (-float("inf") < value < float("inf")):
            continue
        token = repr(value)
        tokens.append(token)
        with localcontext() as context:
            context.prec = 100
            decimal = Decimal(token)
            tokens.append(str(decimal + Decimal(1).scaleb(decimal.as_tuple().exponent - 20)))
    for index, token in enumerate(dict.fromkeys(tokens)):
        raw = ('{"x_t0_answer_states":{"proposal_version":"cd1-0.2","answers":'
               '{"/answer":{"state":"provided","value":' + token + ',"assumption":false}}}}').encode()
        read_case(f"number-{index}", raw, schema={"type": "number"})
    for index, (token, schema) in enumerate((("9007199254740993", {"minimum": 9007199254740993}),
                                             ("9007199254740993", {"maximum": 9007199254740992}),
                                             ("9007199254740993", {"maximum": 9007199254740993.0}),
                                             ("99999999999999991611392", {"maximum": 1e23}),
                                             ("99999999999999991611393", {"maximum": 1e23}),
                                             ("0.1", {"multipleOf": 0.1}),
                                             ("0.3", {"multipleOf": 0.1}),
                                             ("5e-324", {"minimum": 5e-324}),
                                             ("0.1", {"const": 0.10000000000000001}),
                                             ("3", {"multipleOf": 0.1}),
                                             ("3.0", {"multipleOf": 0.1}),
                                             ("-3", {"multipleOf": 0.1}),
                                             ("1", {"const": 1.0}),
                                             ("true", {"const": 1}),
                                             ("99999999999999991611392", {"const": 1e23}),
                                             ("99999999999999991611393", {"const": 1e23}),
                                             ("99999999999999991611391", {"minimum": 1e23}),
                                             ("99999999999999991611392", {"exclusiveMinimum": 1e23}),
                                             ("99999999999999991611393", {"exclusiveMinimum": 1e23}),
                                             ("99999999999999991611392", {"exclusiveMaximum": 1e23}),
                                             ("99999999999999991611391", {"exclusiveMaximum": 1e23}),
                                             ("100000000000000000000000", {"enum": [1e23]}),
                                             ("100000000000000000000000", {"enum": [100000000000000000000000]}),
                                             ("1e20", {"multipleOf": 1e-300}),
                                             ("-0.0", {"const": 0}),
                                             ("6.0", {"multipleOf": 3}),
                                             ("6.5", {"multipleOf": 3}),
                                             ("6", {"multipleOf": 3}))):
        raw = ('{"x_t0_answer_states":{"proposal_version":"cd1-0.2","answers":'
               '{"/answer":{"state":"provided","value":' + token + ',"assumption":false}}}}').encode()
        read_case(f"numeric-schema-{index}", raw, schema=schema)

    variants = [(state.State.PROVIDED, value) for value in (True, False, None)]
    variants += [(item, None) for item in (state.State.UNKNOWN, state.State.MISSING,
                                         state.State.WITHHELD, state.State.NOT_APPLICABLE)]
    for policy in state.RulePolicy:
        for ai, (app_state, app_value) in enumerate(variants):
            for si, (assert_state, assert_value) in enumerate(variants):
                for negate in (False, True):
                    calls = {"appliesWhen": 0, "assertion": 0}
                    severity = ("info", "warning", "error")[(ai + si) % 3]
                    def descriptor(position, answer_state, truth, index):
                        return {"state": answer_state.value, "truth": truth, "sourcePath": "/" + position,
                                "presetSource": "preset:" + position if answer_state is state.State.PROVIDED else None,
                                "assumption": bool(index % 2) if answer_state is state.State.PROVIDED else None}
                    app = descriptor("appliesWhen", app_state, app_value, ai)
                    assertion = descriptor("assertion", assert_state, assert_value, si + 1)
                    def callback(position, description):
                        def invoke():
                            calls[position] += 1
                            answer = reference.AttributedPredicate(state.State(description["state"]), description["truth"],
                                description["sourcePath"], description["presetSource"], description["assumption"])
                            return answer.negated() if negate else answer
                        return invoke
                    result = reference.evaluate_attributed_rule(state.AnswerRule("synthetic.answer", severity, policy,
                        callback("appliesWhen", app), callback("assertion", assertion)))
                    finding = asdict(result.finding)
                    cases.append({"id": f"rule-{policy.value}-{ai}-{si}-{int(negate)}", "operation": "rule",
                        "ruleId": "synthetic.answer", "severity": severity, "policy": policy.value,
                        "appliesWhen": app, "assertion": assertion, "negate": negate,
                        "expected": {"ruleId": finding["rule_id"], "severity": finding["severity"],
                            "outcome": finding["outcome"], "reason": finding["reason"], "position": finding["position"],
                            "answerState": finding["answer_state"], "sourcePath": finding["source_path"],
                            "presetSource": result.source, "assumption": result.assumption, "calls": dict(calls)}})
    return cases


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--research-root", type=Path, required=True)
    parser.add_argument("--write", action="store_true", help="Write the two explicit synthetic fixture files")
    args = parser.parse_args()
    try:
        relative_outputs = tuple((DESTINATION / name).relative_to(ROOT) for name in
                                 ("cd1-reference.json", "cd1-reference-lock.json"))
        for relative in relative_outputs:
            output_path(ROOT, relative)
        fixture, lock = generate(args.research_root.resolve())
        # Check the complete pair before any read or first-file publication.
        targets = tuple(output_path(ROOT, relative) for relative in relative_outputs)
        if args.write:
            for relative, raw in zip(relative_outputs, (fixture, lock)):
                write_binary_output(ROOT, relative, raw)
        else:
            require(targets[0].read_bytes() == fixture, "Fixture differs")
            require(targets[1].read_bytes() == lock, "Fixture lock differs")
    except (OSError, ValueError) as error:
        parser.exit(1, "CD1 reference output failed: " + str(error) + "\n")
    print("CD1_REFERENCE_PASS " + json.dumps(json.loads(lock)["fixture"], sort_keys=True))


if __name__ == "__main__":
    main()
