"""Exercise the offline intake command with actual bounded file inputs."""

from copy import deepcopy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
COMMAND = ROOT / "scripts/check_android_source_use.py"
REGISTER = ROOT / "docs/source-use/android-source-use-v1.json"
EXAMPLE = ROOT / "docs/source-use/requests/models-dev-offline.json"


class AndroidSourceUseTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.register_bytes = REGISTER.read_bytes()
        self.register = json.loads(self.register_bytes)
        self.digest = hashlib.sha256(self.register_bytes).hexdigest()
        self.proposal = json.loads(EXAMPLE.read_bytes())

    def command(self, *arguments, expected):
        before = {path: path.read_bytes() for path in (COMMAND, REGISTER, EXAMPLE)}
        result = subprocess.run(
            [sys.executable, str(COMMAND), *map(str, arguments)],
            cwd=self.directory, capture_output=True, text=True, timeout=10,
        )
        self.assertEqual(expected, result.returncode, result.stdout + result.stderr)
        self.assertEqual("", result.stderr)
        self.assertEqual(1, len(result.stdout.splitlines()), result.stdout)
        receipt = json.loads(result.stdout)
        self.assertIs(receipt["promoted"], False)
        self.assertEqual(before, {path: path.read_bytes() for path in before})
        return receipt

    def file(self, name, value):
        path = self.directory / name
        path.write_bytes(value if isinstance(value, bytes)
                         else (json.dumps(value, ensure_ascii=True) + "\n").encode())
        return path

    def attempt(self, *, source=None, material=None, uses=None, changes=None,
                expected=1, register=None):
        proposal = deepcopy(self.proposal)
        for key, value in (("source_id", source), ("material_id", material), ("uses", uses)):
            if value is not None:
                proposal[key] = value
        proposal.update(changes or {})
        path = self.file("proposal.json", proposal)
        arguments = ["--proposal", path]
        if register is not None:
            arguments += ["--register", register]
        receipt = self.command(*arguments, expected=expected)
        self.assertEqual(proposal, json.loads(path.read_bytes()))
        return receipt

    def invalid_register(self, mutate, reason):
        value = deepcopy(self.register)
        mutate(value)
        path = self.file("register.json", value)
        receipt = self.command("--check-register", "--register", path, expected=2)
        self.assertEqual(reason, receipt["reason"])
        self.assertEqual(value, json.loads(path.read_bytes()))

    def test_checked_register_is_consistent_without_promoting_a_source(self):
        receipt = self.command("--check-register", expected=0)
        self.assertEqual("register_consistent", receipt["result"])
        self.assertEqual((10, 60), (receipt["sources"], receipt["use_decisions"]))
        self.assertEqual(self.digest, receipt["register_sha256"])
        self.assertEqual([], list(self.directory.iterdir()))

    def test_documented_models_proposal_rejects_unapproved_feed_and_offline_cache(self):
        receipt = self.command("--proposal", EXAMPLE, expected=1)
        self.assertEqual(self.digest, self.proposal["register_sha256"])
        self.assertEqual(["integration_not_approved"], receipt["reason_codes"])
        self.assertEqual(
            ["use_conditions_not_received", "use_conditions_not_received",
             "android_use_not_reviewed"],
            [item["reason"] for item in receipt["uses"]],
        )
        self.assertEqual([], list(self.directory.iterdir()))

    def test_all_sixty_material_use_attempts_remain_blocked(self):
        attended = set()
        for record in self.register["records"]:
            for use in record["uses"]:
                with self.subTest(source=record["source_id"], use=use):
                    receipt = self.attempt(
                        source=record["source_id"], material=record["material_id"],
                        uses=[use],
                    )
                    self.assertEqual("blocked", receipt["result"])
                    self.assertEqual(["integration_not_approved"], receipt["reason_codes"])
                    self.assertEqual(record["uses"][use]["disposition"],
                                     receipt["uses"][0]["disposition"])
                    attended.add((record["source_id"], use))
        self.assertEqual(60, len(attended))

    def test_gpus_product_exclusion_overrides_historical_retrieval_and_display_yes(self):
        record = next(row for row in self.register["records"] if row["id"] == "gpus-io")
        for use in ("automated_retrieval", "commercial_display"):
            self.assertEqual("yes", record["uses"][use]["research_value"])
        receipt = self.attempt(source="src-gpus-io", material="service-data",
                               uses=["automated_retrieval", "commercial_display", "export"])
        self.assertEqual(["use_excluded"] * 3, [item["reason"] for item in receipt["uses"]])

    def test_internal_benchlm_access_does_not_become_an_android_product_feed(self):
        receipt = self.attempt(source="src-benchlm", material="owned-service-outputs",
                               uses=["automated_retrieval", "commercial_display"])
        self.assertEqual(["use_excluded"] * 2, [item["reason"] for item in receipt["uses"]])

    def test_mcp_downstream_metadata_intent_does_not_grant_android_cache_or_retention(self):
        receipt = self.attempt(source="src-mcp-registry", material="registry-metadata",
                               uses=["commercial_display", "offline_caching", "retention"])
        self.assertEqual(["use_conditions_not_received", "android_use_not_reviewed",
                          "android_use_not_reviewed"],
                         [item["reason"] for item in receipt["uses"]])

    def test_paper_scope_does_not_admit_the_separate_harness_repository(self):
        paper = self.attempt(source="src-harness-bench", material="paper-v2", uses=["export"])
        self.assertEqual("use_conditions_not_received", paper["uses"][0]["reason"])
        repository = self.attempt(source="src-harness-bench", material="repository-tasks",
                                  uses=["export"])
        self.assertEqual(["unreviewed_material"], repository["reason_codes"])
        self.assertEqual([], repository["uses"])

    def test_hugging_face_factual_identifiers_do_not_admit_cards_weights_or_bulk_exports(self):
        for material in ("model-cards", "model-weights", "private-dataset"):
            with self.subTest(material=material):
                receipt = self.attempt(source="src-hugging-face", material=material,
                                       uses=["commercial_display"])
                self.assertEqual(["unreviewed_material"], receipt["reason_codes"])
        receipt = self.attempt(source="src-hugging-face", material="public-factual-identifiers",
                               uses=["redistribution", "export"])
        self.assertEqual(["use_excluded"] * 2, [item["reason"] for item in receipt["uses"]])

    def test_unknown_source_and_product_label_are_not_canonical_source_aliases(self):
        for source in ("src-unreviewed", "models-dev"):
            with self.subTest(source=source):
                receipt = self.attempt(source=source)
                self.assertEqual(["unknown_source"], receipt["reason_codes"])
                self.assertEqual([], receipt["uses"])

    def test_changed_register_bytes_make_the_original_proposal_stale(self):
        path = self.file("formatted-register.json", self.register_bytes + b"\n")
        checked = self.command("--check-register", "--register", path, expected=0)
        self.assertNotEqual(self.digest, checked["register_sha256"])
        receipt = self.attempt(register=path)
        self.assertEqual(["stale_register"], receipt["reason_codes"])
        self.assertEqual([], receipt["uses"])

    def test_current_digest_does_not_supply_missing_integration_or_notice_receipts(self):
        path = self.file("formatted-register.json", self.register_bytes + b"\n")
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        receipt = self.attempt(register=path, changes={"register_sha256": digest})
        self.assertEqual(["integration_not_approved"], receipt["reason_codes"])
        self.assertEqual("use_conditions_not_received", receipt["uses"][0]["reason"])

    def test_proposal_cannot_declare_approval_notice_receipts_or_another_profile(self):
        for extra in ({"approved": True}, {"notice_receipt": "accepted"},
                      {"integration_status": "approved"}, {"reviewer": "owner"}):
            with self.subTest(field=next(iter(extra))):
                receipt = self.attempt(changes=extra, expected=2)
                self.assertEqual("invalid_proposal_fields", receipt["reason"])
        receipt = self.attempt(changes={"profile": "android-source-promotion-proposal/v2"},
                               expected=2)
        self.assertEqual("unsupported_proposal_version", receipt["reason"])

    def test_empty_duplicate_unknown_and_wrong_type_uses_are_invalid(self):
        for uses in ([], ["commercial_display"] * 2, ["training"], [True], "export", [{}]):
            with self.subTest(uses=uses):
                receipt = self.attempt(uses=uses, expected=2)
                self.assertEqual("invalid_proposal_uses", receipt["reason"])

    def test_unsafe_identity_and_malformed_pin_are_rejected_without_echo(self):
        sentinel = "PRIVATE_SENTINEL_NOT_FOR_OUTPUT"
        for changes in ({"source_id": sentinel + "\n"}, {"material_id": {"value": sentinel}},
                        {"register_sha256": sentinel}, {"register_sha256": True}):
            with self.subTest(field=next(iter(changes))):
                receipt = self.attempt(changes=changes, expected=2)
                self.assertNotIn(sentinel, json.dumps(receipt))

    def test_register_cannot_enable_a_promotion_profile_or_approve_a_row(self):
        self.invalid_register(lambda value: value.update(promotion_profile="approved"),
                              "unsupported_promotion_profile")
        self.invalid_register(
            lambda value: value["records"][0].update(research_integration_status="Approved"),
            "unsupported_integration_status",
        )

    def test_register_cannot_reclassify_an_excluded_source_as_conditional(self):
        def mutate(value):
            row = next(row for row in value["records"] if row["id"] == "gpus-io")
            row["uses"]["automated_retrieval"]["disposition"] = "conditional_basis"
        self.invalid_register(mutate, "unreviewed_use_disposition")

    def test_register_cannot_transfer_a_paper_decision_to_repository_material(self):
        def mutate(value):
            row = next(row for row in value["records"] if row["id"] == "harness-bench")
            row["material_id"] = "repository-tasks"
        self.invalid_register(mutate, "unreviewed_register_material")

    def test_missing_and_duplicate_register_rows_are_invalid(self):
        self.invalid_register(lambda value: value["records"].pop(), "invalid_record_inventory")
        self.invalid_register(lambda value: value["records"].__setitem__(
            1, deepcopy(value["records"][0])), "ambiguous_register_source")

    def test_missing_use_or_new_basis_does_not_pass_register_validation(self):
        self.invalid_register(lambda value: value["records"][0]["uses"].pop("retention"),
                              "incomplete_use_matrix")
        self.invalid_register(lambda value: value["records"][0]["uses"]["export"]["basis"].update(
            input="caller_approval"), "unknown_research_basis")

    def test_undeclared_fields_and_boolean_versions_are_invalid(self):
        self.invalid_register(lambda value: value.update(approval=True), "invalid_register_fields")
        self.invalid_register(lambda value: value.update(register_version=True),
                              "unsupported_register_version")
        self.invalid_register(lambda value: value["android_scope"].update(issue=True),
                              "unreviewed_android_scope")

    def test_replaced_research_revision_blob_or_receipt_requires_a_new_reviewed_profile(self):
        self.invalid_register(lambda value: value["research"].update(revision="0" * 40),
                              "unreviewed_research_pin")
        self.invalid_register(lambda value: value["research"]["inputs"]["rights"].update(
            git_blob="0" * 40), "unreviewed_research_pin")
        self.invalid_register(lambda value: value["research"].update(receiving_receipt="https://example.org"),
                              "unreviewed_research_pin")

    def test_observation_dates_are_real_and_do_not_silently_refresh(self):
        self.invalid_register(lambda value: value["records"][0].update(
            material_review_observed_on="2026-02-30"), "invalid_observation_date")
        self.invalid_register(lambda value: value["records"][0].update(
            inventory_observed_on="2026-10-19"), "unreviewed_observation_date")
        self.invalid_register(lambda value: value["records"][0].update(
            material_review_observed_on="2026-10-19"), "unreviewed_observation_date")

    def test_primary_reference_with_credentials_is_invalid_and_never_echoed(self):
        self.invalid_register(lambda value: value["records"][0].update(
            primary_references=["https://PRIVATE_SENTINEL:password@example.org/terms"]),
            "invalid_primary_reference")

    def test_invalid_json_duplicate_keys_and_nonfinite_values_fail_before_lookup(self):
        for name, raw in (
            ("broken", b'{"PRIVATE_SENTINEL":'),
            ("duplicate", b'{"profile":"a","profile":"b"}'),
            ("nonfinite", b'{"value":NaN}'),
            ("infinite", b'{"value":Infinity}'),
            ("utf8", b'{"value":"\xff"}'),
        ):
            with self.subTest(name=name):
                path = self.file(name, raw)
                receipt = self.command("--proposal", path, expected=2)
                self.assertIn(receipt["reason"], {"invalid_json", "duplicate_json_key",
                                                  "nonfinite_json_number"})
                self.assertNotIn("PRIVATE_SENTINEL", json.dumps(receipt))

    def test_deep_array_cannot_reach_promotion_whether_parsed_or_depth_limited(self):
        # A nested array is valid JSON but never a proposal object. Python builds
        # may reject it in the parser first; either route must return invalid input.
        path = self.file("deep.json", b"[" * 1200 + b"0" + b"]" * 1200)
        receipt = self.command("--proposal", path, expected=2)
        self.assertIn(receipt["reason"], {"invalid_json", "invalid_proposal_fields"})

    def test_register_parser_also_rejects_duplicate_keys_and_invalid_unicode(self):
        path = self.file("duplicate-register.json", b'{"profile":"a","profile":"b"}')
        self.assertEqual("duplicate_json_key", self.command(
            "--check-register", "--register", path, expected=2)["reason"])
        self.invalid_register(lambda value: value["records"][0].update(label="\ud800"),
                              "invalid_register_text")

    def test_empty_and_oversized_proposals_and_registers_are_bounded(self):
        for flag, mode, size in (("--proposal", [], 8193),
                                 ("--register", ["--check-register"], 262145)):
            for count in (0, size):
                with self.subTest(flag=flag, bytes=count):
                    path = self.file("bounded.json", b" " * count)
                    receipt = self.command(*mode, flag, path, expected=2)
                    self.assertEqual("input_size_limit", receipt["reason"])

    def test_directory_symlink_and_fifo_are_rejected_without_blocking(self):
        target = self.file("target.json", self.proposal)
        link = self.directory / "link.json"
        link.symlink_to(target)
        fifo = self.directory / "input.fifo"
        os.mkfifo(fifo)
        for path in (self.directory, link, fifo):
            with self.subTest(kind=path.name):
                receipt = self.command("--proposal", path, expected=2)
                self.assertIn(receipt["reason"], {"input_not_regular", "input_unreadable"})
        self.assertEqual(self.proposal, json.loads(target.read_bytes()))

    def test_missing_path_and_invalid_arguments_do_not_disclose_values(self):
        sentinel = "PRIVATE_SENTINEL_NOT_FOR_OUTPUT"
        cases = (
            ("--proposal", self.directory / sentinel),
            ("--unknown-" + sentinel,),
            ("--check-register", "--proposal", sentinel),
            (),
        )
        for arguments in cases:
            with self.subTest(count=len(arguments)):
                receipt = self.command(*arguments, expected=2)
                self.assertIn(receipt["reason"], {"invalid_arguments", "input_unreadable"})
                self.assertNotIn(sentinel, json.dumps(receipt))


if __name__ == "__main__":
    unittest.main()
