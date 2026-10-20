"""Exercise the ResourceEstimate reference CLI with exact original JSON number tokens."""
from decimal import Decimal
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = Path("scripts/check_resource_estimate_fixtures.py")
FIXTURE = Path("core/src/test/resources/contracts/resource-estimate-cases.json")
EXAMPLE = Path("contracts/v1/examples/resource-estimate.json")
SCHEMA = Path("contracts/v1/schemas/resource-estimate.schema.json")
SUCCESS = "resource-estimate cases agree with the frozen Python schema"


class ResourceEstimatePrecisionTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        shutil.copytree(ROOT / "contracts/v1/schemas",
                        self.root / "contracts/v1/schemas")
        for relative in (SCRIPT, FIXTURE, EXAMPLE):
            destination = self.root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / relative, destination)
        self.fixture = json.loads((ROOT / FIXTURE).read_text(encoding="utf-8"),
                                  parse_float=Decimal)

    def run_checker(self):
        before = {str(path.relative_to(self.root)): path.read_bytes()
                  for path in self.root.rglob("*") if path.is_file()}
        result = subprocess.run(
            [sys.executable, "-B", str(self.root / SCRIPT)],
            cwd=self.root, text=True, capture_output=True, timeout=30,
            env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"},
        )
        after = {str(path.relative_to(self.root)): path.read_bytes()
                 for path in self.root.rglob("*") if path.is_file()}
        self.assertEqual(before, after, "Reference verification must not alter inputs")
        return result

    def install_isolated_case(self, name, token, expected):
        # Keep independent valid, version and ordinary-malformed controls. Replacing
        # the full cases list isolates a false approval from other new negatives.
        cases = [
            {"name": "ordinary valid control", "expected": "decoded"},
            {"name": "version control", "expected": "unsupported_version",
             "set": {"schema_version": "2.0.0"}},
            {"name": "ordinary fractional control", "expected": "schema_mismatch",
             "set": {"build_revision": 1.5}},
        ]
        raw = [json.dumps(case) for case in cases]
        raw.append('{"name": ' + json.dumps(name) + ', "expected": '
                   + json.dumps(expected) + ', "set": {"build_revision": '
                   + token + '}}')
        header = {key: self.fixture[key] for key in
                  ("profile", "schema_sha256", "example_sha256")}
        header["cases"] = []
        content = json.dumps(header, indent=2).replace(
            '"cases": []', '"cases": [\n    ' + ",\n    ".join(raw) + "\n  ]",
        ) + "\n"
        (self.root / FIXTURE).write_text(content, encoding="utf-8")

    def test_complete_shared_corpus_passes_with_exact_numbers(self):
        result = self.run_checker()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn(f"{len(self.fixture['cases'])} {SUCCESS}", result.stdout)
        self.assertEqual("", result.stderr)

    def test_fractional_bounds_and_wrong_types_cannot_be_claimed_decoded(self):
        # The first four tokens would round to integral binary floats in the
        # original checker. The remaining controls retain minimum/type rejection.
        tokens = (
            "9007199254740992.1", "1.0000000000000001",
            "0.99999999999999999", "123456789012345678901234567890.1",
            "0.0", "-1e2", "true", "null", '"1"',
        )
        for index, token in enumerate(tokens):
            with self.subTest(token=token):
                name = f"false decoded claim {index}"
                self.install_isolated_case(name, token, "decoded")
                result = self.run_checker()
                self.assertEqual(1, result.returncode, result.stdout + result.stderr)
                self.assertIn(f"Resource-estimate reference outcome differs: {name}",
                              result.stderr)
                self.assertNotIn(SUCCESS, result.stdout)

    def test_non_json_constants_do_not_become_schema_fixture_values(self):
        for token in ("NaN", "Infinity", "-Infinity"):
            with self.subTest(token=token):
                self.install_isolated_case("non-JSON constant", token, "schema_mismatch")
                result = self.run_checker()
                self.assertEqual(1, result.returncode, result.stdout + result.stderr)
                self.assertIn("Resource-estimate fixtures require finite JSON numbers",
                              result.stderr)
                self.assertNotIn(SUCCESS, result.stdout)

    def test_original_input_pins_still_gate_validation(self):
        for relative in (SCHEMA, EXAMPLE):
            with self.subTest(path=str(relative)):
                path = self.root / relative
                original = path.read_bytes()
                try:
                    path.write_bytes(original + b"\n")
                    result = self.run_checker()
                    self.assertEqual(1, result.returncode, result.stdout + result.stderr)
                    self.assertIn("Resource-estimate fixture input identity changed",
                                  result.stderr)
                    self.assertNotIn(SUCCESS, result.stdout)
                finally:
                    path.write_bytes(original)


if __name__ == "__main__":
    unittest.main()
