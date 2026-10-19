"""Exercise SPDX mapping and actual checkout/output rejection boundaries."""

import copy
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("runtime_sbom", ROOT / "scripts/runtime_sbom.py")
sbom = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sbom)
CREATED = "2026-10-18T12:34:56Z"
LIBRARY = "example.test:library:1.2.3"
BOM = "example.test:metadata-bom:4.5.6"
CORE_BYTES = b"Synthetic core output, not an executed JVM build.\n"
PREVIEW_BYTES = b"Synthetic preview output, not an APK or installed result.\n"


def pom(declarations=None):
    declarations = [] if declarations is None else declarations
    return {"sha256": "a" * 64, "direct_license_declarations": declarations,
            "license_status": "declared-unreviewed" if declarations else "no-direct-declaration",
            "parent": None, "parent_license_inheritance": "no-parent"}


def dependency(component, constraint=False):
    return {"component": component, "constraint": constraint}


def inventory(preview=False):
    root = {"component": "project::core", "pom": None,
            "dependencies": [dependency(LIBRARY), dependency(LIBRARY, True), dependency(BOM, True)]}
    library = {"component": LIBRARY, "pom": pom([
        {"name": "Unreviewed example license", "url": "https://example.invalid/license", "distribution": "repo"}
    ]), "dependencies": [dependency(BOM, True)]}
    bom = {"component": BOM, "pom": pom(), "dependencies": [dependency(LIBRARY, True)]}
    artifacts = [
        {"component": LIBRARY, "file": "library-1.2.3.jar", "sha256": "b" * 64, "bytes": 120},
        {"component": LIBRARY, "file": "library-1.2.3-extra.jar", "sha256": "c" * 64, "bytes": 75},
    ]
    core = {"project": ":core", "configuration": "runtimeClasspath",
            "components": [root, library, bom], "artifacts": artifacts}
    outputs = [{"kind": "core-jar", "file": sbom.OUTPUTS["core-jar"][0],
                "sha256": sbom.digest(CORE_BYTES), "bytes": len(CORE_BYTES)}]
    result = {"schema_version": 1, "scope": "resolved-runtime-classpaths",
              "source": {"commit": "d" * 40, "tree": "e" * 40,
                         "pins": [{"file": p, "sha256": "f" * 64}
                                  for p in sorted(sbom.PINS | (sbom.PREVIEW_PINS if preview else set()))]},
              "toolchain": {"gradle": "8.11.1", "java": "21.0.12.1"},
              "outputs": outputs, "classpaths": [core], "limitations": ["Synthetic conversion test only."]}
    if preview:
        preview_core = copy.deepcopy(root)
        preview_core["dependencies"] = [dependency(LIBRARY)]
        result["classpaths"].append({
            "project": ":preview", "configuration": "debugRuntimeClasspath",
            "components": [preview_core, copy.deepcopy(library), copy.deepcopy(bom),
                           {"component": "project::preview", "pom": None,
                            "dependencies": [dependency("project::core"), dependency(LIBRARY, True)]}],
            "artifacts": copy.deepcopy(artifacts) + [
                {"component": "project::core", "file": "core.jar",
                 "sha256": outputs[0]["sha256"], "bytes": outputs[0]["bytes"]}],
        })
        outputs.append({"kind": "unsigned-debug-preview", "file": sbom.OUTPUTS["unsigned-debug-preview"][0],
                        "sha256": sbom.digest(PREVIEW_BYTES), "bytes": len(PREVIEW_BYTES)})
    return result


def convert(value=None, created=CREATED):
    value = inventory() if value is None else value
    return sbom.document(value, sbom.digest(sbom.canonical(value).encode()), "1" * 64, created)


def resign_namespace(value):
    content = {k: v for k, v in value.items() if k != "documentNamespace"}
    value["documentNamespace"] = sbom.NAMESPACE + sbom.digest(sbom.canonical(content).encode())


