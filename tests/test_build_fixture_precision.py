"""Run the real Build checker on exact JSON tokens in isolated copied inputs.

The shared corpus also reaches the existing Kotlin dynamic decoder tests. These
CLI controls retain token spellings and check false positive fixture claims,
referenced schemas, non-JSON constants and input identity without running Kotlin.
"""
from contextlib import contextmanager
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
FIXTURE = Path("core/src/test/resources/contracts/build-cases.json")
SCRIPT = Path("scripts/check_build_fixtures.py")
INPUTS = (
    SCRIPT, FIXTURE, Path("contracts/v1/examples/build.json"),
    *(path.relative_to(ROOT) for path in
      sorted((ROOT / "contracts/v1/schemas").glob("*.schema.json"))),
)
BASELINE = (
    {"name": "baseline decoded", "expected": "decoded", "operations": []},
    {"name": "baseline version", "expected": "unsupported_version",
     "operations": [{"op": "set", "path": ["schema_version"], "value": "9.0.0"}]},
    {"name": "baseline mismatch", "expected": "schema_mismatch",
     "operations": [{"op": "remove", "path": ["revision"]}]},
)


class BuildFixturePrecisionTests(unittest.TestCase):
    @contextmanager
    def copied_checkout(self):
        with tempfile.TemporaryDirectory(prefix="build-fixture-precision-") as directory:
            root = Path(directory)
            for relative in INPUTS:
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ROOT / relative, target)
            yield root

    def snapshot(self, root):
        return {
            str(path.relative_to(root)): (
                path.read_bytes(), path.stat().st_mode,
                path.stat().st_ino, path.stat().st_mtime_ns,
            )
            for path in sorted(root.rglob("*")) if path.is_file()
        }

    def run_checker(self, root):
        before = self.snapshot(root)
        environment = {**os.environ, "PYTHONDONTWRITEBYTECODE": "1"}
        environment.pop("PYTHONOPTIMIZE", None)
        options = ["-O"] if sys.flags.optimize else []
        result = subprocess.run(
            [sys.executable, "-B", *options, str(root / SCRIPT)],
            cwd=root, env=environment, text=True, capture_output=True, timeout=20,
        )
        self.assertEqual(before, self.snapshot(root))
        return result

    def witness(self, root, path, token, expected="decoded"):
        original = json.loads((ROOT / FIXTURE).read_text(encoding="utf-8"),
                              parse_float=Decimal)
        fixture = {key: value for key, value in original.items() if key != "cases"}
        fixture["cases"] = [*BASELINE, {
            "name": "precision witness", "expected": expected,
            "operations": [{"op": "set", "path": path,
                            "value": "__EXACT_JSON_TOKEN__"}],
        }]
        raw = json.dumps(fixture, indent=2)
        marker = json.dumps("__EXACT_JSON_TOKEN__")
        self.assertEqual(1, raw.count(marker))
        (root / FIXTURE).write_text(raw.replace(marker, token) + "\n", encoding="utf-8")

    def assert_agreement(self, result, count=4):
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("", result.stderr)
        self.assertIn(f"{count} Build cases agree with the frozen Python schemas:",
                      result.stdout)

    def assert_false_decoded_claim_rejected(self, result):
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Build reference outcome differs: precision witness", result.stderr)
        self.assertNotIn("Build cases agree", result.stdout)

    def test_complete_shared_corpus_retains_all_215_cases(self):
        with self.copied_checkout() as root:
            result = self.run_checker(root)
            self.assert_agreement(result, count=215)
            self.assertIn("'decoded': 84, 'unsupported_version': 5, 'schema_mismatch': 126",
                          result.stdout)

    def test_fractional_values_cannot_be_decoded_as_any_inline_build_integer(self):
        paths = (
            ["revision"], ["locks", "dependency_graph_revision"],
            ["objectives", "requests_per_day"], ["objectives", "avg_input_tokens"],
            ["objectives", "avg_output_tokens"], ["objectives", "max_concurrency"],
            ["objectives", "max_latency_ms"],
        )
        with self.copied_checkout() as root:
            for path in paths:
                with self.subTest(path=path):
                    self.witness(root, path, "1.0000000000000001")
                    self.assert_false_decoded_claim_rejected(self.run_checker(root))

    def test_large_fraction_below_minimum_and_negative_underflow_stay_invalid(self):
        cases = (
            (["revision"], "9007199254740992.1"),
            (["revision"], "0.99999999999999999999"),
            (["objectives", "avg_input_tokens"], "-1e-9999"),
        )
        with self.copied_checkout() as root:
            for path, token in cases:
                with self.subTest(path=path, token=token):
                    self.witness(root, path, token)
                    self.assert_false_decoded_claim_rejected(self.run_checker(root))

    def test_referenced_selection_integer_and_number_bounds_keep_exact_values(self):
        cases = (
            (0, "concurrency", "1.0000000000000001"),
            (4, "requested_context_tokens", "1.0000000000000001"),
            (3, "max_output_tokens", "1.0000000000000001"),
            (0, "requests_per_day", "-1e-9999"),
            (3, "temperature", "2.0000000000000001"),
            (3, "top_p", "1.0000000000000001"),
            (22, "train_split", "1.0000000000000001"),
            (9, "provisioned_gb", "-1e-9999"),
            (0, "concurrency", "1e-400"),
            (0, "concurrency", "0.99999999999999999999"),
        )
        with self.copied_checkout() as root:
            for index, field, token in cases:
                with self.subTest(index=index, field=field, token=token):
                    self.witness(root, ["selections", str(index), "settings", field], token)
                    self.assert_false_decoded_claim_rejected(self.run_checker(root))

    def test_integral_decimal_exponents_and_signed_zero_remain_valid_through_refs(self):
        cases = (
            (["revision"], "1.0"),
            (["revision"], "1000e-3"),
            (["revision"], "1e40"),
            (["revision"], "9007199254740993"),
            (["objectives", "avg_input_tokens"], "-0.0"),
            (["selections", "0", "settings", "concurrency"], "1.0"),
            (["selections", "4", "settings", "requested_context_tokens"], "1000e-3"),
            (["selections", "4", "settings", "reserved_output_tokens"], "-0e-4"),
        )
        with self.copied_checkout() as root:
            for path, token in cases:
                with self.subTest(path=path, token=token):
                    self.witness(root, path, token)
                    self.assert_agreement(self.run_checker(root))

    def test_finite_numbers_outside_binary_float_range_remain_schema_eligible(self):
        cases = (
            (3, "top_p", "1e-400"),
            (20, "learning_rate", "1e-400"),
            (25, "minimum_score", "1e400"),
            (3, "temperature", "-0e-4"),
        )
        with self.copied_checkout() as root:
            for index, field, token in cases:
                with self.subTest(index=index, field=field, token=token):
                    self.witness(root, ["selections", str(index), "settings", field], token)
                    self.assert_agreement(self.run_checker(root))

    def test_boolean_and_other_nonnumeric_values_do_not_become_integers(self):
        paths = (["revision"], ["selections", "0", "settings", "concurrency"])
        with self.copied_checkout() as root:
            for path in paths:
                for token in ("true", "false", '"1.0"', "null", "[]", "{}"):
                    with self.subTest(path=path, token=token):
                        self.witness(root, path, token, expected="schema_mismatch")
                        self.assert_agreement(self.run_checker(root))

    def test_non_json_constants_are_rejected_even_inside_unconstrained_extensions(self):
        with self.copied_checkout() as root:
            for token in ("NaN", "Infinity", "-Infinity"):
                with self.subTest(token=token):
                    self.witness(root, ["extensions"], '{"x_untrusted":' + token + "}")
                    result = self.run_checker(root)
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn("Non-JSON numeric constant", result.stderr)
                    self.assertNotIn("Build cases agree", result.stdout)

    def test_every_pinned_schema_and_example_identity_is_still_required(self):
        paths = (
            *(Path("contracts/v1/schemas") / name for name in
              ("build.schema.json", "selection.schema.json", "common.schema.json")),
            Path("contracts/v1/examples/build.json"),
        )
        with self.copied_checkout() as root:
            self.witness(root, ["revision"], "1")
            for path in paths:
                with self.subTest(path=str(path)):
                    target = root / path
                    original = target.read_bytes()
                    try:
                        target.write_bytes(original + b"\n")
                        result = self.run_checker(root)
                        self.assertNotEqual(0, result.returncode)
                        kind = "example" if "examples" in path.parts else "schema"
                        self.assertIn(f"Build fixture {kind} identity changed", result.stderr)
                        self.assertNotIn("Build cases agree", result.stdout)
                    finally:
                        target.write_bytes(original)


if __name__ == "__main__":
    unittest.main(verbosity=2)
