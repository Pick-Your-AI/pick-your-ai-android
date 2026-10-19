"""Android #36 reference shapes only; no HTTP adapter, authority or successor API."""
import copy
import hashlib
import importlib.metadata
import importlib.util
import json
from pathlib import Path
import unittest

import yaml
from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "contracts/v1"
FIXTURE_PATH = ROOT / "tests/fixtures/android-api-consumer-intake-v1.json"
EXPECTED_IDS = {
    f"{prefix}{number:02}" for prefix, count in
    (("B", 11), ("C", 4), ("R", 8), ("F", 4), ("K", 5),
     ("E", 6), ("P", 4), ("O", 6), ("H", 4), ("X", 6))
    for number in range(1, count + 1)
}
EXAMPLES = {
    "build": "build", "catalog": "catalog", "resources": "resource-estimate",
    "report": "evaluation-report", "cost": "cost-estimate",
}
SCHEMAS = {**EXAMPLES, "catalog": "catalog-snapshot"}
POINTERS = {
    "evaluate_request": "/paths/~1builds~1{build_id}~1evaluate/post/requestBody/content/application~1json/schema",
    "cost_request": "/paths/~1builds~1{build_id}~1estimate-cost/post/requestBody/content/application~1json/schema",
    "options": "/paths/~1builds~1{build_id}~1compatible-options/get/responses/200/content/application~1json/schema",
    "idempotency_key": "/components/parameters/idempotencyKey/schema",
    "if_match": "/components/parameters/ifMatch/schema",
    "error": "/components/schemas/Error",
}
OPERATIONS = {
    ("/component-kinds", "get"): ("listComponentKinds", {"200"}),
    ("/catalog/snapshots/{snapshot_id}", "get"): ("readCatalogSnapshot", {"200", "404"}),
    ("/dependencies/{dependency_id}", "get"): ("readDependencyGraph", {"200", "404"}),
    ("/rulesets/{ruleset_id}", "get"): ("readRuleset", {"200", "404"}),
    ("/pricebooks/{pricebook_id}", "get"): ("readPricebook", {"200", "404"}),
    ("/builds", "post"): ("createBuild", {"201", "422"}),
    ("/builds/{build_id}", "get"): ("readBuild", {"200", "404"}),
    ("/builds/{build_id}", "put"): ("replaceBuildIfRevisionMatches", {"200", "409", "422"}),
    ("/builds/{build_id}/evaluate", "post"): ("evaluateBuild", {"200", "409", "422"}),
    ("/builds/{build_id}/resource-estimates", "post"): ("publishResourceEstimate", {"201", "422"}),
    ("/builds/{build_id}/estimate-cost", "post"): ("estimateCost", {"200", "422"}),
    ("/builds/{build_id}/quality-runs", "post"): ("recordQualityEvaluationRun", {"201", "422"}),
    ("/builds/{build_id}/compatible-options", "get"): ("listCompatibleOptions", {"200"}),
    ("/builds/{build_id}/deployment-proposals", "post"): ("proposeDeployment", {"201", "422"}),
}


def pointer_parts(pointer):
    if not isinstance(pointer, str) or not pointer.startswith("/"):
        raise ValueError("A nonempty local JSON pointer is required")
    return [part.replace("~1", "/").replace("~0", "~") for part in pointer[1:].split("/")]


def member(value, key):
    return value[int(key)] if isinstance(value, list) else value[key]


def at_pointer(value, pointer):
    for part in pointer_parts(pointer):
        value = member(value, part)
    return value


def validate_fixture(fixture):
    if set(fixture) != {"case_format", "contract_version", "upstream_revision", "openapi_sha256", "cases"}:
        raise ValueError("Unexpected fixture fields")
    if fixture["case_format"] != "android-api-consumer-intake-1" or fixture["contract_version"] != "1.0.0":
        raise ValueError("Unsupported fixture version")
    if fixture["upstream_revision"] != "9769fcd9ea380b1211669b2753181c7782444178":
        raise ValueError("Frozen upstream identity changed")
    if fixture["openapi_sha256"] != "6a08d7230c23576889167da6cff48d95093da9dc04d4bfa6ece443ff35089a79":
        raise ValueError("Frozen OpenAPI identity changed")
    if not isinstance(fixture["cases"], list):
        raise ValueError("Cases must be a list")
    ids = []
    for case in fixture["cases"]:
        required = {"id", "target", "schema_valid", "observation"}
        if not isinstance(case, dict) or not required <= case.keys() or case.keys() - required - {"example", "payload", "set", "remove"}:
            raise ValueError("Unexpected case fields")
        if type(case["schema_valid"]) is not bool or case["target"] not in SCHEMAS.keys() | POINTERS.keys():
            raise ValueError("Invalid expectation or target")
        if not isinstance(case["id"], str) or not isinstance(case["observation"], str) or not case["observation"]:
            raise ValueError("Invalid case identity or observation")
        if ("example" in case) == ("payload" in case):
            raise ValueError("A case needs exactly one source")
        if "example" in case and case["example"] != f"examples/{EXAMPLES.get(case['target'])}.json":
            raise ValueError("Only the target's pinned synthetic example may be read")
        if not isinstance(case.get("set", {}), dict) or not isinstance(case.get("remove", []), list):
            raise ValueError("Invalid mutation container")
        for pointer in list(case.get("set", {})) + case.get("remove", []):
            pointer_parts(pointer)
        ids.append(case["id"])
    if len(ids) != len(set(ids)) or set(ids) != EXPECTED_IDS:
        raise ValueError("The complete unique 58-case inventory is required")
    return fixture