class MappingTest(unittest.TestCase):
    def setUp(self):
        self.schema = (ROOT / sbom.SCHEMA).read_bytes()

    def test_core_and_preview_documents_pass_the_official_schema(self):
        for preview, count in ((False, 6), (True, 14)):
            with self.subTest(preview=preview):
                result = convert(inventory(preview))
                sbom.validate_document(result, self.schema)
                self.assertEqual(count, len(result["packages"]))
                self.assertEqual("SPDX-2.3", result["spdxVersion"])
                self.assertEqual("CC0-1.0", result["dataLicense"])
                self.assertEqual(CREATED, result["creationInfo"]["created"])

    def test_each_classpath_retains_its_own_exact_component_graph(self):
        original = inventory(True)
        before = copy.deepcopy(original)
        result = convert(original)
        records = [json.loads(p["sourceInfo"]) for p in result["packages"]]
        for scope in original["classpaths"]:
            for component in scope["components"]:
                expected = {"kind": "resolved-component", "project": scope["project"],
                            "configuration": scope["configuration"], **component}
                self.assertEqual(1, records.count(expected))
        roots = [p for p in result["packages"] if p["name"] == "project::core"]
        self.assertEqual(2, len(roots))
        self.assertNotEqual(roots[0]["SPDXID"], roots[1]["SPDXID"])
        self.assertNotEqual(json.loads(roots[0]["sourceInfo"])["dependencies"],
                            json.loads(roots[1]["sourceInfo"])["dependencies"])
        self.assertEqual(before, original)

    def test_constraints_and_dependencies_between_the_same_nodes_remain_distinct(self):
        result = convert()
        source = sbom.spdx_id("component", ":core", "runtimeClasspath", "project::core")
        target = sbom.spdx_id("component", ":core", "runtimeClasspath", LIBRARY)
        edges = [r for r in result["relationships"] if
                 r["spdxElementId"] == source and r["relatedSpdxElement"] == target]
        self.assertEqual({"DEPENDS_ON", "OTHER"}, {r["relationshipType"] for r in edges})
        constraint = next(r for r in edges if r["relationshipType"] == "OTHER")
        self.assertIn("constraint", constraint["comment"])
        self.assertEqual(sum(len(c["dependencies"]) for c in inventory()["classpaths"][0]["components"]),
                         sum(r["relationshipType"] == "DEPENDS_ON" or "constraint" in r.get("comment", "")
                             for r in result["relationships"]))

    def test_metadata_only_bom_and_multiple_artifact_hashes_are_not_lost_or_combined(self):
        result = convert()
        bom = next(p for p in result["packages"] if p["name"] == "example.test:metadata-bom")
        self.assertEqual("4.5.6", bom["versionInfo"])
        self.assertNotIn("checksums", bom)
        library = next(p for p in result["packages"] if p["name"] == "example.test:library")
        self.assertNotIn("checksums", library)
        artifacts = [p for p in result["packages"] if json.loads(p["sourceInfo"])["kind"] == "resolved-artifact"]
        self.assertEqual({"b" * 64, "c" * 64}, {p["checksums"][0]["checksumValue"] for p in artifacts})
        self.assertTrue(all(len(p["checksums"]) == 1 for p in artifacts))
        self.assertTrue(all(p["checksums"][0]["checksumValue"] != "a" * 64 for p in artifacts))

    def test_license_text_and_unresolved_parent_survive_without_license_or_origin_assertions(self):
        value = inventory()
        evidence = value["classpaths"][0]["components"][1]["pom"]
        evidence["parent"] = {"group": "example.test", "module": "parent", "version": "${unresolved}"}
        evidence["parent_license_inheritance"] = "not-evaluated"
        evidence["direct_license_declarations"].append({"name": "NOASSERTION\nUnicode \u03bb", "url": None, "distribution": None})
        result = convert(value)
        library = next(p for p in result["packages"] if p["name"] == "example.test:library")
        self.assertEqual(evidence, json.loads(library["sourceInfo"])["pom"])
        for item in result["packages"]:
            for field in ("licenseDeclared", "licenseConcluded", "downloadLocation", "copyrightText", "supplier"):
                self.assertEqual("NOASSERTION", item[field])
            self.assertIs(False, item["filesAnalyzed"])
            self.assertNotIn("packageVerificationCode", item)
            self.assertNotIn("externalRefs", item)

    def test_document_identifies_original_receipt_source_toolchain_and_generator(self):
        value = inventory(True)
        result = convert(value)
        evidence = json.loads(result["comment"])
        self.assertEqual(value["source"], evidence["source"])
        self.assertEqual(value["toolchain"], evidence["toolchain"])
        self.assertEqual(value["limitations"], evidence["inventory_limitations"])
        self.assertEqual(sbom.digest(sbom.canonical(value).encode()), evidence["inventory_sha256"])
        self.assertEqual("1" * 64, evidence["generator_sha256"])
        self.assertEqual(sbom.SCHEMA_SHA256, evidence["spdx_schema_sha256"])

    def test_replay_is_deterministic_and_changed_document_gets_a_new_namespace(self):
        first = convert()
        self.assertEqual(first, convert())
        self.assertNotEqual(first["documentNamespace"], convert(created="2026-10-18T12:34:57Z")["documentNamespace"])
        value = inventory()
        for inventory_hash, generator_hash in (("2" * 64, "1" * 64), ("1" * 64, "2" * 64)):
            changed = sbom.document(value, inventory_hash, generator_hash, CREATED)
            self.assertNotEqual(first["documentNamespace"], changed["documentNamespace"])
        for stamp in ("2026-10-18T12:34:56+00:00", "2026-02-30T12:34:56Z", "yesterday"):
            with self.assertRaises(ValueError):
                convert(created=stamp)

    def test_official_schema_bytes_are_pinned_and_have_no_external_references(self):
        self.assertEqual(sbom.SCHEMA_SHA256, sbom.digest(self.schema))
        pending = [json.loads(self.schema)]
        while pending:
            node = pending.pop()
            if isinstance(node, dict):
                if "$ref" in node:
                    self.assertTrue(node["$ref"].startswith("#"))
                pending.extend(node.values())
            elif isinstance(node, list):
                pending.extend(node)
        with self.assertRaisesRegex(ValueError, "schema pin"):
            sbom.validate_document(convert(), self.schema + b"\n")

    def test_schema_and_semantic_guards_reject_invalid_or_misleading_spdx(self):
        for mutation in ("additional-field", "duplicate-id", "dangling-edge", "license-claim", "verification-code"):
            with self.subTest(mutation=mutation):
                value = convert()
                if mutation == "additional-field":
                    value["inventedField"] = True
                elif mutation == "duplicate-id":
                    value["packages"].append(copy.deepcopy(value["packages"][0]))
                elif mutation == "dangling-edge":
                    value["relationships"][0]["relatedSpdxElement"] = "SPDXRef-missing"
                elif mutation == "license-claim":
                    value["packages"][0]["licenseDeclared"] = "MIT"
                else:
                    value["packages"][0]["packageVerificationCode"] = {"packageVerificationCodeValue": "0" * 40}
                resign_namespace(value)
                with self.assertRaises(ValueError):
                    sbom.validate_document(value, self.schema)
        value = convert()
        value["creationInfo"]["created"] = "2026-10-19T12:34:56Z"
        with self.assertRaisesRegex(ValueError, "namespace"):
            sbom.validate_document(value, self.schema)


