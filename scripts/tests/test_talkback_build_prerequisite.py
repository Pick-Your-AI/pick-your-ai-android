"""Synthetic guard tests only; these do not build or run TalkBack."""
import copy
import importlib.util
import json
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
import zipfile

SCRIPT = Path(__file__).resolve().parents[1] / "talkback_build_prerequisite.py"
SPEC = importlib.util.spec_from_file_location("talkback_build_prerequisite", SCRIPT)
subject = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(subject)
MANIFEST = json.loads(SCRIPT.with_suffix(".json").read_text())
EXPECTED = MANIFEST["expected"]


def xmltree():
    return "\n".join([
        "N: android=http://schemas.android.com/apk/res/android",
        "  E: manifest (line=1)",
        '    A: package="com.android.talkback"',
        '    A: android:versionName(0x0101021c)="' + EXPECTED["version_name"] + '"',
        "    E: application (line=2)",
        '      A: android:name(0x01010003)="' + EXPECTED["application"] + '"',
        "      E: service (line=3)",
        '        A: android:name(0x01010003)="' + EXPECTED["service"] + '"',
        '        A: android:permission(0x01010006)="android.permission.BIND_ACCESSIBILITY_SERVICE"',
        "        A: android:exported(0x01010010)=(type 0x12)0xffffffff",
        "        E: intent-filter (line=4)",
        "          E: action (line=5)",
        '            A: android:name(0x01010003)="android.accessibilityservice.AccessibilityService"',
        "        E: meta-data (line=6)",
        '          A: android:name(0x01010003)="android.accessibilityservice"',
        "          A: android:resource(0x01010025)=@0x7f010001",
        ""
    ])


def elf(machine=62, bits=2):
    data = bytearray(64)
    data[:7] = bytes([0x7f, 69, 76, 70, bits, 1, 1])
    struct.pack_into("<HH", data, 16, 3, machine)
    return bytes(data)


def dependencies():
    components = MANIFEST["expected"]["dependency_components"]
    return {
        "schema": "talkback-dependency-observation/v1",
        "build_completed": True, "preapproved_checksums": False,
        "gradle": "8.13",
        "android_modules": {":": {"compile_sdk": "android-36", "build_tools": "35.0.0"}},
        "components": list(components),
        "artifacts": [{"component": component, "name": "artifact.jar",
                       "bytes": 64, "sha256": "1" * 64} for component in components]
    }


