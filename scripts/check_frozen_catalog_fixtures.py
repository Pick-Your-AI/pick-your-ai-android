"""Check the synthetic catalog wire mutations against unchanged frozen Python schemas."""
import copy
import hashlib
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

from frozen_fixture_numbers import FixtureValidator, read_json

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "contracts/v1"
INPUTS = {
    "schemas/catalog-snapshot.schema.json", "schemas/catalog-item.schema.json",
    "schemas/common.schema.json", "examples/catalog.json",
}


def mutated(original, case):
    if case["target"] == "item":
        value = copy.deepcopy(next(item for item in original["items"] if item["kind"] == case["kind"]))
    elif case["target"] == "snapshot":
        value = copy.deepcopy(original)
        if "kind" in case:
            value["items"] = [next(item for item in value["items"] if item["kind"] == case["kind"])]
    else:
        raise ValueError("Unknown catalog fixture target")
    for operation in case.get("operations", []):
        if operation.get("op") not in {"set", "remove"}:
            raise ValueError("Unknown catalog fixture operation")
        expected_keys = {"op", "path", "value"} if operation["op"] == "set" else {"op", "path"}
        if set(operation) != expected_keys or not isinstance(operation["path"], list) or not operation["path"]:
            raise ValueError("Malformed catalog fixture operation")
        if not all(type(part) in {str, int} for part in operation["path"]):
            raise ValueError("Malformed catalog fixture path")
        parent = value
        for part in operation["path"][:-1]:
            parent = parent[part]
        final = operation["path"][-1]
        if operation["op"] == "set":
            parent[final] = copy.deepcopy(operation["value"])
        else:
            del parent[final]
    return value


def checker_schema(path):
    schema = read_json(path)
    if schema.pop("$schema", None) != Draft202012Validator.META_SCHEMA["$id"]:
        raise ValueError("Unexpected frozen Catalog schema dialect")
    # Snapshot items reach an external root schema. Select its known dialect
    # explicitly so that reference traversal retains the exact integer checker.
    # Only decoded private copies omit this dispatch key; frozen bytes stay exact.
    return schema


def main():
    fixture = read_json(ROOT / "core/src/test/resources/contracts/frozen-catalog-cases.json")
    if set(fixture) != {"profile", "inputs", "cases"} or fixture["profile"] != "android-frozen-catalog-wire/v1":
        raise ValueError("Unknown catalog fixture profile")
    if set(fixture["inputs"]) != INPUTS:
        raise ValueError("Catalog fixture input inventory changed")
    for path, expected in fixture["inputs"].items():
        if hashlib.sha256((BASE / path).read_bytes()).hexdigest() != expected:
            raise ValueError("Catalog fixture input identity changed")
    schemas = [checker_schema(path) for path in (BASE / "schemas").glob("*.schema.json")]
    registry = Registry().with_resources(
        (schema["$id"], Resource.from_contents(schema, default_specification=DRAFT202012))
        for schema in schemas
    )
    formats = FormatChecker()
    if not {"date-time", "uri"} <= formats.checkers.keys():
        raise ValueError("Catalog format checkers unavailable; install requirements-contracts.txt")
    validators = {
        target: FixtureValidator(
            checker_schema(BASE / f"schemas/{name}.schema.json"),
            registry=registry, format_checker=formats,
        ) for target, name in (("item", "catalog-item"), ("snapshot", "catalog-snapshot"))
    }
    original = read_json(BASE / "examples/catalog.json")
    names = set()
    counts = {"decoded": 0, "unsupported_version": 0, "schema_mismatch": 0}
    for case in fixture["cases"]:
        if set(case) - {"name", "target", "kind", "expected", "operations"}:
            raise ValueError("Unsupported catalog fixture field")
        if not isinstance(case["name"], str) or not case["name"] or case["name"] in names:
            raise ValueError("Catalog fixture names must be unique")
        names.add(case["name"])
        value = mutated(original, case)
        if value.get("schema_version") != "1.0.0":
            outcome = "unsupported_version"
        else:
            outcome = "decoded" if validators[case["target"]].is_valid(value) else "schema_mismatch"
        if outcome != case["expected"]:
            raise ValueError(f"Catalog reference outcome differs: {case['name']}")
        counts[outcome] += 1
    if len(names) != 225 or not all(counts.values()):
        raise ValueError("Expected all 225 positive, version and malformed catalog cases")
    print(f"{len(names)} catalog cases agree with the frozen Python schemas: {counts}")


if __name__ == "__main__":
    main()
