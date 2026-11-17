"""Synthetic guard tests only; these do not build or run TalkBack."""
import copy
from contextlib import ExitStack
from unittest.mock import patch
import importlib.util
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import time
import tempfile
import unittest
import zipfile

SCRIPT = Path(__file__).resolve().parents[1] / "talkback_build_prerequisite.py"
SPEC = importlib.util.spec_from_file_location("talkback_build_prerequisite", SCRIPT)
subject = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(subject)
MANIFEST = json.loads(SCRIPT.with_suffix(".json").read_text())
EXPECTED = MANIFEST["expected"]
ARTIFACT_NAMES = ("talkback-phone-x86_64.apk", "dependencies.json", "manifest.xmltree.txt",
                  "signing.txt", "receipt.json")


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
        "configurations": [":|1|classpath", ":|0|observed"],
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

    def test_aapt2_version_captures_successful_stderr_and_stdout(self):
        version = b"Android Asset Packaging Tool (aapt) 2.19-12345678\n"
        for stdout, stderr in ((b"", version), (version, b"")):
            with patch.object(subject, "bounded_run", return_value=subprocess.CompletedProcess(
                    [], 0, stdout=stdout, stderr=stderr)) as command:
                self.assertEqual(subject.aapt2_version(Path("aapt2")), version.decode().strip())
                self.assertTrue(command.call_args.kwargs["check"])
                self.assertEqual(command.call_args.kwargs["stderr"], subprocess.PIPE)

    def test_aapt2_version_rejects_missing_conflicting_malformed_and_failed_output(self):
        version = b"Android Asset Packaging Tool (aapt) 2.19-12345678\n"
        for output in (b"", b"unrelated output", version + version, b"x" * 257,
                       b"Android Asset Packaging Tool (aapt) 1.19-12345678"):
            with patch.object(subject, "bounded_run", return_value=subprocess.CompletedProcess(
                    [], 0, stdout=b"", stderr=output)), self.assertRaises(ValueError):
                subject.aapt2_version(Path("aapt2"))
        error = subprocess.CalledProcessError(1, ["aapt2", "version"])
        with patch.object(subject, "bounded_run", side_effect=error), self.assertRaises(
                subprocess.CalledProcessError) as caught:
            subject.aapt2_version(Path("aapt2"))
        self.assertIs(caught.exception, error)

    def test_aapt2_full_android_namespace_preserves_exact_checks(self):
        # Official AAPT2 Debug.cpp XmlPrinter prints namespace_uri + ':' + name.
        text = xmltree().replace("A: android:", "A: http://schemas.android.com/apk/res/android:")
        subject.validate_manifest(text, EXPECTED)
        for changed in (text.replace(EXPECTED["version_name"], "wrong-version"),
                        text.replace("BIND_ACCESSIBILITY_SERVICE", "OTHER_PERMISSION"),
                        text.replace("http://schemas.android.com/apk/res/android:", "https://unknown.example/android:")):
            with self.assertRaises(ValueError):
                subject.validate_manifest(changed, EXPECTED)

    def test_namespace_alias_collisions_are_rejected(self):
        for value in (EXPECTED["version_name"], "different"):
            text = xmltree().replace("    E: application", '    A: http://schemas.android.com/apk/res/android:versionName="'
                                     + value + '"\n    E: application')
            with self.assertRaisesRegex(ValueError, "Duplicate"):
                subject.validate_manifest(text, EXPECTED)

    def test_manifest_diagnostic_is_bounded_and_allowlisted(self):
        text = xmltree().replace("A: android:", "A: http://schemas.android.com/apk/res/android:")
        result = subject.manifest_version_diagnostic(text, EXPECTED)
        self.assertFalse(result["qualified"])
        self.assertFalse(result["attributes"][0]["present"])
        self.assertTrue(result["attributes"][1]["matches_expected"])
        for value in ("private-provider-token", "/private/credential", "x" * 1000):
            result = subject.manifest_version_diagnostic(text.replace(EXPECTED["version_name"], value), EXPECTED)
            self.assertIsNone(result["attributes"][1]["observed_version"])
            self.assertNotIn(value, json.dumps(result))
        self.assertFalse(subject.manifest_version_diagnostic("x" * (16 * 1024 * 1024 + 1), EXPECTED)["parsed"])

    def test_record_failure_writes_only_diagnostic_and_reraises_original(self):
        self.record_failure_control(False)

    def test_record_manifest_failure_preserves_exception_and_no_qualified_artifact(self):
        self.record_failure_control(True)

    def record_failure_control(self, manifest_failure):
        project, upstream, work, sdk = [self.root / name for name in
                                       ("project", "upstream", "work", "sdk")]
        for path in (project / "scripts", upstream, work / "artifacts"):
            path.mkdir(parents=True)
        manifest_bytes = json.dumps(MANIFEST).encode()
        (project / subject.MANIFEST).write_bytes(manifest_bytes)
        (project / subject.INIT_SCRIPT).write_text("synthetic observer fixture")
        project_identity = {"commit": "a" * 40, "tree": "b" * 40}
        upstream_identity = {key: MANIFEST["upstream"][key] for key in ("commit", "tree")}
        state = {"project": project_identity, "upstream": upstream_identity,
                 "manifest_sha256": subject.hashlib.sha256(manifest_bytes).hexdigest(),
                 "init_sha256": subject.sha256_file(project / subject.INIT_SCRIPT),
                 "overlay_blobs": {key: value["after_blob"] for key, value in MANIFEST["overlays"].items()},
                 "project_tracked_files": {}, "upstream_tracked_files": {},
                 "copied_wrapper_sha256": {}}
        (work / "prepared.json").write_text(json.dumps(state))
        (work / "gradle-version.txt").write_text("Gradle 8.13\n")
        (work / "java-version.txt").write_text('openjdk version "17.0.20"\n')
        for directory, revision in (("platforms/android-36", "2"),
                                    ("build-tools/35.0.0", "35.0.0"),
                                    ("ndk/21.4.7075529", "21.4.7075529")):
            target = sdk / directory
            target.mkdir(parents=True)
            (target / "source.properties").write_text("Pkg.Revision = " + revision + "\n")
        receipt = dependencies()
        if not manifest_failure:
            receipt["components"].remove(EXPECTED["dependency_components"][0])
        if not manifest_failure:
            receipt["provider_text"] = "private-provider-value"
            receipt["artifacts"][0]["path"] = "/private/credential"
        (work / "dependencies.json").write_text(json.dumps(receipt))
        original = []
        validator = subject.validate_manifest if manifest_failure else subject.validate_dependencies
        if manifest_failure:
            apk = upstream / "build/outputs/apk/phone/debug/synthetic.apk"
            apk.parent.mkdir(parents=True)
            apk.write_bytes(b"synthetic APK fixture, never qualified")

        def capture_failure(*args):
            try:
                validator(*args)
            except ValueError as error:
                original.append(error)
                raise

        with ExitStack() as stack:
            stack.enter_context(patch.object(subject, "identity", side_effect=lambda path:
                project_identity if path == project else upstream_identity))
            stack.enter_context(patch.object(subject, "verify_tracked", return_value={}))
            stack.enter_context(patch.object(subject, "verify_copied_wrapper", return_value={}))
            # This diagnostic-only fixture already substitutes source identity.
            # Real observer admission is covered by the temporary Git fixtures.
            stack.enter_context(patch.object(subject, "observer_hash", return_value=state["init_sha256"]))
            stack.enter_context(patch.object(subject, "validate_manifest" if manifest_failure else "validate_dependencies",
                                             side_effect=capture_failure))
            if manifest_failure:
                stack.enter_context(patch.object(subject, "run", return_value=xmltree().replace(
                    EXPECTED["version_name"], "private-provider-value").encode()))
            apk_check = stack.enter_context(patch.object(subject, "inspect_apk"))
            with self.assertRaises(ValueError) as caught:
                subject.record(project, upstream, work, sdk, project_identity["commit"])
            self.assertIs(caught.exception, original[0])
            if manifest_failure:
                apk_check.assert_called_once()
            else:
                apk_check.assert_not_called()
        target = work / "diagnostics" / ("manifest-version-failure.json" if manifest_failure else "dependency-failure.json")
        diagnostic = json.loads(target.read_text())
        self.assertEqual(diagnostic["project"], project_identity)
        self.assertEqual(diagnostic["upstream"], upstream_identity)
        if manifest_failure:
            self.assertEqual(diagnostic["apk_sha256"], subject.sha256_file(apk))
        else:
            self.assertEqual(diagnostic["dependencies_sha256"], subject.sha256_file(work / "dependencies.json"))
        self.assertFalse(diagnostic["qualified"])
        self.assertNotIn("private-provider-value", target.read_text())
        self.assertNotIn("/private/credential", target.read_text())
        self.assertEqual(list((work / "artifacts").iterdir()), [])
        self.assertEqual(list((work / "diagnostics").iterdir()), [target])

    def test_missing_coordinates_are_exact_and_diagnostic_is_allowlisted(self):
        receipt = dependencies()
        missing = EXPECTED["dependency_components"][0]
        receipt["components"].remove(missing)
        with self.assertRaisesRegex(ValueError, "missing from observed graph") as caught:
            subject.validate_dependencies(receipt, MANIFEST)
        self.assertEqual(str(caught.exception),
                         "Pinned dependencies missing from observed graph: " + missing)
        receipt["components"].append("secret arbitrary provider text")
        receipt["artifacts"].append({"component": "secret arbitrary provider text",
                                      "path": "/private/credential"})
        result = subject.dependency_diagnostic(receipt, MANIFEST)
        self.assertFalse(result["qualified"])
        self.assertNotIn("secret", json.dumps(result))
        self.assertNotIn("credential", json.dumps(result))
        self.assertFalse(result["pins"][0]["observed_component"])
        with self.assertRaisesRegex(ValueError, "bound"):
            subject.dependency_diagnostic(dict(receipt, components=["x"] * 20001), MANIFEST)
        receipt = dependencies()
        receipt["artifacts"] = receipt["artifacts"][1:]
        with self.assertRaisesRegex(ValueError, "missing observed artifact hashes") as caught:
            subject.validate_dependencies(receipt, MANIFEST)
        self.assertEqual(str(caught.exception),
                         "Pinned dependencies missing observed artifact hashes: " + missing)

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

    def record_fixture(self):
        """Real temporary Git/wrapper/ZIP checks; only SDK observations are mocked."""
        base = Path(tempfile.mkdtemp(dir=self.root))
        project, upstream, work, sdk = [base / name for name in ("project", "upstream", "work", "sdk")]
        for path in (project / "scripts", upstream, work / "artifacts"):
            path.mkdir(parents=True)

        def commit(path):
            subprocess.run(["git", "init", "--quiet", str(path)], check=True)
            subprocess.run(["git", "-C", str(path), "add", "."], check=True)
            subprocess.run(["git", "-C", str(path), "-c", "user.name=Fixture",
                            "-c", "user.email=fixture@example.invalid", "commit", "--quiet",
                            "--no-gpg-sign", "-m", "Synthetic guard fixture"], check=True)
            return subject.identity(path)

        (upstream / "source.txt").write_text("synthetic source\n")
        upstream_identity = commit(upstream)
        manifest = copy.deepcopy(MANIFEST)
        manifest["upstream"].update(upstream_identity)
        manifest["overlays"] = {}
        (project / subject.MANIFEST).write_text(json.dumps(manifest))
        (project / subject.INIT_SCRIPT).write_text("// synthetic observer fixture\n")
        for name in manifest["wrapper_blobs"]:
            path = project / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes((SCRIPT.parents[1] / name).read_bytes())
        project_identity = commit(project)
        for name, data in subject.expected_wrapper(project, manifest).items():
            path = work / "wrapper" / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        state = {"project": project_identity, "upstream": upstream_identity,
                 "manifest_sha256": subject.sha256_file(project / subject.MANIFEST),
                 "init_sha256": subject.sha256_file(project / subject.INIT_SCRIPT), "overlay_blobs": {},
                 "project_tracked_files": subject.verify_tracked(project),
                 "upstream_tracked_files": subject.verify_tracked(upstream),
                 "copied_wrapper_sha256": {name: subject.sha256_file(work / "wrapper" / name)
                                            for name in manifest["wrapper_blobs"]}}
        (work / "prepared.json").write_text(json.dumps(state))
        (work / "gradle-version.txt").write_text("Gradle 8.13\n")
        (work / "java-version.txt").write_text('openjdk version "17.0.20"\n')
        (work / "dependencies.json").write_text(json.dumps(dependencies()))
        for directory, revision in (("platforms/android-36", "2"),
                                    ("build-tools/35.0.0", "35.0.0"),
                                    ("ndk/21.4.7075529", "21.4.7075529")):
            path = sdk / directory
            path.mkdir(parents=True)
            (path / "source.properties").write_text("Pkg.Revision = " + revision + "\n")
        apk = upstream / "build/outputs/apk/phone/debug/fixture.apk"
        apk.parent.mkdir(parents=True)
        apk.write_bytes(self.apk().read_bytes())
        return project, upstream, work, sdk, project_identity["commit"]

    def record_synthetic(self, fixture):
        real_run = subject.run

        def sdk_output(argv, **kwargs):
            if argv[0] == "git":
                return real_run(argv, **kwargs)
            if argv[1] == "dump":
                return xmltree().encode()
            if argv[1] == "version":
                return b"Android Asset Packaging Tool (aapt) 2.19-12345678\n"
            if argv[1] == "verify":
                return ("Verifies\nSigner #1 certificate SHA-256 digest: " + "a" * 64 + "\n").encode()
            raise AssertionError("Unexpected SDK command")

        with patch.object(subject, "run", side_effect=sdk_output), patch("builtins.print"):
            subject.record(*fixture)

    def test_record_success_publishes_complete_ordinary_bundle(self):
        fixture = self.record_fixture()
        output = fixture[2] / "artifacts"
        self.record_synthetic(fixture)
        self.assertEqual(set(ARTIFACT_NAMES), {path.name for path in output.iterdir()})
        for path in output.iterdir():
            self.assertTrue(path.is_file())
            self.assertFalse(path.is_symlink())
            self.assertEqual(1, path.stat().st_nlink)
        receipt = json.loads((output / "receipt.json").read_text())
        self.assertEqual(subject.sha256_file(output / ARTIFACT_NAMES[0]), receipt["apk"]["sha256"])
        self.assertEqual(subject.sha256_file(output / "dependencies.json"), receipt["dependencies_sha256"])
        self.assertEqual(xmltree(), (output / "manifest.xmltree.txt").read_text())
        self.assertIn("Verifies", (output / "signing.txt").read_text())
        self.assertEqual("Android Asset Packaging Tool (aapt) 2.19-12345678", receipt["tools"]["aapt2"])
        self.assertTrue(receipt["scope"]["phone_debug_built"])
        for key in ("installed", "service_enabled", "speech_observed", "talkback_acceptance"):
            self.assertFalse(receipt["scope"][key])

    def test_record_rejects_all_existing_and_dangling_artifact_links(self):
        for name in ARTIFACT_NAMES:
            for dangling in (False, True):
                with self.subTest(name=name, dangling=dangling):
                    fixture = self.record_fixture()
                    work = fixture[2]
                    sentinel = work.parent / "outside-sentinel"
                    if not dangling:
                        sentinel.write_bytes(b"unchanged")
                    target = work / "artifacts" / name
                    target.symlink_to(sentinel)
                    with self.assertRaisesRegex(ValueError, "already exists"):
                        self.record_synthetic(fixture)
                    self.assertEqual([target], list(target.parent.iterdir()))
                    self.assertTrue(target.is_symlink())
                    if dangling:
                        self.assertFalse(sentinel.exists())
                    else:
                        self.assertEqual(b"unchanged", sentinel.read_bytes())

    def test_record_rejects_existing_files_and_hardlinks_without_writes(self):
        for hardlink in (False, True):
            fixture = self.record_fixture()
            output = fixture[2] / "artifacts"
            sentinel = fixture[2].parent / "outside-sentinel"
            sentinel.write_bytes(b"unchanged")
            target = output / "dependencies.json"
            if hardlink:
                target.hardlink_to(sentinel)
            else:
                target.write_bytes(b"unchanged")
            with self.assertRaisesRegex(ValueError, "already exists"):
                self.record_synthetic(fixture)
            self.assertEqual([target], list(output.iterdir()))
            self.assertEqual(b"unchanged", target.read_bytes())
            self.assertEqual(b"unchanged", sentinel.read_bytes())

    def test_record_rejects_linked_output_directory_and_ancestors(self):
        for component in ("artifacts", "work", "ancestor"):
            with self.subTest(component=component):
                fixture = list(self.record_fixture())
                work = fixture[2]
                if component == "artifacts":
                    outside = work.parent / "outside"
                    outside.mkdir()
                    (work / "artifacts").rmdir()
                    (work / "artifacts").symlink_to(outside, target_is_directory=True)
                elif component == "work":
                    outside = work.parent / "outside-work"
                    work.rename(outside)
                    work.symlink_to(outside, target_is_directory=True)
                    outside = outside / "artifacts"
                else:
                    container = work.parent / "container"
                    container.mkdir()
                    work.rename(container / "work")
                    link = work.parent / "ancestor-link"
                    link.symlink_to(container, target_is_directory=True)
                    fixture[2] = link / "work"
                    outside = container / "work/artifacts"
                sentinel = outside / "sentinel"
                sentinel.write_bytes(b"unchanged")
                with self.assertRaises((OSError, ValueError)):
                    self.record_synthetic(fixture)
                self.assertEqual([sentinel], list(outside.iterdir()))
                self.assertEqual(b"unchanged", sentinel.read_bytes())

    def test_publication_race_never_follows_replaced_destination(self):
        output = self.root / "artifacts"
        output.mkdir()
        sentinel = self.root / "outside"
        sentinel.write_bytes(b"unchanged")
        link = subject.os.link

        def replace_destination(source, destination, **kwargs):
            (output / destination).symlink_to(sentinel)
            return link(source, destination, **kwargs)

        with patch.object(subject.os, "link", side_effect=replace_destination):
            with self.assertRaises(FileExistsError):
                subject.publish_files(output, {"dependencies.json": b"new", "receipt.json": b"receipt"})
        self.assertEqual(b"unchanged", sentinel.read_bytes())
        self.assertEqual([output / "dependencies.json"], list(output.iterdir()))
        self.assertFalse((output / "receipt.json").exists())

    def test_staging_write_failure_publishes_no_files(self):
        output = self.root / "artifacts"
        output.mkdir()
        source = self.root / "source"
        source.write_bytes(b"source")
        error = OSError("synthetic write failure")
        with patch.object(subject.shutil, "copyfileobj", side_effect=error):
            with self.assertRaises(OSError) as caught:
                subject.publish_files(output, {"artifact": source, "receipt.json": b"receipt"})
        self.assertIs(error, caught.exception)
        self.assertEqual([], list(output.iterdir()))
        self.assertEqual(b"source", source.read_bytes())

    def test_staging_hash_mismatch_publishes_no_files(self):
        output = self.root / "artifacts"
        output.mkdir()
        source = self.root / "source"
        source.write_bytes(b"source")
        with self.assertRaisesRegex(ValueError, "Copied artifact bytes differ"):
            subject.publish_files(output, {"artifact": source, "receipt.json": b"receipt"},
                                  expected_hashes={"artifact": "0" * 64})
        self.assertEqual([], list(output.iterdir()))

    def test_diagnostic_links_and_ancestors_preserve_original_failures(self):
        for validator in ("validate_dependencies", "validate_manifest"):
            for linked in ("diagnostics", "work"):
                with self.subTest(validator=validator, linked=linked):
                    fixture = self.record_fixture()
                    work = fixture[2]
                    outside = work.parent / "outside"
                    if linked == "work":
                        # Admission now rejects linked input ancestors too. Move
                        # this diagnostic-only collision to the validation seam.
                        def fail_after_admission(*args, destination=outside):
                            work.rename(destination)
                            work.symlink_to(destination, target_is_directory=True)
                            raise error
                        outside = work
                    else:
                        outside.mkdir()
                        (work / "diagnostics").symlink_to(outside, target_is_directory=True)
                    sentinel = outside / "sentinel"
                    sentinel.write_bytes(b"unchanged")
                    before = set(outside.iterdir())
                    error = ValueError("original validation failure")
                    with patch.object(subject, validator, side_effect=(
                            fail_after_admission if linked == "work" else error)):
                        with self.assertRaises(ValueError) as caught:
                            self.record_synthetic(fixture)
                    self.assertIs(error, caught.exception)
                    self.assertEqual(before, set(outside.iterdir()))
                    self.assertEqual(b"unchanged", sentinel.read_bytes())
                    self.assertEqual([], list((work / "artifacts").iterdir()))

    def test_diagnostic_generation_and_write_failures_preserve_original_exceptions(self):
        for validator, diagnostic in (("validate_dependencies", "dependency_diagnostic"),
                                       ("validate_manifest", "manifest_version_diagnostic")):
            for failure in (diagnostic, "publish_files"):
                with self.subTest(validator=validator, failure=failure):
                    fixture = self.record_fixture()
                    original = ValueError("original validation failure")
                    with patch.object(subject, validator, side_effect=original), patch.object(
                            subject, failure, side_effect=OSError("synthetic diagnostic failure")):
                        with self.assertRaises(ValueError) as caught:
                            self.record_synthetic(fixture)
                    self.assertIs(original, caught.exception)
                    self.assertEqual("original validation failure", str(caught.exception))
                    self.assertEqual([], list((fixture[2] / "artifacts").iterdir()))

    def run_child(self, script, **kwargs):
        """Exercise real harmless children and verify the runner reaps/closes them."""
        created = []
        popen = subject.subprocess.Popen

        def observe(*args, **options):
            process = popen(*args, **options)
            created.append(process)
            return process

        with patch.object(subject.subprocess, "Popen", side_effect=observe):
            try:
                return subject.run([sys.executable, "-I", "-c", script], **kwargs)
            finally:
                self.assertEqual(1, len(created))
                self.assertIsNotNone(created[0].poll())
                self.assertTrue(created[0].stdout.closed)
                self.assertTrue(created[0].stderr.closed)
                with self.assertRaises(ChildProcessError):
                    os.waitpid(created[0].pid, os.WNOHANG)

    def test_run_preserves_stdout_stderr_order_and_nonzero_failure(self):
        script = 'import os; os.write(2, b"err"); os.write(1, b"out")'
        self.assertEqual(b"out", self.run_child(script))
        self.assertEqual(b"outerr", self.run_child(script, include_stderr=True))
        with self.assertRaises(subprocess.CalledProcessError) as caught:
            self.run_child(script + "; raise SystemExit(7)")
        self.assertEqual(7, caught.exception.returncode)
        self.assertEqual(b"out", caught.exception.output)
        self.assertEqual(b"err", caught.exception.stderr)

    def test_run_original_stderr_overflow_is_rejected_and_reaped(self):
        self.assertEqual(16 * 1024 * 1024, subject.COMMAND_OUTPUT_LIMIT)
        with self.assertRaisesRegex(ValueError, "Command output exceeds its bound"):
            self.run_child('import sys; sys.stderr.buffer.write(b"x"*(16*1024*1024+1)); '
                           'sys.stdout.buffer.write(b"ok")')

    def test_run_bounds_stdout_stderr_and_aggregate_while_child_is_alive(self):
        for stream in ("stdout", "stderr", "both"):
            for include_stderr in (False, True):
                with self.subTest(stream=stream, include_stderr=include_stderr):
                    script = 'import os, signal, time; signal.signal(signal.SIGTERM, signal.SIG_IGN); '
                    if stream == "both":
                        script += 'os.write(1, b"x"*600); os.write(2, b"y"*425); '
                    else:
                        script += 'os.write(' + ("1" if stream == "stdout" else "2") + ', b"x"*1025); '
                    script += 'time.sleep(30)'
                    start = time.monotonic()
                    with patch.object(subject, "COMMAND_OUTPUT_LIMIT", 1024):
                        with self.assertRaisesRegex(ValueError, "Command output exceeds its bound"):
                            self.run_child(script, include_stderr=include_stderr)
                    self.assertLess(time.monotonic() - start, 5)

    def test_run_accepts_exact_byte_boundary(self):
        with patch.object(subject, "COMMAND_OUTPUT_LIMIT", 1024):
            script = 'import os; os.write(1, b"x"*600); os.write(2, b"y"*424)'
            self.assertEqual(b"x" * 600, self.run_child(script))
            self.assertEqual(b"x" * 600 + b"y" * 424, self.run_child(script, include_stderr=True))

    def test_run_timeout_reaps_child_with_open_or_closed_streams(self):
        self.assertEqual(120, subject.COMMAND_TIMEOUT)
        for close_streams in (False, True):
            with self.subTest(close_streams=close_streams):
                script = 'import os, signal, time; signal.signal(signal.SIGTERM, signal.SIG_IGN); '
                script += 'os.write(1, b"out"); os.write(2, b"err"); '
                if close_streams:
                    script += 'os.close(1); os.close(2); '
                start = time.monotonic()
                with patch.object(subject, "COMMAND_TIMEOUT", 0.3):
                    with self.assertRaises(subprocess.TimeoutExpired) as caught:
                        self.run_child(script + 'time.sleep(30)')
                self.assertEqual(0.3, caught.exception.timeout)
                self.assertEqual(b"out", caught.exception.output)
                self.assertEqual(b"err", caught.exception.stderr)
                self.assertLess(time.monotonic() - start, 5)

    def test_run_failure_terminates_owned_descendants(self):
        # The synthetic parent reaps its child on TERM, so this test itself leaves
        # no orphan/zombie. The descendant retains both pipes and receives group TERM.
        marker = self.root / "descendant-reaped"
        script = '\n'.join([
            'import os, signal, subprocess, sys, time',
            'child = subprocess.Popen([sys.executable, "-I", "-c", "import time; time.sleep(30)"])',
            'def finish(signum, frame):',
            '    child.wait(timeout=1)',
            '    open(' + repr(str(marker)) + ', "w").write(str(child.returncode))',
            '    raise SystemExit(0)',
            'signal.signal(signal.SIGTERM, finish)',
            'time.sleep(30)'
        ])
        with patch.object(subject, "COMMAND_TIMEOUT", 0.5):
            with self.assertRaises(subprocess.TimeoutExpired):
                self.run_child(script)
        self.assertEqual("-15", marker.read_text())

    def test_run_read_failure_closes_streams_and_reaps_child(self):
        original = OSError("synthetic pipe read failure")
        read = subject.os.read

        def fail_pipe(descriptor, count):
            # Popen itself reads its exec-error pipe before the runner reads output.
            if count == 65536:
                raise original
            return read(descriptor, count)

        with patch.object(subject.os, "read", side_effect=fail_pipe):
            with self.assertRaises(OSError) as caught:
                self.run_child('import os, time; os.write(1, b"ready"); time.sleep(30)')
        self.assertIs(original, caught.exception)

    def commit_fixture(self, repository):
        subprocess.run(["git", "-C", str(repository), "add", "-A"], check=True)
        subprocess.run(["git", "-C", str(repository), "-c", "user.name=Fixture",
                        "-c", "user.email=fixture@example.invalid", "commit", "--quiet",
                        "--no-gpg-sign", "-m", "Synthetic prerequisite fixture"], check=True)
        return subject.identity(repository)

    def prepare_fixture(self):
        project, upstream, _, _, _ = self.record_fixture()
        shutil.rmtree(upstream / "build")
        original, changed = b"original overlay\n", b"prepared overlay\n"
        (upstream / "build.gradle").write_bytes(original)
        upstream_identity = self.commit_fixture(upstream)
        manifest = json.loads((project / subject.MANIFEST).read_text())
        manifest["upstream"].update(upstream_identity)
        manifest["overlays"] = {"build.gradle": {
            "before_blob": subject.blob(original), "after_blob": subject.blob(changed),
            "before_sha256": subject.hashlib.sha256(original).hexdigest(),
            "after_sha256": subject.hashlib.sha256(changed).hexdigest(),
            "replacements": [[original.decode(), changed.decode()]]}}
        (project / subject.MANIFEST).write_text(json.dumps(manifest))
        project_identity = self.commit_fixture(project)
        return project, upstream, project.parent / "new-work", project_identity["commit"]

    def assert_unprepared(self, fixture):
        self.assertEqual(b"original overlay\n", (fixture[1] / "build.gradle").read_bytes())
        self.assertEqual(b"", subject.git(fixture[1], "status", "--porcelain", "--untracked-files=all"))
        self.assertFalse((fixture[2] / "prepared.json").is_file())

    def test_prepare_success_writes_exclusive_wrapper_and_verified_state(self):
        fixture = self.prepare_fixture()
        project, upstream, work, _ = fixture
        with patch("builtins.print"):
            subject.prepare(*fixture)
        state = json.loads((work / "prepared.json").read_bytes())
        self.assertEqual(b"prepared overlay\n", (upstream / "build.gradle").read_bytes())
        self.assertEqual(subject.observer_hash(project), state["init_sha256"])
        manifest = json.loads((project / subject.MANIFEST).read_bytes())
        self.assertEqual(state["copied_wrapper_sha256"], subject.verify_copied_wrapper(
            project, work, manifest, state["copied_wrapper_sha256"]))
        self.assertEqual([], list((work / "artifacts").iterdir()))
        self.assertLessEqual((work / "prepared.json").stat().st_size, subject.RECEIPT_BYTES)
        for path in (work / "prepared.json", *[work / "wrapper" / name for name in manifest["wrapper_blobs"]]):
            self.assertFalse(path.is_symlink())
            self.assertEqual(1, path.stat().st_nlink)
        self.assertEqual(0o755, (work / "wrapper/gradlew").stat().st_mode & 0o777)

    def test_prepare_rejects_linked_ancestors_parent_traversal_and_existing_roots(self):
        for variant in ("ancestor", "traversal", "dangling", "directory", "file"):
            with self.subTest(variant=variant):
                fixture = list(self.prepare_fixture())
                work = fixture[2]
                outside = work.parent / "outside"
                outside.mkdir()
                sentinel = outside / "sentinel"
                sentinel.write_bytes(b"unchanged")
                if variant == "ancestor":
                    alias = work.parent / "alias"
                    alias.symlink_to(outside, target_is_directory=True)
                    fixture[2] = alias / "new-work"
                elif variant == "traversal":
                    fixture[2] = outside / ".." / "new-work"
                elif variant == "dangling":
                    work.symlink_to(outside / "missing", target_is_directory=True)
                elif variant == "directory":
                    work.mkdir()
                else:
                    work.write_bytes(b"unchanged")
                with self.assertRaises((OSError, ValueError)):
                    subject.prepare(*fixture)
                self.assert_unprepared(fixture)
                self.assertEqual([sentinel], list(outside.iterdir()))
                self.assertEqual(b"unchanged", sentinel.read_bytes())

    def test_prepare_wrapper_races_and_collisions_precede_source_mutation(self):
        for variant in ("wrapper-link", "file-link", "file-hardlink", "file-existing", "root-swap"):
            with self.subTest(variant=variant):
                fixture = self.prepare_fixture()
                work = fixture[2]
                outside = work.parent / "outside"
                outside.mkdir()
                sentinel = outside / "sentinel"
                sentinel.write_bytes(b"unchanged")
                mkdir, open_file, wrapper = subject.os.mkdir, subject.os.open, subject.prepare_wrapper

                def race_mkdir(name, *args, **kwargs):
                    if variant == "wrapper-link" and name == "wrapper":
                        os.symlink(outside, name, dir_fd=kwargs["dir_fd"])
                    return mkdir(name, *args, **kwargs)

                def race_open(name, flags, *args, **kwargs):
                    if name == "gradlew" and flags & os.O_CREAT:
                        parent = kwargs["dir_fd"]
                        if variant == "file-link":
                            os.symlink(sentinel, name, dir_fd=parent)
                        elif variant == "file-hardlink":
                            os.link(sentinel, name, dst_dir_fd=parent)
                        elif variant == "file-existing":
                            fd = open_file(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600, dir_fd=parent)
                            os.close(fd)
                    return open_file(name, flags, *args, **kwargs)

                def race_root(*args):
                    wrapper(*args)
                    if variant == "root-swap":
                        work.rename(work.parent / "owned-original")
                        work.symlink_to(outside, target_is_directory=True)

                with patch.object(subject.os, "mkdir", side_effect=race_mkdir), patch.object(
                        subject.os, "open", side_effect=race_open), patch.object(
                        subject, "prepare_wrapper", side_effect=race_root):
                    with self.assertRaises((OSError, ValueError)):
                        subject.prepare(*fixture)
                self.assert_unprepared(fixture)
                self.assertEqual([sentinel], list(outside.iterdir()))
                self.assertEqual(b"unchanged", sentinel.read_bytes())

    def test_prepare_missing_untracked_and_linked_observer_leave_no_changes(self):
        for variant in ("missing", "untracked", "linked"):
            with self.subTest(variant=variant):
                fixture = list(self.prepare_fixture())
                project, upstream, work, _ = fixture
                observer = project / subject.INIT_SCRIPT
                observer.unlink()
                fixture[3] = self.commit_fixture(project)["commit"]
                if variant == "untracked":
                    observer.write_text("synthetic untracked observer")
                elif variant == "linked":
                    sentinel = project.parent / "observer-sentinel"
                    sentinel.write_text("synthetic linked observer")
                    observer.symlink_to(sentinel)
                    fixture[3] = self.commit_fixture(project)["commit"]
                before = subject.git(project, "status", "--porcelain", "--untracked-files=all")
                with self.assertRaisesRegex(ValueError, "Required tracked dependency observer is unavailable"):
                    subject.prepare(*fixture)
                self.assert_unprepared(fixture)
                self.assertFalse(work.exists())
                self.assertEqual(before, subject.git(project, "status", "--porcelain", "--untracked-files=all"))

    def test_read_observation_limits_reads_and_rejects_indirection_and_nonfiles(self):
        path = self.root / "observation"
        path.write_bytes(b"x" * 32)
        self.assertEqual(b"x" * 32, subject.read_observation(path, 32))
        read, fstat = subject.os.read, subject.os.fstat
        counts = []

        def count_read(descriptor, count):
            value = read(descriptor, count)
            counts.append((count, len(value)))
            return value

        path.write_bytes(b"x" * 1000)
        with patch.object(subject.os, "read", side_effect=count_read):
            with self.assertRaisesRegex(ValueError, "byte bound"):
                subject.read_observation(path, 32)
        self.assertEqual([], counts)

        def simulate_growth(descriptor):
            fields = list(fstat(descriptor))
            fields[6] = 32
            return os.stat_result(fields)

        with patch.object(subject.os, "read", side_effect=count_read), patch.object(
                subject.os, "fstat", side_effect=simulate_growth):
            with self.assertRaisesRegex(ValueError, "byte bound"):
                subject.read_observation(path, 32)
        self.assertEqual([(33, 33)], counts)
        for variant in ("symlink", "dangling", "hardlink", "fifo", "directory", "ancestor"):
            candidate = self.root / variant
            if variant == "symlink":
                candidate.symlink_to(path)
            elif variant == "dangling":
                candidate.symlink_to(self.root / "missing")
            elif variant == "hardlink":
                candidate.hardlink_to(path)
            elif variant == "fifo":
                os.mkfifo(candidate)
            elif variant == "directory":
                candidate.mkdir()
            else:
                candidate.symlink_to(self.root, target_is_directory=True)
                candidate = candidate / "observation"
            with self.subTest(variant=variant), self.assertRaises((OSError, ValueError)):
                subject.read_observation(candidate, 1024)

    def test_record_version_observation_boundaries_and_original_overflow_witness(self):
        for name, prefix in (("java-version.txt", b'openjdk version "17.0.20"\n'),
                             ("gradle-version.txt", b"Gradle 8.13\n")):
            for size in (subject.OBSERVATION_BYTES, subject.OBSERVATION_BYTES + 1, 16 * 1024 * 1024 + 27):
                with self.subTest(name=name, size=size):
                    fixture = self.record_fixture()
                    raw = prefix + b" " * (size - len(prefix))
                    (fixture[2] / name).write_bytes(raw)
                    if size == subject.OBSERVATION_BYTES:
                        self.record_synthetic(fixture)
                        receipt = fixture[2] / "artifacts/receipt.json"
                        field = "java_output" if name.startswith("java") else "gradle_output"
                        self.assertEqual(raw.decode(), json.loads(receipt.read_text())["tools"][field])
                        self.assertLessEqual(receipt.stat().st_size, subject.RECEIPT_BYTES)
                    else:
                        with self.assertRaisesRegex(ValueError, "byte bound"):
                            self.record_synthetic(fixture)
                        self.assertEqual([], list((fixture[2] / "artifacts").iterdir()))

    def test_record_rejects_linked_and_oversized_adjacent_observations(self):
        for name in ("manifest", "observer", "prepared", "dependencies", "wrapper", "sdk"):
            for variant in ("linked", "oversized"):
                with self.subTest(name=name, variant=variant):
                    fixture = self.record_fixture()
                    project, _, work, sdk, _ = fixture
                    path, limit = {
                        "manifest": (project / subject.MANIFEST, subject.SPECIFICATION_BYTES),
                        "observer": (project / subject.INIT_SCRIPT, subject.SPECIFICATION_BYTES),
                        "prepared": (work / "prepared.json", subject.SPECIFICATION_BYTES),
                        "dependencies": (work / "dependencies.json", subject.DEPENDENCY_BYTES),
                        "wrapper": (work / "wrapper/gradlew", subject.SPECIFICATION_BYTES),
                        "sdk": (sdk / "platforms/android-36/source.properties", subject.OBSERVATION_BYTES)
                    }[name]
                    if variant == "linked":
                        sentinel = work.parent / "sentinel"
                        original = path.read_bytes()
                        sentinel.write_bytes(original)
                        path.unlink()
                        path.symlink_to(sentinel)
                    else:
                        path.write_bytes(b"x" * (limit + 1))
                    with self.assertRaises((OSError, ValueError)):
                        self.record_synthetic(fixture)
                    self.assertEqual([], list((work / "artifacts").iterdir()))
                    if variant == "linked":
                        self.assertEqual(original, sentinel.read_bytes())

    def test_serialized_receipt_bound_is_exact_and_rejects_before_publication(self):
        value = {"value": "quote\" and Unicode \u2603"}
        expected = (json.dumps(value, sort_keys=True, indent=2) + "\n").encode()
        with patch.object(subject, "RECEIPT_BYTES", len(expected)):
            self.assertEqual(expected, subject.receipt_bytes(value))
        with patch.object(subject, "RECEIPT_BYTES", len(expected) - 1):
            with self.assertRaisesRegex(ValueError, "Receipt exceeds its byte bound"):
                subject.receipt_bytes(value)
        fixture = self.record_fixture()
        with patch.object(subject, "RECEIPT_BYTES", 16):
            with self.assertRaisesRegex(ValueError, "Receipt exceeds its byte bound"):
                self.record_synthetic(fixture)
        self.assertEqual([], list((fixture[2] / "artifacts").iterdir()))

    def test_dependency_unknown_fields_reject_without_publishing_private_markers(self):
        for position in ("top", "artifact"):
            fixture = self.record_fixture()
            path = fixture[2] / "dependencies.json"
            observation = json.loads(path.read_text())
            marker = "SYNTHETIC_PRIVATE_MARKER"
            key = "synthetic_private_key"
            (observation if position == "top" else observation["artifacts"][0])[key] = marker
            path.write_text(json.dumps(observation))
            original, validator = [], subject.validate_dependencies

            def capture(*args):
                try:
                    validator(*args)
                except ValueError as error:
                    original.append(error)
                    raise

            with patch.object(subject, "validate_dependencies", side_effect=capture):
                with self.assertRaises(ValueError) as caught:
                    self.record_synthetic(fixture)
            self.assertIs(original[0], caught.exception)
            self.assertEqual("Dependency observation fields differ" if position == "top" else
                             "Dependency artifact fields differ", str(caught.exception))
            self.assertEqual([], list((fixture[2] / "artifacts").iterdir()))
            diagnostic = (fixture[2] / "diagnostics/dependency-failure.json").read_text()
            self.assertNotIn(marker, diagnostic + str(caught.exception))
            self.assertNotIn(key, diagnostic + str(caught.exception))
            self.assertEqual({"schema", "qualified", "component_count", "artifact_count", "pins",
                              "project", "upstream", "dependencies_sha256"}, set(json.loads(diagnostic)))
            self.assertFalse(json.loads(diagnostic)["qualified"])

    def test_dependency_producer_configurations_and_raw_hash_are_preserved(self):
        fixture = self.record_fixture()
        path = fixture[2] / "dependencies.json"
        observation = json.loads(path.read_text())
        observation["configurations"] = [":|1|classpath", ":|0|observed", ":talkback|0|phoneDebugRuntimeClasspath"]
        raw = (json.dumps(observation, sort_keys=True, indent=2) + "\n").encode()
        path.write_bytes(raw)
        self.record_synthetic(fixture)
        published = fixture[2] / "artifacts/dependencies.json"
        self.assertEqual(raw, published.read_bytes())
        receipt = json.loads((fixture[2] / "artifacts/receipt.json").read_bytes())
        self.assertEqual(subject.hashlib.sha256(raw).hexdigest(), receipt["dependencies_sha256"])
        self.assertFalse(receipt["dependency_checksums_preapproved"])
        self.assertFalse(receipt["scope"]["talkback_acceptance"])

    def test_dependency_duplicate_members_cannot_hide_private_publication(self):
        marker = "SYNTHETIC_DUPLICATE_PRIVATE_MARKER"
        observation = dependencies()
        valid = json.dumps(observation, separators=(",", ":"))
        raw_variants = {
            "top": '{"artifacts":[{"private_marker":"' + marker + '"}],' + valid[1:],
            "escaped-top": '{"\\u0061rtifacts":[{"private_marker":"' + marker + '"}],' + valid[1:],
            "artifact": valid.replace('"name":', '"name":"' + marker + '","name":', 1),
            "module": valid.replace('"compile_sdk":', '"compile_sdk":"' + marker + '","compile_sdk":', 1),
        }
        for position, raw in raw_variants.items():
            with self.subTest(position=position):
                self.assertEqual(observation, json.loads(raw))
                fixture = self.record_fixture()
                (fixture[2] / "dependencies.json").write_text(raw)
                with self.assertRaisesRegex(ValueError, "Duplicate dependency observation member") as caught:
                    self.record_synthetic(fixture)
                self.assertNotIn(marker, str(caught.exception))
                self.assertEqual([], list((fixture[2] / "artifacts").iterdir()))
                self.assertFalse((fixture[2] / "diagnostics").exists())
        adjacent = self.record_fixture()
        (adjacent[2] / "dependencies.json").write_text(valid)
        self.record_synthetic(adjacent)
        self.assertEqual(valid.encode(), (adjacent[2] / "artifacts/dependencies.json").read_bytes())

    def test_record_requires_tracked_observer_despite_self_consistent_prepared_state(self):
        for variant in ("untracked", "missing", "linked"):
            with self.subTest(variant=variant):
                fixture = list(self.record_fixture())
                project, upstream, work, _, _ = fixture
                observer = project / subject.INIT_SCRIPT
                original = observer.read_bytes()
                subject.git(project, "rm", "--cached", "--", subject.INIT_SCRIPT)
                subject.git(project, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                            "commit", "--quiet", "--no-gpg-sign", "-m", "Synthetic untracked observer")
                if variant != "untracked":
                    observer.unlink()
                if variant == "linked":
                    sentinel = project.parent / "observer-sentinel"
                    sentinel.write_bytes(original)
                    observer.symlink_to(sentinel)
                identity = subject.identity(project)
                fixture[4] = identity["commit"]
                prepared = work / "prepared.json"
                state = json.loads(prepared.read_bytes())
                state.update(project=identity, project_tracked_files=subject.verify_tracked(project),
                             init_sha256=subject.hashlib.sha256(original).hexdigest())
                prepared.write_text(json.dumps(state))
                before = prepared.read_bytes()
                status = subject.git(project, "status", "--porcelain", "--untracked-files=all")
                with self.assertRaisesRegex(ValueError, "Required tracked dependency observer is unavailable"):
                    self.record_synthetic(fixture)
                self.assertEqual(before, prepared.read_bytes())
                self.assertEqual(status, subject.git(project, "status", "--porcelain", "--untracked-files=all"))
                self.assertEqual([], list((work / "artifacts").iterdir()))
                self.assertFalse((work / "diagnostics").exists())
                if variant == "linked":
                    self.assertEqual(original, sentinel.read_bytes())
        adjacent = self.record_fixture()
        self.record_synthetic(adjacent)
        self.assertTrue((adjacent[2] / "artifacts/receipt.json").is_file())

    def test_dependency_configurations_and_nonobject_rows_fail_closed(self):
        variants = [[], dict(dependencies(), artifacts=[[]]), dict(dependencies(), artifacts=[{}])]
        for configurations in (None, [], [":|2|unknown"], ["unstructured"], [":|1|x", ":|1|x"], [42]):
            variants.append(dict(dependencies(), configurations=configurations))
        for receipt in variants:
            with self.subTest(receipt=receipt), self.assertRaises(ValueError):
                subject.validate_dependencies(receipt, MANIFEST)

    def test_dependency_publication_uses_only_the_validated_snapshot(self):
        fixture = self.record_fixture()
        path = fixture[2] / "dependencies.json"
        validated = path.read_bytes()
        validator = subject.validate_dependencies

        def replace_after_validation(*args):
            validator(*args)
            changed = json.loads(validated)
            changed["unknown_private_key"] = "SYNTHETIC_LATE_PRIVATE_MARKER"
            path.write_text(json.dumps(changed))

        with patch.object(subject, "validate_dependencies", side_effect=replace_after_validation):
            self.record_synthetic(fixture)
        self.assertNotEqual(validated, path.read_bytes())
        self.assertEqual(validated, (fixture[2] / "artifacts/dependencies.json").read_bytes())
        receipt = json.loads((fixture[2] / "artifacts/receipt.json").read_bytes())
        self.assertEqual(subject.hashlib.sha256(validated).hexdigest(), receipt["dependencies_sha256"])

    def test_prepare_receipt_collision_is_detected_before_source_mutation(self):
        fixture = self.prepare_fixture()
        sentinel = fixture[2].parent / "sentinel"
        sentinel.write_bytes(b"unchanged")
        wrapper = subject.prepare_wrapper

        def inject_receipt(directory, data):
            wrapper(directory, data)
            os.symlink(sentinel, "prepared.json", dir_fd=directory)

        with patch.object(subject, "prepare_wrapper", side_effect=inject_receipt):
            with self.assertRaisesRegex(ValueError, "already exists"):
                subject.prepare(*fixture)
        self.assertEqual(b"original overlay\n", (fixture[1] / "build.gradle").read_bytes())
        self.assertEqual(b"unchanged", sentinel.read_bytes())
        self.assertEqual(["artifacts", "prepared.json", "wrapper"], sorted(path.name for path in fixture[2].iterdir()))


