"""Check synthetic deployment-manifest cases against the unchanged frozen Python schema."""
import copy
import hashlib
import json
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "contracts/v1"


def main():
    fixture = json.loads((ROOT / "core/src/test/resources/contracts/deployment-manifest-cases.json").read_text())
    if fixture["profile"] != "android-frozen-deployment-manifest-wire/v1":
        raise ValueError("Unknown deployment-manifest fixture profile")
    for key, path in (
        ("schema_sha256", BASE / "schemas/deployment-manifest.schema.json"),
        ("example_sha256", BASE / "examples/deployment-manifest.json"),
    ):
        if hashlib.sha256(path.read_bytes()).hexdigest() != fixture[key]:
            raise ValueError("Deployment-manifest fixture input identity changed")
    schemas = [json.loads(path.read_text()) for path in (BASE / "schemas").glob("*.schema.json")]
    registry = Registry().with_resources((schema["$id"], Resource.from_contents(schema)) for schema in schemas)
    schema = json.loads((BASE / "schemas/deployment-manifest.schema.json").read_text())
    formats = FormatChecker()
    if not {"date-time", "uri"}.issubset(formats.checkers):
        raise ValueError("Manifest format checkers unavailable; install requirements-contracts.txt")
    validator = Draft202012Validator(schema, registry=registry, format_checker=formats)
    original = json.loads((BASE / "examples/deployment-manifest.json").read_text())
    names = set()
    counts = {"decoded": 0, "unsupported_version": 0, "schema_mismatch": 0}
    for case in fixture["cases"]:
        if not isinstance(case["name"], str) or not case["name"] or case["name"] in names:
            raise ValueError("Deployment-manifest names must be nonempty and unique")
        names.add(case["name"])
        if set(case) - {"name", "expected", "set", "remove", "approval_set", "approval_remove"}:
            raise ValueError("Unsupported deployment-manifest mutation")
        value = copy.deepcopy(original)
        value.update(case.get("set", {}))
        for key in case.get("remove", []):
            del value[key]
        if "approval_set" in case:
            value["approvals"][0].update(case["approval_set"])
        for key in case.get("approval_remove", []):
            del value["approvals"][0][key]
        if value.get("schema_version") != "1.0.0":
            outcome = "unsupported_version"
        else:
            outcome = "decoded" if validator.is_valid(value) else "schema_mismatch"
        if outcome != case["expected"]:
            raise ValueError(f"Deployment-manifest reference outcome differs: {case['name']}")
        counts[outcome] += 1
    if not all(counts.values()):
        raise ValueError("Manifest fixtures must retain positive, version and malformed cases")
    print(f"{len(names)} deployment-manifest cases agree with the frozen Python schema: {counts}")


if __name__ == "__main__":
    main()