FIXTURE = validate_fixture(json.loads(FIXTURE_PATH.read_text(encoding="utf-8")))
API = None
VALIDATORS = {}


def setUpModule():
    # Use the existing complete import gate before reading any schema/example.
    spec = importlib.util.spec_from_file_location("android_intake_integrity", ROOT / "scripts/check_contracts.py")
    integrity = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(integrity)
    integrity.verify()
    for name, expected in (("jsonschema", "4.25.1"), ("PyYAML", "6.0.3")):
        if importlib.metadata.version(name) != expected:
            raise ValueError(f"Install pinned requirements-contracts.txt: {name}")
    raw = (BASE / "api/openapi.yaml").read_bytes()
    if len(raw) != 12079 or hashlib.sha256(raw).hexdigest() != FIXTURE["openapi_sha256"]:
        raise ValueError("Frozen OpenAPI bytes changed")
    global API
    API = yaml.safe_load(raw)
    schemas = [json.loads(path.read_text(encoding="utf-8")) for path in (BASE / "schemas").glob("*.schema.json")]
    # Registry's default retrieval rejects unregistered URIs; no network loader is installed.
    registry = Registry().with_resources((schema["$id"], Resource.from_contents(schema)) for schema in schemas)
    formats = FormatChecker()
    if not {"date-time", "uri"} <= formats.checkers.keys():
        raise ValueError("Required format checkers are unavailable")
    for target in SCHEMAS.keys() | POINTERS.keys():
        schema = (json.loads((BASE / "schemas" / f"{SCHEMAS[target]}.schema.json").read_text(encoding="utf-8"))
                  if target in SCHEMAS else at_pointer(API, POINTERS[target]))
        Draft202012Validator.check_schema(schema)
        VALIDATORS[target] = Draft202012Validator(schema, registry=registry, format_checker=formats)


def materialize(case):
    value = (json.loads((BASE / case["example"]).read_text(encoding="utf-8"))
             if "example" in case else copy.deepcopy(case["payload"]))
    for pointer, replacement in case.get("set", {}).items():
        parts = pointer_parts(pointer)
        parent = value
        for key in parts[:-1]:
            parent = member(parent, key)
        key = int(parts[-1]) if isinstance(parent, list) else parts[-1]
        parent[key] = copy.deepcopy(replacement)
    for pointer in case.get("remove", []):
        parts = pointer_parts(pointer)
        parent = value
        for key in parts[:-1]:
            parent = member(parent, key)
        key = int(parts[-1]) if isinstance(parent, list) else parts[-1]
        del parent[key]
    return value


class ApiIntakeCases(unittest.TestCase):
    """Each synthetic vector has its own reported unittest identity."""


def case_test(case):
    def test(self):
        self.assertEqual(case["schema_valid"], VALIDATORS[case["target"]].is_valid(materialize(case)),
                         case["observation"])
    return test


for _case in FIXTURE["cases"]:
    setattr(ApiIntakeCases, f"test_{_case['id']}_{_case['target']}", case_test(_case))


class ApiIntakeBoundaryTest(unittest.TestCase):
    def test_complete_existing_operations_and_response_mapping(self):
        self.assertEqual("3.1.0", API["openapi"])
        self.assertEqual("1.0.0", API["info"]["version"])
        self.assertEqual([{"url": "/v1"}], API["servers"])
        observed = {(path, method): (operation["operationId"], set(operation["responses"]))
                    for path, methods in API["paths"].items() for method, operation in methods.items()}
        self.assertEqual(OPERATIONS, observed)

    def test_all_operations_keep_bearer_and_writes_keep_required_headers(self):
        self.assertEqual([{"bearerAuth": []}], API["security"])
        writes = 0
        for (path, method) in OPERATIONS:
            operation = API["paths"][path][method]
            self.assertNotIn("security", operation)
            refs = {param.get("$ref") for param in operation.get("parameters", [])}
            if method != "get":
                writes += 1
                self.assertIn("#/components/parameters/idempotencyKey", refs)
            if method == "put":
                self.assertIn("#/components/parameters/ifMatch", refs)
        self.assertEqual(7, writes)
        for name in ("idempotencyKey", "ifMatch"):
            self.assertIs(True, API["components"]["parameters"][name]["required"])

    def test_fixture_cannot_silently_drop_duplicate_or_relabel_cases(self):
        for mutation in ("empty", "drop", "duplicate", "non_boolean", "external_example"):
            with self.subTest(mutation=mutation):
                bad = copy.deepcopy(FIXTURE)
                if mutation == "empty":
                    bad["cases"] = []
                elif mutation == "drop":
                    bad["cases"].pop()
                elif mutation == "duplicate":
                    bad["cases"][1]["id"] = bad["cases"][0]["id"]
                elif mutation == "non_boolean":
                    bad["cases"][0]["schema_valid"] = 1
                else:
                    bad["cases"][0]["example"] = "../unreviewed.json"
                with self.assertRaises(ValueError):
                    validate_fixture(bad)

    def test_reference_retains_exact_wide_integer_and_decimal_text(self):
        by_id = {case["id"]: case for case in FIXTURE["cases"]}
        decimal = materialize(by_id["B06"])["objectives"]["monthly_budget_amount"]
        revision = materialize(by_id["B11"])["revision"]
        self.assertIs(type(decimal), str)
        self.assertEqual("9007199254740993.123456789012", decimal)
        self.assertIs(type(revision), int)
        self.assertEqual(9007199254740993, revision)
        self.assertIn('"revision": 9007199254740993', json.dumps(materialize(by_id["B11"])))


if __name__ == "__main__":
    unittest.main()