EXPECTED_TEST_METHODS = frozenset("""
test_aapt2_full_android_namespace_preserves_exact_checks
test_aapt2_version_captures_successful_stderr_and_stdout
test_aapt2_version_rejects_missing_conflicting_malformed_and_failed_output
test_arm_payload_hidden_under_x86_64_name_is_rejected
test_declared_service_requires_its_own_permission_action_and_configuration
test_dependency_configurations_and_nonobject_rows_fail_closed
test_dependency_duplicate_members_cannot_hide_private_publication
test_dependency_producer_configurations_and_raw_hash_are_preserved
test_dependency_publication_uses_only_the_validated_snapshot
test_dependency_unknown_fields_reject_without_publishing_private_markers
test_diagnostic_generation_and_write_failures_preserve_original_exceptions
test_diagnostic_links_and_ancestors_preserve_original_failures
test_duplicate_and_escaping_apk_entries_are_rejected
test_duplicate_attributes_wrong_package_and_unexported_service_fail
test_elf_class_and_apk_resource_limits_are_enforced
test_exact_overlay_requires_both_pinned_input_and_output
test_first_resolution_is_observed_and_never_preapproved
test_foreign_abi_and_missing_source_library_are_rejected
test_incomplete_changed_dynamic_or_duplicated_dependencies_are_rejected
test_manifest_diagnostic_is_bounded_and_allowlisted
test_matching_strings_on_a_different_service_cannot_satisfy_the_guard
test_missing_coordinates_are_exact_and_diagnostic_is_allowlisted
test_missing_or_duplicate_overlay_anchor_is_rejected
test_namespace_alias_collisions_are_rejected
test_native_guard_accepts_all_required_elf64_libraries
test_only_explicit_existing_overlay_can_differ_from_native_map
test_output_and_input_roots_must_not_overlap
test_prepare_missing_untracked_and_linked_observer_leave_no_changes
test_prepare_receipt_collision_is_detected_before_source_mutation
test_prepare_rejects_linked_ancestors_parent_traversal_and_existing_roots
test_prepare_success_writes_exclusive_wrapper_and_verified_state
test_prepare_wrapper_races_and_collisions_precede_source_mutation
test_publication_race_never_follows_replaced_destination
test_read_observation_limits_reads_and_rejects_indirection_and_nonfiles
test_record_failure_writes_only_diagnostic_and_reraises_original
test_record_manifest_failure_preserves_exception_and_no_qualified_artifact
test_record_rejects_all_existing_and_dangling_artifact_links
test_record_rejects_existing_files_and_hardlinks_without_writes
test_record_rejects_linked_and_oversized_adjacent_observations
test_record_rejects_linked_output_directory_and_ancestors
test_record_requires_tracked_observer_despite_self_consistent_prepared_state
test_record_success_publishes_complete_ordinary_bundle
test_record_version_observation_boundaries_and_original_overflow_witness
test_replacement_refs_and_promisor_configuration_are_not_admitted
test_run_accepts_exact_byte_boundary
test_run_bounds_stdout_stderr_and_aggregate_while_child_is_alive
test_run_failure_terminates_owned_descendants
test_run_original_stderr_overflow_is_rejected_and_reaped
test_run_preserves_stdout_stderr_order_and_nonzero_failure
test_run_read_failure_closes_streams_and_reaps_child
test_run_timeout_reaps_child_with_open_or_closed_streams
test_serialized_receipt_bound_is_exact_and_rejects_before_publication
test_signature_observation_requires_one_verified_signer_identity
test_staging_hash_mismatch_publishes_no_files
test_staging_write_failure_publishes_no_files
test_tracked_map_rejects_unrelated_change_deletion_and_mode_change
""".split())


if __name__ == "__main__":
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(TalkBackPrerequisiteGuards)
    observed = {test._testMethodName for test in suite}
    if suite.countTestCases() != 56 or observed != EXPECTED_TEST_METHODS:
        raise SystemExit("Expected the exact 56 prerequisite guard methods")
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    raise SystemExit(0 if result.wasSuccessful() and result.testsRun == 56 and not result.skipped else 1)
