"""Check actual frozen-document pattern cases with the pinned Python reference validator."""
import copy
import hashlib
import json
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "contracts/v1"


def patterns(value):
    if isinstance(value, dict):
        for key, child in value.items():
            if key == "pattern":
                yield child
            yield from patterns(child)
    elif isinstance(value, list):
        for child in value:
            yield from patterns(child)


def replace(value, pointer, replacement):
    parts = pointer.removeprefix("/").split("/")
    parent = value
    for part in parts[:-1]:
        parent = parent[int(part)] if isinstance(parent, list) else parent[part]
    key = int(parts[-1]) if isinstance(parent, list) else parts[-1]
    parent[key] = replacement


def main():
    fixture = json.loads((ROOT / "core/src/test/resources/contracts/frozen-pattern-cases.json").read_text())
    if fixture["profile"] != "android-frozen-pattern-wire/v1":
        raise ValueError("Unknown frozen-pattern profile")
    schema_paths = sorted((BASE / "schemas").glob("*.schema.json"))
    required_inputs = {path.relative_to(BASE).as_posix() for path in schema_paths}
    required_inputs.update("examples/" + entry["example"] + ".json" for entry in fixture["inputs"].values())
    if len(required_inputs) != 20 or set(fixture["input_sha256"]) != required_inputs:
        raise ValueError("Frozen-pattern pins must cover every bundled schema and used example exactly")
    for path, expected in fixture["input_sha256"].items():
        if hashlib.sha256((BASE / path).read_bytes()).hexdigest() != expected:
            raise ValueError("Frozen-pattern input identity changed")
    schemas = [json.loads(path.read_text()) for path in schema_paths]
    actual_patterns = {pattern for schema in schemas for pattern in patterns(schema)}
    covered_patterns = {item["pattern"] for item in fixture["inputs"].values() if item["pattern"] is not None}
    if len(actual_patterns) != 9 or actual_patterns != covered_patterns:
        raise ValueError("Frozen-pattern coverage differs from the complete bundled pattern set")
    formats = FormatChecker()
    if not {"date-time", "uri"} <= formats.checkers.keys():
        raise ValueError("Frozen-pattern format checkers unavailable; install requirements-contracts.txt")
    registry = Registry().with_resources((schema["$id"], Resource.from_contents(schema)) for schema in schemas)
    validators = {schema["$id"].rsplit("/", 1)[-1].removesuffix(".schema.json"):
                  Draft202012Validator(schema, registry=registry, format_checker=formats) for schema in schemas}
    names = set()
    counts = {"valid": 0, "schema_mismatch": 0}
    for case in fixture["cases"]:
        if set(case) != {"name", "input", "value", "expected"} or not case["name"] or case["name"] in names:
            raise ValueError("Frozen-pattern cases must be named uniquely and use only supported fields")
        names.add(case["name"])
        entry = fixture["inputs"][case["input"]]
        value = copy.deepcopy(json.loads((BASE / "examples" / (entry["example"] + ".json")).read_text()))
        replace(value, entry["pointer"], case["value"])
        outcome = "valid" if validators[entry["schema"]].is_valid(value) else "schema_mismatch"
        if outcome != case["expected"]:
            raise ValueError(f"Frozen-pattern reference outcome differs: {case['name']}")
        counts[outcome] += 1
    if len(names) != 160 or not all(counts.values()):
        raise ValueError("Frozen-pattern matrix lost a required positive or negative case")
    print(f"{len(names)} frozen-pattern cases match all nine Python schema patterns: {counts}")


if __name__ == "__main__":
    main()