class InventoryRejectionTest(unittest.TestCase):
    def test_json_duplicate_keys_nonfinite_invalid_unicode_depth_and_byte_limits_fail(self):
        for data in (b'{"schema_version":1,"schema_version":1}', b'{"x":NaN}', b'\xff',
                     b'[' * 1100 + b']' * 1100, b' ' * (sbom.MAX_INPUT_BYTES + 1)):
            with self.subTest(length=len(data)), self.assertRaises(ValueError):
                sbom.parse_inventory(data)
        value = inventory()
        value["limitations"] = ["\ud800"]
        with self.assertRaisesRegex(ValueError, "Unicode"):
            sbom.parse_inventory(json.dumps(value).encode())

    def test_boolean_numeric_aliases_and_unsupported_schema_scope_or_fields_fail(self):
        for field, replacement in (("schema_version", True), ("schema_version", 1.0), ("schema_version", 2),
                                   ("scope", "all-packaged-software"), ("new_field", "unknown")):
            value = inventory()
            value[field] = replacement
            with self.subTest(field=field, replacement=replacement), self.assertRaises(ValueError):
                sbom.validate_inventory(value)
        for replacement in (True, 1.0, 0, -1, 2**63):
            value = inventory()
            value["outputs"][0]["bytes"] = replacement
            with self.assertRaises(ValueError):
                sbom.validate_inventory(value)
        value = inventory()
        value["classpaths"][0]["components"][0]["dependencies"][0]["constraint"] = 0
        with self.assertRaises(ValueError):
            sbom.validate_inventory(value)

    def test_missing_repeated_and_unexpected_pins_outputs_or_scopes_fail(self):
        for field, mutation in (("pins", "missing"), ("pins", "duplicate"), ("outputs", "duplicate"),
                                ("classpaths", "duplicate"), ("outputs", "missing")):
            value = inventory()
            rows = value["source"][field] if field == "pins" else value[field]
            if mutation == "missing":
                rows.pop()
            else:
                rows.append(copy.deepcopy(rows[0]))
            with self.subTest(field=field, mutation=mutation), self.assertRaises(ValueError):
                sbom.validate_inventory(value)
        value = inventory()
        value["source"]["pins"][0]["file"] = "unexpected.gradle.kts"
        with self.assertRaisesRegex(ValueError, "source pin"):
            sbom.validate_inventory(value)

    def test_unsafe_paths_and_digests_are_never_interpreted_as_files(self):
        for filename in ("../outside.jar", "/absolute.jar", "x/../file.jar", "x\\file.jar", ".", "x\n.jar"):
            value = inventory()
            value["classpaths"][0]["artifacts"][0]["file"] = filename
            with self.subTest(filename=repr(filename)), self.assertRaises(ValueError):
                sbom.validate_inventory(value)
        value = inventory()
        value["outputs"][0]["sha256"] = "f" * 63 + "\n"
        with self.assertRaises(ValueError):
            sbom.validate_inventory(value)

    def test_dangling_edges_repeated_nodes_orphans_and_disconnected_components_fail(self):
        for mutation in ("dangling", "repeated", "orphan", "disconnected", "missing-root"):
            value = inventory()
            scope = value["classpaths"][0]
            if mutation == "dangling":
                scope["components"][0]["dependencies"].append(dependency("example.test:absent:1"))
            elif mutation == "repeated":
                scope["components"].append(copy.deepcopy(scope["components"][0]))
            elif mutation == "orphan":
                scope["artifacts"][0]["component"] = "example.test:absent:1"
            elif mutation == "disconnected":
                scope["components"].append({"component": "example.test:unused:1", "dependencies": [], "pom": pom()})
            else:
                scope["components"].pop(0)
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                sbom.validate_inventory(value)

    def test_conflicting_pom_artifact_and_project_output_evidence_fail(self):
        for mutation in ("pom", "artifact", "project-output"):
            value = inventory(True)
            scope = value["classpaths"][1]
            if mutation == "pom":
                scope["components"][1]["pom"]["sha256"] = "0" * 64
            else:
                scope["artifacts"][0 if mutation == "artifact" else -1]["sha256"] = "0" * 64
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                sbom.validate_inventory(value)

    def test_license_status_cannot_hide_missing_declarations_or_invent_parent_review(self):
        for key, replacement in (("license_status", "reviewed"), ("license_status", "no-direct-declaration"),
                                 ("parent_license_inheritance", "reviewed")):
            value = inventory()
            value["classpaths"][0]["components"][1]["pom"][key] = replacement
            with self.assertRaises(ValueError):
                sbom.validate_inventory(value)


class CheckoutQualificationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="android-spdx-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.value = inventory(True)
        for pin in self.value["source"]["pins"]:
            path = self.root / pin["file"]
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("Synthetic pinned fixture: " + pin["file"] + "\n")
            pin["sha256"] = sbom.hash_file(path)
        for relative in (sbom.DRIVER, sbom.SCHEMA):
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes((ROOT / relative).read_bytes())
        (self.root / ".gitignore").write_text("**/build/\n__pycache__/\n")
        self.git("init", "--quiet")
        self.git("add", "--all")
        # This private disposable test history is not a contribution commit.
        self.git("-c", "user.name=SPDX test fixture", "-c", "user.email=spdx@example.invalid",
                 "-c", "commit.gpgsign=false", "commit", "--quiet", "--no-verify", "-m", "Synthetic SPDX fixture")
        self.value["source"]["commit"] = self.git("rev-parse", "HEAD").decode().strip()
        self.value["source"]["tree"] = self.git("rev-parse", "HEAD^{tree}").decode().strip()
        for output, data in zip(self.value["outputs"], (CORE_BYTES, PREVIEW_BYTES)):
            path = self.root / output["file"]
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        self.input = self.root / sbom.INVENTORY
        self.input.parent.mkdir(parents=True)
        self.write_inventory()

    def git(self, *arguments):
        return sbom.git(self.root, *arguments)

    def write_inventory(self):
        self.input.write_text(json.dumps(self.value, indent=2) + "\n")

    def seed_old_success(self):
        output = self.root / sbom.OUTPUT
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(b"old success must not survive a failed qualification\n")
        return output

    def test_actual_clean_checkout_and_output_bytes_produce_an_atomic_schema_valid_document(self):
        result = sbom.export(self.root, CREATED)
        output = self.root / sbom.OUTPUT
        self.assertEqual(result, json.loads(output.read_bytes()))
        self.assertEqual(sbom.digest(self.input.read_bytes()), json.loads(result["comment"])["inventory_sha256"])
        self.assertEqual(sbom.hash_file(self.root / sbom.DRIVER), json.loads(result["comment"])["generator_sha256"])
        self.assertEqual(b"", self.git("status", "--porcelain", "--untracked-files=all"))
        self.assertEqual([output.name], [p.name for p in output.parent.iterdir()])
        sbom.validate_document(result, (self.root / sbom.SCHEMA).read_bytes())

    def test_old_commit_or_tree_is_rejected_and_old_success_is_removed(self):
        for field in ("commit", "tree"):
            original = self.value["source"][field]
            self.value["source"][field] = "0" * 40
            self.write_inventory()
            output = self.seed_old_success()
            with self.assertRaisesRegex(ValueError, "does not match"):
                sbom.export(self.root, CREATED)
            self.assertFalse(output.exists())
            self.value["source"][field] = original

    def test_clean_status_cannot_hide_changed_tracked_source_bytes(self):
        relative = "core/build.gradle.kts"
        self.git("update-index", "--assume-unchanged", relative)
        (self.root / relative).write_text("Changed but hidden from status\n")
        self.assertEqual(b"", self.git("status", "--porcelain", "--untracked-files=all"))
        output = self.seed_old_success()
        with self.assertRaisesRegex(ValueError, "Tracked source bytes"):
            sbom.export(self.root, CREATED)
        self.assertFalse(output.exists())

    def test_git_replacement_cannot_borrow_an_unchanged_commit_for_a_different_tree(self):
        original = self.value["source"]["commit"]
        (self.root / ".gitignore").write_text("**/build/\n__pycache__/\nreplaced-tree-only\n")
        self.git("add", ".gitignore")
        replacement_tree = self.git("write-tree").decode().strip()
        replacement = self.git("-c", "user.name=SPDX replacement fixture", "-c",
                               "user.email=spdx@example.invalid", "-c", "commit.gpgsign=false",
                               "commit-tree", replacement_tree, "-m", "Private replacement fixture").decode().strip()
        self.git("replace", original, replacement)
        ordinary_status = subprocess.run(["git", "status", "--porcelain", "--untracked-files=all"],
                                         cwd=self.root, check=True, stdout=subprocess.PIPE,
                                         stderr=subprocess.PIPE, timeout=30)
        self.assertEqual(b"", ordinary_status.stdout)
        self.value["source"]["tree"] = replacement_tree
        self.write_inventory()
        output = self.seed_old_success()
        with self.assertRaisesRegex(ValueError, "clean source"):
            sbom.export(self.root, CREATED)
        self.assertFalse(output.exists())

    def test_dirty_untracked_source_and_changed_pin_do_not_receive_an_sbom(self):
        extra = self.root / "unreviewed-source.txt"
        extra.write_text("Untracked fixture\n")
        with self.assertRaisesRegex(ValueError, "clean source"):
            sbom.export(self.root, CREATED)
        extra.unlink()
        self.value["source"]["pins"][0]["sha256"] = "0" * 64
        self.write_inventory()
        with self.assertRaisesRegex(ValueError, "source pin mismatch"):
            sbom.export(self.root, CREATED)

    def test_changed_output_hash_even_with_unchanged_size_is_rejected(self):
        path = self.root / self.value["outputs"][1]["file"]
        path.write_bytes(b"x" * path.stat().st_size)
        output = self.seed_old_success()
        with self.assertRaisesRegex(ValueError, "Built output"):
            sbom.export(self.root, CREATED)
        self.assertFalse(output.exists())

    def test_missing_inventory_and_invalid_json_remove_old_success_without_partial_output(self):
        for data in (None, b"{broken JSON}"):
            output = self.seed_old_success()
            if data is None:
                self.input.unlink()
            else:
                self.input.write_bytes(data)
            with self.assertRaises((ValueError, OSError)):
                sbom.export(self.root, CREATED)
            self.assertFalse(output.exists())
            self.assertEqual([], list(output.parent.iterdir()))

    def test_input_and_output_ancestor_symlinks_are_rejected(self):
        real_input = self.input.with_name("original-inventory.json")
        self.input.rename(real_input)
        self.input.symlink_to(real_input)
        with self.assertRaisesRegex(ValueError, "Symlink"):
            sbom.export(self.root, CREATED)
        self.input.unlink()
        real_input.rename(self.input)
        directory = self.root / "preview/build/outputs"
        original = directory.with_name("original-outputs")
        directory.rename(original)
        directory.symlink_to(original, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "Symlink"):
            sbom.export(self.root, CREATED)

    def test_unsafe_output_links_are_preserved_without_touching_their_targets(self):
        output = self.seed_old_success()
        victim = self.root / "build/protected-fixture.txt"
        victim.write_bytes(b"Do not replace or unlink this file.\n")
        for mode in ("symlink", "hardlink", "dangling-symlink"):
            output.unlink()
            if mode == "hardlink":
                os.link(victim, output)
            else:
                output.symlink_to(victim if mode == "symlink" else victim.with_name("missing.txt"))
            with self.subTest(mode=mode), self.assertRaisesRegex(ValueError, "Unsafe existing SBOM"):
                sbom.export(self.root, CREATED)
            self.assertEqual(b"Do not replace or unlink this file.\n", victim.read_bytes())
            self.assertTrue(output.exists() or output.is_symlink())

    def test_output_parent_symlink_cannot_redirect_cleanup_or_writes(self):
        output = self.seed_old_success()
        parent = output.parent
        original = parent.with_name("original-sbom")
        parent.rename(original)
        parent.symlink_to(original, target_is_directory=True)
        before = (original / output.name).read_bytes()
        with self.assertRaisesRegex(ValueError, "Unsafe SBOM output directory"):
            sbom.export(self.root, CREATED)
        self.assertEqual(before, (original / output.name).read_bytes())

    def test_atomic_replace_failure_removes_temporary_and_old_success_and_allows_retry(self):
        output = self.seed_old_success()
        with mock.patch.object(sbom.os, "replace", side_effect=OSError("Synthetic atomic-replace failure")):
            with self.assertRaisesRegex(OSError, "atomic-replace"):
                sbom.export(self.root, CREATED)
        self.assertFalse(output.exists())
        self.assertEqual([], list(output.parent.iterdir()))
        sbom.export(self.root, CREATED)
        self.assertTrue(output.is_file())

    def test_copied_cli_returns_failure_for_stale_evidence_after_a_real_success(self):
        command = [sys.executable, str(self.root / sbom.DRIVER)]
        success = subprocess.run(command, cwd=self.root, capture_output=True, text=True, timeout=30)
        self.assertEqual(0, success.returncode, success.stderr)
        self.assertIn("Verified SPDX 2.3 runtime document", success.stdout)
        self.input.write_text("{}\n")
        failure = subprocess.run(command, cwd=self.root, capture_output=True, text=True, timeout=30)
        self.assertEqual(1, failure.returncode)
        self.assertEqual("", failure.stdout)
        self.assertFalse((self.root / sbom.OUTPUT).exists())


if __name__ == "__main__":
    unittest.main()