class TalkBackPrerequisiteGuards(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)

    def tearDown(self):
        self.temporary.cleanup()

    def repository(self):
        repository = self.root / "repository"
        repository.mkdir()
        subprocess.run(["git", "init", "--quiet", str(repository)], check=True)
        (repository / "source.txt").write_text("original\n")
        subprocess.run(["git", "-C", str(repository), "add", "source.txt"], check=True)
        subprocess.run(["git", "-C", str(repository), "-c", "user.name=Fixture",
                        "-c", "user.email=fixture@example.invalid", "commit", "--quiet",
                        "--no-gpg-sign", "-m", "Synthetic guard fixture"], check=True)
        return repository

    def apk(self, change=None):
        entries = {"AndroidManifest.xml": b"synthetic parser fixture", "classes.dex": b"fixture"}
        entries.update({"lib/x86_64/" + name: elf() for name in EXPECTED["native_libraries"]})
        if change:
            change(entries)
        path = self.root / "fixture.apk"
        with zipfile.ZipFile(path, "w") as archive:
            for name, data in entries.items():
                archive.writestr(name, data)
        return path

    def test_exact_overlay_requires_both_pinned_input_and_output(self):
        original = b'abiFilters "armeabi-v7a", "arm64-v8a"\n'
        transformed = b'abiFilters "x86_64"\n'
        specification = {
            "before_blob": subject.blob(original),
            "before_sha256": subject.hashlib.sha256(original).hexdigest(),
            "after_blob": subject.blob(transformed),
            "after_sha256": subject.hashlib.sha256(transformed).hexdigest(),
            "replacements": [['abiFilters "armeabi-v7a", "arm64-v8a"', 'abiFilters "x86_64"']]
        }
        self.assertEqual(transformed, subject.overlay(original, specification))
        with self.assertRaisesRegex(ValueError, "input"):
            subject.overlay(original + b"// changed\n", specification)
        wrong_output = dict(specification, after_blob="0" * 40)
        with self.assertRaisesRegex(ValueError, "output blob"):
            subject.overlay(original, wrong_output)
        project = SCRIPT.parents[1]
        work = self.root / "wrapper-output"
        expected = subject.expected_wrapper(project, MANIFEST)
        recorded = {path: subject.hashlib.sha256(data).hexdigest() for path, data in expected.items()}
        for path, data in expected.items():
            target = work / "wrapper" / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        self.assertEqual(recorded, subject.verify_copied_wrapper(project, work, MANIFEST, recorded))
        for state in ({}, dict(recorded, unexpected="0" * 64)):
            with self.subTest(state=state), self.assertRaisesRegex(ValueError, "saved wrapper identities"):
                subject.verify_copied_wrapper(project, work, MANIFEST, state)
        for path, data in expected.items():
            target = work / "wrapper" / path
            changed = data + b"changed copied wrapper"
            target.write_bytes(changed)
            with self.subTest(path=path), self.assertRaisesRegex(ValueError, "Copied wrapper bytes"):
                subject.verify_copied_wrapper(project, work, MANIFEST, recorded)
            changed_state = dict(recorded)
            changed_state[path] = subject.hashlib.sha256(changed).hexdigest()
            with self.subTest(path=path), self.assertRaisesRegex(ValueError, "saved wrapper identities"):
                subject.verify_copied_wrapper(project, work, MANIFEST, changed_state)
            target.write_bytes(data)
        self.assertEqual(recorded, subject.verify_copied_wrapper(project, work, MANIFEST, recorded))

    def test_missing_or_duplicate_overlay_anchor_is_rejected(self):
        for data in (b"unrelated", b"one\none\n"):
            with self.assertRaisesRegex(ValueError, "anchor"):
                subject.replace_once(data, "one", "two")

    def test_tracked_map_rejects_unrelated_change_deletion_and_mode_change(self):
        repository = self.repository()
        self.assertEqual(1, subject.verify_tracked(repository))
        source = repository / "source.txt"
        source.write_text("unexpected\n")
        with self.assertRaisesRegex(ValueError, "bytes changed"):
            subject.verify_tracked(repository)
        source.write_text("original\n")
        source.chmod(0o755)
        with self.assertRaisesRegex(ValueError, "mode changed"):
            subject.verify_tracked(repository)
        source.unlink()
        with self.assertRaisesRegex(ValueError, "missing"):
            subject.verify_tracked(repository)

    def test_only_explicit_existing_overlay_can_differ_from_native_map(self):
        repository = self.repository()
        source = repository / "source.txt"
        source.write_text("approved overlay\n")
        override = {"source.txt": subject.blob(source.read_bytes())}
        self.assertEqual(1, subject.verify_tracked(repository, override))
        override["invented.txt"] = "0" * 40
        with self.assertRaisesRegex(ValueError, "not an existing"):
            subject.verify_tracked(repository, override)

    def test_replacement_refs_and_promisor_configuration_are_not_admitted(self):
        repository = self.repository()
        head = subject.identity(repository)["commit"]
        subprocess.run(["git", "-C", str(repository), "update-ref",
                        "refs/replace/" + head, head], check=True)
        with self.assertRaisesRegex(ValueError, "replacement"):
            subject.identity(repository)
        subprocess.run(["git", "-C", str(repository), "update-ref", "-d",
                        "refs/replace/" + head], check=True)
        subprocess.run(["git", "-C", str(repository), "config",
                        "remote.origin.promisor", "true"], check=True)
        with self.assertRaisesRegex(ValueError, "Promisor"):
            subject.identity(repository)

    def test_output_and_input_roots_must_not_overlap(self):
        project, upstream = self.root / "project", self.root / "upstream"
        subject.isolated_roots(project, upstream, self.root / "output")
        for output in (project, project / "generated", self.root):
            with self.assertRaisesRegex(ValueError, "separate"):
                subject.isolated_roots(project, upstream, output)

    def test_declared_service_requires_its_own_permission_action_and_configuration(self):
        subject.validate_manifest(xmltree(), EXPECTED)
        for token in ("android.permission.BIND_ACCESSIBILITY_SERVICE",
                      "android.accessibilityservice.AccessibilityService",
                      '"android.accessibilityservice"'):
            with self.subTest(token=token), self.assertRaises(ValueError):
                subject.validate_manifest(xmltree().replace(token, "unrelated"), EXPECTED)
        for resource in ("", "@null", "@0x00000000", "0", "0x7f010001",
                         "(type 0x10)0x7f010001", '"@0x7f010001"', "@xml/configuration"):
            with self.subTest(resource=resource), self.assertRaisesRegex(ValueError, "configuration"):
                subject.validate_manifest(xmltree().replace("@0x7f010001", resource), EXPECTED)
        with self.assertRaisesRegex(ValueError, "configuration"):
            subject.validate_manifest(xmltree().replace("android:resource", "android:value"), EXPECTED)

    def test_matching_strings_on_a_different_service_cannot_satisfy_the_guard(self):
        text = xmltree().replace(
            '        A: android:permission',
            '      E: service (line=7)\n'
            '        A: android:name(0x01010003)="unrelated.Service"\n'
            '        A: android:permission')
        with self.assertRaisesRegex(ValueError, "permission"):
            subject.validate_manifest(text, EXPECTED)

    def test_duplicate_attributes_wrong_package_and_unexported_service_fail(self):
        variants = [
            xmltree().replace('    A: package=', '    A: package="duplicate"\n    A: package='),
            xmltree().replace('package="com.android.talkback"', 'package="other.package"'),
            xmltree().replace("(type 0x12)0xffffffff", "(type 0x12)0x0"),
            xmltree().replace(EXPECTED["version_name"], "a different version")
        ]
        for text in variants:
            with self.subTest(text=text), self.assertRaises(ValueError):
                subject.validate_manifest(text, EXPECTED)

    def test_native_guard_accepts_all_required_elf64_libraries(self):
        observed = subject.inspect_apk(self.apk(), MANIFEST)
        self.assertEqual({"lib/x86_64/" + name for name in EXPECTED["native_libraries"]},
                         {item["path"] for item in observed})
        self.assertTrue(all(item["bytes"] == 64 and len(item["sha256"]) == 64 for item in observed))

    def test_arm_payload_hidden_under_x86_64_name_is_rejected(self):
        path = self.apk(lambda entries: entries.update({"lib/x86_64/liblouis.so": elf(machine=183)}))
        with self.assertRaisesRegex(ValueError, "x86_64 shared object"):
            subject.inspect_apk(path, MANIFEST)

    def test_foreign_abi_and_missing_source_library_are_rejected(self):
        for change in (
            lambda entries: entries.update({"lib/arm64-v8a/libforeign.so": elf(machine=183)}),
            lambda entries: entries.pop("lib/x86_64/liblouiswrap.so")
        ):
            with self.subTest(change=change), self.assertRaises(ValueError):
                subject.inspect_apk(self.apk(change), MANIFEST)

    def test_duplicate_and_escaping_apk_entries_are_rejected(self):
        path = self.apk()
        with zipfile.ZipFile(path, "a") as archive:
            archive.writestr("classes.dex", b"second")
        with self.assertRaisesRegex(ValueError, "duplicate"):
            subject.inspect_apk(path, MANIFEST)
        path = self.apk(lambda entries: entries.update({"../outside": b"invalid"}))
        with self.assertRaisesRegex(ValueError, "Unsafe"):
            subject.inspect_apk(path, MANIFEST)
        for alias in ("lib//x86_64/liblouis.so", "lib/./x86_64/liblouis.so"):
            for replace in (False, True):
                def alias_library(entries):
                    original = "lib/x86_64/liblouis.so"
                    entries[alias] = entries.pop(original) if replace else entries[original]
                with self.subTest(alias=alias, replace=replace), self.assertRaisesRegex(ValueError, "canonical"):
                    subject.inspect_apk(self.apk(alias_library), MANIFEST)

    def test_elf_class_and_apk_resource_limits_are_enforced(self):
        path = self.apk(lambda entries: entries.update({"lib/x86_64/liblouis.so": elf(bits=1)}))
        with self.assertRaisesRegex(ValueError, "ELF64"):
            subject.inspect_apk(path, MANIFEST)
        path = self.apk()
        bounded = copy.deepcopy(MANIFEST)
        bounded["limits"]["uncompressed_bytes"] = 10
        with self.assertRaisesRegex(ValueError, "uncompressed"):
            subject.inspect_apk(path, bounded)

    def test_signature_observation_requires_one_verified_signer_identity(self):
        output = "Verifies\nSigner #1 certificate SHA-256 digest: " + "a" * 64 + "\n"
        self.assertEqual("a" * 64, subject.signature_certificate(output))
        for text in ("Verifies\n", output + output.replace("#1", "#2")):
            with self.assertRaisesRegex(ValueError, "one observed"):
                subject.signature_certificate(text)

    def test_first_resolution_is_observed_and_never_preapproved(self):
        receipt = dependencies()
        subject.validate_dependencies(receipt, MANIFEST)
        receipt["preapproved_checksums"] = True
        with self.assertRaisesRegex(ValueError, "observation"):
            subject.validate_dependencies(receipt, MANIFEST)

    def test_incomplete_changed_dynamic_or_duplicated_dependencies_are_rejected(self):
        variants = []
        for key, value in (("build_completed", False), ("gradle", "8.11.1"), ("artifacts", [])):
            variants.append(dict(dependencies(), **{key: value}))
        missing_pin = dependencies()
        missing_pin["components"].remove("com.mcxiaoke.volley:library:1.0.19")
        variants.append(missing_pin)
        for component in EXPECTED["dependency_components"]:
            missing_artifact = dependencies()
            missing_artifact["artifacts"] = [
                item for item in missing_artifact["artifacts"] if item["component"] != component]
            variants.append(missing_artifact)
        unrelated_artifact = dependencies()
        unrelated_artifact["artifacts"].append({
            "component": "unobserved:module:1.0", "name": "other.jar",
            "bytes": 64, "sha256": "2" * 64})
        variants.append(unrelated_artifact)
        missing_hash = dependencies()
        del missing_hash["artifacts"][0]["sha256"]
        variants.append(missing_hash)
        dynamic = dependencies()
        dynamic["artifacts"][0]["component"] = "group:name:1.+"
        variants.append(dynamic)
        duplicate = dependencies()
        duplicate["artifacts"].append(dict(duplicate["artifacts"][0]))
        variants.append(duplicate)
        wrong_tools = dependencies()
        wrong_tools["android_modules"][":"]["compile_sdk"] = "android-35"
        variants.append(wrong_tools)
        for receipt in variants:
            with self.subTest(receipt=receipt), self.assertRaises(ValueError):
                subject.validate_dependencies(receipt, MANIFEST)


if __name__ == "__main__":
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(TalkBackPrerequisiteGuards)
    if suite.countTestCases() != 17:
        raise SystemExit("Expected all 17 prerequisite guard methods")
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    raise SystemExit(0 if result.wasSuccessful() and result.testsRun == 17 and not result.skipped else 1)
