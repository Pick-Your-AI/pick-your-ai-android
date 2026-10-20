"""Fail-closed checks for the packaged synthetic-preview declaration boundary."""
import copy
from contextlib import ExitStack, redirect_stdout
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import subprocess
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
import check_environment_configuration as environment
import check_preview_policy_guards as packaged_guards
import check_runtime_inventory_guards as ignored_guards
import check_runtime_inventory_source as runtime_source

spec = importlib.util.spec_from_file_location("preview_policy", ROOT / "scripts/check_preview_policy.py")
policy = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = policy
spec.loader.exec_module(policy)
E = policy.Element


def manifest():
    return E("manifest", {"package": policy.PACKAGE, "android:versionCode": "1",
                          "android:versionName": "0.0.1-synthetic"}, [
        E("uses-sdk", {"android:minSdkVersion": "26", "android:targetSdkVersion": "35"}),
        E("application", {"android:debuggable": "true", "android:allowBackup": "false",
                          "android:fullBackupContent": "false", "android:usesCleartextTraffic": "false",
                          "android:dataExtractionRules": "@0x7f040000"}, [
            E("activity", {"android:name": policy.PACKAGE + ".PreviewActivity", "android:exported": "true",
                           "android:launchMode": "1"}, [
                E("intent-filter", {}, [E("action", {"android:name": "android.intent.action.MAIN"}),
                                        E("category", {"android:name": "android.intent.category.LAUNCHER"})])])])])


def backup():
    return E("data-extraction-rules", {}, [E(name, {}, [E("exclude", {"domain": d, "path": "."})
                for d in sorted(policy.DOMAINS)]) for name in ["cloud-backup", "device-transfer"]])


class PreviewPolicyTest(unittest.TestCase):
    def test_version_reads_only_the_sdk_output_stream_without_accepting_diagnostics(self):
        version = "Android Asset Packaging Tool (aapt) 2.19-11948202\n"
        with tempfile.TemporaryDirectory() as temporary:
            tool = Path(temporary) / "aapt2-fixture"
            for stdout, stderr, code, expected in [
                ("", version, 0, None),
                (version, "", 0, "TOOL_FAILED"),
                ("unexpected output", version, 0, "TOOL_FAILED"),
                ("", "private diagnostic sentinel", 0, "TOOL_VERSION_FORMAT"),
                ("", version + "unexpected diagnostic\n", 0, "TOOL_VERSION_FORMAT"),
                ("", version, 1, "TOOL_FAILED"),
            ]:
                tool.write_text(f"#!{sys.executable}\nimport sys\n"
                                f"sys.stdout.write({stdout!r})\nsys.stderr.write({stderr!r})\n"
                                f"sys.exit({code})\n")
                tool.chmod(0o700)
                with self.subTest(stdout=stdout, stderr=stderr, code=code):
                    if expected is None:
                        self.assertEqual(version.strip(), policy.aapt2_version(tool))
                        # Ordinary inspection must still fail on the same stderr.
                        with self.assertRaisesRegex(policy.PolicyFailure, "^TOOL_FAILED$"):
                            policy.command([str(tool), "dump", "resources", "fixture.apk"])
                    else:
                        with self.assertRaisesRegex(policy.PolicyFailure, "^" + expected + "$"):
                            policy.aapt2_version(tool)

    def test_adopted_policy_accepts(self):
        self.assertEqual("0x7f040000", policy.manifest_policy(manifest()))
        policy.backup_policy(backup())

    def test_every_permission_form_and_additional_root_component_is_rejected(self):
        for name in ["uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m", "permission",
                     "instrumentation", "queries", "uses-feature", "application"]:
            with self.subTest(name=name):
                root = manifest()
                root.children.append(E(name))
                with self.assertRaises(policy.PolicyFailure):
                    policy.manifest_policy(root)

    def test_additional_components_are_rejected_even_when_unexported(self):
        for name in ["activity", "activity-alias", "receiver", "service", "provider", "meta-data"]:
            root = manifest()
            root.children[1].children.append(E(name, {"android:exported": "false"}))
            with self.subTest(name=name), self.assertRaises(policy.PolicyFailure):
                policy.manifest_policy(root)

    def test_security_flags_cannot_be_removed_or_enabled(self):
        for key in ["android:allowBackup", "android:fullBackupContent", "android:usesCleartextTraffic"]:
            for value in [None, "true", "@0x7f040001"]:
                root = manifest()
                if value is None:
                    del root.children[1].attributes[key]
                else:
                    root.children[1].attributes[key] = value
                with self.subTest(key=key, value=value), self.assertRaises(policy.PolicyFailure):
                    policy.manifest_policy(root)

    def test_unreviewed_application_overrides_and_manifest_identity_are_rejected(self):
        for key in ["android:networkSecurityConfig", "android:backupAgent", "android:permission",
                    "android:sharedUserId", "android:appComponentFactory"]:
            root = manifest()
            root.children[1].attributes[key] = "unexpected"
            with self.subTest(key=key), self.assertRaises(policy.PolicyFailure):
                policy.manifest_policy(root)
        root = manifest()
        root.attributes["package"] = "unexpected"
        with self.assertRaisesRegex(policy.PolicyFailure, "PACKAGE_IDENTITY"):
            policy.manifest_policy(root)

    def test_inbound_data_or_extra_action_cannot_join_the_launcher(self):
        for node in [E("data", {"android:scheme": "https"}), E("action", {"android:name": "android.intent.action.VIEW"})]:
            root = manifest()
            root.children[1].children[0].children[0].children.append(node)
            with self.assertRaises(policy.PolicyFailure):
                policy.manifest_policy(root)

    def test_each_transport_requires_every_distinct_domain_root(self):
        for index in [0, 1]:
            for mutation in ["missing", "duplicate", "narrow", "include"]:
                root = backup()
                rows = root.children[index].children
                if mutation == "missing":
                    rows.pop()
                elif mutation == "duplicate":
                    rows[-1] = copy.deepcopy(rows[0])
                elif mutation == "narrow":
                    rows[-1].attributes["path"] = "only-one-file"
                else:
                    rows[-1].name = "include"
                with self.subTest(index=index, mutation=mutation), self.assertRaises(policy.PolicyFailure):
                    policy.backup_policy(root)

    def test_missing_duplicate_or_attributed_transport_is_rejected(self):
        for mutation in ["missing", "duplicate", "attribute"]:
            root = backup()
            if mutation == "missing":
                root.children.pop()
            elif mutation == "duplicate":
                root.children[1] = copy.deepcopy(root.children[0])
            else:
                root.children[0].attributes["disableIfNoEncryptionCapabilities"] = "true"
            with self.assertRaises(policy.PolicyFailure):
                policy.backup_policy(root)

    def test_referenced_resource_requires_one_exact_default_file_not_alias_or_override(self):
        header = "    resource 0x7f040000 xml/backup_rules\n"
        entry = "      () (file) res/xml/backup_rules.xml type=XML\n"
        policy.backup_resource_policy("Binary APK\n" + header + entry, "0x7f040000")
        for value in [header + "      () @0x7f040001\n", header + entry.replace("backup_rules.xml", "other.xml"),
                      header + entry + "      (v35) (file) res/xml-v35/backup_rules.xml type=XML\n",
                      (header + entry) * 2, header + entry.replace("()", "(v31)")]:
            with self.subTest(value=value), self.assertRaises(policy.PolicyFailure):
                policy.backup_resource_policy(value, "0x7f040000")
        with self.assertRaises(policy.PolicyFailure):
            policy.backup_resource_policy(header + entry, "0x7f040001")

    def test_xmltree_preserves_structure_and_rejects_unknown_or_ambiguous_output(self):
        text = 'N: android=http://schemas.android.com/apk/res/android (line=2)\n  E: manifest (line=2)\n' + \
               '    A: package="safe" (Raw: "safe")\n      E: application (line=3)\n' + \
               '        A: http://schemas.android.com/apk/res/android:allowBackup(0x01010280)=false\n'
        root = policy.xmltree(text)
        self.assertEqual("safe", root.attributes["package"])
        self.assertEqual("false", root.children[0].attributes["android:allowBackup"])
        for changed in [text.replace('Raw: "safe"', 'Raw: "different"'), text + "unparsed content\n",
                        text + '        A: http://schemas.android.com/apk/res/android:allowBackup(0x01010280)=true\n',
                        text + "E: manifest (line=2)\n", text.replace("false", "@null"), ""]:
            with self.subTest(changed=changed), self.assertRaises(policy.PolicyFailure):
                policy.xmltree(changed)

    def test_framework_attribute_ids_must_match_the_qualified_name(self):
        prefix = "E: application (line=1)\n  A: "
        valid = policy.ANDROID + "allowBackup(0x01010280)=false"
        self.assertEqual("false", policy.xmltree(prefix + valid).attributes["android:allowBackup"])
        for attribute in [
            valid.replace("(0x01010280)", ""),
            valid.replace("0x01010280", "0x00000000"),
            valid.replace("0x01010280", "0x01010281"),
            valid.replace("allowBackup", "fullBackupContent"),
            valid.replace(policy.ANDROID, "android:"),
            valid.replace(policy.ANDROID, "http://synthetic.invalid/android:"),
            'package(0x01010280)="synthetic" (Raw: "synthetic")',
            'domain(0x01010280)="root" (Raw: "root")',
        ]:
            with self.subTest(attribute=attribute), \
                 self.assertRaisesRegex(policy.PolicyFailure, "^XML_ATTRIBUTE_RESOURCE_ID$"):
                policy.xmltree(prefix + attribute)

    def test_framework_attribute_ids_keep_lookup_order_within_each_element(self):
        first = "  A: " + policy.ANDROID + "allowBackup(0x01010280)=false\n"
        second = "  A: " + policy.ANDROID + "fullBackupContent(0x010104eb)=false\n"
        plain = '  A: label="synthetic" (Raw: "synthetic")\n'
        child = "  E: activity (line=2)\n    A: " + policy.ANDROID + 'name(0x01010003)="x" (Raw: "x")\n'
        # Unqualified zero-ID attributes and a child's separate lower IDs
        # must not reset or contaminate the parent's framework lookup order.
        valid = "E: application (line=1)\n" + first + plain + second + child
        self.assertEqual("false", policy.xmltree(valid).attributes["android:allowBackup"])
        with self.assertRaisesRegex(policy.PolicyFailure, "^XML_ATTRIBUTE_RESOURCE_ORDER$"):
            policy.xmltree("E: application (line=1)\n" + second + plain + first)

    def test_reference_boolean_and_integer_spellings_cannot_be_quoted_strings(self):
        for name, value in [
            ("dataExtractionRules", "@0x7f040000"), ("theme", "@0x01030241"), ("icon", "@0x7f010000"),
            ("fullBackupContent", "false"), ("allowBackup", "false"), ("usesCleartextTraffic", "false"),
            ("debuggable", "true"), ("exported", "true"), ("supportsRtl", "true"),
            ("extractNativeLibs", "false"), ("minSdkVersion", "26"), ("targetSdkVersion", "35"),
            ("compileSdkVersion", "35"), ("versionCode", "1"), ("launchMode", "1"),
        ]:
            prefix = "E: application (line=1)\n  A: " + policy.ANDROID + name + \
                     "(" + policy.FRAMEWORK_ATTRIBUTE_IDS[name] + ")="
            with self.subTest(name=name):
                self.assertEqual(value, policy.xmltree(prefix + value).attributes["android:" + name])
                quoted = json.dumps(value)
                with self.assertRaisesRegex(policy.PolicyFailure, "^XML_ATTRIBUTE_VALUE_KIND$"):
                    policy.xmltree(prefix + quoted + " (Raw: " + quoted + ")")

    def test_string_attributes_keep_their_kinds_and_explicit_label_reference(self):
        for name in ["name", "versionName", "compileSdkVersionCodename", "label"]:
            prefix = "E: application (line=1)\n  A: " + policy.ANDROID + name + \
                     "(" + policy.FRAMEWORK_ATTRIBUTE_IDS[name] + ")="
            self.assertEqual("1", policy.xmltree(prefix + '\"1\" (Raw: \"1\")').attributes["android:" + name])
            invalid = ["1", "true", "false"] + ([] if name == "label" else ["@0x7f040000"])
            for value in invalid:
                with self.subTest(name=name, value=value), \
                     self.assertRaisesRegex(policy.PolicyFailure, "^XML_ATTRIBUTE_VALUE_KIND$"):
                    policy.xmltree(prefix + value)
            if name == "label":
                self.assertEqual("@0x7f040000", policy.xmltree(prefix + "@0x7f040000").attributes["android:label"])

    def test_inventory_must_identify_the_current_commit_tree_and_exact_apk(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            apk = root / policy.APK_PATH
            apk.parent.mkdir(parents=True)
            apk.write_bytes(b"synthetic identity fixture")
            inventory_path = root / "build/inventory/runtime-inventory.json"
            inventory_path.parent.mkdir(parents=True)
            inventory = {"schema_version": 1, "scope": "resolved-runtime-classpaths",
                         "source": {"commit": "a" * 40, "tree": "b" * 40},
                         "outputs": [{"kind": "unsigned-debug-preview", "file": policy.APK_PATH,
                                      "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "bytes": apk.stat().st_size}]}
            for mutation, expected in [("commit", "INVENTORY_SOURCE_MISMATCH"),
                                       ("tree", "INVENTORY_SOURCE_MISMATCH"),
                                       ("hash", "INVENTORY_APK_MISMATCH"),
                                       ("size", "INVENTORY_APK_MISMATCH"),
                                       ("duplicate", "INVENTORY_APK_IDENTITY"),
                                       ("path", "INVENTORY_APK_IDENTITY")]:
                changed = copy.deepcopy(inventory)
                if mutation in {"commit", "tree"}:
                    changed["source"][mutation] = "c" * 40
                elif mutation == "hash":
                    changed["outputs"][0]["sha256"] = "0" * 64
                elif mutation == "size":
                    changed["outputs"][0]["bytes"] += 1
                elif mutation == "duplicate":
                    changed["outputs"].append(copy.deepcopy(changed["outputs"][0]))
                else:
                    changed["outputs"][0]["file"] = "different.apk"
                inventory_path.write_text(json.dumps(changed))
                with self.subTest(mutation=mutation), \
                     patch.object(policy, "source_identity", return_value={"commit": "a" * 40, "tree": "b" * 40}), \
                     patch.object(policy, "inspect_apk") as inspect, \
                     self.assertRaisesRegex(policy.PolicyFailure, expected):
                    policy.check(root, root / "unused-tool")
                inspect.assert_not_called()

    def test_source_changes_during_inspection_cannot_receive_a_pass(self):
        for mutation in ["none", "tracked-file", "head"]:
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                def git(*arguments):
                    return subprocess.run(["git", *arguments], cwd=root, check=True, timeout=30,
                                          stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout.decode().strip()
                git("init", "--quiet")
                (root / ".gitignore").write_text("build/\n**/build/\n")
                tracked = root / "tracked.txt"
                tracked.write_text("original\n")
                aapt2 = root / "aapt2-fixture"
                aapt2.write_bytes(b"synthetic tool identity")
                git("add", "--all")
                def commit(message):
                    git("-c", "user.name=Policy test", "-c", "user.email=policy@example.invalid",
                        "-c", "commit.gpgsign=false", "commit", "--quiet", "--no-verify", "-am", message)
                commit("Synthetic source baseline")
                source = policy.source_identity(root)
                apk = root / policy.APK_PATH
                apk.parent.mkdir(parents=True)
                apk.write_bytes(b"unchanged synthetic artifact")
                apk_hash = policy.sha256(apk)
                inventory_path = root / "build/inventory/runtime-inventory.json"
                inventory_path.parent.mkdir(parents=True)
                inventory_path.write_text(json.dumps({"schema_version": 1, "scope": "resolved-runtime-classpaths",
                    "source": source, "outputs": [{"kind": "unsigned-debug-preview", "file": policy.APK_PATH,
                    "sha256": apk_hash, "bytes": apk.stat().st_size}]}))
                inventory_hash = policy.sha256(inventory_path)
                run_command = policy.command
                def commands(arguments, cwd=None, **options):
                    if arguments == [str(aapt2), "version"]:
                        return "Android Asset Packaging Tool (aapt) 2.19-11948202"
                    return run_command(arguments, cwd, **options)
                def inspect(*_):
                    if mutation != "none":
                        tracked.write_text("changed during inspection\n")
                        if mutation == "head":
                            commit("Synthetic concurrent source advance")
                    return {"sha256": apk_hash}
                with patch.object(policy, "command", side_effect=commands), \
                     patch.object(policy, "inspect_apk", side_effect=inspect):
                    if mutation == "none":
                        self.assertEqual("passed", policy.check(root, aapt2)["status"])
                    else:
                        expected = "SOURCE_CHANGED" if mutation == "tracked-file" else "SOURCE_CHANGED_DURING_INSPECTION"
                        with self.assertRaisesRegex(policy.PolicyFailure, "^" + expected + "$"):
                            policy.check(root, aapt2)
                self.assertEqual(apk_hash, policy.sha256(apk))
                self.assertEqual(inventory_hash, policy.sha256(inventory_path))

    def test_failed_current_check_removes_previous_pass_without_disclosing_error(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            output = root / "build/inventory/preview-policy.json"
            output.parent.mkdir(parents=True)
            output.write_text('{"status":"passed","source":"old"}')
            with patch.object(policy, "ROOT", root), \
                 patch.object(sys, "argv", ["check", "--aapt2", "/unused"]), \
                 patch.object(policy, "check", side_effect=ValueError("private input sentinel")), \
                 patch("builtins.print") as printed:
                self.assertEqual(1, policy.main())
                self.assertFalse(output.exists())
                self.assertNotIn("sentinel", str(printed.call_args_list))


class InspectionSourceBytesTest(unittest.TestCase):
    def check_hidden_edit(self, flag, during_inspection):
        # Only this disposable repository receives index flags or commits. The
        # actual source helper runs Git; only the heavy APK/tool observation is mocked.
        fixture_environment = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
        fixture_environment.update({"GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull,
                                    "GIT_TERMINAL_PROMPT": "0"})
        with patch.dict(os.environ, fixture_environment, clear=True), tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            def git(*arguments):
                return subprocess.run(["git", "--no-replace-objects", *arguments], cwd=root, check=True,
                                      stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30).stdout.decode().strip()
            git("init", "--quiet", "--template=")
            (root / ".gitignore").write_text("build/\n**/build/\n")
            tracked = root / "tracked.txt"
            original = b"original synthetic source\n"
            tracked.write_bytes(original)
            aapt2 = root / "aapt2-fixture"
            aapt2.write_bytes(b"synthetic tool identity")
            git("add", "--all")
            git("-c", "user.name=Inspection source fixture", "-c", "user.email=inspection@example.invalid",
                "-c", "commit.gpgsign=false", "-c", "core.hooksPath=" + os.devnull,
                "commit", "--quiet", "--no-verify", "-m", "Disposable source baseline")
            identity = policy.source_identity(root)
            apk = root / policy.APK_PATH
            apk.parent.mkdir(parents=True)
            apk.write_bytes(b"unchanged synthetic artifact")
            apk_hash = policy.sha256(apk)
            inventory = root / "build/inventory/runtime-inventory.json"
            inventory.parent.mkdir(parents=True)
            inventory.write_text(json.dumps({"schema_version": 1, "scope": "resolved-runtime-classpaths",
                "source": identity, "outputs": [{"kind": "unsigned-debug-preview", "file": policy.APK_PATH,
                "sha256": apk_hash, "bytes": apk.stat().st_size}]}))
            inventory_hash = policy.sha256(inventory)
            git("update-index", flag, "--", "tracked.txt")
            inject = False
            def change_source():
                changed = b"private! synthetic source\n"
                self.assertEqual(len(original), len(changed))
                tracked.write_bytes(changed)
                self.assertEqual("", git("status", "--porcelain", "--untracked-files=all"))
            def inspect(*_):
                if inject:
                    change_source()
                return {"sha256": apk_hash}
            with patch.object(policy, "inspect_apk", side_effect=inspect) as inspected, \
                 patch.object(policy, "aapt2_version", return_value="Android Asset Packaging Tool (aapt) 2.19-11948202"):
                # An index hint alone is harmless; unchanged bytes must still pass.
                self.assertEqual("passed", policy.check(root, aapt2)["status"])
                inspected.reset_mock()
                if during_inspection:
                    inject = True
                else:
                    change_source()
                receipt = root / "build/inventory/preview-policy.json"
                receipt.write_text('{"status":"passed","source":"old"}\n')
                with patch.object(policy, "ROOT", root), \
                     patch.object(sys, "argv", ["inspection", "--aapt2", str(aapt2)]), \
                     redirect_stdout(io.StringIO()) as messages:
                    result = policy.main()
                new_receipt = receipt.exists()
                failure = None
                try:
                    policy.source_identity(root)
                except policy.PolicyFailure as error:
                    failure = str(error)
                negative_inspections = inspected.call_count
                self.assertEqual(apk_hash, policy.sha256(apk))
                self.assertEqual(inventory_hash, policy.sha256(inventory))
                inject = False
                tracked.write_bytes(original)
                self.assertEqual("", git("status", "--porcelain", "--untracked-files=all"))
                self.assertEqual("passed", policy.check(root, aapt2)["status"])
                self.assertEqual(identity, policy.source_identity(root))
                self.assertEqual(apk_hash, policy.sha256(apk))
                self.assertEqual(inventory_hash, policy.sha256(inventory))
                self.assertEqual(("SOURCE_CHANGED", 1, False), (failure, result, new_receipt))
                self.assertNotIn("private!", messages.getvalue())
                self.assertEqual(1 if during_inspection else 0, negative_inspections)

    def test_assume_unchanged_source_edit_before_policy_inspection_rejects(self):
        self.check_hidden_edit("--assume-unchanged", False)

    def test_skip_worktree_source_edit_before_policy_inspection_rejects(self):
        self.check_hidden_edit("--skip-worktree", False)

    def test_assume_unchanged_source_edit_during_policy_inspection_rejects(self):
        self.check_hidden_edit("--assume-unchanged", True)

    def test_skip_worktree_source_edit_during_policy_inspection_rejects(self):
        self.check_hidden_edit("--skip-worktree", True)


COMMANDS = (
    (policy, "preview-policy.json", ["--aapt2", "/unused-synthetic-tool"]),
    (environment, "environment-configuration.json", []),
)
SENTINEL = b"unrelated retained artifact\n"
PEER = b"unrelated inventory peer\n"


class InspectionReceiptTests(unittest.TestCase):
    def invoke(self, module, root, arguments, *, inspect=None):
        output = io.StringIO()
        with patch.object(module, "ROOT", root), \
             patch.object(sys, "argv", ["inspection", *arguments]), \
             patch.object(module, "check", side_effect=inspect) as checked, \
             redirect_stdout(output):
            try:
                result = module.main()
            except Exception as error:
                # An escaped exception is an observable failure, not a typed rejection.
                result = error
        self.assertNotIn("private input sentinel", output.getvalue())
        return result, checked

    def reject_inspection(self, *_):
        raise ValueError("private input sentinel")

    def test_ancestor_aliases_reject_before_inspection_or_unrelated_deletion(self):
        for module, name, arguments in COMMANDS:
            for ancestor in ("build", "build/inventory"):
                with self.subTest(command=name, ancestor=ancestor), tempfile.TemporaryDirectory() as directory:
                    root = Path(directory) / "checkout"
                    root.mkdir()
                    foreign = Path(directory) / "foreign"
                    foreign.mkdir()
                    alias = root / ancestor
                    alias.parent.mkdir(parents=True, exist_ok=True)
                    alias.symlink_to(foreign, target_is_directory=True)
                    inventory = root / "build/inventory"
                    inventory.mkdir(parents=True, exist_ok=True)
                    target = inventory / name
                    target.write_bytes(SENTINEL)
                    peer = inventory / "peer.txt"
                    peer.write_bytes(PEER)

                    result, checked = self.invoke(module, root, arguments, inspect=self.reject_inspection)

                    self.assertEqual(1, result)
                    self.assertTrue(target.is_file(), "An unrelated aliased receipt must remain")
                    self.assertEqual(SENTINEL, target.read_bytes())
                    self.assertEqual(PEER, peer.read_bytes())
                    self.assertTrue(alias.is_symlink())
                    checked.assert_not_called()

    def test_output_aliases_and_nonregular_files_reject_without_replacement(self):
        for module, name, arguments in COMMANDS:
            for kind in ("symlink", "dangling-symlink", "hardlink", "directory", "fifo"):
                with self.subTest(command=name, kind=kind), tempfile.TemporaryDirectory() as directory:
                    root = Path(directory) / "checkout"
                    inventory = root / "build/inventory"
                    inventory.mkdir(parents=True)
                    target = inventory / name
                    foreign = Path(directory) / "foreign-receipt.json"
                    if kind != "dangling-symlink":
                        foreign.write_bytes(SENTINEL)
                    if kind in {"symlink", "dangling-symlink"}:
                        target.symlink_to(foreign)
                    elif kind == "hardlink":
                        os.link(foreign, target)
                    elif kind == "directory":
                        target.mkdir()
                        (target / "retained.txt").write_bytes(SENTINEL)
                    else:
                        os.mkfifo(target)
                    before = target.lstat()
                    peer = inventory / "peer.txt"
                    peer.write_bytes(PEER)

                    result, checked = self.invoke(module, root, arguments, inspect=self.reject_inspection)

                    self.assertEqual(1, result)
                    self.assertTrue(os.path.lexists(target))
                    after = target.lstat()
                    self.assertEqual((before.st_ino, before.st_mode, before.st_nlink),
                                     (after.st_ino, after.st_mode, after.st_nlink))
                    self.assertEqual(PEER, peer.read_bytes())
                    if kind == "dangling-symlink":
                        self.assertFalse(foreign.exists())
                    else:
                        self.assertEqual(SENTINEL, foreign.read_bytes())
                    if kind == "directory":
                        self.assertEqual(SENTINEL, (target / "retained.txt").read_bytes())
                    checked.assert_not_called()

    def test_aliased_root_and_nondirectory_ancestors_are_finite_rejections(self):
        for module, name, arguments in COMMANDS:
            for kind in ("root-symlink", "build-file", "inventory-file"):
                with self.subTest(command=name, kind=kind), tempfile.TemporaryDirectory() as directory:
                    root = Path(directory) / "checkout"
                    if kind == "root-symlink":
                        foreign = Path(directory) / "foreign"
                        (foreign / "build/inventory").mkdir(parents=True)
                        target = foreign / "build/inventory" / name
                        target.write_bytes(SENTINEL)
                        root.symlink_to(foreign, target_is_directory=True)
                    else:
                        target = root / ("build" if kind == "build-file" else "build/inventory")
                        target.parent.mkdir(parents=True)
                        target.write_bytes(SENTINEL)

                    result, checked = self.invoke(module, root, arguments, inspect=self.reject_inspection)

                    self.assertEqual(1, result)
                    self.assertTrue(target.is_file())
                    self.assertEqual(SENTINEL, target.read_bytes())
                    checked.assert_not_called()

    def test_ordinary_stale_receipt_is_retired_before_failed_inspection(self):
        for module, name, arguments in COMMANDS:
            with self.subTest(command=name), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                target = root / "build/inventory" / name
                target.parent.mkdir(parents=True)
                target.write_bytes(b'{"status":"passed","source":"old"}\n')
                peer = target.parent / "peer.txt"
                peer.write_bytes(PEER)

                def fail(*_):
                    self.assertFalse(target.exists())
                    raise ValueError("private input sentinel")

                result, checked = self.invoke(module, root, arguments, inspect=fail)

                self.assertEqual(1, result)
                checked.assert_called_once()
                self.assertFalse(target.exists())
                self.assertEqual({"peer.txt"}, {path.name for path in target.parent.iterdir()})
                self.assertEqual(PEER, peer.read_bytes())

    def test_failed_inspection_does_not_create_missing_receipt_directories(self):
        for module, name, arguments in COMMANDS:
            with self.subTest(command=name), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                result, checked = self.invoke(module, root, arguments, inspect=self.reject_inspection)
                self.assertEqual(1, result)
                checked.assert_called_once()
                self.assertEqual([], list(root.iterdir()))

    def test_success_publishes_exact_json_with_private_mode_and_preserves_peers(self):
        receipt = {"status": "passed", "source": {"tree": "b" * 40, "commit": "a" * 40}}
        expected = (json.dumps(receipt, indent=2, sort_keys=True) + "\n").encode("utf-8")
        for module, name, arguments in COMMANDS:
            for previous in (False, True):
                with self.subTest(command=name, previous=previous), tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    target = root / "build/inventory" / name
                    if previous:
                        target.parent.mkdir(parents=True)
                        target.write_bytes(SENTINEL)
                        (target.parent / "peer.txt").write_bytes(PEER)

                    def inspect(*_):
                        self.assertFalse(target.exists())
                        return receipt

                    result, checked = self.invoke(module, root, arguments, inspect=inspect)

                    self.assertEqual(0, result)
                    checked.assert_called_once()
                    self.assertEqual(expected, target.read_bytes())
                    self.assertEqual(0o600, stat.S_IMODE(target.stat().st_mode))
                    self.assertEqual({name, "peer.txt"} if previous else {name},
                                     {path.name for path in target.parent.iterdir()})
                    if previous:
                        self.assertEqual(PEER, (target.parent / "peer.txt").read_bytes())

    def test_serialization_and_replace_failures_remove_only_the_temporary_file(self):
        for module, name, arguments in COMMANDS:
            for failure in ("serialization", "replace"):
                with self.subTest(command=name, failure=failure), tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    inventory = root / "build/inventory"
                    inventory.mkdir(parents=True)
                    target = inventory / name
                    target.write_bytes(SENTINEL)
                    peer = inventory / "peer.txt"
                    peer.write_bytes(PEER)
                    receipt = {"status": "passed", "value": object()} if failure == "serialization" else {"status": "passed"}
                    with patch.object(Path, "replace", side_effect=OSError("private input sentinel")) as replaced:
                        result, checked = self.invoke(module, root, arguments, inspect=lambda *_: receipt)

                    self.assertEqual(1, result)
                    checked.assert_called_once()
                    if failure == "serialization":
                        replaced.assert_not_called()
                    else:
                        replaced.assert_called_once()
                    self.assertFalse(target.exists())
                    self.assertEqual(PEER, peer.read_bytes())
                    self.assertEqual({"peer.txt"}, {path.name for path in inventory.iterdir()})

    def test_destination_rechecked_after_inspection_before_publication(self):
        for module, name, arguments in COMMANDS:
            with self.subTest(command=name), tempfile.TemporaryDirectory() as directory:
                root = Path(directory) / "checkout"
                root.mkdir()
                foreign = Path(directory) / "foreign"
                foreign.mkdir()
                target = foreign / name
                target.write_bytes(SENTINEL)
                (foreign / "peer.txt").write_bytes(PEER)

                def changed_destination(*_):
                    (root / "build").mkdir()
                    (root / "build/inventory").symlink_to(foreign, target_is_directory=True)
                    return {"status": "passed"}

                result, checked = self.invoke(module, root, arguments, inspect=changed_destination)

                self.assertEqual(1, result)
                checked.assert_called_once()
                self.assertEqual(SENTINEL, target.read_bytes())
                self.assertEqual(PEER, (foreign / "peer.txt").read_bytes())
                self.assertEqual({name, "peer.txt"}, {path.name for path in foreign.iterdir()})

    def test_environment_source_only_never_touches_an_aliased_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "checkout"
            source = root / environment.SOURCE_PATH
            source.parent.mkdir(parents=True)
            source.write_text(json.dumps(environment.EXPECTED), encoding="utf-8")
            foreign = Path(directory) / "foreign"
            (foreign / "inventory").mkdir(parents=True)
            target = foreign / "inventory/environment-configuration.json"
            target.write_bytes(SENTINEL)
            (root / "build").symlink_to(foreign, target_is_directory=True)

            result, checked = self.invoke(environment, root, ["--source-only"], inspect=self.reject_inspection)

            self.assertEqual(0, result)
            checked.assert_not_called()
            self.assertEqual(SENTINEL, target.read_bytes())
            self.assertTrue((root / "build").is_symlink())



class GuardReceiptDestinationTest(unittest.TestCase):
    commands = (
        (packaged_guards, "preview-policy-guards.json"),
        (runtime_source, "source-identity-regression.json"),
    )
    evidence_names = tuple(case + suffix for case in runtime_source.CASES
                           for suffix in (".log", ".jar", ".json"))

    def invoke(self, module, root, inspect):
        with ExitStack() as stack:
            stack.enter_context(patch.object(module, "ROOT", root))
            stack.enter_context(patch.object(sys, "argv", ["guard", "--aapt2", "/unused/aapt2",
                                                          "--android-jar", "/unused/android.jar"]))
            stack.enter_context(patch.object(subprocess, "run", side_effect=AssertionError("Native work is forbidden in destination fixtures")))
            output = stack.enter_context(redirect_stdout(io.StringIO()))
            if module is runtime_source:
                stack.enter_context(patch.object(module, "EVIDENCE", root / "build/inventory/source-identity-regression"))
                checked = stack.enter_context(patch.object(module, "check", side_effect=inspect))
                metadata = None
            else:
                metadata = stack.enter_context(patch.object(module, "command", return_value="a" * 40))
                stack.enter_context(patch.object(module, "sha256", return_value="b" * 64))
                checked = stack.enter_context(patch.object(module, "check_guards", side_effect=inspect))
            try:
                result, failure = module.main(), None
            except (Exception, SystemExit) as error:
                result, failure = None, error
            return result, failure, checked, metadata, output.getvalue()

    def assert_admission_rejected(self, module, root):
        _, failure, checked, metadata, output = self.invoke(module, root, lambda *_: {"status": "passed"})
        self.assertIsNotNone(failure)
        self.assertNotIsInstance(failure, AssertionError)
        checked.assert_not_called()
        if metadata is not None:
            metadata.assert_not_called()
        self.assertEqual("", output)

    def make_entry(self, target, foreign, kind):
        if kind != "dangling-symlink":
            foreign.write_bytes(SENTINEL)
        if kind in ("symlink", "dangling-symlink"):
            target.symlink_to(foreign)
        elif kind == "hardlink":
            os.link(foreign, target)
        elif kind == "directory":
            target.mkdir()
            (target / "retained").write_bytes(SENTINEL)
        else:
            os.mkfifo(target)
        return target.lstat()

    def assert_entry_preserved(self, target, foreign, kind, before):
        self.assertTrue(os.path.lexists(target), "An unsafe output entry must remain intact")
        after = target.lstat()
        self.assertEqual((before.st_ino, before.st_mode, before.st_nlink),
                         (after.st_ino, after.st_mode, after.st_nlink))
        if kind == "dangling-symlink":
            self.assertFalse(foreign.exists())
        else:
            self.assertEqual(SENTINEL, foreign.read_bytes())
        if kind == "directory":
            self.assertEqual(SENTINEL, (target / "retained").read_bytes())

    def test_guard_ancestor_aliases_reject_before_any_inspection_or_retirement(self):
        for module, name in self.commands:
            for ancestor in ("build", "build/inventory"):
                with self.subTest(command=name, ancestor=ancestor), tempfile.TemporaryDirectory() as temporary:
                    root, foreign = Path(temporary) / "checkout", Path(temporary) / "foreign"
                    root.mkdir()
                    foreign.mkdir()
                    alias = root / ancestor
                    alias.parent.mkdir(parents=True, exist_ok=True)
                    alias.symlink_to(foreign, target_is_directory=True)
                    inventory = root / "build/inventory"
                    inventory.mkdir(parents=True, exist_ok=True)
                    target = inventory / name
                    target.write_bytes(SENTINEL)
                    (inventory / "peer.txt").write_bytes(PEER)
                    self.assert_admission_rejected(module, root)
                    self.assertEqual(SENTINEL, target.read_bytes())
                    self.assertEqual(PEER, (inventory / "peer.txt").read_bytes())
                    self.assertTrue(alias.is_symlink())

    def test_guard_root_alias_and_nondirectory_ancestors_preserve_source_entries(self):
        for module, name in self.commands:
            for kind in ("root-alias", "build-file", "inventory-file"):
                with self.subTest(command=name, kind=kind), tempfile.TemporaryDirectory() as temporary:
                    root = Path(temporary) / "checkout"
                    if kind == "root-alias":
                        foreign = Path(temporary) / "foreign"
                        (foreign / "build/inventory").mkdir(parents=True)
                        target = foreign / "build/inventory" / name
                        root.symlink_to(foreign, target_is_directory=True)
                    else:
                        target = root / ("build" if kind == "build-file" else "build/inventory")
                        target.parent.mkdir(parents=True)
                    target.write_bytes(SENTINEL)
                    self.assert_admission_rejected(module, root)
                    self.assertEqual(SENTINEL, target.read_bytes())

    def test_guard_report_aliases_and_special_entries_remain_untouched(self):
        for module, name in self.commands:
            for kind in ("symlink", "dangling-symlink", "hardlink", "directory", "fifo"):
                with self.subTest(command=name, kind=kind), tempfile.TemporaryDirectory() as temporary:
                    root = Path(temporary) / "checkout"
                    inventory = root / "build/inventory"
                    inventory.mkdir(parents=True)
                    target, foreign = inventory / name, Path(temporary) / "foreign"
                    before = self.make_entry(target, foreign, kind)
                    self.assert_admission_rejected(module, root)
                    self.assert_entry_preserved(target, foreign, kind, before)

    def test_runtime_evidence_directory_is_admitted_before_report_retirement(self):
        for kind in ("symlink", "dangling-symlink", "file", "fifo"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary) / "checkout"
                inventory = root / "build/inventory"
                inventory.mkdir(parents=True)
                report = inventory / "source-identity-regression.json"
                report.write_bytes(SENTINEL)
                target = inventory / "source-identity-regression"
                foreign = Path(temporary) / "foreign"
                if kind in ("symlink", "dangling-symlink"):
                    if kind == "symlink":
                        foreign.mkdir()
                        (foreign / "clean-original.log").write_bytes(PEER)
                    target.symlink_to(foreign, target_is_directory=True)
                elif kind == "file":
                    target.write_bytes(PEER)
                else:
                    os.mkfifo(target)
                before = target.lstat()
                self.assert_admission_rejected(runtime_source, root)
                self.assertTrue(report.is_file(), "Late destination rejection must preserve the earlier receipt")
                self.assertEqual(SENTINEL, report.read_bytes())
                self.assertEqual((before.st_mode, before.st_ino), (target.lstat().st_mode, target.lstat().st_ino))
                if kind == "symlink":
                    self.assertEqual(PEER, (foreign / "clean-original.log").read_bytes())
                elif kind == "dangling-symlink":
                    self.assertFalse(foreign.exists())
                elif kind == "file":
                    self.assertEqual(PEER, target.read_bytes())

    def test_every_runtime_evidence_entry_is_preflighted_before_any_peer_is_retired(self):
        for name in self.evidence_names:
            for kind in ("symlink", "dangling-symlink", "hardlink", "directory", "fifo"):
                with self.subTest(name=name, kind=kind), tempfile.TemporaryDirectory() as temporary:
                    root = Path(temporary) / "checkout"
                    folder = root / "build/inventory/source-identity-regression"
                    folder.mkdir(parents=True)
                    report = folder.parent / "source-identity-regression.json"
                    report.write_bytes(SENTINEL)
                    for peer in self.evidence_names:
                        if peer != name:
                            (folder / peer).write_bytes(PEER)
                    target, foreign = folder / name, Path(temporary) / "foreign"
                    before = self.make_entry(target, foreign, kind)
                    self.assert_admission_rejected(runtime_source, root)
                    self.assertTrue(report.is_file(), "The complete set must be admitted before retirement")
                    self.assertEqual(SENTINEL, report.read_bytes())
                    self.assert_entry_preserved(target, foreign, kind, before)
                    for peer in self.evidence_names:
                        if peer != name:
                            self.assertEqual(PEER, (folder / peer).read_bytes())

    def test_ordinary_guard_outputs_retire_before_failed_inspection_and_preserve_peers(self):
        for module, name in self.commands:
            with self.subTest(command=name), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                inventory = root / "build/inventory"
                inventory.mkdir(parents=True)
                report = inventory / name
                report.write_bytes(SENTINEL)
                (inventory / "peer.txt").write_bytes(PEER)
                folder = inventory / "source-identity-regression"
                if module is runtime_source:
                    folder.mkdir()
                    for name in self.evidence_names:
                        (folder / name).write_bytes(SENTINEL)
                    (folder / "peer.txt").write_bytes(PEER)

                def failed(*_):
                    self.assertFalse(report.exists())
                    if module is runtime_source:
                        self.assertEqual({"peer.txt"}, {path.name for path in folder.iterdir()})
                        (folder / "clean-original.log").write_bytes(b"actual partial diagnostic fixture\n")
                    raise RuntimeError("CONTROLLED_INSPECTION_FAILURE")

                _, failure, checked, _, _ = self.invoke(module, root, failed)
                self.assertIsInstance(failure, RuntimeError)
                self.assertEqual("CONTROLLED_INSPECTION_FAILURE", str(failure))
                checked.assert_called_once()
                self.assertFalse(report.exists())
                self.assertEqual(PEER, (inventory / "peer.txt").read_bytes())
                if module is runtime_source:
                    self.assertEqual(PEER, (folder / "peer.txt").read_bytes())
                    self.assertEqual(b"actual partial diagnostic fixture\n", (folder / "clean-original.log").read_bytes())

    def test_guard_success_recreates_missing_output_and_publishes_complete_private_json(self):
        for module, name in self.commands:
            with self.subTest(command=name), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                value = {"status": "passed", "cases": []} if module is runtime_source else []
                result, failure, checked, _, output = self.invoke(module, root, lambda *_: value)
                self.assertIsNone(failure)
                self.assertEqual(None if module is runtime_source else 0, result)
                checked.assert_called_once()
                target = root / "build/inventory" / name
                raw = target.read_bytes()
                self.assertEqual(raw, (json.dumps(json.loads(raw), indent=2, sort_keys=True) + "\n").encode())
                self.assertEqual(0o600, stat.S_IMODE(target.stat().st_mode))
                self.assertFalse(list(target.parent.glob("." + name + ".*")))
                self.assertTrue(output.startswith("Verified" if module is runtime_source else "PASS:"))

    def test_runtime_failed_behavior_report_is_published_with_failure_exit(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            value = {"status": "failed", "cases": [{"case": "replacement-commit", "status": "failed"}]}
            _, failure, checked, _, output = self.invoke(runtime_source, root, lambda: value)
            self.assertIsInstance(failure, SystemExit)
            self.assertIn("source-identity regression failed", str(failure))
            checked.assert_called_once()
            self.assertEqual(value, json.loads((root / "build/inventory/source-identity-regression.json").read_bytes()))
            self.assertEqual("", output)

    def test_guard_serialization_or_replace_failure_cannot_leave_partial_final_json(self):
        for module, name in self.commands:
            for failure_kind in ("serialization", "replace"):
                with self.subTest(command=name, failure=failure_kind), tempfile.TemporaryDirectory() as temporary:
                    root = Path(temporary)
                    inventory = root / "build/inventory"
                    inventory.mkdir(parents=True)
                    target = inventory / name
                    target.write_bytes(SENTINEL)
                    (inventory / "peer.txt").write_bytes(PEER)
                    value = {"status": "passed", "cases": [object()] if failure_kind == "serialization" else []}
                    returned = value if module is runtime_source else value["cases"]
                    with patch.object(Path, "replace", side_effect=OSError("CONTROLLED_PUBLICATION_FAILURE")) as replaced:
                        _, failure, checked, _, output = self.invoke(module, root, lambda *_: returned)
                    self.assertIsInstance(failure, TypeError if failure_kind == "serialization" else OSError)
                    checked.assert_called_once()
                    if failure_kind == "serialization":
                        replaced.assert_not_called()
                    else:
                        replaced.assert_called_once()
                    self.assertFalse(target.exists())
                    self.assertEqual(PEER, (inventory / "peer.txt").read_bytes())
                    self.assertFalse(list(inventory.glob("." + name + ".*")))
                    self.assertEqual("", output)


class IgnoredInputReceiptTests(unittest.TestCase):
    make_entry = GuardReceiptDestinationTest.make_entry
    assert_entry_preserved = GuardReceiptDestinationTest.assert_entry_preserved

    def invoke(self, root, preview, inspect):
        with patch.object(ignored_guards, "ROOT", root), \
             patch.object(sys, "argv", ["ignored-input-guard"] + (["--preview"] if preview else [])), \
             patch.object(ignored_guards, "check_guard", side_effect=inspect) as checked, \
             patch.object(subprocess, "run", side_effect=AssertionError("Native work is forbidden in destination fixtures")), \
             redirect_stdout(io.StringIO()) as output:
            try:
                result, failure = ignored_guards.main(), None
            except (Exception, SystemExit) as error:
                result, failure = None, error
            return result, failure, checked, output.getvalue()

    def assert_admission_rejected(self, root, preview):
        def unexpected(_):
            raise RuntimeError("CONTROLLED_INSPECTION_MUST_NOT_RUN")

        _, failure, checked, output = self.invoke(root, preview, unexpected)
        self.assertIsNotNone(failure)
        self.assertNotIsInstance(failure, AssertionError)
        checked.assert_not_called()
        self.assertEqual("", output)

    def test_ignored_input_ancestors_are_admitted_before_inspection(self):
        for preview in (False, True):
            name = "guard-ignored-preview-asset.json" if preview else "guard-ignored-core-schema.json"
            for kind in ("root-alias", "build-alias", "inventory-alias", "build-file", "inventory-file"):
                with self.subTest(preview=preview, kind=kind), tempfile.TemporaryDirectory() as temporary:
                    root, foreign = Path(temporary) / "checkout", Path(temporary) / "foreign"
                    foreign.mkdir()
                    if kind == "root-alias":
                        root.symlink_to(foreign, target_is_directory=True)
                    else:
                        root.mkdir()
                        entry = root / ("build" if kind.startswith("build-") else "build/inventory")
                        entry.parent.mkdir(parents=True, exist_ok=True)
                        if kind.endswith("-alias"):
                            entry.symlink_to(foreign, target_is_directory=True)
                        else:
                            entry.write_bytes(SENTINEL)
                    if kind.endswith("-alias"):
                        target = root / "build/inventory" / name
                        target.parent.mkdir(parents=True, exist_ok=True)
                        target.write_bytes(SENTINEL)
                        (target.parent / "peer.txt").write_bytes(PEER)
                    else:
                        target = entry
                    before = target.lstat()
                    self.assert_admission_rejected(root, preview)
                    self.assertEqual(SENTINEL, target.read_bytes())
                    self.assertEqual((before.st_ino, before.st_mode), (target.lstat().st_ino, target.lstat().st_mode))
                    if kind.endswith("-alias"):
                        self.assertEqual(PEER, (target.parent / "peer.txt").read_bytes())

    def test_ignored_input_output_aliases_and_special_entries_are_preserved(self):
        for preview in (False, True):
            name = "guard-ignored-preview-asset.json" if preview else "guard-ignored-core-schema.json"
            for kind in ("symlink", "dangling-symlink", "hardlink", "directory", "fifo"):
                with self.subTest(preview=preview, kind=kind), tempfile.TemporaryDirectory() as temporary:
                    root = Path(temporary) / "checkout"
                    inventory = root / "build/inventory"
                    inventory.mkdir(parents=True)
                    target, foreign = inventory / name, Path(temporary) / "foreign"
                    before = self.make_entry(target, foreign, kind)
                    (inventory / "peer.txt").write_bytes(PEER)
                    self.assert_admission_rejected(root, preview)
                    self.assert_entry_preserved(target, foreign, kind, before)
                    self.assertEqual(PEER, (inventory / "peer.txt").read_bytes())

    def test_ignored_input_failure_retires_only_selected_stale_report(self):
        for preview in (False, True):
            for existing in (False, True):
                with self.subTest(preview=preview, existing=existing), tempfile.TemporaryDirectory() as temporary:
                    root = Path(temporary)
                    inventory = root / "build/inventory"
                    name = "guard-ignored-preview-asset.json" if preview else "guard-ignored-core-schema.json"
                    other = "guard-ignored-core-schema.json" if preview else "guard-ignored-preview-asset.json"
                    target = inventory / name
                    if existing:
                        inventory.mkdir(parents=True)
                        target.write_bytes(SENTINEL)
                        (inventory / other).write_bytes(PEER)

                    def failed(selected):
                        self.assertIs(preview, selected)
                        self.assertFalse(target.exists())
                        raise RuntimeError("CONTROLLED_INSPECTION_FAILURE")

                    _, failure, checked, output = self.invoke(root, preview, failed)
                    self.assertIsInstance(failure, RuntimeError)
                    self.assertEqual("CONTROLLED_INSPECTION_FAILURE", str(failure))
                    checked.assert_called_once_with(preview)
                    self.assertFalse(target.exists())
                    self.assertEqual("", output)
                    if existing:
                        self.assertEqual(PEER, (inventory / other).read_bytes())
                    else:
                        self.assertEqual([], list(root.iterdir()))

    def test_ignored_input_success_publishes_exact_private_report(self):
        for preview in (False, True):
            with self.subTest(preview=preview), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                case = "ignored-preview-asset" if preview else "ignored-core-schema"
                report = {"case": case, "status": "passed", "scope": "controlled boundary fixture"}
                result, failure, checked, output = self.invoke(root, preview, lambda selected: report)
                self.assertIsNone(result)
                self.assertIsNone(failure)
                checked.assert_called_once_with(preview)
                target = root / "build/inventory" / ("guard-" + case + ".json")
                self.assertEqual((json.dumps(report, indent=2, sort_keys=True) + "\n").encode(), target.read_bytes())
                self.assertEqual(0o600, stat.S_IMODE(target.stat().st_mode))
                self.assertEqual([target], list(target.parent.iterdir()))
                self.assertEqual(f"Verified {case}: real packaged input rejected; no fresh inventory\n", output)

    def test_ignored_input_publication_failure_leaves_no_partial_final_report(self):
        for preview in (False, True):
            for failure_kind in ("serialization", "replace"):
                with self.subTest(preview=preview, failure=failure_kind), tempfile.TemporaryDirectory() as temporary:
                    root = Path(temporary)
                    inventory = root / "build/inventory"
                    inventory.mkdir(parents=True)
                    case = "ignored-preview-asset" if preview else "ignored-core-schema"
                    target = inventory / ("guard-" + case + ".json")
                    target.write_bytes(SENTINEL)
                    (inventory / "peer.txt").write_bytes(PEER)
                    report = {"case": case, "status": "passed", "value": object() if failure_kind == "serialization" else "complete"}
                    with patch.object(Path, "replace", side_effect=OSError("CONTROLLED_PUBLICATION_FAILURE")) as replaced:
                        _, failure, checked, output = self.invoke(root, preview, lambda selected: report)
                    self.assertIsInstance(failure, TypeError if failure_kind == "serialization" else OSError)
                    checked.assert_called_once_with(preview)
                    if failure_kind == "serialization":
                        replaced.assert_not_called()
                    else:
                        replaced.assert_called_once()
                    self.assertFalse(target.exists())
                    self.assertEqual([inventory / "peer.txt"], list(inventory.iterdir()))
                    self.assertEqual(PEER, (inventory / "peer.txt").read_bytes())
                    self.assertEqual("", output)


if __name__ == "__main__":
    unittest.main()
